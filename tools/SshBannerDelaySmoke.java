package dev.locus.app;

import com.jcraft.jsch.JSchException;
import com.jcraft.jsch.SocketFactory;

import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.EOFException;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketException;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

/** Tests the production SSH client through identification and the start of key exchange. */
public final class SshBannerDelaySmoke {
    private static final String LOOPBACK = "127.0.0.1";
    private static final int BANNER_DELAY_MS = 11_500;
    // Matches the timeout supplied by the embedded FRP route.
    private static final int FRP_TIMEOUT_MS = 20_000;

    public static void main(String[] args) throws Exception {
        File directory = Files.createTempDirectory("locus-ssh-banner-smoke-").toFile();
        File pins = new File(directory, "host-pins.properties");
        try {
            verify(pins, false);
            verify(pins, true);
            require(!pins.exists(), "The fixture did not supply a host key to pin");
        } finally {
            Files.deleteIfExists(pins.toPath());
            Files.delete(directory.toPath());
        }
    }

    private static void verify(File pins, boolean frpEquivalent) throws Exception {
        ExecutorService server = Executors.newSingleThreadExecutor();
        try (ServerSocket listener = new ServerSocket(0, 1, InetAddress.getByName(LOOPBACK))) {
            listener.setSoTimeout(5_000);
            Future<Boolean> kex = server.submit(() -> sendDelayedBanner(listener));
            SshConnection.Profile profile = new SshConnection.Profile(LOOPBACK, listener.getLocalPort(), "fixture");
            long started = System.nanoTime();
            JSchException failure;
            try (RemoteConnection ignored = frpEquivalent
                    ? SshConnection.connect(profile, "", null, pins, null, loopbackFactory(), null, FRP_TIMEOUT_MS)
                    : SshConnection.connect(profile, "", pins)) {
                throw new AssertionError("The fixture did not complete key exchange or authentication");
            } catch (JSchException expected) {
                failure = expected;
            }
            long elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);
            boolean reachedKex = kex.get(6, TimeUnit.SECONDS);
            if (frpEquivalent) {
                require(reachedKex && !isReadTimeout(failure) && elapsedMs >= BANNER_DELAY_MS - 250,
                        "FRP-equivalent socket did not reach key exchange after the delayed banner: " + failure);
                System.out.printf("PASS FRP-equivalent socket reached SSH_MSG_KEXINIT after %d ms%n", elapsedMs);
            } else {
                require(!reachedKex && isReadTimeout(failure) && elapsedMs >= 9_000 && elapsedMs < 14_000,
                        "Direct SSH did not time out around 10 seconds before key exchange: " + failure);
                System.out.printf("PASS direct SSH read timed out before key exchange after %d ms%n", elapsedMs);
            }
        } finally {
            server.shutdownNow();
        }
    }

    private static boolean sendDelayedBanner(ServerSocket listener) throws Exception {
        try (Socket client = listener.accept()) {
            client.setSoTimeout(4_000);
            Thread.sleep(BANNER_DELAY_MS);
            OutputStream output = client.getOutputStream();
            output.write("SSH-2.0-LocusBannerSmoke\r\n".getBytes(StandardCharsets.US_ASCII));
            output.flush();

            DataInputStream input = new DataInputStream(client.getInputStream());
            require(readIdentification(input).startsWith("SSH-2.0-"), "Invalid client SSH identification");
            int packetLength = input.readInt();
            int paddingLength = input.readUnsignedByte();
            int message = input.readUnsignedByte();
            require(packetLength >= 6 && packetLength <= 65_536 && paddingLength >= 4
                            && paddingLength <= packetLength - 2 && message == 20,
                    "Expected SSH_MSG_KEXINIT as the first client packet");
            return true;
        } catch (EOFException | SocketException clientClosed) {
            return false;
        }
    }

    private static String readIdentification(DataInputStream input) throws IOException {
        ByteArrayOutputStream line = new ByteArrayOutputStream();
        for (int i = 0; i < 255; i++) {
            int value = input.readUnsignedByte();
            if (value == '\n') return line.toString(StandardCharsets.US_ASCII.name());
            line.write(value);
        }
        throw new IOException("Client SSH identification exceeds 255 bytes");
    }

    private static SocketFactory loopbackFactory() {
        return new SocketFactory() {
            @Override public Socket createSocket(String host, int port) throws IOException {
                if (!LOOPBACK.equals(host)) throw new IOException("Fixture accepts only loopback");
                Socket socket = new Socket();
                try {
                    socket.connect(new InetSocketAddress(host, port), 3_000);
                    return socket;
                } catch (IOException failure) {
                    socket.close();
                    throw failure;
                }
            }

            @Override public InputStream getInputStream(Socket socket) throws IOException {
                return socket.getInputStream();
            }

            @Override public OutputStream getOutputStream(Socket socket) throws IOException {
                return socket.getOutputStream();
            }
        };
    }

    private static boolean isReadTimeout(Throwable failure) {
        for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
            if (cause instanceof SocketTimeoutException) return true;
        }
        return false;
    }

    private static void require(boolean passed, String message) {
        if (!passed) throw new AssertionError(message);
    }
}
