package dev.locus.app;

import com.jcraft.jsch.SocketFactory;

import dev.locus.app.OperationException.Reason;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import frp.Frp;
import frp.Tunnel;

/** The local handoff is usable only with the per-connection capability from the native visitor. */
final class FrpRoute implements SocketFactory, AutoCloseable {
    static final int MAX_CONFIG_BYTES = 64 * 1024;
    // Upstream allows a 7-second relay dial and a 10-second STCP visitor response.
    static final int SSH_CONNECT_TIMEOUT_MS = 20_000;
    private final Tunnel tunnel;

    static final class Choice {
        final String name;
        final String label;
        final String identity;
        final int legacyLoopbackPort;
        final boolean hasProxyDefinitions;

        Choice(JSONObject json) throws JSONException {
            name = json.getString("name");
            label = json.getString("label");
            identity = json.getString("identity");
            legacyLoopbackPort = json.optInt("legacyLoopbackPort");
            hasProxyDefinitions = json.optBoolean("hasProxyDefinitions");
        }
    }

    static boolean isLoopbackHost(String host) {
        if (host == null) return false;
        String effective = host.trim();
        if (effective.startsWith("[") && effective.endsWith("]")) {
            effective = effective.substring(1, effective.length() - 1);
        }
        if ("localhost".equalsIgnoreCase(effective) || "localhost.".equalsIgnoreCase(effective)
                || "::1".equals(effective) || "0:0:0:0:0:0:0:1".equals(effective)) {
            return true;
        }
        if (!effective.matches("127(?:\\.[0-9]{1,3}){3}")) return false;
        String[] octets = effective.split("\\.");
        return Integer.parseInt(octets[1]) <= 255 && Integer.parseInt(octets[2]) <= 255
                && Integer.parseInt(octets[3]) <= 255;
    }

    static List<Choice> inspect(byte[] config) throws IOException {
        try {
            JSONArray entries = new JSONArray(Frp.inspect(config));
            List<Choice> choices = new ArrayList<>(entries.length());
            for (int index = 0; index < entries.length(); index++) {
                choices.add(new Choice(entries.getJSONObject(index)));
            }
            return choices;
        } catch (Exception failure) {
            throw nativeFailure(failure, Reason.FRP_PROFILE_INVALID, "Could not read the FRP profile.");
        }
    }

    static byte[] select(byte[] config, String visitor) throws IOException {
        try {
            return Frp.select(config, visitor);
        } catch (Exception failure) {
            throw nativeFailure(failure, Reason.FRP_PROFILE_SELECTION_FAILED, "Could not select the STCP visitor.");
        }
    }

    static FrpRoute start(byte[] selected) throws IOException {
        try {
            return new FrpRoute(Frp.start(selected));
        } catch (Exception failure) {
            throw nativeFailure(failure, Reason.FRP_RELAY_START_FAILED, "Could not start the FRP visitor.");
        }
    }

    private static OperationException nativeFailure(Exception failure, Reason fallback, String fallbackMessage) {
        String message = failure.getMessage();
        Reason reason = message == null ? null : nativeReason(message);
        return reason == null ? new OperationException(fallback, fallbackMessage, failure)
                : new OperationException(reason, message, failure);
    }

    // The gomobile ABI supplies only text. Match the fixed errors we own in frp-engine.
    private static Reason nativeReason(String message) {
        switch (message) {
            case "Choose an FRP file no larger than 64 KiB.":
            case "Choose a nonempty FRP TOML file no larger than 64 KiB.":
                return Reason.FRP_PROFILE_SIZE;
            case "This file defines FRP proxies. Choose the client TOML with an STCP entry under [[visitors]].":
            case "Choose a self-contained FRP TOML with the relay settings and its STCP [[visitors]] entry.":
            case "Choose the FRP client TOML with an STCP entry under [[visitors]].":
            case "The FRP file contains no enabled STCP visitor.":
                return Reason.FRP_PROFILE_NEEDS_VISITOR;
            case "Use an FRP token specified directly in the TOML file.":
            case "The FRP file contains an unsupported authentication scope.":
            case "Choose TCP or KCP for FRP transport.protocol.":
            case "Choose v1 or v2 for FRP transport.wireProtocol.":
            case "This FRP profile requires transport.proxyURL, which Locus does not support.":
            case "Enable TLS for the FRP relay.":
            case "FRP imports support TLS with system trust instead of certificate file paths.":
            case "Locus supports regular STCP visitors without FRP plugins.":
                return Reason.FRP_PROFILE_UNSUPPORTED;
            case "Could not parse the FRP TOML file.":
            case "FRP visitor names must be unique.":
            case "Could not read the saved FRP visitor.":
            case "Enter a valid FRP relay address, port and user.":
            case "The FRP token must be a literal value no longer than 4096 bytes.":
            case "Enter a valid FRP TLS server name.":
            case "Each STCP visitor needs a valid name and serverName.":
            case "Each STCP visitor needs a literal secretKey no longer than 4096 bytes.":
            case "The saved FRP visitor has an invalid local port hint.":
                return Reason.FRP_PROFILE_INVALID;
            case "The selected STCP visitor was not found.": return Reason.FRP_PROFILE_SELECTION_FAILED;
            case "The FRP relay did not become ready in time.": return Reason.FRP_RELAY_TIMEOUT;
            case "Could not log in to the FRP relay. Check its address and token.": return Reason.FRP_RELAY_LOGIN_FAILED;
            case "Could not open the local FRP transport socket.":
            case "Could not initialize the FRP transport.":
            case "Could not configure the selected FRP visitor.":
                return Reason.FRP_RELAY_START_FAILED;
            default: return null;
        }
    }

    private FrpRoute(Tunnel tunnel) {
        this.tunnel = tunnel;
    }

    int port() {
        return Math.toIntExact(tunnel.getPort());
    }

    String failureHint() {
        return tunnel.getFailureHint();
    }

    static boolean isSshTimeout(Throwable failure) {
        for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
            if (cause instanceof SocketTimeoutException) return true;
        }
        return false;
    }

    @Override
    public Socket createSocket(String host, int port) throws IOException {
        if (!"127.0.0.1".equals(host) || port != port()) {
            throw new IOException("Invalid local FRP handoff.");
        }
        byte[] capability = tunnel.getToken();
        Socket socket = new Socket();
        boolean connected = false;
        try {
            socket.connect(new InetSocketAddress(host, port), 3_000);
            OutputStream output = socket.getOutputStream();
            output.write(capability);
            output.flush();
            connected = true;
            return socket;
        } finally {
            Arrays.fill(capability, (byte) 0);
            if (!connected) socket.close();
        }
    }

    @Override
    public InputStream getInputStream(Socket socket) throws IOException {
        return socket.getInputStream();
    }

    @Override
    public OutputStream getOutputStream(Socket socket) throws IOException {
        return socket.getOutputStream();
    }

    @Override
    public void close() {
        tunnel.close();
    }
}
