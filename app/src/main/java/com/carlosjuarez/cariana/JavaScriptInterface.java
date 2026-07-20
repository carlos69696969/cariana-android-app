// Copyright 2026 (c) WebIntoApp.com
//
// Permission is hereby granted, free of charge, to any person obtaining a copy of
// this software and associated documentation files (the "Software"), to deal in the
// Software without restriction, including without limitation the rights to use,
// copy, modify, merge, publish, distribute, sublicense, and/or sell copies of the
// Software, and to permit persons to whom the Software is furnished to do so,
// subject to the following conditions:
//
// The above copyright notice and this permission notice shall be included in
// all copies or substantial portions of the Software.
//
// THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
// IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
// FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
// AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
// LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
// OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
// SOFTWARE.
//  CARIANA
//
//  Created by CarlosJuarez on 19/02/2026.
//
package com.carlosjuarez.cariana;
import android.app.Activity;
import android.app.AlertDialog;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.Manifest;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothSocket;
import android.content.pm.PackageManager;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Environment;
import android.content.Context;
import android.os.Build;
import android.service.notification.StatusBarNotification;
import androidx.core.app.NotificationCompat;
import androidx.core.content.FileProvider;
import android.os.Handler;
import android.text.TextUtils;
import android.text.InputType;
import android.util.Base64;
import android.webkit.JavascriptInterface;
import android.webkit.MimeTypeMap;
import java.io.IOException;
import android.widget.EditText;
import android.widget.Toast;
import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStream;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
public class JavaScriptInterface {
    private static final String PREFS_PUSH_STORE = "cariana_push_notifications";
    private static final String PREFS_KEY_URL_PREFIX = "push_url_";
    private static final String PREFS_PUSH_SYNC = "cariana_push_sync";
    private static final String PREFS_KEY_PUSH_USER_ID = "user_id";
    private static final String PREFS_KEY_PUSH_TOKEN = "token";
    private Context context;
    public JavaScriptInterface(Context context) {
        this.context = context;
    }
    @JavascriptInterface
    public void getBase64FromBlobData(final String base64Data, final String mimetype) throws IOException {
        AlertDialog.Builder builder = new AlertDialog.Builder(this.context);
        builder.setTitle("Save As");
        final EditText input = new EditText(this.context);
        input.setInputType(InputType.TYPE_CLASS_TEXT);
        builder.setView(input);
        builder.setPositiveButton("OK", new DialogInterface.OnClickListener() {
            @Override
            public void onClick(DialogInterface dialog, int which) {
                try {
                    convertBase64StringAndStoreIt(base64Data, mimetype, input.getText().toString());
                } catch (IOException e) {
                    e.printStackTrace();
                }
            }
        });
        builder.setNegativeButton("Cancel", new DialogInterface.OnClickListener() {
            @Override
            public void onClick(DialogInterface dialog, int which) {
                dialog.cancel();
            }
        });
        builder.show();
    }
    public static String getBase64StringFromBlobUrl(String blobUrl, String mimetype) {
        if(blobUrl.startsWith("blob")){
            return "javascript: var xhr = new XMLHttpRequest();" +
                "xhr.open('GET', '"+ blobUrl +"', true);" +
                "xhr.setRequestHeader('Content-type','" + mimetype + "');" +
                "xhr.responseType = 'blob';" +
                "xhr.onload = function(e) {" +
                "    if (this.status == 200) {" +
                "        var blobData = this.response;" +
                "        var reader = new FileReader();" +
                "        reader.readAsDataURL(blobData);" +
                "        reader.onloadend = function() {" +
                "            base64data = reader.result;" +
                "            Android.getBase64FromBlobData(base64data, '" + mimetype +"');" +
                "        }" +
                "    }" +
                "};" +
                "xhr.send();";
        }
        return "javascript: console.log('It is not a Blob URL');";
    }
    @JavascriptInterface
    public void shareText(String text) {
        shareContent("Compartir", text);
    }

