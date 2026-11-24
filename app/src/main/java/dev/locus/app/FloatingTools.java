package dev.locus.app;

import android.animation.ValueAnimator;
import android.annotation.SuppressLint;
import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Rect;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.InsetDrawable;
import android.graphics.drawable.RippleDrawable;
import android.os.Build;
import android.view.Gravity;
import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.ViewTreeObserver;
import android.view.animation.PathInterpolator;
import android.widget.Button;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.PopupWindow;
import android.widget.ScrollView;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Anchored actions. Only palettes opened from a bound trigger can move or tuck away. */
final class FloatingTools {
    private static final int TARGET = 48;
    private static final int TAB = 20;
    static final int WINDOW_TAB = 24;
    private static final int SNAP = 24;
    private static final int WINDOW_PADDING = 4;
    private static final PathInterpolator OPEN_INTERPOLATOR = new PathInterpolator(0.22f, 0.25f, 0f, 1f);
    enum Kind { WORKSPACE, WINDOW, SHELF }
    private enum Edge { FLOATING, LEFT, RIGHT, TOP, BOTTOM }

    private final View anchor;
    private final View host;
    private final Context context;
    private final LinearLayout content;
    private final LinearLayout actions;
    private final ImageView handle;
    private final ScrollView scroller;
    private final PopupWindow popup;
    private final Trigger owner;
    private final List<View> gridActions = new ArrayList<>();
    private final Rect safe = new Rect();
    private final int[] origin = new int[2];
    private final ViewTreeObserver.OnGlobalLayoutListener reflow = this::reflow;
    private final ViewTreeObserver.OnWindowFocusChangeListener focusChanged;
    private ViewTreeObserver observer;
    private ViewTreeObserver popupObserver;
    private int width;
    private int height;
    private int actionRows;
    private int gridColumns;
    private int gridCell;
    private float x;
    private float y;
    private boolean hasText;
    private boolean compact;
    private boolean draggingGrip;

    FloatingTools(View anchor) {
        this(anchor, anchor.getRootView());
    }

    FloatingTools(View anchor, View host) {
        this.anchor = anchor;
        this.host = host;
        context = anchor.getContext();
        owner = anchor.getTag() instanceof Trigger ? (Trigger) anchor.getTag() : null;
        content = new LinearLayout(context);
        content.setOrientation(LinearLayout.VERTICAL);

        handle = owner == null ? null : createHandle();
        if (handle != null) {
            boolean shelf = owner.kind == Kind.SHELF;
            int panelColor = shelf ? owner.fillColor : context.getColor(R.color.surface);
            content.setBackground(surface(context, panelColor, shelf ? 16 : 12, 244));
            content.setClipToOutline(true);
            content.addView(handle, new LinearLayout.LayoutParams(-1, dp(context, gripHeight())));
        }

        actions = new LinearLayout(context);
        actions.setOrientation(LinearLayout.VERTICAL);
        if (owner == null) {
            actions.setBackground(surface(context, context.getColor(R.color.surface), 12, 255));
            actions.setClipToOutline(true);
        }
        scroller = new ScrollView(context);
        scroller.setVerticalScrollBarEnabled(false);
        scroller.addView(actions, new ScrollView.LayoutParams(-1, -2));
        LinearLayout.LayoutParams scrollerSize = new LinearLayout.LayoutParams(-1, -2);
        content.addView(scroller, scrollerSize);

        popup = new PopupWindow(content, -2, -2, true);
        popup.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
        popup.setAnimationStyle(0);
        if (owner != null) popup.setElevation(dp(context, 2));
        popup.setOutsideTouchable(true);
        popup.setInputMethodMode(PopupWindow.INPUT_METHOD_NOT_NEEDED);
        if (Build.VERSION.SDK_INT >= 29) popup.setIsClippedToScreen(true);
        focusChanged = focused -> {
            if (!focused && popup.isShowing()) popup.dismiss();
        };
        popup.setOnDismissListener(() -> {
            finishEntrance();
            if (observer != null && observer.isAlive()) observer.removeOnGlobalLayoutListener(reflow);
            if (popupObserver != null && popupObserver.isAlive()) {
                popupObserver.removeOnWindowFocusChangeListener(focusChanged);
            }
            observer = null;
            popupObserver = null;
            draggingGrip = false;
        });
    }

