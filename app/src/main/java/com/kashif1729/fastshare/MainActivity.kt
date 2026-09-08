package com.kashif1729.fastshare

import android.Manifest
import android.content.Intent
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.kashif1729.fastshare.network.TransferServer
import com.kashif1729.fastshare.model.*
import com.kashif1729.fastshare.network.*
import com.kashif1729.fastshare.transfer.TransferService
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    private val vm by viewModels<MainViewModel>()
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestRuntimePermissions()
        vm.start()
        setContent { FastShareTheme { FastShareApp(vm) } }
    }
    private fun requestRuntimePermissions() {
        val permissions = buildList {
            add(Manifest.permission.POST_NOTIFICATIONS)
            if (Build.VERSION.SDK_INT >= 31) { add(Manifest.permission.BLUETOOTH_SCAN); add(Manifest.permission.BLUETOOTH_CONNECT) }
            if (Build.VERSION.SDK_INT >= 33) add(Manifest.permission.NEARBY_WIFI_DEVICES)
        }
        if (permissions.isNotEmpty()) requestPermissions(permissions.toTypedArray(), 42)
    }
    override fun onDestroy() { vm.stop(); super.onDestroy() }
}

class MainViewModel(private val app: android.app.Application) : ViewModel() {
    private val discovery = NsdDiscovery(app)
    private val server = TransferServer(app)
    private val client = TransferClient(app)
    val devices = discovery.devices
    private val _progress = MutableStateFlow<TransferProgress?>(null)
    val progress: StateFlow<TransferProgress?> = _progress.asStateFlow()
    var selectedDevice by mutableStateOf<Device?>(null); private set
    init {
        viewModelScope.launch { server.progress.collect { _progress.value = it } }
        viewModelScope.launch { client.progress.collect { _progress.value = it } }
    }
    fun selectDevice(d: Device) { selectedDevice = d }
    fun start() {
        val port = server.start(); discovery.start(port)
        val prefs = app.getSharedPreferences("identity", 0)
        if (!prefs.contains("name")) prefs.edit().putString("name", "My Android").apply()
        val intent = Intent(app, TransferService::class.java)
        if (Build.VERSION.SDK_INT >= 26) app.startForegroundService(intent) else app.startService(intent)
    }
    fun send(files: List<SelectedFile>) { val d = selectedDevice ?: return; viewModelScope.launch { runCatching { client.send(d.host, d.port, files) }.onFailure { _progress.value = _progress.value?.copy(state = TransferState.FAILED) } } }
    fun stop() { discovery.stop(); server.stop() }
    override fun onCleared() { stop(); super.onCleared() }
}

@Composable fun FastShareApp(vm: MainViewModel) {
    var selectedFiles by remember { mutableStateOf<List<SelectedFile>>(emptyList()) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        val context = androidx.compose.ui.platform.LocalContext.current
        selectedFiles = uris.map { uri ->
            val cr = context.contentResolver
            val name = cr.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c -> if (c.moveToFirst()) c.getString(0) else null } ?: "file"
            val size = cr.openAssetFileDescriptor(uri, "r")?.use { it.length }?.coerceAtLeast(0) ?: 0
            SelectedFile(uri, name, size, cr.getType(uri))
        }
    }
    val devices by vm.devices.collectAsState(); val progress by vm.progress.collectAsState()
    Scaffold(topBar = {
        @OptIn(ExperimentalMaterial3Api::class)
        TopAppBar(title = { Text("Fast Share", fontWeight = FontWeight.Bold) }, actions = { IconButton({}) { Icon(Icons.Default.Bolt, "Fast") } })
    }) { pad ->
        Column(Modifier.fillMaxSize().padding(pad).padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Text("Nearby devices", style = MaterialTheme.typography.titleLarge)
            if (devices.isEmpty()) Card(Modifier.fillMaxWidth()) { Column(Modifier.padding(18.dp)) { Icon(Icons.Default.Wifi, null); Spacer(Modifier.height(6.dp)); Text("Looking for Fast Share devices…"); Text("Both devices should be on the same local Wi-Fi network.", style = MaterialTheme.typography.bodySmall) } }
            else LazyColumn(Modifier.weight(1f, false), verticalArrangement = Arrangement.spacedBy(8.dp)) { items(devices, key = { it.id }) { d -> DeviceCard(d, vm.selectedDevice?.id == d.id) { vm.selectDevice(d) } } }
            OutlinedButton({ picker.launch(arrayOf("*/*")) }, Modifier.fillMaxWidth().height(52.dp)) { Icon(Icons.Default.AttachFile, null); Spacer(Modifier.width(8.dp)); Text(if (selectedFiles.isEmpty()) "Select files" else "${selectedFiles.size} selected") }
            selectedFiles.take(3).forEach { Text("• ${it.name}  ${formatBytes(it.size)}") }
            Button({ vm.send(selectedFiles) }, enabled = vm.selectedDevice != null && selectedFiles.isNotEmpty(),
                modifier = Modifier.fillMaxWidth().height(54.dp)
            ) { Icon(Icons.Default.Send, null); Spacer(Modifier.width(8.dp)); Text("SEND") }
            progress?.let { TransferCard(it) }
        }
    }
}

@Composable fun DeviceCard(d: Device, selected: Boolean, click: () -> Unit) { Card(onClick = click, Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant)) { Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) { Icon(Icons.Default.PhoneAndroid, null, Modifier.size(32.dp)); Spacer(Modifier.width(14.dp)); Column(Modifier.weight(1f)) { Text(d.name, style = MaterialTheme.typography.titleMedium); Text("Nearby", style = MaterialTheme.typography.bodySmall) }; if (selected) Icon(Icons.Default.CheckCircle, null) } } }

@Composable fun TransferCard(p: TransferProgress) { val f = if (p.total > 0) p.transferred.toFloat() / p.total else 0f; Card(Modifier.fillMaxWidth()) { Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) { Text(p.fileName, fontWeight = FontWeight.SemiBold); LinearProgressIndicator({ f.coerceIn(0f, 1f) }, Modifier.fillMaxWidth()); Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) { Text("${formatBytes(p.transferred)} / ${formatBytes(p.total)}"); Text(if (p.speedBytesPerSecond > 0) "${formatBytes(p.speedBytesPerSecond)}/s" else p.state.name) } } } }

fun formatBytes(v: Long): String { if (v < 1024) return "$v B"; var x=v.toDouble(); val u=arrayOf("KB","MB","GB","TB"); var i=-1; while(x>=1024 && i<u.lastIndex){x/=1024;i++}; return "%.1f %s".format(x,u[i]) }
@Composable fun FastShareTheme(content: @Composable () -> Unit) { MaterialTheme(if (androidx.compose.foundation.isSystemInDarkTheme()) darkColorScheme() else lightColorScheme(), content = content) }