package dev.opencodeapk.runtime

import java.io.File

/** Every on-disk location the runtime uses. All of it lives in app-private storage. */
data class Paths(val filesDir: File, val nativeLibDir: File) {
    /** The active Alpine rootfs (guest `/`). */
    val rootfs: File get() = File(filesDir, "rootfs")

    /** Where a new rootfs is extracted before it is swapped in. */
    val rootfsNew: File get() = File(filesDir, "rootfs.new")

    /** The previous rootfs during a swap; removed once the swap succeeds. */
    val rootfsOld: File get() = File(filesDir, "rootfs.old")

    /** User data, bind-mounted to guest `/root`. Survives rootfs upgrades. */
    val home: File get() = File(filesDir, "home")

    /** Bind-mounted to guest `/tmp`. */
    val tmp: File get() = File(filesDir, "tmp")

    /** proot's own scratch directory (PROOT_TMP_DIR). */
    val prootTmp: File get() = File(filesDir, "proot-tmp")

    /** Number of tar entries already extracted into [rootfsNew]. */
    val progressFile: File get() = File(rootfsNew, ".extract-progress")

    /** Rootfs version the partial extraction in [rootfsNew] belongs to. */
    val progressVersionFile: File get() = File(rootfsNew, ".extract-version")
}
