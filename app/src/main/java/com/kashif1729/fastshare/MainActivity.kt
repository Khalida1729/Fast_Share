package com.kashif1729.fastshare

import android.Manifest
import android.app.Activity
import android.content.ContentResolver
import android.content.Context
//import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.net.wifi.WifiManager
//import android.net.wifi.p2p.WifiP2pManager
//import android.os.Build
import android.os.Bundle
import android.provider.OpenableColumns
//import android.provider.Settings
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Chat
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
//import androidx.core.app.ActivityCompat
//import androidx.core.app.ActivityCompat.requestPermissions
import androidx.core.content.ContextCompat
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.*
import java.net.*
import java.text.DateFormat.getDateInstance
import java.text.DateFormat.getTimeInstance
import java.text.SimpleDateFormat
import java.util.*

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
    OFFLINE, LOCAL_WIFI
//    WIFI_DIRECT, INTERNET
}

// ConnectionManager
@Suppress("NULLABILITY_MISMATCH_BASED_ON_JAVA_ANNOTATIONS")
class ConnectionManager(private val context: Context) {
    private val TAG = "ConnectionManager"
    private val _currentConnection = MutableStateFlow(ConnectionStatus())
    val currentConnection = _currentConnection.asStateFlow()

    private var receiverJob: Job? = null
    private var serverSocket: ServerSocket? = null

    private val wifiManager = context.getSystemService(Context.WIFI_SERVICE) as WifiManager

