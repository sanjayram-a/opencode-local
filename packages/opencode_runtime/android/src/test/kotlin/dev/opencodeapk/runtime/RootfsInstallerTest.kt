package dev.opencodeapk.runtime

import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream
import org.apache.commons.compress.archivers.tar.TarConstants
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FilterInputStream
import java.io.IOException
import java.io.InputStream
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

internal class RootfsInstallerTest {
    private lateinit var base: File
    private lateinit var paths: Paths
    private lateinit var ops: RecordingFileOps

    @BeforeTest
    fun setUp() {
        base = Files.createTempDirectory("rootfs-test").toFile()
        paths = Paths(filesDir = File(base, "files"), nativeLibDir = File(base, "lib"))
        paths.filesDir.mkdirs()
        ops = RecordingFileOps()
    }

    @AfterTest
    fun tearDown() {
        base.deleteRecursively()
    }

    // ---- fixture helpers ----

    private sealed class E(val name: String) {
        class Dir(name: String, val mode: Int = 0b111_101_101) : E(name)
        class Reg(name: String, val content: String, val mode: Int = 0b110_100_100) : E(name)
        class Sym(name: String, val target: String) : E(name)
        class Hard(name: String, val target: String) : E(name)
    }

    private fun manifest(version: String) = E.Reg(".manifest.json", """{"version":"$version","opencode":"1.18.3","arch":"x86_64"}""")

    private fun tar(vararg entries: E): ByteArray {
        val bytes = ByteArrayOutputStream()
        TarArchiveOutputStream(bytes).use { out ->
            out.setLongFileMode(TarArchiveOutputStream.LONGFILE_POSIX)
            for (e in entries) {
                val entry = when (e) {
                    is E.Dir -> TarArchiveEntry(e.name.trimEnd('/') + "/").apply { mode = e.mode }
                    // preserveAbsolutePath = true: otherwise commons-compress silently strips a leading "/".
                    is E.Reg -> TarArchiveEntry(e.name, true).apply { mode = e.mode; size = e.content.toByteArray().size.toLong() }
                    is E.Sym -> TarArchiveEntry(e.name, TarConstants.LF_SYMLINK).apply { linkName = e.target }
                    is E.Hard -> TarArchiveEntry(e.name, TarConstants.LF_LINK).apply { linkName = e.target }
                }
                out.putArchiveEntry(entry)
                if (e is E.Reg) out.write(e.content.toByteArray())
                out.closeArchiveEntry()
            }
        }
        return bytes.toByteArray()
    }

    /** Lets exactly [okEntries] tar entries through, then fails like a killed process would. */
    private fun failingAfter(data: ByteArray, okEntries: Int): InputStream {
        // Each fixture entry here is header (512) + one data block (512) at most, so cut at a block boundary
        // past the last good entry's data.
        val cut = okEntries * 1024
        return object : FilterInputStream(ByteArrayInputStream(data)) {
            var pos = 0
            override fun read(): Int {
                if (pos >= cut) throw IOException("killed")
                return super.read().also { if (it >= 0) pos++ }
            }
            override fun read(b: ByteArray, off: Int, len: Int): Int {
                if (pos >= cut) throw IOException("killed")
                val n = super.read(b, off, minOf(len, cut - pos))
                if (n > 0) pos += n
                return n
            }
        }
    }

    private fun installer() = RootfsInstaller(paths, ops)

    // ---- tests ----

    @Test
    fun extractsFilesDirsModesAndLinks() {
        val data = tar(
            E.Dir("bin"),
            E.Reg("bin/sh", "x", mode = 0b111_101_101),
            E.Sym("usr/bin/env", "/bin/sh"),
            E.Hard("bin/ash", "bin/sh"),
            manifest("1"),
        )
        installer().install({ ByteArrayInputStream(data) }, "1") {}

        val sh = File(paths.rootfs, "bin/sh")
        assertEquals("x", sh.readText())
        assertEquals(0b111_101_101, ops.modes[sh.canonicalPath])
        assertEquals("/bin/sh", ops.symlinks[File(paths.rootfs, "usr/bin/env").canonicalPath])
        assertEquals(sh.canonicalPath, ops.hardlinks[File(paths.rootfs, "bin/ash").canonicalPath])
    }

