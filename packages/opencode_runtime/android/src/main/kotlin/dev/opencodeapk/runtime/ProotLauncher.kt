package dev.opencodeapk.runtime

/** Builds the one command line used to run anything inside the Alpine rootfs. */
object ProotLauncher {
    fun argv(paths: Paths, guestCmd: List<String>, cwd: String = "/root"): List<String> = buildList {
        add("${paths.nativeLibDir.path}/libproot.so") // the only exec from the host side; it lives in nativeLibraryDir
        add("--kill-on-exit")
        add("-r"); add(paths.rootfs.path)
        add("-b"); add("/dev")
        add("-b"); add("/proc")
        add("-b"); add("/sys")
        add("-b"); add("${paths.home.path}:/root")
        add("-b"); add("${paths.tmp.path}:/tmp")
        add("-0") // fake root (ptrace-spoofed uid 0, no real privilege)
        add("-w"); add(cwd)
        add("--link2symlink")
        addAll(guestCmd)
    }
}