    fun startReceiver() {
        stopReceiver()

        receiverJob = CoroutineScope(Dispatchers.IO).launch {
            try {
                serverSocket = ServerSocket(8989)
                val localIp = getLocalIpAddress()

                _currentConnection.value = ConnectionStatus(
                    isReceiving = true,
                    localIp = localIp,
                    connectionType = ConnectionType.LOCAL_WIFI
                )

                Log.d(TAG, "Receiver started on $localIp:8989")

                while (isActive) {
                    val socket = serverSocket!!.accept()
                    Log.d(TAG, "Incoming connection from ${socket.inetAddress.hostAddress}")
                    handleIncomingConnection(socket)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Receiver error", e)
                _currentConnection.value = _currentConnection.value.copy(
                    isReceiving = false,
                    isConnected = false
                )
            }
        }
    }

    fun stopReceiver() {
        receiverJob?.cancel()
        serverSocket?.close()
        _currentConnection.value = _currentConnection.value.copy(
            isReceiving = false,
            isConnected = false
        )
        Log.d(TAG, "Receiver stopped")
    }

    fun toggleReceiver() {
        if (_currentConnection.value.isReceiving) {
            stopReceiver()
        } else {
            startReceiver()
        }
    }

    suspend fun sendFile(contact: Contact, fileUri: Uri): Boolean {
        return withContext(Dispatchers.IO) {
            try {
                if (isLocalConnectionPossible(contact)) {
                    sendViaLocalNetwork(contact, fileUri)
                } else {
                    false // Internet transfer not implemented yet
                }
            } catch (e: Exception) {
                Log.e(TAG, "Send failed", e)
                false
            }
        }
    }

    suspend fun sendMessage(contact: Contact, message: String): Boolean {
        return withContext(Dispatchers.IO) {
            try {
                val socket = Socket(contact.ipAddress, 8989)
                val outputStream = socket.getOutputStream()
                val writer = PrintWriter(outputStream, true)

                // Send message header
                writer.println("TEXT:${message.length}")
                writer.println(message)

                writer.close()
                outputStream.close()
                socket.close()

                Log.d(TAG, "Message sent to ${contact.ipAddress}")
                true
            } catch (e: Exception) {
                Log.e(TAG, "Failed to send message", e)
                false
            }
        }
    }

    private suspend fun handleIncomingConnection(socket: Socket) {
        withContext(Dispatchers.IO) {
            try {
                val inputStream = socket.getInputStream()
                val reader = BufferedReader(InputStreamReader(inputStream))

                // Read header
                val header = reader.readLine()
                Log.d(TAG, "Received header: $header")

                when {
                    header.startsWith("TEXT:") -> {
                        val length = header.substringAfter("TEXT:").toInt()
                        val message = reader.readText(length)
                        handleIncomingMessage(socket.inetAddress.hostAddress, message)
                    }
                    header.startsWith("FILE:") -> {
                        val fileName = header.substringAfter("FILE:")
                        handleIncomingFile(socket, fileName, reader)
                    }
                }

            } catch (e: Exception) {
                Log.e(TAG, "Error handling connection", e)
            } finally {
                socket.close()
            }
        }
    }

    private fun handleIncomingMessage(senderIp: String, message: String) {
        Log.d(TAG, "Message from $senderIp: $message")
        // This would typically update the chat UI via ChatManager
    }

    private fun handleIncomingFile(socket: Socket, fileName: String, reader: BufferedReader) {
        // Implement file receiving logic
        Log.d(TAG, "File received: $fileName")
    }

    private suspend fun sendViaLocalNetwork(contact: Contact, fileUri: Uri): Boolean {
        return withContext(Dispatchers.IO) {
            try {
                val socket = Socket(contact.ipAddress, 8989)
                val outputStream = socket.getOutputStream()
                val writer = PrintWriter(outputStream, true)

                val fileName = getFileName(context.contentResolver, fileUri)

                // Send file header
                writer.println("FILE:$fileName")

                // Here you would read the file and send its content
                // For now, we'll just send a placeholder
                writer.println("FILE_CONTENT_PLACEHOLDER")

                writer.close()
                outputStream.close()
                socket.close()

                Log.d(TAG, "File sent to ${contact.ipAddress}")
                true
            } catch (e: Exception) {
                Log.e(TAG, "Failed to send file", e)
                false
            }
        }
    }

    private fun isLocalConnectionPossible(contact: Contact): Boolean {
        return try {
            // Simple check - try to resolve the IP
            InetAddress.getByName(contact.ipAddress).isReachable(1000)
        } catch (e: Exception) {
            false
        }
    }

    private suspend fun getLocalIpAddress(): String = withContext(Dispatchers.IO) {
        try {
            val wifiInfo = wifiManager.connectionInfo
            val ip = wifiInfo.ipAddress
            "${ip and 0xFF}.${ip shr 8 and 0xFF}.${ip shr 16 and 0xFF}.${ip shr 24 and 0xFF}"
        } catch (e: Exception) {
            Log.e(TAG, "Error getting local IP", e)
            try {
                NetworkInterface.getNetworkInterfaces().toList().forEach { intf ->
                    intf.inetAddresses.toList().forEach { addr ->
                        if (!addr.isLoopbackAddress && addr is Inet4Address) {
                            return@withContext addr.hostAddress ?: ""
                        }
                    }
                }
                ""
            } catch (e2: Exception) {
                ""
            }
        }
    }

    fun cleanup() {
        stopReceiver()
    }
}

// ContactManager
class ContactManager(private val context: Context) {
    private val TAG = "ContactManager"
    private val _contacts = MutableStateFlow<List<Contact>>(emptyList())
    val contacts = _contacts.asStateFlow()

    private val sharedPrefs = context.getSharedPreferences("contacts", Context.MODE_PRIVATE)
    private val gson = Gson()

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
        Log.d(TAG, "Contact added: $name ($ipAddress)")
    }

    fun removeContact(contactId: String) {
        _contacts.value = _contacts.value.filter { it.id != contactId }
        saveContacts()
        Log.d(TAG, "Contact removed: $contactId")
    }

    fun selectContact(contactId: String) {
        _contacts.value = _contacts.value.map { contact ->
            contact.copy(isSelected = contact.id == contactId)
        }
        Log.d(TAG, "Contact selected: $contactId")
    }

    fun clearAllContacts() {
        _contacts.value = emptyList()
        sharedPrefs.edit().remove("contacts_list").apply()
        Log.d(TAG, "All contacts cleared")
    }

