package dev.locus.app;

import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.os.SystemClock;
import android.util.Log;

import java.util.UUID;

/** Structured connection events for debuggable builds. */
final class DiagnosticLog {
    enum Route { DIRECT, FRP_STCP }
    enum Auth { PASSWORD, KEY }
    enum Stage { PREPARE, FRP_RELAY, STCP, SSH_HANDSHAKE, HOST_KEY, SSH_AUTH, SFTP, PTY, SESSION }
    enum Outcome {
        STARTED, READY, FAILED, APPROVAL_REQUIRED, HOST_CHANGED, TIMEOUT, STCP_OPEN_FAILED,
        AUTH_REJECTED, NEGOTIATION_FAILED, PATH_UNAVAILABLE, DISCONNECTED
    }

    private static final String TAG = "LocusDiag";
    private static DiagnosticLog instance;

    private final boolean debuggable;
    private final long appVersion;

    static synchronized DiagnosticLog get(Context context) {
        if (instance == null) instance = new DiagnosticLog(context.getApplicationContext());
        return instance;
    }

    private DiagnosticLog(Context app) {
        debuggable = (app.getApplicationInfo().flags & ApplicationInfo.FLAG_DEBUGGABLE) != 0;
        appVersion = debuggable ? installedVersion(app) : 0;
    }

    private static long installedVersion(Context app) {
        try {
            return app.getPackageManager().getPackageInfo(app.getPackageName(), 0).getLongVersionCode();
        } catch (PackageManager.NameNotFoundException unavailable) {
            return 0;
        }
    }

    Trace begin(Route route, Auth auth) {
        if (!debuggable || route == null || auth == null) return Trace.DISABLED;
        return new Trace(appVersion, route, auth, UUID.randomUUID(), SystemClock.elapsedRealtime());
    }

    static final class Trace {
        private static final Trace DISABLED = new Trace(0, null, null, null, 0);

        private final long appVersion;
        private final Route route;
        private final Auth auth;
        private final UUID attemptId;
        private final long started;

        private Trace(long appVersion, Route route, Auth auth, UUID attemptId, long started) {
            this.appVersion = appVersion;
            this.route = route;
            this.auth = auth;
            this.attemptId = attemptId;
            this.started = started;
        }

        void record(Stage stage, Outcome outcome) {
            if (attemptId == null || stage == null || outcome == null) return;
            boolean warning = isFailure(outcome);
            long elapsed = SystemClock.elapsedRealtime() - started;
            String event = "{\"schema\":1,\"timestamp_ms\":" + System.currentTimeMillis()
                    + ",\"elapsed_ms\":" + elapsed + ",\"app_version_code\":" + appVersion
                    + ",\"level\":\"" + (warning ? "WARN" : "INFO") + "\",\"attempt_id\":\"" + attemptId
                    + "\",\"route\":\"" + route.name() + "\",\"auth\":\"" + auth.name()
                    + "\",\"stage\":\"" + stage.name() + "\",\"outcome\":\"" + outcome.name() + "\"}";
            if (warning) Log.w(TAG, event);
            else Log.i(TAG, event);
        }

        private static boolean isFailure(Outcome outcome) {
            switch (outcome) {
                case STARTED:
                case READY:
                case APPROVAL_REQUIRED:
                case DISCONNECTED:
                    return false;
                default:
                    return true;
            }
        }
    }
}
