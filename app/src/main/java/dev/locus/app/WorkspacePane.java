package dev.locus.app;

import android.content.res.ColorStateList;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.InsetDrawable;
import android.graphics.drawable.RippleDrawable;
import android.graphics.drawable.StateListDrawable;
import android.os.Handler;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.ImageButton;
import android.widget.TextView;

import dev.locus.app.DiagnosticLog.Outcome;
import dev.locus.app.DiagnosticLog.Stage;

import java.io.IOException;
import java.util.Arrays;
import java.util.EnumMap;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.Semaphore;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

final class WorkspacePane extends FrameLayout {
    static final int FRAME_INSET_DP = 2;
    private static final int MOUNT_TARGET_DP = 48;

    private final MainActivity activity;
    private final Handler main;
    private final ExecutorService io;
    private final ThreadPoolExecutor output = new ThreadPoolExecutor(
            1, 1, 0, TimeUnit.SECONDS, new ArrayBlockingQueue<>(64));
    private final GradientDrawable frameSurface;
    private final FrameLayout body;
    private final TextView status;
    private final ImageButton windowOptions;
    private final ImageButton minimizeTarget;
    private final LocusTerminalView terminal;
    private final TextView placeholder;
    private final EnumMap<PaneDock.Edge, ImageButton> mounts = new EnumMap<>(PaneDock.Edge.class);
    private final Runnable flushResize = this::sendResize;
    private final int id;

    private RemoteConnection connection;
    private RemoteBrowserView browser;
    private volatile TerminalRun run;
    private String directory = "";
    private String directoryEndpoint;
    private String directoryUsername;
    private FavoriteLayouts.Directory pendingFavorite;
    private String shelfName = "";
    private Bitmap shelfPreview;
    private long openRequest;
    private boolean openingTerminal;
    private boolean shellExited;
    private boolean reconnectRequired;
    private boolean showingBrowser;
    private boolean connected;
    private int connectionProgress;
    private Supplier<String> statusMessage;
    private boolean canReconnect;
    private volatile boolean minimized;
    private volatile long placementGeneration;
    private boolean highlightedForMinimize;
    private boolean disposed;

    WorkspacePane(MainActivity activity, Handler main, ExecutorService io, int id) {
        super(activity);
        this.activity = activity;
        this.main = main;
        this.io = io;
        this.id = id;
        frameSurface = LocusChrome.surface(activity, activity.getColor(R.color.surface_variant), 0, 14);
        setBackground(frameSurface);
        setClipToOutline(true);
        int frameInset = activity.dp(FRAME_INSET_DP);
        body = new FrameLayout(activity);
        body.setBackground(LocusChrome.surface(activity, activity.getColor(R.color.surface), 0, 12));
        body.setClipToOutline(true);
        FrameLayout.LayoutParams contentSpace = new FrameLayout.LayoutParams(-1, -1);
        contentSpace.setMargins(frameInset, frameInset, frameInset, frameInset);
        addView(body, contentSpace);

        status = new TextView(activity);
        status.setTextSize(12);
        status.setTextColor(activity.getColor(R.color.text_primary));
        status.setBackground(new RippleDrawable(ColorStateList.valueOf(activity.getColor(R.color.ripple)),
                LocusChrome.surface(activity, activity.getColor(R.color.surface_variant), 0, 6), null));
        status.setPaddingRelative(activity.dp(8), activity.dp(4), activity.dp(8), activity.dp(4));
        status.setGravity(Gravity.CENTER_VERTICAL);
        status.setMaxLines(2);
        status.setEllipsize(TextUtils.TruncateAt.END);
        status.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        status.setOnClickListener(view -> activity.reconnectPane(this));
        status.setClickable(false);
        status.setFocusable(false);
        status.setVisibility(View.GONE);
        FrameLayout.LayoutParams statusSpace = new FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM | Gravity.START);
        int labelMargin = frameInset + activity.dp(4);
        statusSpace.setMargins(labelMargin, 0, labelMargin, labelMargin + activity.dp(FloatingTools.WINDOW_TAB));
        addView(status, statusSpace);