    @Test
    fun resumesAfterInterruption() {
        val data = tar(
            E.Reg("a", "1"),
            E.Reg("b", "2"),
            E.Reg("c", "3"),
            manifest("1"),
        )
        assertFailsWith<IOException> { installer().install({ failingAfter(data, 2) }, "1") {} }
        assertEquals("2", paths.progressFile.readText().trim())

        val seen = mutableListOf<Int>()
        installer().install({ ByteArrayInputStream(data) }, "1") { seen += it }
        assertEquals(3, seen.first())
        for (name in listOf("a", "b", "c", ".manifest.json")) assertTrue(File(paths.rootfs, name).exists(), name)
    }

    @Test
    fun neverExposesPartialRootfs() {
        val data = tar(E.Reg("a", "1"), E.Reg("b", "2"), E.Reg("c", "3"), manifest("1"))
        assertFailsWith<IOException> { installer().install({ failingAfter(data, 2) }, "1") {} }
        assertFalse(paths.rootfs.exists())
    }

    @Test
    fun swapsAtomicallyAndKeepsHome() {
        installer().install({ ByteArrayInputStream(tar(E.Reg("old", "o"), manifest("1"))) }, "1") {}
        paths.home.mkdirs()
        File(paths.home, "a.txt").writeText("keep")

        installer().install({ ByteArrayInputStream(tar(E.Reg("new", "n"), manifest("2"))) }, "2") {}

        assertEquals("2", installer().installedVersion())
        assertFalse(paths.rootfsOld.exists())
        assertFalse(File(paths.rootfs, "old").exists())
        assertEquals("keep", File(paths.home, "a.txt").readText())
    }

    @Test
    fun recoversFromInterruptedSwap() {
        installer().install({ ByteArrayInputStream(tar(E.Reg("old", "o"), manifest("1"))) }, "1") {}
        assertTrue(paths.rootfs.renameTo(paths.rootfsOld))

        installer().recover()

        assertEquals("1", installer().installedVersion())
        assertFalse(paths.rootfsOld.exists())
    }

    @Test
    fun rejectsPathTraversal() {
        val cases = listOf(
            tar(E.Reg("../evil", "x"), manifest("1")),
            tar(E.Reg("/abs", "x"), manifest("1")),
            tar(E.Sym("x", "../../.."), E.Reg("x/escape", "x"), manifest("1")),
            tar(E.Reg("a", "x"), E.Hard("b", "../../outside"), manifest("1")),
        )
        for (data in cases) {
            assertFailsWith<SecurityException> { installer().install({ ByteArrayInputStream(data) }, "1") {} }
        }
        val inside = paths.rootfsNew.canonicalPath
        val stray = base.walkTopDown().filter { it.isFile && !it.canonicalPath.startsWith(inside) }
            .map { it.name }.toList()
        assertEquals(emptyList(), stray)
        assertFalse(paths.rootfs.exists())
    }

    @Test
    fun rejectsHardlinkThroughSymlink() {
        val data = tar(E.Sym("d", "../.."), E.Hard("h", "d/shared_prefs/x.xml"), manifest("1"))
        assertFailsWith<SecurityException> { installer().install({ ByteArrayInputStream(data) }, "1") {} }
        assertTrue(ops.hardlinks.isEmpty())
        assertFalse(paths.rootfs.exists())
    }

    @Test
    fun rejectsDirectoryOnTopOfSymlink() {
        val data = tar(E.Sym("a", "../.."), E.Dir("a", 0b111_111_111), manifest("1"))
        assertFailsWith<SecurityException> { installer().install({ ByteArrayInputStream(data) }, "1") {} }
        assertFalse(ops.modes.values.contains(0b111_111_111))
    }

