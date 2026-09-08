package com.kashif1729.fastshare.network

import android.content.Context
import com.kashif1729.fastshare.model.TransferProgress
import com.kashif1729.fastshare.model.TransferState
import com.kashif1729.fastshare.network.TransferProtocol
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.io.*
import java.net.ServerSocket
import java.net.Socket
import java.security.MessageDigest

class TransferServer(private val context: Context) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val _progress = MutableStateFlow<TransferProgress?>(null)
    val progress: StateFlow<TransferProgress?> = _progress
    private var server: ServerSocket? = null
    fun start(): Int {
        if (server != null) return server!!.localPort
        server = ServerSocket(0)
        scope.launch { while (isActive && server != null) try { launch { receive(server!!.accept()) } } catch (_: Exception) {} }
        return server!!.localPort
    }
    private fun receive(socket: Socket) {
        socket.use {
            try {
                val input = DataInputStream(it.getInputStream().buffered(64 * 1024))
                val output = DataOutputStream(it.getOutputStream().buffered(64 * 1024))
                TransferProtocol.readHello(input)
                val offer = TransferProtocol.readOffer(input)
                _progress.value = TransferProgress(offer.transferId, offer.fileName, offer.size, 0, TransferState.WAITING)
                TransferProtocol.writeAccept(output)
                val dir = File(context.getExternalFilesDir(android.os.Environment.DIRECTORY_DOWNLOADS), "FastShare").apply { mkdirs() }
                val safe = offer.fileName.substringAfterLast('/').substringAfterLast('\\').ifBlank { "received_file" }
                val file = uniqueFile(dir, safe)
                FileOutputStream(file).use { fos ->
                    val digest = MessageDigest.getInstance("SHA-256")
                    val buffer = ByteArray(64 * 1024); var remaining = offer.size; var done = 0L; val started = System.nanoTime()
                    while (remaining > 0) {
                        val n = input.read(buffer, 0, minOf(buffer.size.toLong(), remaining).toInt())
                        if (n < 0) throw EOFException("Connection ended early")
                        fos.write(buffer, 0, n); digest.update(buffer, 0, n); remaining -= n; done += n
                        val sec = (System.nanoTime() - started) / 1_000_000_000.0
                        _progress.value = _progress.value!!.copy(transferred = done, state = TransferState.TRANSFERRING, speedBytesPerSecond = if (sec > 0) (done / sec).toLong() else 0)
                    }
                    fos.fd.sync()
                    val remote = TransferProtocol.readComplete(input)
                    val local = digest.digest().joinToString("") { "%02x".format(it) }
                    if (remote != local) throw IllegalStateException("Hash mismatch")
                }
                _progress.value = _progress.value!!.copy(transferred = offer.size, state = TransferState.COMPLETED)
            } catch (_: Exception) { _progress.value = _progress.value?.copy(state = TransferState.FAILED) }
        }
    }
    private fun uniqueFile(dir: File, name: String): File {
        var f = File(dir, name); if (!f.exists()) return f
        val base = name.substringBeforeLast('.', name); val ext = if (name.contains('.')) "." + name.substringAfterLast('.') else ""
        var i = 1; while (f.exists()) f = File(dir, "$base ($i)$ext").also { i++ }; return f
    }
    fun stop() { try { server?.close() } catch (_: Exception) {}; server = null; scope.coroutineContext.cancelChildren() }
}
