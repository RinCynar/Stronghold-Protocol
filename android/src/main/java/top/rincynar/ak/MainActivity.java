package top.rincynar.ak;

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
import android.net.http.SslError;
import android.webkit.CookieManager;
import android.webkit.SslErrorHandler;
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
import java.net.HttpURLConnection;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URL;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

public class MainActivity extends Activity {
    private FrameLayout rootContainer;
    private WebView webView;
    private ProgressBar progressBar;
    private long backPressedTime = 0;
    private PowerManager.WakeLock wakeLock;

    private static class ServerCandidate implements Comparable<ServerCandidate> {
        final String url;
        final String host;
        final int priority;
        long latencyMs = Long.MAX_VALUE;
        boolean reachable = false;

        ServerCandidate(String url, int priority) {
            this.url = url;
            this.priority = priority;
            String h = "";
            try {
                Uri uri = Uri.parse(url);
                h = uri.getHost();
            } catch (Throwable ignored) {}
            this.host = h != null ? h : "";
        }

        @Override
        public int compareTo(ServerCandidate o) {
            if (this.reachable != o.reachable) {
                return this.reachable ? -1 : 1;
            }
            // If latency difference is significant (> 80ms), pick the faster node
            if (Math.abs(this.latencyMs - o.latencyMs) > 80) {
                return Long.compare(this.latencyMs, o.latencyMs);
            }
            // Otherwise preserve official priority order from servers.xml
            return Integer.compare(this.priority, o.priority);
        }
    }

    private String currentActiveUrl = null;
    private List<ServerCandidate> sortedCandidates = new ArrayList<>();
    private int currentCandidateIndex = 0;
    private int lastFailedCandidateIndex = -1;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private Runnable loadTimeoutRunnable = null;

    private List<String> getServerCandidates() {
        List<String> list = new ArrayList<>();
        try {
            int resId = getResources().getIdentifier("server_candidates", "array", getPackageName());
            if (resId != 0) {
                String[] arr = getResources().getStringArray(resId);
                if (arr != null) {
                    for (String s : arr) {
                        if (s != null && !s.trim().isEmpty() && !list.contains(s.trim())) {
                            list.add(s.trim());
                        }
                    }
                }
            }
        } catch (Throwable ignored) {}
        if (list.isEmpty()) {
            list.add("https://ak.rincynar.top");
        }
        return list;
    }

    private void probeCandidate(final ServerCandidate candidate) {
        if (candidate.url == null || candidate.url.isEmpty()) return;
        HttpURLConnection conn = null;
        try {
            long t0 = System.currentTimeMillis();
            URL u = new URL(candidate.url);
            conn = (HttpURLConnection) u.openConnection();
            conn.setRequestMethod("HEAD");
            conn.setConnectTimeout(2500);
            conn.setReadTimeout(2500);
            conn.setInstanceFollowRedirects(true);
            conn.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/136.0.0.0 Mobile Safari/537.36");
            int code = conn.getResponseCode();
            long elapsed = System.currentTimeMillis() - t0;
            if ((code >= 200 && code < 400) || code == 405) {
                candidate.reachable = true;
                candidate.latencyMs = elapsed;
            } else {
                candidate.reachable = false;
                candidate.latencyMs = Long.MAX_VALUE;
            }
        } catch (Throwable t) {
            candidate.reachable = false;
            candidate.latencyMs = Long.MAX_VALUE;
        } finally {
            if (conn != null) {
                try { conn.disconnect(); } catch (Throwable ignored) {}
            }
        }
    }

    private void startServerPingAndConnect() {
        cancelLoadTimeout();
        lastFailedCandidateIndex = -1;

        if (progressBar != null) {
            progressBar.setVisibility(View.VISIBLE);
            progressBar.setIndeterminate(true);
        }

        final List<String> rawUrls = getServerCandidates();
        final List<ServerCandidate> candidates = new ArrayList<>();
        for (int i = 0; i < rawUrls.size(); i++) {
            candidates.add(new ServerCandidate(rawUrls.get(i), i));
        }

        new Thread(new Runnable() {
            @Override
            public void run() {
                int poolSize = Math.min(6, candidates.size());
                ExecutorService pool = Executors.newFixedThreadPool(poolSize);
                final CountDownLatch latch = new CountDownLatch(candidates.size());

                for (final ServerCandidate c : candidates) {
                    pool.execute(new Runnable() {
                        @Override
                        public void run() {
                            try {
                                probeCandidate(c);
                            } finally {
                                latch.countDown();
                            }
                        }
                    });
                }

                try {
                    latch.await(2500, TimeUnit.MILLISECONDS);
                } catch (Throwable ignored) {}

                pool.shutdownNow();

                Collections.sort(candidates);

                mainHandler.post(new Runnable() {
                    @Override
                    public void run() {
                        if (isFinishing() || (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN_MR1 && isDestroyed())) {
                            return;
                        }
                        sortedCandidates = candidates;
                        currentCandidateIndex = 0;
                        tryConnectCandidate(0);
                    }
                });
            }
        }).start();
    }

