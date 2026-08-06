package ir.tsgym.chat;

import android.Manifest;
import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.net.Uri;
import android.net.http.SslCertificate;
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
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Locale;

import javax.net.ssl.HttpsURLConnection;

public final class MainActivity extends Activity {
    private static final String APP_VERSION = "4.1.0";
    private static final String START_URL = "https://tsgym.ir/panel/tsgymchat_app/app_login?app_android=1&app_version=" + APP_VERSION;
    private static final String PROBE_URL = "https://tsgym.ir/panel/tsgymchat_app/webview_probe?app_version=" + APP_VERSION;
    private static final String ALLOWED_HOST = "tsgym.ir";
    private static final String CHROME_USER_AGENT =
        "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 " +
        "(KHTML, like Gecko) Chrome/150.0.0.0 Mobile Safari/537.36";

    private static final int FILE_CHOOSER_REQUEST = 2001;
    private static final int AUDIO_PERMISSION_REQUEST = 2002;

    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private WebView webView;
    private ProgressBar progressBar;
    private View errorPanel;
    private TextView errorTitle;
    private TextView errorDetails;
    private Button retryButton;
    private Button browserButton;
    private Button rendererButton;
    private ValueCallback<Uri[]> fileChooserCallback;
    private PermissionRequest pendingAudioRequest;
    private boolean mainFrameFailed = false;
    private boolean softwareRenderer = true;
    private String preflightResult = "Preflight اجرا نشده";
    private String lastUrl = START_URL;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        webView = findViewById(R.id.webView);
        progressBar = findViewById(R.id.progressBar);
        errorPanel = findViewById(R.id.errorPanel);
        errorTitle = findViewById(R.id.errorTitle);
        errorDetails = findViewById(R.id.errorDetails);
        retryButton = findViewById(R.id.retryButton);
        browserButton = findViewById(R.id.browserButton);
        rendererButton = findViewById(R.id.rendererButton);

        retryButton.setOnClickListener(v -> startFreshLoad());
        browserButton.setOnClickListener(v -> openExternal(Uri.parse(lastUrl == null ? START_URL : lastUrl)));
        rendererButton.setOnClickListener(v -> {
            softwareRenderer = !softwareRenderer;
            applyRenderer();
            Toast.makeText(this,
                softwareRenderer ? "نمایش نرم‌افزاری فعال شد" : "نمایش سخت‌افزاری فعال شد",
                Toast.LENGTH_SHORT).show();
            webView.reload();
        });

        configureWebView();
        clearStaleStateOnce();

