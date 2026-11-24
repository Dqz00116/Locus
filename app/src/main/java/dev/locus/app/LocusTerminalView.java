package dev.locus.app;

import android.annotation.SuppressLint;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Matrix;
import android.graphics.Rect;
import android.graphics.Typeface;
import android.os.Build;
import android.text.Editable;
import android.text.InputType;
import android.text.Selection;
import android.text.TextPaint;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.InputDevice;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.inputmethod.BaseInputConnection;
import android.view.inputmethod.CursorAnchorInfo;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputConnection;
import android.view.inputmethod.InputMethodManager;

import com.termux.terminal.TerminalSession;
import com.termux.terminal.TextStyle;
import com.termux.view.TerminalView;
import com.termux.view.TerminalViewClient;

/** Android terminal presentation. All methods and listener callbacks run on the UI thread. */
public final class LocusTerminalView extends TerminalView {
    public interface Listener {
        void onInput(byte[] data);
        void onResize(int columns, int rows, int widthPx, int heightPx);
    }

    public static final class Size {
        public final int columns;
        public final int rows;
        public final int widthPx;
        public final int heightPx;

        Size(int columns, int rows, int widthPx, int heightPx) {
            this.columns = columns;
            this.rows = rows;
            this.widthPx = widthPx;
            this.heightPx = heightPx;
        }
    }

    private static final int MAX_DELETE_KEYS = 32;
    private final InputMethodManager inputMethod;
    private final TextPaint compositionPaint = new TextPaint(TextPaint.ANTI_ALIAS_FLAG);
    private Listener listener;
    private TerminalSession session;
    private Size size;
    private TerminalInputConnection inputConnection;
    private int backgroundColor;

