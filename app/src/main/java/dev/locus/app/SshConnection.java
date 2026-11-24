package dev.locus.app;

import com.jcraft.jsch.Channel;
import com.jcraft.jsch.ChannelExec;
import com.jcraft.jsch.ChannelShell;
import com.jcraft.jsch.ChannelSftp;
import com.jcraft.jsch.HostKey;
import com.jcraft.jsch.HostKeyRepository;
import com.jcraft.jsch.JSch;
import com.jcraft.jsch.JSchException;
import com.jcraft.jsch.Session;
import com.jcraft.jsch.SftpATTRS;
import com.jcraft.jsch.SftpException;
import com.jcraft.jsch.SocketFactory;
import com.jcraft.jsch.UserInfo;

import dev.locus.app.OperationException.Reason;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.Properties;
import java.util.concurrent.TimeUnit;

/** Blocking SSH operations. Each terminal has its own PTY. */
final class SshConnection implements RemoteConnection {
    static final int DEFAULT_CONNECT_TIMEOUT_MS = 10_000;
    private static final int IO_TIMEOUT_MS = 10_000;
    private static final int MAX_DIRECTORY_ENTRIES = 10_000;
    private static final int MAX_PREVIEW_BYTES = 16 * 1024 * 1024;
    static final int MAX_PRIVATE_KEY_BYTES = 64 * 1024;
    private final Session session;
    private final Runnable closeRoute;

    static final class Profile {
        final String host;
        final int port;
        final String username;
        final String routeIdentity;

        Profile(String host, int port, String username) {
            this(host, port, username, null);
        }

        Profile(String host, int port, String username, String routeIdentity) {
            String normalizedHost = host.trim();
            if (normalizedHost.startsWith("[") && normalizedHost.endsWith("]")) {
                normalizedHost = normalizedHost.substring(1, normalizedHost.length() - 1);
            }
            String normalizedUsername = username.trim();
            if (normalizedHost.isEmpty() || normalizedUsername.isEmpty()
                    || port < 1 || port > 65535) {
                throw new IllegalArgumentException("Enter an SSH host, a port from 1 to 65535, and a username.");
            }
            this.host = normalizedHost;
            this.port = port;
            this.username = normalizedUsername;
            this.routeIdentity = routeIdentity;
        }

        String endpoint() {
            return routeIdentity == null ? "[" + host.toLowerCase(Locale.ROOT) + "]:" + port : routeIdentity;
        }
    }

    private SshConnection(Session session, Runnable closeRoute) {
        this.session = session;
        this.closeRoute = closeRoute;
    }

    static SshConnection connect(Profile profile, String password, File hostPins)
            throws IOException, JSchException {
        return connect(profile, password, hostPins, null);
    }

    static SshConnection connect(Profile profile, String password, File hostPins,
            HostKeyApproval approval) throws IOException, JSchException {
        return connect(profile, password, null, hostPins, approval, null, null);
    }

    /** The caller transfers ownership of privateKey, which is cleared before return. */
    static SshConnection connectWithIdentity(Profile profile, byte[] privateKey,
            String passphrase, File hostPins, HostKeyApproval approval)
            throws IOException, JSchException {
        return connect(profile, passphrase, privateKey, hostPins, approval, null, null);
    }

    /** The caller closes the route on failure. A successful connection owns its cleanup. */
    static SshConnection connect(Profile profile, String secret, byte[] privateKey,
            File hostPins, HostKeyApproval approval, SocketFactory sockets, Runnable closeRoute)
            throws IOException, JSchException {
        return connect(profile, secret, privateKey, hostPins, approval, sockets, closeRoute,
                DEFAULT_CONNECT_TIMEOUT_MS);
    }

    static SshConnection connect(Profile profile, String secret, byte[] privateKey,
            File hostPins, HostKeyApproval approval, SocketFactory sockets, Runnable closeRoute,
            int connectTimeoutMs) throws IOException, JSchException {
        return connect(profile, secret, privateKey, hostPins, approval, sockets, closeRoute,
                connectTimeoutMs, null);
    }

    static SshConnection connect(Profile profile, String secret, byte[] privateKey,
            File hostPins, HostKeyApproval approval, SocketFactory sockets, Runnable closeRoute,
            int connectTimeoutMs, Runnable hostVerified) throws IOException, JSchException {
        try {
            if (privateKey != null && (privateKey.length == 0 || privateKey.length > MAX_PRIVATE_KEY_BYTES)) {
                throw new OperationException(Reason.SSH_KEY_SIZE, "Choose a private key file no larger than 64 KiB.");
            }
            return establish(profile, secret, privateKey, hostPins, approval, sockets, closeRoute,
                    connectTimeoutMs, hostVerified);
        } finally {
            if (privateKey != null) Arrays.fill(privateKey, (byte) 0);
        }
    }

