package dev.locus.app;

import android.app.AlertDialog;
import android.content.res.ColorStateList;
import android.graphics.Typeface;
import android.graphics.drawable.RippleDrawable;
import android.graphics.drawable.StateListDrawable;
import android.net.Uri;
import android.text.Editable;
import android.text.InputType;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.ScrollView;
import android.widget.TextView;

/** Collects an SSH connection request and delegates document selection to the activity. */
final class ConnectionDialog {
    enum AuthMode { PASSWORD, KEY }

    interface Listener {
        void pickFrpProfile(ConnectionDialog form);
        void pickSshConfig(ConnectionDialog form);
        void pickSshKey(ConnectionDialog form);
        void forgetFrpProfile(ConnectionDialog form);
        void forgetSshKey(ConnectionDialog form);
        void forgetSshSecret(ConnectionDialog form, boolean passphrase);
        void connect(Request request);
    }

    static final class Request {
        final boolean frp;
        final AuthMode authMode;
        final String host;
        final int port;
        final String username;
        final String secret;
        final Uri keyUri;
        final boolean useSavedKey;
        final boolean saveKey;
        final boolean useSavedSecret;
        final boolean rememberSecret;

        Request(boolean frp, AuthMode authMode, String host, int port, String username, String secret,
                Uri keyUri, boolean useSavedKey, boolean saveKey,
                boolean useSavedSecret, boolean rememberSecret) {
            this.frp = frp;
            this.authMode = authMode;
            this.host = host;
            this.port = port;
            this.username = username;
            this.secret = secret;
            this.keyUri = keyUri;
            this.useSavedKey = useSavedKey;
            this.saveKey = saveKey;
            this.useSavedSecret = useSavedSecret;
            this.rememberSecret = rememberSecret;
        }
    }

    private final MainActivity activity;
    private final Listener listener;
    private final AlertDialog dialog;
    private final LinearLayout directFields;
    private final Button openNativeFrp;
    private final LinearLayout frpFields;
    private final TextView frpSummary;
    private final Button chooseFrpProfile;
    private final Button forgetFrpProfile;
    private final TextView sshAlias;
    private final EditText host;
    private final EditText port;
    private final EditText username;
    private final LinearLayout keyFields;
    private final LinearLayout secretFields;
    private final EditText secret;
    private final TextView secretLabel;
    private final Button chooseKey;
    private final Button useSavedKeyAction;
    private final Button forgetSshKey;
    private final TextView keySummary;
    private final CheckBox saveKey;
    private final Button savedSecretAction;
    private final Button forgetSavedSecret;
    private final CheckBox rememberSecret;
    private final boolean rememberedFrp;
    private final boolean preferSavedKey;
    private final String rememberedHost;
    private final int rememberedPort;
    private final String rememberedUsername;

    private boolean frp;
    private AuthMode authMode;
    private boolean hasFrpProfile;
    private boolean frpProfileChanged;
    private boolean frpImportPending;
    private FrpRoute.Choice frpChoice;
    private boolean hasSavedKey;
    private boolean useSavedKey;
    private boolean autoSelectedKey;
    private boolean hasSavedPassword;
    private boolean hasSavedPassphrase;
    private boolean useSavedSecret;
    private Uri keyUri;
    private String keyName;

