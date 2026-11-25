package dev.locus.app;

import android.os.Handler;
import android.os.Looper;

import com.termux.terminal.TerminalSession;

import dev.locus.app.DiagnosticLog.Outcome;
import dev.locus.app.DiagnosticLog.Stage;

import java.io.IOException;
import java.util.Arrays;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.Semaphore;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/** Live PTY and terminal emulator. State and listeners run on main, blocking I/O on workers. */
final class WorkspaceTerminal {
    interface Listener {
        void screenChanged();
        void ended(End end);
        void writeFailed(Exception failure);
        void sendQueueFull();
        void inputTooLarge();
    }

    static final class End {
        final int exitCode;
        final Exception failure;

        End(int exitCode, Exception failure) {
            this.exitCode = exitCode;
            this.failure = failure;
        }
    }

    private static final int INPUT_LIMIT = 64 * 1024;

    final TerminalSession session;
    private final RemoteConnection.Terminal channel;
    private final DiagnosticLog.Trace trace;
    private final int windowId;
    private final ExecutorService io;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ThreadPoolExecutor writer = new ThreadPoolExecutor(
            1, 1, 0, TimeUnit.SECONDS, new ArrayBlockingQueue<>(64));

    private Future<?> reader;
    private Listener listener;
    private End end;
    private volatile boolean closed;
    private volatile long resizeGeneration;
    private boolean writeFailed;

    WorkspaceTerminal(RemoteConnection.Terminal channel, DiagnosticLog.Trace trace,
                      int windowId, ExecutorService io, LocusTerminalView.Size size,
                      int cellWidth, int cellHeight) {
        this.channel = channel;
        this.trace = trace;
        this.windowId = windowId;
        this.io = io;
        session = new TerminalSession(size.columns, size.rows, cellWidth, cellHeight, this::write);
    }

    void start() {
        record(Stage.PTY_STREAM, Outcome.READY);
        reader = io.submit(this::read);
    }

    boolean isOpen() {
        return !closed && channel.isOpen();
    }

    End end() {
        return end;
    }

    void attachListener(Listener listener) {
        if (closed) return;
        if (this.listener != listener) invalidateResize();
        this.listener = listener;
    }

    void detachListener(Listener listener) {
        if (this.listener == listener) {
            this.listener = null;
            invalidateResize();
        }
    }

    void invalidateResize() {
        resizeGeneration++;
    }

    void resize(LocusTerminalView.Size size) {
        long generation = ++resizeGeneration;
        send(() -> {
            if (generation == resizeGeneration) {
                channel.resize(size.columns, size.rows, size.widthPx, size.heightPx);
            }
        });
    }

    private void write(byte[] data) {
        if (data.length > INPUT_LIMIT) {
            if (listener != null) listener.inputTooLarge();
            return;
        }
        send(() -> channel.write(data));
    }

    private void send(TerminalOperation operation) {
        if (closed) return;
        try {
            writer.execute(() -> {
                if (closed) return;
                try {
                    operation.run();
                    writeFailed = false;
                } catch (Exception failure) {
                    if (!closed && !writeFailed) {
                        record(Stage.PTY_WRITE, Outcome.IO_ERROR);
                        writeFailed = true;
                    }
                    main.post(() -> {
                        if (!closed && listener != null) listener.writeFailed(failure);
                    });
                }
            });
        } catch (RejectedExecutionException saturated) {
            if (!closed && listener != null) listener.sendQueueFull();
        }
    }

    private void read() {
        byte[] buffer = new byte[16384];
        Semaphore pending = new Semaphore(4);
        try {
            int length;
            while ((length = channel.read(buffer)) != -1) {
                if (length == 0) continue;
                pending.acquire();
                byte[] bytes = Arrays.copyOf(buffer, length);
                main.post(() -> {
                    try {
                        if (!closed) {
                            session.append(bytes, 0, bytes.length);
                            if (listener != null) listener.screenChanged();
                        }
                    } finally {
                        pending.release();
                    }
                });
            }
            if (!closed) record(Stage.PTY_STREAM, Outcome.EOF);
            int exitCode = channel.awaitExitStatus();
            if (!closed && exitCode >= 0) record(Stage.PTY_STREAM, Outcome.EXITED);
            main.post(() -> finish(new End(exitCode, null)));
        } catch (InterruptedException canceled) {
            Thread.currentThread().interrupt();
        } catch (Exception failure) {
            if (!closed) record(Stage.PTY_STREAM, Outcome.IO_ERROR);
            main.post(() -> finish(new End(-1, failure)));
        }
    }

    private void finish(End end) {
        if (closed) return;
        this.end = end;
        Listener finished = listener;
        close(null);
        if (finished != null) finished.ended(end);
    }

    void close(Outcome outcome) {
        if (closed) return;
        closed = true;
        listener = null;
        if (outcome != null) record(Stage.PTY_STREAM, outcome);
        if (reader != null) reader.cancel(true);
        writer.shutdownNow();
        io.execute(channel::close);
    }

    private void record(Stage stage, Outcome outcome) {
        if (trace != null) trace.record(stage, outcome, windowId);
    }

    private interface TerminalOperation {
        void run() throws IOException;
    }
}