    public LocusTerminalView(Context context) {
        super(context, null);
        inputMethod = context.getSystemService(InputMethodManager.class);
        setTerminalViewClient(new ViewClient());
        setFocusable(true);
        setFocusableInTouchMode(true);
        setImportantForAutofill(IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS);
        int textSize = Math.round(TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_SP, 14, getResources().getDisplayMetrics()));
        setTextSize(textSize);
        compositionPaint.setTypeface(Typeface.MONOSPACE);
        compositionPaint.setTextSize(textSize);
        resetTerminal();
    }

    public void setListener(Listener listener) {
        this.listener = listener;
    }

    public Size getTerminalSize() {
        return size;
    }

    public void append(byte[] data, int offset, int count) {
        session.append(data, offset, count);
        updateBackground();
        onScreenUpdated();
        if (inputConnection != null) inputConnection.updateAnchor(false);
    }

    public void resetTerminal() {
        clearInputConnection();
        stopTextSelectionMode();
        if (size == null) size = cellSize(80, 24);
        session = new TerminalSession(size.columns, size.rows, Math.round(mRenderer.getFontWidth()),
                mRenderer.getFontLineSpacing(), data -> {
                    if (listener != null) listener.onInput(data);
                });
        attachSession(session);
        updateBackground();
        if (isFocused() && hasWindowFocus() && inputMethod != null) inputMethod.restartInput(this);
        invalidate();
    }

    public void showKeyboard() {
        requestFocus();
        post(() -> {
            if (isFocused() && isShown() && inputMethod != null) {
                inputMethod.showSoftInput(this, InputMethodManager.SHOW_IMPLICIT);
            }
        });
    }

    @Override
    public InputConnection onCreateInputConnection(EditorInfo outAttrs) {
        clearInputConnection();
        outAttrs.inputType = InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE;
        outAttrs.imeOptions = EditorInfo.IME_FLAG_NO_FULLSCREEN | EditorInfo.IME_FLAG_NO_EXTRACT_UI
                | EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING | EditorInfo.IME_FLAG_NO_ENTER_ACTION;
        outAttrs.initialSelStart = 0;
        outAttrs.initialSelEnd = 0;
        inputConnection = new TerminalInputConnection();
        return inputConnection;
    }

    @Override
    protected void onFocusChanged(boolean focused, int direction, Rect previous) {
        super.onFocusChanged(focused, direction, previous);
        if (!focused) clearInputConnection();
    }

    @Override
    public void onWindowFocusChanged(boolean focused) {
        super.onWindowFocusChanged(focused);
        if (!focused) {
            clearInputConnection();
        } else if (isFocused() && inputConnection == null && inputMethod != null) {
            inputMethod.restartInput(this);
        }
    }

    @Override
    protected void onDetachedFromWindow() {
        clearInputConnection();
        super.onDetachedFromWindow();
    }

    @SuppressLint("ClickableViewAccessibility")
    @Override
    public boolean onTouchEvent(MotionEvent event) {
        if (event.getActionMasked() == MotionEvent.ACTION_DOWN) requestFocus();
        return super.onTouchEvent(event);
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (keyCode == KeyEvent.KEYCODE_V && event.isCtrlPressed() && event.isShiftPressed()) {
            if (event.getRepeatCount() == 0) pasteFromClipboard();
            return true;
        }
        return super.onKeyDown(keyCode, event);
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        if (inputConnection == null || mEmulator == null || getTopRow() != 0) return;
        Editable composing = inputConnection.getEditable();
        if (composing.length() == 0) return;
        int lineHeight = mRenderer.getFontLineSpacing();
        float baseline = getPointY(mEmulator.getCursorRow()) + lineHeight;
        String text = TextUtils.ellipsize(composing, compositionPaint,
                getWidth(), TextUtils.TruncateAt.END).toString();
        float width = compositionPaint.measureText(text);
        float x = Math.max(0, Math.min(getPointX(mEmulator.getCursorCol()), getWidth() - width));
        compositionPaint.setColor(0xff264664);
        canvas.drawRect(x, baseline + compositionPaint.ascent(), x + width,
                baseline + compositionPaint.descent(), compositionPaint);
        compositionPaint.setColor(0xffecf4ff);
        compositionPaint.setUnderlineText(true);
        canvas.drawText(text, x, baseline, compositionPaint);
        compositionPaint.setUnderlineText(false);
    }

    private void updateBackground() {
        int newColor = session.getEmulator().mColors.mCurrentColors[TextStyle.COLOR_INDEX_BACKGROUND];
        if (backgroundColor != newColor) {
            backgroundColor = newColor;
            setBackgroundColor(newColor);
        }
    }

    private Size cellSize(int columns, int rows) {
        return new Size(columns, rows, Math.round(columns * mRenderer.getFontWidth()),
                rows * mRenderer.getFontLineSpacing());
    }

    private void clearInputConnection() {
        if (inputConnection == null) return;
        inputConnection.clearPreedit();
        inputConnection = null;
        invalidate();
    }

    private final class TerminalInputConnection extends BaseInputConnection {
        private int batchDepth;
        private boolean monitorCursor;

        TerminalInputConnection() {
            super(LocusTerminalView.this, true);
            Selection.setSelection(getEditable(), 0);
        }

        private boolean isActive() {
            return inputConnection == this && isFocused() && hasWindowFocus() && isShown();
        }

        @Override public boolean beginBatchEdit() {
            if (!isActive()) return false;
            batchDepth++;
            return true;
        }

        @Override public boolean endBatchEdit() {
            if (batchDepth == 0) return false;
            if (--batchDepth == 0) inputChanged();
            return batchDepth > 0;
        }

        @Override public boolean setComposingText(CharSequence text, int newCursorPosition) {
            if (!isActive()) return false;
            boolean updated = super.setComposingText(text, newCursorPosition);
            inputChanged();
            return updated;
        }

        @Override public boolean setSelection(int start, int end) {
            if (!isActive()) return false;
            boolean updated = super.setSelection(start, end);
            inputChanged();
            return updated;
        }

        @Override public boolean finishComposingText() {
            if (!isActive()) return false;
            // Clearing or closing the composing span is not an explicit candidate commit.
            clearPreedit();
            inputChanged();
            return true;
        }

        @Override public boolean commitText(CharSequence text, int newCursorPosition) {
            if (!isActive()) return false;
            clearPreedit();
            stopTextSelectionMode();
            if (text != null && text.length() > 0) {
                String committed = text.toString();
                if ("\n".equals(committed) || "\r".equals(committed)) {
                    session.write("\r");
                } else if (committed.indexOf('\n') >= 0 || committed.indexOf('\r') >= 0) {
                    pasteText(committed);
                } else {
                    session.write(committed);
                }
            }
            inputChanged();
            return true;
        }

        @Override public boolean deleteSurroundingText(int beforeLength, int afterLength) {
            if (!isActive()) return false;
            if (getEditable().length() == 0) return sendDeletes(beforeLength, afterLength);
            boolean updated = super.deleteSurroundingText(beforeLength, afterLength);
            inputChanged();
            return updated;
        }

        @Override public boolean deleteSurroundingTextInCodePoints(int beforeLength, int afterLength) {
            if (!isActive()) return false;
            if (getEditable().length() == 0) return sendDeletes(beforeLength, afterLength);
            boolean updated = super.deleteSurroundingTextInCodePoints(beforeLength, afterLength);
            inputChanged();
            return updated;
        }

        @Override public boolean sendKeyEvent(KeyEvent event) {
            return isActive() && LocusTerminalView.this.dispatchKeyEvent(event);
        }

        @Override public boolean performContextMenuAction(int id) {
            if (!isActive() || id != android.R.id.paste) return false;
            pasteFromClipboard();
            return true;
        }

        @Override public boolean requestCursorUpdates(int mode) {
            if (!isActive()) return false;
            monitorCursor = (mode & CURSOR_UPDATE_MONITOR) != 0;
            if ((mode & CURSOR_UPDATE_IMMEDIATE) != 0) updateAnchor(true);
            return true;
        }

        @Override public void closeConnection() {
            if (inputConnection == this) clearInputConnection();
            super.closeConnection();
        }

        void clearPreedit() {
            Editable editable = getEditable();
            removeComposingSpans(editable);
            editable.clear();
            Selection.setSelection(editable, 0);
        }

        private boolean sendDeletes(int beforeLength, int afterLength) {
            if (mEmulator == null || beforeLength < 0 || afterLength < 0
                    || beforeLength > MAX_DELETE_KEYS || afterLength > MAX_DELETE_KEYS) return false;
            for (int i = 0; i < beforeLength; i++) handleKeyCode(KeyEvent.KEYCODE_DEL, 0);
            for (int i = 0; i < afterLength; i++) handleKeyCode(KeyEvent.KEYCODE_FORWARD_DEL, 0);
            return true;
        }

        private void inputChanged() {
            if (batchDepth != 0 || !isActive()) return;
            Editable editable = getEditable();
            if (inputMethod != null) {
                inputMethod.updateSelection(LocusTerminalView.this, Selection.getSelectionStart(editable),
                        Selection.getSelectionEnd(editable), getComposingSpanStart(editable),
                        getComposingSpanEnd(editable));
            }
            updateAnchor(false);
            invalidate();
        }

        void updateAnchor(boolean immediate) {
            // transformMatrixToGlobal is public from API 29. Older IMEs can use their default placement.
            if (Build.VERSION.SDK_INT < 29 || inputMethod == null || !isActive()
                    || (!monitorCursor && !immediate) || mEmulator == null) return;
            Editable editable = getEditable();
            Matrix screenMatrix = new Matrix();
            transformMatrixToGlobal(screenMatrix);
            int x = getPointX(mEmulator.getCursorCol());
            int top = getPointY(mEmulator.getCursorRow());
            int baseline = top + mRenderer.getFontLineSpacing();
            int flags = getTopRow() == 0 ? CursorAnchorInfo.FLAG_HAS_VISIBLE_REGION
                    : CursorAnchorInfo.FLAG_HAS_INVISIBLE_REGION;
            CursorAnchorInfo.Builder anchor = new CursorAnchorInfo.Builder().setMatrix(screenMatrix)
                    .setSelectionRange(Selection.getSelectionStart(editable), Selection.getSelectionEnd(editable))
                    .setInsertionMarkerLocation(x, top, baseline, baseline + compositionPaint.descent(), flags);
            int start = getComposingSpanStart(editable);
            int end = getComposingSpanEnd(editable);
            if (start >= 0 && end > start) anchor.setComposingText(start, editable.subSequence(start, end));
            inputMethod.updateCursorAnchorInfo(LocusTerminalView.this, anchor.build());
        }
    }

    private final class ViewClient implements TerminalViewClient {
        @Override public void onSingleTapUp(MotionEvent event) {
            if (!event.isFromSource(InputDevice.SOURCE_MOUSE)) showKeyboard();
        }

        @Override public void onEmulatorSet() {
            Size updated = cellSize(mEmulator.mColumns, mEmulator.mRows);
            if (size != null && updated.columns == size.columns && updated.rows == size.rows
                    && updated.widthPx == size.widthPx && updated.heightPx == size.heightPx) return;
            size = updated;
            if (listener != null) listener.onResize(size.columns, size.rows, size.widthPx, size.heightPx);
        }

        @Override public float onScale(float scale) { return 1f; }
        @Override public boolean shouldBackButtonBeMappedToEscape() { return false; }
        @Override public boolean shouldEnforceCharBasedInput() { return false; }
        @Override public boolean shouldUseCtrlSpaceWorkaround() { return false; }
        @Override public boolean isTerminalViewSelected() { return isFocused(); }
        @Override public void copyModeChanged(boolean copyMode) {}
        @Override public boolean onKeyDown(int keyCode, KeyEvent event, TerminalSession session) { return false; }
        @Override public boolean onKeyUp(int keyCode, KeyEvent event) { return false; }
        @Override public boolean onLongPress(MotionEvent event) { return false; }
        @Override public boolean readControlKey() { return false; }
        @Override public boolean readAltKey() { return false; }
        @Override public boolean readShiftKey() { return false; }
        @Override public boolean readFnKey() { return false; }
        @Override public boolean onCodePoint(int codePoint, boolean ctrlDown, TerminalSession session) { return false; }
        @Override public void logError(String tag, String message) {}
        @Override public void logWarn(String tag, String message) {}
        @Override public void logInfo(String tag, String message) {}
        @Override public void logDebug(String tag, String message) {}
        @Override public void logVerbose(String tag, String message) {}
        @Override public void logStackTraceWithMessage(String tag, String message, Exception error) {}
        @Override public void logStackTrace(String tag, Exception error) {}
    }
}
