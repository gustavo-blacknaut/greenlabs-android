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
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Semaphore
import java.util.concurrent.atomic.AtomicReference

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
    private class Viewer(val socket: Socket, val output: OutputStream) {
        val latestFrame = AtomicReference<ByteArray?>(null)
        val frameReady = Semaphore(0)
        var worker: Thread? = null
    }

    @Volatile
    private var running = false

    /**
     * Conjunto que aguenta escrita concorrente durante a iteracao.
     *
     * `pushFrame` percorre os clientes e remove os que cairam no meio do
     * proprio laco - com uma lista comum isso seria
     * ConcurrentModificationException.
     */
    private val clients = ConcurrentHashMap<Socket, Viewer>()
    private val connections = CopyOnWriteArraySet<Socket>()

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
        for (viewer in clients.values) {
            viewer.latestFrame.set(null)
            viewer.worker?.interrupt()
        }
        for (client in connections) runCatching { client.close() }
        connections.clear()
        clients.clear()
        runCatching { socket?.close() }
    }

    private fun acceptLoop() {
        while (running) {
            try {
                val client = socket?.accept() ?: break
                val accepted = synchronized(connections) {
                    if (!running || connections.size >= 4) {
                        false
                    } else {
                        connections.add(client)
                        true
                    }
                }
                if (!accepted) { client.close(); continue }
                Thread({ handle(client) }, "screen-stream-client").apply { isDaemon = true; start() }
            } catch (e: IOException) {
                // Fechar o socket para sair do laco tambem lanca aqui; so
                // interessa o que acontece enquanto ainda deveria estar no ar.
                if (running) Log.w(TAG, "accept falhou: ${e.message}")
            }
        }
    }

    private fun handle(client: Socket) {
        try {
            client.soTimeout = 5000
            client.tcpNoDelay = true
            val entrada = PushbackInputStream(client.getInputStream(), 1)

            // O pedido em si nao importa - existe uma rota so - mas precisa ser
            // consumido antes de a resposta poder ser escrita.
            var headerBytes = 0
            while (true) {
                val linha = readLine(entrada) ?: break
                headerBytes += linha.length
                if (headerBytes > 32768) throw IOException("cabecalho muito grande")
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
            synchronized(connections) {
                if (running) {
                    val viewer = Viewer(client, saida)
                    clients[client] = viewer
                    viewer.worker = Thread({ sendLoop(viewer) }, "screen-stream-send").apply {
                        isDaemon = true
                        start()
                    }
                } else {
                    connections.remove(client)
                    client.close()
                }
            }
        } catch (_: IOException) {
            connections.remove(client)
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
        if (!running || jpeg == null || jpeg.isEmpty() || clients.isEmpty()) return
        // A captura nunca espera a WebView: só um quadro pendente, sempre o mais recente.
        for (viewer in clients.values) {
            if (viewer.latestFrame.getAndSet(jpeg) == null) viewer.frameReady.release()
        }
    }

    private fun sendLoop(viewer: Viewer) {
        try {
            while (running && !viewer.socket.isClosed) {
                viewer.frameReady.acquire()
                if (!running) break
                val jpeg = viewer.latestFrame.getAndSet(null) ?: continue
                sendFrame(viewer.output, jpeg)
            }
        } catch (_: IOException) {
            // Cada cliente tem sua propria escrita; um consumidor lento nao segura os outros.
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        } finally {
            clients.remove(viewer.socket, viewer)
            connections.remove(viewer.socket)
            viewer.latestFrame.set(null)
            runCatching { viewer.socket.close() }
        }
    }

    private fun sendFrame(out: OutputStream, jpeg: ByteArray) {

        val tamanho = jpeg.size
        val cabecalho = byteArrayOf(
            (tamanho ushr 24).toByte(),
            (tamanho ushr 16).toByte(),
            (tamanho ushr 8).toByte(),
            tamanho.toByte(),
        )

        out.write(cabecalho)
        out.write(jpeg)
        out.flush()
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
                if (buffer.size() > 8192) throw IOException("linha muito grande")
                b = entrada.read()
            }

            if (b == -1 && buffer.size() == 0) return null
            return buffer.toString("UTF-8")
        }
    }
}
