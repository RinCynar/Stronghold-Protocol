package top.rincynar.ak;

import android.content.Context;
import android.net.Uri;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Manages persistent local disk caching for static game assets.
 * Files are stored in internal filesDir (immune to Android cache clearers).
 */
public class AssetCacheManager {
    private static final String CACHE_DIR_NAME = "game_asset_cache";
    private final File cacheDir;
    private final ConcurrentHashMap<String, Object> locks = new ConcurrentHashMap<>();

    public AssetCacheManager(Context context) {
        this.cacheDir = new File(context.getFilesDir(), CACHE_DIR_NAME);
        if (!cacheDir.exists()) {
            cacheDir.mkdirs();
        }
    }

    public boolean isCacheable(Uri uri) {
        if (uri == null) return false;
        String host = uri.getHost();
        if (host == null) return false;
        String lower = host.toLowerCase();
        if (!lower.endsWith("rincynar.top") && !lower.endsWith("onrender.com")) {
            return false;
        }
        String path = uri.getPath();
        if (path == null) return false;
        return path.startsWith("/assets/") || path.startsWith("/fonts/") || path.startsWith("/vendor/");
    }

    public WebResourceResponse intercept(WebResourceRequest request) {
        if (request == null) return null;
        Uri uri = request.getUrl();
        if (!isCacheable(uri)) return null;

        String path = uri.getPath();
        if (path == null) return null;
        if (path.startsWith("/")) path = path.substring(1);

        File targetFile = new File(cacheDir, path.replace('/', File.separatorChar));
        String mime = getMimeType(path);

        // 1. If cached on disk, serve directly from local storage with zero network traffic
        if (targetFile.exists() && targetFile.length() > 0) {
            try {
                FileInputStream fis = new FileInputStream(targetFile);
                WebResourceResponse response = new WebResourceResponse(mime, null, fis);
                Map<String, String> headers = new HashMap<>();
                headers.put("Access-Control-Allow-Origin", "*");
                headers.put("Cache-Control", "public, max-age=31536000, immutable");
                headers.put("Accept-Ranges", "bytes");
                response.setResponseHeaders(headers);
                return response;
            } catch (Throwable ignored) {}
        }

        // 2. First-time fetch: stream to WebView and write to disk simultaneously
        Object lock = locks.computeIfAbsent(path, k -> new Object());
        synchronized (lock) {
            if (targetFile.exists() && targetFile.length() > 0) {
                try {
                    FileInputStream fis = new FileInputStream(targetFile);
                    WebResourceResponse response = new WebResourceResponse(mime, null, fis);
                    Map<String, String> headers = new HashMap<>();
                    headers.put("Access-Control-Allow-Origin", "*");
                    headers.put("Cache-Control", "public, max-age=31536000, immutable");
                    response.setResponseHeaders(headers);
                    return response;
                } catch (Throwable ignored) {}
            }

            try {
                URL url = new URL(uri.toString());
                HttpURLConnection conn = (HttpURLConnection) url.openConnection();
                conn.setRequestMethod("GET");
                conn.setConnectTimeout(8000);
                conn.setReadTimeout(20000);
                conn.setRequestProperty("User-Agent", "Mozilla/5.0 (Mobile; Android) StrongholdProtocol");

                int code = conn.getResponseCode();
                if (code == HttpURLConnection.HTTP_OK) {
                    File parent = targetFile.getParentFile();
                    if (parent != null && !parent.exists()) {
                        parent.mkdirs();
                    }
                    File tempFile = new File(targetFile.getAbsolutePath() + ".tmp");
                    FileOutputStream fos = new FileOutputStream(tempFile);
                    InputStream netIn = conn.getInputStream();

                    final String finalPath = path;
                    TeeInputStream tee = new TeeInputStream(netIn, fos, tempFile, targetFile, new Runnable() {
                        @Override
                        public void run() {
                            locks.remove(finalPath);
                        }
                    });

                    WebResourceResponse response = new WebResourceResponse(mime, null, tee);
                    Map<String, String> headers = new HashMap<>();
                    headers.put("Access-Control-Allow-Origin", "*");
                    headers.put("Cache-Control", "public, max-age=31536000, immutable");
                    response.setResponseHeaders(headers);
                    return response;
                }
            } catch (Throwable t) {
                locks.remove(path);
                return null;
            }
        }
        return null;
    }

    private String getMimeType(String path) {
        String lower = path.toLowerCase();
        if (lower.endsWith(".png")) return "image/png";
        if (lower.endsWith(".jpg") || lower.endsWith(".jpeg")) return "image/jpeg";
        if (lower.endsWith(".webp")) return "image/webp";
        if (lower.endsWith(".skel")) return "application/octet-stream";
        if (lower.endsWith(".atlas")) return "text/plain";
        if (lower.endsWith(".mp3")) return "audio/mpeg";
        if (lower.endsWith(".ogg")) return "audio/ogg";
        if (lower.endsWith(".wav")) return "audio/wav";
        if (lower.endsWith(".woff2")) return "font/woff2";
        if (lower.endsWith(".woff")) return "font/woff";
        if (lower.endsWith(".ttf")) return "font/ttf";
        if (lower.endsWith(".otf")) return "font/otf";
        if (lower.endsWith(".wasm")) return "application/wasm";
        if (lower.endsWith(".js") || lower.endsWith(".mjs")) return "application/javascript";
        if (lower.endsWith(".css")) return "text/css";
        if (lower.endsWith(".json")) return "application/json";
        if (lower.endsWith(".svg")) return "image/svg+xml";
        if (lower.endsWith(".obj")) return "text/plain";
        return "application/octet-stream";
    }

    private static class TeeInputStream extends InputStream {
        private final InputStream in;
        private final OutputStream out;
        private final File tempFile;
        private final File destFile;
        private final Runnable onComplete;
        private boolean finished = false;

        public TeeInputStream(InputStream in, OutputStream out, File tempFile, File destFile, Runnable onComplete) {
            this.in = in;
            this.out = out;
            this.tempFile = tempFile;
            this.destFile = destFile;
            this.onComplete = onComplete;
        }

        @Override
        public int read() throws IOException {
            int b = in.read();
            if (b != -1) {
                try { out.write(b); } catch (Throwable ignored) {}
            } else {
                finish();
            }
            return b;
        }

        @Override
        public int read(byte[] b, int off, int len) throws IOException {
            int n = in.read(b, off, len);
            if (n != -1) {
                try { out.write(b, off, n); } catch (Throwable ignored) {}
            } else {
                finish();
            }
            return n;
        }

        private synchronized void finish() {
            if (!finished) {
                finished = true;
                try { out.flush(); } catch (Throwable ignored) {}
                try { out.close(); } catch (Throwable ignored) {}
                try { in.close(); } catch (Throwable ignored) {}
                if (tempFile.exists() && tempFile.length() > 0) {
                    tempFile.renameTo(destFile);
                } else {
                    tempFile.delete();
                }
                if (onComplete != null) onComplete.run();
            }
        }

        @Override
        public void close() throws IOException {
            try {
                in.close();
            } finally {
                try { out.close(); } catch (Throwable ignored) {}
                if (!finished) {
                    tempFile.delete();
                    if (onComplete != null) onComplete.run();
                }
            }
        }
    }
}
