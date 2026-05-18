package com.carlosjuarez.cariana;

import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Intent;
import android.os.Build;
import android.text.TextUtils;
import android.util.Log;

import androidx.core.app.NotificationCompat;

import com.google.firebase.messaging.FirebaseMessagingService;
import com.google.firebase.messaging.RemoteMessage;

public class CarianaFirebaseMessagingService extends FirebaseMessagingService {
    private static final String TAG = "CarianaFCM";
    private static final String CHANNEL_ID = "cariana_general";
    private static final String CHANNEL_NAME = "General";

    @Override
    public void onNewToken(String token) {
        super.onNewToken(token);
        Log.d(TAG, "FCM token refreshed: " + token);
    }

    @Override
    public void onMessageReceived(RemoteMessage remoteMessage) {
        super.onMessageReceived(remoteMessage);

        String title = "CARIANA";
        String body = "";
        if (remoteMessage.getNotification() != null) {
            if (!TextUtils.isEmpty(remoteMessage.getNotification().getTitle())) {
                title = remoteMessage.getNotification().getTitle();
            }
            if (!TextUtils.isEmpty(remoteMessage.getNotification().getBody())) {
                body = remoteMessage.getNotification().getBody();
            }
        }

        if (TextUtils.isEmpty(body)) {
            body = remoteMessage.getData().get("message");
        }
        if (TextUtils.isEmpty(body)) {
            body = "Tienes una nueva notificacion.";
        }

        String targetUrl = remoteMessage.getData().get("url");
        if (TextUtils.isEmpty(targetUrl)) {
            targetUrl = remoteMessage.getData().get("link");
        }
        if (TextUtils.isEmpty(targetUrl)) {
            targetUrl = "https://cariana.com.mx/";
        }

        Intent openIntent = new Intent(this, MainActivity.class);
        openIntent.putExtra(MainActivity.EXTRA_TARGET_URL, targetUrl);
        openIntent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);

        PendingIntent pendingIntent = PendingIntent.getActivity(
            this,
            1001,
            openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
        );

        NotificationManager manager = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (manager == null) {
            return;
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                CHANNEL_ID,
                CHANNEL_NAME,
                NotificationManager.IMPORTANCE_DEFAULT
            );
            manager.createNotificationChannel(channel);
        }

        NotificationCompat.Builder builder = new NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_notification)
            .setContentTitle(title)
            .setContentText(body)
            .setAutoCancel(true)
            .setContentIntent(pendingIntent)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT);

        manager.notify((int) System.currentTimeMillis(), builder.build());
    }
}