    private fun loadContacts() {
        try {
            val contactsJson = sharedPrefs.getString("contacts_list", "[]")
            val type = object : TypeToken<List<Contact>>() {}.type
            val loadedContacts: List<Contact> = gson.fromJson(contactsJson, type) ?: emptyList()
            _contacts.value = loadedContacts
            Log.d(TAG, "Loaded ${loadedContacts.size} contacts")
        } catch (e: Exception) {
            Log.e(TAG, "Error loading contacts", e)
            _contacts.value = emptyList()
        }
    }

    private fun saveContacts() {
        try {
            val contactsJson = gson.toJson(_contacts.value)
            sharedPrefs.edit().putString("contacts_list", contactsJson).apply()
            Log.d(TAG, "Saved ${_contacts.value.size} contacts")
        } catch (e: Exception) {
            Log.e(TAG, "Error saving contacts", e)
        }
    }
}

// ChatManager
class ChatManager(
    private val context: Context,
    private val connectionManager: ConnectionManager,
    private val contactManager: ContactManager
) {
    private val TAG = "ChatManager"
    private val _messages = MutableStateFlow<List<ChatMessage>>(emptyList())
    val messages = _messages.asStateFlow()

    private val messageCache = mutableMapOf<String, List<ChatMessage>>()

    suspend fun sendMessage(contact: Contact, message: String) {
        val chatMessage = ChatMessage(
            id = UUID.randomUUID().toString(),
            contactId = contact.id,
            content = message,
            type = MessageType.TEXT,
            timestamp = Date(),
            isSent = true
        )

        // Add to UI immediately
        addMessageToCache(contact.id, chatMessage)

        // Actually send via connection manager
        val success = connectionManager.sendMessage(contact, message)
        if (!success) {
            Log.e(TAG, "Failed to send message to ${contact.name}")
            // You might want to show an error to the user
        } else {
            Log.d(TAG, "Message sent to ${contact.name}")
        }
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

        // Add to UI immediately
        addMessageToCache(contact.id, chatMessage)

        // Actually send the file
        val success = connectionManager.sendFile(contact, fileUri)
        if (!success) {
            Log.e(TAG, "Failed to send file to ${contact.name}")
            // You might want to show an error to the user
        } else {
            Log.d(TAG, "File sent to ${contact.name}")
        }
    }

    suspend fun loadMessages(contactId: String) {
        _messages.value = messageCache[contactId] ?: emptyList()
        Log.d(TAG, "Loaded messages for contact: $contactId")
    }

    fun receiveMessage(contactId: String, content: String, type: MessageType = MessageType.TEXT) {
        val chatMessage = ChatMessage(
            id = UUID.randomUUID().toString(),
            contactId = contactId,
            content = content,
            type = type,
            timestamp = Date(),
            isSent = false
        )

        addMessageToCache(contactId, chatMessage)
        Log.d(TAG, "Message received from contact: $contactId")
    }

    private fun addMessageToCache(contactId: String, message: ChatMessage) {
        val currentMessages = messageCache[contactId] ?: emptyList()
        messageCache[contactId] = currentMessages + message

        // If this is the currently selected contact, update the flow
        val contacts = contactManager.contacts.value
        val selectedContact = contacts.find { it.isSelected }
        if (selectedContact?.id == contactId) {
            _messages.value = messageCache[contactId] ?: emptyList()
        }
    }

    fun clearMessages(contactId: String) {
        messageCache.remove(contactId)
        val contacts = contactManager.contacts.value
        val selectedContact = contacts.find { it.isSelected }
        if (selectedContact?.id == contactId) {
            _messages.value = emptyList()
        }
        Log.d(TAG, "Messages cleared for contact: $contactId")
    }
}