    private void tryConnectCandidate(int index) {
        cancelLoadTimeout();
        if (sortedCandidates == null || index >= sortedCandidates.size()) {
            if (progressBar != null) {
                progressBar.setIndeterminate(false);
                progressBar.setVisibility(View.GONE);
            }
            showUnavailableView();
            return;
        }

        currentCandidateIndex = index;
        final ServerCandidate target = sortedCandidates.get(index);
        currentActiveUrl = target.url;

        if (progressBar != null) {
            progressBar.setVisibility(View.VISIBLE);
            progressBar.setIndeterminate(true);
        }

        if (webView != null) {
            webView.loadUrl(target.url);
        }

        loadTimeoutRunnable = new Runnable() {
            @Override
            public void run() {
                if (isFinishing() || (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN_MR1 && isDestroyed())) {
                    return;
                }
                failoverToNextCandidate("连接超时");
            }
        };
        mainHandler.postDelayed(loadTimeoutRunnable, 15000);
    }

    private void failoverToNextCandidate(String reason) {
        cancelLoadTimeout();
        if (currentCandidateIndex == lastFailedCandidateIndex) {
            return; // Already failed over for this candidate
        }
        lastFailedCandidateIndex = currentCandidateIndex;
        final int nextIndex = currentCandidateIndex + 1;
        mainHandler.post(new Runnable() {
            @Override
            public void run() {
                tryConnectCandidate(nextIndex);
            }
        });
    }

    private void cancelLoadTimeout() {
        if (loadTimeoutRunnable != null) {
            mainHandler.removeCallbacks(loadTimeoutRunnable);
            loadTimeoutRunnable = null;
        }
    }

