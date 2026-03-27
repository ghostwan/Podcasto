package com.ghostwan.podcasto.web

import android.util.Log
import com.jcraft.jsch.ChannelExec
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
 * Uses: ssh -R 80:localhost:<localPort> nokey@localhost.run
 * localhost.run prints the public URL on stdout of the SSH session.
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
    private var execChannel: ChannelExec? = null
    @Volatile private var urlReaderThread: Thread? = null

    /**
     * Start the SSH tunnel. Connects to localhost.run via an exec channel
     * that runs the remote port forwarding command. The public URL is
     * extracted from the command output on stdout.
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

                // Open an exec channel with the remote forwarding command
                // This is how localhost.run works: the command triggers port forwarding
                // and the URL is printed to stdout
                val channel = sshSession.openChannel("exec") as ChannelExec
                channel.setCommand("ssh -R $REMOTE_PORT:localhost:$localPort $SSH_USER@$SSH_HOST")
                channel.setErrStream(System.err)

                val inputStream = channel.inputStream
                channel.connect(10_000)
                execChannel = channel

                Log.i(TAG, "Exec channel connected, reading output...")

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

                // Timeout: if after 15s we still don't have a URL, stop waiting
                Thread {
                    try {
                        Thread.sleep(15_000)
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
        try { execChannel?.disconnect() } catch (_: Exception) {}
        execChannel = null
        try { session?.disconnect() } catch (_: Exception) {}
        session = null
    }

    /**
     * Check if the tunnel is currently connected.
     */
    fun isConnected(): Boolean = session?.isConnected == true && execChannel?.isClosed == false

    /**
     * Extract the public URL from localhost.run SSH output.
     * localhost.run outputs lines like:
     *   "Connect to http://abc123.localhost.run or https://abc123.localhost.run"
     *   or just "https://abc123.localhost.run"
     */
    private fun extractUrl(line: String): String? {
        // Look for https://*.localhost.run pattern
        val httpsRegex = Regex("""(https://[a-zA-Z0-9-]+\.localhost\.run)""")
        httpsRegex.find(line)?.let { return it.value }

        // Fallback: look for http://*.localhost.run
        val httpRegex = Regex("""(http://[a-zA-Z0-9-]+\.localhost\.run)""")
        httpRegex.find(line)?.let { return it.value }

        return null
    }
}
