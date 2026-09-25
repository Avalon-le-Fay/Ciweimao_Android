package com.avalon.cwm.backend.server

import com.avalon.cwm.backend.core.LibraryBookSummary
import com.avalon.cwm.backend.core.LocalBookSummary
import com.avalon.cwm.backend.core.OutputSummary
import org.json.JSONArray
import org.json.JSONObject

fun localBooksJson(values: Iterable<LocalBookSummary>): JSONArray = JSONArray(
    values.map { value ->
        JSONObject()
            .put("id", value.id)
            .put("name", value.name)
            .put("author", value.author)
            .put("cover", value.cover)
            .put("chapters_cached", value.chaptersCached)
            .put("unknown", value.unknown)
    },
)

fun libraryBooksJson(values: Iterable<LibraryBookSummary>): JSONArray = JSONArray(
    values.map { value ->
        JSONObject()
            .put("id", value.id)
            .put("name", value.name)
            .put("author", value.author)
            .put("cover", value.cover)
    },
)

fun outputsJson(values: Iterable<OutputSummary>): JSONArray = JSONArray(
    values.map { value ->
        JSONObject()
            .put("name", value.name)
            .put("size", value.size)
            .put("type", value.type)
    },
)

fun jsonArrayValues(array: JSONArray?): List<Any?> {
    if (array == null) return emptyList()
    return buildList {
        for (index in 0 until array.length()) add(array.opt(index))
    }
}