        terminal = new LocusTerminalView(activity);
        terminal.setListener(new LocusTerminalView.Listener() {
            @Override
            public void onInput(byte[] data) {
                TerminalRun current = run;
                // A hidden live terminal still answers remote terminal capability queries.
                if (current == null) return;
                if (data.length > 65536) {
                    setStatus(R.string.workspace_input_limit, true, 64);
                    return;
                }
                send(current, () -> current.channel.write(data));
            }

            @Override
            public void onResize(int columns, int rows, int widthPx, int heightPx) {
                scheduleResize();
            }
        });
        body.addView(terminal, new FrameLayout.LayoutParams(-1, -1));
        placeholder = new TextView(activity);
        placeholder.setTextColor(activity.getColor(R.color.text_secondary));
        placeholder.setBackgroundColor(activity.getColor(R.color.surface));
        placeholder.setTextSize(14);
        placeholder.setGravity(Gravity.CENTER);
        placeholder.setPadding(activity.dp(20), 0, activity.dp(20), 0);
        placeholder.setOnClickListener(view -> activity.openEmptyWindow(this));
        body.addView(placeholder, new FrameLayout.LayoutParams(-1, -1));

        windowOptions = LocusChrome.iconAction(activity, R.drawable.ic_window_grip, null);
        int target = activity.dp(MOUNT_TARGET_DP);
        body.addView(windowOptions, new FrameLayout.LayoutParams(target, target, Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL));
        FloatingTools.bindTrigger(windowOptions, body, FloatingTools.Kind.WINDOW,
                activity.freeFloating(), () -> showWindowOptions(windowOptions));

        minimizeTarget = new ImageButton(activity);
        minimizeTarget.setScaleType(ImageButton.ScaleType.CENTER);
        minimizeTarget.setBackground(null);
        minimizeTarget.setOnClickListener(view -> activity.minimizePane(this));
        minimizeTarget.setVisibility(View.GONE);
        addView(minimizeTarget, new FrameLayout.LayoutParams(-1, -1));

