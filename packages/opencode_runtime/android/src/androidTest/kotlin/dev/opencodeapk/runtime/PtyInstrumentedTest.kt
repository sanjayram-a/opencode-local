package dev.opencodeapk.runtime

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PtyInstrumentedTest {
    private val env = mapOf("PATH" to "/system/bin", "TERM" to "xterm-256color")

    @Test
    fun runsCommandOnPtyAndReportsExitCode() {
        val s = PtySession(listOf("/system/bin/sh", "-c", "echo hello-pty; exit 3"), env, "/", rows = 24, cols = 80)
        val out = s.output.bufferedReader().readText()
        assertTrue(out, out.contains("hello-pty"))
        assertEquals(3, s.waitFor())
        s.close()
    }

    @Test
    fun resizeIsVisibleToChild() {
        val s = PtySession(listOf("/system/bin/sh", "-c", "sleep 0.3; stty size"), env, "/", rows = 24, cols = 80)
        s.resize(rows = 40, cols = 120)
        val out = s.output.bufferedReader().readText()
        assertTrue(out, out.contains("40 120"))
        s.close()
    }

    @Test
    fun inputReachesChild() {
        val s = PtySession(listOf("/system/bin/sh", "-c", "read x; echo got-\$x"), env, "/", rows = 24, cols = 80)
        s.write("abc\n".toByteArray())
        val out = s.output.bufferedReader().readText()
        assertTrue(out, out.contains("got-abc"))
        s.close()
    }
}