    private static SshConnection establish(Profile profile, String secret, byte[] privateKey,
            File hostPins, HostKeyApproval approval, SocketFactory sockets, Runnable closeRoute,
            int connectTimeoutMs, Runnable hostVerified) throws IOException, JSchException {
        requireNotInterrupted();
        HostPins pins = new HostPins(profile.endpoint(), hostPins, approval, hostVerified);
        JSch jsch = new JSch();
        jsch.setHostKeyRepository(pins);
        Session session = jsch.getSession(profile.username, profile.host, profile.port);
        session.setHostKeyAlias(profile.endpoint());
        if (sockets != null) session.setSocketFactory(sockets);
        session.setConfig("StrictHostKeyChecking", "yes");
        session.setConfig("PreferredAuthentications", privateKey == null ? "password" : "publickey");
        // Raw host keys allow the same exact-key pinning flow for every server.
        session.setConfig("server_host_key", pins.negotiatedHostAlgorithms());
        session.setConfig("max_input_buffer_size", "131072");
        session.setServerAliveInterval(IO_TIMEOUT_MS);
        session.setServerAliveCountMax(2);
        byte[] secretBytes = secret == null ? new byte[0] : secret.getBytes(StandardCharsets.UTF_8);
        boolean connected = false;
        try {
            if (privateKey == null) {
                session.setPassword(secretBytes);
            } else {
                try {
                    jsch.addIdentity("Selected SSH key", privateKey, null,
                            secretBytes.length == 0 ? null : secretBytes);
                } catch (JSchException | RuntimeException invalidKey) {
                    // A parser error might contain part of the input. Do not retain its cause.
                    throw new OperationException(Reason.SSH_KEY_INVALID,
                            "Could not open the SSH key. Check the file format and passphrase.");
                }
            }
            Arrays.fill(secretBytes, (byte) 0);
            session.connect(connectTimeoutMs);
            requireNotInterrupted();
            connected = true;
            return new SshConnection(session, closeRoute);
        } catch (JSchException failure) {
            if (pins.failure != null) {
                throw pins.failure;
            }
            throw failure;
        } finally {
            Arrays.fill(secretBytes, (byte) 0);
            if (privateKey != null) {
                jsch.getIdentityRepository().removeAll();
            }
            if (!connected) {
                session.disconnect();
            }
        }
    }

    @Override
    public boolean isOpen() {
        return session.isConnected();
    }

    @Override
    public void close() {
        try {
            session.disconnect();
        } finally {
            if (closeRoute != null) closeRoute.run();
        }
    }

    @Override
    public Terminal openTerminal(String cwd, int columns, int rows, int width, int height)
            throws IOException {
        requireNotInterrupted();
        String command = cwd == null || cwd.isEmpty() ? null : shellCommand(cwd);
        Channel channel = openChannel(command == null ? "shell" : "exec");
        try {
            PtyResize resize;
            if (command == null) {
                ChannelShell shell = (ChannelShell) channel;
                shell.setPty(true);
                shell.setPtyType("xterm-256color", columns, rows, width, height);
                resize = shell::setPtySize;
            } else {
                ChannelExec shell = (ChannelExec) channel;
                shell.setCommand(command);
                shell.setPty(true);
                shell.setPtyType("xterm-256color", columns, rows, width, height);
                resize = shell::setPtySize;
            }
            InputStream input = channel.getInputStream();
            OutputStream output = channel.getOutputStream();
            channel.connect(IO_TIMEOUT_MS);
            requireNotInterrupted();
            return new SshTerminal(channel, input, output, resize);
        } catch (IOException | RuntimeException failure) {
            channel.disconnect();
            throw failure;
        } catch (JSchException failure) {
            channel.disconnect();
            throw sshError(failure);
        }
    }

    private interface PtyResize {
        void apply(int columns, int rows, int width, int height);
    }

    private static final class SshTerminal implements Terminal {
        private final Channel channel;
        private final InputStream input;
        private final OutputStream output;
        private final PtyResize resize;

        private SshTerminal(Channel channel, InputStream input, OutputStream output, PtyResize resize) {
            this.channel = channel;
            this.input = input;
            this.output = output;
            this.resize = resize;
        }

        @Override
        public int read(byte[] bytes) throws IOException {
            return input.read(bytes);
        }

