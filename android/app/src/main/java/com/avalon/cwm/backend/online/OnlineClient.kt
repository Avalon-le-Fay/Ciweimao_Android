package com.avalon.cwm.backend.online

import com.avalon.cwm.backend.online.latest.LatestProtocol
import org.json.JSONArray
import org.json.JSONObject

class CiweimaoClient(
    private val account: String,
    private val loginToken: String,
    val config: CiweimaoClientConfig = CiweimaoClientConfig(),
) {
    private val transport = CiweimaoTransport(config)

    @Volatile
    private var discoveredShelfId: String = config.shelfId.trim()

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
            val readerId = config.readerId.trim()
            require(readerId.isNotEmpty()) {
                "2.9.366 的 get_my_info 需要 reader_id"
            }
            parameters["reader_id"] = readerId
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
        if (config.isLatest && discoveredShelfId.isBlank()) {
            for (index in 0 until shelves.length()) {
                val id = shelves.optJSONObject(index)
                    ?.opt("shelf_id")?.toString()?.trim().orEmpty()
                if (id.isNotEmpty()) {
                    discoveredShelfId = id
                    break
                }
            }
        }
        return shelves
    }

    fun discoveredShelf(): String = discoveredShelfId

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

    /** Official multi-chapter subscription request (endpoint id 109). */
    fun buyChapters(
        chapterIds: List<String>,
        isFull: Boolean = false,
        shelfId: String = "",
    ): JSONObject {
        val ids = chapterIds.map(String::trim).filter(String::isNotBlank)
        require(ids.isNotEmpty()) { "购买章节不能为空" }
        val data = linkedMapOf<String, Any?>(
            // ChaptersBuyTask serialises the Java List as a JSON-like numeric array,
            // e.g. [115137222], not as a comma-separated form value.
            "chapter_id_list" to ids.joinToString(", ", prefix = "[", postfix = "]"),
            "is_full" to if (isFull) 1 else 0,
        )
        if (shelfId.isNotBlank()) data["shelf_id"] = shelfId
        // A timeout has an unknown commit state. Never replay a purchase request.
        return call("chapter/buy_multi", data, method = "POST", retries = 1)
    }

    /** Verified task endpoints used by the official client. */
    fun signIn(proof: GeetestProof? = null): JSONObject = try {
        val fields = linkedMapOf<String, Any?>("task_type" to 1)
        proof?.let { fields.putAll(it.fields()) }
        call("reader/get_task_bonus_with_sign_recommend", fields,
            method = "POST", retries = 1)
    } catch (error: CiweimaoApiException) {
        if (error.code != "340001") throw error
        JSONObject().put("code", error.code).put("tip", "今天已签到")
    }

    fun allTaskList(): JSONObject = call("task/get_all_task_list", method = "POST")
        .optJSONObject("data")
        ?: throw CiweimaoException("任务响应缺少 data，未把空数据当成已完成")

    fun dailyTasks(): JSONArray = allTaskList().optJSONArray("daily_task_list")
        ?: throw CiweimaoException("每日任务响应缺少 daily_task_list，未把空数据当成已完成")

    /** Claim a server-confirmed completed challenge task; never fabricates progress. */
    fun getChallengeTaskBonus(taskType: String): JSONObject {
        require(taskType.isNotBlank()) { "挑战任务类型为空" }
        return call("reader/get_challenge_task_bonus", mapOf("task_type" to taskType), method = "POST", retries = 1)
    }

    /** Claim a directly-openable weekly task chest. Ad-gated chests are filtered by the caller. */
    fun getWeekTaskChestBonus(chestType: Int): JSONObject {
        require(chestType in 1..3) { "宝箱类型无效" }
        return call("reader/get_week_task_chest_bonus", mapOf("chest_type" to chestType), method = "POST", retries = 1)
    }

    fun openNoviceTaskChest(chestType: Int): JSONObject {
        require(chestType in 1..3) { "宝箱类型无效" }
        return call("reader/open_novioce_task_chest", mapOf("chest_type" to chestType), method = "POST", retries = 1)
    }

    fun submitReadingProgress(bookId: String, chapterId: String): JSONObject = call(
        "reader/add_readbook",
        mapOf("readTimes" to 1200, "getTime" to java.text.SimpleDateFormat(
            "yyyy-MM-dd HH:mm:ss", java.util.Locale.US,
        ).format(java.util.Date()), "book_id" to bookId, "chapter_id" to chapterId),
        method = "POST",
    )

    fun readAreaProgress(): JSONObject = call(
        "bbs/add_bbs_read_time",
        mapOf("readTimes" to 300, "getTime" to java.text.SimpleDateFormat(
            "yyyy-MM-dd HH:mm:ss", java.util.Locale.US,
        ).format(java.util.Date())),
        method = "POST",
    )

    fun shareIllustrationPost(postId: String): JSONObject = call(
        "bbs/share_bbs", mapOf("bbs_id" to postId), method = "POST",
    )

    /** Returns the raw illustration-area post list; callers filter is_like themselves. */
    fun illustrationPostList(page: Int = 0, count: Int = 10, order: String = "2"): JSONArray =
        call(
            "bbs/get_bbs_list",
            mapOf("bbs_type" to 5, "order" to order, "count" to count, "page" to page),
            method = "POST",
        ).optJSONObject("data")?.optJSONArray("bbs_list") ?: JSONArray()

    /** Real like mutation. It is intentionally one-shot: never replay an uncertain write. */
    fun likeBbs(bbsId: String): JSONObject {
        require(bbsId.isNotBlank()) { "帖子 ID 为空" }
        return call("bbs/like_bbs", mapOf("bbs_id" to bbsId), method = "POST", retries = 1)
    }

    /** Real comment mutation using the official comment fields and one-shot semantics. */
    fun addBbsComment(bbsId: String, content: String): JSONObject {
        require(bbsId.isNotBlank() && content.isNotBlank()) { "评论参数不完整" }
        return call(
            "bbs/add_bbs_comment",
            mapOf(
                "bbs_id" to bbsId,
                "comment_content" to content,
                "is_combo" to 0,
                "combo_type" to 0,
            ),
            method = "POST",
            retries = 1,
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
            method = if (config.isLatest) "POST" else "GET",
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
