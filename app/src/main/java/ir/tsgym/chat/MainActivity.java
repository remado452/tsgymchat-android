package ir.tsgym.chat;

import android.Manifest;
import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.net.http.SslError;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.webkit.ConsoleMessage;
import android.webkit.CookieManager;
import android.webkit.DownloadListener;
import android.webkit.PermissionRequest;
import android.webkit.RenderProcessGoneDetail;
import android.webkit.SslErrorHandler;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;

public final class MainActivity extends Activity {
    private static final String START_URL = "https://tsgym.ir/panel/tsgymchat_app/?app_android=1&app_version=3.3.0";
    private static final String ALLOWED_HOST = "tsgym.ir";
    private static final String APP_WEB_VERSION = "3.3.0";
    private static final int FILE_CHOOSER_REQUEST = 2001;
    private static final int AUDIO_PERMISSION_REQUEST = 2002;
    private static final long PAGE_TIMEOUT_MS = 30000L;

    private WebView webView;
    private LinearLayout loadingPanel;
    private LinearLayout errorPanel;
    private TextView loadingMessage;
    private TextView errorMessage;
    private ValueCallback<Uri[]> fileChooserCallback;
    private PermissionRequest pendingAudioRequest;
    private boolean blankReloadAttempted = false;
    private boolean renderProcessGone = false;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable pageTimeout = () -> showError(
        "بارگذاری صفحه بیش از حد طول کشید. اینترنت، Android System WebView و Google Chrome را بررسی کنید."
    );

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        webView = findViewById(R.id.webView);
        loadingPanel = findViewById(R.id.loadingPanel);
        loadingMessage = findViewById(R.id.loadingMessage);
        errorPanel = findViewById(R.id.errorPanel);
        errorMessage = findViewById(R.id.errorMessage);
        Button retryButton = findViewById(R.id.retryButton);
        retryButton.setOnClickListener(v -> {
            if (renderProcessGone) {
                recreate();
                return;
            }
            blankReloadAttempted = false;
            webView.clearCache(true);
            loadStartPage();
        });

        configureWebView();
        clearOldWebCacheWhenVersionChanges();

