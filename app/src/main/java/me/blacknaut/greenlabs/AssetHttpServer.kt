package me.blacknaut.greenlabs

import android.content.res.AssetManager
import android.util.Log
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.OutputStream
import java.io.PushbackInputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.Locale
import java.util.concurrent.Executors

/**
 * Serve o cliente web embutido por http://127.0.0.1.
 *
 * A origem importa: `file://` nao e contexto seguro, entao o getUserMedia
 * seria bloqueado; e uma origem `https://` bloquearia as conexoes `ws://` para
 * um servidor de sinalizacao na rede local. O `http://` de loopback e tratado
 * como seguro pelo Chromium e ainda permite `ws://` - atende os dois.
 */
internal class AssetHttpServer(private val assets: AssetManager) {

    private val workers = Executors.newFixedThreadPool(4)
    private var socket: ServerSocket? = null
    private var acceptThread: Thread? = null

    @Volatile
    private var running = false

    /** Abre a porta e devolve o endereco base. */
    fun start(): String {
        val aberto = try {
            ServerSocket(PORTA_PREFERIDA, 8, InetAddress.getByName("127.0.0.1"))
        } catch (e: IOException) {
            Log.w(TAG, "porta preferida ocupada, sorteando outra: ${e.message}")
            ServerSocket(0, 8, InetAddress.getByName("127.0.0.1"))
        }
        socket = aberto
        running = true

        acceptThread = Thread(::acceptLoop, "asset-http-accept").apply {
            isDaemon = true
            start()
        }
        return "http://127.0.0.1:${aberto.localPort}"
    }

    fun stop() {
        running = false
        runCatching { socket?.close() }
        workers.shutdownNow()
    }

    private fun acceptLoop() {
        while (running) {
            try {
                val client = socket?.accept() ?: break
                workers.execute { handle(client) }
            } catch (e: IOException) {
                if (running) Log.w(TAG, "accept falhou: ${e.message}")
            }
        }
    }

    private fun handle(client: Socket) {
        try {
            client.use { conexao ->
                val entrada = PushbackInputStream(conexao.getInputStream(), 1)

                val pedido = readLine(entrada)
                if (pedido.isNullOrEmpty()) return

                // Os cabecalhos nao interessam, mas precisam ser consumidos
                // antes de a resposta poder ser escrita.
                while (true) {
                    val linha = readLine(entrada) ?: break
                    if (linha.isEmpty()) break
                }

                val partes = pedido.split(" ")
                if (partes.size < 2) {
                    responder(conexao.getOutputStream(), 400, "text/plain", "pedido invalido".toByteArray())
                    return
                }

                val caminho = partes[1]
                    .substringBefore('?')
                    .ifEmpty { "/" }
                    .let { if (it == "/") "/index.html" else it }

                val noAsset = RAIZ + normalizar(caminho)
                val conteudo = lerAsset(noAsset)

                if (conteudo != null) {
                    responder(conexao.getOutputStream(), 200, tipoDe(noAsset), conteudo)
                    return
                }

                // Aplicativo de pagina unica: caminho desconhecido cai no
                // ponto de entrada, e o roteamento acontece no JavaScript.
                val indice = lerAsset("$RAIZ/index.html")
                if (indice == null) {
                    responder(conexao.getOutputStream(), 404, "text/plain", "nao encontrado".toByteArray())
                    return
                }
                responder(conexao.getOutputStream(), 200, "text/html; charset=utf-8", indice)
            }
        } catch (e: IOException) {
            Log.w(TAG, "falha ao atender: ${e.message}")
        }
    }

    private fun lerAsset(caminho: String): ByteArray? =
        try {
            assets.open(caminho).use { it.readBytes() }
        } catch (_: IOException) {
            null
        }

    private fun responder(saida: OutputStream, status: Int, tipo: String, corpo: ByteArray) {
        val cabecalho = buildString {
            append("HTTP/1.1 ").append(status).append(if (status == 200) " OK" else " ERROR").append("\r\n")
            append("Content-Type: ").append(tipo).append("\r\n")
            append("Content-Length: ").append(corpo.size).append("\r\n")
            append("Cache-Control: no-store\r\n")
            append("Connection: close\r\n\r\n")
        }
        saida.write(cabecalho.toByteArray())
        saida.write(corpo)
        saida.flush()
    }

    private companion object {
        const val TAG = "GreenLabsHttp"
        const val RAIZ = "web"

        /**
         * Porta fixa, e nao sorteada.
         *
         * O localStorage e separado por origem, e a origem inclui a porta.
         * Sorteando uma porta a cada abertura, cada sessao comecaria com um
         * armazenamento vazio - e o nome, o servidor e a sala que a pessoa
         * configurou na primeira vez se perderiam toda vez.
         */
        const val PORTA_PREFERIDA = 47869

        /** Impede sair da pasta de assets por `../`. */
        fun normalizar(caminho: String): String {
            var limpo = caminho.replace('\\', '/')
            while (limpo.contains("../")) limpo = limpo.replace("../", "")
            return if (limpo.startsWith("/")) limpo else "/$limpo"
        }

        fun readLine(entrada: PushbackInputStream): String? {
            val buffer = ByteArrayOutputStream()
            var b = entrada.read()

            while (b != -1) {
                if (b == '\n'.code) break
                if (b != '\r'.code) buffer.write(b)
                b = entrada.read()
            }

            if (b == -1 && buffer.size() == 0) return null
            return buffer.toString("UTF-8")
        }

        fun tipoDe(caminho: String): String {
            val p = caminho.lowercase(Locale.ROOT)
            return when {
                p.endsWith(".html") -> "text/html; charset=utf-8"
                p.endsWith(".js") || p.endsWith(".mjs") -> "application/javascript; charset=utf-8"
                p.endsWith(".css") -> "text/css; charset=utf-8"
                p.endsWith(".json") -> "application/json; charset=utf-8"
                p.endsWith(".svg") -> "image/svg+xml"
                p.endsWith(".png") -> "image/png"
                p.endsWith(".jpg") || p.endsWith(".jpeg") -> "image/jpeg"
                p.endsWith(".webp") -> "image/webp"
                p.endsWith(".ico") -> "image/x-icon"
                p.endsWith(".woff2") -> "font/woff2"
                p.endsWith(".woff") -> "font/woff"
                else -> "application/octet-stream"
            }
        }
    }
}
