package dev.locus.app;

import com.jcraft.jsch.SocketFactory;

import org.json.JSONArray;
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

        Choice(JSONObject json) throws Exception {
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

    static List<Choice> inspect(byte[] config) throws Exception {
        JSONArray entries = new JSONArray(Frp.inspect(config));
        List<Choice> choices = new ArrayList<>(entries.length());
        for (int index = 0; index < entries.length(); index++) {
            choices.add(new Choice(entries.getJSONObject(index)));
        }
        return choices;
    }

    static byte[] select(byte[] config, String visitor) throws Exception {
        return Frp.select(config, visitor);
    }

    static FrpRoute start(byte[] selected) throws Exception {
        return new FrpRoute(Frp.start(selected));
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
