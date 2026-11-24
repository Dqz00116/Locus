package dev.locus.app;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ActivityNotFoundException;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.res.Configuration;
import android.content.res.Resources;
import android.database.Cursor;
import android.net.Uri;
import android.graphics.Rect;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.LocaleList;
import android.os.Looper;
import android.provider.OpenableColumns;
import android.text.InputFilter;
import android.text.InputType;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowInsets;
import android.view.inputmethod.InputMethodManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import com.jcraft.jsch.JSchAlgoNegoFailException;

import dev.locus.app.ConnectionDialog.AuthMode;
import dev.locus.app.DiagnosticLog.Outcome;
import dev.locus.app.DiagnosticLog.Stage;
import dev.locus.app.OperationException.Reason;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;

public final class MainActivity extends Activity {
    private enum Selection { NONE, PLACE, MINIMIZE }

    private static final int PICK_SSH_KEY = 7;
    private static final int PICK_FRP_PROFILE = 8;
    private static final int PICK_SSH_CONFIG = 9;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService io = Executors.newCachedThreadPool();
    private final List<WorkspacePane> panes = new ArrayList<>();
    private final List<WorkspacePane> minimized = new ArrayList<>();
    private PaneDock dock;
    private WindowShelf shelf;
    private ImageButton workspaceTray;
    private View mountGuide;
    private TextView mountInstruction;
    private Button cancelMount;
    private volatile Resources legacyLanguageResources;
    private LocaleList displayedLocales;
    private Selection selection = Selection.NONE;
    private List<View> minimizeTargets = Collections.emptyList();
    private boolean freeFloating;
    private WorkspacePane restoring;
    private RemoteConnection connection;
    private DiagnosticLog diagnostics;
    private DiagnosticLog.Trace connectionTrace;
    private DiagnosticLog.Trace pendingConnectionTrace;
    private SshConnection.Profile lastDirect;
    private String lastUsername = "";
    private boolean preferFrp;
    private AuthMode draftAuthMode;
    private boolean hasDraft;
    private boolean loadingConnection = true;
    private SharedPreferences preferences;
    private SavedSshConnection savedConnection;
    private FrpRoute.Choice importedFrpChoice;
    private String connectedEndpoint;
    private String connectedUsername;
    private WorkspacePane activePane;
    private ConnectionDialog pendingPicker;
    private LocalCredentials credentials;
    private boolean importingProfile;
    private boolean connecting;
    private int connectionProgress;
    private volatile boolean foreground;
    private volatile boolean destroyed;
    private int nextPaneId = 1;

    @Override
    public void onCreate(Bundle state) {
        super.onCreate(state);
        if (Build.VERSION.SDK_INT < 33) {
            legacyLanguageResources = AppLanguage.legacyResources(getBaseContext());
        } else {
            AppLanguage.migrateLegacyChoice(this);
        }
        displayedLocales = getResources().getConfiguration().getLocales();
        credentials = new LocalCredentials(this);
        diagnostics = DiagnosticLog.get(this);
        preferences = getSharedPreferences("connection", MODE_PRIVATE);
        freeFloating = preferences.getBoolean("freeFloatingControls", false);
        restoreConnectionDraft();
        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(getColor(R.color.workspace_background));
        dock = new PaneDock(this);
        dock.setOnSplitMenu(this::showSplitMenu);
        FrameLayout.LayoutParams workspaceSize = new FrameLayout.LayoutParams(-1, -1);
        workspaceSize.setMargins(dp(6), dp(6), dp(6), dp(6));
        root.addView(dock, workspaceSize);

        workspaceTray = LocusChrome.iconAction(this, R.drawable.ic_settings, getString(R.string.workspace_actions));
        FloatingTools.bindTrigger(workspaceTray, root, true, freeFloating, () -> showWorkspaceActions(workspaceTray));
        FrameLayout.LayoutParams traySize = new FrameLayout.LayoutParams(dp(48), dp(48), Gravity.TOP | Gravity.LEFT);
        root.addView(workspaceTray, traySize);
        shelf = new WindowShelf(root, freeFloating, this::restoreMinimized);
        mountGuide = addMountGuide(root);
        dock.getViewTreeObserver().addOnGlobalLayoutListener(this::updateDockControls);

        setContentView(root);
        addInitialWindow();
        loadSavedConnection();
    }

    @Override
    public Resources getResources() {
        Resources localized = legacyLanguageResources;
        return localized == null ? super.getResources() : localized;
    }

    @Override
    public void onConfigurationChanged(Configuration configuration) {
        super.onConfigurationChanged(configuration);
        if (Build.VERSION.SDK_INT < 33) {
            legacyLanguageResources = AppLanguage.legacyResources(getBaseContext());
        }
        refreshLocale();
    }

    private void refreshLocale() {
        LocaleList locales = getResources().getConfiguration().getLocales();
        if (workspaceTray == null || locales.equals(displayedLocales)) return;
        displayedLocales = locales;
        LocusChrome.labelIcon(workspaceTray, getString(R.string.workspace_actions));
        FloatingTools.refreshLocale(workspaceTray);
        shelf.refreshLocale();
        dock.refreshLocale();
        updateMountInstruction();
        cancelMount.setText(getText(R.string.action_cancel));
        for (WorkspacePane pane : panes) pane.refreshLocale();
        for (WorkspacePane pane : minimized) pane.refreshLocale();
    }

    private void addInitialWindow() {
        WorkspacePane pane = new WorkspacePane(this, main, io, nextPaneId++);
        panes.add(pane);
        dock.setInitial(pane);
        refreshConnection();
        pane.focus();
    }

    void mountWindow(WorkspacePane target, PaneDock.Edge edge) {
        if (selection != Selection.PLACE || !canMountWindow(target, edge)) return;
        boolean restore = restoring != null;
        WorkspacePane pane = restore ? restoring : new WorkspacePane(this, main, io, nextPaneId++);
        if (!dock.split(target, pane, edge)) {
            if (!restore) pane.dispose();
            return;
        }
        panes.add(pane);
        if (restore) {
            minimized.remove(pane);
            pane.setMinimized(false);
        }
        setSelection(Selection.NONE);
        refreshConnection();
        pane.focus();
        if (!restore && hasConnection()) {
            chooseDirectory(pane, target.directoryFor(connectedEndpoint, connectedUsername));
        }
    }

    void minimizePane(WorkspacePane pane) {
        if (selection != Selection.MINIMIZE || !minimizeTargets.contains(pane)
                || panes.size() < 2 || !panes.remove(pane)) return;
        pane.setMinimized(true);
        dock.remove(pane);
        minimized.add(pane);
        if (activePane == pane) panes.get(0).focus();
        setSelection(Selection.NONE);
    }

