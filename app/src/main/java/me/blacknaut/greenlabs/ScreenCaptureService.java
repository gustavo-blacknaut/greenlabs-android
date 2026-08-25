package me.blacknaut.greenlabs;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.graphics.Bitmap;
import android.graphics.PixelFormat;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.media.Image;
import android.media.ImageReader;
import android.media.projection.MediaProjection;
import android.media.projection.MediaProjectionManager;
import android.os.Binder;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.util.Log;

import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;

/**
 * Owns the MediaProjection capture: VirtualDisplay -> ImageReader -> JPEG ->
 * ScreenStreamServer. Has to be a foreground service of type mediaProjection
 * - since Android 14 the platform throws a SecurityException out of
 * MediaProjectionManager.getMediaProjection() if that isn't already active,
 * which is why startForegroundCompat() runs from onCreate() and not
 * onStartCommand(): onCreate is guaranteed to finish before onBind can fire,
 * so the ordering holds regardless of how start/bind race each other.
 */
public class ScreenCaptureService extends Service {

    private static final String TAG = "GreenLabsScreen";
    private static final String CHANNEL_ID = "screen_capture";
    private static final int NOTIFICATION_ID = 4201;

    interface Callback {
        void onReady(int port);
        void onError(String message);
        void onStopped();
        void onLeaveCallRequested();
    }

    static volatile Callback callback;

    static final String ACTION_STOP_SHARE = "me.blacknaut.greenlabs.action.STOP_SHARE";
    static final String ACTION_LEAVE_CALL = "me.blacknaut.greenlabs.action.LEAVE_CALL";

    private final IBinder binder = new LocalBinder();
    private MediaProjectionManager projectionManager;
    private MediaProjection projection;
    private VirtualDisplay virtualDisplay;
    private ImageReader imageReader;
    private ScreenStreamServer streamServer;
    private HandlerThread captureThread;
    private Handler captureHandler;
    private volatile long lastFrameAt;
    private volatile long minFrameIntervalMs = 33;

    class LocalBinder extends Binder {
        ScreenCaptureService getService() {
            return ScreenCaptureService.this;
        }
    }

    @Override
    public void onCreate() {
        super.onCreate();
        projectionManager = (MediaProjectionManager) getSystemService(Context.MEDIA_PROJECTION_SERVICE);
        createChannel();
        startForegroundCompat();
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return binder;
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent != null ? intent.getAction() : null;
        if (ACTION_STOP_SHARE.equals(action)) {
            Callback cb = callback;
            stopCapture();
            if (cb != null) cb.onStopped();
            stopSelf();
        } else if (ACTION_LEAVE_CALL.equals(action)) {
            Callback cb = callback;
            stopCapture();
            if (cb != null) cb.onLeaveCallRequested();
            stopSelf();
        }
        return START_NOT_STICKY;
    }

    private PendingIntent actionPendingIntent(String action) {
        Intent intent = new Intent(this, ScreenCaptureService.class).setAction(action);
        int flags = PendingIntent.FLAG_UPDATE_CURRENT
                | (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M ? PendingIntent.FLAG_IMMUTABLE : 0);
        return Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                ? PendingIntent.getForegroundService(this, action.hashCode(), intent, flags)
                : PendingIntent.getService(this, action.hashCode(), intent, flags);
    }

