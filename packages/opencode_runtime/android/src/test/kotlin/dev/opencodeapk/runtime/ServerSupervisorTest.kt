package dev.opencodeapk.runtime

import java.io.ByteArrayInputStream
import java.io.File
import java.io.InputStream
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

internal class ServerSupervisorTest {
    private lateinit var base: File
    private lateinit var paths: Paths

    @BeforeTest
    fun setUp() {
        base = Files.createTempDirectory("supervisor-test").toFile()
        paths = Paths(File(base, "files"), File(base, "lib"))
    }

    @AfterTest
    fun tearDown() {
        base.deleteRecursively()
    }

    /** Time only moves when the supervisor sleeps, so tests never wait for real. */
    private class FakeClock : Clock {
        var t = 0L
        override fun now() = t
        override fun sleep(ms: Long) { t += ms }
    }

    private class FakeProc(output: String, private val clock: FakeClock, private val exitAt: Long?, private val code: Int = 1) : Proc {
        override val stdout: InputStream = ByteArrayInputStream(output.toByteArray())
        var destroyed = false
        override fun isAlive() = !destroyed && (exitAt == null || clock.now() < exitAt)
        override fun exitCode(): Int? = if (isAlive()) null else code
        override fun destroy() { destroyed = true }
    }

    private class Spawn(val argv: List<String>, val env: Map<String, String>)

    private class FakeSpawner(private val make: (Int) -> Proc) : Spawner {
        val spawns = mutableListOf<Spawn>()
        override fun spawn(argv: List<String>, env: Map<String, String>, cwd: File): Proc {
            spawns += Spawn(argv, env)
            return make(spawns.size - 1)
        }
    }

    private fun supervisor(
        spawner: Spawner,
        clock: FakeClock,
        healthyAfter: Long = 500,
        ports: Iterator<Int> = generateSequence(4100) { it + 1 }.iterator(),
    ) = ServerSupervisor(
        paths = paths,
        spawner = spawner,
        probe = { _, _ -> clock.now() >= healthyAfter },
        freePort = { ports.next() },
        clock = clock,
    )

    @Test
    fun picksFreePortEachStart() {
        val clock = FakeClock()
        val spawner = FakeSpawner { FakeProc("", clock, exitAt = null) }
        val sup = supervisor(spawner, clock, ports = listOf(4100, 4101).iterator())

        assertEquals(4100, sup.start(emptyMap()).port)
        sup.stop()
        clock.t = 0
        assertEquals(4101, sup.start(emptyMap()).port)

        val argv = spawner.spawns.last().argv
        assertEquals("4101", argv[argv.indexOf("--port") + 1])
        assertEquals(listOf("/usr/local/bin/opencode", "serve", "--hostname", "127.0.0.1", "--port", "4101"), argv.takeLast(6))
    }

    @Test
    fun retriesWithNoSeccompOnEarlyExit() {
        val clock = FakeClock()
        val spawner = FakeSpawner { i -> if (i == 0) FakeProc("seccomp trouble\n", clock, exitAt = 200) else FakeProc("", clock, exitAt = null) }
        val sup = supervisor(spawner, clock, healthyAfter = 1_000)

        sup.start(emptyMap())

        assertEquals(2, spawner.spawns.size)
        assertFalse(spawner.spawns[0].env.containsKey("PROOT_NO_SECCOMP"))
        assertEquals("1", spawner.spawns[1].env["PROOT_NO_SECCOMP"])
    }

    @Test
    fun failsWithLogTailAfterSecondEarlyExit() {
        val clock = FakeClock()
        val spawner = FakeSpawner { FakeProc("boom\n", clock, exitAt = clock.t + 100) }
        val sup = supervisor(spawner, clock, healthyAfter = Long.MAX_VALUE)

        val e = assertFailsWith<ServerStartException> { sup.start(emptyMap()) }

        assertTrue("boom" in e.logTail, e.logTail)
        assertEquals(2, spawner.spawns.size)
    }

