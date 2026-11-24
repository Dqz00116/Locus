package dev.locus.app;

import android.annotation.SuppressLint;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.InsetDrawable;
import android.view.Gravity;
import android.view.HapticFeedbackConstants;
import android.view.InputDevice;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.PointerIcon;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.FrameLayout;

import java.util.ArrayList;
import java.util.List;
import java.util.function.BiConsumer;

/** Recursively lays out a flat set of attached panes and their split handles. */
final class PaneDock extends ViewGroup {
    enum Edge { TOP, BOTTOM, LEFT, RIGHT }

    private static final float CENTER = 0.5f;
    private final int gutter;
    private final int minimumLeafWidth;
    private final int minimumLeafHeight;
    private final Rect viewport = new Rect();
    private Node root;
    private Divider forwardedDivider;
    private Divider pendingDivider;
    private float pendingX;
    private float pendingY;
    private BiConsumer<View, List<View>> splitMenu;
    private boolean mountTargeting;

    PaneDock(Context context) {
        super(context);
        gutter = dp(6);
        minimumLeafWidth = dp(160);
        minimumLeafHeight = dp(112);
        setClipChildren(false);
    }

    void setInitial(View pane) {
        if (root != null || pane.getParent() != null) {
            throw new IllegalStateException("The first pane needs an empty dock.");
        }
        root = new Leaf(pane);
        addView(pane);
        pane.setVisibility(VISIBLE);
    }

    boolean split(View target, View newPane, Edge edge) {
        if (newPane == null || newPane.getParent() != null || !canSplit(target, edge)) return false;
        Leaf existing = findLeaf(root, target);
        Leaf added = new Leaf(newPane);
        Split parent = existing.parent;
        boolean before = edge == Edge.LEFT || edge == Edge.TOP;
        Split branch = new Split(before ? added : existing, before ? existing : added, isVertical(edge));
        replaceChild(parent, existing, branch);
        addView(newPane);
        newPane.setVisibility(VISIBLE);
        addView(branch.divider);
        if (mountTargeting) branch.divider.setVisibility(INVISIBLE);
        return true;
    }

    boolean canSplit(View target, Edge edge) {
        Leaf leaf = findLeaf(root, target);
        if (leaf == null || edge == null) return false;
        if (getWidth() == 0 || getHeight() == 0) return true;
        updateGeometry(getWidth(), getHeight());
        boolean vertical = isVertical(edge);
        int span = vertical ? leaf.bounds.height() : leaf.bounds.width();
        return !leaf.bounds.isEmpty()
                && span >= 2 * (vertical ? minimumLeafHeight : minimumLeafWidth) + gutter;
    }

    void remove(View pane) {
        Leaf leaf = findLeaf(root, pane);
        if (leaf == null) return;
        Split parent = leaf.parent;
        if (parent == null) {
            root = null;
        } else {
            Node sibling = parent.first == leaf ? parent.second : parent.first;
            replaceChild(parent.parent, parent, sibling);
            if (forwardedDivider == parent.divider) forwardedDivider = null;
            if (pendingDivider == parent.divider) pendingDivider = null;
            parent.divider.stopTracking();
            removeView(parent.divider);
        }
        removeView(pane);
    }

    void setOnSplitMenu(BiConsumer<View, List<View>> callback) {
        splitMenu = callback;
    }

    void setMountTargeting(boolean targeting) {
        if (mountTargeting == targeting) return;
        mountTargeting = targeting;
        if (targeting) {
            pendingDivider = null;
            forwardedDivider = null;
        }
        for (int index = 0; index < getChildCount(); index++) {
            View child = getChildAt(index);
            if (!(child instanceof Divider)) continue;
            Divider divider = (Divider) child;
            if (targeting) divider.stopTracking();
            divider.setVisibility(targeting ? INVISIBLE : VISIBLE);
        }
    }

    private static boolean isVertical(Edge edge) {
        return edge == Edge.TOP || edge == Edge.BOTTOM;
    }

    private void replaceChild(Split parent, Node oldChild, Node replacement) {
        replacement.parent = parent;
        if (parent == null) root = replacement;
        else if (parent.first == oldChild) parent.first = replacement;
        else parent.second = replacement;
    }

    private static Leaf findLeaf(Node node, View pane) {
        if (node instanceof Leaf) {
            Leaf leaf = (Leaf) node;
            return leaf.pane == pane ? leaf : null;
        }
        if (node == null) return null;
        Split branch = (Split) node;
        Leaf first = findLeaf(branch.first, pane);
        return first != null ? first : findLeaf(branch.second, pane);
    }

    private static void collectPanes(Node node, List<View> panes) {
        if (node instanceof Leaf) {
            panes.add(((Leaf) node).pane);
        } else {
            Split branch = (Split) node;
            collectPanes(branch.first, panes);
            collectPanes(branch.second, panes);
        }
    }

