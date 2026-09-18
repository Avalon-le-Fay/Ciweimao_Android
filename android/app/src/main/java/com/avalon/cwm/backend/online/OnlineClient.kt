package com.avalon.cwm.backend.online

import org.json.JSONArray
import org.json.JSONObject

class CiweimaoClient(
    private val account: String,
    private val loginToken: String,
    val config: CiweimaoClientConfig = CiweimaoClientConfig(),
) {
    private val transport = CiweimaoTransport(config)

    internal fun call(
        path: String,
        data: Map<String, Any?> = emptyMap(),
        method: String = "GET",
        checkCode: Boolean = true,
    ): JSONObject {
        val parameters = linkedMapOf<String, Any?>()
        parameters.putAll(config.baseParameters())
        parameters["login_token"] = loginToken
        parameters["account"] = account
        parameters.putAll(data)
        return transport.request(
            path,
            parameters,
            method = method,
            checkCode = checkCode,
        )
    }

    fun checkLogin(): JSONObject {
        val response = try {
            call("reader/get_my_info")
        } catch (error: CiweimaoAuthException) {
            throw CiweimaoAuthException(
                error.code,
                "登录态已失效，请重新登录",
                error.apiPath,
            )
        }
        val reader = response.getJSONObject("data").getJSONObject("reader_info")
        val output = JSONObject()
            .put("reader_name", reader.optString("reader_name"))
            .put("reader_id", reader.opt("reader_id") ?: JSONObject.NULL)
            .put(
                "avatar",
                reader.optString("avatar_thumb_url")
                    .ifBlank { reader.optString("avatar_url") },
            )
        runCatching {
            val wallet = call("reader/get_wallet_info")
                .getJSONObject("data")
                .getJSONObject("wallet_info")
            output.put("hlb", wallet.opt("rest_hlb") ?: JSONObject.NULL)
            output.put("gift_hlb", wallet.opt("rest_gift_hlb") ?: JSONObject.NULL)
        }
        return output
    }

    fun getShelfList(): JSONArray = call("bookshelf/get_shelf_list")
        .getJSONObject("data")
        .optJSONArray("shelf_list") ?: JSONArray()

    fun getShelfBooks(shelfId: String): JSONArray {
        val source = call(
            "bookshelf/get_shelf_book_list",
            mapOf(
                "shelf_id" to shelfId,
                "last_mod_time" to "0",
                "direction" to "prev",
            ),
            method = "POST",
        ).getJSONObject("data").optJSONArray("book_list") ?: JSONArray()
        val output = JSONArray()
        for (index in 0 until source.length()) {
            val book = source.optJSONObject(index)?.optJSONObject("book_info") ?: continue
            output.put(
                JSONObject()
                    .put("book_id", book.opt("book_id") ?: JSONObject.NULL)
                    .put("book_name", book.optString("book_name"))
                    .put("author_name", book.optString("author_name"))
                    .put("cover", book.optString("cover"))
                    .put("total_word_count", book.opt("total_word_count") ?: ""),
            )
        }
        return output
    }

    fun getAllShelfBooks(): JSONArray {
        val output = JSONArray()
        val shelves = getShelfList()
        for (index in 0 until shelves.length()) {
            val shelfId = shelves.optJSONObject(index)?.opt("shelf_id")?.toString() ?: continue
            val books = getShelfBooks(shelfId)
            for (bookIndex in 0 until books.length()) output.put(books.get(bookIndex))
        }
        return output
    }

    fun getBookInfo(bookId: String): JSONObject {
        val response = call(
            "book/get_info_by_id",
            mapOf(
                "book_id" to bookId,
                "recommend" to "",
                "carousel_position" to "",
                "tab_type" to "",
                "module_id" to "",
            ),
            method = "POST",
        )
        return response.optJSONObject("data")?.optJSONObject("book_info")
            ?: throw CiweimaoApiException(
                response.opt("code")?.toString().orEmpty(),
                "书籍信息为空（可能已下架或书号错误）",
                "book/get_info_by_id",
            )
    }

    fun searchBooks(keyword: String, page: Int = 0, count: Int = 15): JSONArray {
        val source = call(
            "bookcity/get_filter_search_book_list",
            mapOf(
                "count" to count,
                "page" to page,
                "use_daguan" to 1,
                "category_index" to 0,
                "key" to keyword,
            ),
        ).optJSONObject("data")?.optJSONArray("book_list") ?: JSONArray()
        val output = JSONArray()
        for (index in 0 until source.length()) {
            val book = source.optJSONObject(index) ?: continue
            output.put(
                JSONObject()
                    .put("book_id", book.opt("book_id") ?: JSONObject.NULL)
                    .put("book_name", book.optString("book_name"))
                    .put("author_name", book.optString("author_name"))
                    .put("cover", book.optString("cover"))
                    .put("total_word_count", book.opt("total_word_count") ?: ""),
            )
        }
        return output
    }
}
