package dev.locus.app;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.Base64;

/** The last successful setup and its opt-in secret, stored together in one encrypted record. */
final class SavedSshConnection {
    final boolean frp;
    final String host;
    final int port;
    final String username;
    final String endpoint;
    final String keyDigest;
    final boolean hasKeyCopy;
    final boolean needsSecret;
    final boolean hasSecret;

    SavedSshConnection(boolean frp, String host, int port, String username, String endpoint,
                       String keyDigest, boolean hasKeyCopy, boolean needsSecret, boolean hasSecret) {
        this.frp = frp;
        this.host = host;
        this.port = port;
        this.username = username;
        this.endpoint = endpoint;
        this.keyDigest = keyDigest;
        this.hasKeyCopy = hasKeyCopy;
        this.needsSecret = needsSecret;
        this.hasSecret = hasSecret;
    }

    boolean usesKey() {
        return !keyDigest.isEmpty();
    }

    boolean matchesForm(boolean frp, SshConnection.Profile direct, String username, String frpIdentity) {
        String target = frp ? frpIdentity : direct == null ? null : direct.endpoint();
        return this.frp == frp && this.username.equals(username) && endpoint.equals(target);
    }

    boolean matchesAuthentication(String endpoint, String username, String keyDigest) {
        return this.endpoint.equals(endpoint) && this.username.equals(username)
                && this.keyDigest.equals(keyDigest);
    }

    boolean canConnect(LocalCredentials credentials) {
        return (!frp || credentials.exists(LocalCredentials.Slot.FRP_PROFILE))
                && (!usesKey() || hasKeyCopy && credentials.exists(LocalCredentials.Slot.SSH_PRIVATE_KEY))
                && (!needsSecret || hasSecret);
    }

    static String digest(byte[] key) {
        if (key == null) return "";
        try {
            return Base64.getEncoder().encodeToString(MessageDigest.getInstance("SHA-256").digest(key));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    private SavedSshConnection withoutSecret() {
        return new SavedSshConnection(frp, host, port, username, endpoint, keyDigest,
                hasKeyCopy, needsSecret, false);
    }

    static final class Record {
        final SavedSshConnection profile;
        final String secret;

        Record(SavedSshConnection profile, String secret) {
            this.profile = profile;
            this.secret = secret;
        }
    }

    static Record read(LocalCredentials credentials) throws IOException {
        byte[] payload = credentials.read(LocalCredentials.Slot.SSH_CONNECTION);
        if (payload == null) return null;
        try {
            JSONObject json = new JSONObject(new String(payload, StandardCharsets.UTF_8));
            String secret = json.optString("secret", "");
            SavedSshConnection profile = new SavedSshConnection(json.getBoolean("frp"),
                    json.getString("host"), json.getInt("port"), json.getString("username"),
                    json.getString("endpoint"), json.getString("keyDigest"), json.getBoolean("hasKeyCopy"),
                    json.getBoolean("needsSecret"), !secret.isEmpty());
            return new Record(profile, secret);
        } catch (JSONException malformed) {
            throw new IOException("Could not read the saved connection. Open Connection setup to configure it again.");
        } finally {
            Arrays.fill(payload, (byte) 0);
        }
    }

    void write(LocalCredentials credentials, String secret) throws IOException {
        try {
            JSONObject json = new JSONObject()
                    .put("frp", frp)
                    .put("host", host)
                    .put("port", port)
                    .put("username", username)
                    .put("endpoint", endpoint)
                    .put("keyDigest", keyDigest)
                    .put("hasKeyCopy", hasKeyCopy)
                    .put("needsSecret", needsSecret);
            if (hasSecret) json.put("secret", secret);
            byte[] payload = json.toString().getBytes(StandardCharsets.UTF_8);
            try {
                credentials.save(LocalCredentials.Slot.SSH_CONNECTION, payload);
            } finally {
                Arrays.fill(payload, (byte) 0);
            }
        } catch (JSONException malformed) {
            throw new IOException("Could not save this connection.");
        }
    }

    static SavedSshConnection forgetSecret(LocalCredentials credentials, boolean passphrase) throws IOException {
        Record stored = read(credentials);
        if (stored == null) return null;
        SavedSshConnection profile = stored.profile;
        if (profile.usesKey() != passphrase || !profile.hasSecret) return profile;
        SavedSshConnection updated = profile.withoutSecret();
        updated.write(credentials, "");
        return updated;
    }
}