        if (savedInstanceState == null) {
            loadStartPage();
        } else {
            showLoading("در حال بازیابی tsgymChat…");
            webView.restoreState(savedInstanceState);
        }
    }

    private void configureWebView() {
        WebView.setWebContentsDebuggingEnabled(BuildConfig.DEBUG);

        WebSettings settings = webView.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setDatabaseEnabled(false);
        settings.setAllowFileAccess(false);
        settings.setAllowContentAccess(true);
        settings.setSupportMultipleWindows(false);
        settings.setJavaScriptCanOpenWindowsAutomatically(false);
        settings.setMediaPlaybackRequiresUserGesture(false);
        settings.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);
        settings.setCacheMode(WebSettings.LOAD_NO_CACHE);
        settings.setDefaultTextEncodingName("UTF-8");
        settings.setLoadsImagesAutomatically(true);
        settings.setBlockNetworkImage(false);
        settings.setUseWideViewPort(true);
        settings.setLoadWithOverviewMode(false);
        settings.setBuiltInZoomControls(false);
        settings.setDisplayZoomControls(false);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) settings.setOffscreenPreRaster(true);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) settings.setSafeBrowsingEnabled(true);
        settings.setUserAgentString(settings.getUserAgentString() + " tsgymChatAndroid/3.3");

        // Hardware rendering is intentionally left enabled. Forcing a software
        // WebView layer caused blank pages on some Samsung devices.
        webView.setLayerType(View.LAYER_TYPE_HARDWARE, null);

        CookieManager cookies = CookieManager.getInstance();
        cookies.setAcceptCookie(true);
        cookies.setAcceptThirdPartyCookies(webView, false);

        webView.setBackgroundColor(getColorCompat(R.color.app_background));
        webView.setWebViewClient(new SecureWebViewClient());
        webView.setWebChromeClient(new SecureChromeClient());
        webView.setDownloadListener(new ExternalDownloadListener());
    }

    private void clearOldWebCacheWhenVersionChanges() {
        SharedPreferences preferences = getSharedPreferences("tsgymchat_app", MODE_PRIVATE);
        String previous = preferences.getString("web_version", "");
        if (!APP_WEB_VERSION.equals(previous)) {
            webView.clearCache(true);
            CookieManager.getInstance().flush();
            preferences.edit().putString("web_version", APP_WEB_VERSION).apply();
        }
    }

    private Map<String, String> requestHeaders() {
        Map<String, String> headers = new HashMap<>();
        headers.put("X-TSGYM-App", "android");
        headers.put("Cache-Control", "no-cache");
        return headers;
    }

    private int getColorCompat(int colorRes) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) return getColor(colorRes);
        return getResources().getColor(colorRes);
    }

    private void loadStartPage() {
        renderProcessGone = false;
        showLoading("در حال اتصال امن به TSGYM…");
        webView.loadUrl(START_URL, requestHeaders());
    }

    private boolean isAllowed(Uri uri) {
        if (uri == null || !"https".equalsIgnoreCase(uri.getScheme())) return false;
        String host = uri.getHost();
        return host != null && (host.equalsIgnoreCase(ALLOWED_HOST) || host.endsWith("." + ALLOWED_HOST));
    }

    private void openExternal(Uri uri) {
        try {
            startActivity(new Intent(Intent.ACTION_VIEW, uri));
        } catch (ActivityNotFoundException e) {
            Toast.makeText(this, "برنامه‌ای برای بازکردن این لینک پیدا نشد.", Toast.LENGTH_SHORT).show();
        }
    }

    private String getWebViewInfo() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                PackageInfo info = WebView.getCurrentWebViewPackage();
                if (info != null) return info.packageName + " / " + info.versionName;
            }
        } catch (Throwable ignored) { }
        return "نامشخص";
    }

    private void startTimeout() {
        handler.removeCallbacks(pageTimeout);
        handler.postDelayed(pageTimeout, PAGE_TIMEOUT_MS);
    }

    private void stopTimeout() {
        handler.removeCallbacks(pageTimeout);
    }

    private void showLoading(String message) {
        loadingMessage.setText(message);
        loadingPanel.setVisibility(View.VISIBLE);
        errorPanel.setVisibility(View.GONE);
        webView.setVisibility(View.VISIBLE);
        startTimeout();
    }

    private void hideLoading() {
        stopTimeout();
        loadingPanel.setVisibility(View.GONE);
    }

    private void showError(String message) {
        stopTimeout();
        loadingPanel.setVisibility(View.GONE);
        errorMessage.setText(message + "\n\nWebView: " + getWebViewInfo());
        errorPanel.setVisibility(View.VISIBLE);
        webView.setVisibility(View.INVISIBLE);
    }

    private void showWebContent() {
        hideLoading();
        errorPanel.setVisibility(View.GONE);
        webView.setVisibility(View.VISIBLE);
    }

    @Override
    public void onBackPressed() {
        if (errorPanel.getVisibility() == View.VISIBLE) {
            loadStartPage();
        } else if (webView.canGoBack()) {
            webView.goBack();
        } else {
            super.onBackPressed();
        }
    }

    @Override
    protected void onSaveInstanceState(Bundle outState) {
        webView.saveState(outState);
        super.onSaveInstanceState(outState);
    }

    @Override
    protected void onDestroy() {
        stopTimeout();
        if (pendingAudioRequest != null) pendingAudioRequest.deny();
        if (fileChooserCallback != null) fileChooserCallback.onReceiveValue(null);
        if (webView != null) {
            webView.stopLoading();
            webView.setWebChromeClient(null);
            webView.setWebViewClient(null);
            webView.destroy();
        }
        super.onDestroy();
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != FILE_CHOOSER_REQUEST || fileChooserCallback == null) return;
        Uri[] results = null;
        if (resultCode == RESULT_OK && data != null) {
            if (data.getClipData() != null) {
                int count = data.getClipData().getItemCount();
                results = new Uri[count];
                for (int i = 0; i < count; i++) results[i] = data.getClipData().getItemAt(i).getUri();
            } else if (data.getData() != null) {
                results = new Uri[]{data.getData()};
            }
        }
        fileChooserCallback.onReceiveValue(results);
        fileChooserCallback = null;
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode != AUDIO_PERMISSION_REQUEST || pendingAudioRequest == null) return;
        if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            pendingAudioRequest.grant(new String[]{PermissionRequest.RESOURCE_AUDIO_CAPTURE});
        } else {
            pendingAudioRequest.deny();
            Toast.makeText(this, "برای ارسال Voice دسترسی میکروفن لازم است.", Toast.LENGTH_LONG).show();
        }
        pendingAudioRequest = null;
    }

    private final class SecureWebViewClient extends WebViewClient {
        @Override
        public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
            Uri uri = request.getUrl();
            if (isAllowed(uri)) return false;
            openExternal(uri);
            return true;
        }

        @Override
        public boolean shouldOverrideUrlLoading(WebView view, String url) {
            Uri uri = Uri.parse(url);
            if (isAllowed(uri)) return false;
            openExternal(uri);
            return true;
        }

        @Override
        public void onPageStarted(WebView view, String url, android.graphics.Bitmap favicon) {
            showLoading("در حال بارگذاری صفحه…");
        }

        @Override
        public void onPageCommitVisible(WebView view, String url) {
            loadingMessage.setText("در حال آماده‌سازی صفحه…");
        }

        @Override
        public void onPageFinished(WebView view, String url) {
            CookieManager.getInstance().flush();
            view.evaluateJavascript(
                "(function(){try{var b=document.body;var h=document.documentElement;return JSON.stringify({ready:document.readyState,text:b?(b.innerText||'').length:0,html:b?(b.innerHTML||'').length:0,w:h?h.scrollWidth:0,h:h?h.scrollHeight:0,title:document.title||''});}catch(e){return JSON.stringify({error:String(e)});}})()",
                value -> {
                    boolean empty = value == null ||
                        (value.contains("\\\"text\\\":0") && value.contains("\\\"html\\\":0"));
                    if (empty) {
                        if (!blankReloadAttempted) {
                            blankReloadAttempted = true;
                            webView.clearCache(true);
                            webView.loadUrl(url, requestHeaders());
                        } else {
                            showError("صفحه از سرور دریافت شد اما محتوایی نمایش داده نشد. Android System WebView و Google Chrome را به‌روزرسانی کنید.");
                        }
                    } else {
                        blankReloadAttempted = false;
                        showWebContent();
                    }
                }
            );
        }

        @Override
        public void onReceivedError(WebView view, WebResourceRequest request, WebResourceError error) {
            if (request.isForMainFrame()) {
                showError("ارتباط با سرور برقرار نشد. اینترنت را بررسی کنید. کد خطا: " + error.getErrorCode());
            }
        }

        @Override
        public void onReceivedHttpError(WebView view, WebResourceRequest request, WebResourceResponse errorResponse) {
            if (request.isForMainFrame() && errorResponse.getStatusCode() >= 400) {
                showError("سرور پاسخ HTTP " + errorResponse.getStatusCode() + " برگرداند.");
            }
        }

        @Override
        public void onReceivedSslError(WebView view, SslErrorHandler handler, SslError error) {
            handler.cancel();
            showError("گواهی امنیتی سرور معتبر تشخیص داده نشد. اتصال متوقف شد.");
        }

        @Override
        public boolean onRenderProcessGone(WebView view, RenderProcessGoneDetail detail) {
            renderProcessGone = true;
            showError("موتور نمایش Android متوقف شد. روی تلاش دوباره بزنید. اگر تکرار شد Android System WebView و Chrome را به‌روزرسانی کنید.");
            return true;
        }
    }

    private final class SecureChromeClient extends WebChromeClient {
        @Override
        public void onProgressChanged(WebView view, int newProgress) {
            if (newProgress < 100 && loadingPanel.getVisibility() == View.VISIBLE) {
                loadingMessage.setText("در حال بارگذاری… " + newProgress + "٪");
            }
        }

        @Override
        public boolean onConsoleMessage(ConsoleMessage consoleMessage) {
            return true;
        }

        @Override
        public boolean onShowFileChooser(WebView webView, ValueCallback<Uri[]> callback, FileChooserParams params) {
            if (fileChooserCallback != null) fileChooserCallback.onReceiveValue(null);
            fileChooserCallback = callback;
            try {
                Intent intent = params.createIntent();
                intent.addCategory(Intent.CATEGORY_OPENABLE);
                intent.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, params.getMode() == FileChooserParams.MODE_OPEN_MULTIPLE);
                startActivityForResult(intent, FILE_CHOOSER_REQUEST);
                return true;
            } catch (ActivityNotFoundException e) {
                fileChooserCallback = null;
                Toast.makeText(MainActivity.this, "فایل‌گیر روی گوشی در دسترس نیست.", Toast.LENGTH_SHORT).show();
                return false;
            }
        }

        @Override
        public void onPermissionRequest(PermissionRequest request) {
            runOnUiThread(() -> {
                Uri origin = request.getOrigin();
                boolean trustedOrigin = isAllowed(origin);
                boolean audioOnly = Arrays.equals(request.getResources(), new String[]{PermissionRequest.RESOURCE_AUDIO_CAPTURE});
                if (!trustedOrigin || !audioOnly) {
                    request.deny();
                    return;
                }
                if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
                    request.grant(new String[]{PermissionRequest.RESOURCE_AUDIO_CAPTURE});
                } else {
                    if (pendingAudioRequest != null) pendingAudioRequest.deny();
                    pendingAudioRequest = request;
                    requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, AUDIO_PERMISSION_REQUEST);
                }
            });
        }
    }

    private final class ExternalDownloadListener implements DownloadListener {
        @Override
        public void onDownloadStart(String url, String userAgent, String contentDisposition, String mimeType, long contentLength) {
            Uri uri = Uri.parse(url);
            if (isAllowed(uri)) openExternal(uri);
            else Toast.makeText(MainActivity.this, "دانلود از دامنه ناشناس مسدود شد.", Toast.LENGTH_SHORT).show();
        }
    }
}
