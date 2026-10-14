package com.avalon.cwm.backend.online.latest

import android.os.Build
import java.io.File

/**
 * Decides whether this process may load the official 2.9.365/366 native closure.
 *
 * The closure (libcurl/libssl/libcrypto + libcwmtransport) only exists for the
 * ARM ABIs shipped in jniLibs. On x86/x86_64 hosts — PC emulators such as MuMu,
 * where Android executes ARM libraries through a binary translation bridge
 * (libhoudini / libndk_translation) — loading it has been observed to kill the
 * whole process with an uncatchable native crash before any Java fallback can
 * run. Such environments stay on the OkHttp transport instead: the signed
 * request bytes are transport-independent, so both adapters send the identical
 * request.
 */
internal object OfficialNativeEnvironment {
    /** ABIs whose official libraries are bundled in the APK (jniLibs + cmake). */
    private val SHIPPED_ABIS = listOf("arm64-v8a", "armeabi-v7a")

    /** Host ABIs that can only run shipped ARM libraries through translation. */
    private val TRANSLATION_HOST_ABIS = setOf("x86", "x86_64")

    /** Binary-translation bridges mapped into the process when ARM is emulated. */
    private val TRANSLATION_LIBRARY_MARKERS = listOf("libhoudini", "ndk_translation")

    private const val MAX_MAPS_CHARS = 4 * 1024 * 1024

    /**
     * True only when the device's primary ABI is one of the shipped ARM ABIs
     * and no x86 host ABI is present. A list such as
     * "x86_64,x86,arm64-v8a,armeabi-v7a,armeabi" (MuMu and friends) fails this
     * check even though it advertises arm64-v8a.
     */
    fun abiLooksLikeNativeArm(supportedAbis: List<String>): Boolean {
        val primary = supportedAbis.firstOrNull().orEmpty()
        if (primary !in SHIPPED_ABIS) return false
        return supportedAbis.none { it in TRANSLATION_HOST_ABIS }
    }

    /** True when /proc/self/maps shows a binary translation bridge in this process. */
    fun mapsShowTranslationBridge(maps: String): Boolean =
        TRANSLATION_LIBRARY_MARKERS.any(maps::contains)

    private val cachedReason: String? by lazy { detect() }

    /**
     * null when the official native transport is safe to use on this device;
     * otherwise a short reason meant for logs and the account panel.
     */
    fun unavailableReason(): String? = cachedReason

    private fun detect(): String? {
        val abis = Build.SUPPORTED_ABIS.toList()
        if (!abiLooksLikeNativeArm(abis)) {
            return "模拟器转译环境不支持官方原生传输，已自动降级"
        }
        return if (readProcessMaps()?.let(::mapsShowTranslationBridge) == true) {
            "检测到 ARM 转译层，已自动降级"
        } else {
            null
        }
    }

    private fun readProcessMaps(): String? = try {
        val file = File("/proc/self/maps")
        if (!file.canRead()) {
            null
        } else {
            val text = StringBuilder()
            file.bufferedReader().use { reader ->
                val buffer = CharArray(16 * 1024)
                while (text.length <= MAX_MAPS_CHARS) {
                    val count = reader.read(buffer)
                    if (count <= 0) break
                    text.append(buffer, 0, count)
                }
            }
            text.toString()
        }
    } catch (_: Throwable) {
        // An unreadable maps file is not proof of a translation layer; keep the
        // ABI-based verdict (fail open for genuine ARM devices).
        null
    }
}