    private void restoreMinimized(WorkspacePane pane) {
        if (!minimized.contains(pane)) return;
        if (!canCreateWindow()) {
            toast(getString(R.string.no_dock_space));
            return;
        }
        restoring = pane;
        setSelection(Selection.PLACE);
    }

    private boolean canMountWindow(WorkspacePane pane, PaneDock.Edge edge) {
        return panes.contains(pane) && dock.canSplit(pane, edge);
    }

    private boolean canCreateWindow() {
        for (WorkspacePane pane : panes) {
            if (canMountWindow(pane, PaneDock.Edge.TOP) || canMountWindow(pane, PaneDock.Edge.LEFT)) return true;
        }
        return false;
    }

    private View addMountGuide(FrameLayout root) {
        LinearLayout guide = new LinearLayout(this);
        guide.setGravity(Gravity.CENTER_VERTICAL);
        guide.setFocusableInTouchMode(true);
        guide.setPaddingRelative(dp(14), 0, dp(2), 0);
        int fill = (getColor(R.color.accent_surface) & 0x00ffffff) | 0xee000000;
        guide.setBackground(LocusChrome.surface(this, fill, getColor(R.color.divider), 10));
        mountInstruction = new TextView(this);
        mountInstruction.setTextColor(getColor(R.color.text_primary));
        mountInstruction.setTextSize(14);
        mountInstruction.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        guide.addView(mountInstruction, new LinearLayout.LayoutParams(0, -2, 1));
        cancelMount = addAction(guide, getString(R.string.action_cancel), () -> setSelection(Selection.NONE));
        FrameLayout.LayoutParams size = new FrameLayout.LayoutParams(-2, -2, Gravity.TOP | Gravity.CENTER_HORIZONTAL);
        size.topMargin = dp(64);
        size.leftMargin = size.rightMargin = dp(10);
        guide.setVisibility(View.GONE);
        root.addView(guide, size);
        return guide;
    }

    private void setSelection(Selection next) {
        if (selection == next || next == Selection.PLACE && !canCreateWindow()) return;
        selection = next;
        boolean selecting = next != Selection.NONE;
        if (selecting) hideKeyboard(workspaceTray);
        if (next != Selection.PLACE) restoring = null;
        if (next != Selection.MINIMIZE) minimizeTargets = Collections.emptyList();
        dock.setMountTargeting(selecting);
        workspaceTray.setVisibility(selecting ? View.INVISIBLE : View.VISIBLE);
        updateMountInstruction();
        mountGuide.setVisibility(selecting ? View.VISIBLE : View.GONE);
        shelf.update(minimized, selecting);
        updateDockControls();
        if (selecting) mountGuide.requestFocus();
        else if (activePane != null) activePane.focus();
    }

    private void updateMountInstruction() {
        mountInstruction.setText(getText(selection == Selection.MINIMIZE ? R.string.select_minimize_hint
                : restoring == null ? R.string.mount_hint : R.string.restore_mount_hint));
    }

    @Override
    public void onBackPressed() {
        if (selection != Selection.NONE) setSelection(Selection.NONE);
        else finishAndRemoveTask();
    }

    void activate(WorkspacePane pane) {
        if (activePane == pane || !panes.contains(pane)) return;
        if (activePane != null) activePane.setActive(false);
        activePane = pane;
        pane.setActive(true);
    }

    void closePane(WorkspacePane pane) {
        if (pane.hasLiveTerminal()) {
            new AlertDialog.Builder(this)
                    .setTitle(R.string.workspace_close_window_title)
                    .setMessage(R.string.workspace_close_window_message)
                    .setNegativeButton(R.string.action_cancel, null)
                    .setPositiveButton(R.string.workspace_close_window, (dialog, which) -> removePane(pane))
                    .show();
        } else {
            removePane(pane);
        }
    }

    void renameWindow(WorkspacePane pane) {
        if (!panes.contains(pane)) return;
        EditText name = new EditText(this);
        name.setSingleLine(true);
        name.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        name.setFilters(new InputFilter[]{new InputFilter.LengthFilter(64)});
        name.setHint(R.string.shelf_name_hint);
        name.setText(pane.shelfName());
        name.setSelectAllOnFocus(true);
        FrameLayout field = new FrameLayout(this);
        field.setPadding(dp(24), dp(8), dp(24), dp(8));
        field.addView(name, new FrameLayout.LayoutParams(-1, -2));
        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle(R.string.rename_window)
                .setView(field)
                .setNegativeButton(R.string.action_cancel, null)
                .setPositiveButton(R.string.save_window_name, (view, which) -> pane.setShelfName(name.getText().toString()))
                .show();
        LocusChrome.fitForm(dialog);
    }

    private void removePane(WorkspacePane pane) {
        if (!panes.remove(pane)) return;
        dock.remove(pane);
        pane.dispose();
        boolean wasActive = activePane == pane;
        if (wasActive) activePane = null;
        if (panes.isEmpty()) {
            addInitialWindow();
        } else {
            refreshConnection();
            if (wasActive) panes.get(0).focus();
        }
    }

    private void updateDockControls() {
        boolean browsingAtTray = false;
        for (WorkspacePane pane : panes) {
            boolean nearTray = pane.getVisibility() == View.VISIBLE && pane.getTop() == 0 && pane.getLeft() == 0;
            pane.setTrayInset(nearTray ? dp(48) : 0);
            if (nearTray) browsingAtTray = pane.isBrowsing();
            pane.setSelecting(selection != Selection.NONE, minimizeTargets.contains(pane));
            boolean vertical = selection == Selection.PLACE && canMountWindow(pane, PaneDock.Edge.TOP);
            boolean horizontal = selection == Selection.PLACE && canMountWindow(pane, PaneDock.Edge.LEFT);
            pane.updateMountAvailability(vertical, horizontal);
            pane.setMountVisible(PaneDock.Edge.TOP, vertical);
            pane.setMountVisible(PaneDock.Edge.BOTTOM, vertical);
            pane.setMountVisible(PaneDock.Edge.LEFT, horizontal);
            pane.setMountVisible(PaneDock.Edge.RIGHT, horizontal);
        }
        int controlsTop = dp(6 + WorkspacePane.MOUNT_RAIL_DP + (browsingAtTray ? 48 : 0));
        FloatingTools.setDefaultTop(workspaceTray, controlsTop);
        shelf.setDefaultTop(controlsTop + dp(2 * 48));
    }

