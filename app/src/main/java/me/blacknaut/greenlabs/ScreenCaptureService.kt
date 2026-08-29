package me.blacknaut.greenlabs

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.Image
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Binder
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import java.io.ByteArrayOutputStream

/**
 * Dono da captura: VirtualDisplay -> ImageReader -> JPEG -> ScreenStreamServer.
 *
 * Precisa ser servico em primeiro plano do tipo mediaProjection. Desde o
 * Android 14 a plataforma lanca SecurityException dentro do
 * `getMediaProjection()` se isso ainda nao estiver ativo - e por isso que a
 * promocao acontece no `onCreate`, e nao no `onStartCommand`: o `onCreate`
 * termina antes de qualquer `onBind`, entao a ordem se mantem independente de
 * como o start e o bind corram um com o outro.
 */
class ScreenCaptureService : Service() {

    /** Quem escuta o que acontece aqui. A MainActivity se registra ao ligar. */
    interface Callback {
        fun onReady(port: Int)
        fun onError(message: String)
        fun onStopped()
        fun onLeaveCallRequested()
    }

    private val binder = LocalBinder()
    private var projectionManager: MediaProjectionManager? = null
    private var projection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null
    private var streamServer: ScreenStreamServer? = null
    private var captureThread: HandlerThread? = null
    private var captureHandler: Handler? = null

    @Volatile
    private var lastFrameAt = 0L

    @Volatile
    private var minFrameIntervalMs = 33L

    inner class LocalBinder : Binder() {
        val service: ScreenCaptureService get() = this@ScreenCaptureService
    }

