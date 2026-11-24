package dev.locus.app;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.FrameLayout;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.ScrollView;
import android.widget.TextView;

import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CoderResult;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.regex.Pattern;

import static android.view.ViewGroup.LayoutParams.MATCH_PARENT;

/** A peer pane for looking at remote directories and files through SFTP. */
final class RemoteBrowserView extends LinearLayout {
    private static final int TEXT_LIMIT = 64 * 1024;
    private static final int IMAGE_LIMIT = 8 * 1024 * 1024;
    private static final int IMAGE_DISPLAY_SIZE = 1600;
    private static final long IMAGE_PIXEL_LIMIT = 32_000_000;
    private static final Pattern LABEL_CONTROLS =
            Pattern.compile("[\\p{Cc}\\p{Zl}\\p{Zp}\\u061C\\u200E\\u200F\\u202A-\\u202E\\u2066-\\u2069]");

    private final RemoteConnection connection;
    private final ExecutorService worker;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ImageButton back;
    private final ImageButton refresh;
    private final TextView pathLabel;
    private final TextView status;
    private final ListView listing;
    private final FrameLayout fileBody;

    private Future<?> request;
    private long generation;
    private boolean disposed;
    private boolean offline;
    private String directory;
    private RemoteConnection.FileEntry selectedFile;
    // Resolve display text from the current locale without reloading the remote data.
    private Function<Context, String> directoryStatus = string(R.string.remote_select_directory);
    private Function<Context, String> previewStatus;