    private void showSplitMenu(View anchor, List<View> candidates) {
        FloatingTools menu = new FloatingTools(anchor);
        menu.compactRow();
        menu.add(R.drawable.ic_add, getString(R.string.workspace_new_window), canCreateWindow(),
                () -> setSelection(Selection.PLACE));
        menu.add(R.drawable.ic_minimize, getString(R.string.minimize_window), true, () -> {
            minimizeTargets = candidates;
            setSelection(Selection.MINIMIZE);
        });
        menu.show();
    }

    private void showWorkspaceActions(View anchor) {
        WorkspacePane pane = selectedPane();
        FloatingTools menu = new FloatingTools(anchor);
        boolean connected = hasConnection();
        String connectLabel = getString(connecting ? connectionProgress : connected
                ? R.string.connection_action_disconnect : R.string.connection_action_connect);
        menu.addLabeled(connected ? R.drawable.ic_disconnect : R.drawable.ic_connect, connectLabel,
                !connecting && !loadingConnection, () -> connectionClicked(pane));
        if (!connected && (hasDraft || savedConnection != null)) {
            menu.addLabeled(R.drawable.ic_settings, getString(R.string.connection_setup), !connecting && !loadingConnection,
                    () -> configureConnection(pane));
        }
        menu.addLabeled(R.drawable.ic_add, getString(R.string.workspace_new_window), canCreateWindow(),
                () -> setSelection(Selection.PLACE));
        menu.addLabeled(R.drawable.ic_move, getString(freeFloating
                ? R.string.disable_free_floating : R.string.enable_free_floating), true, this::toggleFreeFloating);
        menu.addLabeled(R.drawable.ic_language, getString(R.string.app_language), true, this::chooseLanguage);
        menu.show();
    }

    private void chooseLanguage() {
        AppLanguage current = AppLanguage.current(this);
        AppLanguage[] languages = AppLanguage.values();
        CharSequence[] labels = new CharSequence[languages.length];
        for (int index = 0; index < labels.length; index++) {
            labels[index] = getText(languages[index].label);
        }
        new AlertDialog.Builder(this)
                .setTitle(R.string.app_language)
                .setSingleChoiceItems(labels, current.ordinal(), (dialog, which) -> {
                    dialog.dismiss();
                    AppLanguage chosen = languages[which];
                    chosen.apply(this);
                    if (Build.VERSION.SDK_INT < 33) {
                        legacyLanguageResources = AppLanguage.legacyResources(getBaseContext());
                        refreshLocale();
                    }
                })
                .setNegativeButton(R.string.action_cancel, null)
                .show();
    }

    boolean freeFloating() {
        return freeFloating;
    }

    private void toggleFreeFloating() {
        freeFloating = !freeFloating;
        preferences.edit().putBoolean("freeFloatingControls", freeFloating).apply();
        FloatingTools.setFreeFloating(workspaceTray, freeFloating);
        shelf.setFreeFloating(freeFloating);
        for (WorkspacePane pane : panes) pane.setFreeFloating(freeFloating);
        for (WorkspacePane pane : minimized) pane.setFreeFloating(freeFloating);
    }

    private void connectionClicked(WorkspacePane pane) {
        if (hasConnection()) {
            new AlertDialog.Builder(this)
                    .setTitle(R.string.connection_disconnect_title)
                    .setMessage(R.string.connection_disconnect_message)
                    .setNegativeButton(R.string.action_cancel, null)
                    .setPositiveButton(R.string.connection_disconnect_confirm, (dialog, which) -> disconnect())
                    .show();
        } else {
            connectOrConfigure(pane, false);
        }
    }

    private WorkspacePane selectedPane() {
        return panes.contains(activePane) ? activePane : panes.get(0);
    }

    void openEmptyWindow(WorkspacePane pane) {
        if (selection != Selection.NONE || !panes.contains(pane)) return;
        if (hasConnection()) chooseDirectory(pane);
        else connectOrConfigure(pane, true);
    }

    void reconnectPane(WorkspacePane pane) {
        if (connecting || loadingConnection || selection != Selection.NONE || !panes.contains(pane)) return;
        if (hasConnection()) {
            reopenDirectory(pane, connection, pane.reconnectDirectory(connectedEndpoint, connectedUsername));
        } else {
            connectOrConfigure(pane, true);
        }
    }

    private SavedSshConnection reusableConnection() {
        SavedSshConnection saved = savedConnection;
        if (!matchesSavedConnection(saved, preferFrp) || !saved.canConnect(credentials)) return null;
        AuthMode savedMode = saved.usesKey() ? AuthMode.KEY : AuthMode.PASSWORD;
        return draftAuthMode == null || draftAuthMode == savedMode ? saved : null;
    }

    private void connectOrConfigure(WorkspacePane pane, boolean reopenDirectory) {
        if (connecting || loadingConnection) return;
        SavedSshConnection saved = reusableConnection();
        if (saved != null) {
            AuthMode savedMode = saved.usesKey() ? AuthMode.KEY : AuthMode.PASSWORD;
            ConnectionDialog.Request request = new ConnectionDialog.Request(saved.frp, savedMode, saved.host, saved.port,
                    saved.username, "", null, saved.usesKey(), false, saved.hasSecret, false);
            connect(pane, request, null, saved, reopenDirectory);
        } else {
            configureConnection(pane);
        }
    }

    private void restoreConnectionDraft() {
        hasDraft = preferences.getBoolean("hasDraft", false);
        if (!hasDraft) return;
        preferFrp = preferences.getBoolean("frp", false);
        if (preferences.contains("keyLogin")) {
            draftAuthMode = preferences.getBoolean("keyLogin", false) ? AuthMode.KEY : AuthMode.PASSWORD;
        }
        lastUsername = preferences.getString("username", "");
        String host = preferences.getString("host", "");
        int port = preferences.getInt("port", 22);
        if (!host.isEmpty() && !lastUsername.isEmpty()) {
            try {
                lastDirect = new SshConnection.Profile(host, port, lastUsername);
            } catch (IllegalArgumentException invalidDraft) {
                lastDirect = null;
            }
        }
    }