// Extension function to read exact number of characters
private fun BufferedReader.readText(length: Int): String {
    val buffer = CharArray(length)
    read(buffer, 0, length)
    return String(buffer)
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

// Main Activity
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

    var selectedTab by remember { mutableIntStateOf(0) }
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
            NavigationBar { 
                NavigationBarItem(
                    selected = selectedTab == 0,
                    onClick = { selectedTab = 0 },
                    icon = { Icon(Icons.AutoMirrored.Filled.Chat, "Chats") },
                    label = { Text("Chats") }
                )
                NavigationBarItem(
                    selected = selectedTab == 1,
                    onClick = { selectedTab = 1 },
                    icon = { Icon(Icons.Default.Contacts, "Contacts") },
                    label = { Text("Contacts") }
                )
                NavigationBarItem(
                    selected = selectedTab == 2,
                    onClick = { selectedTab = 2 },
                    icon = { Icon(Icons.Default.Settings, "Settings") },
                    label = { Text("Settings") }
                )
            }
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
            permissionLauncher.launch(
                arrayOf(
                    Manifest.permission.ACCESS_WIFI_STATE,
                    Manifest.permission.CHANGE_WIFI_STATE,
                    Manifest.permission.ACCESS_NETWORK_STATE,
                    Manifest.permission.INTERNET
                )
            )
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


        // Chat Area
        Column(
            modifier = Modifier
                .fillMaxSize()
        ) {
            if (selectedContact != null) {
                // Fixed Header with Contact Name
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(64.dp)
                        .background(MaterialTheme.colorScheme.primary)
                        .padding(horizontal = 16.dp),
                    contentAlignment = Alignment.CenterStart
                ) {
                    Text(
                        text = selectedContact.name,
                        style = MaterialTheme.typography.headlineSmall,
                        color = MaterialTheme.colorScheme.onPrimary
                    )
                }

                // Messages Area with proper spacing
                LazyColumn(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(messages) { message ->
                        MessageBubble(message = message)
                    }
                }

                // Message Input
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    OutlinedTextField(
                        value = messageText,
                        onValueChange = { messageText = it },
                        placeholder = { Text("Type a message...") },
                        modifier = Modifier.weight(1f)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    IconButton(
                        onClick = {
                            if (messageText.isNotBlank()) {
                                onSendMessage(messageText)
                                messageText = ""
                            }
                        }
                    ) {
                        Icon(Icons.AutoMirrored.Filled.Send, "Send Message")
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

//@Composable
//fun ContactListItem(contact: Contact, isSelected: Boolean, onClick: () -> Unit) {
//    Card(
//        modifier = Modifier
//            .fillMaxWidth()
//            .padding(8.dp),
//        colors = CardDefaults.cardColors(
//            containerColor = if (isSelected) MaterialTheme.colorScheme.primaryContainer
//            else MaterialTheme.colorScheme.surface
//        ),
//        onClick = onClick
//    ) {
//        Row(
//            modifier = Modifier.padding(16.dp),
//            verticalAlignment = Alignment.CenterVertically
//        ) {
//            Box(
//                modifier = Modifier
//                    .size(40.dp)
//                    .background(MaterialTheme.colorScheme.primary, CircleShape),
//                contentAlignment = Alignment.Center
//            ) {
//                Text(
//                    text = contact.name.take(2).uppercase(),
//                    color = Color.White,
//                    fontWeight = FontWeight.Bold
//                )
//            }
//            Spacer(modifier = Modifier.width(12.dp))
//            Column {
//                Text(contact.name, fontWeight = FontWeight.Bold)
//                Text(contact.ipAddress, style = MaterialTheme.typography.bodySmall)
//                Text(
//                    "Last seen: ${getTimeInstance().format(contact.lastSeen)}",
//                    style = MaterialTheme.typography.bodySmall
//                )
//            }
//        }
//    }
//}

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
                    text = getTimeInstance().format(message.timestamp),
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

@Composable
fun ContactsScreen(
    contacts: List<Contact>,
    onContactSelect: (Contact) -> Unit,
    onContactDelete: (Contact) -> Unit
) {
    Column(modifier = Modifier.fillMaxSize()) {
        if (contacts.isEmpty()) {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                Text("No contacts yet. Add a contact to start chatting.")
            }
        } else {
            LazyColumn(modifier = Modifier.fillMaxSize()) {
                items(contacts) { contact ->
                    ContactListItemWithDelete(
                        contact = contact,
                        isSelected = contact.isSelected,
                        onClick = { onContactSelect(contact) },
                        onDelete = { onContactDelete(contact) }
                    )
                }
            }
        }
    }
}

@Composable
fun ContactListItemWithDelete(
    contact: Contact,
    isSelected: Boolean,
    onClick: () -> Unit,
    onDelete: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(8.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (isSelected) MaterialTheme.colorScheme.primaryContainer
            else MaterialTheme.colorScheme.surface
        ),
        onClick = onClick  // Make entire card clickable
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
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
            Column(
                modifier = Modifier.weight(1f)
            ) {
                Text(contact.name, fontWeight = FontWeight.Bold)
                Text(contact.ipAddress, style = MaterialTheme.typography.bodySmall)
                Text(
                    "Last seen: ${getTimeInstance().format(contact.lastSeen)}",
                    style = MaterialTheme.typography.bodySmall
                )
            }
            IconButton(
                onClick = onDelete,
                modifier = Modifier
                    // Prevent the icon button click from triggering the card click
                    .clickable(enabled = true, onClick = {})
            ) {
                Icon(Icons.Default.Delete, "Delete Contact")
            }
        }
    }
}
@Composable
fun SettingsScreen(
    connectionManager: ConnectionManager,
    contactManager: ContactManager
) {
    //val context = LocalContext.current
    val currentConnection by connectionManager.currentConnection.collectAsState()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
    ) {
        Text("Settings", style = MaterialTheme.typography.headlineMedium)

        Spacer(modifier = Modifier.height(24.dp))

        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text("Connection Status", style = MaterialTheme.typography.titleMedium)
                Spacer(modifier = Modifier.height(8.dp))
                ConnectionStatusIndicator(currentConnection)
                Spacer(modifier = Modifier.height(8.dp))
                Text("Local IP: ${currentConnection.localIp}", style = MaterialTheme.typography.bodySmall)
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text("App Information", style = MaterialTheme.typography.titleMedium)
                Spacer(modifier = Modifier.height(8.dp))
                Text("FastShare Pro v1.0", style = MaterialTheme.typography.bodyMedium)
                Text("File sharing made easy", style = MaterialTheme.typography.bodySmall)
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        Button(
            onClick = {
                connectionManager.cleanup()
                contactManager.clearAllContacts()
            },
            modifier = Modifier.fillMaxWidth(),
            colors = ButtonDefaults.buttonColors(
                containerColor = MaterialTheme.colorScheme.errorContainer,
                contentColor = MaterialTheme.colorScheme.onErrorContainer
            )
        ) {
            Text("Clear All Data & Reset")
        }
    }
}