    private void startForegroundCompat() {
        Notification notification = new NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle("GreenLabs")
                .setContentText("Compartilhando a tela")
                .setSmallIcon(android.R.drawable.ic_menu_share)
                .setOngoing(true)
                .addAction(android.R.drawable.ic_media_pause, "Parar transmissão", actionPendingIntent(ACTION_STOP_SHARE))
                .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Sair da chamada", actionPendingIntent(ACTION_LEAVE_CALL))
                .build();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION);
        } else {
            startForeground(NOTIFICATION_ID, notification);
        }
    }

    private void createChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return;
        NotificationManager nm = getSystemService(NotificationManager.class);
        if (nm.getNotificationChannel(CHANNEL_ID) != null) return;
        NotificationChannel channel = new NotificationChannel(
                CHANNEL_ID, "Compartilhamento de tela", NotificationManager.IMPORTANCE_LOW);
        channel.setDescription("Mostrado enquanto sua tela esta sendo transmitida.");
        nm.createNotificationChannel(channel);
    }

    /** Only valid to call once the foreground promotion from onCreate() has taken effect. */
    void startCapture(int resultCode, Intent resultData, int width, int height, int fps) {
        try {
            projection = projectionManager.getMediaProjection(resultCode, resultData);
            if (projection == null) throw new IllegalStateException("getMediaProjection returned null");

            projection.registerCallback(new MediaProjection.Callback() {
                @Override
                public void onStop() {
                    Log.i(TAG, "projection stopped");
                    teardown();
                    Callback cb = callback;
                    if (cb != null) cb.onStopped();
                    stopSelf();
                }
            }, null);

            // O piso era 33ms (30fps). Com 30fps pedido, o piso precisa deixar
            // passar exatamente isso, senao o limite anula o que foi escolhido.
            minFrameIntervalMs = Math.max(1000L / Math.max(1, fps), 16);

            captureThread = new HandlerThread("screen-capture");
            captureThread.start();
            captureHandler = new Handler(captureThread.getLooper());

            int densityDpi = getResources().getDisplayMetrics().densityDpi;

            imageReader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 2);
            imageReader.setOnImageAvailableListener(this::onFrame, captureHandler);

            virtualDisplay = projection.createVirtualDisplay(
                    "greenlabs-screen", width, height, densityDpi,
                    DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                    imageReader.getSurface(), null, captureHandler);

            streamServer = new ScreenStreamServer();
            int port = streamServer.start();

            Callback cb = callback;
            if (cb != null) cb.onReady(port);
        } catch (Exception e) {
            Log.w(TAG, "startCapture failed: " + e.getMessage());
            teardown();
            Callback cb = callback;
            if (cb != null) cb.onError(String.valueOf(e.getMessage()));
            stopSelf();
        }
    }

    private void onFrame(ImageReader reader) {
        Image image = null;
        try {
            image = reader.acquireLatestImage();
            if (image == null) return;

            long now = System.currentTimeMillis();
            if (now - lastFrameAt < minFrameIntervalMs) return;
            lastFrameAt = now;

            Bitmap bitmap = imageToBitmap(image);
            if (bitmap == null) return;
            ByteArrayOutputStream jpeg = new ByteArrayOutputStream();
            bitmap.compress(Bitmap.CompressFormat.JPEG, 62, jpeg);
            bitmap.recycle();
            if (streamServer != null) streamServer.pushFrame(jpeg.toByteArray());
        } catch (Exception e) {
            Log.w(TAG, "frame drop: " + e.getMessage());
        } finally {
            if (image != null) image.close();
        }
    }

    /** ImageReader rows are often padded past width*4 bytes; crop back to the real size. */
    private static Bitmap imageToBitmap(Image image) {
        Image.Plane plane = image.getPlanes()[0];
        ByteBuffer buffer = plane.getBuffer();
        int pixelStride = plane.getPixelStride();
        int rowStride = plane.getRowStride();
        int width = image.getWidth();
        int height = image.getHeight();
        int rowPadding = rowStride - pixelStride * width;

        Bitmap raw = Bitmap.createBitmap(width + rowPadding / pixelStride, height, Bitmap.Config.ARGB_8888);
        raw.copyPixelsFromBuffer(buffer);
        if (rowPadding == 0) return raw;
        Bitmap cropped = Bitmap.createBitmap(raw, 0, 0, width, height);
        raw.recycle();
        return cropped;
    }

    void stopCapture() {
        if (projection != null) {
            try { projection.stop(); } catch (Exception ignored) { }
        }
        teardown();
    }

    private void teardown() {
        if (virtualDisplay != null) {
            try { virtualDisplay.release(); } catch (Exception ignored) { }
            virtualDisplay = null;
        }
        if (imageReader != null) {
            try { imageReader.close(); } catch (Exception ignored) { }
            imageReader = null;
        }
        if (streamServer != null) {
            streamServer.stop();
            streamServer = null;
        }
        if (captureThread != null) {
            captureThread.quitSafely();
            captureThread = null;
        }
        projection = null;
    }

    @Override
    public void onDestroy() {
        teardown();
        super.onDestroy();
    }
}
