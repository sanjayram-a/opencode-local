package dev.opencodeapk.runtime

/**
 * The complete environment for every child process. It is built from scratch: nothing from the app's own
 * environment is inherited, so the guest sees the same variables on every device.
 */
object EnvBuilder {
    fun build(
        paths: Paths,
        extra: Map<String, String> = emptyMap(),
        noSeccomp: Boolean = false,
        heapMb: Int = 1024,
    ): Map<String, String> {
        val lib = paths.nativeLibDir.path
        val env = linkedMapOf(
            "HOME" to "/root",
            "PATH" to "/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin",
            "TMPDIR" to "/tmp",
            "LANG" to "C.UTF-8",
            "TERM" to "xterm-256color",
            // proot is a host (Android) binary: it needs its loader and libtalloc/libandroid-shmem from nativeLibraryDir.
            "PROOT_LOADER" to "$lib/libproot-loader.so",
            "PROOT_TMP_DIR" to paths.prootTmp.path,
            "LD_LIBRARY_PATH" to lib,
            // Warm caches live in /root so they survive rootfs upgrades.
            "NODE_COMPILE_CACHE" to "/root/.cache/node-compile",
            "UV_CACHE_DIR" to "/root/.cache/uv",
            "npm_config_prefer_offline" to "true",
            "PIP_PREFER_BINARY" to "1",
            "NODE_OPTIONS" to "--max-old-space-size=$heapMb",
        )
        if (noSeccomp) env["PROOT_NO_SECCOMP"] = "1"
        env.putAll(extra)
        return env
    }
}
