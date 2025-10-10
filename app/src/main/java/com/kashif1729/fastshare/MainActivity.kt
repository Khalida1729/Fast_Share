package com.kashif1729.fastshare

import android.Manifest
import android.app.Activity
import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.net.wifi.WifiManager
import android.net.wifi.p2p.WifiP2pManager
import android.os.Build
import android.os.Bundle
import android.provider.OpenableColumns
import android.provider.Settings
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Chat
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.AttachFile
import androidx.compose.material.icons.filled.Chat
import androidx.compose.material.icons.filled.Contacts
import androidx.compose.material.icons.filled.PersonAdd
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Send
import androidx.compose.material.icons.filled.Stop
//import androidx.compose.material.icons.Icons
import androidx.compose.material3.Icon
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.*
import java.net.*
import java.text.SimpleDateFormat
import java.util.*
import androidx.compose.material3.NavigationBar
import com.google.android.material.bottomnavigation.BottomNavigationItemView
import com.google.android.material.bottomnavigation.BottomNavigationMenuView
import com.google.android.material.bottomnavigation.BottomNavigationView

class MainActivity : ComponentActivity() {
    private val TAG = "FastSharePro"
    private lateinit var connectionManager: ConnectionManager
    private lateinit var contactManager: ContactManager
    private lateinit var chatManager: ChatManager

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        connectionManager = ConnectionManager(this)
        contactManager = ContactManager(this)
        chatManager = ChatManager(this, connectionManager, contactManager)

