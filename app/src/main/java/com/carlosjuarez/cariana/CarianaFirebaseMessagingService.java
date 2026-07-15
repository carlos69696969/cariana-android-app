package com.carlosjuarez.cariana;

import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.media.AudioAttributes;
import android.net.Uri;
import android.os.Build;
import android.text.TextUtils;
import android.util.Log;

import androidx.core.app.NotificationCompat;

import com.google.firebase.messaging.FirebaseMessagingService;
import com.google.firebase.messaging.RemoteMessage;
import java.util.Map;

public class CarianaFirebaseMessagingService extends FirebaseMessagingService {
    private static final String TAG = "CarianaFCM";
    private static final String CHANNEL_GENERAL = "cariana_general_v2";
    private static final String CHANNEL_ORDERS = "cariana_orders_v2";
    private static final String CHANNEL_SHIPPING = "cariana_shipping_v2";
    private static final String CHANNEL_RETURNS = "cariana_returns_v2";
    private static final String CHANNEL_PROMOS = "cariana_promos_v2";
    private static final String PREFS_PUSH_STORE = "cariana_push_notifications";
    private static final String PREFS_KEY_URL_PREFIX = "push_url_";

    @Override
    public void onNewToken(String token) {
        super.onNewToken(token);
        Log.d(TAG, "FCM token refreshed: " + token);
        PushSyncManager.syncToken(this, token);
    }

    @Override
    public void onMessageReceived(RemoteMessage remoteMessage) {
        super.onMessageReceived(remoteMessage);

        Map<String, String> data = remoteMessage.getData();
        String type = normalizeType(firstNonEmpty(
            valueFromMap(data, "type"),
            valueFromMap(data, "status"),
            valueFromMap(data, "deepLinkType")
        ));
        String title = resolveNotificationTitle(remoteMessage, data, type);
        String body = "";
        if (remoteMessage.getNotification() != null) {
            if (!TextUtils.isEmpty(remoteMessage.getNotification().getBody())) {
                body = remoteMessage.getNotification().getBody();
            }
        }

        if (TextUtils.isEmpty(body)) {
            body = firstNonEmpty(
                valueFromMap(data, "message"),
                valueFromMap(data, "body")
            );
        }
        if (TextUtils.isEmpty(body)) {
            body = defaultBodyForType(type);
        }

        String targetUrl = valueFromMap(data, "deepLink");
        if (TextUtils.isEmpty(targetUrl)) {
            targetUrl = valueFromMap(data, "url");
        }
        if (TextUtils.isEmpty(targetUrl)) {
            targetUrl = valueFromMap(data, "link");
        }
        if (TextUtils.isEmpty(targetUrl) && remoteMessage.getNotification() != null) {
            targetUrl = remoteMessage.getNotification().getClickAction();
        }
        if (TextUtils.isEmpty(targetUrl)) {
            targetUrl = defaultUrlForType(type);
        }

        Intent openIntent = new Intent(this, MainActivity.class);
        openIntent.setAction(Intent.ACTION_VIEW);
        openIntent.putExtra(MainActivity.EXTRA_TARGET_URL, targetUrl);
        if (!TextUtils.isEmpty(targetUrl)) {
            openIntent.setData(Uri.parse(targetUrl));
        }
        openIntent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);

        int requestCode = (int) (System.currentTimeMillis() & 0xfffffff);
        PendingIntent pendingIntent = PendingIntent.getActivity(
            this,
            requestCode,
            openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
        );

        NotificationManager manager = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (manager == null) {
            return;
        }

        ensureNotificationChannels(this);