    private void loadSavedConnection() {
        io.execute(() -> {
            SavedSshConnection profile = null;
            try {
                SavedSshConnection.Record saved = SavedSshConnection.read(credentials);
                if (saved != null) profile = saved.profile;
            } catch (IOException ignored) {
                // The editable connection fields are available independently of the encrypted record.
            }
            SavedSshConnection loaded = profile;
            FrpRoute.Choice frpChoice = readFrpChoice();
            main.post(() -> {
                if (destroyed) return;
                savedConnection = loaded;
                importedFrpChoice = frpChoice;
                if (!hasDraft && loaded != null) {
                    lastUsername = loaded.username;
                    preferFrp = loaded.frp;
                    if (!loaded.frp) {
                        lastDirect = new SshConnection.Profile(loaded.host, loaded.port, loaded.username);
                    }
                }
                loadingConnection = false;
                refreshConnection();
            });
        });
    }

    private FrpRoute.Choice readFrpChoice() {
        byte[] profile = null;
        try {
            profile = credentials.read(LocalCredentials.Slot.FRP_PROFILE);
            if (profile == null) return null;
            List<FrpRoute.Choice> choices = FrpRoute.inspect(profile);
            return choices.size() == 1 ? choices.get(0) : null;
        } catch (Exception unavailable) {
            return null;
        } finally {
            clearBytes(profile);
        }
    }

    private boolean matchesSavedConnection(SavedSshConnection saved, boolean frp) {
        return saved != null && saved.matchesForm(frp, lastDirect, lastUsername,
                importedFrpChoice == null ? null : importedFrpChoice.identity);
    }

    private boolean hasConnection() {
        return connection != null && connection.isOpen();
    }

    private void refreshConnection() {
        boolean connected = hasConnection();
        int progress = connecting ? connectionProgress : 0;
        boolean canReconnect = !connected && !loadingConnection && reusableConnection() != null;
        for (WorkspacePane pane : panes) pane.updateConnection(connected, progress, canReconnect);
        for (WorkspacePane pane : minimized) pane.updateConnection(connected, progress, canReconnect);
    }

    void terminalEnded() {
        if (connection != null && !connection.isOpen()) {
            transportClosed(connection);
        } else {
            refreshConnection();
        }
    }

    private void transportClosed(RemoteConnection ended) {
        if (destroyed || connection != ended) return;
        connection = null;
        endConnectionTrace(Outcome.TRANSPORT_LOST);
        connectedEndpoint = null;
        connectedUsername = null;
        for (WorkspacePane pane : panes) pane.transportLost();
        for (WorkspacePane pane : minimized) pane.transportLost();
        onIo(ended::close);
        refreshConnection();
    }

    private void disconnect() {
        RemoteConnection previous = connection;
        connection = null;
        endConnectionTrace(Outcome.DISCONNECTED);
        connectedEndpoint = null;
        connectedUsername = null;
        for (WorkspacePane pane : panes) pane.detach();
        for (WorkspacePane pane : minimized) pane.detach();
        if (previous != null) onIo(previous::close);
        refreshConnection();
    }

    DiagnosticLog.Trace diagnosticTrace(RemoteConnection target) {
        return connection == target ? connectionTrace : null;
    }

    private void endConnectionTrace(Outcome outcome) {
        if (connectionTrace == null) return;
        connectionTrace.record(Stage.SESSION, outcome);
        connectionTrace = null;
    }

    void chooseDirectory(WorkspacePane pane) {
        chooseDirectory(pane, pane.directoryFor(connectedEndpoint, connectedUsername));
    }

    private void chooseDirectory(WorkspacePane pane, String suggestedDirectory) {
        if (connecting) return;
        RemoteConnection current = connection;
        if (current == null || !current.isOpen()) {
            connectOrConfigure(pane, false);
            return;
        }
        RemoteDirectoryDialog.show(this, current, io,
                suggestedDirectory.isEmpty() ? "~" : suggestedDirectory, directory -> {
                    if (connection != current || !panes.contains(pane)) return;
                    if (pane.hasLiveTerminal()) {
                        new AlertDialog.Builder(this)
                                .setTitle(R.string.workspace_open_shell_title)
                                .setMessage(R.string.workspace_open_shell_message)
                                .setNegativeButton(R.string.action_cancel, null)
                                .setPositiveButton(R.string.workspace_open_shell,
                                        (dialog, which) -> openShell(pane, current, directory))
                                .show();
                    } else {
                        openShell(pane, current, directory);
                    }
                });
    }

    private void configureConnection(WorkspacePane pane) {
        if (connecting || loadingConnection || hasConnection()) return;
        boolean hasProfile = credentials.exists(LocalCredentials.Slot.FRP_PROFILE);
        boolean mode = hasDraft ? preferFrp : savedConnection != null ? savedConnection.frp : hasProfile;
        SavedSshConnection previous = savedConnection;
        boolean matches = matchesSavedConnection(previous, mode);
        boolean savedKeyLogin = matches && previous.usesKey();
        AuthMode authMode = draftAuthMode != null ? draftAuthMode : savedKeyLogin ? AuthMode.KEY : AuthMode.PASSWORD;
        boolean hasKey = credentials.exists(LocalCredentials.Slot.SSH_PRIVATE_KEY);
        boolean preferKey = savedKeyLogin && previous.hasKeyCopy && hasKey;
        new ConnectionDialog(this, lastDirect, lastUsername,
                mode, authMode, importedFrpChoice,
                hasProfile, hasKey, preferKey,
                matches && !savedKeyLogin && previous.hasSecret,
                preferKey && previous.hasSecret,
                new ConnectionDialog.Listener() {
                    @Override
                    public void pickSshConfig(ConnectionDialog form) {
                        launchDocumentPicker(form, PICK_SSH_CONFIG);
                    }

                    @Override
                    public void pickFrpProfile(ConnectionDialog form) {
                        launchDocumentPicker(form, PICK_FRP_PROFILE);
                    }

                    @Override
                    public void pickSshKey(ConnectionDialog form) {
                        launchDocumentPicker(form, PICK_SSH_KEY);
                    }

                    @Override
                    public void forgetFrpProfile(ConnectionDialog form) {
                        forgetCredential(form, LocalCredentials.Slot.FRP_PROFILE);
                    }

                    @Override
                    public void forgetSshKey(ConnectionDialog form) {
                        forgetCredential(form, LocalCredentials.Slot.SSH_PRIVATE_KEY);
                    }

                    @Override
                    public void forgetSshSecret(ConnectionDialog form, boolean passphrase) {
                        MainActivity.this.forgetSshSecret(form, passphrase);
                    }

                    @Override
                    public void connect(ConnectionDialog.Request request) {
                        MainActivity.this.connect(pane, request, null, null, false);
                    }
                }).show();
    }

