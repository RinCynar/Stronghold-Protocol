package top.rincynar.stronghold;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.DialogInterface;
import android.content.Intent;
import android.graphics.Bitmap;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.view.WindowManager;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;
import android.widget.ProgressBar;
import android.widget.Toast;

import android.os.PowerManager;
import java.io.PrintWriter;
import java.io.StringWriter;

public class MainActivity extends Activity {
    private FrameLayout rootContainer;
    private WebView webView;
    private ProgressBar progressBar;
    private long backPressedTime = 0;
    private PowerManager.WakeLock wakeLock;

    private String getTargetUrl() {
        try {
            String u = getString(R.string.target_url);
            if (u != null && !u.trim().isEmpty()) return u.trim();
        } catch (Throwable ignored) {}
        return "https://ak.rincynar.top";
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        // Set up global crash handler to prevent silent crash (闪退)
        setupCrashHandler();

        super.onCreate(savedInstanceState);

        try {
            // Keep screen on during match
            getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        } catch (Throwable ignored) {}

        try {
            // Short-edges cutout mode for notch / punch-hole displays (Android 9+)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                WindowManager.LayoutParams lp = getWindow().getAttributes();
                lp.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES;
                getWindow().setAttributes(lp);
            }
        } catch (Throwable ignored) {}

