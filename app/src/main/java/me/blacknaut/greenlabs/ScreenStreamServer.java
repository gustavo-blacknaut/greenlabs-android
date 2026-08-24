package me.blacknaut.greenlabs;

import android.util.Log;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.PushbackInputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CopyOnWriteArraySet;

/**
 * Local HTTP server that pushes captured screen frames to the WebView.
 *
 * Same shape as the desktop app's WASAPI audio endpoint: a single GET route
 * returns a chunked stream of self-framed binary records (4-byte big-endian
 * length + payload), one JPEG per frame. That's simpler on both ends than a
 * real multipart/x-mixed-replace parser, and the JS side already knows this
 * pattern from parsing the audio stream.
 */
final class ScreenStreamServer {

    private static final String TAG = "GreenLabsScreen";

    private ServerSocket socket;
    private Thread acceptThread;
    private volatile boolean running;
    private final CopyOnWriteArraySet<OutputStream> clients = new CopyOnWriteArraySet<>();

    int start() throws IOException {
        socket = new ServerSocket(0, 4, InetAddress.getByName("127.0.0.1"));
        running = true;
        acceptThread = new Thread(this::acceptLoop, "screen-stream-accept");
        acceptThread.setDaemon(true);
        acceptThread.start();
        return socket.getLocalPort();
    }

    void stop() {
        running = false;
        for (OutputStream out : clients) {
            try { out.close(); } catch (IOException ignored) { }
        }
        clients.clear();
        try {
            if (socket != null) socket.close();
        } catch (IOException ignored) { }
    }

    private void acceptLoop() {
        while (running) {
            try {
                Socket client = socket.accept();
                new Thread(() -> handle(client), "screen-stream-client").start();
            } catch (IOException e) {
                if (running) Log.w(TAG, "accept failed: " + e.getMessage());
            }
        }
    }

    private void handle(Socket client) {
        try {
            PushbackInputStream in = new PushbackInputStream(client.getInputStream(), 1);
            // The request itself is irrelevant (there is only one route), but
            // it has to be drained before the response can be written.
            String line;
            while ((line = readLine(in)) != null && !line.isEmpty()) {
                // discard
            }
            OutputStream out = client.getOutputStream();
            out.write(("HTTP/1.1 200 OK\r\n"
                    + "Content-Type: application/octet-stream\r\n"
                    + "Cache-Control: no-store\r\n"
                    + "Connection: close\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
            out.flush();
            clients.add(out);
        } catch (IOException e) {
            try { client.close(); } catch (IOException ignored) { }
        }
    }

    /** Best-effort: drops the frame for any client whose socket is behind. */
    void pushFrame(byte[] jpeg) {
        if (jpeg == null || jpeg.length == 0 || clients.isEmpty()) return;
        int len = jpeg.length;
        byte[] header = { (byte) (len >>> 24), (byte) (len >>> 16), (byte) (len >>> 8), (byte) len };
        for (OutputStream out : clients) {
            try {
                synchronized (out) {
                    out.write(header);
                    out.write(jpeg);
                    out.flush();
                }
            } catch (IOException e) {
                clients.remove(out);
                try { out.close(); } catch (IOException ignored) { }
            }
        }
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
}
