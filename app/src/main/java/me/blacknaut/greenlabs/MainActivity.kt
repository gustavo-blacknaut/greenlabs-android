package me.blacknaut.greenlabs

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.view.View
import android.view.WindowManager
import android.webkit.JavascriptInterface
import android.webkit.PermissionRequest
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.ActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.graphics.Insets
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import java.io.IOException

class MainActivity : AppCompatActivity() {

    private var server: AssetHttpServer? = null
    private lateinit var webView: WebView
    private lateinit var rootContainer: FrameLayout

    private var lastInsets: Insets? = null
    private var customView: View? = null
    private var customViewCallback: WebChromeClient.CustomViewCallback? = null

    private var projectionManager: MediaProjectionManager? = null
    private var screenService: ScreenCaptureService? = null
    private var screenServiceBound = false

    private var pendingWidth = 1280
    private var pendingHeight = 720
    private var pendingFps = 30
    private var pendingResultCode = 0
    private var pendingResultData: Intent? = null

    private val screenCaptureLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
        ::aoResponderCaptura,
    )

    // Tipo explicito: o objeto se referencia por dentro (no onLeaveCallRequested),
    // e sem a anotacao o compilador entra em inferencia recursiva.
    private val screenConnection: ServiceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            val servico = (binder as? ScreenCaptureService.LocalBinder)?.service ?: return
            screenService = servico
            screenServiceBound = true

            ScreenCaptureService.callback = object : ScreenCaptureService.Callback {
                override fun onReady(port: Int) = runOnUiThread {
                    evalJs("window.__glScreenReady && window.__glScreenReady($port)")
                }

                override fun onError(message: String) = runOnUiThread {
                    evalJs("window.__glScreenError && window.__glScreenError(${paraJs(message)})")
                }

                override fun onStopped() = runOnUiThread {
                    evalJs("window.__glScreenStopped && window.__glScreenStopped()")
                }

                override fun onLeaveCallRequested() = runOnUiThread {
                    evalJs("window.__glScreenStopped && window.__glScreenStopped()")
                    evalJs("window.__glLeaveCall && window.__glLeaveCall()")
                    if (screenServiceBound) {
                        runCatching { unbindService(this@MainActivity.screenConnection) }
                        screenServiceBound = false
                    }
                }
            }

            val dados = pendingResultData ?: return
            servico.startCapture(pendingResultCode, dados, pendingWidth, pendingHeight, pendingFps)
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            screenService = null
            screenServiceBound = false
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Aplicativos que miram SDK 35+ recebem o modo de ponta a ponta imposto
        // pela plataforma, independente de `setDecorFitsSystemWindows(true)` -
        // essa chamada virou nada. Entao, em vez de brigar, o app abraca: o
        // conteudo desenha sob as barras do sistema, e o tamanho real delas vai
        // para a pagina pelo ouvinte de insets abaixo.
        WindowCompat.setDecorFitsSystemWindows(window, false)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        projectionManager =
            getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager

        webView = WebView(this).apply { setBackgroundColor(FUNDO) }
        rootContainer = FrameLayout(this).apply {
            setBackgroundColor(FUNDO)
            addView(
                webView,
                FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.MATCH_PARENT,
                ),
            )
        }
        setContentView(rootContainer)

        // Os insets vao para o CSS, e nao viram padding da view: padding na view
        // nao encolhe de forma confiavel o que a pagina enxerga como 100dvh, e o
        // layout continuava passando por baixo das barras. Como variaveis de
        // CSS, a pagina os coloca onde precisa - e o de baixo e o que mantem a
        // barra de abas longe da barra de navegacao do Android.
        ViewCompat.setOnApplyWindowInsetsListener(webView) { _, insets ->
            val barras = insets.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout(),
            )
            lastInsets = barras
            publicarInsets(barras)
            insets
        }
        ViewCompat.requestApplyInsets(webView)

        configurarWebView()
        configurarBotaoVoltar()

        pedirPermissoesDeCaptura()
        subirServidorEAbrir()
    }

    private fun configurarWebView() {
        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            mediaPlaybackRequiresUserGesture = false
            useWideViewPort = true
            loadWithOverviewMode = true
            // A pagina vem dos assets do proprio aplicativo; nao ha motivo para
            // a WebView poder ler arquivos nem provedores de conteudo.
            allowFileAccess = false
            allowContentAccess = false
            setSupportZoom(false)
            textZoom = 100
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) safeBrowsingEnabled = false
        }

        webView.addJavascriptInterface(ScreenBridge(), "greenlabsMobile")

        webView.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView?, url: String?) {
                // Carregar apaga o documento, e com ele as variaveis de CSS. Sem
                // reenviar, a pagina volta desenhando sob as barras.
                lastInsets?.let(::publicarInsets)
            }
        }

        webView.webChromeClient = object : WebChromeClient() {
            override fun onPermissionRequest(request: PermissionRequest) {
                // A pagina so pede camera e microfone; o resto e negado.
                val permitidos = request.resources.filter {
                    it == PermissionRequest.RESOURCE_VIDEO_CAPTURE ||
                        it == PermissionRequest.RESOURCE_AUDIO_CAPTURE
                }
                if (permitidos.isEmpty()) request.deny() else request.grant(permitidos.toTypedArray())
            }

            // O botao de tela cheia da pagina chama requestFullscreen() num
            // elemento qualquer, e nao so num <video>. A WebView so honra isso
            // quando o aplicativo implementa estes dois retornos; sem eles a
            // chamada nao faz nada e nao avisa.
            override fun onShowCustomView(view: View?, callback: CustomViewCallback?) {
                if (customView != null) {
                    callback?.onCustomViewHidden()
                    return
                }
                customView = view
                customViewCallback = callback
                webView.visibility = View.GONE
                rootContainer.addView(
                    view,
                    FrameLayout.LayoutParams(
                        FrameLayout.LayoutParams.MATCH_PARENT,
                        FrameLayout.LayoutParams.MATCH_PARENT,
                    ),
                )
                esconderBarras()
            }

            override fun onHideCustomView() {
                val atual = customView ?: return
                rootContainer.removeView(atual)
                customView = null
                webView.visibility = View.VISIBLE
                customViewCallback?.onCustomViewHidden()
                customViewCallback = null
                mostrarBarras()
            }
        }
    }

    private fun configurarBotaoVoltar() {
        onBackPressedDispatcher.addCallback(
            this,
            object : OnBackPressedCallback(true) {
                override fun handleOnBackPressed() {
                    when {
                        customView != null -> webView.webChromeClient?.onHideCustomView()
                        webView.canGoBack() -> webView.goBack()
                        else -> finish()
                    }
                }
            },
        )
    }

    private fun pedirPermissoesDeCaptura() {
        val faltando = listOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO)
            .filter {
                ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
            }
        if (faltando.isNotEmpty()) {
            ActivityCompat.requestPermissions(this, faltando.toTypedArray(), REQ_PERMISSOES)
        }
    }

    private fun subirServidorEAbrir() {
        try {
            val servidor = AssetHttpServer(assets)
            server = servidor
            webView.loadUrl("${servidor.start()}/index.html")
        } catch (e: IOException) {
            Toast.makeText(this, "Falha ao iniciar: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    private fun aoResponderCaptura(resultado: ActivityResult) {
        val dados = resultado.data
        if (resultado.resultCode != Activity.RESULT_OK || dados == null) {
            evalJs("window.__glScreenError && window.__glScreenError('Permissão negada')")
            return
        }

        pendingResultCode = resultado.resultCode
        pendingResultData = dados

        val servico = Intent(this, ScreenCaptureService::class.java)
        ContextCompat.startForegroundService(this, servico)
        bindService(servico, screenConnection, Context.BIND_AUTO_CREATE)
    }

    private fun pararCompartilhamento() {
        if (screenServiceBound) {
            runCatching { screenService?.stopCapture() }
            runCatching { unbindService(screenConnection) }
            screenServiceBound = false
        }
        ScreenCaptureService.callback = null
        stopService(Intent(this, ScreenCaptureService::class.java))
    }

    private fun esconderBarras() {
        WindowCompat.getInsetsController(window, rootContainer).apply {
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            hide(WindowInsetsCompat.Type.systemBars())
        }
    }

    private fun mostrarBarras() {
        WindowCompat.getInsetsController(window, rootContainer)
            .show(WindowInsetsCompat.Type.systemBars())
    }

    /** Publica o tamanho das barras do sistema como variaveis de CSS, em px de CSS. */
    private fun publicarInsets(barras: Insets) {
        val densidade = resources.displayMetrics.density.takeIf { it > 0f } ?: 1f
        val topo = Math.round(barras.top / densidade)
        val baixo = Math.round(barras.bottom / densidade)
        val esquerda = Math.round(barras.left / densidade)
        val direita = Math.round(barras.right / densidade)

        evalJs(
            "(function(){var s=document.documentElement.style;" +
                "s.setProperty('--android-inset-top','${topo}px');" +
                "s.setProperty('--android-inset-bottom','${baixo}px');" +
                "s.setProperty('--android-inset-left','${esquerda}px');" +
                "s.setProperty('--android-inset-right','${direita}px');})()",
        )
    }

    private fun evalJs(script: String) {
        webView.evaluateJavascript(script, null)
    }

    /**
     * Exposta ao cliente web como `window.greenlabsMobile`.
     *
     * Tem o mesmo formato do `window.greenlabsApp` do desktop, entao o
     * JavaScript separa o que so existe no celular do mesmo jeito que ja separa
     * o que so existe no Electron.
     */
    private inner class ScreenBridge {
        @JavascriptInterface
        fun isAvailable(): Boolean = true

        @JavascriptInterface
        fun requestScreenCapture(width: Int, height: Int, fps: Int) {
            pendingWidth = width.coerceIn(320, 1280)
            pendingHeight = height.coerceIn(240, 1280)

            // Teto de 30, acompanhando o lado web. Cada quadro vira um JPEG por
            // software, entao 30 custa o dobro de bateria de 15.
            pendingFps = fps.coerceIn(5, 30)

            runOnUiThread {
                // A notificacao e obrigatoria para o servico em primeiro plano.
                // Negada, a captura ainda funciona - so nao aparece o aviso.
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                    ContextCompat.checkSelfPermission(
                        this@MainActivity,
                        Manifest.permission.POST_NOTIFICATIONS,
                    ) != PackageManager.PERMISSION_GRANTED
                ) {
                    ActivityCompat.requestPermissions(
                        this@MainActivity,
                        arrayOf(Manifest.permission.POST_NOTIFICATIONS),
                        REQ_NOTIFICACOES,
                    )
                }
                projectionManager?.createScreenCaptureIntent()?.let(screenCaptureLauncher::launch)
            }
        }

        @JavascriptInterface
        fun stopScreenCapture() {
            runOnUiThread(::pararCompartilhamento)
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray,
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)

        // A da notificacao nao e fatal: so afeta o aviso enquanto transmite.
        if (requestCode != REQ_PERMISSOES) return

        if (grantResults.any { it != PackageManager.PERMISSION_GRANTED }) {
            Toast.makeText(
                this,
                "Sem permissão de câmera e microfone você só consegue assistir.",
                Toast.LENGTH_LONG,
            ).show()
        }
    }

    override fun onDestroy() {
        pararCompartilhamento()
        server?.stop()
        webView.loadUrl("about:blank")
        webView.destroy()
        super.onDestroy()
    }

    private companion object {
        const val REQ_PERMISSOES = 1001
        const val REQ_NOTIFICACOES = 1002

        /** O mesmo fundo do CSS da pagina, para nao piscar branco ao abrir. */
        const val FUNDO = 0xFF05070A.toInt()

        /** Escapa um texto para caber dentro de aspas no JavaScript. */
        fun paraJs(texto: String?): String {
            if (texto == null) return "null"
            val escapado = texto
                .replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", " ")
            return "\"$escapado\""
        }
    }
}
