package dev.locus.app;

import android.app.Activity;
import android.app.Instrumentation;
import android.content.ComponentName;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.os.Bundle;
import android.view.KeyEvent;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputConnection;
import android.widget.FrameLayout;

import java.io.ByteArrayOutputStream;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.concurrent.atomic.AtomicReference;

/** Exercises the Android view's byte contract without an SSH host or installed Chinese IME. */
public final class TerminalSmoke extends Instrumentation {
    private static final String ESC = "\u001b";

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
            Intent intent = new Intent(getTargetContext(), MainActivity.class)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            activity = startActivitySync(intent);
            Activity host = activity;
            Fixture fixture = new Fixture();
            onUi(() -> fixture.install(host));
            waitForIdleSync();
            onUi(fixture::exerciseComposition);
            waitForIdleSync();
            onUi(fixture::exerciseSecondTerminal);
            onUi(fixture::exerciseRenderingAndResize);
            result.putString("stream", "\nPASS: local composition, UTF-8 commit, cancellation, focus, control keys,"
                    + " bracketed paste, CJK rendering, independent resize and keyboard configuration.\n");
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