    ConnectionDialog(MainActivity activity, SshConnection.Profile lastDirect, String username,
                     boolean preferFrp, AuthMode authMode, FrpRoute.Choice frpChoice, boolean hasFrpProfile,
                     boolean hasSavedKey,
                     boolean preferSavedKey, boolean hasSavedPassword, boolean hasSavedPassphrase,
                     Listener listener) {
        this.activity = activity;
        this.listener = listener;
        this.frp = preferFrp;
        this.authMode = authMode;
        this.frpChoice = frpChoice;
        this.hasFrpProfile = hasFrpProfile;
        this.hasSavedKey = hasSavedKey;
        this.preferSavedKey = preferSavedKey;
        this.useSavedKey = authMode == AuthMode.KEY && preferSavedKey;
        this.autoSelectedKey = useSavedKey;
        this.hasSavedPassword = hasSavedPassword;
        this.hasSavedPassphrase = hasSavedPassphrase;
        rememberedFrp = preferFrp;
        rememberedHost = lastDirect == null ? "" : lastDirect.host;
        rememberedPort = lastDirect == null ? 22 : lastDirect.port;

        int text = InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS;
        LinearLayout fields = column();
        fields.setPadding(activity.dp(16), activity.dp(4), activity.dp(16), activity.dp(12));
        fields.setFocusableInTouchMode(true);
        LinearLayout routeSection = section(fields, "Connection method", 0);
        RadioGroup modes = modeGroup();
        RadioButton directMode = mode(modes, "Direct SSH");
        RadioButton frpMode = mode(modes, "FRP STCP");
        routeSection.addView(modes);

        directFields = new LinearLayout(activity);
        LinearLayout hostField = column();
        host = field(hostField, null, "Host or IP address", rememberedHost, text);
        directFields.addView(hostField, new LinearLayout.LayoutParams(0, -2, 1));
        LinearLayout portField = column();
        port = field(portField, null, "Port", Integer.toString(rememberedPort), InputType.TYPE_CLASS_NUMBER);
        LinearLayout.LayoutParams portLayout = new LinearLayout.LayoutParams(activity.dp(84), -2);
        portLayout.setMarginStart(activity.dp(10));
        directFields.addView(portField, portLayout);
        addSpaced(routeSection, directFields, 10);
        LinearLayout localActions = new LinearLayout(activity);
        openNativeFrp = activity.addAction(localActions, activity.getString(R.string.open_native_frp));
        openNativeFrp.setOnClickListener(view -> {
            boolean keepKey = this.authMode == AuthMode.KEY && useSavedKey;
            modes.check(frpMode.getId());
            if (keepKey) selectKey(null, null, true, false);
        });
        routeSection.addView(localActions);

        frpFields = column();
        TextView frpHelp = supportingText();
        frpHelp.setText(R.string.native_frp_help);
        frpFields.addView(frpHelp);
        frpSummary = supportingText();
        frpSummary.setTextColor(color(R.color.text_primary));
        frpSummary.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        frpSummary.setMaxLines(3);
        frpSummary.setEllipsize(TextUtils.TruncateAt.END);
        addSpaced(frpFields, frpSummary, 6);
        LinearLayout frpActions = new LinearLayout(activity);
        chooseFrpProfile = activity.addAction(frpActions, "Import FRP profile",
                () -> listener.pickFrpProfile(this));
        forgetFrpProfile = activity.addAction(frpActions, "Forget profile",
                () -> listener.forgetFrpProfile(this));
        frpFields.addView(frpActions);
        addSpaced(routeSection, frpFields, 10);
        refreshFrpProfile();

        String previousUsername = username;
        if ((previousUsername == null || previousUsername.isEmpty()) && lastDirect != null) {
            previousUsername = lastDirect.username;
        }
        rememberedUsername = previousUsername == null ? "" : previousUsername.trim();
        LinearLayout loginSection = section(fields, "SSH login", 10);
        TextView usernameLabel = new TextView(activity);
        loginSection.addView(usernameLabel);
        LinearLayout usernameRow = new LinearLayout(activity);
        usernameRow.setGravity(Gravity.CENTER_VERTICAL);
        this.username = input(usernameLabel, "SSH username", previousUsername, text);
        usernameRow.addView(this.username, new LinearLayout.LayoutParams(0, -2, 1));
        Button usernameOptions = activity.addAction(usernameRow, "Options");
        usernameOptions.setContentDescription("SSH username options");
        usernameOptions.setOnClickListener(this::showUsernameOptions);
        loginSection.addView(usernameRow);
        sshAlias = supportingText();
        sshAlias.setSingleLine(true);
        sshAlias.setEllipsize(TextUtils.TruncateAt.END);
        sshAlias.setVisibility(View.GONE);
        addSpaced(loginSection, sshAlias, 4);
        RadioGroup authModes = modeGroup();
        RadioButton passwordMode = mode(authModes, "Password");
        RadioButton keyMode = mode(authModes, "SSH key");
        authModes.check(authMode == AuthMode.KEY ? keyMode.getId() : passwordMode.getId());
        addSpaced(loginSection, authModes, 10);

        keyFields = column();
        keySummary = supportingText();
        keySummary.setMaxLines(2);
        keySummary.setEllipsize(TextUtils.TruncateAt.END);
        keySummary.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        addSpaced(keyFields, keySummary, 8);
        LinearLayout keyChoices = new LinearLayout(activity);
        chooseKey = activity.addAction(keyChoices, "Choose SSH key", () -> listener.pickSshKey(this));
        useSavedKeyAction = activity.addAction(keyChoices, "Use saved key",
                () -> selectKey(null, null, true, false));
        keyFields.addView(keyChoices);
        LinearLayout keyActions = new LinearLayout(activity);
        forgetSshKey = activity.addAction(keyActions, "Forget saved key",
                () -> listener.forgetSshKey(this));
        keyFields.addView(keyActions);

        saveKey = checkbox();
        keyFields.addView(saveKey);
        loginSection.addView(keyFields);

        secretFields = column();
        secretLabel = new TextView(activity);
        secret = field(secretFields, secretLabel, "SSH password", "",
                InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        secret.setSaveEnabled(false);
        secret.setImportantForAutofill(View.IMPORTANT_FOR_AUTOFILL_NO);

        LinearLayout secretActions = new LinearLayout(activity);
        savedSecretAction = activity.addAction(secretActions, "Use saved password", this::toggleSavedSecret);
        forgetSavedSecret = activity.addAction(secretActions, "Forget saved password",
                () -> listener.forgetSshSecret(this, this.authMode == AuthMode.KEY));
        secretFields.addView(secretActions);
        rememberSecret = checkbox();
        secretFields.addView(rememberSecret);
        addSpaced(loginSection, secretFields, 10);
        saveKey.setOnCheckedChangeListener((button, checked) -> {
            if (!checked) rememberSecret.setChecked(false);
            refreshSecret();
        });
        useSavedSecret = canUseSavedSecret();
        refreshAuthentication();
        authModes.setOnCheckedChangeListener((group, id) -> {
            this.authMode = id == keyMode.getId() ? AuthMode.KEY : AuthMode.PASSWORD;
            boolean defaultKey = this.authMode == AuthMode.KEY && this.preferSavedKey
                    && this.hasSavedKey && matchesRememberedTarget();
            selectKey(null, null, defaultKey, defaultKey);
        });

        TextWatcher endpointChanges = new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence text, int start, int count, int after) {}
            @Override public void onTextChanged(CharSequence text, int start, int before, int count) {}
            @Override public void afterTextChanged(Editable value) {
                sshAlias.setVisibility(View.GONE);
                refreshNativeFrpAction();
                authenticationTargetChanged();
            }
        };
        host.addTextChangedListener(endpointChanges);
        port.addTextChangedListener(endpointChanges);
        this.username.addTextChangedListener(endpointChanges);

