package dev.opencodeapk.runtime

import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import java.io.File
import java.io.IOException
import java.io.InputStream

/**
 * Streams a rootfs tarball into [Paths.rootfsNew], then swaps it in as [Paths.rootfs].
 *
 * - Resumable: the number of finished entries is stored after each one, so a killed install picks up where it
 *   stopped (re-reading and skipping the entries already done).
 * - Atomic: the active rootfs is only ever a fully extracted tree whose `.manifest.json` (the tar's last entry)
 *   matches the expected version.
 * - Safe: entries cannot write outside [Paths.rootfsNew] (no absolute names, no `..`, no writing through a symlink).
 */
class RootfsInstaller(private val paths: Paths, private val ops: FileOps) {

    /** Repairs the state left by a crash in the middle of a swap. Call on every start. */
    fun recover() {
        val rootfs = paths.rootfs
        val old = paths.rootfsOld
        if (!rootfs.exists() && old.exists()) {
            if (!ops.rename(old, rootfs)) throw IOException("cannot restore $old")
        } else if (rootfs.exists() && old.exists()) {
            ops.deleteRecursively(old)
        }
    }

    /** Version from the active rootfs manifest, or null when no rootfs is installed. */
    fun installedVersion(): String? = readManifestVersion(File(paths.rootfs, MANIFEST))

    fun install(openTar: () -> InputStream, bundledVersion: String, onProgress: (entriesDone: Int) -> Unit) {
        recover()
        val target = paths.rootfsNew
        val done = resumePoint(bundledVersion)
        if (done == 0) {
            ops.deleteRecursively(target)
            target.mkdirs()
            paths.progressVersionFile.writeText(bundledVersion)
        }

        try {
            TarArchiveInputStream(openTar()).use { tar ->
                var index = 0
                while (true) {
                    val entry = tar.nextEntry ?: break
                    if (index < done) {
                        index++
                        continue
                    }
                    val rel = safeRelative(entry.name)
                    if (rel.isNotEmpty()) {
                        val dst = File(target, rel)
                        requireNoSymlinkParent(target, dst)
                        val mode = entry.mode and PERMISSION_BITS
                        when {
                            entry.isDirectory -> ops.mkdir(dst, mode)
                            entry.isSymbolicLink -> ops.symlink(entry.linkName, dst)
                            entry.isLink -> ops.hardlink(File(target, safeRelative(entry.linkName)), dst)
                            entry.isFile -> ops.writeFile(dst, tar, mode)
                            else -> Unit // devices, fifos: proot binds the host /dev instead
                        }
                    }
                    index++
                    paths.progressFile.writeText(index.toString())
                    onProgress(index)
                }
            }
        } catch (e: SecurityException) {
            ops.deleteRecursively(target) // a malicious or corrupt archive is not resumable
            throw e
        }

        val version = readManifestVersion(File(target, MANIFEST))
        if (version != bundledVersion) {
            ops.deleteRecursively(target)
            throw IOException("rootfs manifest version $version does not match expected $bundledVersion")
        }
        paths.progressFile.delete()
        paths.progressVersionFile.delete()
        swapIn()
    }

    private fun resumePoint(bundledVersion: String): Int {
        if (!paths.progressFile.exists()) return 0
        if (paths.progressVersionFile.takeIf { it.exists() }?.readText()?.trim() != bundledVersion) return 0
        return paths.progressFile.readText().trim().toIntOrNull() ?: 0
    }

    private fun swapIn() {
        val rootfs = paths.rootfs
        if (rootfs.exists() && !ops.rename(rootfs, paths.rootfsOld)) throw IOException("cannot move old rootfs aside")
        if (!ops.rename(paths.rootfsNew, rootfs)) {
            recover()
            throw IOException("cannot move new rootfs into place")
        }
        ops.deleteRecursively(paths.rootfsOld)
    }

    private fun requireNoSymlinkParent(root: File, dst: File) {
        var p = dst.parentFile
        while (p != null && p != root) {
            if (ops.isSymlink(p)) throw SecurityException("entry ${dst.path} would be written through symlink ${p.path}")
            p = p.parentFile
        }
    }

    companion object {
        const val MANIFEST = ".manifest.json"
        private const val PERMISSION_BITS = 0b111_111_111
        private val VERSION = Regex("\"version\"\\s*:\\s*\"([^\"]+)\"")

        /** Normalises a tar name to a relative path; "" means the archive root. Rejects anything escaping it. */
        internal fun safeRelative(name: String): String {
            if (name.startsWith("/") || name.contains('\\') || name.contains('\u0000')) {
                throw SecurityException("unsafe tar entry name: $name")
            }
            val parts = name.split('/').filter { it.isNotEmpty() && it != "." }
            if (parts.any { it == ".." }) throw SecurityException("unsafe tar entry name: $name")
            return parts.joinToString("/")
        }

        private fun readManifestVersion(f: File): String? =
            if (f.isFile) VERSION.find(f.readText())?.groupValues?.get(1) else null
    }
}