        @Override
        public void write(byte[] bytes) throws IOException {
            if (!isOpen()) {
                throw new OperationException(Reason.TERMINAL_CLOSED, "The terminal channel is closed.");
            }
            output.write(bytes);
            output.flush();
        }

        @Override
        public void resize(int columns, int rows, int width, int height) {
            resize.apply(columns, rows, width, height);
        }

        @Override
        public boolean isOpen() {
            return channel.isConnected();
        }

        @Override
        public int awaitExitStatus() throws InterruptedException {
            // JSch closes the output stream on SSH EOF. The exit-status request can follow it.
            long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(500);
            while (channel.getExitStatus() < 0 && channel.isConnected()) {
                long remaining = deadline - System.nanoTime();
                if (remaining <= 0) {
                    break;
                }
                TimeUnit.NANOSECONDS.sleep(Math.min(remaining, TimeUnit.MILLISECONDS.toNanos(20)));
            }
            return channel.getExitStatus();
        }

        @Override
        public void close() {
            channel.disconnect();
        }
    }

    @Override
    public String canonicalDirectory(String path) throws IOException {
        ChannelSftp sftp = openSftp();
        try {
            String canonical = sftp.realpath(sftpPath(sftp, path));
            if (!sftp.stat(literalPath(canonical)).isDir()) {
                throw new OperationException(Reason.REMOTE_NOT_DIRECTORY, "The remote path is not a directory.");
            }
            return canonical;
        } catch (SftpException failure) {
            throw sshError(failure);
        } finally {
            sftp.disconnect();
        }
    }

    @Override
    public List<FileEntry> list(String path) throws IOException {
        ChannelSftp sftp = openSftp();
        try {
            String literal = literalPath(sftpPath(sftp, path));
            if (!sftp.stat(literal).isDir()) {
                throw new OperationException(Reason.REMOTE_NOT_DIRECTORY, "The remote path is not a directory.");
            }
            List<FileEntry> entries = new ArrayList<>();
            boolean[] limited = {false};
            sftp.ls(literal, entry -> {
                if (Thread.currentThread().isInterrupted()) {
                    return ChannelSftp.LsEntrySelector.BREAK;
                }
                String name = entry.getFilename();
                if (!name.isEmpty() && !name.equals(".") && !name.equals("..")
                        && name.indexOf('/') < 0 && name.indexOf('\0') < 0) {
                    if (entries.size() == MAX_DIRECTORY_ENTRIES) {
                        limited[0] = true;
                        return ChannelSftp.LsEntrySelector.BREAK;
                    }
                    SftpATTRS attributes = entry.getAttrs();
                    entries.add(new FileEntry(name, attributes.isDir(), attributes.isReg(),
                            attributes.isLink(), attributes.getSize()));
                }
                return ChannelSftp.LsEntrySelector.CONTINUE;
            });
            requireNotInterrupted();
            if (limited[0]) {
                throw new OperationException(Reason.DIRECTORY_LIMIT,
                        "The directory exceeds the 10,000-entry preview limit.");
            }
            return entries;
        } catch (SftpException failure) {
            throw sshError(failure);
        } finally {
            sftp.disconnect();
        }
    }

    @Override
    public byte[] read(String path, int maxBytes) throws IOException {
        if (maxBytes < 0 || maxBytes > MAX_PREVIEW_BYTES) {
            throw new IllegalArgumentException("Preview read limit must be between 0 and 16 MiB.");
        }
        ChannelSftp sftp = openSftp();
        try {
            String literal = literalPath(sftpPath(sftp, path));
            if (!sftp.lstat(literal).isReg()) {
                throw new OperationException(Reason.REMOTE_NOT_FILE, "The remote path is not a regular file.");
            }
            try (InputStream input = sftp.get(literal)) {
                byte[] buffer = new byte[16 * 1024];
                ByteArrayOutputStream output = new ByteArrayOutputStream(Math.min(maxBytes, buffer.length));
                while (output.size() < maxBytes) {
                    requireNotInterrupted();
                    int count = input.read(buffer, 0, Math.min(buffer.length, maxBytes - output.size()));
                    if (count == -1) {
                        break;
                    }
                    output.write(buffer, 0, count);
                }
                requireNotInterrupted();
                return output.toByteArray();
            }
        } catch (SftpException failure) {
            throw sshError(failure);
        } finally {
            sftp.disconnect();
        }
    }

    private Channel openChannel(String type) throws IOException {
        try {
            return session.openChannel(type);
        } catch (JSchException failure) {
            throw sshError(failure);
        }
    }