    @Override
    protected LayoutParams generateDefaultLayoutParams() {
        return new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT);
    }

    @Override
    protected void onMeasure(int widthSpec, int heightSpec) {
        int width = dimension(widthSpec, getSuggestedMinimumWidth());
        int height = dimension(heightSpec, getSuggestedMinimumHeight());
        setMeasuredDimension(width, height);
        updateGeometry(width, height);
        placeTree(root, true);
    }

    @Override
    protected void onLayout(boolean changed, int left, int top, int right, int bottom) {
        updateGeometry(right - left, bottom - top);
        placeTree(root, false);
    }

    private static int dimension(int spec, int fallback) {
        return MeasureSpec.getMode(spec) == MeasureSpec.UNSPECIFIED ? fallback : MeasureSpec.getSize(spec);
    }

    private void updateGeometry(int width, int height) {
        int left = getPaddingLeft();
        int top = getPaddingTop();
        viewport.set(left, top, Math.max(left, width - getPaddingRight()),
                Math.max(top, height - getPaddingBottom()));
        if (root != null) {
            measureMinimum(root);
            position(root, viewport.left, viewport.top, viewport.right, viewport.bottom);
        }
    }

    private void measureMinimum(Node node) {
        if (node instanceof Leaf) {
            node.minWidth = minimumLeafWidth;
            node.minHeight = minimumLeafHeight;
            return;
        }
        Split branch = (Split) node;
        measureMinimum(branch.first);
        measureMinimum(branch.second);
        if (branch.vertical) {
            node.minWidth = Math.max(branch.first.minWidth, branch.second.minWidth);
            node.minHeight = branch.first.minHeight + gutter + branch.second.minHeight;
        } else {
            node.minWidth = branch.first.minWidth + gutter + branch.second.minWidth;
            node.minHeight = Math.max(branch.first.minHeight, branch.second.minHeight);
        }
    }

    private void position(Node node, int left, int top, int right, int bottom) {
        node.bounds.set(left, top, right, bottom);
        if (!(node instanceof Split)) return;
        Split branch = (Split) node;
        int span = branch.vertical ? bottom - top : right - left;
        int separator = Math.min(gutter, span);
        int available = span - separator;
        int first = Math.round(available * boundedFraction(branch, branch.fraction, available));
        if (branch.vertical) {
            int splitY = top + first;
            branch.dividerBounds.set(left, splitY, right, splitY + separator);
            position(branch.first, left, top, right, splitY);
            position(branch.second, left, splitY + separator, right, bottom);
        } else {
            int splitX = left + first;
            branch.dividerBounds.set(splitX, top, splitX + separator, bottom);
            position(branch.first, left, top, splitX, bottom);
            position(branch.second, splitX + separator, top, right, bottom);
        }
    }

    private static float boundedFraction(Split branch, float fraction, int available) {
        int firstMin = branch.vertical ? branch.first.minHeight : branch.first.minWidth;
        int secondMin = branch.vertical ? branch.second.minHeight : branch.second.minWidth;
        if (available < firstMin + secondMin || available == 0) {
            return (float) firstMin / (firstMin + secondMin);
        }
        return Math.max((float) firstMin / available,
                Math.min(fraction, 1 - (float) secondMin / available));
    }

    private void placeTree(Node node, boolean measure) {
        if (node instanceof Leaf) {
            place(((Leaf) node).pane, node.bounds, measure);
        } else if (node != null) {
            Split branch = (Split) node;
            placeTree(branch.first, measure);
            place(branch.divider, branch.dividerBounds, measure);
            placeTree(branch.second, measure);
        }
    }

    private static void place(View view, Rect bounds, boolean measure) {
        if (measure) {
            view.measure(MeasureSpec.makeMeasureSpec(bounds.width(), MeasureSpec.EXACTLY),
                    MeasureSpec.makeMeasureSpec(bounds.height(), MeasureSpec.EXACTLY));
        } else {
            view.layout(bounds.left, bounds.top, bounds.right, bounds.bottom);
        }
    }

    @Override
    public boolean onInterceptTouchEvent(MotionEvent event) {
        if (mountTargeting) return false;
        int action = event.getActionMasked();
        if (action == MotionEvent.ACTION_MOVE) return beginPendingDrag(event);
        if (action != MotionEvent.ACTION_DOWN) {
            pendingDivider = null;
            return false;
        }
        forwardedDivider = null;
        pendingDivider = null;
        boolean touch = event.isFromSource(InputDevice.SOURCE_TOUCHSCREEN);
        int radius = dp(24);
        float closest = Float.MAX_VALUE;
        for (int index = 0; index < getChildCount(); index++) {
            View child = getChildAt(index);
            if (!(child instanceof Divider) || child.getVisibility() != VISIBLE) continue;
            Divider divider = (Divider) child;
            float x = event.getX() - (divider.getLeft() + divider.getWidth() / 2f);
            float y = event.getY() - (divider.getTop() + divider.getHeight() / 2f);
            float distance = x * x + y * y;
            if (Math.abs(x) > radius || Math.abs(y) > radius) continue;
            if (divider.touchesGrip(event.getX(), event.getY())
                    || (event.getX() >= divider.getLeft() && event.getX() < divider.getRight()
                    && event.getY() >= divider.getTop() && event.getY() < divider.getBottom())) {
                pendingDivider = null;
                forwardedDivider = divider;
                return true;
            }
            if (touch && distance < closest) {
                closest = distance;
                pendingDivider = divider;
            }
        }
        pendingX = event.getRawX();
        pendingY = event.getRawY();
        // The visible handle takes taps; the rest of the 48dp target takes resize drags only.
        return false;
    }

    @Override
    public PointerIcon onResolvePointerIcon(MotionEvent event, int pointerIndex) {
        if (!mountTargeting) {
            for (int index = 0; index < getChildCount(); index++) {
                View child = getChildAt(index);
                if (child instanceof Divider && child.getVisibility() == VISIBLE
                        && ((Divider) child).touchesGrip(event.getX(pointerIndex), event.getY(pointerIndex))) {
                    return child.getPointerIcon();
                }
            }
        }
        return super.onResolvePointerIcon(event, pointerIndex);
    }

    private boolean beginPendingDrag(MotionEvent event) {
        Divider divider = pendingDivider;
        if (divider == null || event.getPointerCount() != 1) return false;
        float dx = Math.abs(event.getRawX() - pendingX);
        float dy = Math.abs(event.getRawY() - pendingY);
        float along = divider.branch.vertical ? dy : dx;
        float across = divider.branch.vertical ? dx : dy;
        if (along <= divider.touchSlop || along <= across) return false;
        pendingDivider = null;
        forwardedDivider = divider;
        divider.startTracking(pendingX, pendingY);
        divider.dragTo(event.getRawX(), event.getRawY());
        return true;
    }

    @SuppressLint("ClickableViewAccessibility")
    @Override
    public boolean onTouchEvent(MotionEvent event) {
        if (mountTargeting) return super.onTouchEvent(event);
        int action = event.getActionMasked();
        if (forwardedDivider == null) {
            if (pendingDivider == null) return super.onTouchEvent(event);
            // A pane without a touch target may leave this gesture with the dock itself.
            if (action == MotionEvent.ACTION_MOVE) beginPendingDrag(event);
            else if (action != MotionEvent.ACTION_DOWN) pendingDivider = null;
            return true;
        }
        Divider target = forwardedDivider;
        if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
            forwardedDivider = null;
        }
        return target.onTouchEvent(event);
    }

    private int availableSpan(Split branch) {
        int span = branch.vertical ? branch.bounds.height() : branch.bounds.width();
        return Math.max(0, span - gutter);
    }

    private void resize(Split branch, float fraction) {
        int available = availableSpan(branch);
        int minimum = (branch.vertical ? branch.minHeight : branch.minWidth) - gutter;
        if (available <= 0 || available < minimum) return;
        float bounded = boundedFraction(branch, fraction, available);
        if (branch.fraction != bounded) {
            branch.fraction = bounded;
            requestLayout();
        }
    }

    private int dp(float value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private abstract static class Node {
        final Rect bounds = new Rect();
        Split parent;
        int minWidth;
        int minHeight;
    }

    private static final class Leaf extends Node {
        final View pane;

        Leaf(View pane) {
            this.pane = pane;
        }
    }

    private final class Split extends Node {
        final Rect dividerBounds = new Rect();
        final Divider divider;
        final boolean vertical;
        Node first;
        Node second;
        float fraction = CENTER;

        Split(Node first, Node second, boolean vertical) {
            this.first = first;
            this.second = second;
            this.vertical = vertical;
            first.parent = this;
            second.parent = this;
            divider = new Divider(getContext(), this);
        }
    }

    private final class Divider extends FrameLayout {
        private final Split branch;
        private final View grip;
        private final Paint dotPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final GradientDrawable gripShape = new GradientDrawable();
        private final int touchSlop;
        private float startX;
        private float startY;
        private float startFraction;
        private boolean tracking;
        private boolean dragging;

        Divider(Context context, Split branch) {
            super(context);
            this.branch = branch;
            touchSlop = ViewConfiguration.get(context).getScaledTouchSlop();
            setClipChildren(false);
            setElevation(dp(1));
            setFocusable(true);
            grip = new View(context) {
                @Override
                protected void onDraw(Canvas canvas) {
                    super.onDraw(canvas);
                    float density = getResources().getDisplayMetrics().density;
                    float centerX = getWidth() / 2f;
                    float centerY = getHeight() / 2f;
                    float radius = 1.25f * density;
                    for (int dot = -1; dot <= 1; dot++) {
                        float offset = dot * 4.5f * density;
                        canvas.drawCircle(centerX + (branch.vertical ? offset : 0),
                                centerY + (branch.vertical ? 0 : offset), radius, dotPaint);
                    }
                }
            };
            grip.setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
            int width = dp(branch.vertical ? 30 : 18);
            int height = dp(branch.vertical ? 18 : 30);
            gripShape.setCornerRadius(dp(6));
            // The inset keeps the visible capsule smaller than its hit area.
            grip.setBackground(new InsetDrawable(gripShape, dp(3)));
            addView(grip, new FrameLayout.LayoutParams(width, height, Gravity.CENTER));
            setContentDescription("Split options. Tap for window actions, or drag to resize.");
            setPointerIcon(PointerIcon.getSystemIcon(context, branch.vertical
                    ? PointerIcon.TYPE_VERTICAL_DOUBLE_ARROW : PointerIcon.TYPE_HORIZONTAL_DOUBLE_ARROW));
            updateGripAppearance();
            setOnClickListener(view -> {
                if (mountTargeting || splitMenu == null) return;
                List<View> panes = new ArrayList<>();
                collectPanes(branch, panes);
                splitMenu.accept(grip, panes);
            });
        }

        boolean touchesGrip(float x, float y) {
            float localX = x - getLeft();
            float localY = y - getTop();
            return localX >= grip.getLeft() && localX < grip.getRight()
                    && localY >= grip.getTop() && localY < grip.getBottom();
        }

        @Override
        protected void drawableStateChanged() {
            super.drawableStateChanged();
            if (grip != null) updateGripAppearance();
        }

        @Override
        public CharSequence getAccessibilityClassName() {
            return Button.class.getName();
        }

        private void updateGripAppearance() {
            Context context = getContext();
            boolean active = isFocused() || isPressed();
            gripShape.setColor(context.getColor(active ? R.color.accent_surface : R.color.surface));
            gripShape.setStroke(dp(1), context.getColor(active ? R.color.accent : R.color.divider));
            dotPaint.setColor(context.getColor(active ? R.color.accent : R.color.text_secondary));
            grip.invalidate();
        }

        @SuppressLint("ClickableViewAccessibility")
        @Override
        public boolean onTouchEvent(MotionEvent event) {
            if (mountTargeting) return false;
            switch (event.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    startTracking(event.getRawX(), event.getRawY());
                    return true;
                case MotionEvent.ACTION_MOVE:
                    dragTo(event.getRawX(), event.getRawY());
                    return true;
                case MotionEvent.ACTION_UP:
                    boolean click = tracking && !dragging;
                    boolean snap = tracking && dragging && Math.abs(branch.fraction - CENTER) < 0.035f;
                    stopTracking();
                    if (click) {
                        performClick();
                    } else if (snap) {
                        resize(branch, CENTER);
                        performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK);
                    }
                    return true;
                case MotionEvent.ACTION_POINTER_DOWN:
                case MotionEvent.ACTION_CANCEL:
                    stopTracking();
                    return true;
                default:
                    return tracking;
            }
        }

        private void startTracking(float x, float y) {
            startX = x;
            startY = y;
            startFraction = boundedFraction(branch, branch.fraction, availableSpan(branch));
            tracking = true;
            dragging = false;
            setPressed(true);
            getParent().requestDisallowInterceptTouchEvent(true);
        }

        private void dragTo(float x, float y) {
            if (!tracking) return;
            float delta = branch.vertical ? y - startY : x - startX;
            if (Math.abs(delta) > touchSlop) dragging = true;
            int span = availableSpan(branch);
            if (dragging && span > 0) resize(branch, startFraction + delta / span);
        }

        private void stopTracking() {
            if (!tracking) return;
            tracking = false;
            setPressed(false);
            if (getParent() != null) getParent().requestDisallowInterceptTouchEvent(false);
        }

        @Override
        public boolean onKeyDown(int keyCode, KeyEvent event) {
            if (mountTargeting) return false;
            int decrement = branch.vertical ? KeyEvent.KEYCODE_DPAD_UP : KeyEvent.KEYCODE_DPAD_LEFT;
            int increment = branch.vertical ? KeyEvent.KEYCODE_DPAD_DOWN : KeyEvent.KEYCODE_DPAD_RIGHT;
            if (keyCode == decrement || keyCode == increment) {
                float current = boundedFraction(branch, branch.fraction, availableSpan(branch));
                resize(branch, current + (keyCode == decrement ? -0.1f : 0.1f));
                return true;
            }
            return super.onKeyDown(keyCode, event);
        }
    }
}