        modes.check(frp ? frpMode.getId() : directMode.getId());
        modes.setOnCheckedChangeListener((group, id) -> {
            frp = id == frpMode.getId();
            sshAlias.setVisibility(View.GONE);
            refreshRoute();
            refreshConnectAction();
            authenticationTargetChanged();
        });
        refreshRoute();

        ScrollView scroll = new ScrollView(activity);
        scroll.addView(fields);
        dialog = new AlertDialog.Builder(activity)
                .setTitle("Connect")
                .setView(scroll)
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Connect", null)
                .create();
        dialog.setOnDismissListener(ignored -> secret.getText().clear());
    }

    void show() {
        dialog.getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN);
        dialog.show();
        LocusChrome.fitForm(dialog);
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(view -> submit());
        refreshConnectAction();
    }

    boolean isShowing() {
        return dialog.isShowing();
    }

    boolean isFrp() {
        return frp;
    }

    void setFrpProfile(FrpRoute.Choice choice, boolean present) {
        boolean changingProfile = hasFrpProfile && (frpChoice == null || choice == null
                || !frpChoice.identity.equals(choice.identity));
        hasFrpProfile = present;
        frpChoice = choice;
        refreshFrpProfile();
        refreshNativeFrpAction();
        if (changingProfile) {
            frpProfileChanged = true;
            if (frp) authenticationTargetChanged();
        }
    }

    void setFrpImportPending(boolean pending) {
        frpImportPending = pending;
        refreshFrpProfile();
        refreshConnectAction();
    }

    private void refreshFrpProfile() {
        String defaultSummary = hasFrpProfile ? "Saved FRP profile"
                : activity.getString(R.string.native_frp_import_hint);
        String summary = frpChoice == null ? defaultSummary : safeLabel(frpChoice.label);
        if (frpChoice != null && frpChoice.hasProxyDefinitions) {
            summary += "\n" + activity.getString(R.string.native_frp_mixed_profile);
        }
        frpSummary.setText(frpImportPending ? "Reading FRP profile…" : summary);
        chooseFrpProfile.setText(hasFrpProfile ? "Replace FRP profile" : "Import FRP profile");
        chooseFrpProfile.setEnabled(!frpImportPending);
        forgetFrpProfile.setVisibility(hasFrpProfile ? View.VISIBLE : View.GONE);
        forgetFrpProfile.setEnabled(!frpImportPending);
    }

    void setSavedKeyPresent(boolean present) {
        hasSavedKey = present;
        if (!present) hasSavedPassphrase = false;
        if (!present && useSavedKey) selectKey(null, null, false, false);
        else refreshAuthentication();
    }

    void setKey(Uri uri, String displayName) {
        if (authMode == AuthMode.KEY) selectKey(uri, displayName, false, false);
    }

    void setSavedSecretPresent(boolean passphrase, boolean present) {
        if (passphrase) hasSavedPassphrase = present;
        else hasSavedPassword = present;
        if (useSavedSecret && !canUseSavedSecret()) useSavedSecret = false;
        refreshSecret();
    }

    void applySshAlias(String alias, String hostname, int remotePort, String user) {
        clearSecretChoice();
        if (!frp) {
            host.setText(hostname);
            port.setText(Integer.toString(remotePort));
        }
        if (!TextUtils.isEmpty(user)) username.setText(user);
        if (TextUtils.isEmpty(alias)) {
            sshAlias.setVisibility(View.GONE);
        } else {
            sshAlias.setText("SSH alias " + safeLabel(alias));
            sshAlias.setVisibility(View.VISIBLE);
        }
        refreshSecret();
    }

    private void selectKey(Uri uri, String displayName, boolean saved, boolean automatic) {
        keyUri = uri;
        keyName = displayName;
        useSavedKey = saved;
        autoSelectedKey = automatic;
        saveKey.setChecked(false);
        clearSecretChoice();
        useSavedSecret = canUseSavedSecret();
        refreshAuthentication();
    }

    private void refreshAuthentication() {
        boolean attached = keyUri != null;
        keyFields.setVisibility(authMode == AuthMode.KEY ? View.VISIBLE : View.GONE);
        chooseKey.setText(attached ? "Change SSH key" : "Choose SSH key");
        useSavedKeyAction.setVisibility(hasSavedKey && !useSavedKey ? View.VISIBLE : View.GONE);
        forgetSshKey.setVisibility(hasSavedKey ? View.VISIBLE : View.GONE);
        keySummary.setVisibility(hasSelectedKey() ? View.VISIBLE : View.GONE);
        if (hasSelectedKey()) {
            keySummary.setText(useSavedKey ? "Using saved SSH key"
                    : TextUtils.isEmpty(keyName) ? "SSH key selected" : safeLabel(keyName));
        }
        saveKey.setText(hasSavedKey ? "Replace the encrypted SSH key saved on this tablet"
                : "Save an encrypted copy of this SSH key on this tablet");
        saveKey.setVisibility(attached ? View.VISIBLE : View.GONE);
        refreshSecret();
    }

    private void refreshSecret() {
        boolean keyAuthentication = authMode == AuthMode.KEY;
        secretFields.setVisibility(!keyAuthentication || hasSelectedKey() ? View.VISIBLE : View.GONE);
        boolean available = canUseSavedSecret();
        String label = keyAuthentication ? "SSH key passphrase (optional)" : "SSH password";
        secretLabel.setText(label);
        secret.setContentDescription(label);
        secret.setEnabled(!useSavedSecret);
        secret.setHint(useSavedSecret
                ? keyAuthentication ? "Using saved key passphrase" : "Using saved SSH password"
                : null);
        savedSecretAction.setText(useSavedSecret ? "Enter manually"
                : keyAuthentication ? "Use saved passphrase" : "Use saved password");
        savedSecretAction.setVisibility(available ? View.VISIBLE : View.GONE);
        forgetSavedSecret.setText(keyAuthentication ? "Forget saved passphrase" : "Forget saved password");
        forgetSavedSecret.setVisibility(available ? View.VISIBLE : View.GONE);
        rememberSecret.setText(keyAuthentication ? "Remember key passphrase on this tablet"
                : "Remember SSH password on this tablet");
        boolean canRemember = !keyAuthentication || useSavedKey || saveKey.isChecked();
        rememberSecret.setVisibility(!useSavedSecret && canRemember ? View.VISIBLE : View.GONE);
    }

    private boolean hasSelectedKey() {
        return keyUri != null || useSavedKey;
    }

    private boolean canUseSavedSecret() {
        return matchesRememberedTarget()
                && (authMode == AuthMode.KEY ? useSavedKey && hasSavedPassphrase : hasSavedPassword);
    }

    private boolean matchesRememberedTarget() {
        if (rememberedFrp != frp || !rememberedUsername.equals(username.getText().toString().trim())) {
            return false;
        }
        if (frp) return hasFrpProfile && !frpProfileChanged;
        return rememberedHost.equalsIgnoreCase(host.getText().toString().trim())
                && Integer.toString(rememberedPort).equals(port.getText().toString().trim());
    }

    private void authenticationTargetChanged() {
        if (autoSelectedKey && !matchesRememberedTarget()) {
            selectKey(null, null, false, false);
        } else {
            clearSecretChoice();
            useSavedSecret = canUseSavedSecret();
            refreshSecret();
        }
    }

    private void toggleSavedSecret() {
        if (!canUseSavedSecret()) return;
        boolean useStored = !useSavedSecret;
        clearSecretChoice();
        useSavedSecret = useStored;
        refreshSecret();
    }

    private void clearSecretChoice() {
        useSavedSecret = false;
        secret.getText().clear();
        rememberSecret.setChecked(false);
    }

    private void refreshRoute() {
        directFields.setVisibility(frp ? View.GONE : View.VISIBLE);
        frpFields.setVisibility(frp ? View.VISIBLE : View.GONE);
        refreshNativeFrpAction();
    }

    private void refreshNativeFrpAction() {
        String address = host.getText().toString().trim();
        boolean local = !frp && FrpRoute.isLoopbackHost(address);
        openNativeFrp.setVisibility(local ? View.VISIBLE : View.GONE);
        if (local) {
            String candidatePort = port.getText().toString().trim();
            boolean useImported = frpChoice != null && (frpChoice.legacyLoopbackPort == 0
                    || Integer.toString(frpChoice.legacyLoopbackPort).equals(candidatePort));
            openNativeFrp.setText(useImported ? R.string.use_imported_frp : R.string.open_native_frp);
        }
    }

    private void showUsernameOptions(View anchor) {
        FloatingTools menu = new FloatingTools(anchor);
        menu.addText(frp ? "Import username from OpenSSH config…" : "Import from OpenSSH config…",
                true, () -> listener.pickSshConfig(this));
        menu.show();
    }

    private void refreshConnectAction() {
        if (dialog.isShowing()) {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setEnabled(!frp || !frpImportPending);
        }
    }

    private void submit() {
        String hostName = "";
        int portNumber = 0;
        if (frp) {
            if (frpImportPending) return;
            if (!hasFrpProfile) {
                frpSummary.setText("Import an FRP profile to connect via STCP.");
                return;
            }
        } else {
            hostName = host.getText().toString().trim();
            if (hostName.isEmpty() || hostName.equals("[]")) {
                host.setError("Enter an SSH host");
                return;
            }
            try {
                portNumber = Integer.parseInt(port.getText().toString().trim());
                if (portNumber < 1 || portNumber > 65535) throw new NumberFormatException();
            } catch (NumberFormatException badPort) {
                port.setError("Enter a port from 1 to 65535");
                return;
            }
        }

        String user = username.getText().toString().trim();
        if (user.isEmpty() || user.indexOf(0) >= 0) {
            username.setError("Enter an SSH username");
            return;
        }
        boolean useStored = useSavedSecret && canUseSavedSecret();
        String typedSecret = useStored ? "" : secret.getText().toString();
        if (authMode == AuthMode.KEY && !hasSelectedKey()) {
            keySummary.setText("Choose an SSH private key to continue.");
            keySummary.setVisibility(View.VISIBLE);
            return;
        }
        boolean saveNewKey = keyUri != null && saveKey.isChecked();
        boolean rememberTyped = !typedSecret.isEmpty() && rememberSecret.isChecked()
                && (authMode == AuthMode.PASSWORD || useSavedKey || saveNewKey);
        Request request = new Request(frp, authMode, hostName, portNumber, user, typedSecret,
                keyUri, useSavedKey, saveNewKey, useStored, rememberTyped);
        secret.getText().clear();
        dialog.dismiss();
        listener.connect(request);
    }

    private LinearLayout column() {
        LinearLayout column = new LinearLayout(activity);
        column.setOrientation(LinearLayout.VERTICAL);
        return column;
    }

    private LinearLayout section(LinearLayout parent, String title, int topMargin) {
        LinearLayout section = column();
        int padding = activity.dp(12);
        section.setPadding(padding, padding, padding, padding);
        section.setBackground(LocusChrome.surface(activity, color(R.color.surface_variant), 0, 8));
        TextView heading = new TextView(activity);
        heading.setText(title);
        heading.setTextColor(color(R.color.text_primary));
        heading.setTextSize(15);
        heading.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        heading.setAccessibilityHeading(true);
        heading.setPadding(0, 0, 0, activity.dp(8));
        section.addView(heading);
        addSpaced(parent, section, topMargin);
        return section;
    }

    private void addSpaced(LinearLayout parent, View child, int topMargin) {
        LinearLayout.LayoutParams layout = new LinearLayout.LayoutParams(-1, -2);
        layout.topMargin = activity.dp(topMargin);
        parent.addView(child, layout);
    }

    private TextView supportingText() {
        TextView text = new TextView(activity);
        text.setTextSize(13);
        text.setTextColor(color(R.color.text_secondary));
        return text;
    }

    private CheckBox checkbox() {
        CheckBox checkbox = new CheckBox(activity);
        checkbox.setMinHeight(activity.dp(48));
        checkbox.setMinimumHeight(activity.dp(48));
        checkbox.setTextSize(14);
        checkbox.setTextColor(color(R.color.text_primary));
        return checkbox;
    }

    private RadioGroup modeGroup() {
        RadioGroup group = new RadioGroup(activity);
        group.setOrientation(RadioGroup.HORIZONTAL);
        group.setBackground(LocusChrome.surface(activity, color(R.color.surface), 0, 8));
        int padding = activity.dp(3);
        group.setPadding(padding, padding, padding, padding);
        return group;
    }

    private RadioButton mode(RadioGroup group, String title) {
        RadioButton choice = new RadioButton(activity);
        choice.setId(View.generateViewId());
        choice.setText(title);
        choice.setButtonDrawable(null);
        choice.setGravity(Gravity.CENTER);
        choice.setMinHeight(activity.dp(48));
        choice.setMinimumHeight(activity.dp(48));
        choice.setTextSize(14);
        choice.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        choice.setTextColor(new ColorStateList(new int[][] {
                {android.R.attr.state_checked}, {}
        }, new int[] {color(R.color.accent), color(R.color.text_primary)}));
        StateListDrawable states = new StateListDrawable();
        states.addState(new int[] {android.R.attr.state_checked, android.R.attr.state_focused},
                LocusChrome.surface(activity, color(R.color.accent_surface), color(R.color.accent), 6));
        states.addState(new int[] {android.R.attr.state_checked},
                LocusChrome.surface(activity, color(R.color.accent_surface), 0, 6));
        states.addState(new int[] {android.R.attr.state_focused},
                LocusChrome.surface(activity, color(R.color.surface_active), color(R.color.accent), 6));
        states.addState(new int[] {}, LocusChrome.surface(activity, 0, 0, 6));
        choice.setBackground(new RippleDrawable(ColorStateList.valueOf(color(R.color.ripple)),
                states, LocusChrome.surface(activity, 0xffffffff, 0, 6)));
        group.addView(choice, new RadioGroup.LayoutParams(0, -2, 1));
        return choice;
    }

    private EditText field(LinearLayout fields, TextView existingLabel, String label,
                           String value, int inputType) {
        TextView caption = existingLabel == null ? new TextView(activity) : existingLabel;
        fields.addView(caption);
        EditText input = input(caption, label, value, inputType);
        fields.addView(input, new LinearLayout.LayoutParams(-1, -2));
        return input;
    }

    private EditText input(TextView caption, String label, String value, int inputType) {
        caption.setText(label);
        caption.setTextSize(13);
        caption.setTextColor(color(R.color.text_secondary));
        caption.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        caption.setPadding(0, 0, 0, activity.dp(4));
        EditText input = new EditText(activity);
        input.setId(View.generateViewId());
        input.setInputType(inputType);
        input.setSingleLine(true);
        input.setText(value);
        input.setTextSize(16);
        input.setTextColor(color(R.color.text_primary));
        input.setHintTextColor(color(R.color.text_secondary));
        input.setMinHeight(activity.dp(48));
        input.setMinimumHeight(activity.dp(48));
        StateListDrawable states = new StateListDrawable();
        states.addState(new int[] {android.R.attr.state_focused},
                LocusChrome.surface(activity, color(R.color.surface), color(R.color.accent), 8));
        states.addState(new int[] {}, LocusChrome.surface(activity, color(R.color.surface), 0, 8));
        input.setBackground(states);
        input.setBackgroundTintList(null);
        input.setPaddingRelative(activity.dp(12), activity.dp(8), activity.dp(12), activity.dp(8));
        input.setContentDescription(label);
        caption.setLabelFor(input.getId());
        return input;
    }

    private int color(int resource) {
        return activity.getColor(resource);
    }

    private static String safeLabel(String value) {
        return value.replaceAll("[\\p{Cc}\\p{Zl}\\p{Zp}\\u061C\\u200E\\u200F\\u202A-\\u202E\\u2066-\\u2069]", " ");
    }
}
