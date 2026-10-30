package dev.opencodeapk.runtime

import android.app.ActivityManager
import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import com.github.luben.zstd.ZstdInputStream
import io.flutter.embedding.engine.plugins.FlutterPlugin
import io.flutter.plugin.common.EventChannel
import io.flutter.plugin.common.MethodCall
import io.flutter.plugin.common.MethodChannel
import java.io.BufferedInputStream
import java.io.File
import java.util.concurrent.Executors

/**
 * Flutter bridge.
 *  - MethodChannel "opencode_runtime": selfTest, startServer, stopServer, serverLog
 *  - EventChannel "opencode_runtime/install": entries extracted so far; ends when the rootfs is ready
 * All work runs on background threads; results are posted back on the main thread.
 */
class OpencodeRuntimePlugin : FlutterPlugin, MethodChannel.MethodCallHandler {
    private lateinit var context: Context
    private lateinit var methods: MethodChannel
    private lateinit var installEvents: EventChannel
    private lateinit var paths: Paths
    private lateinit var supervisor: ServerSupervisor
    private val main = Handler(Looper.getMainLooper())
    private val worker = Executors.newCachedThreadPool()

    override fun onAttachedToEngine(binding: FlutterPlugin.FlutterPluginBinding) {
        context = binding.applicationContext
        paths = Paths(context.filesDir, File(context.applicationInfo.nativeLibraryDir))
        supervisor = ServerSupervisor(paths, heapMb = nodeHeapMb())
        methods = MethodChannel(binding.binaryMessenger, "opencode_runtime").also { it.setMethodCallHandler(this) }
        installEvents = EventChannel(binding.binaryMessenger, "opencode_runtime/install").also {
            it.setStreamHandler(InstallStream())
        }
    }

    override fun onDetachedFromEngine(binding: FlutterPlugin.FlutterPluginBinding) {
        methods.setMethodCallHandler(null)
        installEvents.setStreamHandler(null)
        worker.execute { supervisor.stop() }
    }

    override fun onMethodCall(call: MethodCall, result: MethodChannel.Result) {
        when (call.method) {
            "selfTest" -> background(result) { selfTest() }
            "startServer" -> background(result) {
                @Suppress("UNCHECKED_CAST")
                val env = (call.argument<Map<String, String>>("env") ?: emptyMap())
                val info = supervisor.start(env)
                mapOf("port" to info.port, "password" to info.password)
            }
            "stopServer" -> background(result) { supervisor.stop(); null }
            "serverLog" -> result.success(supervisor.log.tail())
            else -> result.notImplemented()
        }
    }

    private fun background(result: MethodChannel.Result, work: () -> Any?) {
        worker.execute {
            try {
                val value = work()
                main.post { result.success(value) }
            } catch (e: ServerStartException) {
                main.post { result.error("server_start", e.message, e.logTail) }
            } catch (e: Exception) {
                main.post { result.error(e.javaClass.simpleName, e.message, null) }
            }
        }
    }

    /** Runs one shell line through proot and returns one value per probe. */
    private fun selfTest(): Map<String, String> {
        listOf(paths.home, paths.tmp, paths.prootTmp).forEach { it.mkdirs() }
        val keys = listOf("arch", "node", "python", "opencode")
        val script = "uname -m; node -v; python3 -V; /usr/local/bin/opencode --version"
        val argv = ProotLauncher.argv(paths, listOf("/bin/sh", "-c", script))
        val proc = ProcessSpawner.spawn(argv, EnvBuilder.build(paths), paths.filesDir)
        val lines = proc.stdout.bufferedReader().readLines()
        val values = lines.filter { it.isNotBlank() && !it.startsWith("proot warning") }
        if (values.size < keys.size) throw IllegalStateException("self-test output:\n" + lines.joinToString("\n"))
        return keys.zip(values.takeLast(keys.size)).toMap()
    }

    private fun nodeHeapMb(): Int {
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val info = ActivityManager.MemoryInfo().also { am.getMemoryInfo(it) }
        return (info.totalMem / (1024 * 1024) / 4).toInt().coerceIn(512, 2048)
    }

    private inner class InstallStream : EventChannel.StreamHandler {
        override fun onListen(arguments: Any?, events: EventChannel.EventSink) {
            worker.execute {
                try {
                    val abi = Build.SUPPORTED_ABIS.first()
                    val bundled = context.assets.open("rootfs/rootfs.version").bufferedReader().readText().trim()
                    RootfsInstaller.ensureInstalled(
                        RootfsInstaller(paths, AndroidFileOps()),
                        openTar = {
                            ZstdInputStream(BufferedInputStream(context.assets.open("rootfs/rootfs-$abi.tar.zst"), 1 shl 20))
                        },
                        bundledVersion = bundled,
                    ) { n -> if (n % 100 == 0) main.post { events.success(n) } }
                    main.post { events.endOfStream() }
                } catch (e: Exception) {
                    main.post { events.error(e.javaClass.simpleName, e.message, null) }
                }
            }
        }

        override fun onCancel(arguments: Any?) = Unit
    }
}
