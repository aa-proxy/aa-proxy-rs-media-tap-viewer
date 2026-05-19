package com.github.deadknight.aaproxymediatapviewer

import android.app.Activity
import android.content.Context
import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.view.WindowInsetsController
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URL
import java.util.concurrent.atomic.AtomicBoolean

private const val DEFAULT_HOST = "192.168.1.166"
private const val TAG = "MediaTapViewer"

private const val PREFS_NAME = "media_tap_viewer"
private const val PREF_LAST_HOST = "last_host"
private const val PREF_LAST_ENDPOINT_ID = "last_endpoint_id"
private const val PREF_AUTO_OPEN_LAST = "auto_open_last"

data class MediaTapsResponse(
    val available: Boolean,
    val reason: String?,
    val endpoints: List<MediaTapEndpoint>
)

data class MediaTapEndpoint(
    val endpointId: String,
    val injectDisplayId: String?,
    val label: String,
    val kind: String,
    val displayType: String?,
    val audioStreamType: String?,
    val port: Int?,
    val directPort: Int?
) {
    val isVideo: Boolean get() = kind.equals("video", ignoreCase = true)
    val isAudio: Boolean get() = kind.equals("audio", ignoreCase = true)
    val playablePort: Int? get() = directPort ?: port
}

sealed interface Screen {
    data object List : Screen
    data class Player(val endpoint: MediaTapEndpoint) : Screen
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        hideSystemBars()

        setContent {
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize(), color = Color.Black) {
                    App()
                }
            }
        }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) hideSystemBars()
    }
}