    @JavascriptInterface
    public void printPrepLabel(String orderNumber, String routeNumber, String customerName, String address) {
        final String cleanOrderNumber = normalizeLabelText(orderNumber, "SIN ORDEN");
        final String cleanRouteNumber = normalizeLabelText(routeNumber, "-");
        final String cleanCustomerName = normalizeLabelText(customerName, "Cliente");
        final String cleanAddress = normalizeLabelText(address, "");

        new Thread(new Runnable() {
            @Override
            public void run() {
                BluetoothSocket socket = null;
                try {
                    if (!hasBluetoothConnectPermission()) {
                        requestBluetoothConnectPermission();
                        showToast("Permite Bluetooth y vuelve a presionar Listo.");
                        return;
                    }

                    BluetoothAdapter adapter = BluetoothAdapter.getDefaultAdapter();
                    if (adapter == null) {
                        showToast("Este celular no tiene Bluetooth disponible.");
                        return;
                    }
                    if (!adapter.isEnabled()) {
                        showToast("Activa Bluetooth para imprimir la etiqueta.");
                        return;
                    }

                    BluetoothDevice printer = findBondedLabelPrinter(adapter);
                    if (printer == null) {
                        showToast("Empareja la impresora Hstem 420B BL por Bluetooth.");
                        return;
                    }

                    socket = printer.createRfcommSocketToServiceRecord(
                        UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")
                    );
                    socket.connect();
                    OutputStream outputStream = socket.getOutputStream();
                    outputStream.write(buildPrepLabelTspl(cleanOrderNumber, cleanRouteNumber, cleanCustomerName, cleanAddress));
                    outputStream.flush();
                    showToast("Etiqueta enviada a la impresora.");
                } catch (SecurityException error) {
                    requestBluetoothConnectPermission();
                    showToast("Permite Bluetooth para imprimir.");
                } catch (Exception error) {
                    showToast("No se pudo imprimir. Revisa que la impresora este encendida y emparejada.");
                } finally {
                    if (socket != null) {
                        try {
                            socket.close();
                        } catch (IOException ignored) {}
                    }
                }
            }
        }).start();
    }