    @Test
    fun timesOutAndKillsServerThatNeverGetsHealthy() {
        val clock = FakeClock()
        val procs = mutableListOf<FakeProc>()
        val spawner = FakeSpawner { FakeProc("", clock, exitAt = null).also { procs += it } }
        val sup = supervisor(spawner, clock, healthyAfter = Long.MAX_VALUE)

        assertFailsWith<ServerStartException> { sup.start(emptyMap()) }

        assertEquals(1, spawner.spawns.size)
        assertTrue(procs.single().destroyed)
        assertTrue(clock.t >= 60_000)
    }

    @Test
    fun passwordInEnvNotArgv() {
        val clock = FakeClock()
        val spawner = FakeSpawner { FakeProc("", clock, exitAt = null) }
        val info = supervisor(spawner, clock).start(mapOf("ANTHROPIC_API_KEY" to "k"))

        val spawn = spawner.spawns.single()
        assertEquals(info.password, spawn.env["OPENCODE_SERVER_PASSWORD"])
        assertEquals(24, info.password.length)
        assertTrue(spawn.argv.none { info.password in it })
        assertEquals("k", spawn.env["ANTHROPIC_API_KEY"])
    }

    @Test
    fun portInUseRetriesNewPortWithoutNoSeccomp() {
        val clock = FakeClock()
        val spawner = FakeSpawner { i ->
            if (i < 2) FakeProc("error: listen EADDRINUSE: address already in use\n", clock, exitAt = clock.t)
            else FakeProc("", clock, exitAt = null)
        }
        val info = supervisor(spawner, clock, healthyAfter = 0).start(emptyMap())

        assertEquals(3, spawner.spawns.size)
        assertTrue(spawner.spawns.none { it.env.containsKey("PROOT_NO_SECCOMP") })
        assertEquals(4102, info.port)
    }

    @Test
    fun httpProbeRequiresSuccessWithOurPassword() {
        // Minimal HTTP server: 200 only for the right Basic credentials, 401 otherwise (like opencode serve).
        val expected = "Basic " + java.util.Base64.getEncoder().encodeToString("opencode:right".toByteArray())
        val server = java.net.ServerSocket(0, 50, java.net.InetAddress.getByName("127.0.0.1"))
        val t = Thread {
            while (!server.isClosed) {
                val s = runCatching { server.accept() }.getOrNull() ?: break
                s.use {
                    val headers = it.getInputStream().bufferedReader().let { r ->
                        generateSequence { r.readLine() }.takeWhile { l -> l.isNotEmpty() }.toList()
                    }
                    val ok = headers.any { h -> h.equals("Authorization: $expected", ignoreCase = true) }
                    val status = if (ok) "200 OK" else "401 Unauthorized"
                    it.getOutputStream().write("HTTP/1.1 $status\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".toByteArray())
                }
            }
        }.apply { isDaemon = true; start() }
        try {
            assertTrue(ServerSupervisor.httpProbe(server.localPort, "right"))
            assertFalse(ServerSupervisor.httpProbe(server.localPort, "wrong"))
        } finally {
            server.close()
            t.join(2000)
        }
    }

    @Test
    fun logsTimeToHealthy() {
        val clock = FakeClock()
        val sup = supervisor(FakeSpawner { FakeProc("", clock, exitAt = null) }, clock, healthyAfter = 700)
        sup.start(emptyMap())
        assertTrue(Regex("""healthy on port 4100 after \d+ ms""").containsMatchIn(sup.log.tail()), sup.log.tail())
        val ms = Regex("""after (\d+) ms""").find(sup.log.tail())!!.groupValues[1].toLong()
        assertTrue(ms in 700..1500, "ms=$ms")
    }

    @Test
    fun stopDestroysRunningServer() {
        val clock = FakeClock()
        val procs = mutableListOf<FakeProc>()
        val sup = supervisor(FakeSpawner { FakeProc("", clock, exitAt = null).also { procs += it } }, clock)
        sup.start(emptyMap())
        sup.stop()
        assertTrue(procs.single().destroyed)
    }
}