    private ImageView createHandle() {
        ImageView grip = new ImageView(context);
        if (owner.kind == Kind.SHELF) {
            grip.setImageResource(owner.icon);
            grip.setImageTintList(owner.iconTint);
            grip.setContentDescription(context.getString(R.string.drag_menu));
        } else {
            grip.setImageResource(R.drawable.ic_drag_handle);
            grip.setContentDescription(context.getString(owner.kind == Kind.WINDOW
                    ? R.string.hold_drag_window_toolbar : R.string.hold_drag_menu));
        }
        grip.setScaleType(ImageView.ScaleType.CENTER);
        grip.setOnTouchListener(new Drag(grip) {
            private float startX;
            private float startY;
            private float draggedX;
            private float draggedY;

            @Override boolean requiresHold() {
                return owner.kind != Kind.SHELF && owner.edge != Edge.FLOATING;
            }

            @Override void start() {
                finishEntrance();
                startX = x;
                startY = y;
                draggingGrip = true;
            }

            @Override void move(float dx, float dy) {
                draggedX = startX + dx;
                draggedY = startY + dy;
                place(draggedX, draggedY);
            }

            @Override void finish() {
                draggingGrip = false;
                // Use the grip's pointer position even when the popup has run out of room.
                int releaseWidth = anchor.getWidth();
                int releaseHeight = anchor.getHeight();
                float releaseX = draggedX + (grip.getWidth() - releaseWidth) / 2f;
                float releaseY = draggedY + (grip.getHeight() - releaseHeight) / 2f;
                Edge edge = owner.dropEdge(releaseX - safe.left, releaseY - safe.top,
                        safe.right - releaseX - releaseWidth, safe.bottom - releaseY - releaseHeight);
                host.getLocationOnScreen(origin);
                if (edge != Edge.FLOATING) {
                    owner.dock(edge, releaseX - origin[0], releaseY - origin[1]);
                    popup.dismiss();
                } else {
                    owner.moveTo(x - origin[0], y - origin[1]);
                    place(x, y);
                }
            }
        });
        return grip;
    }

    static void bindTrigger(ImageButton button, View host, Kind kind, boolean freeFloating, Runnable open) {
        Trigger trigger = new Trigger(button, host, kind, freeFloating);
        button.setTag(trigger);
        button.setOnClickListener(view -> open.run());
        button.setOnTouchListener(trigger);
        host.addOnLayoutChangeListener((view, l, t, r, b, oldL, oldT, oldR, oldB) -> trigger.reflow());
        button.addOnLayoutChangeListener((view, l, t, r, b, oldL, oldT, oldR, oldB) -> trigger.reflow());
        trigger.reflow();
    }

    static void setDefaultTop(View button, int topPx) {
        if (button.getTag() instanceof Trigger) {
            Trigger trigger = (Trigger) button.getTag();
            trigger.defaultTop = topPx;
            if (!trigger.moved) trigger.reflow();
        }
    }

    static void setFreeFloating(View button, boolean enabled) {
        if (button.getTag() instanceof Trigger) {
            Trigger trigger = (Trigger) button.getTag();
            trigger.freeFloating = enabled;
            if (!enabled && trigger.edge == Edge.FLOATING) trigger.finish();
        }
    }

    static void refreshLocale(View button) {
        if (Build.VERSION.SDK_INT >= 30 && button.getTag() instanceof Trigger) {
            Trigger trigger = (Trigger) button.getTag();
            button.setStateDescription(trigger.dockedDescription());
        }
    }

    void compactRow() {
        compact = true;
    }

    void add(int icon, String label, boolean enabled, Runnable action) {
        addItem(LocusChrome.iconAction(context, icon, label), enabled, action);
    }

    void addText(String label, boolean enabled, Runnable action) {
        addLabeled(0, label, enabled, action);
    }

    void addLabeled(int icon, String label, boolean enabled, Runnable action) {
        hasText = true;
        Button button = new Button(context, null, android.R.attr.borderlessButtonStyle);
        button.setText(label);
        button.setAllCaps(false);
        button.setTextSize(14);
        ColorStateList colors = LocusChrome.actionText(context, false);
        button.setTextColor(colors);
        button.setGravity(Gravity.START | Gravity.CENTER_VERTICAL);
        button.setPaddingRelative(dp(context, 14), dp(context, 4), dp(context, 14), dp(context, 4));
        button.setBackground(LocusChrome.action(context, false));
        if (icon != 0) {
            Drawable mark = context.getDrawable(icon).mutate();
            mark.setTintList(colors);
            button.setCompoundDrawablePadding(dp(context, 10));
            button.setCompoundDrawablesRelativeWithIntrinsicBounds(mark, null, null, null);
        }
        addItem(button, enabled, action);
    }

