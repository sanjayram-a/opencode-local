package dev.opencodeapk.runtime

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

internal class LauncherTest {
    private val paths = Paths(filesDir = File("/data/user/0/dev.opencodeapk.app/files"), nativeLibDir = File("/data/app/x/lib/arm64"))
    private val lib = paths.nativeLibDir.path

    @Test
    fun argvMatchesSpec() {
        assertEquals(
            listOf(
                "$lib/libproot.so", "--kill-on-exit",
                "-r", paths.rootfs.path,
                "-b", "/dev", "-b", "/proc", "-b", "/sys",
                "-b", "${paths.home.path}:/root",
                "-b", "${paths.tmp.path}:/tmp",
                "-0", "-w", "/root", "--link2symlink",
                "/bin/sh", "-c", "true",
            ),
            ProotLauncher.argv(paths, listOf("/bin/sh", "-c", "true")),
        )
    }

    @Test
    fun argvUsesGivenCwd() {
        val argv = ProotLauncher.argv(paths, listOf("/bin/true"), cwd = "/root/projects")
        assertEquals("/root/projects", argv[argv.indexOf("-w") + 1])
    }

    @Test
    fun envHasRequiredKeys() {
        val expected = mapOf(
            "HOME" to "/root",
            "PATH" to "/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin",
            "TMPDIR" to "/tmp",
            "LANG" to "C.UTF-8",
            "TERM" to "xterm-256color",
            "PROOT_LOADER" to "$lib/libproot-loader.so",
            "PROOT_TMP_DIR" to paths.prootTmp.path,
            "LD_LIBRARY_PATH" to lib,
            "NODE_COMPILE_CACHE" to "/root/.cache/node-compile",
            "UV_CACHE_DIR" to "/root/.cache/uv",
            "npm_config_prefer_offline" to "true",
            "PIP_PREFER_BINARY" to "1",
            "NODE_OPTIONS" to "--max-old-space-size=1024",
            "EXTRA" to "yes",
        )
        assertEquals(expected, EnvBuilder.build(paths, extra = mapOf("EXTRA" to "yes")))
    }

    @Test
    fun noSeccompOnlyWhenAsked() {
        assertFalse(EnvBuilder.build(paths).containsKey("PROOT_NO_SECCOMP"))
        assertEquals("1", EnvBuilder.build(paths, noSeccomp = true)["PROOT_NO_SECCOMP"])
    }

    @Test
    fun heapSizeFlowsIntoNodeOptions() {
        assertEquals("--max-old-space-size=2048", EnvBuilder.build(paths, heapMb = 2048)["NODE_OPTIONS"])
    }

    @Test
    fun envDoesNotLeakHostEnv() {
        val env = EnvBuilder.build(paths)
        val allowed = setOf(
            "HOME", "PATH", "TMPDIR", "LANG", "TERM", "PROOT_LOADER", "PROOT_TMP_DIR", "LD_LIBRARY_PATH",
            "NODE_COMPILE_CACHE", "UV_CACHE_DIR", "npm_config_prefer_offline", "PIP_PREFER_BINARY", "NODE_OPTIONS",
        )
        val leaked = System.getenv().keys.filter { it in env && it !in allowed }
        assertEquals(emptyList(), leaked)
        assertEquals(allowed, env.keys)
    }
}
