package dev.locus.app;

import android.app.AlertDialog;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.StateListDrawable;
import android.text.Editable;
import android.text.InputType;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.view.inputmethod.EditorInfo;
import android.widget.BaseAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.function.Consumer;
import java.util.regex.Pattern;

/** Explicitly chooses a directory through the active connection's read-only filesystem. */
final class RemoteDirectoryDialog {
    private static final Pattern LABEL_CONTROLS =
            Pattern.compile("[\\p{Cc}\\p{Zl}\\p{Zp}\\u061C\\u200E\\u200F\\u202A-\\u202E\\u2066-\\u2069]");

    private final MainActivity activity;
    private final RemoteConnection connection;
    private final ExecutorService io;
    private final AlertDialog dialog;
    private final TextView pathLabel;
    private final TextView status;
    private final EditText filter;
    private final ImageButton up;
    private final Button retry;
    private final ListView listing;
    private final FolderAdapter adapter;
    private final List<RemoteConnection.FileEntry> visibleFolders = new ArrayList<>();

    private List<RemoteConnection.FileEntry> folders = Collections.emptyList();
    private Future<?> request;
    private long generation;
    private String requested;
    private String canonical;
    private String listingError;

    static void show(MainActivity activity, RemoteConnection connection, ExecutorService io,
                     String initial, Consumer<String> choose) {
        new RemoteDirectoryDialog(activity, connection, io, choose)
                .open(initial == null || initial.isEmpty() ? "~" : initial);
    }

    private RemoteDirectoryDialog(MainActivity activity, RemoteConnection connection,
                                  ExecutorService io, Consumer<String> choose) {
        this.activity = activity;
        this.connection = connection;
        this.io = io;

        LinearLayout content = new LinearLayout(activity);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(activity.dp(16), activity.dp(4), activity.dp(16), activity.dp(8));
        content.setFocusableInTouchMode(true);

        LinearLayout navigation = new LinearLayout(activity);
        navigation.setGravity(Gravity.CENTER_VERTICAL);
        pathLabel = new TextView(activity);
        pathLabel.setTypeface(Typeface.MONOSPACE);
        pathLabel.setTextSize(13);
        pathLabel.setTextColor(activity.getColor(R.color.text_secondary));
        pathLabel.setSingleLine(true);
        pathLabel.setEllipsize(TextUtils.TruncateAt.START);
        pathLabel.setTextDirection(View.TEXT_DIRECTION_LTR);
        pathLabel.setTextIsSelectable(true);
        LinearLayout.LayoutParams pathSpace = new LinearLayout.LayoutParams(0, -2, 1);
        pathSpace.setMarginEnd(activity.dp(8));
        navigation.addView(pathLabel, pathSpace);

        up = activity.addIconAction(navigation, R.drawable.ic_up,
                activity.getString(R.string.remote_parent_folder), view -> {
            String parent = parentOf(canonical == null ? requested : canonical);
            if (parent != null) open(parent);
        });
        activity.addIconAction(navigation, R.drawable.ic_home,
                activity.getString(R.string.remote_home_folder), view -> open("~"));
        retry = activity.addAction(navigation, "Retry", () -> open(requested));
        content.addView(navigation);

        filter = new EditText(activity);
        filter.setSingleLine(true);
        filter.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        filter.setImeOptions(EditorInfo.IME_ACTION_DONE | EditorInfo.IME_FLAG_NO_EXTRACT_UI);
        filter.setHint("Filter folders here");
        filter.setTextSize(15);
        filter.setTextColor(activity.getColor(R.color.text_primary));
        filter.setHintTextColor(activity.getColor(R.color.text_secondary));
        int filterFill = activity.getColor(R.color.surface_variant);
        StateListDrawable filterSurface = new StateListDrawable();
        filterSurface.addState(new int[]{android.R.attr.state_focused},
                LocusChrome.surface(activity, filterFill, activity.getColor(R.color.accent), 8));
        filterSurface.addState(new int[0], LocusChrome.surface(activity, filterFill, 0, 8));
        filter.setBackground(filterSurface);
        filter.setMinHeight(activity.dp(48));
        filter.setPaddingRelative(activity.dp(12), 0, activity.dp(12), 0);
        LinearLayout.LayoutParams filterSpace = new LinearLayout.LayoutParams(-1, -2);
        filterSpace.topMargin = activity.dp(4);
        content.addView(filter, filterSpace);

        status = new TextView(activity);
        status.setTextSize(12);
        status.setTextColor(activity.getColor(R.color.text_secondary));
        status.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        status.setMaxLines(3);
        status.setEllipsize(TextUtils.TruncateAt.END);
        status.setTextDirection(View.TEXT_DIRECTION_LTR);
        status.setPadding(activity.dp(12), activity.dp(8), activity.dp(12), activity.dp(8));
        content.addView(status);

        listing = new ListView(activity);
        listing.setDivider(new ColorDrawable(activity.getColor(R.color.divider)));
        listing.setDividerHeight(Math.max(1, activity.dp(1)));
        listing.setSelector(LocusChrome.action(activity, false));
        adapter = new FolderAdapter();
        listing.setAdapter(adapter);
        listing.setOnItemClickListener((parent, view, position, id) -> {
            RemoteConnection.FileEntry item = adapter.getItem(position);
            if (canonical != null) {
                open((canonical.equals("/") ? "/" : canonical + "/") + item.name);
            }
        });
        int height = Math.min(activity.dp(280), activity.getResources().getDisplayMetrics().heightPixels / 4);
        content.addView(listing, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, height));

