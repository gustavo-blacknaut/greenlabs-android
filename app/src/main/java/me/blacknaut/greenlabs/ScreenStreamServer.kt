package me.blacknaut.greenlabs

import android.util.Log
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.OutputStream
import java.io.PushbackInputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.nio.charset.StandardCharsets
import java.util.concurrent.CopyOnWriteArraySet

/**
 * Servidor HTTP local que empurra os quadros capturados para a WebView.
 *
 * Tem o mesmo formato do endpoint de audio do aplicativo de desktop: uma rota
 * GET so, devolvendo um fluxo de registros que carregam o proprio tamanho
 * (4 bytes big-endian seguidos do conteudo), um JPEG por quadro. E mais simples
 * dos dois lados que um multipart/x-mixed-replace de verdade, e o JavaScript ja
 * conhece este formato de quando le o audio.
 */
internal class ScreenStreamServer {

    private var socket: ServerSocket? = null
    private var acceptThread: Thread? = null

    @Volatile
    private var running = false

    /**
     * Conjunto que aguenta escrita concorrente durante a iteracao.
     *
     * `pushFrame` percorre os clientes e remove os que cairam no meio do
     * proprio laco - com uma lista comum isso seria
     * ConcurrentModificationException.
     */
    private val clients = CopyOnWriteArraySet<OutputStream>()

    /** Devolve a porta sorteada pelo sistema. */
    fun start(): Int {
        // Porta 0: o sistema escolhe uma livre. Fixar uma porta daria conflito
        // com qualquer outro aplicativo que ja a estivesse usando.
        val aberto = ServerSocket(0, 4, InetAddress.getByName("127.0.0.1"))
        socket = aberto
        running = true

        acceptThread = Thread(::acceptLoop, "screen-stream-accept").apply {
            isDaemon = true
            start()
        }
        return aberto.localPort
    }

    fun stop() {
        running = false
        for (out in clients) {
            runCatching { out.close() }
        }
        clients.clear()
        runCatching { socket?.close() }
    }

    private fun acceptLoop() {
        while (running) {
            try {
                val client = socket?.accept() ?: break
                Thread({ handle(client) }, "screen-stream-client").start()
            } catch (e: IOException) {
                // Fechar o socket para sair do laco tambem lanca aqui; so
                // interessa o que acontece enquanto ainda deveria estar no ar.
                if (running) Log.w(TAG, "accept falhou: ${e.message}")
            }
        }
    }

    private fun handle(client: Socket) {
        try {
            val entrada = PushbackInputStream(client.getInputStream(), 1)

            // O pedido em si nao importa - existe uma rota so - mas precisa ser
            // consumido antes de a resposta poder ser escrita.
            while (true) {
                val linha = readLine(entrada) ?: break
                if (linha.isEmpty()) break
            }

            val saida = client.getOutputStream()

            // A pagina e servida de 127.0.0.1:<porta dos assets> e este fluxo
            // vive em outra porta - origem diferente, para o navegador. Sem
            // estes cabecalhos o fetch e bloqueado antes de chegar aqui, e do
            // lado do JavaScript aparece so um "Failed to fetch" sem motivo.
            saida.write(
                (
                    "HTTP/1.1 200 OK\r\n" +
                        "Content-Type: application/octet-stream\r\n" +
                        "Access-Control-Allow-Origin: *\r\n" +
                        "Cache-Control: no-store\r\n" +
                        "Connection: close\r\n\r\n"
                    ).toByteArray(StandardCharsets.US_ASCII),
            )
            saida.flush()
            clients.add(saida)
        } catch (_: IOException) {
            runCatching { client.close() }
        }
    }

    /**
     * Manda um quadro para todos os clientes.
     *
     * Quem estiver atrasado perde o quadro em vez de segurar os outros: ao vivo,
     * imagem velha nao vale a espera que custa.
     */
    fun pushFrame(jpeg: ByteArray?) {
        if (jpeg == null || jpeg.isEmpty() || clients.isEmpty()) return

        val tamanho = jpeg.size
        val cabecalho = byteArrayOf(
            (tamanho ushr 24).toByte(),
            (tamanho ushr 16).toByte(),
            (tamanho ushr 8).toByte(),
            tamanho.toByte(),
        )

        for (out in clients) {
            try {
                // O synchronized e por cliente: dois quadros ao mesmo tempo no
                // mesmo socket embaralhariam cabecalho e conteudo.
                synchronized(out) {
                    out.write(cabecalho)
                    out.write(jpeg)
                    out.flush()
                }
            } catch (_: IOException) {
                clients.remove(out)
                runCatching { out.close() }
            }
        }
    }

    private companion object {
        const val TAG = "GreenLabsScreen"

        /** Le uma linha do cabecalho HTTP. Nulo quando o fluxo acaba. */
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
    }
}