    override fun onCreate() {
        super.onCreate()
        projectionManager =
            getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        criarCanal()
        irParaPrimeiroPlano()
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP_SHARE -> {
                val ouvinte = callback
                stopCapture()
                ouvinte?.onStopped()
                stopSelf()
            }

            ACTION_LEAVE_CALL -> {
                val ouvinte = callback
                stopCapture()
                ouvinte?.onLeaveCallRequested()
                stopSelf()
            }
        }
        return START_NOT_STICKY
    }

    private fun intencaoDaAcao(acao: String): PendingIntent {
        val intent = Intent(this, ScreenCaptureService::class.java).setAction(acao)
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        return PendingIntent.getForegroundService(this, acao.hashCode(), intent, flags)
    }

    private fun irParaPrimeiroPlano() {
        val notificacao = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("GreenLabs")
            .setContentText("Compartilhando a tela")
            .setSmallIcon(android.R.drawable.ic_menu_share)
            .setOngoing(true)
            .addAction(
                android.R.drawable.ic_media_pause,
                "Parar transmissão",
                intencaoDaAcao(ACTION_STOP_SHARE),
            )
            .addAction(
                android.R.drawable.ic_menu_close_clear_cancel,
                "Sair da chamada",
                intencaoDaAcao(ACTION_LEAVE_CALL),
            )
            .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID,
                notificacao,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION,
            )
        } else {
            startForeground(NOTIFICATION_ID, notificacao)
        }
    }

    private fun criarCanal() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return

        val gerenciador = getSystemService(NotificationManager::class.java) ?: return
        if (gerenciador.getNotificationChannel(CHANNEL_ID) != null) return

        val canal = NotificationChannel(
            CHANNEL_ID,
            "Compartilhamento de tela",
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = "Mostrado enquanto sua tela está sendo transmitida."
        }
        gerenciador.createNotificationChannel(canal)
    }

    /** Só vale chamar depois de o `onCreate` ter promovido o serviço. */
    fun startCapture(resultCode: Int, resultData: Intent, width: Int, height: Int, fps: Int) {
        try {
            val projecao = projectionManager?.getMediaProjection(resultCode, resultData)
                ?: error("getMediaProjection devolveu nulo")
            projection = projecao

            projecao.registerCallback(
                object : MediaProjection.Callback() {
                    override fun onStop() {
                        Log.i(TAG, "projecao encerrada")
                        teardown()
                        callback?.onStopped()
                        stopSelf()
                    }
                },
                null,
            )

            // O piso era 33 ms (30 fps). Com 30 fps pedido, o piso precisa
            // deixar passar exatamente isso, senao o limite anula a escolha.
            minFrameIntervalMs = maxOf(1000L / maxOf(1, fps), 16L)

            val thread = HandlerThread("screen-capture").apply { start() }
            captureThread = thread
            val handler = Handler(thread.looper)
            captureHandler = handler

            val leitor = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 2)
            leitor.setOnImageAvailableListener(::aoChegarQuadro, handler)
            imageReader = leitor

            virtualDisplay = projecao.createVirtualDisplay(
                "greenlabs-screen",
                width,
                height,
                resources.displayMetrics.densityDpi,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                leitor.surface,
                null,
                handler,
            )

            val servidor = ScreenStreamServer()
            streamServer = servidor
            callback?.onReady(servidor.start())
        } catch (e: Exception) {
            Log.w(TAG, "startCapture falhou: ${e.message}")
            teardown()
            callback?.onError(e.message ?: "falha desconhecida")
            stopSelf()
        }
    }

    private fun aoChegarQuadro(reader: ImageReader) {
        var imagem: Image? = null
        try {
            imagem = reader.acquireLatestImage() ?: return

            // Limita a taxa aqui, e nao no VirtualDisplay: ele entrega quadro
            // sempre que a tela muda, o que num jogo passa de 100 por segundo.
            // Comprimir todos em JPEG por software gastaria bateria a toa.
            val agora = System.currentTimeMillis()
            if (agora - lastFrameAt < minFrameIntervalMs) return
            lastFrameAt = agora

            val bitmap = paraBitmap(imagem) ?: return
            val jpeg = ByteArrayOutputStream()
            bitmap.compress(Bitmap.CompressFormat.JPEG, 62, jpeg)
            bitmap.recycle()

            streamServer?.pushFrame(jpeg.toByteArray())
        } catch (e: Exception) {
            Log.w(TAG, "quadro descartado: ${e.message}")
        } finally {
            imagem?.close()
        }
    }

    fun stopCapture() {
        runCatching { projection?.stop() }
        teardown()
    }

    private fun teardown() {
        runCatching { virtualDisplay?.release() }
        virtualDisplay = null

        runCatching { imageReader?.close() }
        imageReader = null

        streamServer?.stop()
        streamServer = null

        captureThread?.quitSafely()
        captureThread = null
        captureHandler = null

        projection = null
    }

    override fun onDestroy() {
        teardown()
        super.onDestroy()
    }

    companion object {
        private const val TAG = "GreenLabsScreen"
        private const val CHANNEL_ID = "screen_capture"
        private const val NOTIFICATION_ID = 4201

        const val ACTION_STOP_SHARE = "me.blacknaut.greenlabs.action.STOP_SHARE"
        const val ACTION_LEAVE_CALL = "me.blacknaut.greenlabs.action.LEAVE_CALL"

        /**
         * Estatico porque as acoes da notificacao chegam por `onStartCommand`,
         * numa instancia que pode nao ser a mesma que a tela conhece. A
         * notificacao sobrevive enquanto a captura existir.
         */
        @Volatile
        var callback: Callback? = null

        /**
         * As linhas do ImageReader costumam vir com folga alem de `largura * 4`
         * bytes. Sem recortar de volta, a imagem sai com uma faixa de lixo na
         * lateral direita.
         */
        private fun paraBitmap(imagem: Image): Bitmap? {
            val plano = imagem.planes.firstOrNull() ?: return null
            val passoDoPixel = plano.pixelStride
            if (passoDoPixel == 0) return null

            val largura = imagem.width
            val altura = imagem.height
            val folga = plano.rowStride - passoDoPixel * largura

            val bruto = Bitmap.createBitmap(
                largura + folga / passoDoPixel,
                altura,
                Bitmap.Config.ARGB_8888,
            )
            bruto.copyPixelsFromBuffer(plano.buffer)
            if (folga == 0) return bruto

            val recortado = Bitmap.createBitmap(bruto, 0, 0, largura, altura)
            bruto.recycle()
            return recortado
        }
    }
}