        filter.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence text, int start, int count, int after) {}
            @Override public void onTextChanged(CharSequence text, int start, int before, int count) {
                if (canonical != null && listingError == null) applyFilter();
            }
            @Override public void afterTextChanged(Editable text) {}
        });

        dialog = new AlertDialog.Builder(activity)
                .setTitle("Choose remote directory")
                .setView(content)
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Use this directory", null)
                .create();
        dialog.setOnDismissListener(ignored -> cancelRequest());
        dialog.show();
        LocusChrome.fitForm(dialog);
        dialog.getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE
                | WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN);
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(view -> {
            if (canonical != null) {
                String selected = canonical;
                dialog.dismiss();
                choose.accept(selected);
            }
        });
        content.requestFocus();
    }

    private void open(String path) {
        cancelRequest();
        DiagnosticLog.Trace trace = activity.diagnosticTrace(connection);
        long current = generation;
        requested = path;
        canonical = null;
        listingError = null;
        folders = Collections.emptyList();
        visibleFolders.clear();
        adapter.notifyDataSetChanged();
        filter.setText("");
        filter.setEnabled(false);
        up.setEnabled(false);
        retry.setVisibility(View.GONE);
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setEnabled(false);
        pathLabel.setText(label(path));
        status.setText("Loading remote folders…");
        try {
            request = io.submit(() -> {
                if (trace != null) trace.record(DiagnosticLog.Stage.SFTP, DiagnosticLog.Outcome.STARTED);
                String resolved = null;
                List<RemoteConnection.FileEntry> entries = new ArrayList<>();
                String error = null;
                try {
                    resolved = connection.canonicalDirectory(path);
                    if (Thread.currentThread().isInterrupted()) return;
                    for (RemoteConnection.FileEntry entry : connection.list(resolved)) {
                        if (entry.directory || entry.symbolicLink) entries.add(entry);
                    }
                    entries.sort(Comparator.comparing((RemoteConnection.FileEntry entry) -> entry.name,
                                    String.CASE_INSENSITIVE_ORDER).thenComparing(entry -> entry.name));
                } catch (IOException failure) {
                    error = failure.getMessage();
                    if (error == null || error.trim().isEmpty()) error = "The remote operation failed.";
                }
                if (Thread.currentThread().isInterrupted()) return;
                if (trace != null) trace.record(DiagnosticLog.Stage.SFTP, error == null
                        ? DiagnosticLog.Outcome.READY : DiagnosticLog.Outcome.FAILED);
                String directory = resolved;
                String message = error;
                activity.runOnUiThread(() -> {
                    if (current == generation && dialog.isShowing() && !activity.isDestroyed()) {
                        request = null;
                        loaded(directory, entries, message);
                    }
                });
            });
        } catch (RejectedExecutionException unavailable) {
            if (trace != null) trace.record(DiagnosticLog.Stage.SFTP, DiagnosticLog.Outcome.FAILED);
            loaded(null, Collections.emptyList(), "File operations are unavailable.");
        }
    }

    private void loaded(String path, List<RemoteConnection.FileEntry> entries, String error) {
        canonical = path;
        folders = entries;
        listingError = error;
        if (path != null) pathLabel.setText(label(path));
        up.setEnabled(parentOf(path == null ? requested : path) != null);
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setEnabled(path != null);
        retry.setVisibility(error == null ? View.GONE : View.VISIBLE);
        filter.setEnabled(error == null);
        if (error == null) {
            applyFilter();
        } else {
            status.setText((path == null ? "Could not open folder. " : "Could not list folders. ") + label(error));
        }
    }

    private void applyFilter() {
        String query = filter.getText().toString().toLowerCase(Locale.ROOT);
        visibleFolders.clear();
        for (RemoteConnection.FileEntry entry : folders) {
            if (entry.name.toLowerCase(Locale.ROOT).contains(query)) visibleFolders.add(entry);
        }
        adapter.notifyDataSetChanged();
        listing.setSelection(0);
        if (visibleFolders.isEmpty()) {
            status.setText(query.isEmpty() ? "No subfolders." : "No matching folders here.");
        } else {
            int count = visibleFolders.size();
            status.setText(count == 1 ? "1 result" : count + " results");
        }
    }

    private void cancelRequest() {
        generation++;
        if (request != null) {
            request.cancel(true);
            request = null;
        }
    }

    private static String parentOf(String path) {
        if (path == null || !path.startsWith("/") || path.equals("/")) return null;
        int slash = path.lastIndexOf('/');
        return slash == 0 ? "/" : path.substring(0, slash);
    }

    private static String label(String value) {
        return LABEL_CONTROLS.matcher(value).replaceAll("\uFFFD");
    }

    private final class FolderAdapter extends BaseAdapter {
        @Override public int getCount() { return visibleFolders.size(); }
        @Override public RemoteConnection.FileEntry getItem(int position) { return visibleFolders.get(position); }
        @Override public long getItemId(int position) { return position; }

        @Override public View getView(int position, View reusable, ViewGroup parent) {
            TextView row = (TextView) reusable;
            if (row == null) {
                row = new TextView(activity);
                row.setGravity(Gravity.CENTER_VERTICAL);
                row.setMinimumHeight(activity.dp(48));
                row.setTextSize(15);
                row.setTextColor(activity.getColor(R.color.text_primary));
                row.setPaddingRelative(activity.dp(12), 0, activity.dp(12), 0);
                row.setCompoundDrawablesRelativeWithIntrinsicBounds(R.drawable.ic_folder, 0, 0, 0);
                row.setCompoundDrawablePadding(activity.dp(12));
                row.setSingleLine(true);
                row.setEllipsize(TextUtils.TruncateAt.END);
                row.setTextDirection(View.TEXT_DIRECTION_LTR);
            }
            RemoteConnection.FileEntry entry = getItem(position);
            row.setText(label(entry.name) + (entry.symbolicLink ? " (link)" : "/"));
            return row;
        }
    }
}
