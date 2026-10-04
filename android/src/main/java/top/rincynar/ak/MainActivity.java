package top.rincynar.ak;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.ProgressBar;
import android.widget.Toast;

import org.mozilla.geckoview.AllowOrDeny;
import org.mozilla.geckoview.GeckoResult;
import org.mozilla.geckoview.GeckoRuntime;
import org.mozilla.geckoview.GeckoRuntimeSettings;
import org.mozilla.geckoview.GeckoSession;
import org.mozilla.geckoview.GeckoSessionSettings;
import org.mozilla.geckoview.GeckoView;
import org.mozilla.geckoview.WebRequestError;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.ArrayList;
import java.util.List;

public class MainActivity extends Activity {

    private static class ServerCandidate {
        final String url;
        final String host;
        final int priority;

        ServerCandidate(String url, int priority) {
            this.url = url;
            this.priority = priority;
            String h = "";
            try {
                Uri u = Uri.parse(url);
                h = u.getHost() != null ? u.getHost() : "";
            } catch (Throwable ignored) {}
            this.host = h;
        }
    }

    private static GeckoRuntime sRuntime;
    private GeckoView geckoView;
    private GeckoSession geckoSession;
    private FrameLayout rootContainer;
    private ProgressBar progressBar;

    private PowerManager.WakeLock wakeLock;
    private long backPressedTime = 0;
    private boolean canSessionGoBack = false;

    private String currentActiveUrl = null;
    private List<ServerCandidate> candidateList = new ArrayList<>();
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

    private void startServerConnect() {
        cancelLoadTimeout();
        lastFailedCandidateIndex = -1;

        if (progressBar != null) {
            progressBar.setVisibility(View.VISIBLE);
            progressBar.setIndeterminate(true);
        }

        final List<String> rawUrls = getServerCandidates();
        candidateList = new ArrayList<>();
        for (int i = 0; i < rawUrls.size(); i++) {
            candidateList.add(new ServerCandidate(rawUrls.get(i), i));
        }

        currentCandidateIndex = 0;
        tryConnectCandidate(0);
    }

    private void tryConnectCandidate(int index) {
        cancelLoadTimeout();
        if (candidateList == null || index >= candidateList.size()) {
            if (progressBar != null) {
                progressBar.setIndeterminate(false);
                progressBar.setVisibility(View.GONE);
            }
            showUnavailableView();
            return;
        }

        currentCandidateIndex = index;
        final ServerCandidate target = candidateList.get(index);
        currentActiveUrl = target.url;

        if (progressBar != null) {
            progressBar.setVisibility(View.VISIBLE);
            progressBar.setIndeterminate(true);
        }

        if (geckoSession != null) {
            geckoSession.loadUri(target.url);
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
        if (geckoSession == null) return;
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
        geckoSession.load(new GeckoSession.Loader().data(html, "text/html"));
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        setupCrashHandler();
        super.onCreate(savedInstanceState);

        try {
            getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        } catch (Throwable ignored) {}

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                WindowManager.LayoutParams lp = getWindow().getAttributes();
                lp.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES;
                getWindow().setAttributes(lp);
            }
        } catch (Throwable ignored) {}

        try {
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

        try {
            initGeckoView(savedInstanceState);
        } catch (Throwable t) {
            handleGeckoViewInitError(t);
        }
    }

    private String getTargetUrl() {
        if (currentActiveUrl != null) return currentActiveUrl;
        List<String> list = getServerCandidates();
        if (!list.isEmpty()) return list.get(0);
        return "https://ak.rincynar.top";
    }

    private void setupCrashHandler() {
        final Thread.UncaughtExceptionHandler defaultHandler = Thread.getDefaultUncaughtExceptionHandler();
        Thread.setDefaultUncaughtExceptionHandler(new Thread.UncaughtExceptionHandler() {
            @Override
            public void uncaughtException(Thread thread, final Throwable ex) {
                try {
                    new Handler(Looper.getMainLooper()).post(new Runnable() {
                        @Override
                        public void run() {
                            showCrashDialog(ex);
                        }
                    });
                    if (Looper.myLooper() == null) {
                        Looper.prepare();
                    }
                    Looper.loop();
                } catch (Throwable t) {
                    if (defaultHandler != null) {
                        defaultHandler.uncaughtException(thread, ex);
                    }
                }
            }
        });
    }

    private void initGeckoView(Bundle savedInstanceState) {
        androidx.lifecycle.ProcessLifecycleOwner.onAppCreate();

        geckoView = new GeckoView(this);
        FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT
        );
        rootContainer.addView(geckoView, 0, params);
        geckoView.setBackgroundColor(0xFF0C0F0E);

