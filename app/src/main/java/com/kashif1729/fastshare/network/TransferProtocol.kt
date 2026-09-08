package com.kashif1729.fastshare.network

import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.EOFException

object TransferProtocol {
    private const val MAGIC = 0x46535632
    const val SERVICE_TYPE = "_fastshare._tcp."

    fun writeHello(out: DataOutputStream, id: String, name: String) {
        out.writeInt(MAGIC); out.writeUTF("HELLO"); out.writeUTF(id); out.writeUTF(name); out.flush()
    }
    fun readHello(input: DataInputStream): Pair<String,String> {
        if (input.readInt() != MAGIC) throw EOFException("Invalid Fast Share connection")
        if (input.readUTF() != "HELLO") throw EOFException("Expected HELLO")
        return input.readUTF() to input.readUTF()
    }
    fun writeOffer(out: DataOutputStream, id: String, name: String, size: Long, mime: String?) {
        out.writeUTF("OFFER"); out.writeUTF(id); out.writeUTF(name); out.writeLong(size); out.writeUTF(mime ?: "application/octet-stream"); out.flush()
    }
    fun readOffer(input: DataInputStream): Offer {
        if (input.readUTF() != "OFFER") throw EOFException("Expected OFFER")
        return Offer(input.readUTF(), input.readUTF(), input.readLong(), input.readUTF())
    }
    fun writeAccept(out: DataOutputStream) { out.writeUTF("ACCEPT"); out.flush() }
    fun writeReject(out: DataOutputStream) { out.writeUTF("REJECT"); out.flush() }
    fun readDecision(input: DataInputStream) = input.readUTF() == "ACCEPT"
    fun writeComplete(out: DataOutputStream, hash: String) { out.writeUTF("COMPLETE"); out.writeUTF(hash); out.flush() }
    fun readComplete(input: DataInputStream): String { if (input.readUTF() != "COMPLETE") throw EOFException("Expected COMPLETE"); return input.readUTF() }
    data class Offer(val transferId: String, val fileName: String, val size: Long, val mimeType: String)
}
