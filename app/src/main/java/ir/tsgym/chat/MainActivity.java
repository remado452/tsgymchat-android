package ir.tsgym.chat;

import android.Manifest;
import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.Signature;
import android.net.Uri;
import android.net.http.SslCertificate;
import android.net.http.SslError;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
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

import androidx.core.content.FileProvider;

import org.json.JSONObject;

import java.io.BufferedInputStream;
import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import javax.net.ssl.HttpsURLConnection;

public final class MainActivity extends Activity {
    private static final String APP_ORIGIN = "https://tsgym.ir";
    private static final String ALLOWED_HOST = "tsgym.ir";
    private static final String UPDATE_URL = APP_ORIGIN + "/panel/tsgymchat_app/updates/latest.json";
    private static final String START_URL = APP_ORIGIN
        + "/panel/tsgymchat_app/android_entry?app_android=1"
        + "&app_version=" + BuildConfig.VERSION_NAME
        + "&version_code=" + BuildConfig.VERSION_CODE;

    private static final int FILE_CHOOSER_REQUEST = 2001;
    private static final int AUDIO_PERMISSION_REQUEST = 2002;

    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    private WebView webView;
    private View loadingOverlay;
    private TextView loadingText;
    private View errorPanel;
    private TextView errorTitle;
    private TextView errorDetails;
    private View updatePanel;
    private TextView updateVersion;
    private TextView updateNotes;
    private TextView updateStatus;
    private ProgressBar updateProgress;
    private Button updateButton;
    private Button laterButton;

    private ValueCallback<Uri[]> fileChooserCallback;
    private PermissionRequest pendingAudioRequest;
    private boolean mainFrameFailed;
    private String lastUrl = START_URL;
    private UpdateInfo pendingUpdate;
    private File downloadedUpdate;
    private String deviceId;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        webView = findViewById(R.id.webView);
        loadingOverlay = findViewById(R.id.loadingOverlay);
        loadingText = findViewById(R.id.loadingText);
        errorPanel = findViewById(R.id.errorPanel);
        errorTitle = findViewById(R.id.errorTitle);
        errorDetails = findViewById(R.id.errorDetails);
        updatePanel = findViewById(R.id.updatePanel);
        updateVersion = findViewById(R.id.updateVersion);
        updateNotes = findViewById(R.id.updateNotes);
        updateStatus = findViewById(R.id.updateStatus);
        updateProgress = findViewById(R.id.updateProgress);
        updateButton = findViewById(R.id.updateButton);
        laterButton = findViewById(R.id.laterButton);

        findViewById(R.id.retryButton).setOnClickListener(v -> checkForUpdateThenLoad());
        findViewById(R.id.browserButton).setOnClickListener(v -> openExternal(Uri.parse(lastUrl)));
        updateButton.setOnClickListener(v -> {
            if (downloadedUpdate != null && downloadedUpdate.isFile()) {
                installDownloadedApk(downloadedUpdate);
            } else if (pendingUpdate != null) {
                downloadUpdate(pendingUpdate);
            }
        });
        laterButton.setOnClickListener(v -> {
            hideUpdatePanel();
            loadStartPage();
        });

        configureWebView();
        configureDeviceIdentity();
        clearStaleWebCacheOnce();

