package com.carlosjuarez.cariana;

import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.os.Build;
import android.provider.Settings;
import android.text.TextUtils;
import android.util.Log;

import com.google.firebase.messaging.FirebaseMessaging;

import org.json.JSONObject;

import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class PushSyncManager {
    private static final String TAG = "PushSyncManager";
    private static final String PREFS = "cariana_push_sync";
    private static final String KEY_TOKEN = "token";
    private static final String KEY_USER_ID = "user_id";
    private static final String KEY_USER_EMAIL = "user_email";
    private static final String KEY_SHOP_DOMAIN = "shop_domain";
    private static final String KEY_LAST_SYNC = "last_sync_signature";
    private static final String META_ENDPOINT = "com.carlosjuarez.cariana.PUSH_TOKEN_ENDPOINT";
    private static final String META_API_KEY = "com.carlosjuarez.cariana.PUSH_TOKEN_API_KEY";
    private static final String META_SHOP_DOMAIN = "com.carlosjuarez.cariana.SHOP_DOMAIN";
    private static final ExecutorService EXECUTOR = Executors.newSingleThreadExecutor();

    private PushSyncManager() {}

    public static void setUser(Context context, String userId, String email) {
        setUser(context, userId, email, "");
    }

    public static void setUser(Context context, String userId, String email, String shopDomain) {
        Context appContext = context.getApplicationContext();
        SharedPreferences prefs = appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        String normalizedShop = normalizeShopDomain(shopDomain);
        SharedPreferences.Editor editor = prefs.edit()
            .putString(KEY_USER_ID, clean(userId))
            .putString(KEY_USER_EMAIL, clean(email));

        if (!TextUtils.isEmpty(normalizedShop)) {
            editor.putString(KEY_SHOP_DOMAIN, normalizedShop);
        }

        editor.apply();
        syncCurrentToken(appContext);
    }

    public static void setShopDomain(Context context, String shopDomain) {
        Context appContext = context.getApplicationContext();
        String normalizedShop = normalizeShopDomain(shopDomain);
        if (TextUtils.isEmpty(normalizedShop)) {
            return;
        }

        SharedPreferences prefs = appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        prefs.edit().putString(KEY_SHOP_DOMAIN, normalizedShop).apply();
        syncCurrentToken(appContext);
    }

    public static void clearUser(Context context) {
        Context appContext = context.getApplicationContext();
        SharedPreferences prefs = appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        prefs.edit()
            .remove(KEY_USER_ID)
            .remove(KEY_USER_EMAIL)
            .remove(KEY_LAST_SYNC)
            .apply();
    }

    public static void syncCurrentToken(Context context) {
        try {
            FirebaseMessaging.getInstance().getToken().addOnCompleteListener(task -> {
                if (!task.isSuccessful()) {
                    Log.w(TAG, "No se pudo obtener token para sincronizacion", task.getException());
                    return;
                }
                syncToken(context, task.getResult());
            });
        } catch (Exception e) {
            Log.w(TAG, "Firebase no disponible para sync de token", e);
        }
    }

    public static void syncToken(Context context, String token) {
        Context appContext = context.getApplicationContext();
        String normalizedToken = clean(token);
        if (TextUtils.isEmpty(normalizedToken)) {
            return;
        }

        SharedPreferences prefs = appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        prefs.edit().putString(KEY_TOKEN, normalizedToken).apply();

        String endpoint = getEndpoint(appContext);
        if (TextUtils.isEmpty(endpoint)) {
            Log.d(TAG, "PUSH_TOKEN_ENDPOINT vacio. Se omite sync remoto.");
            return;
        }

        String shopDomain = getResolvedShopDomain(appContext, prefs);
        if (TextUtils.isEmpty(shopDomain)) {
            Log.d(TAG, "SHOP_DOMAIN vacio. Se omite sync remoto.");
            return;
        }

        String userId = prefs.getString(KEY_USER_ID, "");
        String userEmail = prefs.getString(KEY_USER_EMAIL, "");
        Long shopifyCustomerId = parseLongOrNull(userId);

        if (shopifyCustomerId == null && TextUtils.isEmpty(userEmail)) {
            Log.d(TAG, "Sin identidad de cliente (shopifyCustomerId/email). Se omite sync remoto.");
            return;
        }

        String syncSignature = normalizedToken + "|" + shopDomain + "|" + clean(userId) + "|" + clean(userEmail);
        String lastSync = prefs.getString(KEY_LAST_SYNC, "");
        if (syncSignature.equals(lastSync)) {
            return;
        }

        String apiKey = getApiKey(appContext);
        EXECUTOR.execute(() -> postToken(
            appContext,
            endpoint,
            apiKey,
            shopDomain,
            normalizedToken,
            shopifyCustomerId,
            userEmail,
            syncSignature
        ));
    }

    private static void postToken(
        Context context,
        String endpoint,
        String apiKey,
        String shopDomain,
        String token,
        Long shopifyCustomerId,
        String userEmail,
        String syncSignature
    ) {
        HttpURLConnection connection = null;
        try {
            JSONObject payload = new JSONObject();
            payload.put("shopDomain", shopDomain);
            payload.put("token", token);
            if (shopifyCustomerId != null) {
                payload.put("shopifyCustomerId", shopifyCustomerId);
            }
            if (!TextUtils.isEmpty(userEmail)) {
                payload.put("email", userEmail);
            }
            payload.put("platform", "android");
            payload.put("appVersion", getAppVersion(context));
            payload.put("androidVersion", Build.VERSION.RELEASE);
            payload.put("deviceId", Settings.Secure.getString(
                context.getContentResolver(),
                Settings.Secure.ANDROID_ID
            ));
            payload.put("timestampMs", System.currentTimeMillis());

            URL url = new URL(endpoint);
            connection = (HttpURLConnection) url.openConnection();
            connection.setRequestMethod("POST");
            connection.setConnectTimeout(20000);
            connection.setReadTimeout(20000);
            connection.setDoOutput(true);
            connection.setRequestProperty("Content-Type", "application/json; charset=UTF-8");
            if (!TextUtils.isEmpty(apiKey)) {
                connection.setRequestProperty("x-api-key", apiKey);
            }

            byte[] bytes = payload.toString().getBytes(StandardCharsets.UTF_8);
            try (OutputStream os = connection.getOutputStream()) {
                os.write(bytes);
            }

            int code = connection.getResponseCode();
            if (code >= 200 && code < 300) {
                SharedPreferences prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
                prefs.edit().putString(KEY_LAST_SYNC, syncSignature).apply();
                Log.d(TAG, "Token FCM sincronizado con backend.");
            } else {
                Log.w(TAG, "Sync token respondio codigo " + code);
            }
        } catch (Exception e) {
            Log.w(TAG, "Error al sincronizar token FCM", e);
        } finally {
            if (connection != null) {
                connection.disconnect();
            }
        }
    }

    private static String getResolvedShopDomain(Context context, SharedPreferences prefs) {
        String fromPrefs = normalizeShopDomain(prefs.getString(KEY_SHOP_DOMAIN, ""));
        if (!TextUtils.isEmpty(fromPrefs)) {
            return fromPrefs;
        }

        String fromMeta = normalizeShopDomain(getMetaValue(context, META_SHOP_DOMAIN));
        if (!TextUtils.isEmpty(fromMeta)) {
            prefs.edit().putString(KEY_SHOP_DOMAIN, fromMeta).apply();
            return fromMeta;
        }

        return "";
    }

    private static String getApiKey(Context context) {
        return clean(getMetaValue(context, META_API_KEY));
    }

    private static String getEndpoint(Context context) {
        String endpoint = clean(getMetaValue(context, META_ENDPOINT));
        if (endpoint.contains("TU_BACKEND.com")) {
            return "";
        }
        return endpoint;
    }

    private static String getMetaValue(Context context, String key) {
        try {
            ApplicationInfo appInfo = context.getPackageManager()
                .getApplicationInfo(context.getPackageName(), PackageManager.GET_META_DATA);
            if (appInfo.metaData == null) {
                return "";
            }
            return clean(appInfo.metaData.getString(key, ""));
        } catch (Exception e) {
            Log.w(TAG, "No se pudo leer meta-data " + key, e);
            return "";
        }
    }

    private static Long parseLongOrNull(String value) {
        String v = clean(value);
        if (TextUtils.isEmpty(v)) {
            return null;
        }
        try {
            return Long.parseLong(v);
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    private static String normalizeShopDomain(String value) {
        String v = clean(value).toLowerCase();
        if (TextUtils.isEmpty(v)) {
            return "";
        }
        v = v.replace("https://", "").replace("http://", "");
        int slash = v.indexOf('/');
        if (slash >= 0) {
            v = v.substring(0, slash);
        }
        return v;
    }


    private static String getAppVersion(Context context) {
        try {
            return clean(context.getPackageManager().getPackageInfo(context.getPackageName(), 0).versionName);
        } catch (Exception e) {
            return "";
        }
    }    private static String clean(String value) {
        if (value == null) {
            return "";
        }
        return value.trim();
    }
}