        if (sRuntime == null) {
            GeckoRuntimeSettings settings = new GeckoRuntimeSettings.Builder()
                .aboutConfigEnabled(false)
                .consoleOutput(false)
                .build();

            // Enable TRR (DNS-over-HTTPS) mode 2 (TRR_MODE_FIRST).
            // In Gecko, DoH queries HTTPS RR which retrieves Cloudflare ECHConfig.
            // This enables Encrypted Client Hello (ECH) out-of-the-box, completely
            // bypassing GFW SNI inspection resets on Cloudflare Anycast IPs.
            settings.setTrustedRecursiveResolverMode(GeckoRuntimeSettings.TRR_MODE_FIRST);
            settings.setTrustedRecursiveResolverUri("https://dns.alidns.com/dns-query");

            try {
                sRuntime = GeckoRuntime.create(getApplicationContext(), settings);
            } catch (IllegalStateException e) {
                // If GeckoRuntime was already initialized in this process, retrieve it via reflection
                try {
                    java.lang.reflect.Field field = GeckoRuntime.class.getDeclaredField("sRuntime");
                    field.setAccessible(true);
                    sRuntime = (GeckoRuntime) field.get(null);
                    if (sRuntime != null) {
                        try {
                            sRuntime.getSettings().setTrustedRecursiveResolverMode(GeckoRuntimeSettings.TRR_MODE_FIRST);
                            sRuntime.getSettings().setTrustedRecursiveResolverUri("https://dns.alidns.com/dns-query");
                        } catch (Throwable ignored) {}
                    }
                } catch (Throwable reflectionEx) {
                    throw e;
                }
            }

            if (sRuntime != null) {
                try {
                    // Register built-in extension for mobile responsive CSS layout adjustments
                    sRuntime.getWebExtensionController().ensureBuiltIn(
                        "resource://android/assets/sp_extension/",
                        "sp-layout-fix@rincynar.top"
                    );
                } catch (Throwable ignored) {}
            }
        }

        GeckoSessionSettings sessionSettings = new GeckoSessionSettings.Builder()
            .usePrivateMode(false)
            .displayMode(GeckoSessionSettings.DISPLAY_MODE_FULLSCREEN)
            .build();
        geckoSession = new GeckoSession(sessionSettings);

        geckoSession.setContentDelegate(new GeckoSession.ContentDelegate() {
            @Override
            public void onFullScreen(GeckoSession session, boolean fullScreen) {
                // Intercept and prevent DOM element fullscreen to avoid viewport desync
                if (fullScreen && session != null) {
                    try {
                        session.exitFullScreen();
                    } catch (Throwable ignored) {}
                }
            }
        });

        geckoSession.setProgressDelegate(new GeckoSession.ProgressDelegate() {
            @Override
            public void onPageStart(GeckoSession session, String url) {
                if (progressBar != null) {
                    progressBar.setVisibility(View.VISIBLE);
                }
                hideSystemUI();
            }

            @Override
            public void onPageStop(GeckoSession session, boolean success) {
                if (success) {
                    cancelLoadTimeout();
                }
                if (progressBar != null) {
                    progressBar.setIndeterminate(false);
                    progressBar.setVisibility(View.GONE);
                }
                hideSystemUI();
            }

            @Override
            public void onProgressChange(GeckoSession session, int progress) {
                if (progressBar != null) {
                    progressBar.setIndeterminate(false);
                    progressBar.setProgress(progress);
                    if (progress >= 100) {
                        progressBar.setVisibility(View.GONE);
                    }
                }
                if (progress > 30) {
                    cancelLoadTimeout();
                }
            }
        });

