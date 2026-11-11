package com.rightnow.app;

import android.app.Activity;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.content.pm.PackageInstaller;
import android.net.Uri;
import android.os.Build;
import android.provider.Settings;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;

/** Checks GitHub for a newer Right Now and installs it with one tap. */
public final class Updater {
    private Updater() {}

    static final String LATEST = "https://api.github.com/repos/WarrantCarton53/right-now-android/releases/latest";

    interface Result { void done(int latest, String apkUrl, String error); }

    static long installedVersion(Context c) {
        try {
            PackageInfo pi = c.getPackageManager().getPackageInfo(c.getPackageName(), 0);
            return Build.VERSION.SDK_INT >= 28 ? pi.getLongVersionCode() : pi.versionCode;
        } catch (Exception e) { return 0; }
    }

    /** Runs in the background; calls back with the newest version number on GitHub. */
    static void check(Result r) {
        new Thread(() -> {
            try {
                HttpURLConnection h = (HttpURLConnection) new URL(LATEST).openConnection();
                h.setRequestProperty("Accept", "application/vnd.github+json");
                h.setRequestProperty("User-Agent", "RightNow-Android");
                h.setConnectTimeout(10000);
                h.setReadTimeout(10000);
                String body = read(h.getInputStream());
                JSONObject o = new JSONObject(body);
                int v = Integer.parseInt(o.getString("tag_name").replaceAll("[^0-9]", ""));
                String url = null;
                JSONArray assets = o.getJSONArray("assets");
                for (int i = 0; i < assets.length(); i++) {
                    JSONObject a = assets.getJSONObject(i);
                    if (a.getString("name").endsWith(".apk")) url = a.getString("browser_download_url");
                }
                r.done(v, url, url == null ? "No app file in the latest release" : null);
            } catch (Exception e) {
                r.done(0, null, "Couldn't reach GitHub. Check your internet.");
            }
        }).start();
    }

    static String read(InputStream in) throws Exception {
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        byte[] buf = new byte[16384]; int n;
        while ((n = in.read(buf)) > 0) b.write(buf, 0, n);
        in.close();
        return b.toString("UTF-8");
    }

    /** True when Android still needs "Install unknown apps" turned on for Right Now. */
    static boolean needsPermission(Context c) {
        return Build.VERSION.SDK_INT >= 26 && !c.getPackageManager().canRequestPackageInstalls();
    }

    static void openPermission(Activity a) {
        Intent i = new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:" + a.getPackageName()));
        try { a.startActivity(i); } catch (Exception ignored) {}
    }

    /** Downloads the new version and asks Android to install it. Runs in the background. */
    static void install(Context c, String apkUrl, Result r) {
        new Thread(() -> {
            try {
                PackageInstaller pi = c.getPackageManager().getPackageInstaller();
                PackageInstaller.SessionParams p = new PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL);
                p.setAppPackageName(c.getPackageName());
                int id = pi.createSession(p);
                try (PackageInstaller.Session s = pi.openSession(id)) {
                    URL u = new URL(apkUrl);
                    HttpURLConnection h = (HttpURLConnection) u.openConnection();
                    h.setInstanceFollowRedirects(true);
                    h.setRequestProperty("User-Agent", "RightNow-Android");
                    h.setConnectTimeout(15000);
                    h.setReadTimeout(30000);
                    // GitHub sends downloads through a redirect to another host
                    int code = h.getResponseCode();
                    int hops = 0;
                    while ((code == 301 || code == 302 || code == 303 || code == 307 || code == 308) && hops++ < 5) {
                        String loc = h.getHeaderField("Location");
                        h.disconnect();
                        h = (HttpURLConnection) new URL(u, loc).openConnection();
                        h.setRequestProperty("User-Agent", "RightNow-Android");
                        h.setConnectTimeout(15000);
                        h.setReadTimeout(30000);
                        code = h.getResponseCode();
                    }
                    if (code != 200) throw new Exception("Download failed (" + code + ")");
                    try (InputStream in = h.getInputStream(); OutputStream out = s.openWrite("RightNow.apk", 0, -1)) {
                        byte[] buf = new byte[65536]; int n;
                        while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
                        s.fsync(out);
                    }
                    Intent done = new Intent(c, InstallReceiver.class);
                    int flags = PendingIntent.FLAG_UPDATE_CURRENT | (Build.VERSION.SDK_INT >= 31 ? PendingIntent.FLAG_MUTABLE : 0);
                    PendingIntent cb = PendingIntent.getBroadcast(c, 3, done, flags);
                    s.commit(cb.getIntentSender());
                }
                r.done(0, null, null);
            } catch (Exception e) {
                r.done(0, null, "Couldn't download the update. Try again in a minute.");
            }
        }).start();
    }
}