        checkForUpdateThenLoad();
    }

    private void configureWebView() {
        WebSettings settings = webView.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setDatabaseEnabled(true);
        settings.setAllowFileAccess(false);
        settings.setAllowContentAccess(true);
        settings.setBlockNetworkLoads(false);
        settings.setLoadsImagesAutomatically(true);
        settings.setMediaPlaybackRequiresUserGesture(false);
        settings.setSupportMultipleWindows(false);
        settings.setJavaScriptCanOpenWindowsAutomatically(false);
        settings.setCacheMode(WebSettings.LOAD_DEFAULT);
        settings.setDefaultTextEncodingName("UTF-8");
        settings.setUseWideViewPort(true);
        settings.setLoadWithOverviewMode(false);
        settings.setBuiltInZoomControls(false);
        settings.setDisplayZoomControls(false);
        settings.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);
        settings.setUserAgentString(
            settings.getUserAgentString() + " TsGymChatAndroid/" + BuildConfig.VERSION_NAME
        );
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            settings.setAlgorithmicDarkeningAllowed(false);
        }

        CookieManager cookieManager = CookieManager.getInstance();
        cookieManager.setAcceptCookie(true);
        cookieManager.setAcceptThirdPartyCookies(webView, true);

        webView.setLayerType(View.LAYER_TYPE_HARDWARE, null);
        webView.setWebViewClient(new AppWebViewClient());
        webView.setWebChromeClient(new AppChromeClient());
        webView.setDownloadListener(new AppDownloadListener());
    }

    private void configureDeviceIdentity() {
        SharedPreferences preferences = getSharedPreferences("tsgymchat_android", MODE_PRIVATE);
        deviceId = preferences.getString("device_id", "");
        if (deviceId == null || !deviceId.matches("[a-f0-9\\-]{36}")) {
            deviceId = UUID.randomUUID().toString().toLowerCase(Locale.ROOT);
            preferences.edit().putString("device_id", deviceId).apply();
        }
    }

    private void clearStaleWebCacheOnce() {
        SharedPreferences preferences = getSharedPreferences("tsgymchat_android", MODE_PRIVATE);
        String previous = preferences.getString("native_version", "");
        if (!BuildConfig.VERSION_NAME.equals(previous)) {
            webView.clearCache(true);
            preferences.edit().putString("native_version", BuildConfig.VERSION_NAME).apply();
        }
    }

    private void checkForUpdateThenLoad() {
        hideError();
        hideUpdatePanel();
        showLoading("در حال بررسی نسخه و اتصال امن…");

        new Thread(() -> {
            UpdateInfo update = null;
            try {
                HttpsURLConnection connection = openSecureConnection(
                    UPDATE_URL + "?t=" + System.currentTimeMillis()
                );
                connection.setRequestProperty("Accept", "application/json");
                int status = connection.getResponseCode();
                if (status >= 200 && status < 300) {
                    String json = readText(connection.getInputStream(), 64 * 1024);
                    update = UpdateInfo.fromJson(new JSONObject(json));
                }
                connection.disconnect();
            } catch (Throwable ignored) {
                // The web app remains usable if the update endpoint is temporarily unavailable.
            }

            UpdateInfo finalUpdate = update;
            mainHandler.post(() -> {
                boolean newer = finalUpdate != null
                    && finalUpdate.enabled
                    && finalUpdate.versionCode > BuildConfig.VERSION_CODE;
                boolean unsupported = finalUpdate != null
                    && finalUpdate.enabled
                    && BuildConfig.VERSION_CODE < finalUpdate.minSupportedVersionCode;

                if (newer || unsupported) {
                    showUpdatePanel(finalUpdate, unsupported || finalUpdate.mandatory);
                } else {
                    loadStartPage();
                }
            });
        }, "tsgym-update-check").start();
    }

    private HttpsURLConnection openSecureConnection(String urlValue) throws Exception {
        URL url = new URL(urlValue);
        HttpsURLConnection connection = (HttpsURLConnection) url.openConnection();
        connection.setConnectTimeout(15000);
        connection.setReadTimeout(30000);
        connection.setInstanceFollowRedirects(true);
        connection.setRequestMethod("GET");
        connection.setRequestProperty("User-Agent", webView.getSettings().getUserAgentString());
        connection.setRequestProperty("Cache-Control", "no-cache");
        return connection;
    }

    private static String readText(InputStream stream, int maxBytes) throws Exception {
        if (stream == null) return "";
        StringBuilder result = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(
            new InputStreamReader(stream, StandardCharsets.UTF_8)
        )) {
            char[] buffer = new char[2048];
            int read;
            while ((read = reader.read(buffer)) != -1 && result.length() < maxBytes) {
                int allowed = Math.min(read, maxBytes - result.length());
                result.append(buffer, 0, allowed);
            }
        }
        return result.toString();
    }

    private void showUpdatePanel(UpdateInfo info, boolean mandatory) {
        pendingUpdate = info;
        downloadedUpdate = null;
        hideLoading();
        hideError();
        webView.setVisibility(View.GONE);
        updatePanel.setVisibility(View.VISIBLE);
        updateVersion.setText("Version " + info.versionName);
        updateNotes.setText(info.notes.isEmpty()
            ? "نسخه جدید برای افزایش امنیت و پایداری آماده است."
            : info.notes);
        updateProgress.setVisibility(View.GONE);
        updateStatus.setVisibility(View.GONE);
        updateButton.setEnabled(true);
        updateButton.setText("بروزرسانی اپلیکیشن");
        laterButton.setVisibility(mandatory ? View.GONE : View.VISIBLE);
    }

    private void hideUpdatePanel() {
        updatePanel.setVisibility(View.GONE);
    }

    private void downloadUpdate(UpdateInfo info) {
        Uri apkUri = Uri.parse(info.apkUrl);
        if (!isAllowed(apkUri) || !"https".equalsIgnoreCase(apkUri.getScheme())) {
            showUpdateFailure("آدرس بروزرسانی معتبر نیست.");
            return;
        }
        updateButton.setEnabled(false);
        laterButton.setEnabled(false);
        updateProgress.setProgress(0);
        updateProgress.setVisibility(View.VISIBLE);
        updateStatus.setVisibility(View.VISIBLE);
        updateStatus.setText("در حال دانلود بروزرسانی…");

        new Thread(() -> {
            File output = null;
            try {
                File updateDir = new File(getCacheDir(), "updates");
                if (!updateDir.exists() && !updateDir.mkdirs()) {
                    throw new IllegalStateException("Unable to create update directory");
                }
                output = new File(updateDir, "TsgymChat.apk");
                if (output.exists() && !output.delete()) {
                    throw new IllegalStateException("Unable to replace old update");
                }

                HttpsURLConnection connection = openSecureConnection(info.apkUrl);
                connection.setRequestProperty("Accept", "application/vnd.android.package-archive");
                int status = connection.getResponseCode();
                if (status < 200 || status >= 300) {
                    throw new IllegalStateException("HTTP " + status);
                }
                long total = connection.getContentLengthLong();
                long downloaded = 0;
                byte[] buffer = new byte[32 * 1024];
                try (InputStream input = new BufferedInputStream(connection.getInputStream());
                     FileOutputStream fileOutput = new FileOutputStream(output)) {
                    int read;
                    while ((read = input.read(buffer)) != -1) {
                        fileOutput.write(buffer, 0, read);
                        downloaded += read;
                        if (total > 0) {
                            int progress = (int)Math.min(100, (downloaded * 100L) / total);
                            mainHandler.post(() -> {
                                updateProgress.setProgress(progress);
                                updateStatus.setText("دانلود بروزرسانی… " + progress + "٪");
                            });
                        }
                    }
                    fileOutput.flush();
                }
                connection.disconnect();

                if (info.size > 0 && output.length() != info.size) {
                    throw new SecurityException("حجم فایل بروزرسانی با مقدار سرور یکسان نیست.");
                }
                if (!info.sha256.isEmpty()) {
                    String actualHash = sha256(output);
                    if (!actualHash.equalsIgnoreCase(info.sha256)) {
                        throw new SecurityException("امضای SHA-256 فایل بروزرسانی معتبر نیست.");
                    }
                }
                validateUpdatePackage(output, info.versionCode);

                File finalOutput = output;
                mainHandler.post(() -> {
                    downloadedUpdate = finalOutput;
                    updateProgress.setProgress(100);
                    updateStatus.setText("دانلود و بررسی امنیتی کامل شد.");
                    updateButton.setEnabled(true);
                    updateButton.setText("نصب بروزرسانی");
                    laterButton.setEnabled(true);
                    installDownloadedApk(finalOutput);
                });
            } catch (Throwable error) {
                if (output != null) output.delete();
                String message = error.getMessage() == null
                    ? error.getClass().getSimpleName()
                    : error.getMessage();
                mainHandler.post(() -> showUpdateFailure(message));
            }
        }, "tsgym-update-download").start();
    }

    private void showUpdateFailure(String message) {
        updateProgress.setVisibility(View.GONE);
        updateStatus.setVisibility(View.VISIBLE);
        updateStatus.setText("بروزرسانی انجام نشد: " + message);
        updateButton.setEnabled(true);
        laterButton.setEnabled(true);
    }

    private void validateUpdatePackage(File apkFile, int expectedVersionCode) throws Exception {
        PackageManager packageManager = getPackageManager();
        int flags = Build.VERSION.SDK_INT >= Build.VERSION_CODES.P
            ? PackageManager.GET_SIGNING_CERTIFICATES
            : PackageManager.GET_SIGNATURES;
        PackageInfo archive = packageManager.getPackageArchiveInfo(apkFile.getAbsolutePath(), flags);
        if (archive == null || archive.packageName == null) {
            throw new SecurityException("فایل دانلودشده APK معتبر نیست.");
        }
        if (!getPackageName().equals(archive.packageName)) {
            throw new SecurityException("نام بسته بروزرسانی با TsGym-Chat یکسان نیست.");
        }
        long archiveVersion = Build.VERSION.SDK_INT >= Build.VERSION_CODES.P
            ? archive.getLongVersionCode()
            : archive.versionCode;
        if (archiveVersion < expectedVersionCode || archiveVersion <= BuildConfig.VERSION_CODE) {
            throw new SecurityException("نسخه فایل بروزرسانی معتبر یا جدیدتر نیست.");
        }

        PackageInfo installed = packageManager.getPackageInfo(getPackageName(), flags);
        Set<String> installedCertificates = signingCertificateDigests(installed);
        Set<String> archiveCertificates = signingCertificateDigests(archive);
        if (installedCertificates.isEmpty()
            || archiveCertificates.isEmpty()
            || !installedCertificates.equals(archiveCertificates)) {
            throw new SecurityException("امضای دیجیتال بروزرسانی با نسخه نصب‌شده یکسان نیست.");
        }
    }

    private static Set<String> signingCertificateDigests(PackageInfo info) throws Exception {
        Signature[] signatures;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P && info.signingInfo != null) {
            signatures = info.signingInfo.hasMultipleSigners()
                ? info.signingInfo.getApkContentsSigners()
                : info.signingInfo.getSigningCertificateHistory();
        } else {
            signatures = info.signatures;
        }
        Set<String> digests = new HashSet<>();
        if (signatures == null) return digests;
        for (Signature signature : signatures) {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            digests.add(bytesToHex(digest.digest(signature.toByteArray())));
        }
        return digests;
    }

    private static String sha256(File file) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        byte[] buffer = new byte[32 * 1024];
        try (InputStream input = new FileInputStream(file)) {
            int read;
            while ((read = input.read(buffer)) != -1) {
                digest.update(buffer, 0, read);
            }
        }
        return bytesToHex(digest.digest());
    }

    private static String bytesToHex(byte[] bytes) {
        StringBuilder output = new StringBuilder(bytes.length * 2);
        for (byte value : bytes) output.append(String.format(Locale.ROOT, "%02x", value));
        return output.toString();
    }

    private void installDownloadedApk(File apkFile) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
            && !getPackageManager().canRequestPackageInstalls()) {
            updateStatus.setVisibility(View.VISIBLE);
            updateStatus.setText("ابتدا اجازه نصب برنامه از این منبع را فعال کنید، سپس دوباره روی نصب بروزرسانی بزنید.");
            Intent settingsIntent = new Intent(
                Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                Uri.parse("package:" + getPackageName())
            );
            startActivity(settingsIntent);
            return;
        }

        try {
            Uri contentUri = FileProvider.getUriForFile(
                this,
                getPackageName() + ".fileprovider",
                apkFile
            );
            Intent installIntent = new Intent(Intent.ACTION_VIEW);
            installIntent.setDataAndType(contentUri, "application/vnd.android.package-archive");
            installIntent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(installIntent);
        } catch (Throwable error) {
            showUpdateFailure("صفحه نصب Android باز نشد: " + safeMessage(error.getMessage()));
        }
    }

    private void loadStartPage() {
        mainFrameFailed = false;
        lastUrl = START_URL;
        showLoading("در حال ورود به TsGym-Chat…");
        webView.setVisibility(View.VISIBLE);
        Map<String, String> headers = new HashMap<>();
        headers.put("X-TSGYM-Device", deviceId == null ? "" : deviceId);
        webView.loadUrl(START_URL, headers);
    }

    private void showLoading(String message) {
        loadingText.setText(message);
        loadingOverlay.setVisibility(View.VISIBLE);
        hideError();
    }

    private void hideLoading() {
        loadingOverlay.setVisibility(View.GONE);
    }

    private void hideError() {
        errorPanel.setVisibility(View.GONE);
    }

    private void showError(String title, String details) {
        mainFrameFailed = true;
        hideLoading();
        errorTitle.setText(title);
        errorDetails.setText(details + "\n\nURL: " + safeMessage(lastUrl));
        errorPanel.setVisibility(View.VISIBLE);
        webView.setVisibility(View.VISIBLE);
    }

    private boolean isAllowed(Uri uri) {
        if (uri == null || !"https".equalsIgnoreCase(uri.getScheme())) return false;
        String host = uri.getHost();
        return host != null
            && (host.equalsIgnoreCase(ALLOWED_HOST)
            || host.toLowerCase(Locale.ROOT).endsWith("." + ALLOWED_HOST));
    }

    private void openExternal(Uri uri) {
        try {
            startActivity(new Intent(Intent.ACTION_VIEW, uri));
        } catch (ActivityNotFoundException error) {
            Toast.makeText(this, "برنامه‌ای برای بازکردن لینک پیدا نشد.", Toast.LENGTH_SHORT).show();
        }
    }

    private static String safeMessage(String value) {
        return value == null || value.trim().isEmpty() ? "بدون توضیح" : value.trim();
    }

    @Override
    public void onBackPressed() {
        if (updatePanel.getVisibility() == View.VISIBLE
            && pendingUpdate != null
            && (pendingUpdate.mandatory
            || BuildConfig.VERSION_CODE < pendingUpdate.minSupportedVersionCode)) {
            return;
        }
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
            if (loadingOverlay.getVisibility() != View.VISIBLE) {
                showLoading("در حال بارگذاری…");
            }
            hideError();
        }

        @Override
        public void onPageCommitVisible(WebView view, String url) {
            lastUrl = url;
            if (!mainFrameFailed) {
                hideLoading();
                hideError();
                view.setVisibility(View.VISIBLE);
            }
        }

        @Override
        public void onPageFinished(WebView view, String url) {
            lastUrl = url;
            CookieManager.getInstance().flush();
            if (mainFrameFailed) return;
            hideLoading();
            view.setVisibility(View.VISIBLE);
            view.setAlpha(1.0f);
        }

        @Override
        public void onReceivedError(WebView view, WebResourceRequest request, WebResourceError error) {
            if (!request.isForMainFrame()) return;
            lastUrl = request.getUrl().toString();
            showError(
                "خطای اتصال",
                "code=" + error.getErrorCode()
                    + "\ndescription=" + safeMessage(String.valueOf(error.getDescription()))
            );
        }

        @Override
        public void onReceivedHttpError(WebView view, WebResourceRequest request, WebResourceResponse response) {
            if (!request.isForMainFrame()) return;
            lastUrl = request.getUrl().toString();
            showError(
                "پاسخ نامعتبر سرور",
                "HTTP " + response.getStatusCode() + " " + safeMessage(response.getReasonPhrase())
            );
        }

        @Override
        public void onReceivedSslError(WebView view, SslErrorHandler handler, SslError error) {
            handler.cancel();
            lastUrl = error.getUrl();
            SslCertificate certificate = error.getCertificate();
            String certificateInfo = certificate == null ? "گواهی نامشخص" :
                "issuedTo=" + certificate.getIssuedTo().getDName()
                    + "\nissuedBy=" + certificate.getIssuedBy().getDName();
            showError(
                "خطای گواهی SSL",
                "primaryError=" + error.getPrimaryError() + "\n" + certificateInfo
            );
        }

        @Override
        public boolean onRenderProcessGone(WebView view, RenderProcessGoneDetail detail) {
            showError(
                "موتور نمایش متوقف شد",
                "didCrash=" + detail.didCrash()
                    + "\npriority=" + detail.rendererPriorityAtExit()
            );
            return true;
        }
    }

    private final class AppChromeClient extends WebChromeClient {
        @Override
        public void onProgressChanged(WebView view, int progress) {
            if (loadingOverlay.getVisibility() == View.VISIBLE && progress < 100) {
                loadingText.setText("در حال بارگذاری… " + progress + "٪");
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
                intent.putExtra(
                    Intent.EXTRA_ALLOW_MULTIPLE,
                    params.getMode() == FileChooserParams.MODE_OPEN_MULTIPLE
                );
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
                if (checkSelfPermission(Manifest.permission.RECORD_AUDIO)
                    == PackageManager.PERMISSION_GRANTED) {
                    request.grant(new String[]{PermissionRequest.RESOURCE_AUDIO_CAPTURE});
                } else {
                    if (pendingAudioRequest != null) pendingAudioRequest.deny();
                    pendingAudioRequest = request;
                    requestPermissions(
                        new String[]{Manifest.permission.RECORD_AUDIO},
                        AUDIO_PERMISSION_REQUEST
                    );
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
            else Toast.makeText(
                MainActivity.this,
                "دانلود از دامنه ناشناس مسدود شد.",
                Toast.LENGTH_SHORT
            ).show();
        }
    }

    private static final class UpdateInfo {
        final boolean enabled;
        final int versionCode;
        final String versionName;
        final int minSupportedVersionCode;
        final boolean mandatory;
        final String apkUrl;
        final String sha256;
        final long size;
        final String notes;

        UpdateInfo(
            boolean enabled,
            int versionCode,
            String versionName,
            int minSupportedVersionCode,
            boolean mandatory,
            String apkUrl,
            String sha256,
            long size,
            String notes
        ) {
            this.enabled = enabled;
            this.versionCode = versionCode;
            this.versionName = versionName;
            this.minSupportedVersionCode = minSupportedVersionCode;
            this.mandatory = mandatory;
            this.apkUrl = apkUrl;
            this.sha256 = sha256;
            this.size = size;
            this.notes = notes;
        }

        static UpdateInfo fromJson(JSONObject json) {
            return new UpdateInfo(
                json.optBoolean("enabled", false),
                json.optInt("versionCode", 0),
                json.optString("versionName", ""),
                json.optInt("minSupportedVersionCode", 0),
                json.optBoolean("mandatory", false),
                json.optString("apkUrl", ""),
                json.optString("sha256", "").trim(),
                json.optLong("size", 0),
                json.optString("notes", "").trim()
            );
        }
    }
}
