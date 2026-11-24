package dev.locus.app;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.Rect;
import android.graphics.drawable.ColorDrawable;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.view.ViewTreeObserver;
import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.FrameLayout;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.PopupWindow;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/** Workspace controls for windows kept alive outside the dock. */
final class WindowShelf {
    private static final int ROW_DP = 56;
    private static final int PANEL_MARGIN_DP = 8;

    private final FrameLayout root;
    private final Context context;
    private final Consumer<WorkspacePane> restore;
    private final ImageButton handle;
    private final List<WorkspacePane> windows = new ArrayList<>();
    private FloatingTools menu;
    private PopupWindow previewPopup;

    WindowShelf(FrameLayout root, boolean freeFloating, Consumer<WorkspacePane> restore) {
        this.root = root;
        this.context = root.getContext();
        this.restore = restore;

        String label = context.getString(R.string.shelf_minimized_windows);
        handle = LocusChrome.iconAction(context, R.drawable.ic_window_shelf, label);
        handle.setVisibility(View.GONE);
        FloatingTools.bindShelfTrigger(handle, root, R.drawable.ic_window_shelf,
                context.getColor(R.color.shelf_control_fill), context.getColor(R.color.shelf_control_icon),
                freeFloating, this::show);
        root.addView(handle, new FrameLayout.LayoutParams(dp(48), dp(48), Gravity.LEFT | Gravity.TOP));
        root.addOnLayoutChangeListener((view, left, top, right, bottom, oldLeft, oldTop, oldRight, oldBottom) -> {
            if (previewPopup != null
                    && (right - left != oldRight - oldLeft || bottom - top != oldBottom - oldTop)) {
                previewPopup.dismiss();
            }
        });
    }

    void setDefaultTop(int topPx) {
        FloatingTools.setDefaultTop(handle, topPx);
    }

    void setFreeFloating(boolean enabled) {
        FloatingTools.setFreeFloating(handle, enabled);
    }

    void refreshLocale() {
        dismiss();
        LocusChrome.labelIcon(handle, context.getString(R.string.shelf_minimized_windows));
        FloatingTools.refreshLocale(handle);
    }

    void update(List<WorkspacePane> minimized, boolean targeting) {
        if (!windows.equals(minimized)) {
            dismiss();
            windows.clear();
            windows.addAll(minimized);
        }
        if (targeting) dismiss();
        handle.setVisibility(targeting || windows.isEmpty() ? View.GONE : View.VISIBLE);
    }

    private void show() {
        if (windows.isEmpty() || !handle.isShown() || !handle.isAttachedToWindow()
                || (menu != null && menu.isShowing())) return;
        menu = new FloatingTools(handle, root);
        for (WorkspacePane window : windows) menu.addRow(buildEntry(window), dp(ROW_DP));
        menu.show();
    }

    private View buildEntry(WorkspacePane window) {
        String label = window.shelfLabel();
        String type = viewType(window);
        String subtitle = subtitle(window, type);
        LinearLayout entry = new LinearLayout(context);
        entry.setGravity(Gravity.CENTER_VERTICAL);
        entry.setBackground(LocusChrome.action(context, false));
        entry.setPaddingRelative(dp(12), 0, dp(12), 0);

        ImageView icon = new ImageView(context);
        icon.setImageResource(window.isBrowsing() ? R.drawable.ic_files : R.drawable.ic_terminal);
        LinearLayout.LayoutParams iconParams = new LinearLayout.LayoutParams(dp(20), dp(20));
        iconParams.setMarginEnd(dp(10));
        entry.addView(icon, iconParams);

        LinearLayout labels = new LinearLayout(context);
        labels.setOrientation(LinearLayout.VERTICAL);
        labels.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS);
        labels.addView(line(label, 14, R.color.text_primary));
        if (!subtitle.isEmpty()) labels.addView(line(subtitle, 12, R.color.text_secondary));
        entry.addView(labels, new LinearLayout.LayoutParams(0, -2, 1));

