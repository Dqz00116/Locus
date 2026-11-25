package dev.locus.app;

import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.os.SystemClock;
import android.util.Log;

import org.json.JSONObject;

import java.io.File;
import java.io.IOException;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/** Structured connection events for debuggable builds. */
final class DiagnosticLog {
    enum Route { DIRECT, FRP_STCP }
    enum Auth { PASSWORD, KEY }
    enum Stage {
        PREPARE, FRP_RELAY, STCP, SSH_HANDSHAKE, HOST_KEY, SSH_AUTH,
        SFTP, SFTP_LIST, SFTP_PREVIEW, SFTP_DIRECTORY_PICKER, SFTP_BROWSER,
        PTY, PTY_STREAM, PTY_WRITE, SESSION, RECONNECT, LIFECYCLE, NETWORK, BACKGROUND_SERVICE
    }
    enum Outcome {
        STARTED, READY, FAILED, APPROVAL_REQUIRED, HOST_CHANGED, TIMEOUT, STCP_OPEN_FAILED,
        AUTH_REJECTED, NEGOTIATION_FAILED, PATH_UNAVAILABLE, DISCONNECTED, TRANSPORT_LOST,
        BACKGROUNDED, FOREGROUNDED, RETRYING, CREATED, DESTROYED, RECREATED,
        AVAILABLE, LOST, VALIDATED, UNVALIDATED, WIFI, CELLULAR, ETHERNET, VPN, OTHER_NETWORK,
        BLOCKED, UNBLOCKED, EOF, EXITED, IO_ERROR, REQUESTED, CANCELLED, UNSUPPORTED, DISCARDED
    }

    private static final String TAG = "LocusDiag";
    private static final int MAX_PENDING_EVENTS = 128;
    private static DiagnosticLog instance;

    private final Context app;
    private final long appVersionCode;
    private final String appVersionName;
    private final UUID processId;
    private final ThreadPoolExecutor writer;
    private DiagnosticJournal journal;
    private boolean storageFailureReported;

    static synchronized DiagnosticLog get(Context context) {
        if (instance == null) instance = new DiagnosticLog(context.getApplicationContext());
        return instance;
    }

    private DiagnosticLog(Context app) {
        this.app = app;
        boolean debuggable = (app.getApplicationInfo().flags & ApplicationInfo.FLAG_DEBUGGABLE) != 0;
        PackageInfo version = debuggable ? installedVersion(app) : null;
        appVersionCode = version == null ? 0 : version.getLongVersionCode();
        appVersionName = version == null || version.versionName == null
                ? "null" : JSONObject.quote(version.versionName);
        processId = debuggable ? UUID.randomUUID() : null;
        writer = debuggable ? new ThreadPoolExecutor(1, 1, 0, TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(MAX_PENDING_EVENTS),
                task -> new Thread(task, "locus-diagnostics"), new ThreadPoolExecutor.DiscardOldestPolicy()) : null;
    }

    private static PackageInfo installedVersion(Context app) {
        try {
            return app.getPackageManager().getPackageInfo(app.getPackageName(), 0);
        } catch (PackageManager.NameNotFoundException unavailable) {
            return null;
        }
    }

    boolean isEnabled() {
        return writer != null;
    }

    Trace begin(Route route, Auth auth) {
        if (!isEnabled() || route == null || auth == null) return Trace.DISABLED;
        return new Trace(this, route, auth, UUID.randomUUID(), SystemClock.elapsedRealtime());
    }

    static Outcome sftpOutcome(RemoteConnection connection, Exception failure) {
        if (!connection.isOpen()) return Outcome.TRANSPORT_LOST;
        if (failure instanceof OperationException) {
            switch (((OperationException) failure).reason) {
                case SFTP_DISCONNECTED:
                    return Outcome.TRANSPORT_LOST;
                case REMOTE_NOT_DIRECTORY:
                case REMOTE_NOT_FILE:
                case REMOTE_PATH_INVALID:
                case SFTP_NOT_FOUND:
                    return Outcome.PATH_UNAVAILABLE;
                case SFTP_UNSUPPORTED:
                    return Outcome.UNSUPPORTED;
                default:
                    break;
            }
        }
        return Outcome.FAILED;
    }

    void record(Stage stage, Outcome outcome) {
        record(null, stage, outcome, null);
    }

    void record(Stage stage, Outcome outcome, long windowId) {
        record(null, stage, outcome, windowId);
    }

    private void record(Trace trace, Stage stage, Outcome outcome, Long windowId) {
        if (!isEnabled() || stage == null || outcome == null) return;
        long bootElapsed = SystemClock.elapsedRealtime();
        boolean warning = isFailure(outcome);
        StringBuilder event = new StringBuilder(384)
                .append("{\"schema\":1,\"timestamp_ms\":").append(System.currentTimeMillis())
                .append(",\"boot_elapsed_ms\":").append(bootElapsed)
                .append(",\"app_version_code\":").append(appVersionCode)
                .append(",\"app_version_name\":").append(appVersionName)
                .append(",\"process_id\":\"").append(processId)
                .append("\",\"level\":\"").append(warning ? "WARN" : "INFO").append('"');
        if (trace != null) {
            event.append(",\"elapsed_ms\":").append(bootElapsed - trace.started)
                    .append(",\"attempt_id\":\"").append(trace.attemptId)
                    .append("\",\"route\":\"").append(trace.route.name())
                    .append("\",\"auth\":\"").append(trace.auth.name()).append('"');
        }
        if (windowId != null) event.append(",\"window_id\":").append(windowId);
        String line = event.append(",\"stage\":\"").append(stage.name())
                .append("\",\"outcome\":\"").append(outcome.name()).append("\"}").toString();

        writer.execute(() -> persist(line));
        if (warning) Log.w(TAG, line);
        else Log.i(TAG, line);
    }

    private void persist(String line) {
        try {
            if (journal == null) {
                File privateStorage = app.getNoBackupFilesDir();
                if (privateStorage == null) throw new IOException();
                journal = new DiagnosticJournal(new File(privateStorage, "diagnostics"));
            }
            journal.append(line);
            storageFailureReported = false;
        } catch (IOException | SecurityException unavailable) {
            journal = null;
            if (!storageFailureReported) Log.w(TAG, "Diagnostic journal unavailable");
            storageFailureReported = true;
        }
    }

    private static boolean isFailure(Outcome outcome) {
        switch (outcome) {
            case FAILED:
            case HOST_CHANGED:
            case TIMEOUT:
            case STCP_OPEN_FAILED:
            case AUTH_REJECTED:
            case NEGOTIATION_FAILED:
            case PATH_UNAVAILABLE:
            case TRANSPORT_LOST:
            case LOST:
            case IO_ERROR:
                return true;
            default:
                return false;
        }
    }

    static final class Trace {
        private static final Trace DISABLED = new Trace(null, null, null, null, 0);

        private final DiagnosticLog log;
        private final Route route;
        private final Auth auth;
        private final UUID attemptId;
        private final long started;

        private Trace(DiagnosticLog log, Route route, Auth auth, UUID attemptId, long started) {
            this.log = log;
            this.route = route;
            this.auth = auth;
            this.attemptId = attemptId;
            this.started = started;
        }

        void record(Stage stage, Outcome outcome) {
            if (log != null) log.record(this, stage, outcome, null);
        }

        void record(Stage stage, Outcome outcome, long windowId) {
            if (log != null) log.record(this, stage, outcome, windowId);
        }
    }
}