        String channelId = channelForType(type);
        Uri soundUri = notificationSoundUri();
        NotificationCompat.Builder builder = new NotificationCompat.Builder(this, channelId)
            .setSmallIcon(R.drawable.ic_stat_notification)
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(new NotificationCompat.BigTextStyle().bigText(body))
            .setAutoCancel(true)
            .setContentIntent(pendingIntent)
            .setSound(soundUri)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT);

        int notificationId = (int) (System.currentTimeMillis() & 0x7fffffff);
        manager.notify(notificationId, builder.build());
        persistNotificationTarget(notificationId, targetUrl);
    }

    private String resolveNotificationTitle(RemoteMessage remoteMessage, Map<String, String> data, String type) {
        String fromData = valueFromMap(data, "title");
        if (!TextUtils.isEmpty(fromData)) {
            return fromData;
        }

        if (remoteMessage.getNotification() != null && !TextUtils.isEmpty(remoteMessage.getNotification().getTitle())) {
            String fromNotification = remoteMessage.getNotification().getTitle();
            if (!TextUtils.isEmpty(fromNotification) && !"CARIANA".equalsIgnoreCase(fromNotification.trim())) {
                return fromNotification;
            }
        }

        String status = normalizeType(valueFromMap(data, "status"));
        String statusTitle = titleForStatus(status, type);
        if (!TextUtils.isEmpty(statusTitle)) {
            return statusTitle;
        }

        return "CARIANA";
    }

    private String titleForStatus(String status, String type) {
        switch (status) {
            case "return_requested":
            case "in_review":
                return "Devolucion en revision";
            case "return_approved":
                return "Devolucion aprobada";
            case "return_rejected":
                return "Devolucion rechazada";
            case "return_pickup_scheduled":
                return "Intento de recoleccion fallido";
            case "return_picked_up":
                return "Producto recogido";
            case "refund_processed":
                return "Reembolso procesado";
            case "refund_completed":
                return "Reembolso completado";
            case "order_confirmed":
                return "Pedido confirmado";
            case "order_preparing":
                return "Pedido en preparacion";
            case "order_shipped":
                return "Pedido enviado";
            case "order_in_transit":
                return "Pedido en transito";
            case "order_delivered":
                return "Pedido entregado";
            case "order_cancelled":
                return "Pedido cancelado";
            default:
                break;
        }

        if ("return".equals(type) || "returns".equals(type)) {
            return "Actualizacion de devolucion";
        }
        return "";
    }

    private String valueFromMap(Map<String, String> map, String key) {
        if (map == null || TextUtils.isEmpty(key)) {
            return "";
        }
        String value = map.get(key);
        return value == null ? "" : value.trim();
    }

    private String firstNonEmpty(String... values) {
        if (values == null) {
            return "";
        }
        for (String value : values) {
            if (!TextUtils.isEmpty(value)) {
                return value.trim();
            }
        }
        return "";
    }

    public static void ensureNotificationChannels(Context context) {
        if (context == null || Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            return;
        }
        NotificationManager manager = (NotificationManager) context.getSystemService(NOTIFICATION_SERVICE);
        if (manager == null) {
            return;
        }
        Uri soundUri = notificationSoundUri(context);
        AudioAttributes audioAttributes = new AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_NOTIFICATION)
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
            .build();

        NotificationChannel general = new NotificationChannel(
            CHANNEL_GENERAL,
            "General",
            NotificationManager.IMPORTANCE_DEFAULT
        );
        general.setDescription("Notificaciones generales");
        general.setSound(soundUri, audioAttributes);

        NotificationChannel orders = new NotificationChannel(
            CHANNEL_ORDERS,
            "Pedidos",
            NotificationManager.IMPORTANCE_HIGH
        );
        orders.setDescription("Actualizaciones de pedido");
        orders.setSound(soundUri, audioAttributes);

        NotificationChannel shipping = new NotificationChannel(
            CHANNEL_SHIPPING,
            "Envios",
            NotificationManager.IMPORTANCE_HIGH
        );
        shipping.setDescription("Seguimiento de envio");
        shipping.setSound(soundUri, audioAttributes);

        NotificationChannel returns = new NotificationChannel(
            CHANNEL_RETURNS,
            "Devoluciones",
            NotificationManager.IMPORTANCE_HIGH
        );
        returns.setDescription("Estado de devoluciones y reembolsos");
        returns.setSound(soundUri, audioAttributes);

        NotificationChannel promos = new NotificationChannel(
            CHANNEL_PROMOS,
            "Promociones",
            NotificationManager.IMPORTANCE_DEFAULT
        );
        promos.setDescription("Promociones y novedades");
        promos.setSound(soundUri, audioAttributes);

        manager.createNotificationChannel(general);
        manager.createNotificationChannel(orders);
        manager.createNotificationChannel(shipping);
        manager.createNotificationChannel(returns);
        manager.createNotificationChannel(promos);
    }

    private static Uri notificationSoundUri(Context context) {
        return Uri.parse("android.resource://" + context.getPackageName() + "/" + R.raw.cariana_notification_sound);
    }

    private Uri notificationSoundUri() {
        return notificationSoundUri(this);
    }

    private String normalizeType(String rawType) {
        if (TextUtils.isEmpty(rawType)) {
            return "general";
        }
        return rawType.trim().toLowerCase();
    }

    private String channelForType(String type) {
        switch (type) {
            case "order_created":
            case "order_paid":
            case "order_preparing":
            case "order_ready":
                return CHANNEL_ORDERS;
            case "order_shipped":
            case "order_out_for_delivery":
            case "order_delivered":
                return CHANNEL_SHIPPING;
            case "return_requested":
            case "return_received":
            case "return_approved":
            case "refund_issued":
            case "return_rejected":
                return CHANNEL_RETURNS;
            case "promo":
            case "promotion":
                return CHANNEL_PROMOS;
            default:
                return CHANNEL_GENERAL;
        }
    }

    private String defaultBodyForType(String type) {
        switch (type) {
            case "order_preparing":
                return "Tu pedido esta siendo preparado.";
            case "order_shipped":
                return "Tu pedido ya va en camino.";
            case "order_delivered":
                return "Tu pedido fue entregado.";
            case "return_requested":
                return "Solicitaste tu devolucion con exito.";
            case "return_approved":
                return "Tu devolucion fue aprobada.";
            case "refund_issued":
                return "Tu reembolso fue emitido.";
            default:
                return "Tienes una nueva notificacion.";
        }
    }

    private String defaultUrlForType(String type) {
        switch (type) {
            case "order_created":
            case "order_paid":
            case "order_preparing":
            case "order_ready":
            case "order_shipped":
            case "order_out_for_delivery":
            case "order_delivered":
                return "https://cariana.mx/account/orders";
            case "return_requested":
            case "return_received":
            case "return_approved":
            case "refund_issued":
            case "return_rejected":
                return "https://cariana.mx/pages/devoluciones";
            default:
                return "https://cariana.mx/";
        }
    }

    private void persistNotificationTarget(int notificationId, String targetUrl) {
        if (notificationId <= 0 || TextUtils.isEmpty(targetUrl)) {
            return;
        }
        SharedPreferences preferences = getSharedPreferences(PREFS_PUSH_STORE, MODE_PRIVATE);
        preferences
            .edit()
            .putString(PREFS_KEY_URL_PREFIX + notificationId, normalizeNotificationUrl(targetUrl))
            .apply();
    }

    private String normalizeNotificationUrl(String value) {
        if (TextUtils.isEmpty(value)) {
            return "";
        }
        String normalized = value.trim();
        while (normalized.endsWith("/")) {
            normalized = normalized.substring(0, normalized.length() - 1);
        }
        return normalized;
    }
}





