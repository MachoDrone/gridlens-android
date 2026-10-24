package com.gridlens.android;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.graphics.Color;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.Message;
import android.view.Gravity;
import android.view.View;
import android.view.WindowInsets;
import android.webkit.CookieManager;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class MainActivity extends Activity {
    private static final int IMPORT_HOSTS = 41;
    private static final int MAX_ROSTER_BYTES = 512 * 1024;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private WebView webView;
    private TextView status;
    private Button recovery;
    private UpdateController updates;
    private DiscoveryController discovery;
    private String origin;
    private String token;
    private String importScenario;
    private String savedUrl;
    private boolean destroyed;
    private boolean paused;
    private boolean clearHistoryAfterLoad;
    private boolean pageFailed;
    private long startupAt;

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        if (state != null) {
            savedUrl = state.getString("url");
            importScenario = state.getString("importScenario");
        }
        createViews();
        updates = new UpdateController(this);
        startupAt = android.os.SystemClock.elapsedRealtime();
        NodeRuntime.start(this);
        handler.post(this::awaitRuntime);
    }

    @SuppressLint("SetJavaScriptEnabled") private void createViews() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Color.rgb(11, 17, 22));
        root.setOnApplyWindowInsetsListener((view, insets) -> {
            view.setPadding(insets.getSystemWindowInsetLeft(), insets.getSystemWindowInsetTop(),
                insets.getSystemWindowInsetRight(), insets.getSystemWindowInsetBottom());
            return insets.consumeSystemWindowInsets();
        });
        LinearLayout toolbar = new LinearLayout(this);
        toolbar.setGravity(Gravity.CENTER_VERTICAL);
        toolbar.setPadding(dp(12), 0, dp(4), 0);
        TextView title = new TextView(this);
        title.setText(R.string.app_name);
        title.setTextSize(18);
        title.setTextColor(Color.WHITE);
        title.setTypeface(null, android.graphics.Typeface.BOLD);
        toolbar.addView(title, new LinearLayout.LayoutParams(0, dp(52), 1));
        title.setGravity(Gravity.CENTER_VERTICAL);
        Button fleets = new Button(this);
        fleets.setText(R.string.fleets_button);
        fleets.setContentDescription("Network discovery and saved fleets");
        fleets.setOnClickListener(view -> { if (discovery != null) discovery.show(null); else Toast.makeText(this, "GridLens is still starting.", Toast.LENGTH_SHORT).show(); });
        toolbar.addView(fleets, new LinearLayout.LayoutParams(dp(76), dp(48)));
        Button hosts = new Button(this);
        hosts.setText(R.string.hosts_button);
        hosts.setContentDescription("Import host address list");
        hosts.setOnClickListener(view -> chooseRoster());
        toolbar.addView(hosts, new LinearLayout.LayoutParams(dp(76), dp(48)));
        Button update = new Button(this);
        update.setText(R.string.update_button);
        update.setContentDescription("Check for an Android app update");
        update.setOnClickListener(view -> updates.checkForUpdate());
        toolbar.addView(update, new LinearLayout.LayoutParams(dp(84), dp(48)));
        root.addView(toolbar);
        status = new TextView(this);
        status.setText(R.string.starting_runtime);
        status.setTextColor(Color.LTGRAY);
        status.setPadding(dp(16), dp(12), dp(16), dp(12));
        status.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        root.addView(status);
        recovery = new Button(this);
        recovery.setText(R.string.open_app_settings);
        recovery.setVisibility(View.GONE);
        recovery.setOnClickListener(view -> startActivity(new Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
            Uri.parse("package:" + getPackageName()))));
        root.addView(recovery, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(48)));
        webView = new WebView(this);
        webView.setBackgroundColor(Color.rgb(11, 17, 22));
        WebSettings settings = webView.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setAllowFileAccess(false);
        settings.setAllowContentAccess(false);
        settings.setAllowFileAccessFromFileURLs(false);
        settings.setAllowUniversalAccessFromFileURLs(false);
        settings.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);
        settings.setGeolocationEnabled(false);
        settings.setSupportMultipleWindows(true);
        settings.setJavaScriptCanOpenWindowsAutomatically(false);
        settings.setBuiltInZoomControls(true);
        settings.setDisplayZoomControls(false);
        settings.setUseWideViewPort(true);
        settings.setLoadWithOverviewMode(true);
        CookieManager.getInstance().setAcceptCookie(true);
        CookieManager.getInstance().setAcceptThirdPartyCookies(webView, false);
        WebView.setWebContentsDebuggingEnabled(BuildConfig.DEBUG);
        webView.setWebViewClient(new WebViewClient() {
            @Override public void onPageStarted(WebView view, String url, android.graphics.Bitmap icon) { pageFailed = false; }
            @Override public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                if (local(request.getUrl())) {
                    Uri destination = request.getUrl();
                    if ("/mobile/discovery".equals(destination.getPath()) || "/mobile/select".equals(destination.getPath())) {
                        if (request.isForMainFrame() && request.hasGesture() && discovery != null) {
                            if ("/mobile/discovery".equals(destination.getPath())) {
                                String fleet = destination.getQueryParameter("fleet");
                                if (fleet == null || fleet.matches("[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}")) discovery.show(fleet);
                            } else {
                                String scenario = destination.getQueryParameter("scenario");
                                if (scenario != null) discovery.selectScenario(scenario);
                            }
                        }
                        return true;
                    }
                    return false;
                }
                if (request.isForMainFrame() && request.hasGesture()) openExternal(request.getUrl());
                return true;
            }
            @Override public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest request) {
                if (local(request.getUrl())) return null;
                return new WebResourceResponse("text/plain", "UTF-8", 403, "Blocked", java.util.Collections.emptyMap(),
                    new ByteArrayInputStream(new byte[0]));
            }
            @Override public void onPageFinished(WebView view, String url) {
                if (local(Uri.parse(url)) && !pageFailed) {
                    status.setVisibility(View.GONE);
                    recovery.setVisibility(View.GONE);
                    if (clearHistoryAfterLoad) { webView.clearHistory(); clearHistoryAfterLoad = false; }
                    if (paused) suspendPage();
                }
            }
            @Override public void onReceivedError(WebView view, WebResourceRequest request, android.webkit.WebResourceError error) {
                if (request.isForMainFrame()) {
                    pageFailed = true;
                    showRecoveryStatus("The on-phone dashboard could not load. Force stop GridLens in Android Settings, then reopen it.");
                }
            }
            @Override public void onReceivedHttpError(WebView view, WebResourceRequest request, WebResourceResponse error) {
                if (request.isForMainFrame()) {
                    pageFailed = true;
                    showRecoveryStatus("The private on-phone session was rejected. Force stop GridLens in Android Settings, then reopen it.");
                }
            }
        });
        webView.setWebChromeClient(new WebChromeClient() {
            @Override public boolean onCreateWindow(WebView view, boolean dialog, boolean userGesture, Message result) {
                if (!userGesture) return false;
                WebView child = new WebView(MainActivity.this);
                child.setWebViewClient(new WebViewClient() {
                    @Override public boolean shouldOverrideUrlLoading(WebView childView, WebResourceRequest request) {
                        openExternal(request.getUrl());
                        childView.destroy();
                        return true;
                    }
                });
                ((WebView.WebViewTransport) result.obj).setWebView(child);
                result.sendToTarget();
                return true;
            }
        });
        root.addView(webView, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1));
        setContentView(root);
    }

    private void awaitRuntime() {
        if (destroyed) return;
        if (NodeRuntime.failure() != null) { showRecoveryStatus(NodeRuntime.failure()); return; }
        try {
            JSONObject ready = NodeRuntime.ready(this);
            if (ready != null) {
                int port = ready.getInt("port");
                token = ready.getString("token");
                origin = "http://127.0.0.1:" + port;
                getSharedPreferences("runtime", MODE_PRIVATE).edit().putInt("port", port).apply();
                CookieManager.getInstance().setCookie(origin, "gridlens_session=" + token + "; Path=/; HttpOnly; SameSite=Strict", success -> {
                    if (destroyed) return;
                    if (!success) { showStatus("Could not open the private on-phone session."); return; }
                    try {
                        discovery = new DiscoveryController(this, origin, token, this::openScenario);
                    } catch (Exception unavailable) {
                        showStatus("Native discovery storage is unavailable. Saved public fleets can still be selected in Scenario.");
                    }
                    if (BuildConfig.DEBUG && "fresh".equals(getIntent().getStringExtra("scenario"))) openScenario("fresh");
                    else if (savedUrl != null && local(Uri.parse(savedUrl))) webView.loadUrl(savedUrl);
                    else if (discovery != null) discovery.restoreSelection("network-discovery");
                    else openScenario("network-discovery");
                });
                return;
            }
        } catch (Exception ignored) { /* A partially observed startup file is retried without exposing contents. */ }
        if (android.os.SystemClock.elapsedRealtime() - startupAt > 60000) {
            showRecoveryStatus("GridLens is taking longer to start. If this continues, force stop GridLens in Android Settings, then reopen it.");
        }
        handler.postDelayed(this::awaitRuntime, 150);
    }

    private boolean local(Uri uri) {
        if (origin == null || uri == null || uri.getUserInfo() != null) return false;
        return "http".equals(uri.getScheme()) && "127.0.0.1".equals(uri.getHost()) &&
            uri.getPort() == Uri.parse(origin).getPort();
    }

    private void openExternal(Uri uri) {
        if (!"https".equals(uri.getScheme()) || uri.getHost() == null || uri.getUserInfo() != null) return;
        try { startActivity(new Intent(Intent.ACTION_VIEW, uri).addCategory(Intent.CATEGORY_BROWSABLE)); }
        catch (ActivityNotFoundException error) { Toast.makeText(this, "No browser is available to open this link.", Toast.LENGTH_LONG).show(); }
    }

    private void chooseRoster() {
        if (origin == null) { Toast.makeText(this, "GridLens is still starting.", Toast.LENGTH_SHORT).show(); return; }
        new AlertDialog.Builder(this).setTitle("Import public host addresses")
            .setItems(new String[]{"Supplied Host Addresses", "Real200"}, (dialog, which) -> {
                importScenario = which == 0 ? "supplied" : "real200";
                Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
                intent.addCategory(Intent.CATEGORY_OPENABLE);
                intent.setType("*/*");
                intent.putExtra(Intent.EXTRA_MIME_TYPES, new String[]{"application/json", "text/plain", "application/octet-stream"});
                try { startActivityForResult(intent, IMPORT_HOSTS); }
                catch (ActivityNotFoundException error) { Toast.makeText(this, "No document picker is available on this phone.", Toast.LENGTH_LONG).show(); }
            }).setNegativeButton("Cancel", null).show();
    }

    @Override protected void onActivityResult(int request, int result, Intent data) {
        super.onActivityResult(request, result, data);
        if (discovery != null && discovery.activityResult(request, result, data)) return;
        if (request != IMPORT_HOSTS || result != RESULT_OK || data == null || data.getData() == null || importScenario == null) return;
        Uri uri = data.getData();
        String scenario = importScenario;
        importScenario = null;
        showStatus("Importing host addresses…");
        io.execute(() -> {
            HttpURLConnection connection = null;
            try {
                byte[] body;
                try (InputStream input = getContentResolver().openInputStream(uri)) {
                    if (input == null) throw new java.io.IOException("Could not read this document");
                    body = NodeRuntime.readBounded(input, MAX_ROSTER_BYTES);
                }
                JSONArray array = new JSONArray(new String(body, StandardCharsets.UTF_8));
                if (array.length() > 10000) throw new java.io.IOException("Too many addresses");
                HashSet<String> unique = new HashSet<>();
                for (int i = 0; i < array.length(); i++) {
                    Object item = array.get(i);
                    if (!(item instanceof String) || !((String) item).matches("[1-9A-HJ-NP-Za-km-z]{32,44}") || !unique.add((String) item))
                        throw new java.io.IOException("Invalid or duplicate address");
                }
                connection = (HttpURLConnection) new URL(origin + "/mobile/rosters/" + scenario).openConnection();
                connection.setRequestMethod("POST");
                connection.setConnectTimeout(10000);
                connection.setReadTimeout(10000);
                connection.setInstanceFollowRedirects(false);
                connection.setRequestProperty("Content-Type", "application/json");
                connection.setRequestProperty("X-GridLens-Token", token);
                connection.setDoOutput(true);
                connection.setFixedLengthStreamingMode(body.length);
                try (java.io.OutputStream output = connection.getOutputStream()) { output.write(body); }
                if (connection.getResponseCode() != 200) throw new java.io.IOException("Roster rejected");
                int count;
                try (InputStream input = connection.getInputStream()) {
                    JSONObject response = new JSONObject(new String(NodeRuntime.readBounded(input, 8192), StandardCharsets.UTF_8));
                    if (!response.getBoolean("ok")) throw new java.io.IOException("Roster rejected");
                    count = response.getInt("count");
                }
                runOnUiThread(() -> {
                    if (destroyed) return;
                    Toast.makeText(this, "Imported " + count + " host addresses.", Toast.LENGTH_LONG).show();
                    if (discovery != null) discovery.selectScenario(scenario);
                    else openScenario(scenario);
                });
            } catch (Exception error) {
                runOnUiThread(() -> {
                    if (destroyed) return;
                    status.setVisibility(View.GONE);
                    new AlertDialog.Builder(this).setTitle("Could not import this list")
                        .setMessage("Choose a JSON array of unique public host addresses (32-byte base58), up to 10,000 addresses and 512 KB. If the connection was interrupted, check your list before retrying.")
                        .setPositiveButton("OK", null).show();
                });
            } finally { if (connection != null) connection.disconnect(); }
        });
    }

    private void openScenario(String scenario) {
        if (destroyed || origin == null) return;
        clearHistoryAfterLoad = true;
        webView.loadUrl(origin + "/?scenario=" + Uri.encode(scenario));
    }
    @Override protected void onStop() {
        if (discovery != null) discovery.stopped();
        super.onStop();
    }
    private void suspendPage() { webView.evaluateJavascript("window.dispatchEvent(new PageTransitionEvent('pagehide'));", null); }
    @Override protected void onPause() {
        paused = true;
        String currentUrl = webView.getUrl();
        if (currentUrl != null && local(Uri.parse(currentUrl))) {
            String scenario = Uri.parse(currentUrl).getQueryParameter("scenario");
            if (scenario != null && scenario.matches("[a-z0-9-]{1,48}"))
                getSharedPreferences("runtime", MODE_PRIVATE).edit().putString("scenario", scenario).apply();
        }
        suspendPage();
        webView.onPause();
        super.onPause();
    }
    @Override protected void onResume() {
        super.onResume();
        paused = false;
        if (webView != null) {
            webView.onResume();
            webView.evaluateJavascript("window.dispatchEvent(new PageTransitionEvent('pageshow'));", null);
        }
        if (updates != null) updates.onResume();
    }
    @Override protected void onSaveInstanceState(Bundle state) {
        super.onSaveInstanceState(state);
        state.putString("url", webView.getUrl());
        state.putString("importScenario", importScenario);
    }
    @Override protected void onDestroy() {
        destroyed = true;
        handler.removeCallbacksAndMessages(null);
        io.shutdownNow();
        if (updates != null) updates.destroy();
        if (discovery != null) discovery.destroy();
        webView.stopLoading();
        webView.destroy();
        super.onDestroy();
    }
    @Override public void onBackPressed() {
        if (webView.canGoBack()) webView.goBack(); else super.onBackPressed();
    }
    private int dp(int value) { return Math.round(value * getResources().getDisplayMetrics().density); }
    private void showStatus(String text) { status.setText(text); status.setVisibility(View.VISIBLE); }
    private void showRecoveryStatus(String text) { showStatus(text); recovery.setVisibility(View.VISIBLE); }
    WebView webViewForTesting() { return webView; }
    String originForTesting() { return origin; }
}