    private ChannelSftp openSftp() throws IOException {
        requireNotInterrupted();
        Channel channel = openChannel("sftp");
        try {
            channel.connect(IO_TIMEOUT_MS);
            requireNotInterrupted();
            return (ChannelSftp) channel;
        } catch (IOException | RuntimeException failure) {
            channel.disconnect();
            throw failure;
        } catch (JSchException failure) {
            channel.disconnect();
            throw sshError(failure);
        }
    }

    private static OperationException sshError(Exception failure) {
        String message = failure.getMessage();
        Reason reason = failure instanceof SftpException
                ? sftpFailureReason(((SftpException) failure).id) : Reason.SSH_OPERATION_FAILED;
        return new OperationException(reason, message == null || message.isEmpty()
                ? "The remote SSH operation failed." : message, failure);
    }

    private static Reason sftpFailureReason(int status) {
        switch (status) {
            case ChannelSftp.SSH_FX_NO_SUCH_FILE: return Reason.SFTP_NOT_FOUND;
            case ChannelSftp.SSH_FX_PERMISSION_DENIED: return Reason.SFTP_PERMISSION_DENIED;
            case ChannelSftp.SSH_FX_OP_UNSUPPORTED: return Reason.SFTP_UNSUPPORTED;
            case ChannelSftp.SSH_FX_NO_CONNECTION:
            case ChannelSftp.SSH_FX_CONNECTION_LOST: return Reason.SFTP_DISCONNECTED;
            default: return Reason.SSH_OPERATION_FAILED;
        }
    }

    private static String sftpPath(ChannelSftp sftp, String path) throws IOException, SftpException {
        validatePath(path);
        if (path.isEmpty() || path.equals("~")) {
            return sftp.getHome();
        }
        if (path.startsWith("~/")) {
            return sftp.getHome() + path.substring(1);
        }
        return path;
    }

    private static String literalPath(String path) {
        return path.replace("\\", "\\\\").replace("*", "\\*").replace("?", "\\?");
    }

    private static String shellCommand(String cwd) throws IOException {
        validatePath(cwd);
        String directory;
        if (cwd.equals("~") || cwd.startsWith("~/")) {
            directory = "\"$HOME\"" + shellWord(cwd.substring(1));
        } else {
            directory = shellWord(cwd.startsWith("/") ? cwd : "./" + cwd);
        }
        String script = "cd " + directory + " && exec \"${SHELL:-/bin/sh}\" -i";
        return "exec sh -c " + shellWord(script);
    }

    private static String shellWord(String value) {
        return "'" + value.replace("'", "'\\''") + "'";
    }

    private static void validatePath(String path) throws IOException {
        if (path == null || path.indexOf('\0') >= 0) {
            throw new OperationException(Reason.REMOTE_PATH_INVALID, "Invalid remote path.");
        }
    }

    private static void requireNotInterrupted() throws InterruptedIOException {
        if (Thread.currentThread().isInterrupted()) {
            throw new InterruptedIOException("SSH operation cancelled.");
        }
    }

    static final class HostKeyApproval {
        private final String endpoint;
        private final byte[] key;

        private HostKeyApproval(String endpoint, byte[] key) {
            this.endpoint = endpoint;
            this.key = key.clone();
        }
    }

    static final class UnknownHostKeyException extends OperationException {
        final HostKeyApproval approval;
        final String endpoint;
        final String keyType;
        final String fingerprint;

        private UnknownHostKeyException(String endpoint, HostKey hostKey, byte[] rawKey) {
            super(Reason.SSH_HOST_APPROVAL_REQUIRED, "Verify the SSH host fingerprint for " + endpoint + ".");
            approval = new HostKeyApproval(endpoint, rawKey);
            this.endpoint = endpoint;
            keyType = hostKey.getType();
            fingerprint = SshConnection.fingerprint(rawKey);
        }
    }

    static final class ChangedHostKeyException extends OperationException {
        final String endpoint;
        final String fingerprint;

        private ChangedHostKeyException(String endpoint, byte[] key) {
            super(Reason.SSH_HOST_CHANGED, "The SSH host key for " + endpoint + " changed. Connection refused.");
            this.endpoint = endpoint;
            fingerprint = SshConnection.fingerprint(key);
        }
    }

