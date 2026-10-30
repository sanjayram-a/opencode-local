package dev.opencodeapk.runtime

import java.net.HttpURLConnection
import java.net.ServerSocket
import java.net.URL
import java.security.SecureRandom
import java.util.ArrayDeque
import java.util.Base64

data class ServerInfo(val port: Int, val password: String)

class ServerStartException(message: String, val logTail: String) : Exception(message)

/** Thread-safe ring buffer of the most recent output lines. */
class RingLog(private val capacity: Int = 2000) {
    private val lines = ArrayDeque<String>()

    @Synchronized
    fun add(line: String) {
        if (lines.size == capacity) lines.removeFirst()
        lines.addLast(line)
    }

    @Synchronized
    fun tail(n: Int = capacity): String = lines.toList().takeLast(n).joinToString("\n")
}

/**
 * Runs exactly one `opencode serve` inside proot and decides when it is ready.
 *
 * - Picks a fresh free port and a random password per start; the password goes in the env, never in argv.
 * - If the server exits before it is healthy, retries once with PROOT_NO_SECCOMP=1 (OEM seccomp quirks) and
 *   keeps using that setting for later starts.
 * - Gives up after [startTimeoutMs], killing the process, and reports the log tail.
 */
class ServerSupervisor(
    private val paths: Paths,
    private val spawner: Spawner = ProcessSpawner,
    private val probe: (port: Int, password: String) -> Boolean = ::httpProbe,
    private val freePort: () -> Int = ::pickFreePort,
    private val clock: Clock = Clock.System,
    private val heapMb: Int = 1024,
    private val startTimeoutMs: Long = 60_000,
) {
    val log = RingLog()

    private var current: Proc? = null
    private var pump: Thread? = null
    private var noSeccomp = false

    @Synchronized
    fun start(extraEnv: Map<String, String>): ServerInfo {
        stop()
        listOf(paths.home, paths.tmp, paths.prootTmp).forEach { it.mkdirs() }
        var portRetries = 0
        while (true) {
            val port = freePort()
            val password = randomPassword()
            val env = EnvBuilder.build(paths, extraEnv + ("OPENCODE_SERVER_PASSWORD" to password), noSeccomp, heapMb)
            val argv = ProotLauncher.argv(
                paths,
                listOf("/usr/local/bin/opencode", "serve", "--hostname", "127.0.0.1", "--port", port.toString()),
            )
            log.add("[supervisor] starting on port $port" + if (noSeccomp) " (PROOT_NO_SECCOMP=1)" else "")
            val startedAt = clock.now()
            val attemptLog = RingLog(200)
            val proc = spawner.spawn(argv, env, paths.filesDir)
            val reader = startPump(proc, attemptLog)

            val deadline = clock.now() + startTimeoutMs
            var delay = 100L
            while (clock.now() < deadline) {
                if (!proc.isAlive()) break
                if (probe(port, password)) {
                    current = proc
                    pump = reader
                    log.add("[supervisor] healthy on port $port after ${clock.now() - startedAt} ms")
                    return ServerInfo(port, password)
                }
                clock.sleep(delay)
                delay = minOf(delay * 2, 1000)
            }

            if (proc.isAlive()) {
                proc.destroy()
                reader.join(2000)
                throw ServerStartException("server not healthy after ${startTimeoutMs / 1000}s", log.tail(50))
            }
            reader.join(2000)
            log.add("[supervisor] server exited early with code ${proc.exitCode()}")
            if (PORT_IN_USE.containsMatchIn(attemptLog.tail()) && portRetries < MAX_PORT_RETRIES) {
                portRetries++ // lost the race for the port between picking and binding it: just pick another
                continue
            }
            if (noSeccomp) throw ServerStartException("server exited with code ${proc.exitCode()}", log.tail(50))
            noSeccomp = true
        }
    }

    @Synchronized
    fun stop() {
        current?.destroy()
        pump?.join(2000)
        current = null
        pump = null
    }

    @Synchronized
    fun isRunning(): Boolean = current?.isAlive() == true

    private fun startPump(proc: Proc, attemptLog: RingLog): Thread = Thread({
        try {
            proc.stdout.bufferedReader().forEachLine { log.add(it); attemptLog.add(it) }
        } catch (_: Exception) {
            // stream closed when the process is destroyed
        }
    }, "opencode-log").apply { isDaemon = true; start() }

    companion object {
        private val random = SecureRandom()
        private val PORT_IN_USE = Regex("EADDRINUSE|address already in use", RegexOption.IGNORE_CASE)
        private const val MAX_PORT_RETRIES = 3

        private fun randomPassword(): String {
            val bytes = ByteArray(18) // 18 bytes -> 24 base64 chars, no padding
            random.nextBytes(bytes)
            return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
        }

        fun pickFreePort(): Int = ServerSocket(0).use { it.localPort }

        /**
         * Healthy = the server accepts OUR password (2xx). A 401 means some other listener, or the wrong
         * password, so it does not count.
         */
        fun httpProbe(port: Int, password: String): Boolean = try {
            val conn = URL("http://127.0.0.1:$port/").openConnection() as HttpURLConnection
            conn.connectTimeout = 1000
            conn.readTimeout = 2000
            val token = Base64.getEncoder().encodeToString("opencode:$password".toByteArray())
            conn.setRequestProperty("Authorization", "Basic $token")
            try {
                conn.responseCode in 200..299
            } finally {
                conn.disconnect()
            }
        } catch (_: Exception) {
            false
        }
    }
}