    void addRow(View row, int heightPx) {
        hasText = true;
        appendRow(row, heightPx);
    }

    private void addItem(View item, boolean enabled, Runnable action) {
        item.setMinimumHeight(dp(context, TARGET));
        item.setEnabled(enabled);
        item.setOnClickListener(view -> {
            dismiss();
            anchor.post(action);
        });
        appendRow(item, -2);
    }

    private void appendRow(View item, int rowHeight) {
        if (usesActionGrid()) {
            gridActions.add(item);
            actionRows++;
            return;
        }
        if (actionRows > 0 && owner == null) {
            View divider = new View(context);
            divider.setBackgroundColor(context.getColor(R.color.divider));
            actions.addView(divider, new LinearLayout.LayoutParams(-1, 1));
        }
        actionRows++;
        actions.addView(item, new LinearLayout.LayoutParams(-1, rowHeight));
    }

    boolean isShowing() {
        return popup.isShowing();
    }

    void dismiss() {
        popup.dismiss();
    }

    void show() {
        if (isShowing() || !anchor.isShown() || actionRows == 0 || !visibleBounds(safe)) return;
        measurePanel();
        placeAtAnchor();
        animateEntrance();
        observer = anchor.getViewTreeObserver();
        observer.addOnGlobalLayoutListener(reflow);
        popupObserver = content.getViewTreeObserver();
        popupObserver.addOnWindowFocusChangeListener(focusChanged);
    }

    private void placeAtAnchor() {
        anchor.getLocationOnScreen(origin);
        int left = compact ? origin[0] + (anchor.getWidth() - width) / 2
                : origin[0] + anchor.getWidth() - width;
        int top = origin[1];
        if (owner != null && (owner.edge == Edge.TOP || owner.edge == Edge.BOTTOM)) {
            left = origin[0] + (anchor.getWidth() - width) / 2;
            top = owner.edge == Edge.TOP ? safe.top : safe.bottom - height;
        } else if (compact) {
            top += (anchor.getHeight() - height) / 2;
        } else if (hasText && owner == null) {
            int below = origin[1] + anchor.getHeight();
            top = below + height <= safe.bottom ? below : origin[1] - height;
        }
        place(left, top);
    }

    private void animateEntrance() {
        if (owner == null || !popup.isShowing() || !ValueAnimator.areAnimatorsEnabled()) return;
        anchor.getLocationOnScreen(origin);
        int travel = dp(context, 6);
        if (owner.edge == Edge.TOP || owner.edge == Edge.BOTTOM) {
            content.setTranslationY(owner.edge == Edge.TOP ? -travel : travel);
        } else {
            content.setTranslationX(Math.signum(origin[0] + anchor.getWidth() / 2f - x - width / 2f) * travel);
        }
        content.setAlpha(0.6f);
        content.animate().alpha(1f).translationX(0).translationY(0)
                .setInterpolator(OPEN_INTERPOLATOR).setDuration(160).start();
    }

    private void finishEntrance() {
        content.animate().cancel();
        content.setAlpha(1f);
        content.setTranslationX(0);
        content.setTranslationY(0);
    }

    private boolean visibleBounds(Rect target) {
        anchor.getWindowVisibleDisplayFrame(target);
        host.getLocationOnScreen(origin);
        if (!target.intersect(origin[0], origin[1], origin[0] + host.getWidth(), origin[1] + host.getHeight())) {
            return false;
        }
        int margin = Math.min(dp(context, 4), Math.min(target.width(), target.height()) / 8);
        target.inset(margin, margin);
        return !target.isEmpty();
    }

