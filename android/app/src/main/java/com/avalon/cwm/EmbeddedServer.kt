package com.avalon.cwm

import android.content.Context
import android.util.Log
import com.avalon.cwm.backend.server.NativeHttpServer
import java.util.Collections
import java.util.IdentityHashMap
import java.util.UUID
import java.util.concurrent.Executors

/** Process-wide owner of the native Kotlin HTTP backend. */
object EmbeddedServer {
    private const val TAG = "CwmEmbeddedServer"
    private const val PORT = 8080

    /** Used only by trusted native probes; never persisted or exposed to web content. */
    val nativeToken: String = UUID.randomUUID().toString()

    enum class State {
        STOPPED,
        STARTING,
        RUNNING,
        FAILED,
    }

    private val stateLock = Any()
    private val serverWorker = Executors.newSingleThreadExecutor { task ->
        Thread(task, "cwm-kotlin-server")
    }

    @Volatile
    var state: State = State.STOPPED
        private set

    @Volatile
    var lastError: String? = null
        private set

    @Volatile
    private var startupStage: String = "尚未启动"

    @Volatile
    private var server: NativeHttpServer? = null

    @Volatile
    private var startGeneration: Long = 0

    @Volatile
    private var startBlocked = false

    /** Permit a later explicit app launch to start the server again. */
    fun prepareForStart() {
        synchronized(stateLock) {
            startBlocked = false
        }
    }

    fun isExplicitlyStopped(): Boolean = startBlocked

    /** Idempotent start: Activity recreation cannot bind port 8080 twice. */
    fun ensureStarted(context: Context) {
        val appContext = context.applicationContext
        val generation: Long
        synchronized(stateLock) {
            if (startBlocked || state == State.STARTING || state == State.RUNNING) return
            state = State.STARTING
            lastError = null
            startupStage = "调度 Kotlin 服务线程"
            generation = ++startGeneration
        }
        serverWorker.execute { startServer(appContext, generation) }
    }

    fun retry(context: Context) {
        prepareForStart()
        ensureStarted(context)
    }

    /** The Activity calls this only after a successful localhost HTTP probe. */
    fun markReady() {
        synchronized(stateLock) {
            if (!startBlocked) {
                if (state == State.STARTING) state = State.RUNNING
                startupStage = "HTTP 探针通过"
            }
        }
    }

    /** Cancel startup and release the currently running local HTTP server. */
    fun stop() {
        val active = synchronized(stateLock) {
            startBlocked = true
            ++startGeneration
            val current = server
            server = null
            state = State.STOPPED
            lastError = null
            startupStage = "已停止"
            current
        }
        try {
            active?.stop()
        } catch (error: Throwable) {
            Log.w(TAG, "Unable to stop the native server", error)
        }
    }

    private fun startServer(context: Context, generation: Long) {
        var candidate: NativeHttpServer? = null
        try {
            reportStage("初始化受限 Root 网关")
            RootImportGateway.initialize(context)
            reportStage("加载并构造 NativeHttpServer")
            val created = NativeHttpServer(context, PORT, nativeToken, ::reportStage)
            candidate = created
            reportStage("绑定 0.0.0.0:$PORT")
            created.start(fi.iki.elonen.NanoHTTPD.SOCKET_READ_TIMEOUT, false)

            val accepted = synchronized(stateLock) {
                if (startBlocked || generation != startGeneration) {
                    false
                } else {
                    server = created
                    true
                }
            }
            if (!accepted) {
                try {
                    created.stop()
                } catch (_: Throwable) {
                    // The explicit exit path is already complete; there is no owner left to notify.
                }
                return
            }

            reportStage("等待 HTTP 探针")
            Log.i(TAG, "Native Kotlin server listening at http://0.0.0.0:$PORT/")
        } catch (error: Throwable) {
            try {
                candidate?.stop()
            } catch (_: Throwable) {
                // Preserve the original startup error.
            }
            val message = describeStartupFailure(startupStage, error)
            var recordFailure = false
            synchronized(stateLock) {
                if (!startBlocked && generation == startGeneration) {
                    server = null
                    lastError = message
                    state = State.FAILED
                    recordFailure = true
                }
            }
            if (recordFailure) {
                Log.e(TAG, "Native server failed during stage: $startupStage", error)
            }
        }
    }

    private fun reportStage(stage: String) {
        startupStage = stage
        Log.i(TAG, "Startup stage: $stage")
    }

    private fun describeStartupFailure(stage: String, error: Throwable): String = buildString {
        append("启动阶段：").append(stage)
        val seen = Collections.newSetFromMap(IdentityHashMap<Throwable, Boolean>())
        var current: Throwable? = error
        var depth = 0
        while (current != null && depth < 8 && seen.add(current)) {
            append('\n')
            if (depth > 0) append("Caused by: ")
            append(current.javaClass.name)
            current.message?.trim()?.takeIf { it.isNotEmpty() }?.let {
                append(": ").append(it.take(1_500))
            }
            val frame = current.stackTrace.firstOrNull {
                it.className.startsWith("com.avalon.cwm")
            } ?: current.stackTrace.firstOrNull()
            if (frame != null) {
                append("\n  at ").append(frame.className).append('.').append(frame.methodName)
                    .append('(').append(frame.fileName ?: "Unknown Source")
                if (frame.lineNumber >= 0) append(':').append(frame.lineNumber)
                append(')')
            }
            current = when (current) {
                is ExceptionInInitializerError -> current.exception ?: current.cause
                else -> current.cause
            }
            depth += 1
        }
    }
}
