package dev.locus.app;

import java.io.Closeable;
import java.io.IOException;
import java.util.List;

/** The blocking remote operations used by the prototype views. */
interface RemoteConnection extends Closeable {
    boolean isOpen();
    Terminal openTerminal(String cwd, int columns, int rows, int width, int height)
            throws IOException;
    String canonicalDirectory(String path) throws IOException;
    List<FileEntry> list(String path) throws IOException;
    byte[] read(String path, int maxBytes) throws IOException;

    @Override
    void close();

    interface Terminal extends Closeable {
        int read(byte[] bytes) throws IOException;
        void write(byte[] bytes) throws IOException;
        void resize(int columns, int rows, int width, int height);
        boolean isOpen();
        /** Waits briefly for exit status after read returns EOF. Call only on a worker. */
        int awaitExitStatus() throws InterruptedException;

        @Override
        void close();
    }

    final class FileEntry {
        final String name;
        final boolean directory;
        final boolean regularFile;
        final boolean symbolicLink;
        final long size;

        FileEntry(String name, boolean directory, boolean regularFile, boolean symbolicLink, long size) {
            this.name = name;
            this.directory = directory;
            this.regularFile = regularFile;
            this.symbolicLink = symbolicLink;
            this.size = size;
        }
    }
}
