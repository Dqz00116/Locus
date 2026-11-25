package dev.locus.app;

import android.app.Activity;
import android.app.Instrumentation;
import android.app.Notification;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.UiAutomation;
import android.content.ComponentName;
import android.content.Context;
import android.content.ContextWrapper;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.os.Bundle;
import android.os.SystemClock;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputConnection;
import android.widget.FrameLayout;
import android.widget.ListView;
import android.widget.ScrollView;
import android.widget.TextView;

import com.termux.terminal.TerminalEmulator;

import java.io.ByteArrayOutputStream;
import java.io.InterruptedIOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;

/** Exercises the Android view's byte contract without an SSH host or installed Chinese IME. */
public final class TerminalSmoke extends Instrumentation {
    private static final String ESC = "\u001b";
    private static final String PREVIEW_TEXT = "preview retained through recreation";

    @Override public void onCreate(Bundle arguments) {
        super.onCreate(arguments);
        start();
    }

    @Override public void onStart() {
        super.onStart();
        Bundle result = new Bundle();
        Activity activity = null;
        try {
            checkKeyboardConfiguration();
            checkFavoriteLayouts();
            Intent intent = new Intent(getTargetContext(), MainActivity.class)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            activity = startActivitySync(intent);
            activity = checkWorkspaceRecreation((MainActivity) activity);
            Activity host = activity;
            Fixture fixture = new Fixture();
            onUi(() -> fixture.install(host));
            waitForIdleSync();
            onUi(fixture::exerciseComposition);
            waitForIdleSync();
            onUi(fixture::exerciseSecondTerminal);
            onUi(fixture::exerciseRenderingAndResize);
            result.putString("stream", "\nPASS: local composition, UTF-8 commit, cancellation, focus, control keys,"
                    + " bracketed paste, CJK rendering, independent resize, keyboard configuration"
                    + ", favorite layout directories and foreground workspace Activity recreation.\n");
            finish(Activity.RESULT_OK, result);
        } catch (Throwable error) {
            StringWriter trace = new StringWriter();
            error.printStackTrace(new PrintWriter(trace));
            result.putString("stream", "\nFAIL: " + trace + "\n");
            finish(Activity.RESULT_CANCELED, result);
        } finally {
            if (activity != null) activity.finish();
        }
    }

    private void checkKeyboardConfiguration() throws PackageManager.NameNotFoundException {
        int keyboardChanges = ActivityInfo.CONFIG_KEYBOARD | ActivityInfo.CONFIG_KEYBOARD_HIDDEN
                | ActivityInfo.CONFIG_NAVIGATION;
        ActivityInfo workspace = getTargetContext().getPackageManager().getActivityInfo(
                new ComponentName(getTargetContext(), MainActivity.class), 0);
        require((workspace.configChanges & keyboardChanges) == keyboardChanges,
                "keyboard and navigation changes must preserve the workspace Activity");
        require(workspace.launchMode == ActivityInfo.LAUNCH_SINGLE_TASK,
                "the launcher and service notification must reuse one workspace Activity");
    }

