package dev.locus.app;

import android.content.Context;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.AtomicFile;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.security.GeneralSecurityException;
import java.security.Key;
import java.security.KeyStore;
import java.security.ProviderException;
import java.util.Arrays;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

/** App-private storage for credentials the user explicitly chooses to keep on this device. */
final class LocalCredentials {
    enum Slot {
        FRP_PROFILE("frp-profile.enc", 1),
        SSH_PRIVATE_KEY("ssh-private-key.enc", 2),
        SSH_CONNECTION("ssh-connection.enc", 3);

        private final String fileName;
        private final byte id;

        Slot(String fileName, int id) {
            this.fileName = fileName;
            this.id = (byte) id;
        }
    }

    private static final Object STORAGE_LOCK = new Object();
    private static final String KEYSTORE = "AndroidKeyStore";
    private static final String KEY_ALIAS = "locus.credentials.v1";
    private static final String TRANSFORMATION = "AES/GCM/NoPadding";
    private static final byte VERSION = 1;
    private static final int MAX_PAYLOAD_BYTES = 64 * 1024;
    private static final int NONCE_BYTES = 12;
    private static final int TAG_BYTES = 16;
    private static final int HEADER_BYTES = 1 + NONCE_BYTES;
    private static final int MAX_RECORD_BYTES = HEADER_BYTES + MAX_PAYLOAD_BYTES + TAG_BYTES;

    private final File directory;

    LocalCredentials(Context context) {
        directory = context.getNoBackupFilesDir();
    }

    boolean exists(Slot slot) {
        File base = baseFile(slot);
        return base.exists() || sibling(base, ".bak").exists();
    }

    /** Calls to read, save, and forget run on an I/O worker. The caller owns returned plaintext. */
    byte[] read(Slot slot) throws IOException {
        synchronized (STORAGE_LOCK) {
            try {
                byte[] record = readRecord(new AtomicFile(baseFile(slot)));
                if (record.length <= HEADER_BYTES + TAG_BYTES || record[0] != VERSION) {
                    throw new IOException();
                }
                SecretKey key = credentialKey(false);
                Cipher cipher = Cipher.getInstance(TRANSFORMATION);
                cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(TAG_BYTES * 8,
                        record, 1, NONCE_BYTES));
                cipher.updateAAD(new byte[]{VERSION, slot.id});
                return cipher.doFinal(record, HEADER_BYTES, record.length - HEADER_BYTES);
            } catch (FileNotFoundException missing) {
                if (!exists(slot)) return null;
            } catch (IOException | GeneralSecurityException | ProviderException ignored) {
                // The platform and crypto exceptions are intentionally kept out of UI and logs.
            }
            throw new IOException("Could not open the saved credential. Import it again.");
        }
    }

    void save(Slot slot, byte[] plaintext) throws IOException {
        if (plaintext.length == 0 || plaintext.length > MAX_PAYLOAD_BYTES) {
            throw new IOException("The credential must be between 1 byte and 64 KiB.");
        }
        synchronized (STORAGE_LOCK) {
            try {
                Cipher cipher = Cipher.getInstance(TRANSFORMATION);
                cipher.init(Cipher.ENCRYPT_MODE, credentialKey(true));
                cipher.updateAAD(new byte[]{VERSION, slot.id});
                byte[] nonce = cipher.getIV();
                if (nonce == null || nonce.length != NONCE_BYTES) throw new GeneralSecurityException();
                byte[] ciphertext = cipher.doFinal(plaintext);
                byte[] record = ByteBuffer.allocate(HEADER_BYTES + ciphertext.length)
                        .put(VERSION)
                        .put(nonce)
                        .put(ciphertext)
                        .array();
                writeRecord(new AtomicFile(baseFile(slot)), record);
            } catch (IOException | GeneralSecurityException | ProviderException ignored) {
                throw new IOException("Could not save the credential on this device. Try importing it again.");
            }
        }
    }

    void forget(Slot slot) throws IOException {
        synchronized (STORAGE_LOCK) {
            File base = baseFile(slot);
            new AtomicFile(base).delete();
            if (exists(slot) || sibling(base, ".new").exists()) {
                throw new IOException("Could not remove the saved credential. Try again.");
            }
        }
    }

    private File baseFile(Slot slot) {
        return new File(directory, slot.fileName);
    }

    private static File sibling(File base, String suffix) {
        return new File(base.getPath() + suffix);
    }

    private static SecretKey credentialKey(boolean create) throws GeneralSecurityException, IOException {
        KeyStore store = KeyStore.getInstance(KEYSTORE);
        store.load(null);
        Key existing = store.getKey(KEY_ALIAS, null);
        if (existing instanceof SecretKey) return (SecretKey) existing;
        if (existing != null || !create) throw new GeneralSecurityException();

        KeyGenerator generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE);
        generator.init(new KeyGenParameterSpec.Builder(KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                .setKeySize(256)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setRandomizedEncryptionRequired(true)
                .build());
        return generator.generateKey();
    }

    private static byte[] readRecord(AtomicFile file) throws IOException {
        try (FileInputStream input = file.openRead()) {
            byte[] buffer = new byte[MAX_RECORD_BYTES + 1];
            int length = 0;
            while (length < buffer.length) {
                int count = input.read(buffer, length, buffer.length - length);
                if (count < 0) return Arrays.copyOf(buffer, length);
                if (count == 0) throw new IOException();
                length += count;
            }
            throw new IOException();
        }
    }

    private static void writeRecord(AtomicFile file, byte[] record) throws IOException {
        FileOutputStream output = file.startWrite();
        boolean ready = false;
        try {
            output.write(record);
            output.getFD().sync();
            ready = true;
        } finally {
            if (ready) file.finishWrite(output);
            else file.failWrite(output);
        }
        // AtomicFile logs a failed rename instead of returning an error.
        if (!Arrays.equals(record, readRecord(file))) throw new IOException();
    }
}
