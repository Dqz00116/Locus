package dev.locus.app;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

/** Runs the production transport against a disposable OpenSSH host. */
final class SshSmoke {
    public static void main(String[] args) throws Exception {
        if (args.length != 5 || (System.getenv("LOCUS_TEST_PASSWORD") == null
                && System.getenv("LOCUS_TEST_KEY_FILE") == null)) {
            throw new IllegalArgumentException("Set LOCUS_TEST_PASSWORD or LOCUS_TEST_KEY_FILE "
                    + "and specify <host> <port> <user> <pins-file> <fingerprint|changed>.");
        }
        SshConnection.Profile profile = new SshConnection.Profile(args[0], Integer.parseInt(args[1]), args[2]);
        File pins = new File(args[3]);
        if (args[4].equals("changed")) {
            check(pins.isFile(), "changed-key test requires the original host pin");
            try (RemoteConnection ignored = connect(profile, pins, null)) {
                throw new AssertionError("changed key was accepted");
            } catch (SshConnection.ChangedHostKeyException expected) {
                System.out.println("PASS changed key rejected");
                return;
            }
        }

        check(!pins.exists(), "first connection requires a fresh pins file");
        SshConnection.HostKeyApproval approval;
        try (RemoteConnection ignored = connect(profile, pins, null)) {
            throw new AssertionError("unknown key was accepted without approval");
        } catch (SshConnection.UnknownHostKeyException unknown) {
            check(unknown.fingerprint.equals(args[4]), "host fingerprint differs from the out-of-band value");
            approval = unknown.approval;
        }
        check(!pins.exists(), "unknown key was written before approval");

        try (RemoteConnection connection = connect(profile, pins, approval)) {
            check(connection.isOpen() && pins.isFile(), "approved host did not connect and persist");
            checkFiles(connection);
            checkTerminals(connection);
        }
        try (RemoteConnection again = connect(profile, pins, null)) {
            check(again.isOpen(), "pinned host did not reconnect");
        }
        System.out.println("PASS first trust, saved trust, SFTP, UTF-8 round-trip, independent PTYs and resize");
    }

    private static RemoteConnection connect(SshConnection.Profile profile, File pins,
            SshConnection.HostKeyApproval approval) throws Exception {
        String keyFile = System.getenv("LOCUS_TEST_KEY_FILE");
        if (keyFile == null) {
            return SshConnection.connect(profile, System.getenv("LOCUS_TEST_PASSWORD"), pins, approval);
        }
        byte[] key = Files.readAllBytes(Paths.get(keyFile));
        try {
            return SshConnection.connectWithIdentity(profile, key,
                    System.getenv("LOCUS_TEST_PASSPHRASE"), pins, approval);
        } finally {
            for (byte value : key) {
                check(value == 0, "private key buffer was not cleared");
            }
        }
    }

    private static void checkFiles(RemoteConnection connection) throws IOException {
        String home = connection.canonicalDirectory("~");
        List<RemoteConnection.FileEntry> entries = connection.list(home);
        check(entries.stream().anyMatch(file -> file.name.equals("preview*?.txt") && file.regularFile),
                "literal wildcard filename is missing");
        check(entries.stream().anyMatch(file -> file.name.equals("select quote';[]") && file.directory),
                "quoted directory is missing");
        check(entries.stream().anyMatch(file -> file.name.equals("preview-link.txt") && file.symbolicLink),
                "symlink metadata is missing");
        check(new String(connection.read(home + "/preview*?.txt", 7), StandardCharsets.UTF_8)
                .equals("fixture"), "bounded literal read differs");
        check(new String(connection.read(home + "/中文.txt", 128), StandardCharsets.UTF_8)
                .contains("中文"), "CJK read differs");
        check(connection.read(home + "/fixture.png", 4)[0] == (byte) 0x89, "PNG signature differs");
        try {
            connection.read(home + "/preview-link.txt", 32);
            throw new AssertionError("symlink unexpectedly opened");
        } catch (IOException expected) {
            check(expected.getMessage().contains("not a regular file"), "unexpected symlink error");
        }
    }

    private static void checkTerminals(RemoteConnection connection) throws Exception {
        ExecutorService reader = Executors.newSingleThreadExecutor();
        try (RemoteConnection.Terminal first = connection.openTerminal("", 87, 36, 800, 600);
             RemoteConnection.Terminal second = connection.openTerminal("~/select quote';[]", 101, 41, 900, 650)) {
            expect(reader, first, "stty -echo; printf '\\n'; stty size", "36 87");
            expect(reader, second, "stty -echo; printf '\\n'; stty size", "41 101");
            expect(reader, second, "printf '\\n'; pwd", connection.canonicalDirectory("~/select quote';[]"));
            expect(reader, first, "export LOCUS_SMOKE=first; printf '\\n%s%s\\n' locus_First _Pane",
                    "locus_First_Pane");
            expect(reader, second, "if [ -z \"$LOCUS_SMOKE\" ]; then printf '\\n%s%s\\n' locus_Separate _Pane; fi",
                    "locus_Separate_Pane");
            expect(reader, first, "printf '\\n%s%s\\n' 中文 '🙂'", "中文🙂");
            first.resize(123, 53, 1_100, 770);
            expect(reader, first, "printf '\\n'; stty size", "53 123");
            expect(reader, second, "printf '\\n'; stty size", "41 101");
            finish(reader, first, 7);
            expect(reader, second, "printf '\\n%s%s\\n' locus_Remaining _Pane", "locus_Remaining_Pane");
            finish(reader, second, 9);
            check(connection.isOpen(), "terminal exit closed the SSH session");
        } finally {
            reader.shutdownNow();
        }
    }

    private static void expect(ExecutorService reader, RemoteConnection.Terminal terminal, String command, String result)
            throws Exception {
        terminal.write((command + "\r").getBytes(StandardCharsets.UTF_8));
        readUntil(reader, terminal, "\n" + result + "\r\n");
    }

    private static void finish(ExecutorService reader, RemoteConnection.Terminal terminal, int code)
            throws Exception {
        terminal.write(("exit " + code + "\r").getBytes(StandardCharsets.UTF_8));
        readUntil(reader, terminal, null);
        check(terminal.awaitExitStatus() == code, "missing process exit status " + code);
    }

    private static void readUntil(ExecutorService reader, RemoteConnection.Terminal terminal, String expected)
            throws Exception {
        Future<?> read = reader.submit(() -> {
            byte[] buffer = new byte[4096];
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            while (output.size() < 65_536) {
                int count = terminal.read(buffer);
                if (count < 0) {
                    check(expected == null, "terminal ended before " + expected);
                    return null;
                }
                output.write(buffer, 0, count);
                if (expected != null && output.toString("UTF-8").contains(expected)) {
                    return null;
                }
            }
            throw new AssertionError("terminal output limit reached before " + expected);
        });
        read.get(10, TimeUnit.SECONDS);
    }

    private static void check(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }
}