        setContent {
            FastShareProApp(
                connectionManager = connectionManager,
                contactManager = contactManager,
                chatManager = chatManager,
                activity = this
            )
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        connectionManager.cleanup()
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FastShareProApp(
    connectionManager: ConnectionManager,
    contactManager: ContactManager,
    chatManager: ChatManager,
    activity: Activity
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var selectedTab by remember { mutableStateOf(0) }
    var showAddContact by remember { mutableStateOf(false) }
    var newContactIp by remember { mutableStateOf("") }
    var newContactName by remember { mutableStateOf("") }

    val contacts by contactManager.contacts.collectAsState()
    val currentConnection by connectionManager.currentConnection.collectAsState()
    val messages by chatManager.messages.collectAsState()

    // Permission launcher
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        if (permissions.all { it.value }) {
            scope.launch {
                connectionManager.startReceiver()
            }
        }
    }

    // File picker launcher
    val filePicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        uri?.let {
            val selectedContact = contacts.find { contact -> contact.isSelected }
            selectedContact?.let { contact ->
                scope.launch {
                    chatManager.sendFile(contact, uri)
                }
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text("FastShare Pro", fontWeight = FontWeight.Bold)
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                    titleContentColor = MaterialTheme.colorScheme.primary
                ),
                actions = {
                    // Connection status indicator
                    ConnectionStatusIndicator(currentConnection)

                    IconButton(onClick = {
                        scope.launch {
                            connectionManager.toggleReceiver()
                        }
                    }) {
                        Icon(
                            imageVector = if (currentConnection.isReceiving)
                                Icons.Default.Stop else Icons.Default.PlayArrow,
                            contentDescription = "Toggle Receiver"
                        )
                    }
                }
            )
        },
        bottomBar = {
            BottomAppBar {
                BottomNavigationView
            }
//            BottomAppBar {
//                BottomNavigationView {
//                    BottomNavigationItem(
//                        selected = selectedTab == 0,
//                        onClick = { selectedTab = 0 },
//                        icon = { Icon(Icons.Default.Chat, "Chats") },
//                        label = { Text("Chats") }
//                    )
//                    BottomNavigationItem(
//                        selected = selectedTab == 1,
//                        onClick = { selectedTab = 1 },
//                        icon = { Icon(Icons.Default.Contacts, "Contacts") },
//                        label = { Text("Contacts") }
//                    )
//                    BottomNavigationItem(
//                        selected = selectedTab == 2,
//                        onClick = { selectedTab = 2 },
//                        icon = { Icon(Icons.Default.Settings, "Settings") },
//                        label = { Text("Settings") }
//                    )
//                }

        },
        floatingActionButton = {
            when (selectedTab) {
                0 -> {
                    val selectedContact = contacts.find { it.isSelected }
                    if (selectedContact != null) {
                        FloatingActionButton(
                            onClick = { filePicker.launch(arrayOf("*/*")) },
                            containerColor = MaterialTheme.colorScheme.primary
                        ) {
                            Icon(Icons.Default.AttachFile, "Send File")
                        }
                    }
                }
                1 -> {
                    FloatingActionButton(
                        onClick = { showAddContact = true },
                        containerColor = MaterialTheme.colorScheme.primary
                    ) {
                        Icon(Icons.Default.PersonAdd, "Add Contact")
                    }
                }
            }
        }
    ) { padding ->
        Box(modifier = Modifier.padding(padding)) {
            when (selectedTab) {
                0 -> ChatScreen(
                    contacts = contacts,
                    messages = messages,
                    onContactSelect = { contact ->
                        contactManager.selectContact(contact.id)
                        scope.launch {
                            chatManager.loadMessages(contact.id)
                        }
                    },
                    onSendMessage = { message ->
                        val selectedContact = contacts.find { it.isSelected }
                        selectedContact?.let { contact ->
                            scope.launch {
                                chatManager.sendMessage(contact, message)
                            }
                        }
                    }
                )
                1 -> ContactsScreen(
                    contacts = contacts,
                    onContactSelect = { contact ->
                        contactManager.selectContact(contact.id)
                        selectedTab = 0 // Switch to chat tab
                    },
                    onContactDelete = { contact ->
                        contactManager.removeContact(contact.id)
                    }
                )
                2 -> SettingsScreen(connectionManager, contactManager)
            }

            // Add Contact Dialog
            if (showAddContact) {
                AlertDialog(
                    onDismissRequest = { showAddContact = false },
                    title = { Text("Add New Contact") },
                    text = {
                        Column {
                            OutlinedTextField(
                                value = newContactName,
                                onValueChange = { newContactName = it },
                                label = { Text("Contact Name") },
                                modifier = Modifier.fillMaxWidth()
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                            OutlinedTextField(
                                value = newContactIp,
                                onValueChange = { newContactIp = it },
                                label = { Text("IP Address") },
                                modifier = Modifier.fillMaxWidth()
                            )
                        }
                    },
                    confirmButton = {
                        TextButton(
                            onClick = {
                                if (newContactName.isNotBlank() && newContactIp.isNotBlank()) {
                                    contactManager.addContact(newContactName, newContactIp)
                                    newContactName = ""
                                    newContactIp = ""
                                    showAddContact = false
                                }
                            }
                        ) {
                            Text("Add")
                        }
                    },
                    dismissButton = {
                        TextButton(onClick = { showAddContact = false }) {
                            Text("Cancel")
                        }
                    }
                )
            }
        }
    }

    // Auto-start receiver and request permissions
    LaunchedEffect(Unit) {
        if (hasRequiredPermissions(context)) {
            connectionManager.startReceiver()
        } else {
            requestPermissions(permissionLauncher)
        }
    }
}

