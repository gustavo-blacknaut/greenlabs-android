package me.blacknaut.greenlabs;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.pm.PackageManager;
import android.media.projection.MediaProjectionManager;
import android.os.Build;
import android.os.Bundle;
import android.os.IBinder;
import android.view.View;
import android.view.WindowManager;
import android.webkit.JavascriptInterface;
import android.webkit.PermissionRequest;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Toast;

import androidx.activity.OnBackPressedCallback;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import androidx.core.view.WindowCompat;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

public class MainActivity extends AppCompatActivity {

    private static final int REQ_PERMISSIONS = 1001;
    private static final int REQ_NOTIFICATIONS = 1002;

    private AssetHttpServer server;
    private WebView webView;

    private MediaProjectionManager projectionManager;
    private ScreenCaptureService screenService;
    private boolean screenServiceBound;
    private int pendingWidth = 1280;
    private int pendingHeight = 720;
    private int pendingFps = 15;
    private int pendingResultCode;
    private Intent pendingResultData;

    private final ActivityResultLauncher<Intent> screenCaptureLauncher = registerForActivityResult(
            new ActivityResultContracts.StartActivityForResult(), this::onScreenCaptureResult);

    private final ServiceConnection screenConnection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder binder) {
            screenService = ((ScreenCaptureService.LocalBinder) binder).getService();
            screenServiceBound = true;
            ScreenCaptureService.callback = new ScreenCaptureService.Callback() {
                @Override
                public void onReady(int port) {
                    runOnUiThread(() -> evalJs("window.__glScreenReady && window.__glScreenReady(" + port + ")"));
                }

                @Override
                public void onError(String message) {
                    runOnUiThread(() -> evalJs("window.__glScreenError && window.__glScreenError(" + jsString(message) + ")"));
                }

                @Override
                public void onStopped() {
                    runOnUiThread(() -> evalJs("window.__glScreenStopped && window.__glScreenStopped()"));
                }
            };
            screenService.startCapture(pendingResultCode, pendingResultData, pendingWidth, pendingHeight, pendingFps);
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            screenService = null;
            screenServiceBound = false;
        }
    };

    @SuppressLint("SetJavaScriptEnabled")
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        WindowCompat.setDecorFitsSystemWindows(getWindow(), true);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        projectionManager = (MediaProjectionManager) getSystemService(Context.MEDIA_PROJECTION_SERVICE);

        webView = new WebView(this);
        webView.setBackgroundColor(0xFF05070A);
        setContentView(webView);

        WebSettings s = webView.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setMediaPlaybackRequiresUserGesture(false);
        s.setUseWideViewPort(true);
        s.setLoadWithOverviewMode(true);
        s.setAllowFileAccess(false);
        s.setAllowContentAccess(false);
        s.setSupportZoom(false);
        s.setTextZoom(100);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            s.setSafeBrowsingEnabled(false);
        }

        webView.addJavascriptInterface(new ScreenBridge(), "greenlabsMobile");

        webView.setWebViewClient(new WebViewClient());
        webView.setWebChromeClient(new WebChromeClient() {
            @Override
            public void onPermissionRequest(PermissionRequest request) {
                // The page only ever asks for capture devices; anything else is denied.
                List<String> allowed = new ArrayList<>();
                for (String r : request.getResources()) {
                    if (PermissionRequest.RESOURCE_VIDEO_CAPTURE.equals(r)
                            || PermissionRequest.RESOURCE_AUDIO_CAPTURE.equals(r)) {
                        allowed.add(r);
                    }
                }
                if (allowed.isEmpty()) {
                    request.deny();
                } else {
                    request.grant(allowed.toArray(new String[0]));
                }
            }
        });

        getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(true) {
            @Override
            public void handleOnBackPressed() {
                if (webView.canGoBack()) {
                    webView.goBack();
                } else {
                    finish();
                }
            }
        });

        requestCapturePermissions();
        startServerAndLoad();
    }

    private void requestCapturePermissions() {
        List<String> missing = new ArrayList<>();
        String[] wanted = { Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO };
        for (String p : wanted) {
            if (ContextCompat.checkSelfPermission(this, p) != PackageManager.PERMISSION_GRANTED) {
                missing.add(p);
            }
        }
        if (!missing.isEmpty()) {
            ActivityCompat.requestPermissions(this, missing.toArray(new String[0]), REQ_PERMISSIONS);
        }
    }

    private void startServerAndLoad() {
        try {
            server = new AssetHttpServer(getAssets());
            String base = server.start();
            webView.loadUrl(base + "/index.html");
        } catch (IOException e) {
            Toast.makeText(this, "Falha ao iniciar: " + e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    private void onScreenCaptureResult(androidx.activity.result.ActivityResult result) {
        if (result.getResultCode() != Activity.RESULT_OK || result.getData() == null) {
            evalJs("window.__glScreenError && window.__glScreenError('Permissão negada')");
            return;
        }
        pendingResultCode = result.getResultCode();
        pendingResultData = result.getData();
        Intent svc = new Intent(this, ScreenCaptureService.class);
        ContextCompat.startForegroundService(this, svc);
        bindService(svc, screenConnection, Context.BIND_AUTO_CREATE);
    }

    private void stopScreenShare() {
        if (screenServiceBound) {
            try { if (screenService != null) screenService.stopCapture(); } catch (Exception ignored) { }
            try { unbindService(screenConnection); } catch (Exception ignored) { }
            screenServiceBound = false;
        }
        ScreenCaptureService.callback = null;
        stopService(new Intent(this, ScreenCaptureService.class));
    }

    private void evalJs(String script) {
        if (webView != null) webView.evaluateJavascript(script, null);
    }

    private static String jsString(String s) {
        if (s == null) return "null";
        return "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", " ") + "\"";
    }

    /**
     * Exposed to the web client as window.greenlabsMobile. Mirrors the shape of
     * window.greenlabsApp on desktop (Electron), so the JS side can gate mobile-only
     * behaviour the same way it already gates Electron-only behaviour.
     */
    private class ScreenBridge {
        @JavascriptInterface
        public boolean isAvailable() {
            return true;
        }

        @JavascriptInterface
        public void requestScreenCapture(int width, int height, int fps) {
            pendingWidth = Math.max(320, Math.min(width, 1280));
            pendingHeight = Math.max(240, Math.min(height, 1280));
            pendingFps = Math.max(5, Math.min(fps, 20));
            runOnUiThread(() -> {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
                        && ContextCompat.checkSelfPermission(MainActivity.this, Manifest.permission.POST_NOTIFICATIONS)
                        != PackageManager.PERMISSION_GRANTED) {
                    ActivityCompat.requestPermissions(MainActivity.this,
                            new String[]{ Manifest.permission.POST_NOTIFICATIONS }, REQ_NOTIFICATIONS);
                }
                screenCaptureLauncher.launch(projectionManager.createScreenCaptureIntent());
            });
        }

        @JavascriptInterface
        public void stopScreenCapture() {
            runOnUiThread(MainActivity.this::stopScreenShare);
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] results) {
        super.onRequestPermissionsResult(requestCode, permissions, results);
        if (requestCode == REQ_NOTIFICATIONS) return; // non-fatal: only affects the ongoing notification
        if (requestCode != REQ_PERMISSIONS) return;
        for (int i = 0; i < permissions.length; i++) {
            if (results[i] != PackageManager.PERMISSION_GRANTED) {
                Toast.makeText(this,
                        "Sem permissão de câmera/microfone você só consegue assistir.",
                        Toast.LENGTH_LONG).show();
                return;
            }
        }
    }

    @Override
    protected void onDestroy() {
        stopScreenShare();
        if (server != null) server.stop();
        if (webView != null) {
            webView.loadUrl("about:blank");
            webView.destroy();
        }
        super.onDestroy();
    }
}