        geckoSession.setNavigationDelegate(new GeckoSession.NavigationDelegate() {
            @Override
            public void onCanGoBack(GeckoSession session, boolean canGoBack) {
                canSessionGoBack = canGoBack;
            }

            @Override
            public GeckoResult<AllowOrDeny> onLoadRequest(GeckoSession session, LoadRequest request) {
                if (request == null || request.uri == null) {
                    return GeckoResult.fromValue(AllowOrDeny.ALLOW);
                }
                String url = request.uri;
                if (url.startsWith("https://retry.local") || url.startsWith("sp://retry")) {
                    startServerConnect();
                    return GeckoResult.fromValue(AllowOrDeny.DENY);
                }
                if (isInternalUrl(url)) {
                    return GeckoResult.fromValue(AllowOrDeny.ALLOW);
                }
                // External links open in default browser
                if (url.startsWith("http://") || url.startsWith("https://")) {
                    try {
                        Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse(url));
                        startActivity(intent);
                        return GeckoResult.fromValue(AllowOrDeny.DENY);
                    } catch (Throwable ignored) {}
                }
                return GeckoResult.fromValue(AllowOrDeny.ALLOW);
            }

            @Override
            public GeckoResult<String> onLoadError(GeckoSession session, String uri, WebRequestError error) {
                if (uri == null || currentActiveUrl == null) return null;
                try {
                    Uri u = Uri.parse(uri);
                    Uri active = Uri.parse(currentActiveUrl);
                    if (u.getHost() != null && active.getHost() != null
                            && u.getHost().equalsIgnoreCase(active.getHost())) {
                        failoverToNextCandidate("页面加载失败: " + (error != null ? error.category : "未知"));
                    }
                } catch (Throwable ignored) {}
                return null;
            }
        });

        geckoSession.open(sRuntime);
        geckoView.setSession(geckoSession);

        startServerConnect();
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
            if (candidateList != null) {
                for (ServerCandidate c : candidateList) {
                    if (c.host != null && !c.host.isEmpty() && h.equalsIgnoreCase(c.host)) {
                        return true;
                    }
                }
            }
        } catch (Throwable ignored) {}
        return false;
    }

    private void handleGeckoViewInitError(final Throwable t) {
        if (progressBar != null) progressBar.setVisibility(View.GONE);

        StringWriter sw = new StringWriter();
        t.printStackTrace(new PrintWriter(sw));
        String stackTrace = sw.toString();

        StringBuilder sb = new StringBuilder();
        sb.append("手机未能成功启动内置浏览器内核。\n\n");
        sb.append("【异常类型】\n").append(t.getClass().getName()).append("\n\n");
        sb.append("【错误详情】\n").append(t.getMessage() != null ? t.getMessage() : "无详细信息").append("\n\n");
        if (t.getCause() != null) {
            sb.append("【根本诱因】\n").append(t.getCause().toString()).append("\n\n");
        }
        sb.append("【调用堆栈 (可长按复制)】\n").append(stackTrace);

        android.widget.ScrollView sv = new android.widget.ScrollView(this);
        sv.setPadding(40, 20, 40, 20);
        android.widget.TextView tv = new android.widget.TextView(this);
        tv.setText(sb.toString());
        tv.setTextSize(12);
        tv.setTextColor(0xFFDDDDDD);
        tv.setTextIsSelectable(true);
        sv.addView(tv);

        new AlertDialog.Builder(this)
            .setTitle("浏览器内核启动失败")
            .setView(sv)
            .setCancelable(false)
            .setPositiveButton("在外部浏览器打开", new DialogInterface.OnClickListener() {
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
                    android.os.Process.killProcess(android.os.Process.myPid());
                }
            })
            .show();
    }

    private void showCrashDialog(Throwable ex) {
        StringWriter sw = new StringWriter();
        ex.printStackTrace(new PrintWriter(sw));
        String stackTrace = sw.toString();

        StringBuilder sb = new StringBuilder();
        sb.append("应用发生未捕获异常：\n\n");
        sb.append("【异常类型】\n").append(ex.getClass().getName()).append("\n\n");
        sb.append("【错误信息】\n").append(ex.getMessage() != null ? ex.getMessage() : "无").append("\n\n");
        if (ex.getCause() != null) {
            sb.append("【根本诱因】\n").append(ex.getCause().toString()).append("\n\n");
        }
        sb.append("【调用堆栈 (可长按复制)】\n").append(stackTrace);

        android.widget.ScrollView sv = new android.widget.ScrollView(this);
        sv.setPadding(40, 20, 40, 20);
        android.widget.TextView tv = new android.widget.TextView(this);
        tv.setText(sb.toString());
        tv.setTextSize(12);
        tv.setTextColor(0xFFDDDDDD);
        tv.setTextIsSelectable(true);
        sv.addView(tv);

        new AlertDialog.Builder(this)
            .setTitle("应用遇到错误")
            .setView(sv)
            .setCancelable(false)
            .setPositiveButton("在浏览器中游玩", new DialogInterface.OnClickListener() {
                @Override
                public void onClick(DialogInterface dialog, int which) {
                    try {
                        Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse(getTargetUrl()));
                        startActivity(intent);
                    } catch (Throwable ignored) {}
                    finish();
                    android.os.Process.killProcess(android.os.Process.myPid());
                }
            })
            .setNegativeButton("退出", new DialogInterface.OnClickListener() {
                @Override
                public void onClick(DialogInterface dialog, int which) {
                    finish();
                    android.os.Process.killProcess(android.os.Process.myPid());
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
        if (canSessionGoBack && geckoSession != null) {
            geckoSession.goBack();
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
        if (geckoSession != null) {
            try {
                geckoSession.setActive(true);
                geckoSession.setFocused(true);
            } catch (Throwable ignored) {}
        }
        hideSystemUI();
        new Handler(Looper.getMainLooper()).postDelayed(new Runnable() {
            @Override
            public void run() {
                hideSystemUI();
            }
        }, 300);
        androidx.lifecycle.ProcessLifecycleOwner.onAppResume();
    }

    @Override
    protected void onPause() {
        super.onPause();
        acquireWakeLock();
        androidx.lifecycle.ProcessLifecycleOwner.onAppPause();
    }

    @Override
    protected void onStop() {
        super.onStop();
        if (!isFinishing()) {
            KeepAliveService.start(this);
            acquireWakeLock();
        }
        androidx.lifecycle.ProcessLifecycleOwner.onAppStop();
    }

    @Override
    protected void onDestroy() {
        androidx.lifecycle.ProcessLifecycleOwner.onAppDestroy();
        KeepAliveService.stop(this);
        releaseWakeLock();
        if (geckoSession != null) {
            try {
                geckoSession.close();
            } catch (Throwable ignored) {}
        }
        if (geckoView != null) {
            try {
                rootContainer.removeView(geckoView);
                geckoView.releaseSession();
            } catch (Throwable ignored) {}
        }
        super.onDestroy();
    }
}