    private void measurePanel() {
        if (usesActionGrid()) {
            measureActionGrid();
        } else {
            int preferred = hasText ? 272 : TARGET;
            if (owner != null) preferred = owner.kind == Kind.SHELF ? 236 : 256;
            width = Math.min(dp(context, preferred), safe.width());
        }
        int overhead = 0;
        if (handle != null) {
            int handleHeight = Math.min(dp(context, gripHeight()), safe.height() / 4);
            handle.getLayoutParams().height = handleHeight;
            overhead = handleHeight;
        }
        actions.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));
        int rows = Math.min(actions.getMeasuredHeight(), Math.max(1, safe.height() - overhead));
        scroller.getLayoutParams().height = rows;
        height = Math.min(safe.height(), overhead + rows);
    }

    private int gripHeight() {
        return owner.kind == Kind.SHELF ? 32 : 24;
    }

    private boolean usesActionGrid() {
        return compact || owner != null && owner.kind == Kind.WINDOW;
    }

    private void measureActionGrid() {
        int padding = Math.min(dp(context, WINDOW_PADDING), Math.max(0, (safe.width() - dp(context, TARGET)) / 2));
        int cell = Math.min(dp(context, TARGET), safe.width() - 2 * padding);
        int columns = Math.min(actionRows, (safe.width() - 2 * padding) / cell);
        width = 2 * padding + columns * cell;
        actions.setPadding(padding, 0, padding, 0);
        if (gridColumns == columns && gridCell == cell) return;
        gridColumns = columns;
        gridCell = cell;
        for (View item : gridActions) {
            if (item.getParent() instanceof LinearLayout) ((LinearLayout) item.getParent()).removeView(item);
        }
        actions.removeAllViews();
        int cellHeight = dp(context, TARGET);
        for (int start = 0; start < gridActions.size(); start += columns) {
            LinearLayout row = new LinearLayout(context);
            row.setGravity(Gravity.START);
            actions.addView(row, new LinearLayout.LayoutParams(-1, -2));
            for (int index = start; index < Math.min(start + columns, gridActions.size()); index++) {
                row.addView(gridActions.get(index), new LinearLayout.LayoutParams(cell, cellHeight));
            }
        }
    }

    private void reflow() {
        if (!anchor.isShown()) {
            popup.dismiss();
            return;
        }
        Rect viewport = new Rect();
        if (!visibleBounds(viewport)) {
            popup.dismiss();
        } else if (!viewport.equals(safe)) {
            safe.set(viewport);
            measurePanel();
            if (owner != null && owner.edge != Edge.FLOATING && !draggingGrip) {
                owner.reflow();
                placeAtAnchor();
            } else {
                place(x, y);
            }
        }
    }

    private void place(float left, float top) {
        x = clamp(left, safe.left, safe.right - width);
        y = clamp(top, safe.top, safe.bottom - height);
        anchor.getLocationOnScreen(origin);
        int offsetX = Math.round(x) - origin[0];
        int offsetY = Math.round(y) - origin[1] - anchor.getHeight();
        if (popup.isShowing()) {
            popup.update(anchor, offsetX, offsetY, width, height);
        } else {
            popup.setWidth(width);
            popup.setHeight(height);
            popup.showAsDropDown(anchor, offsetX, offsetY, Gravity.LEFT);
        }
    }

    private static RippleDrawable surface(Context context, int color, int radius, int opacity) {
        int fill = (color & 0x00ffffff) | (opacity << 24);
        return new RippleDrawable(ColorStateList.valueOf(context.getColor(R.color.ripple)),
                LocusChrome.surface(context, fill, context.getColor(R.color.divider), radius), null);
    }

    private static int dp(Context context, int value) {
        return Math.round(value * context.getResources().getDisplayMetrics().density);
    }

    private static float clamp(float value, int minimum, int maximum) {
        return Math.max(minimum, Math.min(value, maximum));
    }

    /** Keeps tap and native keyboard activation separate from a pointer drag. */
    private abstract static class Drag implements View.OnTouchListener {
        private final View handle;
        private final int slop;
        private float downX;
        private float downY;
        private boolean dragging;
        private boolean interrupted;
        private boolean handledHold;
        private boolean dragArmed;
        private final Runnable hold;

        Drag(View handle) {
            this.handle = handle;
            slop = ViewConfiguration.get(handle.getContext()).getScaledTouchSlop();
            hold = () -> {
                if (requiresHold()) {
                    dragArmed = true;
                    handle.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS);
                } else {
                    handledHold = handle.performLongClick();
                }
            };
        }

        @Override
        public boolean onTouch(View view, MotionEvent event) {
            switch (event.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    downX = event.getRawX();
                    downY = event.getRawY();
                    dragging = false;
                    interrupted = false;
                    handledHold = false;
                    dragArmed = false;
                    handle.setPressed(true);
                    // A pane tab can sit inside the dock divider's expanded drag region.
                    handle.getParent().requestDisallowInterceptTouchEvent(true);
                    handle.postDelayed(hold, ViewConfiguration.getLongPressTimeout());
                    return true;
                case MotionEvent.ACTION_MOVE:
                    if (interrupted || handledHold) return true;
                    float dx = event.getRawX() - downX;
                    float dy = event.getRawY() - downY;
                    if (!dragging && (Math.abs(dx) > slop || Math.abs(dy) > slop)) {
                        handle.removeCallbacks(hold);
                        handle.setPressed(false);
                        if (requiresHold() && !dragArmed) {
                            interrupted = true;
                            return true;
                        }
                        dragging = true;
                        start();
                    }
                    if (dragging) move(dx, dy);
                    return true;
                case MotionEvent.ACTION_UP:
                    handle.removeCallbacks(hold);
                    handle.setPressed(false);
                    if (dragging) finish();
                    else if (!interrupted && !handledHold) handle.performClick();
                    return true;
                case MotionEvent.ACTION_POINTER_DOWN:
                case MotionEvent.ACTION_CANCEL:
                    handle.removeCallbacks(hold);
                    handle.setPressed(false);
                    if (dragging) finish();
                    interrupted = true;
                    dragging = false;
                    return true;
                default:
                    return true;
            }
        }

        boolean requiresHold() { return false; }
        abstract void start();
        abstract void move(float dx, float dy);
        abstract void finish();
    }

    private static final class Trigger extends Drag {
        private final ImageButton button;
        private final View host;
        private final Context context;
        private final Kind kind;
        private final int icon;
        private final int fillColor;
        private final ColorStateList iconTint;
        private final Rect bounds = new Rect();
        private final int[] hostOrigin = new int[2];
        private boolean freeFloating;
        private boolean moved;
        private Edge edge;
        private int defaultTop;
        private float left;
        private float top;
        private float startLeft;
        private float startTop;

        Trigger(ImageButton button, View host, Kind kind, boolean freeFloating) {
            super(button);
            this.button = button;
            this.host = host;
            this.kind = kind;
            edge = kind == Kind.WINDOW ? Edge.BOTTOM : Edge.LEFT;
            this.freeFloating = freeFloating;
            context = button.getContext();
            switch (kind) {
                case WORKSPACE:
                    icon = R.drawable.ic_settings;
                    fillColor = context.getColor(R.color.shelf_control_fill);
                    iconTint = ColorStateList.valueOf(context.getColor(R.color.workspace_control_icon));
                    break;
                case SHELF:
                    icon = R.drawable.ic_window_shelf;
                    fillColor = context.getColor(R.color.shelf_control_fill);
                    iconTint = ColorStateList.valueOf(context.getColor(R.color.shelf_control_icon));
                    break;
                default:
                    icon = R.drawable.ic_window_grip;
                    fillColor = context.getColor(R.color.surface_variant);
                    iconTint = null;
                    break;
            }
            style();
        }

        @Override void start() {
            startLeft = button.getX();
            startTop = button.getY();
        }

        @Override boolean requiresHold() {
            return kind == Kind.WINDOW && edge != Edge.FLOATING;
        }

        @Override void move(float dx, float dy) {
            moveTo(startLeft + dx, startTop + dy);
        }

        @Override void finish() {
            float x = button.getX();
            float y = button.getY();
            Edge next = dropEdge(x - bounds.left, y - bounds.top,
                    bounds.right - x - button.getWidth(), bounds.bottom - y - button.getHeight());
            if (next != Edge.FLOATING) dock(next, x, y);
        }

        Edge dropEdge(float leftGap, float topGap, float rightGap, float bottomGap) {
            float nearest = Math.min(leftGap, rightGap);
            Edge next = leftGap <= rightGap ? Edge.LEFT : Edge.RIGHT;
            if (Math.min(topGap, bottomGap) < nearest) {
                nearest = Math.min(topGap, bottomGap);
                next = topGap <= bottomGap ? Edge.TOP : Edge.BOTTOM;
            }
            return freeFloating && nearest > dp(context, SNAP) ? Edge.FLOATING : next;
        }

        void moveTo(float x, float y) {
            boolean wasDocked = edge != Edge.FLOATING;
            edge = Edge.FLOATING;
            moved = true;
            left = x;
            top = y;
            if (wasDocked) style();
            reflow();
        }

        void dock(Edge next, float x, float y) {
            boolean changedEdge = edge != next;
            edge = next;
            moved = true;
            left = x;
            top = y;
            if (changedEdge) style();
            reflow();
        }

        void reflow() {
            if (button.getWidth() == 0 || button.getHeight() == 0
                    || host.getWidth() == 0 || host.getHeight() == 0) return;
            int hostWidth = host.getWidth();
            int hostHeight = host.getHeight();
            if (host.isAttachedToWindow()) {
                host.getWindowVisibleDisplayFrame(bounds);
                host.getLocationOnScreen(hostOrigin);
                bounds.offset(-hostOrigin[0], -hostOrigin[1]);
                if (!bounds.intersect(0, 0, hostWidth, hostHeight)) bounds.set(0, 0, hostWidth, hostHeight);
            } else {
                bounds.set(0, 0, hostWidth, hostHeight);
            }
            int tab = dp(context, kind == Kind.WINDOW ? WINDOW_TAB : TAB);
            float defaultLeft = kind == Kind.WINDOW && !moved
                    ? (bounds.left + bounds.right - button.getWidth()) / 2f : left;
            float x = edge == Edge.LEFT ? bounds.left + tab - button.getWidth()
                    : edge == Edge.RIGHT ? bounds.right - tab
                    : clamp(defaultLeft, bounds.left, bounds.right - button.getWidth());
            float y = edge == Edge.TOP ? bounds.top + tab - button.getHeight()
                    : edge == Edge.BOTTOM ? bounds.bottom - tab
                    : clamp(moved ? top : defaultTop, bounds.top, bounds.bottom - button.getHeight());
            button.setX(x);
            button.setY(y);

            Rect slice = visibleTab(tab);
            button.setClipBounds(slice);
            if (Build.VERSION.SDK_INT >= 29) {
                // Keep system navigation active outside the draggable tab.
                List<Rect> exclusion = slice == null ? Collections.emptyList() : Collections.singletonList(slice);
                if (!button.getSystemGestureExclusionRects().equals(exclusion)) {
                    button.setSystemGestureExclusionRects(exclusion);
                }
            }
        }

        private Rect visibleTab(int tab) {
            int width = button.getWidth();
            int height = button.getHeight();
            switch (edge) {
                case LEFT: return new Rect(Math.max(0, width - tab), 0, width, height);
                case RIGHT: return new Rect(0, 0, Math.min(tab, width), height);
                case TOP: return new Rect(0, Math.max(0, height - tab), width, height);
                case BOTTOM: return new Rect(0, 0, width, Math.min(tab, height));
                default: return null;
            }
        }

        private void style() {
            boolean verticalGrip = kind == Kind.WINDOW && (edge == Edge.LEFT || edge == Edge.RIGHT);
            button.setImageResource(verticalGrip ? R.drawable.ic_window_grip_vertical : icon);
            button.setImageTintList(iconTint);
            int radius = kind == Kind.WINDOW ? 8 : 14;
            if (edge == Edge.FLOATING) {
                button.setBackground(new InsetDrawable(surface(context, fillColor, radius, 238), dp(context, 5)));
                button.setPadding(0, 0, 0, 0);
            } else {
                button.setBackground(kind == Kind.WINDOW ? LocusChrome.action(context, false)
                        : surface(context, fillColor, radius, 238));
                int inset = dp(context, TARGET - (kind == Kind.WINDOW ? WINDOW_TAB : TAB));
                button.setPadding(edge == Edge.LEFT ? inset : 0, edge == Edge.TOP ? inset : 0,
                        edge == Edge.RIGHT ? inset : 0, edge == Edge.BOTTOM ? inset : 0);
            }
            if (Build.VERSION.SDK_INT >= 30) button.setStateDescription(dockedDescription());
        }

        private String dockedDescription() {
            switch (edge) {
                case LEFT: return context.getString(R.string.controls_docked_left);
                case RIGHT: return context.getString(R.string.controls_docked_right);
                case TOP: return context.getString(R.string.controls_docked_top);
                case BOTTOM: return context.getString(R.string.controls_docked_bottom);
                default: return null;
            }
        }
    }
}
