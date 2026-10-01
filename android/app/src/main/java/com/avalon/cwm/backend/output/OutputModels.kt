package com.avalon.cwm.backend.output

import org.json.JSONArray
import org.json.JSONObject
import java.io.File

data class OutputOptions(
    val chapters: Boolean = false,
    val images: Boolean = false,
) {
    val hasOptionalContent: Boolean get() = chapters || images

    fun toJson(): JSONObject = JSONObject()
        .put("chapters", chapters)
        .put("images", images)

    companion object {
        fun from(payload: JSONObject): OutputOptions {
            val source = when (val raw = payload.opt("options")) {
                is JSONObject -> raw
                is String -> runCatching { JSONObject(raw) }.getOrNull()
                else -> null
            } ?: JSONObject()
            return OutputOptions(
                chapters = flag(source.opt("chapters") ?: payload.opt("include_chapters")),
                images = flag(source.opt("images") ?: payload.opt("include_images")),
            )
        }

        private fun flag(value: Any?): Boolean = when (value) {
            is Boolean -> value
            else -> value?.toString()?.trim()?.lowercase().orEmpty() in setOf("1", "true", "yes", "on")
        }
    }
}

data class SelectedOutput(
    val name: String,
    val file: File,
) {
    val stem: String get() = file.nameWithoutExtension
}

data class OptionalOutputPlan(
    val directories: List<String>,
    val entries: List<OptionalOutputEntry>,
    val missing: List<String>,
)

data class OptionalOutputEntry(
    val archivePath: String,
    val source: File,
)

data class PreparedDownload(
    val token: String,
    val file: File,
    val filename: String,
    val mimeType: String,
    val expiresAtMillis: Long,
) {
    val expired: Boolean get() = System.currentTimeMillis() >= expiresAtMillis
}

data class OutputFailure(
    val name: String,
    val error: String,
) {
    fun toJson(): JSONObject = JSONObject().put("name", name).put("error", error)
}

data class OutputExportResult(
    val copied: List<String>,
    val failed: List<String>,
    val failedDetails: List<OutputFailure>,
    val destination: String,
    val options: OutputOptions,
    val optionalCopied: List<String>,
    val optionalFailed: List<String>,
    val optionalMissing: List<String>,
    val autoCleanup: Boolean,
    val cleaned: List<String>,
    val cleanupFailed: List<String>,
    val purged: List<String>,
) {
    fun toJson(): JSONObject = JSONObject()
        .put("ok", true)
        .put("copied", JSONArray(copied))
        .put("failed", JSONArray(failed))
        .put("failed_details", JSONArray(failedDetails.map { it.toJson() }))
        .put("dst", destination)
        .put("options", options.toJson())
        .put("optional_copied", JSONArray(optionalCopied))
        .put("optional_failed", JSONArray(optionalFailed))
        .put("optional_missing", JSONArray(optionalMissing))
        .put("auto_cleanup", autoCleanup)
        .put("cleaned", JSONArray(cleaned))
        .put("cleanup_failed", JSONArray(cleanupFailed))
        .put("purged", JSONArray(purged))
}
