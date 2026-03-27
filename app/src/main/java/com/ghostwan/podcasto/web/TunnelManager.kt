package com.ghostwan.podcasto.web

import android.util.Log
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
 * Uses remote port forwarding: ssh -R 80:localhost:<localPort> nokey@localhost.run
 * The public URL is extracted from the SSH session output.
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
    @Volatile private var urlReaderThread: Thread? = null

    /**
     * Start the SSH tunnel. Connects to localhost.run and sets up
     * remote port forwarding from port 80 to localhost:[localPort].
     * Extracts the public URL from the SSH channel output.
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
                // Disable password auth — localhost.run uses "none" auth
                sshSession.setConfig("PreferredAuthentications", "none")
                // Keep alive to maintain the tunnel
                sshSession.serverAliveInterval = 30_000
                sshSession.serverAliveCountMax = 3
                // Connection timeout
                sshSession.timeout = 15_000

                Log.i(TAG, "Connecting to $SSH_HOST...")
                sshSession.connect(15_000)
                session = sshSession

                // Open a channel to execute the remote forwarding command
                // localhost.run outputs the URL on the exec channel
                val channel = sshSession.openChannel("exec")
                val execChannel = channel as com.jcraft.jsch.ChannelExec
                execChannel.setCommand("ssh -R 80:localhost:$localPort nokey@localhost.run")

                // Actually, localhost.run works differently:
                // We use remote port forwarding via the SSH session itself,
                // and the URL comes back as output on a "direct-tcpip" or via the session.
                // The correct approach is to use setPortForwardingR and read the session log.

                // Close the exec channel, we don't need it
                execChannel.disconnect()

                // Set up remote port forwarding: remote 80 -> localhost:localPort
                // localhost.run will print the URL to the SSH transport
                sshSession.setPortForwardingR(REMOTE_PORT, "localhost", localPort)

                Log.i(TAG, "Port forwarding set up: remote:$REMOTE_PORT -> localhost:$localPort")

                // Open a shell channel to read the URL output
                val shellChannel = sshSession.openChannel("shell")
                shellChannel.connect(10_000)

                val reader = BufferedReader(InputStreamReader(shellChannel.inputStream))

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
                    }
                }.also { it.isDaemon = true; it.start() }

                // Give some time for the URL to arrive
                // If after 10s we still don't have a URL, set connecting to false
                Thread {
                    Thread.sleep(10_000)
                    if (_tunnelUrl.value == null && _isConnecting.value) {
                        _isConnecting.value = false
                        Log.w(TAG, "Timeout waiting for tunnel URL")
                    }
                }.also { it.isDaemon = true; it.start() }

            } catch (e: Exception) {
                Log.e(TAG, "Failed to start tunnel", e)
                _isConnecting.value = false
                _tunnelUrl.value = null
                session?.disconnect()
                session = null
            }
        }
    }

    /**
     * Stop the SSH tunnel and clean up resources.
     */
    fun stop() {
        urlReaderThread?.interrupt()
        urlReaderThread = null
        session?.disconnect()
        session = null
        _tunnelUrl.value = null
        _isConnecting.value = false
        Log.i(TAG, "Tunnel stopped")
    }

    /**
     * Check if the tunnel is currently connected.
     */
    fun isConnected(): Boolean = session?.isConnected == true

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