    private boolean hasBluetoothConnectPermission() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
            return true;
        }
        return context.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED;
    }

    private void requestBluetoothConnectPermission() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S || !(context instanceof Activity)) {
            return;
        }
        ((Activity) context).runOnUiThread(new Runnable() {
            @Override
            public void run() {
                ((Activity) context).requestPermissions(
                    new String[] { Manifest.permission.BLUETOOTH_CONNECT },
                    420
                );
            }
        });
    }

    private BluetoothDevice findBondedLabelPrinter(BluetoothAdapter adapter) {
        Set<BluetoothDevice> bondedDevices = adapter.getBondedDevices();
        if (bondedDevices == null || bondedDevices.isEmpty()) {
            return null;
        }
        BluetoothDevice fallback = null;
        for (BluetoothDevice device : bondedDevices) {
            if (device == null) continue;
            if (fallback == null) fallback = device;
            String name = "";
            try {
                name = String.valueOf(device.getName()).toLowerCase();
            } catch (SecurityException ignored) {}
            if (
                name.contains("hstem") ||
                name.contains("420") ||
                name.contains("xp") ||
                name.contains("xprinter") ||
                name.contains("printer") ||
                name.contains("label")
            ) {
                return device;
            }
        }
        return fallback;
    }

    private byte[] buildPrepLabelTspl(String orderNumber, String routeNumber, String customerName, String address) {
        StringBuilder command = new StringBuilder();
        command.append("SIZE 100 mm,60 mm\r\n");
        command.append("GAP 2 mm,0 mm\r\n");
        command.append("DIRECTION 1\r\n");
        command.append("REFERENCE 0,0\r\n");
        command.append("SPEED 4\r\n");
        command.append("DENSITY 10\r\n");
        command.append("CLS\r\n");
        command.append("BOX 16,16,784,464,3\r\n");
        command.append("TEXT 36,36,\"0\",0,2,2,\"CARIANA\"\r\n");
        command.append("TEXT 36,104,\"0\",0,2,2,\"ORDEN #").append(tsplText(orderNumber)).append("\"\r\n");
        command.append("CIRCLE 682,112,46,4\r\n");
        command.append("TEXT 666,96,\"0\",0,2,2,\"").append(tsplText(routeNumber)).append("\"\r\n");
        command.append("TEXT 36,180,\"0\",0,1,2,\"CLIENTE\"\r\n");
        command.append("TEXT 36,216,\"0\",0,2,2,\"").append(tsplText(customerName)).append("\"\r\n");
        command.append("TEXT 36,292,\"0\",0,1,2,\"DIRECCION\"\r\n");
        int y = 326;
        for (String line : wrapForTspl(address, 33, 3)) {
            command.append("TEXT 36,").append(y).append(",\"0\",0,1,2,\"").append(tsplText(line)).append("\"\r\n");
            y += 36;
        }
        command.append("PRINT 1,1\r\n");
        return command.toString().getBytes(StandardCharsets.ISO_8859_1);
    }

    private String normalizeLabelText(String value, String fallback) {
        String text = String.valueOf(value == null ? "" : value).trim();
        return TextUtils.isEmpty(text) ? fallback : text;
    }

    private String tsplText(String value) {
        return String.valueOf(value == null ? "" : value)
            .replace("\\", "/")
            .replace("\"", "'")
            .replace("\r", " ")
            .replace("\n", " ")
            .trim();
    }

    private String[] wrapForTspl(String value, int maxChars, int maxLines) {
        String text = normalizeLabelText(value, "-").replaceAll("\\s+", " ");
        String[] words = text.split(" ");
        String[] lines = new String[maxLines];
        int lineIndex = 0;
        StringBuilder current = new StringBuilder();
        for (String word : words) {
            if (lineIndex >= maxLines) break;
            String next = current.length() == 0 ? word : current + " " + word;
            if (next.length() > maxChars && current.length() > 0) {
                lines[lineIndex] = current.toString();
                lineIndex += 1;
                current = new StringBuilder(word);
            } else {
                current = new StringBuilder(next);
            }
        }
        if (lineIndex < maxLines && current.length() > 0) {
            lines[lineIndex] = current.toString();
            lineIndex += 1;
        }
        for (int i = 0; i < maxLines; i++) {
            if (lines[i] == null) lines[i] = "";
        }
        return lines;
    }

    private void showToast(final String message) {
        Handler handler = new Handler(context.getMainLooper());
        handler.post(new Runnable() {
            @Override
            public void run() {
                Toast.makeText(context, message, Toast.LENGTH_LONG).show();
            }
        });
    }
    @JavascriptInterface
    public void shareUrl(String url, String text) {
        String cleanUrl = cleanProductShareUrl(url);
        String cleanText = formatProductShareText(text, cleanUrl);
        StringBuilder content = new StringBuilder();
        if (!TextUtils.isEmpty(cleanText)) {
            content.append(cleanText.trim());
        }
        if (!TextUtils.isEmpty(cleanUrl)) {
            if (content.length() > 0) {
                content.append("\n");
            }
            content.append(cleanUrl.trim());
        }
        shareContent("Compartir", content.toString());
    }
    private String cleanProductShareUrl(String url) {
        if (TextUtils.isEmpty(url)) {
            return url;
        }
        Uri parsed = Uri.parse(url);
        String host = parsed.getHost();
        String path = parsed.getPath();
        if (TextUtils.isEmpty(host) || TextUtils.isEmpty(path) || !path.contains("/products/")) {
            return url;
        }
        String normalizedHost = host.toLowerCase();
        if (!"cariana.mx".equals(normalizedHost)
            && !"www.cariana.mx".equals(normalizedHost)
            && !"app.cariana.mx".equals(normalizedHost)
            && !"cariana-3.myshopify.com".equals(normalizedHost)
            && !normalizedHost.endsWith(".myshopify.com")) {
            return url;
        }
        return parsed.buildUpon()
            .scheme("https")
            .encodedAuthority("app.cariana.mx")
            .encodedPath(path)
            .clearQuery()
            .fragment(null)
            .build()
            .toString();
    }
    private String formatProductShareText(String text, String url) {
        if (TextUtils.isEmpty(url)) {
            return text;
        }
        Uri parsed = Uri.parse(url);
        String path = parsed.getPath();
        if (TextUtils.isEmpty(path) || !path.contains("/products/")) {
            return text;
        }
        String normalizedText = TextUtils.isEmpty(text) ? "" : text.trim();
        if (normalizedText.toLowerCase().contains("mira este producto de cariana")) {
            return normalizedText;
        }
        return "Mira este producto de CARIANA \uD83D\uDD25 Creo que te va a gustar:";
    }
    private void shareContent(String subject, String content) {
        if (TextUtils.isEmpty(content)) {
            Toast.makeText(context, "No hay contenido para compartir", Toast.LENGTH_SHORT).show();
            return;
        }
        Intent intent = new Intent(Intent.ACTION_SEND);
        intent.setType("text/plain");
        intent.putExtra(Intent.EXTRA_SUBJECT, subject);
        intent.putExtra(Intent.EXTRA_TEXT, content);
        Intent chooser = Intent.createChooser(intent, "Compartir con");
        if (!(context instanceof Activity)) {
            chooser.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        }
        context.startActivity(chooser);
    }
        @JavascriptInterface
    public void setPushUser(String userId, String email) {
        PushSyncManager.setUser(context, userId, email);
    }
    @JavascriptInterface
    public void setPushUserWithShop(String userId, String email, String shopDomain) {
        PushSyncManager.setUser(context, userId, email, shopDomain);
    }
    @JavascriptInterface
    public void setPushShopDomain(String shopDomain) {
        PushSyncManager.setShopDomain(context, shopDomain);
    }
    @JavascriptInterface
    public void clearPushUser() {
        PushSyncManager.clearUser(context);
    }
    @JavascriptInterface
    public String getPushCustomerId() {
        SharedPreferences preferences = context.getSharedPreferences(PREFS_PUSH_SYNC, Context.MODE_PRIVATE);
        String value = preferences.getString(PREFS_KEY_PUSH_USER_ID, "");
        return value == null ? "" : value.trim();
    }
    @JavascriptInterface
    public String getPushToken() {
        SharedPreferences preferences = context.getSharedPreferences(PREFS_PUSH_SYNC, Context.MODE_PRIVATE);
        String value = preferences.getString(PREFS_KEY_PUSH_TOKEN, "");
        return value == null ? "" : value.trim();
    }
    @JavascriptInterface
    public int getTrayNotificationCount() {
        NotificationManager notificationManager = (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
        if (notificationManager == null) {
            return -1;
        }
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) {
            return -1;
        }

        StatusBarNotification[] active = notificationManager.getActiveNotifications();
        if (active == null || active.length == 0) {
            return 0;
        }

        int count = 0;
        for (StatusBarNotification item : active) {
            if (item == null || item.getNotification() == null) {
                continue;
            }
            String channelId = "";
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                channelId = item.getNotification().getChannelId();
            }
            String pkg = item.getPackageName();
            if (!TextUtils.equals(pkg, context.getPackageName())) {
                continue;
            }
            if (TextUtils.isEmpty(channelId) || channelId.startsWith("cariana_")) {
                count += 1;
            }
        }
        return count;
    }
    @JavascriptInterface
    public void dismissNotificationByUrl(String targetUrl) {
        NotificationManager notificationManager = (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
        if (notificationManager == null) {
            return;
        }
        String normalizedTarget = normalizeNotificationUrl(targetUrl);
        if (TextUtils.isEmpty(normalizedTarget)) {
            return;
        }

        SharedPreferences preferences = context.getSharedPreferences(PREFS_PUSH_STORE, Context.MODE_PRIVATE);
        Map<String, ?> allEntries = preferences.getAll();
        SharedPreferences.Editor editor = preferences.edit();
        for (Map.Entry<String, ?> entry : allEntries.entrySet()) {
            String key = entry.getKey();
            if (TextUtils.isEmpty(key) || !key.startsWith(PREFS_KEY_URL_PREFIX)) {
                continue;
            }

            String storedUrl = entry.getValue() == null ? "" : String.valueOf(entry.getValue());
            if (!normalizedTarget.equals(normalizeNotificationUrl(storedUrl))) {
                continue;
            }

            String notificationIdText = key.substring(PREFS_KEY_URL_PREFIX.length());
            try {
                int notificationId = Integer.parseInt(notificationIdText);
                notificationManager.cancel(notificationId);
            } catch (NumberFormatException ignored) {
                // Ignore malformed keys.
            }
            editor.remove(key);
        }
        editor.apply();
    }
    @JavascriptInterface
    public void dismissAllPushNotifications() {
        NotificationManager notificationManager = (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
        if (notificationManager != null) {
            notificationManager.cancelAll();
        }
        SharedPreferences preferences = context.getSharedPreferences(PREFS_PUSH_STORE, Context.MODE_PRIVATE);
        Map<String, ?> allEntries = preferences.getAll();
        SharedPreferences.Editor editor = preferences.edit();
        for (String key : allEntries.keySet()) {
            if (!TextUtils.isEmpty(key) && key.startsWith(PREFS_KEY_URL_PREFIX)) {
                editor.remove(key);
            }
        }
        editor.apply();
    }
    private void convertBase64StringAndStoreIt(String base64PDf, String mimetype, String filename) throws IOException {
        String[] parts = mimetype.split("/");
        String ext = parts[1];
        byte[] data = Base64.decode(base64PDf.replaceFirst("^data:" + mimetype + ";base64,", ""), Base64.DEFAULT);
        final File dwldsPath = new File(Environment.getExternalStoragePublicDirectory(
            Environment.DIRECTORY_DOWNLOADS) + "/" + filename);
        String text = new String(data, "UTF-8");
        byte[] dataAsBytes = text.getBytes(Charset.forName("UTF-8"));
        FileOutputStream os;
        os = new FileOutputStream(dwldsPath, false);
        os.write(dataAsBytes);
        os.flush();
        final int notificationId = 1;
        if (dwldsPath.exists()) {
            Intent intent = new Intent();
            intent.setAction(android.content.Intent.ACTION_VIEW);
            Uri apkURI = FileProvider.getUriForFile(context,context.getApplicationContext().getPackageName() + ".provider", dwldsPath);
            intent.setDataAndType(apkURI, MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext));
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            PendingIntent pendingIntent = PendingIntent.getActivity(context,1, intent, PendingIntent.FLAG_CANCEL_CURRENT | PendingIntent.FLAG_IMMUTABLE);
            String CHANNEL_ID = "cariana_local_v3";
            final NotificationManager notificationManager = (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                NotificationChannel notificationChannel= new NotificationChannel(CHANNEL_ID,"Cariana", NotificationManager.IMPORTANCE_HIGH);
                notificationChannel.enableVibration(true);
                Notification notification = new Notification.Builder(context,CHANNEL_ID)
                    .setContentTitle("Download Complete")
                    .setContentText(filename)
                    .setContentIntent(pendingIntent)
                    .setChannelId(CHANNEL_ID)
                    .setSmallIcon(android.R.drawable.stat_sys_download_done)
                    .setPriority(Notification.PRIORITY_HIGH)
                    .build();
                if (notificationManager != null) {
                    notificationManager.createNotificationChannel(notificationChannel);
                    notificationManager.notify(notificationId, notification);
                }
            } else {
                NotificationCompat.Builder b = new NotificationCompat.Builder(context, CHANNEL_ID)
                    .setDefaults(NotificationCompat.DEFAULT_ALL)
                    .setWhen(System.currentTimeMillis())
                    .setSmallIcon(android.R.drawable.sym_action_chat)
                    .setContentTitle("Download Complete")
                    .setContentText(filename)
                    .setPriority(NotificationCompat.PRIORITY_HIGH);
                if (notificationManager != null) {
                    notificationManager.notify(notificationId, b.build());
                    Handler h = new Handler();
                    long delayInMilliseconds = 1000;
                    h.postDelayed(new Runnable() {
                        public void run() {
                            notificationManager.cancel(notificationId);
                        }
                    }, delayInMilliseconds);
                }
            }
        }
        Toast.makeText(context, "Download Completed.", Toast.LENGTH_SHORT).show();
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



