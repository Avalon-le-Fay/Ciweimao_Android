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
        retries: Int = config.retries,
    ): JSONObject {
        val parameters = linkedMapOf<String, Any?>()
        parameters.putAll(config.baseParameters())
        parameters["login_token"] = loginToken
        parameters["account"] = account
        parameters.putAll(data)
        if (config.isLatest && path.trimStart('/') == "reader/get_my_info") {
            // 服务端不校验 reader_id：空值/乱填都会返回 token 所属账号的真实资料。
            // 留空时不携带，checkLogin() 会从 reader_info 取回真值再由 activate 落库。
            val readerId = config.readerId.trim()
            if (readerId.isNotEmpty()) parameters["reader_id"] = readerId
        }
        return DownloadRequestVerification.request(account, config, path, parameters) { proofFields ->
            transport.request(
                path = path,
                parameters = LinkedHashMap(parameters).apply { putAll(proofFields) },
                method = if (config.isLatest) "POST" else method,
                checkCode = checkCode,
                retries = if (proofFields.isEmpty()) retries else 1,
            )
        }
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
        val data = response.optJSONObject("data") ?: JSONObject()
        val reader = data.optJSONObject("reader_info") ?: JSONObject()
        val output = JSONObject()
            .put("reader_name", reader.optString("reader_name"))
            .put("reader_id", reader.opt("reader_id") ?: JSONObject.NULL)
            .put(
                "avatar",
                reader.optString("avatar_thumb_url")
                    .ifBlank { reader.optString("avatar_url") },
            )

        var balanceIssue: JSONObject? = null
        if (config.isLatest) {
            val prop = data.optJSONObject("prop_info") ?: try {
                call("reader/get_prop_info")
                    .optJSONObject("data")
                    ?.optJSONObject("prop_info")
            } catch (error: Exception) {
                balanceIssue = AccountOperationError.describe("余额查询", error)
                null
            }
            if (prop != null) {
                output.put("hlb", prop.opt("rest_hlb") ?: JSONObject.NULL)
                output.put("gift_hlb", prop.opt("rest_gift_hlb") ?: JSONObject.NULL)
            }
            // prop_info carries no token expiry. The wallet endpoint does
            // (wallet_info.recent_end_hlb_info.endtime) and the official 366
            // client calls it, so without this read the UI can only guess
            // "永久" from a missing field. A failure here must not invalidate
            // the balance that was already confirmed above.
            try {
                val wallet = call("reader/get_wallet_info")
                    .optJSONObject("data")
                    ?.optJSONObject("wallet_info")
                if (wallet != null) {
                    output.put("recent_end_hlb_info", wallet.opt("recent_end_hlb_info") ?: JSONObject.NULL)
                    output.put("vip_up_info", wallet.opt("vip_up_info") ?: JSONObject.NULL)
                }
            } catch (error: Exception) {
                if (balanceIssue == null) {
                    balanceIssue = AccountOperationError.describe("代币过期查询", error)
                }
            }
        } else {
            try {
                val wallet = call("reader/get_wallet_info")
                    .getJSONObject("data")
                    .getJSONObject("wallet_info")
                output.put("hlb", wallet.opt("rest_hlb") ?: JSONObject.NULL)
                output.put("gift_hlb", wallet.opt("rest_gift_hlb") ?: JSONObject.NULL)
                output.put("recent_end_hlb_info", wallet.opt("recent_end_hlb_info") ?: JSONObject.NULL)
                output.put("vip_up_info", wallet.opt("vip_up_info") ?: JSONObject.NULL)
            } catch (error: Exception) {
                balanceIssue = AccountOperationError.describe("余额查询", error)
            }
        }
        return ChapterWallet.fromRaw(output.opt("hlb"), output.opt("gift_hlb"))
            .writeTo(output).apply {
                balanceIssue?.let { put("balance_issue", it).put("balance_error", it.getString("message")) }
            }
    }

    fun getShelfList(): JSONArray {
        val response = call(
            "bookshelf/get_shelf_list",
            method = if (config.isLatest) "POST" else "GET",
        )
        val shelves = response.optJSONObject("data")
            ?.optJSONArray("shelf_list") ?: JSONArray()
        return shelves
    }

    fun getShelfBooks(shelfId: String): JSONArray {
        require(shelfId.isNotBlank()) { "shelf_id 不能为空" }
        val source = if (config.isLatest) {
            val output = JSONArray()
            val pageSize = 100
            var page = 0
            while (true) {
                val pageItems = call(
                    "bookshelf/get_shelf_book_list_new",
                    mapOf(
                        "count" to pageSize,
                        "order" to "last_read_time",
                        "page" to page,
                        "shelf_id" to shelfId,
                    ),
                    method = "POST",
                ).getJSONObject("data").optJSONArray("book_list") ?: JSONArray()
                for (index in 0 until pageItems.length()) {
                    val item = pageItems.opt(index)
                    if (item != null) output.put(item)
                }
                if (pageItems.length() < pageSize) break
                page += 1
                require(page < 20) { "书架分页超过 20 页安全上限" }
            }
            output
        } else {
            call(
                "bookshelf/get_shelf_book_list",
                mapOf(
                    "shelf_id" to shelfId,
                    "last_mod_time" to "0",
                    "direction" to "prev",
                ),
                method = "POST",
            ).getJSONObject("data").optJSONArray("book_list") ?: JSONArray()
        }

        val output = JSONArray()
        val seen = linkedSetOf<String>()
        for (index in 0 until source.length()) {
            val item = source.optJSONObject(index) ?: continue
            val book = item.optJSONObject("book_info") ?: item
            val bookId = book.opt("book_id")?.toString().orEmpty()
            if (bookId.isEmpty() || !seen.add(bookId)) continue
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
            val shelfId = shelves.optJSONObject(index)
                ?.opt("shelf_id")?.toString()?.trim().orEmpty()
            if (shelfId.isEmpty()) continue
            val books = getShelfBooks(shelfId)
            for (bookIndex in 0 until books.length()) output.put(books.get(bookIndex))
        }
        return output
    }

    fun getBookInfo(bookId: String): JSONObject {
        require(bookId.isNotBlank()) { "book_id 不能为空" }
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

    /** Official purchase-page price/permission list (endpoint id 47). */
    fun getChapterPermissionList(bookId: String): JSONArray {
        require(bookId.isNotBlank()) { "book_id 不能为空" }
        return call(
            "chapter/get_chapter_permission_list",
            mapOf("book_id" to bookId),
            method = "POST",
        ).optJSONObject("data")
            ?.optJSONArray("chapter_permission_list") ?: JSONArray()
    }
}