        addMount(PaneDock.Edge.TOP, Gravity.TOP | Gravity.CENTER_HORIZONTAL);
        addMount(PaneDock.Edge.BOTTOM, Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL);
        addMount(PaneDock.Edge.LEFT, Gravity.LEFT | Gravity.CENTER_VERTICAL);
        addMount(PaneDock.Edge.RIGHT, Gravity.RIGHT | Gravity.CENTER_VERTICAL);
        refreshLocale();
    }

    private void addMount(PaneDock.Edge edge, int gravity) {
        ImageButton mount = new ImageButton(activity);
        int target = activity.dp(MOUNT_TARGET_DP);
        int railInset = activity.dp(2);
        boolean horizontal = edge == PaneDock.Edge.TOP || edge == PaneDock.Edge.BOTTOM;
        int guideWidth = activity.dp(horizontal ? 36 : 4);
        int guideHeight = activity.dp(horizontal ? 4 : 36);
        int left = edge == PaneDock.Edge.LEFT ? railInset
                : edge == PaneDock.Edge.RIGHT ? target - guideWidth - railInset : (target - guideWidth) / 2;
        int top = edge == PaneDock.Edge.TOP ? railInset
                : edge == PaneDock.Edge.BOTTOM ? target - guideHeight - railInset : (target - guideHeight) / 2;
        StateListDrawable colors = new StateListDrawable();
        colors.addState(new int[]{android.R.attr.state_enabled},
                LocusChrome.surface(activity, activity.getColor(R.color.accent), 0, 2));
        colors.addState(new int[0], LocusChrome.surface(activity, activity.getColor(R.color.divider), 0, 2));
        RippleDrawable guide = new RippleDrawable(
                ColorStateList.valueOf(activity.getColor(R.color.ripple)), colors, null);
        mount.setBackground(new InsetDrawable(guide, left, top,
                target - left - guideWidth, target - top - guideHeight));
        mount.setOnClickListener(view -> activity.mountWindow(this, edge));
        mount.setVisibility(View.GONE);
        mounts.put(edge, mount);
        addView(mount, new FrameLayout.LayoutParams(target, target, gravity));
    }

    void setMountVisible(PaneDock.Edge edge, boolean visible) {
        mounts.get(edge).setVisibility(visible ? View.VISIBLE : View.GONE);
    }

    void setSelecting(boolean selecting, boolean highlightMinimize) {
        windowOptions.setVisibility(selecting ? View.INVISIBLE : View.VISIBLE);
        body.setImportantForAccessibility(selecting
                ? View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS : View.IMPORTANT_FOR_ACCESSIBILITY_AUTO);
        minimizeTarget.setVisibility(selecting ? View.VISIBLE : View.GONE);
        if (highlightMinimize != highlightedForMinimize) {
            highlightedForMinimize = highlightMinimize;
            int accent = activity.getColor(R.color.accent);
            minimizeTarget.setImageResource(highlightMinimize ? R.drawable.ic_minimize : 0);
            minimizeTarget.setImageTintList(ColorStateList.valueOf(accent));
            minimizeTarget.setBackground(highlightMinimize ? new RippleDrawable(
                    ColorStateList.valueOf(activity.getColor(R.color.ripple)),
                    LocusChrome.surface(activity, (accent & 0x00ffffff) | 0x24000000, accent, 14), null) : null);
        }
        minimizeTarget.setFocusable(highlightMinimize);
        minimizeTarget.setImportantForAccessibility(highlightMinimize
                ? View.IMPORTANT_FOR_ACCESSIBILITY_YES : View.IMPORTANT_FOR_ACCESSIBILITY_NO);
    }

    void updateMountAvailability(boolean vertical, boolean horizontal) {
        mounts.forEach((edge, mount) -> {
            boolean available = (edge == PaneDock.Edge.TOP || edge == PaneDock.Edge.BOTTOM)
                    ? vertical : horizontal;
            if (mount.isEnabled() == available) return;
            mount.setEnabled(available);
            mount.setClickable(available);
            mount.setFocusable(available);
        });
    }

    String directoryFor(String endpoint, String username) {
        return directoryEndpoint != null && directoryEndpoint.equals(endpoint)
                && directoryUsername.equals(username) ? directory : "";
    }

    String reconnectDirectory(String endpoint, String username) {
        return reconnectRequired ? directoryFor(endpoint, username) : "";
    }

    void setDirectoryIdentity(String endpoint, String username) {
        directoryEndpoint = endpoint;
        directoryUsername = username;
    }

    FavoriteLayouts.Directory favoriteDirectory() {
        if (pendingFavorite != null) return pendingFavorite;
        if (directory.isEmpty() || directoryEndpoint == null || directoryUsername == null) return null;
        return new FavoriteLayouts.Directory(directoryEndpoint, directoryUsername, directory,
                showingBrowser ? browser.openedDirectory(directory) : null);
    }

    boolean matchesFavoriteDirectory(FavoriteLayouts.Directory saved) {
        if (saved == null) {
            return !openingTerminal && pendingFavorite == null && directory.isEmpty() && !hasLiveTerminal();
        }
        return (!openingTerminal || pendingFavorite != null) && saved.equals(favoriteDirectory());
    }

    void restoreFavoriteDirectory(FavoriteLayouts.Directory saved) {
        if (!hasLiveTerminal()) pendingFavorite = saved;
    }

    boolean hasPendingFavoriteDirectory() {
        return pendingFavorite != null;
    }

    boolean pendingFavoriteMatches(String endpoint, String username) {
        return pendingFavorite != null && pendingFavorite.matchesConnection(endpoint, username);
    }

    boolean openFavoriteDirectory(RemoteConnection current, String endpoint, String username) {
        if (!pendingFavoriteMatches(endpoint, username) || hasLiveTerminal()) return false;
        if (!openingTerminal) open(current, pendingFavorite.shell, null, pendingFavorite);
        return true;
    }

    boolean isBrowsing() {
        return showingBrowser;
    }

    String shelfLabel() {
        if (!shelfName.isEmpty()) return shelfName;
        if (directory.isEmpty()) return activity.getString(showingBrowser ? R.string.shelf_files : R.string.shelf_terminal);
        if (directory.equals("/")) return directory;
        return directory.substring(directory.lastIndexOf('/') + 1);
    }

    String shelfDetail() {
        String message = status.getText().toString();
        if (directory.isEmpty()) return message;
        return message.isEmpty() || status.getVisibility() != View.VISIBLE ? directory : directory + "\n" + message;
    }

    int shelfId() {
        return id;
    }

    String shelfName() {
        return shelfName;
    }

    void setShelfName(String name) {
        shelfName = name.trim();
    }

    Bitmap shelfPreview() {
        return shelfPreview;
    }

    private Bitmap capturePreview() {
        View content = showingBrowser ? browser : placeholder.isShown() ? placeholder : terminal;
        if (content == null || content.getWidth() == 0 || content.getHeight() == 0) return null;
        float scale = Math.min(1f, 640f / Math.max(content.getWidth(), content.getHeight()));
        Bitmap snapshot = Bitmap.createBitmap(Math.max(1, Math.round(content.getWidth() * scale)),
                Math.max(1, Math.round(content.getHeight() * scale)), Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(snapshot);
        canvas.scale(scale, scale);
        content.draw(canvas);
        return snapshot;
    }

    void setMinimized(boolean hidden) {
        if (minimized == hidden) return;
        minimized = hidden;
        placementGeneration++;
        if (hidden) {
            shelfPreview = capturePreview();
            main.removeCallbacks(flushResize);
            if (terminal.hasFocus() && isAttachedToWindow()) activity.hideKeyboard(terminal);
            clearFocus();
        } else {
            shelfPreview = null;
            scheduleResize();
        }
    }

    @Override
    public boolean dispatchTouchEvent(MotionEvent event) {
        if (event.getActionMasked() == MotionEvent.ACTION_DOWN) activity.focusTouchedPane(this);
        return super.dispatchTouchEvent(event);
    }

    @Override
    public void requestChildFocus(View child, View focused) {
        super.requestChildFocus(child, focused);
        if (!minimized) activity.activate(this);
    }

    void focus() {
        if (minimized) return;
        activity.activate(this);
        if (showingBrowser && browser != null) {
            browser.requestFocus();
        } else {
            terminal.requestFocus();
        }
    }

    void setActive(boolean active) {
        frameSurface.setColor(activity.getColor(active ? R.color.surface_active : R.color.surface_variant));
        windowOptions.setSelected(active);
    }

    void setFreeFloating(boolean enabled) {
        FloatingTools.setFreeFloating(windowOptions, enabled);
    }

    void refreshLocale() {
        terminal.setContentDescription(activity.getString(R.string.workspace_terminal_number, id));
        windowOptions.setContentDescription(activity.getText(R.string.workspace_window_options));
        minimizeTarget.setContentDescription(activity.getString(R.string.select_minimize_target, id));
        mounts.get(PaneDock.Edge.TOP).setContentDescription(activity.getText(R.string.workspace_mount_above));
        mounts.get(PaneDock.Edge.BOTTOM).setContentDescription(activity.getText(R.string.workspace_mount_below));
        mounts.get(PaneDock.Edge.LEFT).setContentDescription(activity.getText(R.string.workspace_mount_left));
        mounts.get(PaneDock.Edge.RIGHT).setContentDescription(activity.getText(R.string.workspace_mount_right));
        FloatingTools.refreshLocale(windowOptions);
        updatePlaceholder();
        updateStatusText();
        if (browser != null) browser.refreshLocale();
    }

    private void showWindowOptions(View anchor) {
        FloatingTools tools = new FloatingTools(anchor, body);
        tools.add(R.drawable.ic_folder, activity.getString(R.string.workspace_choose_directory), connected,
                () -> activity.chooseDirectory(this));
        boolean canBrowse = browser != null || connected && connection != null && !directory.isEmpty();
        tools.add(showingBrowser ? R.drawable.ic_terminal : R.drawable.ic_files,
                activity.getString(showingBrowser ? R.string.shelf_terminal : R.string.workspace_browse),
                canBrowse, this::toggleBrowser);
        boolean keyboardVisible = activity.isKeyboardVisible(terminal);
        tools.add(R.drawable.ic_keyboard, activity.getString(R.string.workspace_keyboard), hasLiveTerminal() && !showingBrowser,
                () -> toggleKeyboard(keyboardVisible));
        tools.add(R.drawable.ic_rename, activity.getString(R.string.rename_window), true,
                () -> activity.renameWindow(this));
        tools.add(R.drawable.ic_close_window, activity.getString(R.string.workspace_close_window), true,
                () -> activity.closePane(this));
        tools.show();
    }

    boolean hasLiveTerminal() {
        TerminalRun current = run;
        return current != null && current.channel.isOpen();
    }

    void updateConnection(boolean connected, int progress, boolean canReconnect) {
        this.connected = connected;
        this.connectionProgress = progress;
        this.canReconnect = canReconnect;
        updatePlaceholder();
        if (reconnectRequired) updateStatusText();
    }

    private void updatePlaceholder() {
        if (placeholder.getVisibility() == View.VISIBLE) {
            int prompt = connected ? R.string.workspace_choose_directory_prompt
                    : canReconnect ? R.string.reconnect_window : R.string.workspace_connect_prompt;
            placeholder.setText(activity.getText(connectionProgress != 0 ? connectionProgress : prompt));
            placeholder.setEnabled(connectionProgress == 0);
        }
    }

    void open(RemoteConnection connection, String directory) {
        open(connection, directory, null);
    }

    void open(RemoteConnection connection, String directory, Runnable directoryUnavailable) {
        open(connection, directory, directoryUnavailable, null);
    }

    private void open(RemoteConnection connection, String directory, Runnable directoryUnavailable,
                      FavoriteLayouts.Directory favorite) {
        pendingFavorite = favorite;
        openingTerminal = true;
        reconnectRequired = false;
        setStatus(R.string.workspace_shell_opening, true);
        DiagnosticLog.Trace trace = activity.diagnosticTrace(connection);
        long request = ++openRequest;
        // Allow the split to measure before requesting the initial PTY dimensions.
        terminal.post(() -> {
            if (disposed || request != openRequest) return;
            LocusTerminalView.Size size = terminal.getTerminalSize();
            io.execute(() -> {
                boolean resolved = false;
                try {
                    if (trace != null) trace.record(Stage.SFTP, Outcome.STARTED);
                    String selectedDirectory = connection.canonicalDirectory(directory);
                    resolved = true;
                    if (trace != null) {
                        trace.record(Stage.SFTP, Outcome.READY);
                        trace.record(Stage.PTY, Outcome.STARTED);
                    }
                    RemoteConnection.Terminal channel = connection.openTerminal(selectedDirectory,
                            size.columns, size.rows, size.widthPx, size.heightPx);
                    if (trace != null) trace.record(Stage.PTY, Outcome.READY);
                    main.post(() -> attach(request, connection, selectedDirectory, channel, favorite));
                } catch (Exception failure) {
                    if (trace != null) trace.record(resolved ? Stage.PTY : Stage.SFTP,
                            resolved ? Outcome.FAILED : Outcome.PATH_UNAVAILABLE);
                    boolean chooseAgain = !resolved && directoryUnavailable != null;
                    main.post(() -> {
                        if (disposed || request != openRequest) return;
                        if (!connection.isOpen()) {
                            activity.terminalEnded();
                            return;
                        }
                        openingTerminal = false;
                        pendingFavorite = null;
                        setStatus(() -> UiErrors.message(activity, failure), true);
                        activity.terminalEnded();
                        if (chooseAgain && connection.isOpen()) directoryUnavailable.run();
                    });
                }
            });
        });
    }

    private void attach(long request, RemoteConnection connection, String directory,
                        RemoteConnection.Terminal channel, FavoriteLayouts.Directory favorite) {
        if (disposed || request != openRequest) {
            activity.onIo(channel::close);
            return;
        }
        if (!connection.isOpen()) {
            activity.onIo(channel::close);
            activity.terminalEnded();
            return;
        }
        openingTerminal = false;
        pendingFavorite = null;
        shellExited = false;
        this.connection = connection;
        this.directory = directory;
        clearBrowser();
        closeRun();
        placeholder.setVisibility(View.GONE);
        terminal.resetTerminal();
        TerminalRun current = new TerminalRun(channel);
        run = current;
        setStatus(R.string.workspace_shell_active, false);
        activity.directoryOpened(this, connection, directory);
        sendResize();
        current.reader = io.submit(() -> read(current));
        if (favorite != null && favorite.browser != null) {
            boolean hadFocus = terminal.hasFocus();
            if (hadFocus) activity.hideKeyboard(terminal);
            showBrowser(favorite.browser);
            if (hadFocus && !minimized) browser.requestFocus();
        }
    }

    private void read(TerminalRun current) {
        byte[] buffer = new byte[16384];
        Semaphore pending = new Semaphore(4);
        Supplier<String> outcome;
        boolean exited;
        try {
            int length;
            while ((length = current.channel.read(buffer)) != -1) {
                if (length == 0) continue;
                pending.acquire();
                byte[] bytes = Arrays.copyOf(buffer, length);
                main.post(() -> {
                    try {
                        if (run == current) terminal.append(bytes, 0, bytes.length);
                    } finally {
                        pending.release();
                    }
                });
            }
            int code = current.channel.awaitExitStatus();
            exited = code >= 0;
            outcome = exited ? () -> activity.getString(R.string.workspace_shell_exit, code) : null;
        } catch (InterruptedException canceled) {
            Thread.currentThread().interrupt();
            return;
        } catch (Exception failure) {
            exited = false;
            outcome = () -> activity.getString(R.string.workspace_terminal_stopped, UiErrors.message(activity, failure));
        }
        boolean hasExitStatus = exited;
        Supplier<String> message = outcome;
        main.post(() -> {
            if (run != current) return;
            shellExited = hasExitStatus;
            closeRun();
            if (message != null) {
                setStatus(message, true);
            } else if (connection != null && connection.isOpen()) {
                setStatus(R.string.workspace_terminal_closed, true);
            } else {
                setStatus(R.string.workspace_ssh_disconnected, true);
            }
            activity.terminalEnded();
        });
    }

    private void scheduleResize() {
        main.removeCallbacks(flushResize);
        if (!minimized) main.postDelayed(flushResize, 60);
    }

    private void sendResize() {
        TerminalRun current = run;
        if (current == null || showingBrowser || minimized || !isAttachedToWindow()) return;
        long placement = placementGeneration;
        LocusTerminalView.Size size = terminal.getTerminalSize();
        send(current, () -> {
            if (!minimized && placement == placementGeneration) {
                current.channel.resize(size.columns, size.rows, size.widthPx, size.heightPx);
            }
        });
    }

    private void send(TerminalRun current, TerminalOperation operation) {
        try {
            output.execute(() -> {
                if (run != current) return;
                try {
                    operation.run();
                } catch (Exception failure) {
                    main.post(() -> {
                        if (run == current) setStatus(() -> UiErrors.message(activity, failure), true);
                    });
                }
            });
        } catch (RejectedExecutionException saturated) {
            if (run == current) setStatus(R.string.workspace_send_queue_full, true);
        }
    }

    private void toggleBrowser() {
        activity.activate(this);
        if (showingBrowser) {
            if (browser != null) browser.setVisibility(View.GONE);
            terminal.setVisibility(View.VISIBLE);
            showingBrowser = false;
            terminal.requestFocus();
            sendResize();
        } else {
            if (browser == null && (connection == null || !connection.isOpen())) return;
            terminal.clearFocus();
            activity.hideKeyboard(terminal);
            showBrowser(directory);
            browser.requestFocus();
        }
    }

    private void showBrowser(String path) {
        if (browser == null) {
            browser = new RemoteBrowserView(activity, connection, io);
            browser.setFocusableInTouchMode(true);
            body.addView(browser, body.indexOfChild(windowOptions), new FrameLayout.LayoutParams(-1, -1));
            browser.openDirectory(path);
        }
        showingBrowser = true;
        terminal.setVisibility(View.GONE);
        browser.setVisibility(View.VISIBLE);
    }

    private void toggleKeyboard(boolean wasVisible) {
        if (!hasLiveTerminal()) return;
        if (wasVisible || activity.isKeyboardVisible(terminal)) activity.hideKeyboard(terminal);
        else terminal.showKeyboard();
    }

    private void setStatus(int message, boolean visible, Object... args) {
        setStatus(() -> activity.getString(message, args), visible);
    }

    private void setStatus(Supplier<String> message, boolean visible) {
        statusMessage = message;
        status.setVisibility(visible ? View.VISIBLE : View.GONE);
        updateStatusText();
    }

    private void updateStatusText() {
        boolean retry = reconnectRequired && connectionProgress == 0;
        if (reconnectRequired) {
            int label = connectionProgress != 0 ? connectionProgress : connected ? R.string.workspace_ssh_reopen
                    : canReconnect ? R.string.workspace_ssh_reconnect : R.string.workspace_ssh_setup;
            status.setText(activity.getText(label));
        } else if (statusMessage != null) {
            status.setText(statusMessage.get());
        }
        status.setClickable(retry);
        status.setFocusable(retry);
        status.setMinHeight(retry ? activity.dp(48) : 0);
        updateOptionsTooltip();
    }

    private void updateOptionsTooltip() {
        StringBuilder tooltip = new StringBuilder(activity.getString(R.string.workspace_window_options));
        if (!directory.isEmpty()) tooltip.append('\n').append(directory);
        if (status.length() > 0) tooltip.append('\n').append(status.getText());
        windowOptions.setTooltipText(tooltip);
    }

    private void clearBrowser() {
        if (browser != null) {
            browser.dispose();
            body.removeView(browser);
            browser = null;
        }
        showingBrowser = false;
        terminal.setVisibility(View.VISIBLE);
    }

    void transportLost() {
        boolean affected = openingTerminal || run != null || browser != null || (connection != null && !shellExited);
        ++openRequest;
        openingTerminal = false;
        connection = null;
        closeRun();
        if (browser != null) browser.transportLost();
        if (affected) {
            reconnectRequired = !directory.isEmpty();
            setStatus(R.string.workspace_ssh_disconnected, reconnectRequired);
        }
    }

    void detach() {
        ++openRequest;
        openingTerminal = false;
        reconnectRequired = false;
        shelfPreview = null;
        connection = null;
        clearBrowser();
        closeRun();
        setStatus(R.string.workspace_ssh_disconnected, !directory.isEmpty());
        updatePlaceholder();
    }

    private void closeRun() {
        TerminalRun previous = run;
        run = null;
        main.removeCallbacks(flushResize);
        output.getQueue().clear();
        if (previous == null) return;
        if (previous.reader != null) previous.reader.cancel(true);
        activity.onIo(previous.channel::close);
    }

    void dispose() {
        shelfPreview = null;
        disposed = true;
        detach();
        output.shutdownNow();
    }

    private interface TerminalOperation {
        void run() throws IOException;
    }

    private static final class TerminalRun {
        final RemoteConnection.Terminal channel;
        Future<?> reader;

        TerminalRun(RemoteConnection.Terminal channel) {
            this.channel = channel;
        }
    }
}