private fun Activity.hideSystemBars() {
    window.decorView.systemUiVisibility =
        View.SYSTEM_UI_FLAG_FULLSCREEN or
        View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
        View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or
        View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or
        View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION or
        View.SYSTEM_UI_FLAG_LAYOUT_STABLE

    if (android.os.Build.VERSION.SDK_INT >= 30) {
        window.insetsController?.let { controller ->
            controller.hide(WindowInsets.Type.statusBars() or WindowInsets.Type.navigationBars())
            controller.systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
    }
}

@Composable
fun App() {
    val context = LocalContext.current
    val prefs = remember {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    var host by remember {
        mutableStateOf(prefs.getString(PREF_LAST_HOST, DEFAULT_HOST) ?: DEFAULT_HOST)
    }
    var lastEndpointId by remember {
        mutableStateOf(prefs.getString(PREF_LAST_ENDPOINT_ID, null))
    }
    var autoOpenLast by remember {
        mutableStateOf(prefs.getBoolean(PREF_AUTO_OPEN_LAST, false))
    }
    var autoOpenAttempted by remember { mutableStateOf(false) }
    var screen by remember { mutableStateOf<Screen>(Screen.List) }

    fun persistHost(value: String) {
        host = value
        prefs.edit().putString(PREF_LAST_HOST, value).apply()
    }

    fun persistAutoOpen(value: Boolean) {
        autoOpenLast = value
        prefs.edit().putBoolean(PREF_AUTO_OPEN_LAST, value).apply()
    }

    fun openEndpoint(endpoint: MediaTapEndpoint) {
        lastEndpointId = endpoint.endpointId
        prefs.edit()
            .putString(PREF_LAST_HOST, host)
            .putString(PREF_LAST_ENDPOINT_ID, endpoint.endpointId)
            .apply()

        screen = Screen.Player(endpoint)
    }

    when (val current = screen) {
        Screen.List -> MediaTapListScreen(
            host = host,
            onHostChange = { persistHost(it.trim()) },
            onOpen = { openEndpoint(it) },
            lastEndpointId = lastEndpointId,
            autoOpenLast = autoOpenLast,
            onAutoOpenLastChange = { persistAutoOpen(it) },
            shouldAutoOpenNow = autoOpenLast && !autoOpenAttempted,
            onAutoOpenAttempted = { autoOpenAttempted = true }
        )

        is Screen.Player -> MediaTapPlayerScreen(
            host = host,
            endpoint = current.endpoint,
            onBack = { screen = Screen.List }
        )
    }
}

@Composable
fun MediaTapListScreen(
    host: String,
    onHostChange: (String) -> Unit,
    onOpen: (MediaTapEndpoint) -> Unit,
    lastEndpointId: String?,
    autoOpenLast: Boolean,
    onAutoOpenLastChange: (Boolean) -> Unit,
    shouldAutoOpenNow: Boolean,
    onAutoOpenAttempted: () -> Unit
) {
    var reloadKey by remember { mutableStateOf(0) }
    var showAudio by remember { mutableStateOf(false) }

    val state by produceState<Result<MediaTapsResponse>?>(initialValue = null, host, reloadKey) {
        value = null
        value = runCatching { fetchMediaTaps(host) }
    }

    LaunchedEffect(state, shouldAutoOpenNow, lastEndpointId) {
        val response = state?.getOrNull()
        if (shouldAutoOpenNow && response != null) {
            onAutoOpenAttempted()

            val endpoint = response.endpoints.firstOrNull {
                it.endpointId == lastEndpointId && it.isVideo
            }

            if (endpoint != null) {
                onOpen(endpoint)
            }
        }
    }

    Column(modifier = Modifier.fillMaxSize().padding(16.dp).background(Color.Black)) {
        Text("AA Proxy Media Taps", style = MaterialTheme.typography.headlineSmall, color = Color.White)
        Spacer(Modifier.height(12.dp))

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = host,
                onValueChange = onHostChange,
                label = { Text("Host") },
                singleLine = true,
                modifier = Modifier.weight(1f)
            )
            Button(onClick = { reloadKey++ }) {
                Text("Refresh")
            }
        }

        Spacer(Modifier.height(8.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("Open last stream on startup", color = Color.White, modifier = Modifier.weight(1f))
            Switch(checked = autoOpenLast, onCheckedChange = onAutoOpenLastChange)
        }

        Spacer(Modifier.height(8.dp))

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(
                selected = !showAudio,
                onClick = { showAudio = false },
                label = { Text("Video") }
            )
            FilterChip(
                selected = showAudio,
                onClick = { showAudio = true },
                label = { Text("Audio") }
            )
        }

        Spacer(Modifier.height(12.dp))

        when {
            state == null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }

            state!!.isFailure -> Text(
                text = "Failed: ${state!!.exceptionOrNull()?.message ?: "unknown"}",
                color = MaterialTheme.colorScheme.error
            )

            else -> {
                val response = state!!.getOrThrow()
                if (!response.available) {
                    Text(response.reason ?: "No media taps available. Connect Android Auto first.", color = Color.White)
                }

                val lastEndpoint = response.endpoints.firstOrNull { it.endpointId == lastEndpointId }
                if (lastEndpoint != null) {
                    LastOpenedCard(endpoint = lastEndpoint, onOpen = { onOpen(lastEndpoint) })
                    Spacer(Modifier.height(12.dp))
                }

                val endpoints = response.endpoints
                    .filter { if (showAudio) it.isAudio else it.isVideo }

                if (endpoints.isEmpty()) {
                    Text(if (showAudio) "No audio endpoints." else "No video endpoints.", color = Color.White)
                } else {
                    LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        items(endpoints, key = { it.endpointId }) { endpoint ->
                            MediaTapCard(endpoint = endpoint, onClick = { onOpen(endpoint) })
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun LastOpenedCard(endpoint: MediaTapEndpoint, onOpen: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        elevation = CardDefaults.cardElevation(defaultElevation = 3.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Column(Modifier.weight(1f)) {
                Text("Last opened", style = MaterialTheme.typography.labelMedium)
                Text(endpoint.label, style = MaterialTheme.typography.titleMedium)
                Text(endpoint.endpointId, style = MaterialTheme.typography.bodySmall)
            }

            Button(onClick = onOpen) {
                Text("Open again")
            }
        }
    }
}

@Composable
fun MediaTapCard(endpoint: MediaTapEndpoint, onClick: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth().clickable { onClick() },
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(endpoint.label, style = MaterialTheme.typography.titleMedium)
            Text(endpoint.endpointId, style = MaterialTheme.typography.bodySmall)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                AssistChip(onClick = {}, label = { Text(endpoint.kind) })
                endpoint.displayType?.let { AssistChip(onClick = {}, label = { Text(it.removePrefix("DISPLAY_TYPE_")) }) }
                endpoint.audioStreamType?.let { AssistChip(onClick = {}, label = { Text(it.removePrefix("AUDIO_STREAM_")) }) }
            }
            Text("direct_port: ${endpoint.playablePort ?: "-"}")
            if (endpoint.isVideo) {
                Text("Tap to open fullscreen. Use Mirror for HUD/head-up reflection.")
            } else {
                Text("Audio may require PCM wrapping depending on stream format.")
            }
        }
    }
}

@Composable
fun MediaTapPlayerScreen(
    host: String,
    endpoint: MediaTapEndpoint,
    onBack: () -> Unit
) {
    BackHandler { onBack() }

    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var error by remember { mutableStateOf<String?>(null) }
    var streamUrl by remember { mutableStateOf<String?>(null) }
    var showControls by remember { mutableStateOf(true) }
    var mirror by remember { mutableStateOf(false) }

    val bridge = remember(endpoint.endpointId, host) {
        DirectTcpToHttpBridge(
            host = host,
            port = endpoint.playablePort ?: -1,
            isVideo = endpoint.isVideo
        )
    }

    LaunchedEffect(endpoint.endpointId, host) {
        error = null
        streamUrl = null

        val port = endpoint.playablePort
        if (port == null || port <= 0) {
            error = "Endpoint has no direct_port."
            return@LaunchedEffect
        }

        runCatching { bridge.start() }
            .onSuccess { url ->
                streamUrl = url
                Log.d(TAG, "bridge started url=$url endpoint=${endpoint.endpointId}")
            }
            .onFailure { t ->
                error = t.message ?: t.toString()
            }
    }

    DisposableEffect(endpoint.endpointId, host) {
        onDispose { bridge.stop() }
    }

    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black)
            .clickable { showControls = !showControls }
    ) {
        error?.let {
            Text(
                text = it,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.align(Alignment.Center).padding(16.dp)
            )
        }

        if (streamUrl == null && error == null) {
            CircularProgressIndicator(Modifier.align(Alignment.Center))
        }

        streamUrl?.let { url ->
            val player = remember(url, endpoint.endpointId) {
                ExoPlayer.Builder(context).build().apply {
                    val mediaItem = MediaItem.Builder()
                        .setUri(Uri.parse(url))
                        .setMimeType(if (endpoint.isVideo) MimeTypes.VIDEO_MP2T else MimeTypes.AUDIO_RAW)
                        .build()
                    setMediaItem(mediaItem)
                    prepare()
                    playWhenReady = true
                }
            }

            DisposableEffect(player) {
                onDispose { player.release() }
            }

            AndroidView(
                modifier = Modifier
                    .fillMaxSize()
                    .scale(scaleX = if (mirror) -1f else 1f, scaleY = 1f),
                factory = {
                    PlayerView(it).apply {
                        layoutParams = ViewGroup.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            ViewGroup.LayoutParams.MATCH_PARENT
                        )
                        this.player = player
                        useController = false
                        resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
                    }
                }
            )
        }

        AnimatedVisibility(
            visible = showControls,
            modifier = Modifier.align(Alignment.TopCenter)
        ) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .background(Color.Black.copy(alpha = 0.72f))
                    .padding(12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Button(onClick = onBack) { Text("Back") }

                Column(Modifier.weight(1f)) {
                    Text(endpoint.label, color = Color.White, style = MaterialTheme.typography.titleMedium)
                    Text("tcp://$host:${endpoint.playablePort}", color = Color.White.copy(alpha = 0.8f), style = MaterialTheme.typography.bodySmall)
                }

                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("Mirror", color = Color.White)
                    Switch(checked = mirror, onCheckedChange = { mirror = it })
                }

                Button(onClick = {
                    scope.launch {
                        bridge.stop()
                        runCatching { bridge.start() }
                            .onSuccess { streamUrl = it; error = null }
                            .onFailure { error = it.message ?: it.toString() }
                    }
                }) { Text("Reconnect") }
            }
        }

        AnimatedVisibility(
            visible = !showControls,
            modifier = Modifier.align(Alignment.BottomCenter)
        ) {
            Text(
                "Tap to show controls",
                color = Color.White.copy(alpha = 0.5f),
                modifier = Modifier
                    .background(Color.Black.copy(alpha = 0.35f))
                    .padding(horizontal = 12.dp, vertical = 6.dp)
            )
        }
    }
}

