package me.blacknaut.greenlabs

import java.io.DataInputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.assertTrue
import org.junit.Test

class ScreenStreamServerTest {
    private fun connect(port: Int, receiveBuffer: Int): Socket {
        val socket = Socket()
        socket.receiveBufferSize = receiveBuffer
        socket.soTimeout = 3000
        socket.connect(InetSocketAddress("127.0.0.1", port))
        socket.getOutputStream().write("GET /stream HTTP/1.1\r\nHost: localhost\r\n\r\n".toByteArray())
        val input = socket.getInputStream()
        var last = ""
        while (!last.endsWith("\r\n\r\n")) {
            val byte = input.read()
            check(byte >= 0)
            last = (last + byte.toChar()).takeLast(4)
        }
        return socket
    }

    @Test
    fun slowViewerDoesNotBlockCaptureOrOtherViewer() {
        val server = ScreenStreamServer()
        val port = server.start()
        val slow = connect(port, 1024)
        val healthy = connect(port, 1024 * 1024)
        val received = AtomicInteger()
        val reader = Thread {
            try {
                val input = DataInputStream(healthy.getInputStream())
                while (!healthy.isClosed) {
                    val size = input.readInt()
                    check(size == 1024 * 1024)
                    input.readFully(ByteArray(size))
                    received.incrementAndGet()
                }
            } catch (_: Exception) {
                // Closing the server/socket terminates the reader.
            }
        }
        reader.isDaemon = true
        reader.start()
        try {
            val frame = ByteArray(1024 * 1024)
            val start = System.nanoTime()
            repeat(100) { server.pushFrame(frame); Thread.sleep(10) }
            assertTrue("capture blocked on a viewer", System.nanoTime() - start < 4_000_000_000L)
            val deadline = System.nanoTime() + 3_000_000_000L
            while (received.get() < 10 && System.nanoTime() < deadline) Thread.sleep(10)
            assertTrue("healthy viewer stopped receiving", received.get() >= 10)
        } finally {
            server.stop()
            slow.close()
            healthy.close()
            reader.join(3000)
        }
    }
}
