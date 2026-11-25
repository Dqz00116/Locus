package dev.locus.app;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;

/** A two-file, single-writer journal for the most recent diagnostic events. */
final class DiagnosticJournal {
    static final int MAX_FILE_BYTES = 256 * 1024;

    private final File current;
    private final File previous;

    DiagnosticJournal(File directory) throws IOException {
        Files.createDirectories(directory.toPath());
        current = new File(directory, "timeline.jsonl");
        previous = new File(directory, "timeline.previous.jsonl");
        if (current.isFile()) discardIncompleteTail();
    }

    void append(String event) throws IOException {
        byte[] line = (event + '\n').getBytes(StandardCharsets.UTF_8);
        if (line.length > MAX_FILE_BYTES) return;
        if (current.length() + line.length > MAX_FILE_BYTES) {
            Files.move(current.toPath(), previous.toPath(), StandardCopyOption.REPLACE_EXISTING);
        }
        try (FileOutputStream output = new FileOutputStream(current, true)) {
            output.write(line);
        }
    }

    private void discardIncompleteTail() throws IOException {
        try (RandomAccessFile file = new RandomAccessFile(current, "rw")) {
            long length = file.length();
            long end = length;
            while (end > 0) {
                file.seek(end - 1);
                if (file.read() == '\n') break;
                end--;
            }
            if (end != length) file.setLength(end);
        }
    }
}
