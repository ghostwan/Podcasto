package com.ghostwan.podcasto.web

import android.util.Log
import com.jcraft.jsch.ChannelShell
import com.jcraft.jsch.JSch
import com.jcraft.jsch.Session
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.InputStreamReader

/**
 * Manages an SSH tunnel to localhost.run to expose the local Ktor web server
 * via a public HTTPS URL without any signup or account.
 *
 * How it works:
 * 1. Opens an SSH session to localhost.run with user "nokey" (no auth)
 * 2. Requests remote port forwarding: remote port 80 → localhost:<localPort>
 * 3. Opens a shell channel to receive the banner output containing the public URL
 * 4. The URL is in the format https://<hash>.lhr.life
 */
class TunnelManager {

    companion object {
        private const val TAG = "TunnelManager"
        private const val SSH_HOST = "localhost.run"
        private const val SSH_PORT = 22
        private const val SSH_USER = "nokey"
        private const val REMOTE_PORT = 80

        private val _tunnelUrl = MutableStateFlow<String?>(null)
        val tunnelUrl: StateFlow<String?> = _tunnelUrl.asStateFlow()

        private val _isConnecting = MutableStateFlow(false)
        val isConnecting: StateFlow<Boolean> = _isConnecting.asStateFlow()
    }

    private var session: Session? = null
    private var shellChannel: ChannelShell? = null
    @Volatile private var urlReaderThread: Thread? = null

    /**
     * Start the SSH tunnel. Connects to localhost.run, sets up remote port
     * forwarding at the session level, then opens a shell channel to read
     * the banner containing the public tunnel URL.
     */
    suspend fun start(localPort: Int) {
        if (session?.isConnected == true) {
            Log.w(TAG, "Tunnel already connected")
            return
        }

        _isConnecting.value = true
        _tunnelUrl.value = null

        withContext(Dispatchers.IO) {
            try {
                val jsch = JSch()
                val sshSession = jsch.getSession(SSH_USER, SSH_HOST, SSH_PORT)

                // localhost.run accepts connections without authentication
                sshSession.setConfig("StrictHostKeyChecking", "no")
                sshSession.setConfig("PreferredAuthentications", "none")
                // Keep alive to maintain the tunnel
                sshSession.serverAliveInterval = 30_000
                sshSession.serverAliveCountMax = 3

                Log.i(TAG, "Connecting to $SSH_HOST...")
                sshSession.connect(15_000)
                session = sshSession
                Log.i(TAG, "SSH session connected")

                // Set up remote port forwarding at session level
                // This is equivalent to ssh -R 80:localhost:<localPort>
                sshSession.setPortForwardingR(REMOTE_PORT, "localhost", localPort)
                Log.i(TAG, "Remote port forwarding set: $REMOTE_PORT -> localhost:$localPort")

                // Open a shell channel to receive the banner with the tunnel URL
                val channel = sshSession.openChannel("shell") as ChannelShell
                channel.setPty(false) // No pseudo-terminal needed

                val inputStream = channel.inputStream
                channel.connect(10_000)
                shellChannel = channel

                Log.i(TAG, "Shell channel connected, reading output for URL...")

                val reader = BufferedReader(InputStreamReader(inputStream))

                // Read lines in a background thread to find the URL
                urlReaderThread = Thread {
                    try {
                        var line: String?
                        while (reader.readLine().also { line = it } != null) {
                            Log.d(TAG, "SSH output: $line")
                            val url = extractUrl(line!!)
                            if (url != null) {
                                Log.i(TAG, "Tunnel URL: $url")
                                _tunnelUrl.value = url
                                _isConnecting.value = false
                            }
                        }
                    } catch (e: Exception) {
                        if (session?.isConnected == true) {
                            Log.e(TAG, "Error reading SSH output", e)
                        }
                    } finally {
                        // If we exit the read loop without finding a URL,
                        // the connection was likely closed
                        if (_tunnelUrl.value == null) {
                            _isConnecting.value = false
                            Log.w(TAG, "SSH output ended without URL")
                        }
                    }
                }.also { it.isDaemon = true; it.start() }

                // Timeout: if after 20s we still don't have a URL, stop waiting
                Thread {
                    try {
                        Thread.sleep(20_000)
                        if (_tunnelUrl.value == null && _isConnecting.value) {
                            _isConnecting.value = false
                            Log.w(TAG, "Timeout waiting for tunnel URL")
                        }
                    } catch (_: InterruptedException) {}
                }.also { it.isDaemon = true; it.start() }

            } catch (e: Exception) {
                Log.e(TAG, "Failed to start tunnel", e)
                _isConnecting.value = false
                _tunnelUrl.value = null
                cleanup()
            }
        }
    }

    /**
     * Stop the SSH tunnel and clean up resources.
     */
    fun stop() {
        cleanup()
        _tunnelUrl.value = null
        _isConnecting.value = false
        Log.i(TAG, "Tunnel stopped")
    }

    private fun cleanup() {
        urlReaderThread?.interrupt()
        urlReaderThread = null
        try { shellChannel?.disconnect() } catch (_: Exception) {}
        shellChannel = null
        try { session?.disconnect() } catch (_: Exception) {}
        session = null
    }

    /**
     * Check if the tunnel is currently connected.
     */
    fun isConnected(): Boolean = session?.isConnected == true && shellChannel?.isClosed == false

    /**
     * Extract the public URL from localhost.run SSH output.
     * localhost.run outputs lines like:
     *   "a97a87c271b94b.lhr.life tunneled with tls termination, https://a97a87c271b94b.lhr.life"
     * The domain can be *.lhr.life, *.localhost.run, or other subdomains.
     */
    private fun extractUrl(line: String): String? {
        // Look for https:// URL pattern in the line
        val httpsRegex = Regex("""(https://[a-zA-Z0-9-]+\.[a-zA-Z0-9.-]+)""")
        val match = httpsRegex.find(line) ?: return null
        val url = match.value

        // Filter out known non-tunnel URLs (documentation, admin, twitter, etc.)
        val excludedHosts = listOf(
            "twitter.com", "admin.localhost.run", "localhost.run/docs",
            "localhost:3000", "openssh.com"
        )
        if (excludedHosts.any { url.contains(it) }) return null

        return url
    }
}
