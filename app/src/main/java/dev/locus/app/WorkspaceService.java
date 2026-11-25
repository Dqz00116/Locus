package dev.locus.app;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.content.res.Configuration;
import android.content.res.Resources;
import android.graphics.drawable.Icon;
import android.net.Uri;
import android.os.Build;
import android.os.IBinder;
import android.util.Log;
import android.widget.Toast;

import dev.locus.app.DiagnosticLog.Outcome;
import dev.locus.app.DiagnosticLog.Stage;

/** Keeps the existing, user-opened workspace running while its UI is hidden. */
public final class WorkspaceService extends Service {
    private static final String TAG = "WorkspaceService";
    private static final String CHANNEL = "remote_workspace";
    private static final int NOTIFICATION_ID = 1;
    static final String ACTION_NOTIFICATION_EXIT = "dev.locus.app.action.NOTIFICATION_EXIT";

    static Uri notificationExitUri(String token) {
        return Uri.fromParts("locus-workspace", token, null);
    }

    static boolean start(Context context) {
        LocusApplication app = (LocusApplication) context.getApplicationContext();
        if (app.foregroundWorkspaceRequested) return true;
        app.foregroundWorkspaceRequested = true;
        try {
            if (context.startForegroundService(new Intent(context, WorkspaceService.class)) != null) return true;
        } catch (IllegalStateException | SecurityException failure) {
            Log.w(TAG, "Could not start the workspace service", failure);
        }
        app.foregroundWorkspaceRequested = false;
        DiagnosticLog.get(context).record(Stage.BACKGROUND_SERVICE, Outcome.FAILED);
        return false;
    }

    static void stop(Context context) {
        LocusApplication app = (LocusApplication) context.getApplicationContext();
        app.foregroundWorkspaceRequested = false;
        WorkspaceService active = app.foregroundWorkspace;
        if (active == null) return;
        active.stopForeground(STOP_FOREGROUND_REMOVE);
        app.foregroundWorkspace = null;
        context.stopService(new Intent(context, WorkspaceService.class));
        DiagnosticLog.get(context).record(Stage.BACKGROUND_SERVICE, Outcome.DISCONNECTED);
    }

    static void refreshNotification(Context context) {
        if (!isForeground(context) || !notificationsEnabled(context)) return;
        NotificationManager notifications = context.getSystemService(NotificationManager.class);
        notifications.notify(NOTIFICATION_ID, notification(context, notifications));
    }

    static boolean isForeground(Context context) {
        return ((LocusApplication) context.getApplicationContext()).foregroundWorkspace != null;
    }

    static boolean notificationsEnabled(Context context) {
        NotificationManager notifications = context.getSystemService(NotificationManager.class);
        NotificationChannel channel = notifications.getNotificationChannel(CHANNEL);
        return notifications.areNotificationsEnabled()
                && (channel == null || channel.getImportance() != NotificationManager.IMPORTANCE_NONE);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        LocusApplication app = (LocusApplication) getApplication();
        try {
            Notification notification = notification(this, getSystemService(NotificationManager.class));
            if (Build.VERSION.SDK_INT >= 34) {
                startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
            } else {
                startForeground(NOTIFICATION_ID, notification);
            }
            app.foregroundWorkspace = this;
            DiagnosticLog.get(this).record(Stage.BACKGROUND_SERVICE, Outcome.READY);
        } catch (IllegalStateException | SecurityException failure) {
            Log.w(TAG, "Could not promote the workspace service", failure);
            DiagnosticLog.get(this).record(Stage.BACKGROUND_SERVICE, Outcome.FAILED);
            if (app.foregroundWorkspaceRequested) {
                Toast.makeText(this, localizedResources(this).getText(R.string.workspace_background_unavailable),
                        Toast.LENGTH_SHORT).show();
            }
            stop(this);
            stopSelf(startId);
            return START_NOT_STICKY;
        }
        // A failed connection can request a stop before Android delivers the start callback.
        // Still promote first to satisfy the foreground service deadline.
        if (!app.foregroundWorkspaceRequested) stop(this);
        return START_NOT_STICKY;
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onConfigurationChanged(Configuration configuration) {
        super.onConfigurationChanged(configuration);
        refreshNotification(this);
    }

    @Override
    public void onDestroy() {
        LocusApplication app = (LocusApplication) getApplication();
        if (app.foregroundWorkspace == this) {
            app.foregroundWorkspace = null;
            app.foregroundWorkspaceRequested = false;
        }
        super.onDestroy();
    }

    private static Notification notification(Context context, NotificationManager notifications) {
        Resources resources = localizedResources(context);
        notifications.createNotificationChannel(new NotificationChannel(CHANNEL,
                resources.getText(R.string.workspace_background_notification_channel),
                NotificationManager.IMPORTANCE_LOW));
        Intent reopen = Intent.makeMainActivity(new ComponentName(context, MainActivity.class));
        reopen.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP
                | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        PendingIntent openWorkspace = PendingIntent.getActivity(context, 0, reopen, PendingIntent.FLAG_IMMUTABLE);
        Notification.Builder builder = new Notification.Builder(context, CHANNEL)
                .setSmallIcon(R.drawable.ic_launcher_monochrome)
                .setContentTitle(resources.getText(R.string.app_name))
                .setContentText(resources.getText(R.string.workspace_background_notification))
                .setContentIntent(openWorkspace)
                .addAction(new Notification.Action.Builder(Icon.createWithResource(context, R.drawable.ic_back),
                        resources.getText(R.string.workspace_notification_return), openWorkspace).build())
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setShowWhen(false);
        LocusApplication app = (LocusApplication) context.getApplicationContext();
        String token = app.workspace().notificationExitToken();
        if (token != null) {
            Intent exit = new Intent(reopen).setAction(ACTION_NOTIFICATION_EXIT).setData(notificationExitUri(token));
            PendingIntent exitWorkspace = PendingIntent.getActivity(context, 1, exit, PendingIntent.FLAG_IMMUTABLE);
            builder.addAction(new Notification.Action.Builder(Icon.createWithResource(context, R.drawable.ic_disconnect),
                    resources.getText(R.string.workspace_notification_exit), exitWorkspace).build());
        }
        if (Build.VERSION.SDK_INT >= 31) {
            builder.setForegroundServiceBehavior(Notification.FOREGROUND_SERVICE_IMMEDIATE);
        }
        return builder.build();
    }

    private static Resources localizedResources(Context context) {
        Resources legacy = Build.VERSION.SDK_INT < 33 ? AppLanguage.legacyResources(context) : null;
        return legacy == null ? context.getResources() : legacy;
    }
}
