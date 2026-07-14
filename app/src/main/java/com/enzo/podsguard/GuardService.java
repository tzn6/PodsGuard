package com.enzo.podsguard;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;

public class GuardService extends Service {
    private static final String CHANNEL_ID = "podsguard_protection";
    private static final int NOTIFICATION_ID = 7112;
    private static final long TICK_MS = 140L;

    private final Handler handler = new Handler(Looper.getMainLooper());

    private final Runnable watchdog = new Runnable() {
        @Override
        public void run() {
            if (!Prefs.get(GuardService.this).getBoolean(Prefs.ENABLED, false)) {
                stopSelf();
                return;
            }

            PodsGuardListener.watchdogTick(GuardService.this);
            handler.postDelayed(this, TICK_MS);
        }
    };

    public static void start(Context context) {
        Intent intent = new Intent(context, GuardService.class);
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent);
            } else {
                context.startService(intent);
            }
        } catch (Exception ignored) {
            // Some OEMs temporarily block a foreground-service start while the app is backgrounded.
            // Opening PodsGuard again will retry the start.
        }
    }

    public static void stop(Context context) {
        try {
            context.stopService(new Intent(context, GuardService.class));
        } catch (Exception ignored) {
        }
    }

    @Override
    public void onCreate() {
        super.onCreate();
        createNotificationChannel();
        startForeground(NOTIFICATION_ID, buildNotification());
        handler.removeCallbacks(watchdog);
        handler.post(watchdog);
        PodsGuardListener.requestRefresh(this);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        startForeground(NOTIFICATION_ID, buildNotification());
        handler.removeCallbacks(watchdog);
        handler.post(watchdog);
        PodsGuardListener.requestRefresh(this);
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        handler.removeCallbacksAndMessages(null);
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return;

        NotificationChannel channel = new NotificationChannel(
                CHANNEL_ID,
                "Защита PodsGuard",
                NotificationManager.IMPORTANCE_LOW
        );
        channel.setDescription("Поддерживает защиту от паузы AirPods в фоне");
        channel.setShowBadge(false);

        NotificationManager manager = getSystemService(NotificationManager.class);
        if (manager != null) manager.createNotificationChannel(channel);
    }

    private Notification buildNotification() {
        Intent openIntent = new Intent(this, MainActivity.class);
        openIntent.setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_CLEAR_TOP);

        int pendingFlags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            pendingFlags |= PendingIntent.FLAG_IMMUTABLE;
        }

        PendingIntent pendingIntent = PendingIntent.getActivity(
                this, 0, openIntent, pendingFlags);

        Notification.Builder builder = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                ? new Notification.Builder(this, CHANNEL_ID)
                : new Notification.Builder(this);

        return builder
                .setSmallIcon(R.drawable.ic_app_icon)
                .setContentTitle("PodsGuard включён")
                .setContentText("Отслеживаю паузы AirPods и сразу возвращаю музыку")
                .setContentIntent(pendingIntent)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setCategory(Notification.CATEGORY_SERVICE)
                .build();
    }
}
