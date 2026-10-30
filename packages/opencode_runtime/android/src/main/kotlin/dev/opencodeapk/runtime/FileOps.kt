package dev.opencodeapk.runtime

import android.system.ErrnoException
import android.system.Os
import java.io.File
import java.io.InputStream
import java.nio.file.Files

/** Filesystem operations the installer needs. Abstracted so JVM tests can run on any host OS. */
interface FileOps {
    /** Writes [input] to [dst] (replacing it) and applies [mode] (permission bits only). */
    fun writeFile(dst: File, input: InputStream, mode: Int)
    fun mkdir(dir: File, mode: Int)
    /** Creates [link] pointing at [target] (a guest path, kept as-is), replacing any existing entry. */
    fun symlink(target: String, link: File)
    fun hardlink(existing: File, link: File)
    fun isSymlink(f: File): Boolean
    fun rename(from: File, to: File): Boolean
    fun deleteRecursively(f: File)
}

class AndroidFileOps : FileOps {
    override fun writeFile(dst: File, input: InputStream, mode: Int) {
        dst.parentFile?.mkdirs()
        if (isSymlink(dst)) dst.delete()
        dst.outputStream().use { input.copyTo(it, 64 * 1024) }
        Os.chmod(dst.path, mode)
    }

    override fun mkdir(dir: File, mode: Int) {
        dir.mkdirs()
        Os.chmod(dir.path, mode or 0b111_000_000) // the app must always be able to enter its own dirs
    }

    override fun symlink(target: String, link: File) {
        link.parentFile?.mkdirs()
        if (isSymlink(link) || link.exists()) link.delete()
        Os.symlink(target, link.path)
    }

    override fun hardlink(existing: File, link: File) {
        link.parentFile?.mkdirs()
        if (isSymlink(link) || link.exists()) link.delete()
        try {
            Os.link(existing.path, link.path)
        } catch (e: ErrnoException) {
            // Some filesystems refuse hard links; a copy is equivalent for a read-mostly rootfs.
            existing.copyTo(link, overwrite = true)
            Os.chmod(link.path, Os.stat(existing.path).st_mode and 0b111_111_111)
        }
    }

    override fun isSymlink(f: File): Boolean = Files.isSymbolicLink(f.toPath())

    override fun rename(from: File, to: File): Boolean = from.renameTo(to)

    override fun deleteRecursively(f: File) {
        // Never follow symlinks: guest links such as /lib -> /usr/lib would otherwise resolve on the host.
        if (!isSymlink(f) && f.isDirectory) f.listFiles()?.forEach { deleteRecursively(it) }
        f.delete()
    }
}
