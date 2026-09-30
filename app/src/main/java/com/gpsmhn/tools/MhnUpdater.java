package com.gpsmhn.tools;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.DownloadManager;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Environment;
import android.widget.Toast;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Locale;

/**
 * Kiểm tra cập nhật + tải APK MHN custom.
 *
 * <p>Địa chỉ lấy từ source AnyTo (InstallGameActivity):
 * <pre>
 *   PG   : https://download.pogoace.com/files/location/pokemongoCustom.apk
 *   DQ   : https://download.pogoace.com/files/location/dragonquestCustom.apk
 *   MHN  : https://download.pogoace.com/files/location/monsterhunterCustom.apk
 * </pre>
 *
 * <p>API bản mới (cũng của AnyTo): {@code GET https://apipdm.pogoace.com/game/version}
 * với {@code sign = MD5("timestamp=<ts>&type=<type>&key=PDM637d875cd89a9")} (in HOA), type 3 = MHN.
 * Trả về {@code {"code":1,"data":{"version":"128.0"}}}.
 *
 * <p>Luồng: nếu máy chưa cài MHN custom, hoặc server có version lớn hơn bản đang cài thì mời tải;
 * nếu đã là bản mới nhất thì báo không cần tải.
 */
public final class MhnUpdater {

    public static final String PACKAGE = "com.nianticlabs.monsterhunter";
    public static final String APK_URL = "https://download.pogoace.com/files/location/monsterhunterCustom.apk";

    private static final String VERSION_API = "https://apipdm.pogoace.com/game/version";
    private static final String SIGN_KEY = "PDM637d875cd89a9";
    private static final int TYPE_MHN = 3;
    private static final String APK_FILE = "monsterhunterCustom.apk";

    private static final String PREFS = "gpsmhn_prefs";
    private static final String K_DL_ID = "mhn_download_id";

    private MhnUpdater() { }

    // ------------------------------------------------------------------ installed

    /** versionName của MHN đang cài, hoặc null nếu chưa cài. */
    public static String installedVersion(Context context) {
        try {
            PackageInfo pi = context.getPackageManager().getPackageInfo(PACKAGE, 0);
            return pi.versionName;
        } catch (PackageManager.NameNotFoundException e) {
            return null;
        }
    }

    // ------------------------------------------------------------------ server

    /** Version mới nhất trên server (ví dụ "128.0"). Ném lỗi nếu không lấy được. */
    public static String fetchServerVersion() throws Exception {
        String ts = String.valueOf(System.currentTimeMillis() / 1000L);
        String sign = sign(ts, TYPE_MHN);
        String url = VERSION_API + "?sign=" + sign + "&timestamp=" + ts + "&type=" + TYPE_MHN;
        HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
        conn.setConnectTimeout(10000);
        conn.setReadTimeout(10000);
        conn.setRequestProperty("User-Agent", "gps-mhn/1.0");
        try {
            int code = conn.getResponseCode();
            if (code != 200) throw new Exception("HTTP " + code);
            StringBuilder sb = new StringBuilder();
            try (BufferedReader r = new BufferedReader(new InputStreamReader(conn.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = r.readLine()) != null) sb.append(line);
            }
            JSONObject o = new JSONObject(sb.toString());
            if (o.optInt("code") != 1) throw new Exception("code=" + o.optInt("code"));
            JSONObject data = o.optJSONObject("data");
            String v = data == null ? null : data.optString("version", "");
            if (v == null || v.isEmpty()) throw new Exception("thiếu version");
            return v;
        } finally {
            conn.disconnect();
        }
    }

    private static String sign(String timestamp, int type) throws Exception {
        String raw = "timestamp=" + timestamp + "&type=" + type + "&key=" + SIGN_KEY;
        MessageDigest md = MessageDigest.getInstance("MD5");
        byte[] digest = md.digest(raw.getBytes(StandardCharsets.UTF_8));
        StringBuilder sb = new StringBuilder();
        for (byte b : digest) sb.append(String.format(Locale.US, "%02X", b));
        return sb.toString();
    }

    // ------------------------------------------------------------------ check

    /** Kiểm tra trên luồng nền rồi hiện hộp thoại trên UI. */
    public static void checkAndPrompt(Activity activity) {
        new Thread(() -> {
            String installed = installedVersion(activity);
            String server = null;
            String error = null;
            try {
                server = fetchServerVersion();
            } catch (Exception e) {
                error = e.getMessage();
            }
            final String fi = installed, fs = server, fe = error;
            activity.runOnUiThread(() -> showDialog(activity, fi, fs, fe));
        }, "mhn-update-check").start();
    }