suspend fun fetchMediaTaps(host: String): MediaTapsResponse = withContext(Dispatchers.IO) {
    val url = URL("http://$host/media-taps")
    val conn = (url.openConnection() as HttpURLConnection).apply {
        requestMethod = "GET"
        connectTimeout = 4_000
        readTimeout = 8_000
    }

    try {
        val code = conn.responseCode
        val body = if (code in 200..299) {
            conn.inputStream.bufferedReader().use { it.readText() }
        } else {
            conn.errorStream?.bufferedReader()?.use { it.readText() }.orEmpty()
        }

        if (code !in 200..299) {
            error("HTTP $code $body")
        }

        parseMediaTaps(body)
    } finally {
        conn.disconnect()
    }
}

fun parseMediaTaps(json: String): MediaTapsResponse {
    val root = JSONObject(json)
    val endpointsJson = root.optJSONArray("endpoints")
    val endpoints = buildList {
        if (endpointsJson != null) {
            for (i in 0 until endpointsJson.length()) {
                val item = endpointsJson.getJSONObject(i)
                add(
                    MediaTapEndpoint(
                        endpointId = item.optString("endpoint_id"),
                        injectDisplayId = item.optStringOrNull("inject_display_id"),
                        label = item.optString("label"),
                        kind = item.optString("kind"),
                        displayType = item.optStringOrNull("display_type"),
                        audioStreamType = item.optStringOrNull("audio_stream_type"),
                        port = item.optIntOrNull("port"),
                        directPort = item.optIntOrNull("direct_port")
                    )
                )
            }
        }
    }

    return MediaTapsResponse(
        available = root.optBoolean("available", endpoints.isNotEmpty()),
        reason = root.optStringOrNull("reason"),
        endpoints = endpoints
    )
}