    private void launchDocumentPicker(ConnectionDialog form, int requestCode) {
        if (pendingPicker != null || importingProfile) return;
        Intent picker = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        picker.addCategory(Intent.CATEGORY_OPENABLE);
        picker.setType("*/*");
        picker.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        pendingPicker = form;
        try {
            startActivityForResult(picker, requestCode);
        } catch (ActivityNotFoundException unavailable) {
            pendingPicker = null;
            toast(getString(R.string.connection_picker_unavailable));
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent result) {
        super.onActivityResult(requestCode, resultCode, result);
        if (requestCode != PICK_SSH_KEY && requestCode != PICK_FRP_PROFILE
                && requestCode != PICK_SSH_CONFIG) return;
        ConnectionDialog form = pendingPicker;
        pendingPicker = null;
        if (form == null || !form.isShowing() || resultCode != RESULT_OK || result == null) return;
        Uri uri = result.getData();
        if (uri == null || !ContentResolver.SCHEME_CONTENT.equals(uri.getScheme())) return;
        if (requestCode == PICK_FRP_PROFILE) {
            importFrpProfile(form, uri);
        } else if (requestCode == PICK_SSH_CONFIG) {
            importSshConfig(form, uri);
        } else {
            io.execute(() -> {
                String name = documentName(uri);
                main.post(() -> {
                    if (!destroyed && form.isShowing()) form.setKey(uri, name);
                });
            });
        }
    }

    private String documentName(Uri uri) {
        try (Cursor result = getContentResolver().query(uri,
                new String[] {OpenableColumns.DISPLAY_NAME}, null, null, null)) {
            if (result != null && result.moveToFirst()) {
                String name = result.getString(0);
                if (name != null) {
                    name = name.replaceAll("[\\p{Cntrl}\\p{Cf}]", "").trim();
                    if (!name.isEmpty()) return name.substring(0, Math.min(name.length(), 120));
                }
            }
        } catch (RuntimeException ignored) {
            // A file provider may omit metadata. The temporary content grant is still usable.
        }
        return getString(R.string.connection_ssh_key_selected);
    }

    private void importSshConfig(ConnectionDialog form, Uri uri) {
        io.execute(() -> {
            byte[] config = null;
            try {
                config = readDocument(uri, OpenSshConfig.MAX_BYTES);
                List<OpenSshConfig.Entry> entries = OpenSshConfig.parse(config);
                main.post(() -> chooseSshAlias(form, entries));
            } catch (IOException failure) {
                main.post(() -> {
                    if (!destroyed && form.isShowing()) {
                        showError(R.string.connection_import_ssh_failed, UiErrors.message(this, failure));
                    }
                });
            } finally {
                clearBytes(config);
            }
        });
    }

    private void chooseSshAlias(ConnectionDialog form, List<OpenSshConfig.Entry> entries) {
        if (destroyed || !form.isShowing()) return;
        boolean frp = form.isFrp();
        List<OpenSshConfig.Entry> choices = new ArrayList<>(entries.size());
        for (OpenSshConfig.Entry entry : entries) {
            if (!frp || !TextUtils.isEmpty(entry.username)) choices.add(entry);
        }
        if (choices.isEmpty()) {
            showError(R.string.connection_config_no_user_title, getString(R.string.connection_config_no_user_message));
            return;
        }
        String[] labels = new String[choices.size()];
        for (int index = 0; index < choices.size(); index++) {
            labels[index] = choices.get(index).alias;
        }
        new AlertDialog.Builder(this)
                .setTitle(R.string.connection_choose_host_alias)
                .setItems(labels, (dialog, index) -> {
                    OpenSshConfig.Entry entry = choices.get(index);
                    if (!frp && entry.requiresProxy) {
                        showError(R.string.connection_alias_proxy_title, getString(R.string.connection_alias_proxy_message));
                    } else {
                        form.applySshAlias(entry.alias, entry.hostName, entry.port, entry.username);
                    }
                })
                .setNegativeButton(R.string.action_cancel, null)
                .show();
    }

    private void importFrpProfile(ConnectionDialog form, Uri uri) {
        if (importingProfile) return;
        importingProfile = true;
        form.setFrpImportPending(true);
        io.execute(() -> {
            byte[] config = null;
            try {
                config = readDocument(uri, FrpRoute.MAX_CONFIG_BYTES);
                List<FrpRoute.Choice> choices = FrpRoute.inspect(config);
                if (choices.isEmpty()) {
                    throw new OperationException(Reason.FRP_PROFILE_NEEDS_VISITOR, "No enabled STCP visitors.");
                }
                byte[] imported = config;
                main.post(() -> chooseFrpVisitor(form, imported, choices));
                config = null;
            } catch (Exception failure) {
                main.post(() -> finishFrpImport(form, null, failure));
            } finally {
                clearBytes(config);
            }
        });
    }

    private void chooseFrpVisitor(ConnectionDialog form, byte[] config, List<FrpRoute.Choice> choices) {
        if (destroyed || !form.isShowing()) {
            importingProfile = false;
            clearBytes(config);
            return;
        }
        if (choices.size() == 1) {
            saveFrpProfile(form, config, choices.get(0));
            return;
        }
        String[] labels = new String[choices.size()];
        for (int index = 0; index < choices.size(); index++) {
            labels[index] = choices.get(index).label;
        }
        boolean[] submitted = {false};
        AlertDialog picker = new AlertDialog.Builder(this)
                .setTitle(R.string.connection_choose_stcp_visitor)
                .setItems(labels, (dialog, index) -> {
                    submitted[0] = true;
                    saveFrpProfile(form, config, choices.get(index));
                })
                .setNegativeButton(R.string.action_cancel, null)
                .create();
        picker.setOnDismissListener(dialog -> {
            if (!submitted[0]) {
                clearBytes(config);
                finishFrpImport(form, null, null);
            }
        });
        picker.show();
    }

    private void saveFrpProfile(ConnectionDialog form, byte[] config, FrpRoute.Choice choice) {
        io.execute(() -> {
            byte[] selected = null;
            try {
                selected = FrpRoute.select(config, choice.name);
                credentials.save(LocalCredentials.Slot.FRP_PROFILE, selected);
                main.post(() -> {
                    importedFrpChoice = choice;
                    finishFrpImport(form, choice, null);
                });
            } catch (Exception failure) {
                main.post(() -> finishFrpImport(form, null, failure));
            } finally {
                clearBytes(config);
                clearBytes(selected);
            }
        });
    }

    private void finishFrpImport(ConnectionDialog form, FrpRoute.Choice choice, Exception failure) {
        importingProfile = false;
        if (destroyed || !form.isShowing()) return;
        if (choice != null) form.setFrpProfile(choice, true);
        form.setFrpImportPending(false);
        if (failure != null) showError(R.string.connection_import_frp_failed, UiErrors.message(this, failure));
    }

    private void forgetCredential(ConnectionDialog form, LocalCredentials.Slot slot) {
        if (importingProfile) return;
        io.execute(() -> {
            try {
                credentials.forget(slot);
                SavedSshConnection afterForget = slot == LocalCredentials.Slot.SSH_PRIVATE_KEY
                        ? SavedSshConnection.forgetSecret(credentials, true) : null;
                main.post(() -> {
                    if (destroyed) return;
                    if (slot == LocalCredentials.Slot.SSH_PRIVATE_KEY) savedConnection = afterForget;
                    if (slot == LocalCredentials.Slot.FRP_PROFILE) importedFrpChoice = null;
                    refreshConnection();
                    if (!form.isShowing()) return;
                    if (slot == LocalCredentials.Slot.FRP_PROFILE) {
                        form.setFrpProfile(null, false);
                    } else {
                        form.setSavedKeyPresent(false);
                        form.setSavedSecretPresent(true, false);
                    }
                });
            } catch (IOException failure) {
                main.post(() -> {
                    if (!destroyed && form.isShowing()) {
                        showError(R.string.connection_remove_saved_file_failed, UiErrors.message(this, failure));
                    }
                });
            }
        });
    }

    private void forgetSshSecret(ConnectionDialog form, boolean passphrase) {
        form.setSavedSecretPresent(passphrase, false);
        io.execute(() -> {
            try {
                SavedSshConnection updated = SavedSshConnection.forgetSecret(credentials, passphrase);
                main.post(() -> {
                    if (destroyed) return;
                    savedConnection = updated;
                    refreshConnection();
                });
            } catch (IOException failure) {
                main.post(() -> {
                    if (destroyed || !form.isShowing()) return;
                    form.setSavedSecretPresent(passphrase, true);
                    showError(R.string.connection_forget_login_failed, UiErrors.message(this, failure));
                });
            }
        });
    }

    private byte[] readDocument(Uri uri, int limit) throws IOException {
        byte[] buffer = new byte[limit + 1];
        try {
            int length = 0;
            try (InputStream input = getContentResolver().openInputStream(uri)) {
                if (input == null) throw new IOException();
                while (length < buffer.length) {
                    int count = input.read(buffer, length, buffer.length - length);
                    if (count < 0) break;
                    if (count == 0) throw new IOException();
                    length += count;
                }
            } catch (IOException | RuntimeException failure) {
                throw new OperationException(Reason.DOCUMENT_READ_FAILED, "Could not read the selected document.", failure);
            }
            if (length == 0 || length > limit) {
                throw new OperationException(Reason.DOCUMENT_SIZE, limit / 1024, "The document is empty or too large.");
            }
            return Arrays.copyOf(buffer, length);
        } finally {
            clearBytes(buffer);
        }
    }

    private static void clearBytes(byte[] buffer) {
        if (buffer != null) Arrays.fill(buffer, (byte) 0);
    }

    private void connect(WorkspacePane pane, ConnectionDialog.Request request,
                         SshConnection.HostKeyApproval approval, SavedSshConnection expected,
                         boolean reopenDirectory) {
        if (connecting) return;
        if (hasConnection()) disconnect();
        else if (connection != null) transportClosed(connection);
        hasDraft = true;
        preferFrp = request.frp;
        draftAuthMode = request.authMode;
        lastUsername = request.username;
        if (!request.frp) lastDirect = new SshConnection.Profile(request.host, request.port, request.username);
        SshConnection.Profile direct = lastDirect;
        SharedPreferences.Editor draft = preferences.edit().putBoolean("hasDraft", true)
                .putBoolean("frp", preferFrp).putBoolean("keyLogin", draftAuthMode == AuthMode.KEY)
                .putString("username", lastUsername);
        if (direct != null) draft.putString("host", direct.host).putInt("port", direct.port);
        draft.apply();
        connecting = true;
        connectionProgress = request.frp ? R.string.starting_frp : R.string.connecting_ssh;
        refreshConnection();
        File pins = new File(getFilesDir(), "ssh-host-pins");
        DiagnosticLog.Trace trace = diagnostics.begin(
                request.frp ? DiagnosticLog.Route.FRP_STCP : DiagnosticLog.Route.DIRECT,
                request.authMode == AuthMode.KEY ? DiagnosticLog.Auth.KEY : DiagnosticLog.Auth.PASSWORD);
        pendingConnectionTrace = trace;
        trace.record(Stage.PREPARE, Outcome.STARTED);
        io.execute(() -> establish(pane, request, approval, expected, direct, pins, reopenDirectory, trace));
    }

    private void establish(WorkspacePane pane, ConnectionDialog.Request request,
                           SshConnection.HostKeyApproval approval, SavedSshConnection expected,
                           SshConnection.Profile direct, File pins, boolean reopenDirectory,
                           DiagnosticLog.Trace trace) {
        FrpRoute route = null;
        byte[] frpConfig = null;
        byte[] key = null;
        byte[] keyToSave = null;
        Stage stage = Stage.PREPARE;
        AtomicBoolean hostVerified = new AtomicBoolean();
        try {
            boolean savedKey = request.authMode == AuthMode.KEY && request.useSavedKey;
            if (request.authMode == AuthMode.KEY) {
                if (savedKey) {
                    key = credentials.read(LocalCredentials.Slot.SSH_PRIVATE_KEY);
                    if (key == null) throw new OperationException(Reason.SSH_KEY_REQUIRED, "Saved SSH key is missing.");
                } else {
                    if (request.keyUri == null) throw new OperationException(Reason.SSH_KEY_REQUIRED, "No SSH key selected.");
                    key = readDocument(request.keyUri, SshConnection.MAX_PRIVATE_KEY_BYTES);
                    if (request.saveKey) keyToSave = key.clone();
                }
            }
            String keyDigest = SavedSshConnection.digest(key);
            String endpoint = direct == null ? "" : direct.endpoint();
            if (request.frp) {
                frpConfig = credentials.read(LocalCredentials.Slot.FRP_PROFILE);
                if (frpConfig == null) {
                    throw new OperationException(Reason.FRP_PROFILE_REQUIRED, "Saved FRP profile is missing.");
                }
                List<FrpRoute.Choice> choices = FrpRoute.inspect(frpConfig);
                if (choices.size() != 1) {
                    throw new OperationException(Reason.FRP_PROFILE_SELECTION_FAILED, "Expected one saved FRP visitor.");
                }
                FrpRoute.Choice choice = choices.get(0);
                endpoint = choice.identity;
            }
            if (expected != null && !expected.matchesAuthentication(endpoint, request.username, keyDigest)) {
                throw new OperationException(Reason.SAVED_LOGIN_MISMATCH, "The saved route or SSH key changed.");
            }
            String secret = request.secret;
            if (request.useSavedSecret) {
                SavedSshConnection.Record stored = SavedSshConnection.read(credentials);
                if (stored == null || !stored.profile.hasSecret
                        || !stored.profile.matchesAuthentication(endpoint, request.username, keyDigest)) {
                    throw new OperationException(Reason.SAVED_LOGIN_MISMATCH, "Saved login does not match the connection.");
                }
                secret = stored.secret;
            }
            trace.record(Stage.PREPARE, Outcome.READY);
            SshConnection.Profile profile;
            SshConnection handshake;
            for (int attempt = 0; ; attempt++) {
                profile = direct;
                if (request.frp) {
                    stage = Stage.FRP_RELAY;
                    trace.record(stage, Outcome.STARTED);
                    route = FrpRoute.start(frpConfig);
                    trace.record(stage, Outcome.READY);
                    profile = new SshConnection.Profile("127.0.0.1", route.port(), request.username, endpoint);
                    if (attempt == 0) postConnectionProgress(R.string.connecting_ssh);
                }
                stage = Stage.SSH_HANDSHAKE;
                trace.record(stage, Outcome.STARTED);
                int timeout = route == null ? SshConnection.DEFAULT_CONNECT_TIMEOUT_MS : FrpRoute.SSH_CONNECT_TIMEOUT_MS;
                // SSH takes ownership of the bytes. A fresh FRP route needs its own copy.
                byte[] attemptKey = route == null || key == null ? key : key.clone();
                try {
                    handshake = SshConnection.connect(profile, secret, attemptKey, pins, approval,
                            route, route == null ? null : route::close, timeout,
                            () -> {
                                if (hostVerified.compareAndSet(false, true)) {
                                    trace.record(Stage.HOST_KEY, Outcome.READY);
                                }
                            }, ended -> main.post(() -> transportClosed(ended)));
                    break;
                } catch (Exception failure) {
                    if (attempt != 0 || route == null || hostVerified.get() || !foreground || destroyed
                            || !FrpRoute.isSshTimeout(failure) || !route.failureHint().isEmpty()) throw failure;
                    trace.record(stage, Outcome.TIMEOUT);
                    trace.record(stage, Outcome.RETRYING);
                    route.close();
                    route = null;
                    postConnectionProgress(R.string.retrying_frp_ssh);
                }
            }
            SshConnection result = handshake;
            trace.record(Stage.SSH_AUTH, Outcome.READY);
            route = null;
            if (destroyed) {
                result.close();
                return;
            }
            boolean saveFailed = false;
            if (keyToSave != null) {
                try {
                    credentials.save(LocalCredentials.Slot.SSH_PRIVATE_KEY, keyToSave);
                    savedKey = true;
                } catch (IOException unavailable) {
                    saveFailed = true;
                }
            }
            boolean shouldRemember = !secret.isEmpty() && (request.useSavedSecret || request.rememberSecret)
                    && (keyDigest.isEmpty() || savedKey);
            SavedSshConnection remembered = new SavedSshConnection(request.frp,
                    request.frp ? "" : profile.host, request.frp ? 0 : profile.port,
                    profile.username, endpoint, keyDigest, savedKey, !secret.isEmpty(), shouldRemember);
            try {
                remembered.write(credentials, shouldRemember ? secret : "");
            } catch (IOException unavailable) {
                remembered = null;
                saveFailed = true;
            }
            SavedSshConnection saved = remembered;
            String connectedRoute = endpoint;
            boolean warn = saveFailed;
            main.post(() -> {
                if (destroyed) {
                    onIo(result::close);
                    return;
                }
                connection = result;
                connectionTrace = trace;
                pendingConnectionTrace = null;
                if (saved != null) savedConnection = saved;
                connectedEndpoint = connectedRoute;
                connectedUsername = request.username;
                connecting = false;
                if (!result.isOpen()) {
                    transportClosed(result);
                    return;
                }
                trace.record(Stage.SESSION, Outcome.READY);
                refreshConnection();
                if (warn) toast(getString(R.string.connection_save_login_warning));
                WorkspacePane selected = panes.contains(pane) ? pane : panes.get(0);
                String recent = connectedRoute.equals(preferences.getString("directoryEndpoint", ""))
                        && request.username.equals(preferences.getString("directoryUser", ""))
                        ? preferences.getString("directory", "") : "";
                if (reopenDirectory) {
                    String paneDirectory = selected.reconnectDirectory(connectedRoute, request.username);
                    reopenDirectory(selected, result, paneDirectory.isEmpty() ? recent : paneDirectory);
                } else {
                    chooseDirectory(selected, recent);
                }
            });
        } catch (SshConnection.UnknownHostKeyException unknown) {
            trace.record(Stage.HOST_KEY, Outcome.APPROVAL_REQUIRED);
            main.post(() -> {
                if (destroyed) return;
                pendingConnectionTrace = null;
                connecting = false;
                refreshConnection();
                new AlertDialog.Builder(this)
                        .setTitle(R.string.connection_verify_host)
                        .setMessage(getString(R.string.connection_verify_host_details,
                                unknown.endpoint, unknown.keyType, unknown.fingerprint))
                        .setNegativeButton(R.string.action_cancel, null)
                        .setPositiveButton(R.string.connection_trust_host,
                                (dialog, which) -> connect(pane, request, unknown.approval, expected, reopenDirectory))
                        .show();
            });
        } catch (Exception failure) {
            Outcome outcome = connectionFailureOutcome(route, failure);
            Stage failedStage = stage;
            if (outcome == Outcome.STCP_OPEN_FAILED) failedStage = Stage.STCP;
            else if (outcome == Outcome.HOST_CHANGED) failedStage = Stage.HOST_KEY;
            else if (hostVerified.get() || outcome == Outcome.AUTH_REJECTED) failedStage = Stage.SSH_AUTH;
            trace.record(failedStage, outcome);
            int routeMessage = connectionFailureResource(route, outcome);
            main.post(() -> {
                if (destroyed) return;
                pendingConnectionTrace = null;
                connecting = false;
                refreshConnection();
                AlertDialog.Builder dialog = new AlertDialog.Builder(this)
                        .setTitle(request.frp ? R.string.connection_frp_failed : R.string.connection_ssh_failed)
                        .setMessage(routeMessage == 0 ? UiErrors.message(this, failure) : getString(routeMessage))
                        .setNeutralButton(R.string.connection_setup, (view, which) -> configureConnection(selectedPane()));
                if (outcome == Outcome.TIMEOUT && expected != null && reusableConnection() != null) {
                    dialog.setNegativeButton(R.string.action_cancel, null)
                            .setPositiveButton(R.string.remote_retry, (view, which) -> {
                                WorkspacePane selected = panes.contains(pane) ? pane : selectedPane();
                                connectOrConfigure(selected, reopenDirectory);
                            });
                } else {
                    dialog.setPositiveButton(R.string.action_ok, null);
                }
                dialog.show();
            });
        } finally {
            if (route != null) route.close();
            clearBytes(frpConfig);
            clearBytes(key);
            clearBytes(keyToSave);
        }
    }

    private static Outcome connectionFailureOutcome(FrpRoute route, Exception failure) {
        if (route != null && !route.failureHint().isEmpty()) return Outcome.STCP_OPEN_FAILED;
        if (failure instanceof SshConnection.ChangedHostKeyException) return Outcome.HOST_CHANGED;
        if (failure instanceof JSchAlgoNegoFailException) return Outcome.NEGOTIATION_FAILED;
        if (failure instanceof OperationException && ((OperationException) failure).reason == Reason.FRP_RELAY_TIMEOUT) {
            return Outcome.TIMEOUT;
        }
        if (FrpRoute.isSshTimeout(failure)) return Outcome.TIMEOUT;
        if (UiErrors.isAuthenticationFailure(failure)) return Outcome.AUTH_REJECTED;
        return Outcome.FAILED;
    }

    private static int connectionFailureResource(FrpRoute route, Outcome outcome) {
        if (route != null) {
            if (outcome == Outcome.STCP_OPEN_FAILED) return R.string.frp_stcp_route_failed;
            if (outcome == Outcome.TIMEOUT) return R.string.frp_ssh_response_timeout;
        }
        return 0;
    }

    private void postConnectionProgress(int progress) {
        main.post(() -> {
            if (destroyed) return;
            connectionProgress = progress;
            refreshConnection();
        });
    }

    private void reopenDirectory(WorkspacePane pane, RemoteConnection current, String directory) {
        if (directory.isEmpty()) {
            chooseDirectory(pane, "");
        } else {
            pane.open(current, directory, () -> {
                if (connection == current && panes.contains(pane)) chooseDirectory(pane, "");
            });
            pane.focus();
        }
    }

    private void showError(int title, String message) {
        new AlertDialog.Builder(this)
                .setTitle(title)
                .setMessage(message)
                .setPositiveButton(R.string.action_ok, null)
                .show();
    }

    private void toast(String message) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show();
    }