@Composable
fun ChatScreen(
    contacts: List<Contact>,
    messages: List<ChatMessage>,
    onContactSelect: (Contact) -> Unit,
    onSendMessage: (String) -> Unit
) {
    var messageText by remember { mutableStateOf("") }
    val selectedContact = contacts.find { it.isSelected }

    Row(modifier = Modifier.fillMaxSize()) {
        // Contacts List
        LazyColumn(
            modifier = Modifier
                .width(280.dp)
                .fillMaxHeight()
                .background(MaterialTheme.colorScheme.surfaceVariant)
        ) {
            items(contacts) { contact ->
                ContactListItem(
                    contact = contact,
                    isSelected = contact.isSelected,
                    onClick = { onContactSelect(contact) }
                )
            }
        }

        // Chat Area
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(16.dp)
        ) {
            if (selectedContact != null) {
                // Messages
                LazyColumn(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                ) {
                    items(messages) { message ->
                        MessageBubble(message = message)
                    }
                }

                // Message Input
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    OutlinedTextField(
                        value = messageText,
                        onValueChange = { messageText = it },
                        placeholder = { Text("Type a message...") },
                        modifier = Modifier.weight(1f)
                    )
                    IconButton(
                        onClick = {
                            if (messageText.isNotBlank()) {
                                onSendMessage(messageText)
                                messageText = ""
                            }
                        }
                    ) {
                        Icon(Icons.Default.Send, "Send Message")
                    }
                }
            } else {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    Text("Select a contact to start chatting", style = MaterialTheme.typography.bodyLarge)
                }
            }
        }
    }
}

@Composable
fun ContactListItem(contact: Contact, isSelected: Boolean, onClick: () -> Unit) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(8.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (isSelected) MaterialTheme.colorScheme.primaryContainer
            else MaterialTheme.colorScheme.surface
        ),
        onClick = onClick
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .background(MaterialTheme.colorScheme.primary, CircleShape),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = contact.name.take(2).uppercase(),
                    color = Color.White,
                    fontWeight = FontWeight.Bold
                )
            }
            Spacer(modifier = Modifier.width(12.dp))
            Column {
                Text(contact.name, fontWeight = FontWeight.Bold)
                Text(contact.ipAddress, style = MaterialTheme.typography.bodySmall)
                Text(
                    "Last seen: ${SimpleDateFormat("HH:mm").format(contact.lastSeen)}",
                    style = MaterialTheme.typography.bodySmall
                )
            }
        }
    }
}

@Composable
fun MessageBubble(message: ChatMessage) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(4.dp),
        horizontalArrangement = if (message.isSent) Arrangement.End else Arrangement.Start
    ) {
        Card(
            colors = CardDefaults.cardColors(
                containerColor = if (message.isSent) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.surfaceVariant
            )
        ) {
            Column(modifier = Modifier.padding(8.dp)) {
                when (message.type) {
                    MessageType.TEXT -> {
                        Text(message.content)
                    }
                    MessageType.FILE -> {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.AttachFile, "File")
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(message.content)
                        }
                    }
                }
                Text(
                    text = SimpleDateFormat("HH:mm").format(message.timestamp),
                    style = MaterialTheme.typography.labelSmall
                )
            }
        }
    }
}

@Composable
fun ConnectionStatusIndicator(connection: ConnectionStatus) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            modifier = Modifier
                .size(12.dp)
                .background(
                    color = when {
                        connection.isConnected -> Color.Green
                        connection.isReceiving -> Color.Yellow
                        else -> Color.Red
                    },
                    shape = CircleShape
                )
        )
        Spacer(modifier = Modifier.width(8.dp))
        Text(
            text = when {
                connection.isConnected -> "Connected"
                connection.isReceiving -> "Listening"
                else -> "Offline"
            },
            style = MaterialTheme.typography.labelSmall
        )
    }
}

// Permission helpers
private fun hasRequiredPermissions(context: Context): Boolean {
    val permissions = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        arrayOf(
            Manifest.permission.ACCESS_WIFI_STATE,
            Manifest.permission.CHANGE_WIFI_STATE,
            Manifest.permission.ACCESS_NETWORK_STATE,
            Manifest.permission.INTERNET,
            Manifest.permission.READ_EXTERNAL_STORAGE
        )
    } else {
        arrayOf(
            Manifest.permission.ACCESS_WIFI_STATE,
            Manifest.permission.CHANGE_WIFI_STATE,
            Manifest.permission.ACCESS_NETWORK_STATE,
            Manifest.permission.INTERNET,
            Manifest.permission.READ_EXTERNAL_STORAGE,
            Manifest.permission.WRITE_EXTERNAL_STORAGE
        )
    }
    return permissions.all {
        ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
    }
}

