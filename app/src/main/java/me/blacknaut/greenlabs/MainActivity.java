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
import android.widget.FrameLayout;
import android.widget.Toast;

import androidx.activity.OnBackPressedCallback;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.core.view.WindowInsetsControllerCompat;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

public class MainActivity extends AppCompatActivity {

    private static final int REQ_PERMISSIONS = 1001;
    private static final int REQ_NOTIFICATIONS = 1002;

    private AssetHttpServer server;
    private WebView webView;
    private FrameLayout rootContainer;
    private Insets lastInsets;
    private View customView;
    private WebChromeClient.CustomViewCallback customViewCallback;

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

                @Override
                public void onLeaveCallRequested() {
                    runOnUiThread(() -> {
                        evalJs("window.__glScreenStopped && window.__glScreenStopped()");
                        evalJs("window.__glLeaveCall && window.__glLeaveCall()");
                        if (screenServiceBound) {
                            try { unbindService(screenConnection); } catch (Exception ignored) { }
                            screenServiceBound = false;
                        }
                    });
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
        // Apps targeting SDK 35+ get edge-to-edge forced by the platform regardless
        // of setDecorFitsSystemWindows(true) - that call is simply a no-op now. So
        // instead of fighting it, this embraces it: content draws under the system
        // bars, and the WebView gets padded by their real size via the insets
        // listener below, so it starts below the status bar exactly like it would
        // have with the old (now-ignored) non-edge-to-edge behaviour.
        WindowCompat.setDecorFitsSystemWindows(getWindow(), false);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        projectionManager = (MediaProjectionManager) getSystemService(Context.MEDIA_PROJECTION_SERVICE);

        webView = new WebView(this);
        webView.setBackgroundColor(0xFF05070A);

        rootContainer = new FrameLayout(this);
        rootContainer.setBackgroundColor(0xFF05070A);
        rootContainer.addView(webView, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));
        setContentView(rootContainer);

        // Handing the insets to CSS instead of padding the WebView: padding on
        // the view doesn't reliably shrink what the page sees as 100dvh, so the
        // layout kept running under the system bars even with the padding set.
        // As CSS variables the page can place them exactly where they belong -
        // and the bottom one is what keeps the tab bar off the Android nav bar.
        ViewCompat.setOnApplyWindowInsetsListener(webView, (view, insets) -> {
            Insets bars = insets.getInsets(WindowInsetsCompat.Type.systemBars()
                    | WindowInsetsCompat.Type.displayCutout());
            lastInsets = bars;
            pushInsetsToPage(bars);
            return insets;
        });
        ViewCompat.requestApplyInsets(webView);

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

        webView.setWebViewClient(new WebViewClient() {
            @Override
            public void onPageFinished(WebView view, String url) {
                // A load wipes the document, and with it the CSS variables - the
                // insets have to be pushed again or the page comes back edge-to-edge.
                if (lastInsets != null) pushInsetsToPage(lastInsets);
            }
        });
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

            // The page's fullscreen button calls Element.requestFullscreen() on a
            // stage element, not just <video> - WebView only honours that for any
            // element (not only video) once the host app implements these two
            // callbacks itself; without them the call silently does nothing.
            @Override
            public void onShowCustomView(View view, CustomViewCallback callback) {
                if (customView != null) {
                    callback.onCustomViewHidden();
                    return;
                }
                customView = view;
                customViewCallback = callback;
                webView.setVisibility(View.GONE);
                rootContainer.addView(customView, new FrameLayout.LayoutParams(
                        FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));
                hideSystemBars();
            }

            @Override
            public void onHideCustomView() {
                if (customView == null) return;
                rootContainer.removeView(customView);
                customView = null;
                webView.setVisibility(View.VISIBLE);
                if (customViewCallback != null) customViewCallback.onCustomViewHidden();
                customViewCallback = null;
                showSystemBars();
            }
        });

        getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(true) {
            @Override
            public void handleOnBackPressed() {
                if (customView != null) {
                    webView.getWebChromeClient().onHideCustomView();
                } else if (webView.canGoBack()) {
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

    private void hideSystemBars() {
        WindowInsetsControllerCompat controller = WindowCompat.getInsetsController(getWindow(), rootContainer);
        controller.setSystemBarsBehavior(WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
        controller.hide(WindowInsetsCompat.Type.systemBars());
    }

    private void showSystemBars() {
        WindowInsetsControllerCompat controller = WindowCompat.getInsetsController(getWindow(), rootContainer);
        controller.show(WindowInsetsCompat.Type.systemBars());
    }

    /** Publishes the system bar sizes to the page as CSS variables, in CSS px. */
    private void pushInsetsToPage(Insets bars) {
        float density = getResources().getDisplayMetrics().density;
        if (density <= 0) density = 1f;
        int top = Math.round(bars.top / density);
        int bottom = Math.round(bars.bottom / density);
        int left = Math.round(bars.left / density);
        int right = Math.round(bars.right / density);
        String js = "(function(){var s=document.documentElement.style;"
                + "s.setProperty('--android-inset-top','" + top + "px');"
                + "s.setProperty('--android-inset-bottom','" + bottom + "px');"
                + "s.setProperty('--android-inset-left','" + left + "px');"
                + "s.setProperty('--android-inset-right','" + right + "px');})()";
        evalJs(js);
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