    private void openShell(WorkspacePane pane, RemoteConnection current, String directory) {
        if (connection != current || !panes.contains(pane)) return;
        pane.open(current, directory);
        pane.focus();
    }

    void directoryOpened(WorkspacePane pane, RemoteConnection current, String directory) {
        if (connection == current && connectedEndpoint != null && connectedUsername != null) {
            pane.setDirectoryIdentity(connectedEndpoint, connectedUsername);
            preferences.edit().putString("directoryEndpoint", connectedEndpoint)
                    .putString("directoryUser", connectedUsername).putString("directory", directory).apply();
        }
    }

    Button addAction(ViewGroup row, String label, Runnable click) {
        Button button = addAction(row, label);
        button.setOnClickListener(view -> click.run());
        return button;
    }

    ImageButton addIconAction(ViewGroup row, int icon, String label, View.OnClickListener click) {
        ImageButton button = LocusChrome.iconAction(this, icon, label);
        button.setOnClickListener(click);
        row.addView(button, new LinearLayout.LayoutParams(dp(48), dp(48)));
        return button;
    }

    Button addAction(ViewGroup row, String label) {
        Button button = new Button(this, null, android.R.attr.borderlessButtonStyle);
        button.setText(label);
        button.setAllCaps(false);
        button.setMinWidth(0);
        button.setMinimumWidth(0);
        button.setMinHeight(dp(48));
        button.setMinimumHeight(dp(48));
        button.setTextColor(LocusChrome.actionText(this, false));
        button.setBackground(LocusChrome.action(this, false));
        button.setPadding(dp(11), 0, dp(11), 0);
        row.addView(button, new LinearLayout.LayoutParams(-2, -2));
        return button;
    }

