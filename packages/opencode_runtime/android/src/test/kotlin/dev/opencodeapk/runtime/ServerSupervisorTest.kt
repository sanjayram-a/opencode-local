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
    fun stopDestroysRunningServer() {
        val clock = FakeClock()
        val procs = mutableListOf<FakeProc>()
        val sup = supervisor(FakeSpawner { FakeProc("", clock, exitAt = null).also { procs += it } }, clock)
        sup.start(emptyMap())
        sup.stop()
        assertTrue(procs.single().destroyed)
    }
}
