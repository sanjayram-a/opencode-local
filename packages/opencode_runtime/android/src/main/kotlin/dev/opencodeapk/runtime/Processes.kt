package dev.opencodeapk.runtime

import java.io.File
import java.io.InputStream
import java.util.concurrent.TimeUnit

/** A running child process (stdout and stderr merged). */
interface Proc {
    val stdout: InputStream
    fun isAlive(): Boolean
    fun exitCode(): Int?
    fun destroy()
}

fun interface Spawner {
    fun spawn(argv: List<String>, env: Map<String, String>, cwd: File): Proc
}

interface Clock {
    fun now(): Long
    fun sleep(ms: Long)

    object System : Clock {
        override fun now() = java.lang.System.currentTimeMillis()
        override fun sleep(ms: Long) = Thread.sleep(ms)
    }
}

/** Real spawner: plain pipes via ProcessBuilder, with an environment built only from [env]. */
object ProcessSpawner : Spawner {
    override fun spawn(argv: List<String>, env: Map<String, String>, cwd: File): Proc {
        val pb = ProcessBuilder(argv).directory(cwd).redirectErrorStream(true)
        pb.environment().clear()
        pb.environment().putAll(env)
        val p = pb.start()
        p.outputStream.close()
        return object : Proc {
            override val stdout: InputStream = p.inputStream
            override fun isAlive() = p.isAlive
            override fun exitCode(): Int? = if (p.isAlive) null else p.exitValue()
            override fun destroy() {
                p.destroy() // proot runs with --kill-on-exit, so its guest processes go too
                if (!p.waitFor(3, TimeUnit.SECONDS)) p.destroyForcibly()
            }
        }
    }
}