    private static String fingerprint(byte[] key) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(key);
            return "SHA256:" + Base64.getEncoder().withoutPadding().encodeToString(digest);
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    private static final class HostPins implements HostKeyRepository {
        private static final Object FILE_LOCK = new Object();
        private static final String RAW_HOST_ALGORITHMS =
                "ssh-ed25519,ecdsa-sha2-nistp256,ecdsa-sha2-nistp384,ecdsa-sha2-nistp521,"
                        + "rsa-sha2-512,rsa-sha2-256";
        private final String endpoint;
        private final File file;
        private final HostKeyApproval approval;
        private final Runnable hostVerified;
        private IOException failure;

        private HostPins(String endpoint, File file, HostKeyApproval approval, Runnable hostVerified) {
            this.endpoint = endpoint;
            this.file = file;
            this.approval = approval;
            this.hostVerified = hostVerified;
        }

        String negotiatedHostAlgorithms() throws IOException {
            synchronized (FILE_LOCK) {
                byte[] trusted = pinnedKey(load());
                if (trusted == null) {
                    return RAW_HOST_ALGORITHMS;
                }
                try {
                    String type = new HostKey(endpoint, trusted).getType();
                    String first = type.equals("ssh-rsa") ? "rsa-sha2-512,rsa-sha2-256" : type;
                    return first + "," + RAW_HOST_ALGORITHMS;
                } catch (JSchException | RuntimeException invalid) {
                    throw new OperationException(Reason.SSH_HOST_KEY_UNREADABLE,
                            "The saved SSH host key is unreadable.", invalid);
                }
            }
        }

        @Override
        public int check(String host, byte[] key) {
            if (!endpoint.equals(host)) {
                failure = new OperationException(Reason.SSH_HOST_IDENTITY_MISMATCH,
                        "SSH host identity does not match the endpoint.");
                return CHANGED;
            }
            synchronized (FILE_LOCK) {
                try {
                    Properties properties = load();
                    byte[] trusted = pinnedKey(properties);
                    if (trusted != null) {
                        if (Arrays.equals(trusted, key)) {
                            return verified();
                        }
                        failure = new ChangedHostKeyException(endpoint, key);
                        return CHANGED;
                    }
                    if (approval == null) {
                        failure = new UnknownHostKeyException(endpoint, new HostKey(endpoint, key), key);
                        return NOT_INCLUDED;
                    }
                    if (!endpoint.equals(approval.endpoint) || !Arrays.equals(approval.key, key)) {
                        failure = new ChangedHostKeyException(endpoint, key);
                        return CHANGED;
                    }
                    properties.setProperty(endpoint, Base64.getEncoder().encodeToString(key));
                    save(properties);
                    return verified();
                } catch (IOException | JSchException | RuntimeException problem) {
                    failure = new OperationException(Reason.SSH_HOST_VERIFICATION_FAILED,
                            "Could not verify the SSH host key.", problem);
                    return CHANGED;
                }
            }
        }

        private int verified() {
            if (hostVerified != null) {
                try {
                    hostVerified.run();
                } catch (RuntimeException ignored) {
                    // A diagnostic observer cannot change the result of host-key validation.
                }
            }
            return OK;
        }

        private Properties load() throws IOException {
            Properties properties = new Properties();
            if (file.exists()) {
                try (InputStream input = Files.newInputStream(file.toPath())) {
                    properties.load(input);
                } catch (IllegalArgumentException invalid) {
                    throw new OperationException(Reason.SSH_HOST_KEY_UNREADABLE,
                            "The saved SSH host keys are unreadable.", invalid);
                }
            }
            return properties;
        }

        private byte[] pinnedKey(Properties properties) throws IOException {
            String encoded = properties.getProperty(endpoint);
            if (encoded == null) {
                return null;
            }
            try {
                return Base64.getDecoder().decode(encoded);
            } catch (IllegalArgumentException invalid) {
                throw new OperationException(Reason.SSH_HOST_KEY_UNREADABLE,
                        "The saved SSH host key is unreadable.", invalid);
            }
        }

        private void save(Properties properties) throws IOException {
            Path target = file.toPath().toAbsolutePath();
            Path temporary = Files.createTempFile(target.getParent(), "ssh-pin-", ".tmp");
            try {
                try (OutputStream output = Files.newOutputStream(temporary)) {
                    properties.store(output, "Locus SSH host keys");
                }
                Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE,
                        StandardCopyOption.REPLACE_EXISTING);
            } finally {
                Files.deleteIfExists(temporary);
            }
        }

        @Override
        public String getKnownHostsRepositoryID() {
            return file.getName();
        }

        @Override
        public HostKey[] getHostKey() {
            return new HostKey[0];
        }

        @Override
        public HostKey[] getHostKey(String host, String type) {
            return new HostKey[0];
        }

        @Override
        public void add(HostKey hostKey, UserInfo userInfo) {}

        @Override
        public void remove(String host, String type) {}

        @Override
        public void remove(String host, String type, byte[] key) {}
    }
}
