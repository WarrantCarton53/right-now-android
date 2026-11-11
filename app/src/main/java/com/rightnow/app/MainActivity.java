package com.rightnow.app;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.webkit.JavascriptInterface;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Toast;

import java.io.OutputStream;
import java.nio.charset.StandardCharsets;

public class MainActivity extends Activity {
    private static final int REQ_SAVE = 10, REQ_PICK = 11, REQ_NOTIF = 12;
    private WebView web;
    private ValueCallback<Uri[]> pickCallback;
    private String pendingText;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        web = new WebView(this);
        setContentView(web);

        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setAllowFileAccess(true);
        s.setTextZoom(100);

        web.addJavascriptInterface(new Bridge(), "Android");
        web.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView v, WebResourceRequest r) {
                Uri u = r.getUrl();
                if ("file".equals(u.getScheme())) return false;
                try { startActivity(new Intent(Intent.ACTION_VIEW, u)); } catch (Exception ignored) {}
                return true;
            }
        });
        web.setWebChromeClient(new WebChromeClient() {
            @Override
            public boolean onShowFileChooser(WebView v, ValueCallback<Uri[]> cb, FileChooserParams p) {
                if (pickCallback != null) pickCallback.onReceiveValue(null);
                pickCallback = cb;
                Intent i = new Intent(Intent.ACTION_GET_CONTENT).addCategory(Intent.CATEGORY_OPENABLE).setType("*/*");
                try { startActivityForResult(i, REQ_PICK); }
                catch (Exception e) { pickCallback = null; return false; }
                return true;
            }
        });
        web.loadUrl("file:///android_asset/index.html");

        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, REQ_NOTIF);
        }
        Scheduler.ensureChannel(this);
        Scheduler.refresh(this);
    }

    @Override
    protected void onResume() {
        super.onResume();
        autoCheck();
        if (web != null) web.evaluateJavascript("window.rnRefresh&&rnRefresh()", null);
        Scheduler.refresh(this);
    }

    @Override
    public void onBackPressed() {
        web.evaluateJavascript("window.rnBack?rnBack():false", v -> {
            if (!"true".equals(v)) finish();
        });
    }

    @Override
    protected void onActivityResult(int req, int res, Intent data) {
        super.onActivityResult(req, res, data);
        if (req == REQ_PICK) {
            if (pickCallback != null) pickCallback.onReceiveValue(WebChromeClient.FileChooserParams.parseResult(res, data));
            pickCallback = null;
        } else if (req == REQ_SAVE) {
            if (res == RESULT_OK && data != null && data.getData() != null && pendingText != null) {
                try (OutputStream o = getContentResolver().openOutputStream(data.getData())) {
                    o.write(pendingText.getBytes(StandardCharsets.UTF_8));
                    Toast.makeText(this, "Saved", Toast.LENGTH_SHORT).show();
                } catch (Exception e) {
                    Toast.makeText(this, "Couldn't save the file", Toast.LENGTH_LONG).show();
                }
            }
            pendingText = null;
        }
    }

    private long lastCheck = 0;
    private String updateUrl = null;

    /** Look for a new version when the app opens (at most once an hour). */
    private void autoCheck() {
        long now = System.currentTimeMillis();
        if (now - lastCheck < 3600_000L) return;
        lastCheck = now;
        runCheck(false);
    }

    private void runCheck(boolean manual) {
        long mine = Updater.installedVersion(this);
        Updater.check((latest, url, err) -> runOnUiThread(() -> {
            if (web == null) return;
            String js;
            if (err != null) js = manual ? "window.rnUpdateStatus&&rnUpdateStatus(" + JSONq(err) + ",false)" : "";
            else if (latest > mine) { updateUrl = url; js = "window.rnUpdateStatus&&rnUpdateStatus(" + JSONq("Version " + latest + " is ready (you have " + mine + ").") + ",true)"; }
            else js = manual ? "window.rnUpdateStatus&&rnUpdateStatus(" + JSONq("You have the newest version (" + mine + ").") + ",false)" : "";
            if (!js.isEmpty()) web.evaluateJavascript(js, null);
        }));
    }

    private static String JSONq(String s) { return org.json.JSONObject.quote(s); }

    /** What the Right Now page can ask the phone to do. */
    class Bridge {
        @JavascriptInterface public String getState() { return Store.getState(MainActivity.this); }
        @JavascriptInterface public void saveState(String json) {
            Store.saveState(MainActivity.this, json);
            Scheduler.refresh(MainActivity.this);
        }
        @JavascriptInterface public void checkUpdate() { runOnUiThread(() -> runCheck(true)); }
        @JavascriptInterface public String appVersion() { return String.valueOf(Updater.installedVersion(MainActivity.this)); }
        @JavascriptInterface public void installUpdate() {
            runOnUiThread(() -> {
                if (updateUrl == null) { runCheck(true); return; }
                if (Updater.needsPermission(MainActivity.this)) {
                    web.evaluateJavascript("window.rnUpdateStatus&&rnUpdateStatus(" + JSONq("Turn on \"Allow from this source\" for Right Now, then come back and tap Update again.") + ",true)", null);
                    Updater.openPermission(MainActivity.this);
                    return;
                }
                web.evaluateJavascript("window.rnUpdateStatus&&rnUpdateStatus(" + JSONq("Downloading the update…") + ",false)", null);
                Updater.install(MainActivity.this, updateUrl, (a, b, err) -> runOnUiThread(() -> {
                    if (err != null) web.evaluateJavascript("window.rnUpdateStatus&&rnUpdateStatus(" + JSONq(err) + ",true)", null);
                }));
            });
        }
        @JavascriptInterface public String getDone(String day) { return Store.getDone(MainActivity.this, day); }
        @JavascriptInterface public void setDone(String day, String json) {
            Store.setDone(MainActivity.this, day, json);
            Scheduler.updateWidgets(MainActivity.this);
        }
        @JavascriptInterface public void testNotification() {
            Scheduler.post(MainActivity.this, 999, "Right Now", "Notifications are working. You'll get one when each block with Alert on starts.");
        }
        @JavascriptInterface public boolean notificationsAllowed() {
            if (Build.VERSION.SDK_INT >= 33) return checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED;
            return true;
        }
        @JavascriptInterface public void openNotificationSettings() {
            runOnUiThread(() -> {
                Intent i = new Intent(android.provider.Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                        .putExtra(android.provider.Settings.EXTRA_APP_PACKAGE, getPackageName());
                try { startActivity(i); } catch (Exception ignored) {}
            });
        }
        @JavascriptInterface public void saveFile(String name, String text, String mime) {
            runOnUiThread(() -> {
                pendingText = text;
                Intent i = new Intent(Intent.ACTION_CREATE_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE)
                        .setType(mime == null || mime.isEmpty() ? "application/octet-stream" : mime)
                        .putExtra(Intent.EXTRA_TITLE, name);
                try { startActivityForResult(i, REQ_SAVE); } catch (Exception e) { pendingText = null; }
            });
        }
    }
}