// Permission helpers
private fun hasRequiredPermissions(context: Context): Boolean {
    val permissions = arrayOf(
        Manifest.permission.ACCESS_WIFI_STATE,
        Manifest.permission.CHANGE_WIFI_STATE,
        Manifest.permission.ACCESS_NETWORK_STATE,
        Manifest.permission.INTERNET,
        Manifest.permission.WRITE_EXTERNAL_STORAGE,
        Manifest.permission.READ_EXTERNAL_STORAGE,
        Manifest.permission.NEARBY_WIFI_DEVICES
    )

    return permissions.all {
        ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
    }
}

//private fun requestPermissions(launcher: ActivityResultContracts.RequestMultiplePermissions) {
//    val permissions = mutableListOf(
//        Manifest.permission.ACCESS_WIFI_STATE,
//        Manifest.permission.CHANGE_WIFI_STATE,
//        Manifest.permission.ACCESS_NETWORK_STATE,
//        Manifest.permission.INTERNET
//    )
//
//    if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.Q) {
//        permissions.add(Manifest.permission.WRITE_EXTERNAL_STORAGE)
//        permissions.add(Manifest.permission.READ_EXTERNAL_STORAGE)
//    }
//
//    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
//        permissions.add(Manifest.permission.NEARBY_WIFI_DEVICES)
//    }
//
//    launcher.launch(permissions.toTypedArray())