    private MainActivity checkWorkspaceRecreation(MainActivity original)
            throws InterruptedException, PendingIntent.CanceledException {
        SharedPreferences preferences = original.getSharedPreferences("connection", Context.MODE_PRIVATE);
        String previousEndpoint = preferences.getString("directoryEndpoint", null);
        String previousUser = preferences.getString("directoryUser", null);
        String previousDirectory = preferences.getString("directory", null);
        String notificationKey = MainActivity.WORKSPACE_NOTIFICATIONS_REQUESTED;
        boolean hadNotificationChoice = preferences.contains(notificationKey);
        boolean previousNotificationChoice = preferences.getBoolean(notificationKey, false);
        LocusApplication app = (LocusApplication) original.getApplication();
        WorkspaceSession session = original.workspace();
        LocalConnection connection = new LocalConnection();
        AtomicBoolean started = new AtomicBoolean();
        AtomicReference<WorkspaceService> service = new AtomicReference<>();
        AtomicReference<TerminalEmulator> emulator = new AtomicReference<>();
        MainActivity restored = null;
        boolean passed = false;
        try {
            onUi(() -> {
                require(session.connection() == null && !app.foregroundWorkspaceRequested,
                        "the workspace fixture starts from a disconnected Activity");
                WorkspacePane pane = find(original.getWindow().getDecorView(), WorkspacePane.class);
                require(pane != null, "workspace has a real pane");
                preferences.edit().putBoolean(notificationKey, true).apply();
                started.set(WorkspaceService.start(original));
                require(started.get(), "visible Activity starts the workspace service");
                DiagnosticLog.Trace trace = DiagnosticLog.get(original).begin(DiagnosticLog.Route.DIRECT,
                        DiagnosticLog.Auth.KEY);
                require(session.connected(session.begin(), connection, "[workspace.test]:22", "smoke", trace),
                        "fixture connected through the workspace session");
                pane.open(connection, "/smoke");
            });
            connection.pty.feed("before recreation\r\n");
            awaitUi(() -> app.foregroundWorkspace != null && transcript(original).contains("before recreation"),
                    "service promoted and initial PTY output rendered");
            boolean expectsNotification = WorkspaceService.notificationsEnabled(original);
            if (expectsNotification) {
                awaitUi(() -> hasForegroundNotification(original), "workspace notification visible when allowed");
            }
            onUi(() -> {
                service.set(app.foregroundWorkspace);
                emulator.set(find(original.getWindow().getDecorView(), LocusTerminalView.class).mEmulator);
                switchWorkspacePresentation(original);
            });
            awaitUi(() -> {
                ListView files = find(original.getWindow().getDecorView(), ListView.class);
                return files != null && files.getCount() == 1 && files.getChildCount() == 1;
            }, "workspace browser lists the fixture file");
            onUi(() -> {
                ListView files = find(original.getWindow().getDecorView(), ListView.class);
                require(files.performItemClick(files.getChildAt(0), 0, files.getItemIdAtPosition(0)),
                        "fixture file selected from the real listing");
            });
            awaitUi(() -> showsPreview(original), "fixture file preview loaded");

            ActivityMonitor monitor = addMonitor(MainActivity.class.getName(), null, false);
            try {
                onUi(original::recreate);
                connection.pty.feed("across recreation\r\n");
                restored = (MainActivity) waitForMonitorWithTimeout(monitor, 5_000);
                require(restored != null && restored != original, "Activity was recreated");
            } finally {
                removeMonitor(monitor);
            }

            MainActivity host = restored;
            awaitUi(() -> showsPreview(host), "file preview remains visible after Activity recreation");
            onUi(() -> {
                require(connection.lists.get() == 1 && connection.reads.get() == 1,
                        "completed directory and preview restore without new SFTP requests");
                switchWorkspacePresentation(host);
            });
            awaitUi(() -> transcript(host).contains("across recreation"), "restored view renders retained PTY output");
            onUi(() -> {
                require(host.workspace() == session && session.connection() == connection
                        && app.foregroundWorkspace == service.get(), "transport and foreground service retained");
                LocusTerminalView terminal = find(host.getWindow().getDecorView(), LocusTerminalView.class);
                require(terminal.mEmulator == emulator.get() && transcript(host).contains("before recreation"),
                        "original emulator and transcript retained");
                require(connection.opens.get() == 1, "no replacement PTY opened");
                terminal.requestFocus();
            });
            awaitUi(() -> {
                LocusTerminalView terminal = find(host.getWindow().getDecorView(), LocusTerminalView.class);
                return terminal != null && terminal.isFocused() && terminal.hasWindowFocus();
            }, "restored terminal regained input focus");
            onUi(() -> {
                LocusTerminalView terminal = find(host.getWindow().getDecorView(), LocusTerminalView.class);
                require(terminal.onCreateInputConnection(new EditorInfo()).commitText("继续", 1),
                        "restored terminal accepts IME commit");
            });
            require(Arrays.equals("继续".getBytes(StandardCharsets.UTF_8), connection.pty.writes.poll(5, TimeUnit.SECONDS)),
                    "IME bytes reached the original PTY");
            if (expectsNotification) checkNotificationActions(host, connection);
            onUi(session::disconnected);
            awaitUi(() -> !connection.isOpen() && !connection.pty.isOpen() && app.foregroundWorkspace == null,
                    "disconnect releases the transport, PTY and foreground service");
            if (expectsNotification) {
                awaitUi(() -> !hasForegroundNotification(host), "disconnect removes the workspace notification");
            }
            passed = true;
            return host;
        } finally {
            onUi(() -> {
                if (session.connection() == connection) session.disconnected();
                else if (started.get() && app.foregroundWorkspaceRequested) WorkspaceService.stop(original);
            });
            connection.close();
            SharedPreferences.Editor restore = preferences.edit().putString("directoryEndpoint", previousEndpoint)
                    .putString("directoryUser", previousUser).putString("directory", previousDirectory);
            if (hadNotificationChoice) restore.putBoolean(notificationKey, previousNotificationChoice);
            else restore.remove(notificationKey);
            restore.commit();
            if (!passed && restored != null && restored != original) restored.finish();
        }
    }

