package dev.opencodeapk.runtime

import android.os.ParcelFileDescriptor
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream

/** JNI bindings to cpp/pty.c. */
object Pty {
    init {
        System.loadLibrary("opencode_pty")
    }

    /** Returns [masterFd, pid]. */
    @JvmStatic external fun spawn(argv: Array<String>, env: Array<String>, cwd: String, rows: Int, cols: Int): IntArray
    @JvmStatic external fun resize(fd: Int, rows: Int, cols: Int)
    @JvmStatic external fun waitFor(pid: Int): Int
    @JvmStatic external fun kill(pid: Int, sig: Int)
}

/** An interactive process on a pseudo-terminal (used by the terminal tab). */
class PtySession(argv: List<String>, env: Map<String, String>, cwd: String, rows: Int, cols: Int) {
    private val fd: Int
    val pid: Int
    private val pfd: ParcelFileDescriptor
    private val out: FileOutputStream

    @Volatile private var exitCode: Int? = null

    init {
        val r = Pty.spawn(argv.toTypedArray(), env.map { "${it.key}=${it.value}" }.toTypedArray(), cwd, rows, cols)
        fd = r[0]
        pid = r[1]
        pfd = ParcelFileDescriptor.adoptFd(fd) // takes ownership: closing pfd closes the master fd
        out = FileOutputStream(pfd.fileDescriptor)
    }

    /** Terminal output. Ends (returns -1) when the child has exited and its output is drained. */
    val output: InputStream = object : InputStream() {
        private val raw = FileInputStream(pfd.fileDescriptor)
        override fun read(): Int {
            val b = ByteArray(1)
            return if (read(b, 0, 1) < 0) -1 else b[0].toInt() and 0xff
        }
        override fun read(b: ByteArray, off: Int, len: Int): Int = try {
            raw.read(b, off, len)
        } catch (e: IOException) {
            -1 // Linux returns EIO on the master once the slave side is closed: that is end-of-stream
        }
    }

    fun write(bytes: ByteArray) = out.write(bytes)

    fun resize(rows: Int, cols: Int) = Pty.resize(fd, rows, cols)

    /** Blocks until the child exits; returns its exit code (128+signal if killed). */
    fun waitFor(): Int = exitCode ?: Pty.waitFor(pid).also { exitCode = it }

    fun close() {
        if (exitCode == null) {
            Pty.kill(pid, 1) // SIGHUP, like closing a terminal window
            Thread({ waitFor() }, "pty-reaper").apply { isDaemon = true; start() } // avoid a zombie
        }
        pfd.close()
    }
}