        if (savedInstanceState == null) {
            runPreflightAndLoad();
        } else {
            webView.restoreState(savedInstanceState);
        }
    }

    private void configureWebView() {
        WebSettings s = webView.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setDatabaseEnabled(true);
        s.setAllowFileAccess(false);
        s.setAllowContentAccess(true);
        s.setBlockNetworkLoads(false);
        s.setLoadsImagesAutomatically(true);
        s.setMediaPlaybackRequiresUserGesture(false);
        s.setSupportMultipleWindows(false);
        s.setJavaScriptCanOpenWindowsAutomatically(false);
        s.setCacheMode(WebSettings.LOAD_NO_CACHE);
        s.setDefaultTextEncodingName("UTF-8");
        s.setUseWideViewPort(true);
        s.setLoadWithOverviewMode(false);
        s.setBuiltInZoomControls(false);
        s.setDisplayZoomControls(false);
        s.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);
        s.setUserAgentString(CHROME_USER_AGENT);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            s.setAlgorithmicDarkeningAllowed(false);
        }

        CookieManager cookies = CookieManager.getInstance();
        cookies.setAcceptCookie(true);
        cookies.setAcceptThirdPartyCookies(webView, true);

        webView.setBackgroundColor(Color.rgb(238, 243, 251));
        applyRenderer();
        webView.setWebViewClient(new AppWebViewClient());
        webView.setWebChromeClient(new AppChromeClient());
        webView.setDownloadListener(new AppDownloadListener());
    }

    private void applyRenderer() {
        webView.setLayerType(
            softwareRenderer ? View.LAYER_TYPE_SOFTWARE : View.LAYER_TYPE_HARDWARE,
            null
        );
    }

    private void clearStaleStateOnce() {
        SharedPreferences preferences = getSharedPreferences("tsgymchat_android", MODE_PRIVATE);
        String previous = preferences.getString("native_version", "");
        if (!APP_VERSION.equals(previous)) {
            webView.clearCache(true);
            webView.clearHistory();
            CookieManager.getInstance().removeAllCookies(value -> CookieManager.getInstance().flush());
            preferences.edit().putString("native_version", APP_VERSION).apply();
        }
    }

    private void runPreflightAndLoad() {
        showLoading();
        new Thread(() -> {
            String result;
            try {
                URL url = new URL(PROBE_URL + "&t=" + System.currentTimeMillis());
                HttpsURLConnection connection = (HttpsURLConnection) url.openConnection();
                connection.setConnectTimeout(15000);
                connection.setReadTimeout(15000);
                connection.setInstanceFollowRedirects(true);
                connection.setRequestMethod("GET");
                connection.setRequestProperty("User-Agent", CHROME_USER_AGENT);
                connection.setRequestProperty("Accept", "application/json,text/plain,*/*");
                int status = connection.getResponseCode();
                InputStream stream = status >= 400 ? connection.getErrorStream() : connection.getInputStream();
                String body = readSmallBody(stream);
                String contentType = connection.getContentType();
                result = "HTTP " + status + " | " + (contentType == null ? "بدون Content-Type" : contentType)
                    + " | body=" + body.length();
                connection.disconnect();
            } catch (Throwable error) {
                result = error.getClass().getSimpleName() + ": " + safeMessage(error.getMessage());
            }
            final String finalResult = result;
            mainHandler.post(() -> {
                preflightResult = finalResult;
                loadStartPage();
            });
        }).start();
    }

    private static String readSmallBody(InputStream stream) throws Exception {
        if (stream == null) return "";
        StringBuilder output = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8))) {
            char[] buffer = new char[1024];
            int read;
            while ((read = reader.read(buffer)) != -1 && output.length() < 8192) {
                output.append(buffer, 0, read);
            }
        }
        return output.toString();
    }

    private void startFreshLoad() {
        mainFrameFailed = false;
        hideError();
        webView.stopLoading();
        webView.clearCache(true);
        runPreflightAndLoad();
    }

    private void loadStartPage() {
        mainFrameFailed = false;
        lastUrl = START_URL;
        showLoading();
        webView.loadUrl(START_URL);
    }

    private void showLoading() {
        progressBar.setVisibility(View.VISIBLE);
        webView.setVisibility(View.VISIBLE);
        hideError();
    }

    private void hideError() {
        errorPanel.setVisibility(View.GONE);
    }

    private void showError(String title, String details) {
        mainFrameFailed = true;
        progressBar.setVisibility(View.GONE);
        errorTitle.setText(title);
        errorDetails.setText(
            details +
            "\n\nPreflight: " + preflightResult +
            "\nWebView: " + getWebViewInfo() +
            "\nRenderer: " + (softwareRenderer ? "software" : "hardware") +
            "\nURL: " + safeMessage(lastUrl)
        );
        errorPanel.setVisibility(View.VISIBLE);
        webView.setVisibility(View.VISIBLE);
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

    private static String safeMessage(String value) {
        return value == null || value.trim().isEmpty() ? "بدون توضیح" : value.trim();
    }

    private boolean isAllowed(Uri uri) {
        if (uri == null || !"https".equalsIgnoreCase(uri.getScheme())) return false;
        String host = uri.getHost();
        return host != null && (host.equalsIgnoreCase(ALLOWED_HOST) || host.endsWith("." + ALLOWED_HOST));
    }

    private void openExternal(Uri uri) {
        try {
            startActivity(new Intent(Intent.ACTION_VIEW, uri));
        } catch (ActivityNotFoundException error) {
            Toast.makeText(this, "برنامه‌ای برای بازکردن لینک پیدا نشد.", Toast.LENGTH_SHORT).show();
        }
    }

    @Override
    public void onBackPressed() {
        if (errorPanel.getVisibility() == View.VISIBLE) {
            hideError();
            return;
        }
        if (webView.canGoBack()) webView.goBack();
        else super.onBackPressed();
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (webView != null) {
            webView.onResume();
            webView.resumeTimers();
        }
    }

    @Override
    protected void onPause() {
        if (webView != null) {
            webView.onPause();
            webView.pauseTimers();
        }
        super.onPause();
    }

    @Override
    protected void onSaveInstanceState(Bundle outState) {
        webView.saveState(outState);
        super.onSaveInstanceState(outState);
    }

    @Override
    protected void onDestroy() {
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
                for (int index = 0; index < count; index++) {
                    results[index] = data.getClipData().getItemAt(index).getUri();
                }
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
        }
        pendingAudioRequest = null;
    }

    private final class AppWebViewClient extends WebViewClient {
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
            lastUrl = url;
            mainFrameFailed = false;
            progressBar.setVisibility(View.VISIBLE);
            hideError();
        }

        @Override
        public void onPageCommitVisible(WebView view, String url) {
            lastUrl = url;
            if (!mainFrameFailed) {
                webView.setVisibility(View.VISIBLE);
                hideError();
            }
        }

        @Override
        public void onPageFinished(WebView view, String url) {
            lastUrl = url;
            CookieManager.getInstance().flush();
            if (mainFrameFailed) return;
            progressBar.setVisibility(View.GONE);

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                view.postVisualStateCallback(System.currentTimeMillis(), requestId -> {
                    if (!mainFrameFailed) {
                        view.setVisibility(View.VISIBLE);
                        view.invalidate();
                    }
                });
            } else {
                view.setVisibility(View.VISIBLE);
                view.invalidate();
            }

            final String finishedUrl = url;
            mainHandler.postDelayed(() -> {
                if (mainFrameFailed || !finishedUrl.equals(lastUrl)) return;
                view.evaluateJavascript(
                    "(function(){var b=document.body;return JSON.stringify({ready:document.readyState,html:b?(b.innerHTML||'').length:0,text:b?(b.innerText||'').length:0,title:document.title||''});})()",
                    value -> {
                        if (mainFrameFailed || !finishedUrl.equals(lastUrl)) return;
                        if (value == null || value.contains("\\\"html\\\":0")) {
                            showError(
                                "پاسخ HTML خالی دریافت شد",
                                "WebView درخواست را تمام کرد اما body صفحه خالی بود. این معمولاً از فایروال، محدودیت User-Agent یا پاسخ متفاوت سرور به WebView است.\nJS=" + safeMessage(value)
                            );
                        }
                    }
                );
            }, 1800);
        }

        @Override
        public void onReceivedError(WebView view, WebResourceRequest request, WebResourceError error) {
            if (!request.isForMainFrame()) return;
            lastUrl = request.getUrl().toString();
            showError(
                "خطای شبکه WebView",
                "code=" + error.getErrorCode() + "\ndescription=" + safeMessage(String.valueOf(error.getDescription()))
            );
        }

        @Override
        public void onReceivedHttpError(WebView view, WebResourceRequest request, WebResourceResponse response) {
            if (!request.isForMainFrame()) return;
            lastUrl = request.getUrl().toString();
            showError(
                "پاسخ نامعتبر سرور",
                "HTTP " + response.getStatusCode() + " " + safeMessage(response.getReasonPhrase())
                    + "\nMIME=" + safeMessage(response.getMimeType())
            );
        }

        @Override
        public void onReceivedSslError(WebView view, SslErrorHandler handler, SslError error) {
            handler.cancel();
            lastUrl = error.getUrl();
            SslCertificate certificate = error.getCertificate();
            String certificateInfo = certificate == null ? "گواهی نامشخص" :
                "issuedTo=" + certificate.getIssuedTo().getDName() +
                "\nissuedBy=" + certificate.getIssuedBy().getDName();
            showError(
                "خطای گواهی SSL",
                "primaryError=" + error.getPrimaryError() + "\n" + certificateInfo
            );
        }

        @Override
        public boolean onRenderProcessGone(WebView view, RenderProcessGoneDetail detail) {
            showError(
                "موتور نمایش متوقف شد",
                "didCrash=" + detail.didCrash() + "\npriority=" + detail.rendererPriorityAtExit()
            );
            return true;
        }
    }

    private final class AppChromeClient extends WebChromeClient {
        @Override
        public void onProgressChanged(WebView view, int progress) {
            progressBar.setProgress(progress);
            if (!mainFrameFailed) {
                progressBar.setVisibility(progress >= 100 ? View.GONE : View.VISIBLE);
            }
        }

        @Override
        public boolean onConsoleMessage(ConsoleMessage message) {
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
            } catch (ActivityNotFoundException error) {
                fileChooserCallback = null;
                return false;
            }
        }

        @Override
        public void onPermissionRequest(PermissionRequest request) {
            runOnUiThread(() -> {
                boolean trusted = isAllowed(request.getOrigin());
                boolean audioOnly = Arrays.equals(
                    request.getResources(),
                    new String[]{PermissionRequest.RESOURCE_AUDIO_CAPTURE}
                );
                if (!trusted || !audioOnly) {
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

    private final class AppDownloadListener implements DownloadListener {
        @Override
        public void onDownloadStart(
            String url,
            String userAgent,
            String contentDisposition,
            String mimeType,
            long contentLength
        ) {
            Uri uri = Uri.parse(url);
            if (isAllowed(uri)) openExternal(uri);
            else Toast.makeText(MainActivity.this, "دانلود از دامنه ناشناس مسدود شد.", Toast.LENGTH_SHORT).show();
        }
    }
}
