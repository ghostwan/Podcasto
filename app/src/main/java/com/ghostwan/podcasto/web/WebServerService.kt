package com.ghostwan.podcasto.web

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.net.wifi.WifiManager
import android.os.IBinder
import android.util.Log
import com.ghostwan.podcasto.MainActivity
import com.ghostwan.podcasto.R
import com.ghostwan.podcasto.data.repository.PodcastRepository
import dagger.hilt.android.AndroidEntryPoint
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import io.ktor.server.application.*
import io.ktor.server.cio.*
import io.ktor.server.engine.*
import io.ktor.server.plugins.contentnegotiation.*
import io.ktor.server.plugins.cors.routing.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.json.Json
import javax.inject.Inject

@AndroidEntryPoint
class WebServerService : Service() {

    @Inject lateinit var repository: PodcastRepository

    private var server: ApplicationEngine? = null
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var tunnelManager: TunnelManager? = null

    companion object {
        const val PORT = 8080
        const val CHANNEL_ID = "web_server_channel"
        const val NOTIFICATION_ID = 42
        const val ACTION_TOGGLE_TUNNEL = "toggle_tunnel"
        const val ACTION_START_WITH_TUNNEL = "start_with_tunnel"

        private val _isRunning = MutableStateFlow(false)
        val isRunning: StateFlow<Boolean> = _isRunning.asStateFlow()

        private val _serverUrl = MutableStateFlow<String?>(null)
        val serverUrl: StateFlow<String?> = _serverUrl.asStateFlow()

        val tunnelUrl: StateFlow<String?> = TunnelManager.tunnelUrl
        val isTunnelConnecting: StateFlow<Boolean> = TunnelManager.isConnecting

        fun start(context: Context) {
            val intent = Intent(context, WebServerService::class.java)
            context.startForegroundService(intent)
        }

        fun stop(context: Context) {
            val intent = Intent(context, WebServerService::class.java)
            context.stopService(intent)
        }

        fun toggleTunnel(context: Context) {
            val intent = Intent(context, WebServerService::class.java).apply {
                action = ACTION_TOGGLE_TUNNEL
            }
            context.startService(intent)
        }

        fun startWithTunnel(context: Context) {
            val intent = Intent(context, WebServerService::class.java).apply {
                action = ACTION_START_WITH_TUNNEL
            }
            context.startForegroundService(intent)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_TOGGLE_TUNNEL) {
            serviceScope.launch {
                if (tunnelManager?.isConnected() == true) {
                    tunnelManager?.stop()
                } else {
                    tunnelManager = TunnelManager()
                    tunnelManager?.start(PORT)
                }
            }
            return START_STICKY
        }
        if (intent?.action == ACTION_START_WITH_TUNNEL) {
            if (server == null) {
                startServer()
                val notification = buildNotification()
                startForeground(NOTIFICATION_ID, notification)
            }
            // Start tunnel after server is up
            serviceScope.launch {
                if (tunnelManager?.isConnected() != true) {
                    tunnelManager = TunnelManager()
                    tunnelManager?.start(PORT)
                }
            }
            return START_STICKY
        }
        if (server != null) return START_STICKY // Already running
        startServer()
        val notification = buildNotification()
        startForeground(NOTIFICATION_ID, notification)
        return START_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        tunnelManager?.stop()
        tunnelManager = null
        server?.stop(1000, 2000)
        server = null
        _isRunning.value = false
        _serverUrl.value = null
        serviceScope.cancel()
    }

    private fun startServer() {
        val ip = getLocalIpAddress()
        server = embeddedServer(CIO, port = PORT) {
            install(ContentNegotiation) {
                json(Json {
                    prettyPrint = true
                    isLenient = true
                    ignoreUnknownKeys = true
                })
            }
            install(CORS) {
                anyHost()
                allowMethod(HttpMethod.Get)
                allowMethod(HttpMethod.Post)
                allowMethod(HttpMethod.Put)
                allowMethod(HttpMethod.Delete)
                allowMethod(HttpMethod.Options)
                allowHeader(HttpHeaders.ContentType)
            }
            routing(configureRoutes(this@WebServerService, repository))
        }.also {
            it.start(wait = false)
        }

        _isRunning.value = true
        _serverUrl.value = "http://$ip:$PORT"
        Log.i("WebServer", "Server started at http://$ip:$PORT")
    }

    @Suppress("DEPRECATION")
    private fun getLocalIpAddress(): String {
        val wifiManager = applicationContext.getSystemService(WIFI_SERVICE) as WifiManager
        val ip = wifiManager.connectionInfo.ipAddress
        return String.format(
            "%d.%d.%d.%d",
            ip and 0xff,
            ip shr 8 and 0xff,
            ip shr 16 and 0xff,
            ip shr 24 and 0xff,
        )
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.web_server_channel),
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = getString(R.string.web_server_channel_desc)
        }
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(channel)
    }

    private fun buildNotification(): Notification {
        val pendingIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return Notification.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.web_server_running))
            .setContentText(getString(R.string.web_server_running_desc, _serverUrl.value ?: "http://...:$PORT"))
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .build()
    }
}