        try {
            // Extend window layout behind system bars (status & navigation)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                getWindow().setDecorFitsSystemWindows(false);
            }
        } catch (Throwable ignored) {}

        setContentView(R.layout.activity_main);
        rootContainer = findViewById(R.id.rootContainer);
        progressBar = findViewById(R.id.progressBar);

        try {
            final View decor = getWindow().getDecorView();
            if (decor != null) {
                decor.setOnSystemUiVisibilityChangeListener(new View.OnSystemUiVisibilityChangeListener() {
                    @Override
                    public void onSystemUiVisibilityChange(int visibility) {
                        if ((visibility & View.SYSTEM_UI_FLAG_FULLSCREEN) == 0
                                || (visibility & View.SYSTEM_UI_FLAG_HIDE_NAVIGATION) == 0) {
                            hideSystemUI();
                        }
                    }
                });
            }
        } catch (Throwable ignored) {}

        hideSystemUI();

        // Dynamically instantiate WebView inside try-catch to detect missing/broken system WebView
        try {
            initWebView(savedInstanceState);
        } catch (Throwable t) {
            handleWebViewInitError(t);
        }
    }

    private void setupCrashHandler() {
        Thread.setDefaultUncaughtExceptionHandler(new Thread.UncaughtExceptionHandler() {
            @Override
            public void uncaughtException(Thread thread, final Throwable ex) {
                new Handler(Looper.getMainLooper()).post(new Runnable() {
                    @Override
                    public void run() {
                        showCrashDialog(ex);
                    }
                });
            }
        });
    }

    private void initWebView(Bundle savedInstanceState) {
        webView = new WebView(this);
        FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT
        );
        rootContainer.addView(webView, 0, params);

        setupWebSettings();

        webView.setOverScrollMode(View.OVER_SCROLL_NEVER);
        webView.setBackgroundColor(0xFF0C0F0E);

        final AssetCacheManager assetCache = new AssetCacheManager(this);

        webView.setWebViewClient(new WebViewClient() {
            @Override
            public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest request) {
                try {
                    WebResourceResponse cached = assetCache.intercept(request);
                    if (cached != null) {
                        return cached;
                    }
                } catch (Throwable ignored) {}
                return super.shouldInterceptRequest(view, request);
            }

            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                if (request == null || request.getUrl() == null) return false;
                String url = request.getUrl().toString();
                if (url.startsWith("https://ak.rincynar.top") || url.startsWith("http://ak.rincynar.top")
                        || url.startsWith("https://game.starst.site") || url.startsWith("http://game.starst.site")) {
                    return false;
                }
                try {
                    Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse(url));
                    startActivity(intent);
                    return true;
                } catch (Throwable t) {
                    return false;
                }
            }

            @Override
            public void onPageStarted(WebView view, String url, Bitmap favicon) {
                if (progressBar != null) {
                    progressBar.setVisibility(View.VISIBLE);
                }
                hideSystemUI();
                injectViewportAndLayoutFixes();
            }

            @Override
            public void onPageFinished(WebView view, String url) {
                if (progressBar != null) {
                    progressBar.setVisibility(View.GONE);
                }
                hideSystemUI();
                injectViewportAndLayoutFixes();
            }

            @Override
            public void onReceivedError(WebView view, WebResourceRequest request, WebResourceError error) {
                if (progressBar != null) {
                    progressBar.setVisibility(View.GONE);
                }
            }
        });

        webView.setWebChromeClient(new WebChromeClient() {
            @Override
            public void onProgressChanged(WebView view, int newProgress) {
                if (progressBar != null) {
                    progressBar.setProgress(newProgress);
                    if (newProgress >= 100) {
                        progressBar.setVisibility(View.GONE);
                    }
                }
                if (newProgress > 30) {
                    injectViewportAndLayoutFixes();
                }
            }
        });

        if (savedInstanceState != null) {
            webView.restoreState(savedInstanceState);
        } else {
            webView.loadUrl(getTargetUrl());
        }
    }

    private void injectViewportAndLayoutFixes() {
        if (webView == null) return;
        final String js = 
            "(function() {\n" +
            "  try {\n" +
            "    if (!document.getElementById('sp-responsive-fix')) {\n" +
            "      var style = document.createElement('style');\n" +
            "      style.id = 'sp-responsive-fix';\n" +
            "      style.textContent = '\\n" +
            "        html {\\n" +
            "          font-size: clamp(16px, min(calc(100vw / 19.2), calc(100svh / 10.8), calc(100vh / 10.8)), 240px) !important;\\n" +
            "          -webkit-text-size-adjust: 100% !important;\\n" +
            "          text-size-adjust: 100% !important;\\n" +
            "        }\\n" +
            "        .result__main {\\n" +
            "          padding-top: clamp(0.12rem, 2vh, 0.36rem) !important;\\n" +
            "          padding-bottom: clamp(0.12rem, 2vh, 0.3rem) !important;\\n" +
            "        }\\n" +
            "        .result__hero {\\n" +
            "          padding-top: clamp(0.08rem, 1.5vh, 0.3rem) !important;\\n" +
            "          overflow-y: auto !important;\\n" +
            "          overflow-x: hidden !important;\\n" +
            "          scrollbar-width: thin !important;\\n" +
            "        }\\n" +
            "        .result__foot {\\n" +
            "          margin-top: auto !important;\\n" +
            "          padding-top: clamp(0.08rem, 1.2vh, 0.2rem) !important;\\n" +
            "          padding-bottom: 0.06rem !important;\\n" +
            "        }\\n" +
            "      ';\n" +
            "      (document.head || document.documentElement).appendChild(style);\n" +
            "    }\n" +
            "  } catch(e) {}\n" +
            "})();";
        webView.evaluateJavascript(js, null);
    }

    private void setupWebSettings() {
        WebSettings ws = webView.getSettings();
        ws.setJavaScriptEnabled(true);
        ws.setDomStorageEnabled(true);
        ws.setUseWideViewPort(true);
        ws.setLoadWithOverviewMode(false);
        ws.setTextZoom(100);
        ws.setMinimumFontSize(1);
        ws.setMinimumLogicalFontSize(1);
        ws.setCacheMode(WebSettings.LOAD_DEFAULT);

        try {
            ws.setDatabaseEnabled(true);
        } catch (Throwable ignored) {}

        try {
            ws.setMediaPlaybackRequiresUserGesture(false);
        } catch (Throwable ignored) {}

        try {
            ws.setAllowFileAccess(true);
            ws.setAllowContentAccess(true);
        } catch (Throwable ignored) {}

        try {
            ws.setSupportZoom(false);
            ws.setBuiltInZoomControls(false);
            ws.setDisplayZoomControls(false);
        } catch (Throwable ignored) {}

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                ws.setMixedContentMode(WebSettings.MIXED_CONTENT_ALWAYS_ALLOW);
            }
        } catch (Throwable ignored) {}
    }

    private void handleWebViewInitError(final Throwable t) {
        if (progressBar != null) progressBar.setVisibility(View.GONE);

        String msg = t.getMessage();
        if (msg == null || msg.isEmpty()) msg = t.getClass().getSimpleName();

        new AlertDialog.Builder(this)
            .setTitle("系统 WebView 初始化失败")
            .setMessage("手机未能成功启动内置 WebView 内核。\n\n"
                + "错误详情：" + msg + "\n\n"
                + "常见原因：\n"
                + "1. 手机的「Android System WebView」组件被停用或版本过旧\n"
                + "2. 部分定制精简版系统缺少 WebView 核心支持\n\n"
                + "你可以点击下方按钮直接在外部浏览器中正常游玩。")
            .setCancelable(false)
            .setPositiveButton("在浏览器中打开", new DialogInterface.OnClickListener() {
                @Override
                public void onClick(DialogInterface dialog, int which) {
                    try {
                        Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse(getTargetUrl()));
                        startActivity(intent);
                    } catch (Throwable ignored) {}
                    finish();
                }
            })
            .setNegativeButton("退出", new DialogInterface.OnClickListener() {
                @Override
                public void onClick(DialogInterface dialog, int which) {
                    finish();
                }
            })
            .show();
    }

    private void showCrashDialog(Throwable ex) {
        StringWriter sw = new StringWriter();
        ex.printStackTrace(new PrintWriter(sw));
        String stackTrace = sw.toString();

        new AlertDialog.Builder(this)
            .setTitle("应用遇到错误")
            .setMessage("错误信息：\n" + ex.getMessage() + "\n\n" + (stackTrace.length() > 300 ? stackTrace.substring(0, 300) + "..." : stackTrace))
            .setCancelable(false)
            .setPositiveButton("在浏览器中游玩", new DialogInterface.OnClickListener() {
                @Override
                public void onClick(DialogInterface dialog, int which) {
                    try {
                        Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse(getTargetUrl()));
                        startActivity(intent);
                    } catch (Throwable ignored) {}
                    finish();
                }
            })
            .setNegativeButton("退出", new DialogInterface.OnClickListener() {
                @Override
                public void onClick(DialogInterface dialog, int which) {
                    finish();
                }
            })
            .show();
    }

    private void hideSystemUI() {
        try {
            View decorView = getWindow().getDecorView();
            if (decorView != null) {
                decorView.setSystemUiVisibility(
                    View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                    | View.SYSTEM_UI_FLAG_FULLSCREEN
                    | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                    | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                    | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                    | View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                );
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                WindowInsetsController controller = getWindow().getInsetsController();
                if (controller != null) {
                    controller.hide(WindowInsets.Type.statusBars() | WindowInsets.Type.navigationBars());
                    controller.setSystemBarsBehavior(WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
                }
            }
        } catch (Throwable ignored) {}
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) {
            hideSystemUI();
        }
    }

    @Override
    public void onBackPressed() {
        if (webView != null && webView.canGoBack()) {
            webView.goBack();
            return;
        }
        if (System.currentTimeMillis() - backPressedTime < 2000) {
            super.onBackPressed();
        } else {
            backPressedTime = System.currentTimeMillis();
            Toast.makeText(this, "再按一次退出游戏", Toast.LENGTH_SHORT).show();
        }
    }

    private void acquireWakeLock() {
        try {
            if (wakeLock == null) {
                PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
                if (pm != null) {
                    wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "Stronghold:KeepAlive");
                    wakeLock.setReferenceCounted(false);
                }
            }
            if (wakeLock != null && !wakeLock.isHeld()) {
                wakeLock.acquire(15 * 60 * 1000L); // 15 min keep-alive
            }
        } catch (Throwable ignored) {}
    }

    private void releaseWakeLock() {
        try {
            if (wakeLock != null && wakeLock.isHeld()) {
                wakeLock.release();
            }
        } catch (Throwable ignored) {}
    }

    @Override
    protected void onResume() {
        super.onResume();
        KeepAliveService.stop(this);
        releaseWakeLock();
        if (webView != null) {
            try {
                webView.onResume();
                webView.evaluateJavascript("if (window.dispatchEvent) { window.dispatchEvent(new Event('focus')); }", null);
            } catch (Throwable ignored) {}
        }
        hideSystemUI();
        new Handler(Looper.getMainLooper()).postDelayed(new Runnable() {
            @Override
            public void run() {
                hideSystemUI();
            }
        }, 300);
    }

    @Override
    protected void onPause() {
        super.onPause();
        // DO NOT call webView.onPause()! Pausing WebView stops JavaScript timers and drops WebSocket immediately.
        acquireWakeLock();
    }

    @Override
    protected void onStop() {
        super.onStop();
        if (!isFinishing()) {
            KeepAliveService.start(this);
            acquireWakeLock();
        }
    }

    @Override
    protected void onDestroy() {
        KeepAliveService.stop(this);
        releaseWakeLock();
        if (webView != null) {
            try {
                rootContainer.removeView(webView);
                webView.destroy();
            } catch (Throwable ignored) {}
        }
        super.onDestroy();
    }
}