    RemoteBrowserView(Context context, RemoteConnection connection, ExecutorService worker) {
        super(context);
        this.connection = connection;
        this.worker = worker;
        setOrientation(VERTICAL);
        setBackgroundColor(context.getColor(R.color.surface));

        LinearLayout navigation = new LinearLayout(context);
        navigation.setGravity(Gravity.CENTER_VERTICAL);
        navigation.setPaddingRelative(dp(12), 0, dp(4), 0);
        pathLabel = new TextView(context);
        pathLabel.setTypeface(Typeface.MONOSPACE);
        pathLabel.setTextSize(13);
        pathLabel.setTextColor(context.getColor(R.color.text_secondary));
        pathLabel.setTextDirection(TEXT_DIRECTION_LTR);
        pathLabel.setTextIsSelectable(true);
        pathLabel.setSingleLine(true);
        pathLabel.setEllipsize(TextUtils.TruncateAt.START);
        LinearLayout.LayoutParams pathSpace = new LinearLayout.LayoutParams(0, LayoutParams.WRAP_CONTENT, 1);
        pathSpace.setMarginEnd(dp(8));
        navigation.addView(pathLabel, pathSpace);

        back = LocusChrome.iconAction(context, R.drawable.ic_up, context.getString(R.string.remote_parent_folder));
        back.setOnClickListener(view -> goBack());
        refresh = LocusChrome.iconAction(context, R.drawable.ic_refresh, context.getString(R.string.remote_refresh_folder));
        refresh.setOnClickListener(view -> {
            if (selectedFile == null) openDirectory(directory);
            else openFile(selectedFile);
        });
        int navigationTarget = dp(48);
        navigation.addView(back, new LinearLayout.LayoutParams(navigationTarget, navigationTarget));
        navigation.addView(refresh, new LinearLayout.LayoutParams(navigationTarget, navigationTarget));
        addView(navigation);

        status = new TextView(context);
        status.setTextSize(12);
        status.setTextColor(context.getColor(R.color.text_secondary));
        status.setPaddingRelative(dp(12), dp(2), dp(12), dp(8));
        status.setMaxLines(4);
        status.setEllipsize(TextUtils.TruncateAt.END);
        status.setAccessibilityLiveRegion(ACCESSIBILITY_LIVE_REGION_POLITE);
        refreshStatus();
        addView(status);

        View divider = new View(context);
        divider.setBackgroundColor(context.getColor(R.color.divider));
        addView(divider, new LinearLayout.LayoutParams(MATCH_PARENT, Math.max(1, dp(1))));

        FrameLayout body = new FrameLayout(context);
        listing = new ListView(context);
        listing.setDivider(new ColorDrawable(context.getColor(R.color.divider)));
        listing.setDividerHeight(Math.max(1, dp(1)));
        listing.setSelector(LocusChrome.action(context, false));
        listing.setOnItemClickListener((parent, view, position, id) -> {
            RemoteConnection.FileEntry entry =
                    (RemoteConnection.FileEntry) parent.getItemAtPosition(position);
            if (entry.directory) openDirectory(childPath(entry.name));
            else openFile(entry);
        });
        body.addView(listing, new FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT));
        fileBody = new FrameLayout(context);
        fileBody.setVisibility(GONE);
        body.addView(fileBody, new FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT));
        addView(body, new LinearLayout.LayoutParams(MATCH_PARENT, 0, 1));
        updateNavigation();
    }

    /** Opens the selected directory and uses its canonical path for navigation. */
    void openDirectory(String path) {
        if (disposed || offline) return;
        directory = path;
        selectedFile = null;
        fileBody.removeAllViews();
        fileBody.setVisibility(GONE);
        listing.setVisibility(VISIBLE);
        listing.setAdapter(null);
        showStatus(string(R.string.remote_loading_directory));
        updateNavigation();

        load(() -> {
            String canonical = connection.canonicalDirectory(path);
            List<RemoteConnection.FileEntry> entries = new ArrayList<>(connection.list(canonical));
            entries.sort(Comparator.comparing((RemoteConnection.FileEntry entry) -> !entry.directory)
                    .thenComparing(entry -> entry.name, String.CASE_INSENSITIVE_ORDER)
                    .thenComparing(entry -> entry.name));
            return new DirectoryListing(canonical, entries);
        }, result -> {
            directory = result.path;
            updateNavigation();
            listing.setAdapter(new FileListAdapter(result.entries));
            int count = result.entries.size();
            showStatus(count == 0 ? string(R.string.remote_empty_directory)
                    : quantity(R.plurals.remote_directory_entries, count));
        }, R.string.remote_open_directory_failed);
    }

    void dispose() {
        disposed = true;
        cancelRequest();
        listing.setAdapter(null);
        fileBody.removeAllViews();
    }

    // The disconnected pane can still display its last completed list or preview.
    void transportLost() {
        if (offline || disposed) return;
        offline = true;
        if (request != null) {
            cancelRequest();
            showStatus(string(R.string.error_sftp_disconnected));
        }
        updateNavigation();
    }

    private void openFile(RemoteConnection.FileEntry entry) {
        if (disposed || offline) return;
        selectedFile = entry;
        String path = childPath(entry.name);
        fileBody.removeAllViews();
        fileBody.setVisibility(VISIBLE);
        listing.setVisibility(GONE);
        updateNavigation();

        if (!entry.regularFile) {
            cancelRequest();
            showStatus(string(entry.symbolicLink ? R.string.remote_link_preview_unavailable
                    : R.string.remote_regular_file_preview));
            return;
        }

        showStatus(string(R.string.remote_loading_preview));
        load(() -> readPreview(path, entry.name), this::showPreview, R.string.remote_read_file_failed);
    }

    private void goBack() {
        if (selectedFile != null) {
            cancelRequest();
            selectedFile = null;
            fileBody.removeAllViews();
            fileBody.setVisibility(GONE);
            listing.setVisibility(VISIBLE);
            refreshStatus();
            updateNavigation();
        } else if (!offline && directory != null && !directory.equals("/")) {
            int slash = directory.lastIndexOf('/');
            openDirectory(slash <= 0 ? "/" : directory.substring(0, slash));
        }
    }

    private void updateNavigation() {
        updateNavigationLabels();
        boolean preview = selectedFile != null;
        back.setEnabled(directory != null && (preview || !offline && !directory.equals("/")));
        refresh.setEnabled(!offline && directory != null);
        if (directory != null) {
            String path = preview ? childPath(selectedFile.name) : directory;
            pathLabel.setText(visibleLabel(path));
        }
    }

    private void updateNavigationLabels() {
        boolean preview = selectedFile != null;
        LocusChrome.setIcon(back, preview ? R.drawable.ic_folder : R.drawable.ic_up,
                getContext().getString(preview ? R.string.remote_back_to_folder : R.string.remote_parent_folder));
        LocusChrome.setIcon(refresh, R.drawable.ic_refresh,
                getContext().getString(preview ? R.string.remote_reload_preview : R.string.remote_refresh_folder));
    }

    void refreshLocale() {
        if (disposed) return;
        updateNavigationLabels();
        refreshStatus();
        View preview = fileBody.getChildAt(0);
        if (preview instanceof ImageView) preview.setContentDescription(imageDescription());
        int first = listing.getFirstVisiblePosition();
        for (int index = 0; index < listing.getChildCount(); index++) {
            RemoteConnection.FileEntry entry = (RemoteConnection.FileEntry) listing.getItemAtPosition(first + index);
            if (entry != null && entry.symbolicLink) {
                ((TextView) listing.getChildAt(index)).setText(entryLabel(entry));
            }
        }
    }

    private void showStatus(Function<Context, String> message) {
        if (selectedFile == null) directoryStatus = message;
        else previewStatus = message;
        refreshStatus();
    }

    private void refreshStatus() {
        status.setText((selectedFile == null ? directoryStatus : previewStatus).apply(getContext()));
    }

    private static Function<Context, String> string(int id) {
        return context -> context.getString(id);
    }

    private static Function<Context, String> string(int id, Object... args) {
        return context -> context.getString(id, args);
    }

    private static Function<Context, String> quantity(int id, int count) {
        return context -> context.getResources().getQuantityString(id, count, count);
    }

    private String childPath(String name) {
        return directory.endsWith("/") ? directory + name : directory + "/" + name;
    }

    private <T> void load(Callable<T> operation, Consumer<T> complete, int failureMessage) {
        cancelRequest();
        long current = generation;
        try {
            request = worker.submit(() -> {
                try {
                    T value = operation.call();
                    main.post(() -> {
                        if (current == generation && !disposed) {
                            request = null;
                            complete.accept(value);
                        }
                    });
                } catch (Exception failure) {
                    if (Thread.currentThread().isInterrupted()) return;
                    main.post(() -> {
                        if (current == generation && !disposed) {
                            request = null;
                            showStatus(context -> context.getString(
                                    failureMessage, UiErrors.message(context, failure)));
                        }
                    });
                }
            });
        } catch (RejectedExecutionException ignored) {
            showStatus(context -> context.getString(
                    failureMessage, context.getString(R.string.remote_operations_unavailable)));
        }
    }

    private void cancelRequest() {
        generation++;
        if (request != null) {
            request.cancel(true);
            request = null;
        }
    }

    private Preview readPreview(String path, String name) throws Exception {
        boolean imageName = looksLikeImageName(name);
        int limit = imageName ? IMAGE_LIMIT : TEXT_LIMIT;
        byte[] bytes = connection.read(path, limit + 1);
        boolean incomplete = bytes.length > limit;

        BitmapFactory.Options bounds = imageBounds(bytes, Math.min(bytes.length, limit));
        if (bounds.outWidth > 0 && bounds.outHeight > 0) {
            if (incomplete && !imageName) {
                bytes = connection.read(path, IMAGE_LIMIT + 1);
                incomplete = bytes.length > IMAGE_LIMIT;
                if (!incomplete) bounds = imageBounds(bytes, bytes.length);
            }
            if (incomplete) {
                return new Preview(null, null,
                        string(R.string.remote_image_preview_limit, IMAGE_LIMIT / (1024 * 1024)));
            }
            return decodeImage(bytes, bounds);
        }
        if (incomplete && imageName) {
            return new Preview(null, null,
                    string(R.string.remote_image_preview_limit, IMAGE_LIMIT / (1024 * 1024)));
        }

        int textLength = Math.min(bytes.length, TEXT_LIMIT);
        boolean truncated = bytes.length > textLength;
        String text = decodeText(bytes, textLength, truncated);
        if (text == null) return new Preview(null, null, string(R.string.remote_unsupported_format));
        if (text.isEmpty()) return new Preview(null, text, string(R.string.remote_empty_file));
        CharSequence highlighted = SyntaxHighlight.apply(getContext(), name, text);
        return new Preview(null, highlighted, truncated ? string(R.string.remote_truncated_text, TEXT_LIMIT / 1024)
                : quantity(R.plurals.remote_utf8_bytes, bytes.length));
    }

    private static BitmapFactory.Options imageBounds(byte[] bytes, int length) {
        BitmapFactory.Options options = new BitmapFactory.Options();
        options.inJustDecodeBounds = true;
        if (length > 0) BitmapFactory.decodeByteArray(bytes, 0, length, options);
        return options;
    }

    private static Preview decodeImage(byte[] bytes, BitmapFactory.Options bounds) {
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
            return new Preview(null, null, string(R.string.remote_decode_image_failed));
        }
        if ((long) bounds.outWidth * bounds.outHeight > IMAGE_PIXEL_LIMIT) {
            return new Preview(null, null, string(R.string.remote_image_dimensions_limit));
        }
        BitmapFactory.Options options = new BitmapFactory.Options();
        options.inSampleSize = 1;
        int largest = Math.max(bounds.outWidth, bounds.outHeight);
        while (largest / options.inSampleSize > IMAGE_DISPLAY_SIZE) options.inSampleSize *= 2;
        Bitmap bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.length, options);
        if (bitmap == null) return new Preview(null, null, string(R.string.remote_decode_image_failed));
        return new Preview(bitmap, null,
                string(R.string.remote_image_dimensions, bounds.outWidth, bounds.outHeight));
    }

    private static String decodeText(byte[] bytes, int length, boolean truncated) {
        ByteBuffer input = ByteBuffer.wrap(bytes, 0, length);
        CharBuffer output = CharBuffer.allocate(length);
        try {
            CharsetDecoder decoder = StandardCharsets.UTF_8.newDecoder();
            decoder.onMalformedInput(CodingErrorAction.REPORT);
            decoder.onUnmappableCharacter(CodingErrorAction.REPORT);
            CoderResult decoded = decoder.decode(input, output, !truncated);
            if (decoded.isError()) decoded.throwException();
        } catch (CharacterCodingException failure) {
            return null;
        }
        output.flip();
        for (int index = 0; index < output.length(); index++) {
            char character = output.get(index);
            if (Character.isISOControl(character) && character != '\t'
                    && character != '\r' && character != '\n' && character != '\f') return null;
        }
        if (output.hasRemaining() && output.get(0) == '\uFEFF') output.get();
        return output.toString();
    }

    private static boolean looksLikeImageName(String name) {
        int dot = name.lastIndexOf('.');
        if (dot < 0) return false;
        String extension = name.substring(dot + 1).toLowerCase(Locale.ROOT);
        switch (extension) {
            case "png":
            case "jpg":
            case "jpeg":
            case "gif":
            case "webp":
            case "bmp":
            case "heic":
            case "heif":
            case "avif":
                return true;
            default:
                return false;
        }
    }

    private void showPreview(Preview preview) {
        showStatus(preview.status);
        if (preview.bitmap != null) {
            ImageView image = new ImageView(getContext());
            image.setScaleType(ImageView.ScaleType.FIT_CENTER);
            image.setPadding(dp(12), dp(12), dp(12), dp(12));
            image.setContentDescription(imageDescription());
            image.setImageBitmap(preview.bitmap);
            fileBody.addView(image, new FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT));
        } else if (!TextUtils.isEmpty(preview.text)) {
            TextView text = new TextView(getContext());
            text.setTypeface(Typeface.MONOSPACE);
            text.setTextSize(14);
            text.setTextColor(getContext().getColor(R.color.text_primary));
            text.setPaddingRelative(dp(12), dp(10), dp(12), dp(12));
            text.setLineSpacing(dp(2), 1f);
            text.setTextIsSelectable(true);
            text.setText(preview.text);
            ScrollView scroll = new ScrollView(getContext());
            scroll.addView(text);
            fileBody.addView(scroll, new FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT));
        }
    }

    private String imageDescription() {
        return getContext().getString(R.string.remote_preview_of, visibleLabel(selectedFile.name));
    }

    private int dp(int pixels) {
        return Math.round(pixels * getResources().getDisplayMetrics().density);
    }

    private static String visibleLabel(String value) {
        // Paths and server messages stay on one line and cannot reorder adjacent UI text.
        return LABEL_CONTROLS.matcher(value).replaceAll("\uFFFD");
    }

    private String entryLabel(RemoteConnection.FileEntry entry) {
        String name = visibleLabel(entry.name);
        if (entry.directory) return name + "/";
        if (entry.symbolicLink) return getContext().getString(R.string.remote_link_name, name);
        return name;
    }

    private static final class DirectoryListing {
        final String path;
        final List<RemoteConnection.FileEntry> entries;

        DirectoryListing(String path, List<RemoteConnection.FileEntry> entries) {
            this.path = path;
            this.entries = entries;
        }
    }

    private static final class Preview {
        final Bitmap bitmap;
        final CharSequence text;
        final Function<Context, String> status;

        Preview(Bitmap bitmap, CharSequence text, Function<Context, String> status) {
            this.bitmap = bitmap;
            this.text = text;
            this.status = status;
        }
    }

    private final class FileListAdapter extends ArrayAdapter<RemoteConnection.FileEntry> {
        FileListAdapter(List<RemoteConnection.FileEntry> entries) {
            super(RemoteBrowserView.this.getContext(), 0, entries);
        }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            TextView row = (TextView) convertView;
            if (row == null) {
                row = new TextView(getContext());
                row.setMinimumHeight(dp(48));
                row.setTextSize(15);
                row.setTextColor(getContext().getColor(R.color.text_primary));
                row.setGravity(Gravity.CENTER_VERTICAL);
                row.setPaddingRelative(dp(12), 0, dp(12), 0);
                row.setCompoundDrawablePadding(dp(12));
                row.setSingleLine(true);
                row.setEllipsize(TextUtils.TruncateAt.END);
                row.setTextDirection(TEXT_DIRECTION_LTR);
            }
            RemoteConnection.FileEntry entry = getItem(position);
            row.setCompoundDrawablesRelativeWithIntrinsicBounds(
                    entry.directory ? R.drawable.ic_folder : R.drawable.ic_file, 0, 0, 0);
            row.setText(entryLabel(entry));
            return row;
        }
    }
}