    private static void showDialog(Activity activity, String installed, String server, String error) {
        String title;
        String message;
        boolean offerDownload = false;

        if (installed == null) {
            title = "Chưa cài MHN custom";
            message = "Máy chưa có app MHN custom.\n"
                    + (server != null ? "Bản mới nhất trên server: " + server + "\n\n" : (error != null ? "(Không kiểm tra được version: " + error + ")\n\n" : ""))
                    + "Tải APK MHN custom về máy?";
            offerDownload = true;
        } else if (server == null) {
            title = "Không kiểm tra được bản mới";
            message = "Đang cài: " + installed + "\nKhông lấy được version từ server" + (error != null ? " (" + error + ")" : "") + ".\n\nVẫn tải bản mới nhất về?";
            offerDownload = true;
        } else if (compareVersions(server, installed) > 0) {
            title = "Có bản MHN custom mới";
            message = "Đang cài: " + installed + "\nBản mới: " + server + "\n\nTải bản mới về máy?";
            offerDownload = true;
        } else {
            title = "Đã là bản mới nhất";
            message = "MHN custom đang cài: " + installed + "\nServer: " + server + "\n\nKhông cần tải thêm.";
            offerDownload = false;
        }

        AlertDialog.Builder b = new AlertDialog.Builder(activity)
                .setTitle(title)
                .setMessage(message)
                .setNegativeButton(offerDownload ? "ĐỂ SAU" : "ĐÓNG", null);
        if (offerDownload) {
            b.setPositiveButton("⬇ TẢI VỀ", (d, w) -> downloadApk(activity));
        }
        b.show();
    }

    /** So sánh version dạng "128" vs "128.0" vs "129.1": trả >0 nếu a mới hơn b. */
    public static int compareVersions(String a, String b) {
        String[] pa = a.trim().split("\\.");
        String[] pb = b.trim().split("\\.");
        int n = Math.max(pa.length, pb.length);
        for (int i = 0; i < n; i++) {
            int va = i < pa.length ? parseIntSafe(pa[i]) : 0;
            int vb = i < pb.length ? parseIntSafe(pb[i]) : 0;
            if (va != vb) return Integer.compare(va, vb);
        }
        return 0;
    }

    private static int parseIntSafe(String s) {
        try { return Integer.parseInt(s.replaceAll("[^0-9]", "")); } catch (Exception e) { return 0; }
    }

    // ------------------------------------------------------------------ download

    public static void downloadApk(Activity activity) {
        try {
            DownloadManager dm = (DownloadManager) activity.getSystemService(Context.DOWNLOAD_SERVICE);
            DownloadManager.Request req = new DownloadManager.Request(Uri.parse(APK_URL));
            req.setTitle("monsterhunterCustom.apk");
            req.setDescription("Đang tải MHN custom…");
            req.setMimeType("application/vnd.android.package-archive");
            req.setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED);
            req.setDestinationInExternalFilesDir(activity, Environment.DIRECTORY_DOWNLOADS, APK_FILE);
            long id = dm.enqueue(req);
            prefs(activity).edit().putLong(K_DL_ID, id).apply();
            Toast.makeText(activity, "Đang tải MHN custom về máy…", Toast.LENGTH_LONG).show();
        } catch (Exception e) {
            Toast.makeText(activity, "Không tải được: " + e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    /** Gọi khi DownloadManager báo xong: mở màn hình cài đặt APK. */
    public static boolean handleDownloadComplete(Context context, long downloadId) {
        long saved = prefs(context).getLong(K_DL_ID, -1);
        if (saved != downloadId) return false;
        DownloadManager dm = (DownloadManager) context.getSystemService(Context.DOWNLOAD_SERVICE);
        Uri uri = dm.getUriForDownloadedFile(downloadId);
        if (uri == null) {
            Toast.makeText(context, "Tải xong nhưng không mở được file APK", Toast.LENGTH_LONG).show();
            return true;
        }
        Intent i = new Intent(Intent.ACTION_VIEW);
        i.setDataAndType(uri, "application/vnd.android.package-archive");
        i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_ACTIVITY_NEW_TASK);
        try {
            context.startActivity(i);
        } catch (Exception e) {
            Toast.makeText(context, "Không mở được trình cài đặt: " + e.getMessage(), Toast.LENGTH_LONG).show();
        }
        return true;
    }

    private static SharedPreferences prefs(Context c) {
        return c.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }
}
