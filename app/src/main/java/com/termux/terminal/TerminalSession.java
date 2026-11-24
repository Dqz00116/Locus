package com.termux.terminal;

import java.util.Arrays;
import java.util.function.Consumer;

/** Locus's remote-only replacement for the Termux view's local-process session. */
public final class TerminalSession extends TerminalOutput {
    private static final int SCROLLBACK_ROWS = 1_500;

    private final Consumer<byte[]> output;
    private final TerminalEmulator emulator;

    public TerminalSession(int columns, int rows, int cellWidth, int cellHeight,
                           Consumer<byte[]> output) {
        this.output = output;
        emulator = new TerminalEmulator(this, columns, rows, cellWidth, cellHeight,
                SCROLLBACK_ROWS, null);
    }

    public TerminalEmulator getEmulator() {
        return emulator;
    }

    public void updateSize(int columns, int rows, int cellWidth, int cellHeight) {
        emulator.resize(columns, rows, cellWidth, cellHeight);
    }

    public void append(byte[] data, int offset, int count) {
        if (offset != 0) data = Arrays.copyOfRange(data, offset, offset + count);
        emulator.append(data, count);
    }

    @Override
    public void write(byte[] data, int offset, int count) {
        if (count == 0) return;
        byte[] bytes = offset == 0 && count == data.length
                ? data : Arrays.copyOfRange(data, offset, offset + count);
        output.accept(bytes);
    }

    public void writeCodePoint(boolean prependEscape, int codePoint) {
        if (!Character.isValidCodePoint(codePoint)
                || (codePoint >= Character.MIN_SURROGATE && codePoint <= Character.MAX_SURROGATE)) {
            codePoint = TerminalEmulator.UNICODE_REPLACEMENT_CHAR;
        }
        String text = new String(Character.toChars(codePoint));
        write(prependEscape ? "\u001b" + text : text);
    }

    @Override public void titleChanged(String oldTitle, String newTitle) {}
    @Override public void onCopyTextToClipboard(String text) {}
    @Override public void onPasteTextFromClipboard() {}
    @Override public void onBell() {}
    @Override public void onColorsChanged() {}
}