        String detail = window.shelfDetail();
        String description = subtitle.isEmpty() ? label : label + ", " + subtitle;
        if (!detail.isEmpty()) description += "\n" + detail;
        entry.setContentDescription(context.getString(R.string.shelf_restore, description));
        entry.setFocusable(true);
        entry.setAccessibilityDelegate(new View.AccessibilityDelegate() {
            @Override
            public void onInitializeAccessibilityNodeInfo(View host, AccessibilityNodeInfo info) {
                super.onInitializeAccessibilityNodeInfo(host, info);
                info.setClassName("android.widget.Button");
                AccessibilityNodeInfo.AccessibilityAction longClick =
                        AccessibilityNodeInfo.AccessibilityAction.ACTION_LONG_CLICK;
                info.removeAction(longClick);
                info.addAction(new AccessibilityNodeInfo.AccessibilityAction(
                        longClick.getId(), context.getString(R.string.shelf_preview_action)));
            }
        });
        entry.setOnClickListener(view -> {
            dismiss();
            restore.accept(window);
        });
        entry.setOnLongClickListener(view -> {
            showPreview(window, label, subtitle);
            return true;
        });
        return entry;
    }

    private String viewType(WorkspacePane window) {
        return context.getString(window.isBrowsing() ? R.string.shelf_files : R.string.shelf_terminal);
    }

    private String subtitle(WorkspacePane window, String type) {
        String label = window.shelfLabel();
        for (WorkspacePane other : windows) {
            if (other != window && other.isBrowsing() == window.isBrowsing() && other.shelfLabel().equals(label)) {
                return context.getString(R.string.shelf_duplicate_type, type, window.shelfId());
            }
        }
        return label.equals(type) ? "" : type;
    }

    private TextView line(String text, int size, int color) {
        TextView line = new TextView(context);
        line.setText(text);
        line.setTextSize(size);
        line.setTextColor(context.getColor(color));
        line.setSingleLine(true);
        line.setEllipsize(TextUtils.TruncateAt.MIDDLE);
        return line;
    }

    private void showPreview(WorkspacePane window, String label, String subtitle) {
        Bitmap snapshot = window.shelfPreview();
        Rect visible = new Rect();
        if (!root.getLocalVisibleRect(visible) || !handle.isAttachedToWindow()) return;
        if (snapshot == null || snapshot.isRecycled()) {
            Toast.makeText(context, R.string.shelf_preview_unavailable, Toast.LENGTH_SHORT).show();
            return;
        }

        int margin = dp(PANEL_MARGIN_DP);
        int width = Math.min(dp(480), visible.width() - 2 * margin);
        int contentLimit = Math.min(dp(336), visible.height() - dp(88));
        if (width < dp(120) || contentLimit < dp(48)) return;
        int height = Math.round((width - 2 * margin) * (float) snapshot.getHeight() / snapshot.getWidth());
        height = Math.min(contentLimit, Math.max(dp(48), height));

        LinearLayout preview = new LinearLayout(context);
        preview.setOrientation(LinearLayout.VERTICAL);
        preview.setPadding(margin, margin, margin, margin);
        preview.setBackground(LocusChrome.surface(context, context.getColor(R.color.surface),
                context.getColor(R.color.divider), 16));
        preview.setClipToOutline(true);
        preview.setAccessibilityPaneTitle(context.getString(R.string.shelf_preview_action));
        String heading = subtitle.isEmpty() ? label : label + " · " + subtitle;
        TextView title = line(heading, 14, R.color.text_primary);
        title.setGravity(Gravity.CENTER_VERTICAL);
        title.setPaddingRelative(dp(4), 0, dp(4), 0);
        preview.addView(title, new LinearLayout.LayoutParams(-1, dp(32)));

        ImageView image = new ImageView(context);
        image.setScaleType(ImageView.ScaleType.FIT_CENTER);
        image.setImageBitmap(snapshot);
        image.setContentDescription(context.getString(R.string.shelf_preview_image, label));
        image.setBackground(LocusChrome.surface(context, context.getColor(R.color.surface_variant), 0, 8));
        image.setClipToOutline(true);
        preview.addView(image, new LinearLayout.LayoutParams(-1, height));

        dismiss();
        showPreviewPopup(preview, image, visible, width, height + dp(32) + 2 * margin);
    }

    private void showPreviewPopup(View content, ImageView image, Rect visible, int width, int height) {
        PopupWindow window = new PopupWindow(content, width, height, true);
        window.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
        window.setOutsideTouchable(true);
        window.setClippingEnabled(true);
        window.setInputMethodMode(PopupWindow.INPUT_METHOD_NOT_NEEDED);
        window.setElevation(dp(6));
        window.setAnimationStyle(0);
        boolean[] hadFocus = {false};
        ViewTreeObserver.OnWindowFocusChangeListener focus = focused -> {
            if (focused) hadFocus[0] = true;
            else if (hadFocus[0]) window.dismiss();
        };
        content.getViewTreeObserver().addOnWindowFocusChangeListener(focus);
        window.setOnDismissListener(() -> {
            if (previewPopup == window) previewPopup = null;
            image.setImageDrawable(null);
            if (content.getViewTreeObserver().isAlive()) {
                content.getViewTreeObserver().removeOnWindowFocusChangeListener(focus);
            }
        });

        int[] rootLocation = new int[2];
        int[] handleLocation = new int[2];
        root.getLocationOnScreen(rootLocation);
        handle.getLocationOnScreen(handleLocation);
        int left = visible.centerX() - width / 2;
        int top = visible.centerY() - height / 2;
        previewPopup = window;
        window.showAsDropDown(handle, rootLocation[0] + left - handleLocation[0],
                rootLocation[1] + top - handleLocation[1] - handle.getHeight(), Gravity.LEFT);
    }

    void dismiss() {
        if (menu != null) {
            menu.dismiss();
            menu = null;
        }
        if (previewPopup != null) previewPopup.dismiss();
    }

    private int dp(int value) {
        return Math.round(value * context.getResources().getDisplayMetrics().density);
    }
}