    private void showUnavailableView() {
        cancelLoadTimeout();
        if (webView == null) return;
        final String html = "<!doctype html><html lang=\"zh-CN\"><head><meta charset=\"utf-8\"/>"
            + "<meta name=\"viewport\" content=\"width=device-width, initial-scale=1, user-scalable=no\"/>"
            + "<title>服务暂时不可用</title><style>"
            + "* { box-sizing: border-box; margin: 0; padding: 0; }"
            + "body { background-color: #111614; color: #d8e3de; font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', Roboto, 'Noto Sans SC', sans-serif; height: 100vh; display: flex; flex-direction: column; align-items: center; justify-content: center; text-align: center; padding: 24px; user-select: none; }"
            + ".icon { font-size: 56px; margin-bottom: 16px; line-height: 1; }"
            + "h1 { color: #4ed8af; font-size: 26px; font-weight: 700; letter-spacing: 2px; margin-bottom: 12px; }"
            + "p { color: #9ab3a8; font-size: 15px; line-height: 1.6; max-width: 480px; margin-bottom: 24px; }"
            + ".btn { display: inline-block; background: #4ed8af; color: #111614; font-weight: 700; font-size: 16px; padding: 12px 36px; border-radius: 4px; text-decoration: none; border: none; cursor: pointer; box-shadow: 0 4px 14px rgba(78, 216, 175, 0.25); transition: all 0.2s ease; }"
            + ".btn:active { transform: scale(0.96); background: #39b38f; }"
            + ".footer { position: absolute; bottom: 16px; color: #4e635a; font-size: 12px; letter-spacing: 1px; }"
            + "</style></head><body>"
            + "<div class=\"icon\">⚠️</div>"
            + "<h1>服务暂时不可用</h1>"
            + "<p>未能连接至任何可用的卫戍协议服务器节点。<br/>请检查您的网络连接或稍后重试。</p>"
            + "<a class=\"btn\" href=\"https://retry.local\">重新检测连接</a>"
            + "<div class=\"footer\">STRONGHOLD PROTOCOL · COVENANT</div>"
            + "</body></html>";
        webView.loadDataWithBaseURL("https://retry.local", html, "text/html", "UTF-8", null);
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

    private String getTargetUrl() {
        if (currentActiveUrl != null) return currentActiveUrl;
        List<String> list = getServerCandidates();
        if (!list.isEmpty()) return list.get(0);
        return "https://ak.rincynar.top";
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

        try {
            CookieManager cm = CookieManager.getInstance();
            cm.setAcceptCookie(true);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                cm.setAcceptThirdPartyCookies(webView, true);
            }
        } catch (Throwable ignored) {}

        webView.setOverScrollMode(View.OVER_SCROLL_NEVER);
        webView.setBackgroundColor(0xFF0C0F0E);

        webView.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, String url) {
                return handleUrlOverride(url);
            }

            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                if (request == null || request.getUrl() == null) return false;
                return handleUrlOverride(request.getUrl().toString());
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
                if (url != null && !url.startsWith("https://retry.local")) {
                    cancelLoadTimeout();
                }
                if (progressBar != null) {
                    progressBar.setIndeterminate(false);
                    progressBar.setVisibility(View.GONE);
                }
                hideSystemUI();
                injectViewportAndLayoutFixes();
            }

            @Override
            public void onReceivedSslError(WebView view, SslErrorHandler handler, SslError error) {
                // Proceed through SSL errors rather than silently aborting the load.
                handler.proceed();
            }

            @Override
            public void onReceivedError(WebView view, WebResourceRequest request, WebResourceError error) {
                if (request == null || !request.isForMainFrame()) return;
                Uri uri = request.getUrl();
                if (uri == null) return;
                String host = uri.getHost();
                if (host == null || currentActiveUrl == null) return;
                Uri activeUri = Uri.parse(currentActiveUrl);
                if (activeUri.getHost() != null && !host.equalsIgnoreCase(activeUri.getHost())) {
                    // Ignore errors from previously cancelled navigations or subresources
                    return;
                }
                failoverToNextCandidate("主页面网络加载失败");
            }

            @Override
            public void onReceivedError(WebView view, int errorCode, String description, String failingUrl) {
                if (failingUrl == null || currentActiveUrl == null) return;
                try {
                    Uri uri = Uri.parse(failingUrl);
                    Uri activeUri = Uri.parse(currentActiveUrl);
                    if (uri.getHost() != null && activeUri.getHost() != null
                            && uri.getHost().equalsIgnoreCase(activeUri.getHost())) {
                        failoverToNextCandidate("主页面网络加载失败 (" + errorCode + ")");
                    }
                } catch (Throwable ignored) {}
            }

            @Override
            public void onReceivedHttpError(WebView view, WebResourceRequest request,
                    WebResourceResponse errorResponse) {
                if (request == null || !request.isForMainFrame()) return;
                Uri uri = request.getUrl();
                if (uri == null) return;
                String host = uri.getHost();
                if (host == null || currentActiveUrl == null) return;
                Uri activeUri = Uri.parse(currentActiveUrl);
                if (activeUri.getHost() != null && !host.equalsIgnoreCase(activeUri.getHost())) {
                    return;
                }
                if (errorResponse != null && errorResponse.getStatusCode() >= 400) {
                    failoverToNextCandidate("HTTP " + errorResponse.getStatusCode());
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
                    // Page is actively receiving data and rendering, cancel watchdog timeout
                    cancelLoadTimeout();
                    injectViewportAndLayoutFixes();
                }
            }
        });

        if (savedInstanceState != null) {
            webView.restoreState(savedInstanceState);
        } else {
            startServerPingAndConnect();
        }
    }

    private boolean isInternalUrl(String url) {
        if (url == null || url.isEmpty()) return false;
        try {
            Uri uri = Uri.parse(url);
            String host = uri.getHost();
            if (host == null) return false;
            String h = host.toLowerCase();
            if (h.endsWith("rincynar.top") || h.equals("retry.local")) {
                return true;
            }
            if (sortedCandidates != null) {
                for (ServerCandidate c : sortedCandidates) {
                    if (c.host != null && !c.host.isEmpty() && h.equalsIgnoreCase(c.host)) {
                        return true;
                    }
                }
            }
        } catch (Throwable ignored) {}
        return false;
    }

    private boolean handleUrlOverride(String url) {
        if (url == null || url.isEmpty()) return false;
        if (url.startsWith("https://retry.local") || url.startsWith("http://retry.local")) {
            startServerPingAndConnect();
            return true;
        }
        if (isInternalUrl(url)) {
            return false;
        }
        // Only open external http/https web links in the system browser
        if (url.startsWith("http://") || url.startsWith("https://")) {
            try {
                Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse(url));
                startActivity(intent);
                return true;
            } catch (Throwable ignored) {}
        }
        return false;
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
        ws.setJavaScriptCanOpenWindowsAutomatically(true);
        ws.setCacheMode(WebSettings.LOAD_DEFAULT);
        // Clean default user agent: strip "; wv" and "Version/4.0 " so Cloudflare recognizes it as genuine mobile Chrome
        try {
            String defaultUa = ws.getUserAgentString();
            if (defaultUa != null) {
                ws.setUserAgentString(defaultUa.replace("; wv", "").replace("Version/4.0 ", ""));
            }
        } catch (Throwable ignored) {}

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