fun JSONObject.optStringOrNull(name: String): String? {
    if (!has(name) || isNull(name)) return null
    val value = optString(name)
    return value.ifBlank { null }
}

fun JSONObject.optIntOrNull(name: String): Int? {
    if (!has(name) || isNull(name)) return null
    return optInt(name)
}

class DirectTcpToHttpBridge(
    private val host: String,
    private val port: Int,
    private val isVideo: Boolean
) {
    private val running = AtomicBoolean(false)
    private var server: ServerSocket? = null
    private var job: Thread? = null
    var localPort: Int = -1
        private set

    val streamUrl: String
        get() = "http://127.0.0.1:$localPort/stream.ts"

    suspend fun start(): String = withContext(Dispatchers.IO) {
        if (running.get()) return@withContext streamUrl
        check(port > 0) { "Invalid direct port: $port" }

        server = ServerSocket(0, 8, InetAddress.getByName("127.0.0.1")).apply {
            reuseAddress = true
        }
        localPort = server?.localPort ?: error("HTTP server did not bind")
        running.set(true)

        job = Thread {
            acceptLoop()
        }.also {
            it.name = "DirectTcpToHttpBridge-$port"
            it.isDaemon = true
            it.start()
        }

        streamUrl
    }

    fun stop() {
        if (!running.getAndSet(false)) return
        closeQuietly(server)
        server = null
        localPort = -1
    }

    private fun acceptLoop() {
        while (running.get()) {
            val client = try {
                server?.accept()
            } catch (_: Throwable) {
                null
            } ?: break

            Thread {
                handleClient(client)
            }.also {
                it.name = "DirectTcpToHttpBridge-client-$port"
                it.isDaemon = true
                it.start()
            }
        }
    }

    private fun handleClient(client: Socket) {
        try {
            configureHttpSocket(client)
            readHttpRequest(client)
            writeHttpHeaders(client)

            Socket().use { upstream ->
                upstream.tcpNoDelay = true
                upstream.soTimeout = 0
                upstream.connect(InetSocketAddress(host, port), 5_000)

                val input = BufferedInputStream(upstream.getInputStream())
                val output = BufferedOutputStream(client.getOutputStream())
                val buffer = ByteArray(DEFAULT_BUFFER_SIZE)

                while (running.get()) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    output.write(buffer, 0, read)
                    output.flush()
                }
            }
        } catch (t: Throwable) {
            Log.d(TAG, "stream ended tcp://$host:$port: ${t.message}")
        } finally {
            closeQuietly(client)
        }
    }

    private fun readHttpRequest(client: Socket) {
        client.soTimeout = 5_000
        val input = client.getInputStream()
        val marker = byteArrayOf('\r'.code.toByte(), '\n'.code.toByte(), '\r'.code.toByte(), '\n'.code.toByte())
        var matched = 0

        while (matched < marker.size) {
            val b = input.read()
            if (b < 0) break
            matched = if (b.toByte() == marker[matched]) matched + 1 else 0
        }

        client.soTimeout = 0
    }

    private fun writeHttpHeaders(client: Socket) {
        val contentType = if (isVideo) "video/mp2t" else "application/octet-stream"
        val headers = "HTTP/1.1 200 OK\r\n" +
            "Content-Type: $contentType\r\n" +
            "Cache-Control: no-store\r\n" +
            "Connection: close\r\n" +
            "Accept-Ranges: none\r\n" +
            "\r\n"
        client.getOutputStream().write(headers.toByteArray(Charsets.US_ASCII))
        client.getOutputStream().flush()
    }

    private fun configureHttpSocket(socket: Socket) {
        runCatching {
            socket.tcpNoDelay = true
            socket.keepAlive = false
            socket.soTimeout = 0
        }
    }

    private fun closeQuietly(socket: Socket?) {
        try {
            socket?.close()
        } catch (_: Throwable) {
        }
    }

    private fun closeQuietly(serverSocket: ServerSocket?) {
        try {
            serverSocket?.close()
        } catch (_: Throwable) {
        }
    }
}