private fun requestPermissions(launcher: ActivityResultContracts.RequestMultiplePermissions) {
    val permissions = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        arrayOf(
            Manifest.permission.ACCESS_WIFI_STATE,
            Manifest.permission.CHANGE_WIFI_STATE,
            Manifest.permission.ACCESS_NETWORK_STATE,
            Manifest.permission.INTERNET,
            Manifest.permission.READ_EXTERNAL_STORAGE
        )
    } else {
        arrayOf(
            Manifest.permission.ACCESS_WIFI_STATE,
            Manifest.permission.CHANGE_WIFI_STATE,
            Manifest.permission.ACCESS_NETWORK_STATE,
            Manifest.permission.INTERNET,
            Manifest.permission.READ_EXTERNAL_STORAGE,
            Manifest.permission.WRITE_EXTERNAL_STORAGE
        )
    }
    launcher.launch(permissions.map { it }.toTypedArray())
}
// ConnectionManager.kt
class ConnectionManager(private val context: Context) {
    private val _currentConnection = MutableStateFlow(ConnectionStatus())
    val currentConnection = _currentConnection.asStateFlow()

    private var receiverJob: Job? = null
    private var serverSocket: ServerSocket? = null

    private val wifiManager = context.getSystemService(Context.WIFI_SERVICE) as WifiManager
    private val wifiP2pManager = context.getSystemService(Context.WIFI_P2P_SERVICE) as WifiP2pManager

    suspend fun startReceiver() {
        stopReceiver()

        receiverJob = CoroutineScope(Dispatchers.IO).launch {
            try {
                serverSocket = ServerSocket(8989)
                _currentConnection.value = _currentConnection.value.copy(
                    isReceiving = true,
                    localIp = getLocalIpAddress()
                )

                while (isActive) {
                    val socket = serverSocket!!.accept()
                    handleIncomingConnection(socket)
                }
            } catch (e: Exception) {
                Log.e("ConnectionManager", "Receiver error", e)
            }
        }
    }

    fun stopReceiver() {
        receiverJob?.cancel()
        serverSocket?.close()
        _currentConnection.value = _currentConnection.value.copy(isReceiving = false)
    }

    suspend fun toggleReceiver() {
        if (_currentConnection.value.isReceiving) {
            stopReceiver()
        } else {
            startReceiver()
        }
    }

    suspend fun sendFile(contact: Contact, fileUri: Uri): Boolean {
        return withContext(Dispatchers.IO) {
            try {
                // Try direct connection first (WiFi Direct or local network)
                if (isLocalConnectionPossible(contact)) {
                    sendViaLocalNetwork(contact, fileUri)
                } else {
                    sendViaInternet(contact, fileUri)
                }
                true
            } catch (e: Exception) {
                Log.e("ConnectionManager", "Send failed", e)
                false
            }
        }
    }

    private suspend fun sendViaLocalNetwork(contact: Contact, fileUri: Uri): Boolean {
        // Implementation for local network transfer (WiFi Direct or local WiFi)
        return true
    }

    private suspend fun sendViaInternet(contact: Contact, fileUri: Uri): Boolean {
        // Implementation for internet-based transfer
        return true
    }

    private fun isLocalConnectionPossible(contact: Contact): Boolean {
        // Check if both devices are on same network or can use WiFi Direct
        return true
    }

    fun cleanup() {
        stopReceiver()
    }
}

// ContactManager.kt
class ContactManager(private val context: Context) {
    private val _contacts = MutableStateFlow<List<Contact>>(emptyList())
    val contacts = _contacts.asStateFlow()

