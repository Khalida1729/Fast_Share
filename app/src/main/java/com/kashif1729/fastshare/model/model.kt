package com.kashif1729.fastshare.model

import android.net.Uri

data class Device(val id: String, val name: String, val host: String, val port: Int)
data class SelectedFile(val uri: Uri, val name: String, val size: Long, val mimeType: String?)
enum class TransferState { CONNECTING, WAITING, TRANSFERRING, COMPLETED, FAILED, CANCELLED }
data class TransferProgress(val id: String, val fileName: String, val total: Long, val transferred: Long, val state: TransferState, val speedBytesPerSecond: Long = 0)