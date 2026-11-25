package dev.locus.app;

import android.os.Handler;
import android.os.Looper;

import dev.locus.app.DiagnosticLog.Outcome;
import dev.locus.app.DiagnosticLog.Stage;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;

/** Process-local state owned by the workspace service. UI and terminal state are accessed on main. */
final class WorkspaceSession {
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService io = Executors.newCachedThreadPool();
    private final Map<Integer, WorkspaceTerminal> terminals = new HashMap<>();
    private final LocusApplication app;
    private Object pending;
    private RemoteConnection connection;
    private String notificationExitToken;
    private String endpoint;
    private String username;
    private DiagnosticLog.Trace trace;
    private Consumer<Outcome> listener;
    private LayoutState layout;

    WorkspaceSession(LocusApplication app) {
        this.app = app;
    }

    ExecutorService executor() { return io; }
    RemoteConnection connection() { return connection; }
    String notificationExitToken() { return notificationExitToken; }
    String endpoint() { return endpoint; }
    String username() { return username; }
    DiagnosticLog.Trace trace() { return trace; }

    Object begin() {
        pending = new Object();
        return pending;
    }

    boolean connected(Object attempt, RemoteConnection result, String endpoint, String username,
                      DiagnosticLog.Trace trace) {
        if (attempt != pending) {
            io.execute(result::close);
            return false;
        }
        pending = null;
        connection = result;
        this.endpoint = endpoint;
        this.username = username;
        this.trace = trace;
        if (checkTransport()) return false;
        notificationExitToken = UUID.randomUUID().toString();
        trace.record(Stage.SESSION, Outcome.READY);
        WorkspaceService.refreshNotification(app);
        return true;
    }

    void cancel(Object attempt) {
        if (attempt == null || attempt != pending) return;
        pending = null;
        WorkspaceService.stop(app);
    }

    void disconnected() {
        if (connection != null) closeConnection(Outcome.DISCONNECTED);
    }

    boolean checkTransport() {
        if (connection == null || connection.isOpen()) return false;
        closeConnection(Outcome.TRANSPORT_LOST);
        return true;
    }

    // SshConnection reports termination from its own worker.
    void transportEnded(RemoteConnection ended) {
        main.post(() -> {
            if (connection == ended) closeConnection(Outcome.TRANSPORT_LOST);
        });
    }

    private void closeConnection(Outcome outcome) {
        RemoteConnection previous = connection;
        connection = null;
        notificationExitToken = null;
        endpoint = null;
        username = null;
        if (trace != null) trace.record(Stage.SESSION, outcome);
        trace = null;
        List<WorkspaceTerminal> active = new ArrayList<>(terminals.values());
        terminals.clear();
        Outcome terminalOutcome = outcome == Outcome.TRANSPORT_LOST ? outcome : Outcome.CANCELLED;
        for (WorkspaceTerminal terminal : active) terminal.close(terminalOutcome);
        io.execute(previous::close);
        WorkspaceService.stop(app);
        if (listener != null) listener.accept(outcome);
    }

    void observe(Consumer<Outcome> listener) { this.listener = listener; }

    void removeObserver(Consumer<Outcome> listener) {
        if (this.listener == listener) this.listener = null;
    }

    WorkspaceTerminal terminal(int id) { return terminals.get(id); }

    void putTerminal(int id, WorkspaceTerminal terminal) { terminals.put(id, terminal); }

    void removeTerminal(int id, WorkspaceTerminal expected) { terminals.remove(id, expected); }

    void saveLayout(LayoutState layout) { this.layout = layout; }

    LayoutState takeLayout() {
        LayoutState saved = layout;
        layout = null;
        return saved;
    }

    static final class LayoutState {
        final PaneDock.Layout dock;
        final Map<Integer, WorkspacePane.State> visible;
        final Map<Integer, WorkspacePane.State> minimized;
        final List<Integer> minimizedByLayout;
        final int activeId;
        final int nextId;

        LayoutState(PaneDock.Layout dock, Map<Integer, WorkspacePane.State> visible,
                    Map<Integer, WorkspacePane.State> minimized, List<Integer> minimizedByLayout,
                    int activeId, int nextId) {
            this.dock = dock;
            this.visible = visible;
            this.minimized = minimized;
            this.minimizedByLayout = minimizedByLayout;
            this.activeId = activeId;
            this.nextId = nextId;
        }
    }
}