    private val sharedPrefs = context.getSharedPreferences("contacts", Context.MODE_PRIVATE)

    init {
        loadContacts()
    }

    fun addContact(name: String, ipAddress: String) {
        val newContact = Contact(
            id = UUID.randomUUID().toString(),
            name = name,
            ipAddress = ipAddress,
            lastSeen = Date(),
            isOnline = false
        )

        _contacts.value = _contacts.value + newContact
        saveContacts()
    }

    fun removeContact(contactId: String) {
        _contacts.value = _contacts.value.filter { it.id != contactId }
        saveContacts()
    }

    fun selectContact(contactId: String) {
        _contacts.value = _contacts.value.map { contact ->
            contact.copy(isSelected = contact.id == contactId)
        }
    }

    private fun loadContacts() {
        val contactsJson = sharedPrefs.getString("contacts_list", "[]")
        // Parse JSON and load contacts
    }

    private fun saveContacts() {
        // Save contacts to SharedPreferences
    }
}

// ChatManager.kt
class ChatManager(
    private val context: Context,
    private val connectionManager: ConnectionManager,
    private val contactManager: ContactManager
) {
    private val _messages = MutableStateFlow<List<ChatMessage>>(emptyList())
    val messages = _messages.asStateFlow()

    suspend fun sendMessage(contact: Contact, message: String) {
        val chatMessage = ChatMessage(
            id = UUID.randomUUID().toString(),
            contactId = contact.id,
            content = message,
            type = MessageType.TEXT,
            timestamp = Date(),
            isSent = true
        )

        _messages.value = _messages.value + chatMessage
        // Actually send via connection manager
    }

    suspend fun sendFile(contact: Contact, fileUri: Uri) {
        val fileName = getFileName(context.contentResolver, fileUri)
        val chatMessage = ChatMessage(
            id = UUID.randomUUID().toString(),
            contactId = contact.id,
            content = fileName,
            type = MessageType.FILE,
            timestamp = Date(),
            isSent = true
        )

        _messages.value = _messages.value + chatMessage
        connectionManager.sendFile(contact, fileUri)
    }

    suspend fun loadMessages(contactId: String) {
        // Load messages for specific contact
    }
}

// Data Classes
data class Contact(
    val id: String,
    val name: String,
    val ipAddress: String,
    val lastSeen: Date,
    val isOnline: Boolean = false,
    val isSelected: Boolean = false
)

data class ChatMessage(
    val id: String,
    val contactId: String,
    val content: String,
    val type: MessageType,
    val timestamp: Date,
    val isSent: Boolean
)

data class ConnectionStatus(
    val isConnected: Boolean = false,
    val isReceiving: Boolean = false,
    val localIp: String = "",
    val connectionType: ConnectionType = ConnectionType.OFFLINE
)

enum class MessageType {
    TEXT, FILE
}

enum class ConnectionType {
    OFFLINE, LOCAL_WIFI, WIFI_DIRECT, INTERNET
}

// Utility function to get filename from URI
private fun getFileName(contentResolver: ContentResolver, uri: Uri): String {
    contentResolver.query(uri, null, null, null, null)?.use { cursor ->
        val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
        if (cursor.moveToFirst() && nameIndex >= 0) {
            return cursor.getString(nameIndex) ?: "unknown_file"
        }
    }
    return "unknown_file"
}

private suspend fun getLocalIpAddress(): String = withContext(Dispatchers.IO) {
    try {
        NetworkInterface.getNetworkInterfaces().toList().forEach { intf ->
            intf.inetAddresses.toList().forEach { addr ->
                if (!addr.isLoopbackAddress && addr is InetAddress) {
                    val s = addr.hostAddress ?: return@forEach
                    if (s.indexOf(':') < 0) return@withContext s
                }
            }
        }
    } catch (e: Exception) {
        // ignore
    }
    return@withContext ""
}