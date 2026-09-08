package com.kashif1729.fastshare.network

import android.content.Context
import com.kashif1729.fastshare.model.SelectedFile
import com.kashif1729.fastshare.model.TransferProgress
import com.kashif1729.fastshare.model.TransferState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import java.io.DataInputStream
import java.io.DataOutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.security.MessageDigest
import java.util.UUID

class TransferClient(private val context: Context) {
    private val _progress = MutableStateFlow<TransferProgress?>(null)
    val progress: StateFlow<TransferProgress?> = _progress

    suspend fun send(host: String, port: Int, files: List<SelectedFile>) = withContext(Dispatchers.IO) {
        for (file in files) sendOne(host, port, file)
    }
    private fun sendOne(host: String, port: Int, file: SelectedFile) {
        val id = UUID.randomUUID().toString()
        _progress.value = TransferProgress(id, file.name, file.size, 0, TransferState.CONNECTING)
        Socket().use { socket ->
            socket.tcpNoDelay = true; socket.keepAlive = true; socket.sendBufferSize = 256 * 1024
            socket.connect(InetSocketAddress(host, port), 5000)
            val input = DataInputStream(socket.getInputStream().buffered(64 * 1024))
            val output = DataOutputStream(socket.getOutputStream().buffered(64 * 1024))
            val prefs = context.getSharedPreferences("identity", 0)
            TransferProtocol.writeHello(output, prefs.getString("id", "unknown")!!, prefs.getString("name", "My Android")!!)
            TransferProtocol.writeOffer(output, id, file.name, file.size, file.mimeType)
            _progress.value = _progress.value!!.copy(state = TransferState.WAITING)
            if (!TransferProtocol.readDecision(input)) { _progress.value = _progress.value!!.copy(state = TransferState.CANCELLED); return }
            val digest = MessageDigest.getInstance("SHA-256")
            val buffer = ByteArray(64 * 1024)
            var done = 0L; val started = System.nanoTime()
            context.contentResolver.openInputStream(file.uri)?.use { source ->
                while (done < file.size) {
                    val n = source.read(buffer, 0, minOf(buffer.size.toLong(), file.size - done).toInt())
                    if (n < 0) error("Source ended early")
                    output.write(buffer, 0, n); digest.update(buffer, 0, n); done += n
                    val seconds = (System.nanoTime() - started) / 1_000_000_000.0
                    _progress.value = _progress.value!!.copy(transferred = done, state = TransferState.TRANSFERRING, speedBytesPerSecond = if (seconds > 0) (done / seconds).toLong() else 0)
                }
            } ?: error("Cannot open ${file.name}")
            output.flush()
            TransferProtocol.writeComplete(output, digest.digest().joinToString("") { "%02x".format(it) })
            _progress.value = _progress.value!!.copy(state = TransferState.COMPLETED)
        }
    }
}
