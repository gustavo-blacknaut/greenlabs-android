package me.blacknaut.greenlabs;

import android.content.res.AssetManager;
import android.util.Log;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.PushbackInputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Serves the bundled web client over http://127.0.0.1.
 *
 * The origin matters: file:// is not a secure context, so getUserMedia would be
 * blocked, and an https:// origin would block ws:// connections to a signaling
 * server on the local network. Loopback http:// is treated as secure by
 * Chromium and still allows ws://, so it satisfies both.
 */
final class AssetHttpServer {

    private static final String TAG = "GreenLabsHttp";
    private static final String ROOT = "web";

    private final AssetManager assets;
    private final ExecutorService workers = Executors.newFixedThreadPool(4);
    private ServerSocket socket;
    private Thread acceptThread;
    private volatile boolean running;

    AssetHttpServer(AssetManager assets) {
        this.assets = assets;
    }

    /** Binds to a free loopback port and returns the base URL. */
    String start() throws IOException {
        socket = new ServerSocket(0, 8, InetAddress.getByName("127.0.0.1"));
        running = true;
        acceptThread = new Thread(this::acceptLoop, "asset-http-accept");
        acceptThread.setDaemon(true);
        acceptThread.start();
        return "http://127.0.0.1:" + socket.getLocalPort();
    }

    void stop() {
        running = false;
        try {
            if (socket != null) socket.close();
        } catch (IOException ignored) {
        }
        workers.shutdownNow();
    }

    private void acceptLoop() {
        while (running) {
            try {
                Socket client = socket.accept();
                workers.execute(() -> handle(client));
            } catch (IOException e) {
                if (running) Log.w(TAG, "accept failed: " + e.getMessage());
            }
        }
    }

    private void handle(Socket client) {
        try (Socket c = client) {
            PushbackInputStream in = new PushbackInputStream(c.getInputStream(), 1);
            String requestLine = readLine(in);
            if (requestLine == null || requestLine.isEmpty()) return;

            // Headers are irrelevant here, but they must be drained before writing.
            String line;
            while ((line = readLine(in)) != null && !line.isEmpty()) {
                // discard
            }

            String[] parts = requestLine.split(" ");
            if (parts.length < 2) {
                respond(c.getOutputStream(), 400, "text/plain", "bad request".getBytes());
                return;
            }

            String path = parts[1];
            int q = path.indexOf('?');
            if (q >= 0) path = path.substring(0, q);
            if (path.equals("/") || path.isEmpty()) path = "/index.html";

            String assetPath = ROOT + normalize(path);
            byte[] body = readAsset(assetPath);

            if (body == null) {
                // Single page app: unknown paths fall back to the entry point.
                body = readAsset(ROOT + "/index.html");
                if (body == null) {
                    respond(c.getOutputStream(), 404, "text/plain", "not found".getBytes());
                    return;
                }
                respond(c.getOutputStream(), 200, "text/html; charset=utf-8", body);
                return;
            }

            respond(c.getOutputStream(), 200, mimeFor(assetPath), body);
        } catch (IOException e) {
            Log.w(TAG, "handle failed: " + e.getMessage());
        }
    }

    /** Blocks path traversal out of the asset root. */
    private String normalize(String path) {
        String clean = path.replace('\\', '/');
        while (clean.contains("../")) clean = clean.replace("../", "");
        if (!clean.startsWith("/")) clean = "/" + clean;
        return clean;
    }

    private byte[] readAsset(String path) {
        try (InputStream is = assets.open(path)) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[16 * 1024];
            int n;
            while ((n = is.read(buf)) > 0) out.write(buf, 0, n);
            return out.toByteArray();
        } catch (IOException e) {
            return null;
        }
    }

    private void respond(OutputStream out, int status, String type, byte[] body) throws IOException {
        String head = "HTTP/1.1 " + status + " " + (status == 200 ? "OK" : "ERROR") + "\r\n"
                + "Content-Type: " + type + "\r\n"
                + "Content-Length: " + body.length + "\r\n"
                + "Cache-Control: no-store\r\n"
                + "Connection: close\r\n\r\n";
        out.write(head.getBytes());
        out.write(body);
        out.flush();
    }

    private static String readLine(PushbackInputStream in) throws IOException {
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        int b;
        while ((b = in.read()) != -1) {
            if (b == '\n') break;
            if (b != '\r') buf.write(b);
        }
        if (b == -1 && buf.size() == 0) return null;
        return buf.toString("UTF-8");
    }

    private static String mimeFor(String path) {
        String p = path.toLowerCase(Locale.ROOT);
        if (p.endsWith(".html")) return "text/html; charset=utf-8";
        if (p.endsWith(".js") || p.endsWith(".mjs")) return "application/javascript; charset=utf-8";
        if (p.endsWith(".css")) return "text/css; charset=utf-8";
        if (p.endsWith(".json")) return "application/json; charset=utf-8";
        if (p.endsWith(".svg")) return "image/svg+xml";
        if (p.endsWith(".png")) return "image/png";
        if (p.endsWith(".jpg") || p.endsWith(".jpeg")) return "image/jpeg";
        if (p.endsWith(".webp")) return "image/webp";
        if (p.endsWith(".ico")) return "image/x-icon";
        if (p.endsWith(".woff2")) return "font/woff2";
        if (p.endsWith(".woff")) return "font/woff";
        return "application/octet-stream";
    }
}