    void hideKeyboard(View window) {
        InputMethodManager keyboard = (InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);
        keyboard.hideSoftInputFromWindow(window.getWindowToken(), 0);
    }

    boolean isKeyboardVisible(View view) {
        WindowInsets insets = view.getRootWindowInsets();
        if (insets == null) return false;
        if (Build.VERSION.SDK_INT >= 30) return insets.isVisible(WindowInsets.Type.ime());
        View decor = getWindow().getDecorView();
        Rect visible = new Rect();
        decor.getWindowVisibleDisplayFrame(visible);
        int[] location = new int[2];
        decor.getLocationOnScreen(location);
        int coveredBottom = location[1] + decor.getHeight() - visible.bottom;
        return Math.max(coveredBottom, insets.getSystemWindowInsetBottom()) > insets.getStableInsetBottom() + dp(80);
    }

    int dp(float value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    void onIo(Runnable task) {
        try {
            io.execute(task);
        } catch (RejectedExecutionException closedActivity) {
            // A completed connection can reach the main queue as this Activity is destroyed.
            task.run();
        }
    }

    @Override
    protected void onStart() {
        super.onStart();
        foreground = true;
        recordConnectionLifecycle(Outcome.FOREGROUNDED);
        terminalEnded();
    }

    @Override
    protected void onStop() {
        foreground = false;
        recordConnectionLifecycle(Outcome.BACKGROUNDED);
        shelf.dismiss();
        super.onStop();
    }

    private void recordConnectionLifecycle(Outcome outcome) {
        DiagnosticLog.Trace trace = connectionTrace == null ? pendingConnectionTrace : connectionTrace;
        if (trace != null) trace.record(Stage.LIFECYCLE, outcome);
    }

    @Override
    protected void onDestroy() {
        destroyed = true;
        endConnectionTrace(Outcome.DISCONNECTED);
        for (WorkspacePane pane : panes) pane.dispose();
        for (WorkspacePane pane : minimized) pane.dispose();
        if (connection != null) onIo(connection::close);
        io.shutdown();
        super.onDestroy();
    }

}