    private static boolean hasForegroundNotification(Context context) {
        return foregroundNotification(context) != null;
    }

    private static Notification foregroundNotification(Context context) {
        NotificationManager notifications = context.getSystemService(NotificationManager.class);
        return Arrays.stream(notifications.getActiveNotifications())
                .map(record -> record.getNotification())
                .filter(notification -> (notification.flags & Notification.FLAG_FOREGROUND_SERVICE) != 0)
                .findFirst().orElse(null);
    }

    private void checkNotificationActions(MainActivity host, LocalConnection connection)
            throws PendingIntent.CanceledException {
        Notification notification = foregroundNotification(host);
        require(notification != null && notification.actions != null, "workspace notification has actions");
        PendingIntent returnAction = findNotificationAction(notification,
                host.getString(R.string.workspace_notification_return));
        PendingIntent exitAction = findNotificationAction(notification,
                host.getString(R.string.workspace_notification_exit));
        require(returnAction.equals(notification.contentIntent) && !returnAction.equals(exitAction),
                "Return and the notification body share an action distinct from Exit");

        Activity about = startActivitySync(new Intent(host, AboutActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        try {
            awaitUi(about::hasWindowFocus, "about screen covers the workspace");
            returnAction.send();
            awaitUi(host::hasWindowFocus, "Return brings the original workspace Activity forward");
            onUi(() -> require(host.workspace().connection() == connection && connection.opens.get() == 1,
                    "Return retains the connection and terminal"));
        } finally {
            if (!about.isFinishing()) onUi(about::finish);
        }

        UiAutomation automation = getUiAutomation();
        exitAction.send();
        String cancelLabel = host.getString(R.string.action_cancel);
        String confirmLabel = host.getString(R.string.connection_disconnect_confirm);
        long deadline = SystemClock.elapsedRealtime() + 5_000;
        do {
            AccessibilityNodeInfo root = automation.getRootInActiveWindow();
            if (root != null && host.getPackageName().equals(String.valueOf(root.getPackageName()))
                    && !root.findAccessibilityNodeInfosByText(confirmLabel).isEmpty()) {
                for (AccessibilityNodeInfo button : root.findAccessibilityNodeInfosByText(cancelLabel)) {
                    if (button.isClickable() && button.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
                        awaitUi(host::hasWindowFocus, "cancel closes the disconnect confirmation");
                        onUi(() -> require(host.workspace().connection() == connection && connection.isOpen(),
                                "canceling Exit leaves the connection open"));
                        return;
                    }
                }
            }
            SystemClock.sleep(25);
        } while (SystemClock.elapsedRealtime() < deadline);
        throw new AssertionError("Exit offers the disconnect confirmation with a Cancel action");
    }

    private static PendingIntent findNotificationAction(Notification notification, String title) {
        for (Notification.Action action : notification.actions) {
            if (title.contentEquals(action.title)) return action.actionIntent;
        }
        throw new AssertionError("Missing notification action: " + title);
    }

    private void awaitUi(BooleanSupplier condition, String message) {
        long deadline = SystemClock.elapsedRealtime() + 5_000;
        AtomicBoolean ready = new AtomicBoolean();
        do {
            onUi(() -> ready.set(condition.getAsBoolean()));
            if (ready.get()) return;
            SystemClock.sleep(25);
        } while (SystemClock.elapsedRealtime() < deadline);
        throw new AssertionError(message);
    }

    private static String transcript(Activity activity) {
        LocusTerminalView terminal = find(activity.getWindow().getDecorView(), LocusTerminalView.class);
        return terminal == null || terminal.mEmulator == null ? ""
                : terminal.mEmulator.getScreen().getTranscriptText();
    }

    private static boolean showsPreview(Activity activity) {
        RemoteBrowserView browser = find(activity.getWindow().getDecorView(), RemoteBrowserView.class);
        if (browser == null || !browser.isShown()) return false;
        ScrollView preview = find(browser, ScrollView.class);
        TextView text = preview == null ? null : find(preview, TextView.class);
        return text != null && PREVIEW_TEXT.contentEquals(text.getText());
    }

    private static void switchWorkspacePresentation(Activity activity) {
        WorkspacePane pane = find(activity.getWindow().getDecorView(), WorkspacePane.class);
        require(pane != null, "workspace pane exists when switching presentation");
        try {
            // Invoke the window menu action without relying on popup coordinates or timing.
            java.lang.reflect.Method action = WorkspacePane.class.getDeclaredMethod("toggleBrowser");
            action.setAccessible(true);
            action.invoke(pane);
        } catch (ReflectiveOperationException failure) {
            throw new AssertionError("workspace presentation switched", failure);
        }
    }

    private static <T extends View> T find(View view, Class<T> type) {
        if (type.isInstance(view)) return type.cast(view);
        if (view instanceof ViewGroup) {
            ViewGroup container = (ViewGroup) view;
            for (int index = 0; index < container.getChildCount(); index++) {
                T found = find(container.getChildAt(index), type);
                if (found != null) return found;
            }
        }
        return null;
    }

    private static final class LocalConnection implements RemoteConnection {
        final AtomicInteger opens = new AtomicInteger();
        final AtomicInteger lists = new AtomicInteger();
        final AtomicInteger reads = new AtomicInteger();
        final LocalTerminal pty = new LocalTerminal();
        private volatile boolean open = true;

        @Override public boolean isOpen() { return open; }
        @Override public String canonicalDirectory(String path) { return path; }
        @Override public List<FileEntry> list(String path) {
            lists.incrementAndGet();
            return Collections.singletonList(new FileEntry("notes.txt", false, true, false, PREVIEW_TEXT.length()));
        }

        @Override public byte[] read(String path, int maxBytes) {
            reads.incrementAndGet();
            return PREVIEW_TEXT.getBytes(StandardCharsets.UTF_8);
        }

        @Override public Terminal openTerminal(String path, int cols, int rows, int width, int height) {
            opens.incrementAndGet();
            return pty;
        }

        @Override public void close() {
            open = false;
            pty.close();
        }
    }

    private static final class LocalTerminal implements RemoteConnection.Terminal {
        private final BlockingQueue<byte[]> output = new LinkedBlockingQueue<>();
        final BlockingQueue<byte[]> writes = new LinkedBlockingQueue<>();
        private final AtomicBoolean open = new AtomicBoolean(true);

        void feed(String text) { output.add(text.getBytes(StandardCharsets.UTF_8)); }

        @Override public int read(byte[] bytes) throws InterruptedIOException {
            try {
                byte[] chunk = output.take();
                if (chunk.length == 0) return -1;
                System.arraycopy(chunk, 0, bytes, 0, chunk.length);
                return chunk.length;
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new InterruptedIOException();
            }
        }

        @Override public void write(byte[] bytes) { writes.add(bytes.clone()); }
        @Override public void resize(int cols, int rows, int width, int height) {}
        @Override public boolean isOpen() { return open.get(); }
        @Override public int awaitExitStatus() { return -1; }

        @Override public void close() {
            if (open.getAndSet(false)) output.add(new byte[0]);
        }
    }

    private void checkFavoriteLayouts() {
        Context context = getTargetContext();
        String name = "favorite_layouts_smoke_" + System.nanoTime();
        SharedPreferences isolated = context.getSharedPreferences(name, Context.MODE_PRIVATE);
        Context fixture = new ContextWrapper(context) {
            @Override public SharedPreferences getSharedPreferences(String ignored, int mode) {
                return isolated;
            }
        };
        String shape = "1,h0.5,p,p";
        FavoriteLayouts.Directory first = new FavoriteLayouts.Directory("[example.test]:22", "dev",
                "/home/dev/项目", "/home/dev/项目/src");
        FavoriteLayouts.Directory second = new FavoriteLayouts.Directory("[example.test]:22", "dev", "/tmp", null);
        List<FavoriteLayouts.Directory> directories = Arrays.asList(first, second);
        try {
            require(isolated.edit().putString("layouts",
                    "{\"nextId\":2,\"entries\":[{\"id\":1,\"snapshot\":\"1,h0.5,p,p\"}]}").commit(),
                    "legacy favorite fixture written");
            FavoriteLayouts layouts = new FavoriteLayouts(fixture);
            require(layouts.entries().get(0).directories.isEmpty(), "legacy geometry-only favorite loaded");
            require(layouts.add(shape, directories), "same geometry with directories adds a favorite");
            require(!layouts.add(shape, directories), "same geometry and ordered directories deduplicated");
            require(layouts.add(shape, Arrays.asList(second, first)), "different order adds a favorite");
            require(layouts.add(shape, Arrays.asList(first, null)), "blank pane can be saved");
            require(!layouts.add(shape, Collections.singletonList(first)), "different pane count rejected");

            List<FavoriteLayouts.Entry> reloaded = new FavoriteLayouts(fixture).entries();
            require(reloaded.size() == 4 && reloaded.get(1).directories.equals(directories),
                    "selection and browser folder persist in pane order");
            require(reloaded.get(3).directories.get(1) == null, "blank pane persists");
            require(!reloaded.get(1).directories.get(0).matchesConnection("[other.test]:22", "dev")
                    && !reloaded.get(1).directories.get(0).matchesConnection("[example.test]:22", "other"),
                    "favorite paths are bound to host and account");
            layouts.remove(reloaded.get(1));
            require(new FavoriteLayouts(fixture).entries().size() == 3, "favorite removal persists");
        } finally {
            isolated.edit().clear().commit();
            context.deleteSharedPreferences(name);
        }
    }

    private void onUi(Runnable test) {
        AtomicReference<Throwable> failure = new AtomicReference<>();
        runOnMainSync(() -> {
            try {
                test.run();
            } catch (Throwable error) {
                failure.set(error);
            }
        });
        if (failure.get() != null) throw new AssertionError(failure.get());
    }

    private static final class Fixture {
        private LocusTerminalView first;
        private LocusTerminalView second;
        private final Capture left = new Capture();
        private final Capture right = new Capture();

        void install(Activity activity) {
            FrameLayout layout = new FrameLayout(activity);
            first = new LocusTerminalView(activity);
            second = new LocusTerminalView(activity);
            first.setListener(left);
            second.setListener(right);
            layout.addView(first, new FrameLayout.LayoutParams(420, 300));
            FrameLayout.LayoutParams secondPosition = new FrameLayout.LayoutParams(420, 300);
            secondPosition.topMargin = 300;
            layout.addView(second, secondPosition);
            activity.setContentView(layout);
            first.requestFocus();
        }

        void exerciseComposition() {
            require(first.isFocused() && first.hasWindowFocus(), "first terminal focused");
            InputConnection input = first.onCreateInputConnection(new EditorInfo());
            input.beginBatchEdit();
            input.setComposingText("ni", 1);
            input.setComposingText("nihao", 1);
            input.endBatchEdit();
            require("nihao".contentEquals(input.getTextBeforeCursor(10, 0)), "IME sees local preedit");
            left.expect("");
            input.commitText("你好", 1);
            left.expect("你好");
            input.setComposingText("cancel", 1);
            input.finishComposingText();
            left.expect("");
            require(input.getTextBeforeCursor(10, 0).length() == 0, "canceled preedit is cleared");
            input.commitText("A中文🍵", 1);
            input.deleteSurroundingText(1, 0);
            left.expect("A中文🍵\u007f");

            input.setComposingText("stale", 1);
            second.requestFocus();
            require(second.isFocused() && !first.isFocused(), "focus moved to second terminal");
            require(!input.commitText("leak", 1), "stale input connection rejected");
            left.expect("");
            right.expect("");
        }

        void exerciseSecondTerminal() {
            InputConnection other = second.onCreateInputConnection(new EditorInfo());
            require(other.commitText("二", 1), "second terminal accepted commit");
            sendKey(second, KeyEvent.KEYCODE_C, KeyEvent.META_CTRL_ON);
            sendKey(second, KeyEvent.KEYCODE_TAB, 0);
            sendKey(second, KeyEvent.KEYCODE_ESCAPE, 0);
            sendKey(second, KeyEvent.KEYCODE_DPAD_UP, 0);
            right.expect("二\u0003\t" + ESC + ESC + "[A");
            left.expect("");

            feed(second, ESC + "[?2004h");
            int writes = right.writes;
            // Android may replace the test connection while processing focus and key changes.
            InputConnection pasteInput = second.onCreateInputConnection(new EditorInfo());
            require(pasteInput.commitText("一\n二", 1), "active second terminal accepted paste");
            right.expect(ESC + "[200~一\r二" + ESC + "[201~");
            require(right.writes == writes + 1, "bracketed paste is one write");
        }

        void exerciseRenderingAndResize() {
            byte[] cjk = "中文".getBytes(StandardCharsets.UTF_8);
            feed(first, ESC + "[?25l" + ESC + "[31m");
            first.append(cjk, 0, 2);
            first.append(cjk, 2, cjk.length - 2);
            require(first.mEmulator.getCursorCol() == 4, "two CJK cells per character");
            require("中文".equals(first.mEmulator.getSelectedText(0, 0, 3, 0)),
                    "split UTF-8 feed decoded in the terminal");
            Bitmap pixels = Bitmap.createBitmap(first.getWidth(), first.getHeight(), Bitmap.Config.ARGB_8888);
            try {
                first.draw(new Canvas(pixels));
                require(containsRed(pixels), "ANSI red terminal output reached Android canvas");
            } finally {
                pixels.recycle();
            }

            int firstColumns = first.getTerminalSize().columns;
            int secondColumns = second.getTerminalSize().columns;
            first.layout(0, 0, 300, 300);
            require(first.getTerminalSize().columns < firstColumns
                    && left.columns == first.getTerminalSize().columns, "first terminal reported resize");
            require(second.getTerminalSize().columns == secondColumns, "second terminal retained its size");
        }
    }

    private static void sendKey(LocusTerminalView view, int key, int metaState) {
        require(view.dispatchKeyEvent(new KeyEvent(0, 0, KeyEvent.ACTION_DOWN, key, 0, metaState)),
                "key consumed " + key);
    }

    private static void feed(LocusTerminalView view, String text) {
        byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
        view.append(bytes, 0, bytes.length);
    }

    private static boolean containsRed(Bitmap bitmap) {
        for (int y = 0; y < bitmap.getHeight(); y++) {
            for (int x = 0; x < bitmap.getWidth(); x++) {
                int pixel = bitmap.getPixel(x, y);
                int red = (pixel >>> 16) & 255;
                int green = (pixel >>> 8) & 255;
                int blue = pixel & 255;
                if (red > 80 && red > green * 2 && red > blue * 2) return true;
            }
        }
        return false;
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }

    private static final class Capture implements LocusTerminalView.Listener {
        private final ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        private int writes;
        private int columns;

        @Override public void onInput(byte[] data) {
            buffer.write(data, 0, data.length);
            writes++;
        }

        @Override public void onResize(int columns, int rows, int widthPx, int heightPx) {
            this.columns = columns;
        }

        void expect(String text) {
            byte[] actual = buffer.toByteArray();
            byte[] expected = text.getBytes(StandardCharsets.UTF_8);
            require(Arrays.equals(expected, actual), "expected " + Arrays.toString(expected)
                    + ", received " + Arrays.toString(actual));
            buffer.reset();
        }
    }
}