    @Test
    fun resumeOverwritesReadOnlyFileWrittenBeforeKill() {
        // State after a kill between writing entry "b" (mode 0444) and recording progress for it.
        paths.rootfsNew.mkdirs()
        paths.progressVersionFile.writeText("1")
        paths.progressFile.writeText("1")
        File(paths.rootfsNew, "a").writeText("1")
        File(paths.rootfsNew, "b").apply { writeText("partial"); setWritable(false) }

        val data = tar(E.Reg("a", "1"), E.Reg("b", "2", mode = 0b100_100_100), manifest("1"))
        installer().install({ ByteArrayInputStream(data) }, "1") {}

        assertEquals("2", File(paths.rootfs, "b").readText())
    }

    @Test
    fun concurrentEnsureInstalledExtractsOnce() {
        val data = tar(E.Reg("a", "1"), E.Reg("b", "2"), manifest("1"))
        val opens = java.util.concurrent.atomic.AtomicInteger()
        val release = java.util.concurrent.CountDownLatch(1)
        val open = {
            opens.incrementAndGet()
            release.await(5, java.util.concurrent.TimeUnit.SECONDS) // hold the first install open
            ByteArrayInputStream(data)
        }
        val errors = java.util.Collections.synchronizedList(mutableListOf<Throwable>())
        val threads = List(2) {
            Thread { runCatching { RootfsInstaller.ensureInstalled(installer(), open, "1") { _: Int -> } }.onFailure { e: Throwable -> errors += e } }
        }
        threads.forEach { it.start() }
        Thread.sleep(300)
        release.countDown()
        threads.forEach { it.join(10_000) }

        assertEquals(emptyList(), errors)
        assertEquals(1, opens.get())
        assertEquals("1", installer().installedVersion())
    }

    @Test
    fun rejectsManifestVersionMismatch() {
        val data = tar(E.Reg("a", "1"), manifest("1"))
        assertFailsWith<IOException> { installer().install({ ByteArrayInputStream(data) }, "2") {} }
        assertFalse(paths.rootfs.exists())
    }
}

/** Writes regular files for real; records modes and links so tests behave the same on Windows. */
internal class RecordingFileOps : FileOps {
    val modes = mutableMapOf<String, Int>()
    val symlinks = mutableMapOf<String, String>()
    val hardlinks = mutableMapOf<String, String>()

    override fun writeFile(dst: File, input: InputStream, mode: Int) {
        dst.parentFile?.mkdirs()
        dst.outputStream().use { input.copyTo(it) }
        modes[dst.canonicalPath] = mode
    }

    override fun mkdir(dir: File, mode: Int) {
        dir.mkdirs()
        modes[dir.canonicalPath] = mode
    }

    override fun symlink(target: String, link: File) {
        link.parentFile?.mkdirs()
        symlinks[link.canonicalPath] = target
    }

    override fun hardlink(existing: File, link: File) {
        link.parentFile?.mkdirs()
        hardlinks[link.canonicalPath] = existing.canonicalPath
    }

    override fun isSymlink(f: File): Boolean = symlinks.containsKey(f.canonicalPath)

    override fun rename(from: File, to: File): Boolean {
        val src = from.canonicalPath
        val dst = to.canonicalPath
        if (!from.renameTo(to)) return false
        fun <V> rekey(m: MutableMap<String, V>) {
            m.keys.filter { it == src || it.startsWith(src + File.separator) }.forEach { k ->
                m[dst + k.removePrefix(src)] = m.remove(k)!!
            }
        }
        rekey(modes)
        rekey(symlinks)
        rekey(hardlinks)
        hardlinks.replaceAll { _, v -> if (v.startsWith(src + File.separator)) dst + v.removePrefix(src) else v }
        return true
    }

    override fun deleteRecursively(f: File) {
        val prefix = f.canonicalPath
        // POSIX semantics: a read-only file in a writable directory can still be deleted (Windows refuses).
        f.walkTopDown().forEach { it.setWritable(true) }
        f.deleteRecursively()
        listOf(modes, symlinks, hardlinks).forEach { m -> m.keys.removeAll { it.startsWith(prefix) } }
    }
}
