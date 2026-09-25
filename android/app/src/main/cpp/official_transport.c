#include <jni.h>
#include <dlfcn.h>
#include <pthread.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>

#define CWM_CURLE_OK 0
#define CWM_CURL_GLOBAL_ALL 3L
#define CWM_CURL_HTTP_VERSION_1_1 2L
#define CWM_CURL_IPRESOLVE_V4 1L
#define CWM_CURLINFO_RESPONSE_CODE 0x200002
#define CWM_CURLOPT_WRITEDATA 10001
#define CWM_CURLOPT_URL 10002
#define CWM_CURLOPT_PROXY 10004
#define CWM_CURLOPT_POSTFIELDS 10015
#define CWM_CURLOPT_LOW_SPEED_LIMIT 19
#define CWM_CURLOPT_LOW_SPEED_TIME 20
#define CWM_CURLOPT_HTTPHEADER 10023
#define CWM_CURLOPT_HEADER 42
#define CWM_CURLOPT_POST 47
#define CWM_CURLOPT_POSTFIELDSIZE 60
#define CWM_CURLOPT_SSL_VERIFYPEER 64
#define CWM_CURLOPT_SSL_VERIFYHOST 81
#define CWM_CURLOPT_HTTP_VERSION 84
#define CWM_CURLOPT_NOSIGNAL 99
#define CWM_CURLOPT_CAINFO 10065
#define CWM_CURLOPT_IPRESOLVE 113
#define CWM_CURLOPT_TIMEOUT_MS 155
#define CWM_CURLOPT_CONNECTTIMEOUT_MS 156
#define CWM_CURLOPT_WRITEFUNCTION 20011

typedef void CwmCurl;
typedef int CwmCurlCode;
struct CwmCurlList;

typedef CwmCurlCode (*CwmGlobalInit)(long);
typedef CwmCurl *(*CwmEasyInit)(void);
typedef CwmCurlCode (*CwmEasySetopt)(CwmCurl *, int, ...);
typedef CwmCurlCode (*CwmEasyPerform)(CwmCurl *);
typedef CwmCurlCode (*CwmEasyGetinfo)(CwmCurl *, int, ...);
typedef const char *(*CwmEasyStrerror)(CwmCurlCode);
typedef void (*CwmEasyCleanup)(CwmCurl *);
typedef struct CwmCurlList *(*CwmListAppend)(struct CwmCurlList *, const char *);
typedef void (*CwmListFreeAll)(struct CwmCurlList *);

struct CwmCurlApi {
    void *handle;
    CwmGlobalInit global_init;
    CwmEasyInit easy_init;
    CwmEasySetopt easy_setopt;
    CwmEasyPerform easy_perform;
    CwmEasyGetinfo easy_getinfo;
    CwmEasyStrerror easy_strerror;
    CwmEasyCleanup easy_cleanup;
    CwmListAppend list_append;
    CwmListFreeAll list_free_all;
};

struct CwmResponse {
    unsigned char *data;
    size_t size;
    size_t capacity;
    size_t maximum;
    int rejected;
};

static struct CwmCurlApi g_curl;
static pthread_once_t g_curl_once = PTHREAD_ONCE_INIT;
static int g_curl_ready;

static void cwm_load_curl(void) {
    memset(&g_curl, 0, sizeof(g_curl));
    g_curl.handle = dlopen("libcurl.so", RTLD_NOW | RTLD_LOCAL);
    if (g_curl.handle == NULL) return;
#define CWM_LOAD(member, symbol)                                                   \
    do {                                                                           \
        *(void **)(&g_curl.member) = dlsym(g_curl.handle, symbol);                 \
        if (g_curl.member == NULL) return;                                          \
    } while (0)
    CWM_LOAD(global_init, "curl_global_init");
    CWM_LOAD(easy_init, "curl_easy_init");
    CWM_LOAD(easy_setopt, "curl_easy_setopt");
    CWM_LOAD(easy_perform, "curl_easy_perform");
    CWM_LOAD(easy_getinfo, "curl_easy_getinfo");
    CWM_LOAD(easy_strerror, "curl_easy_strerror");
    CWM_LOAD(easy_cleanup, "curl_easy_cleanup");
    CWM_LOAD(list_append, "curl_slist_append");
    CWM_LOAD(list_free_all, "curl_slist_free_all");
#undef CWM_LOAD
    if (g_curl.global_init(CWM_CURL_GLOBAL_ALL) != CWM_CURLE_OK) return;
    g_curl_ready = 1;
}

static void cwm_throw_io(JNIEnv *env, const char *message) {
    jclass type = (*env)->FindClass(env, "java/io/IOException");
    if (type != NULL) (*env)->ThrowNew(env, type, message);
}

static void cwm_throw_curl(JNIEnv *env, const char *operation, CwmCurlCode code) {
    char message[256];
    const char *detail = g_curl.easy_strerror(code);
    snprintf(message, sizeof(message), "%s (curl=%d: %s)",
             operation, code, detail == NULL ? "unknown" : detail);
    cwm_throw_io(env, message);
}

static int cwm_valid_path(const char *path) {
    if (path == NULL || path[0] != '/' || path[1] == '\0') return 0;
    const size_t length = strlen(path);
    if (length > 128) return 0;
    for (size_t index = 0; index < length; ++index) {
        const unsigned char value = (unsigned char)path[index];
        if ((value >= 'a' && value <= 'z') ||
            (value >= 'A' && value <= 'Z') ||
            (value >= '0' && value <= '9') ||
            value == '/' || value == '_' || value == '-' || value == '.') {
            continue;
        }
        return 0;
    }
    return 1;
}

static int cwm_allowed_path(const char *path) {
    static const char *allowed[] = {
        "/signup/auto_reg_v2", "/signup/use_geetest",
        "/signup/send_verify_code", "/signup/modify_passwd",
        "/signup/login", "/signup/login_v2",
        "/reader/get_my_info",
        "/reader/get_prop_info", "/reader/get_wallet_info",
        "/reader/add_readbook",
        "/reader/get_task_bonus_with_sign_recommend",
        "/reader/get_challenge_task_bonus",
        "/reader/get_week_task_chest_bonus",
        "/reader/open_novioce_task_chest",
        "/bbs/add_bbs_read_time", "/bbs/share_bbs",
        "/bbs/get_bbs_list", "/bbs/like_bbs", "/bbs/unlike_bbs",
        "/bbs/add_bbs_comment",
        "/meta/get_meta_data",
        "/setting/get_startpage_url_list", "/setting/get_check",
        "/setting/thired_party_switch", "/setting/get_mobile_area",
        "/setting/get_version",
        "/bookshelf/get_shelf_list", "/bookshelf/get_shelf_book_list_new",
        "/book/get_info_by_id", "/bookcity/get_filter_search_book_list",
        "/bookcity/get_index_list", "/task/get_sign_record",
        "/task/get_all_task_list",
        "/chapter/get_updated_chapter_by_division_new",
        "/chapter/get_chapter_permission_list", "/chapter/buy_multi",
        "/chapter/get_chapter_download_cmd", "/chapter/download_cpt",
        "/chapter/check_download_cpt",
    };
    const size_t count = sizeof(allowed) / sizeof(allowed[0]);
    for (size_t index = 0; index < count; ++index) {
        if (strcmp(path, allowed[index]) == 0) return 1;
    }
    return 0;
}

static size_t cwm_write_response(char *source, size_t size, size_t count, void *userdata) {
    struct CwmResponse *response = (struct CwmResponse *)userdata;
    if (size != 0 && count > SIZE_MAX / size) {
        response->rejected = 1;
        return 0;
    }
    const size_t bytes = size * count;
    if (response->size > response->maximum || bytes > response->maximum - response->size) {
        response->rejected = 1;
        return 0;
    }
    const size_t required = response->size + bytes;
    if (required > response->capacity) {
        size_t capacity = response->capacity == 0 ? 16384 : response->capacity;
        while (capacity < required) {
            if (capacity > response->maximum / 2) {
                capacity = response->maximum;
                break;
            }
            capacity *= 2;
        }
        unsigned char *expanded = (unsigned char *)realloc(response->data, capacity);
        if (expanded == NULL) {
            response->rejected = 1;
            return 0;
        }
        response->data = expanded;
        response->capacity = capacity;
    }
    memcpy(response->data + response->size, source, bytes);
    response->size += bytes;
    return bytes;
}

static int cwm_append_header(struct CwmCurlList **headers, const char *value) {
    struct CwmCurlList *updated = g_curl.list_append(*headers, value);
    if (updated == NULL) return 0;
    *headers = updated;
    return 1;
}

JNIEXPORT jbyteArray JNICALL
Java_com_avalon_cwm_backend_online_latest_OfficialNativeTransport_nativePost(
    JNIEnv *env,
    jobject receiver,
    jstring path_value,
    jstring user_agent_value,
    jstring ca_bundle_value,
    jbyteArray body_value,
    jint connect_timeout_ms,
    jint request_timeout_ms,
    jint maximum_response_bytes) {
    (void)receiver;
    const char *path = NULL;
    const char *user_agent = NULL;
    const char *ca_bundle = NULL;
    unsigned char *body = NULL;
    char *url = NULL;
    char *user_agent_header = NULL;
    CwmCurl *curl = NULL;
    struct CwmCurlList *headers = NULL;
    struct CwmResponse response = {0};
    jbyteArray envelope = NULL;

    pthread_once(&g_curl_once, cwm_load_curl);
    if (!g_curl_ready) {
        cwm_throw_io(env, "official libcurl initialization failed");
        return NULL;
    }
    if (path_value == NULL || user_agent_value == NULL || ca_bundle_value == NULL ||
        body_value == NULL || connect_timeout_ms <= 0 || request_timeout_ms <= 0 ||
        maximum_response_bytes <= 0) {
        cwm_throw_io(env, "invalid native transport arguments");
        return NULL;
    }

    path = (*env)->GetStringUTFChars(env, path_value, NULL);
    user_agent = (*env)->GetStringUTFChars(env, user_agent_value, NULL);
    ca_bundle = (*env)->GetStringUTFChars(env, ca_bundle_value, NULL);
    if (path == NULL || user_agent == NULL || ca_bundle == NULL) goto cleanup;
    if (!cwm_valid_path(path) || !cwm_allowed_path(path)) {
        cwm_throw_io(env, "API path rejected");
        goto cleanup;
    }

    const jsize body_size = (*env)->GetArrayLength(env, body_value);
    if (body_size <= 0 || body_size > 8 * 1024 * 1024) {
        cwm_throw_io(env, "invalid API request body length");
        goto cleanup;
    }
    body = (unsigned char *)malloc((size_t)body_size + 1);
    if (body == NULL) {
        cwm_throw_io(env, "native request allocation failed");
        goto cleanup;
    }
    (*env)->GetByteArrayRegion(env, body_value, 0, body_size, (jbyte *)body);
    if ((*env)->ExceptionCheck(env)) goto cleanup;
    body[body_size] = '\0';

    static const char host[] = "https://app1.happybooker.cn";
    const size_t url_size = sizeof(host) - 1 + strlen(path) + 1;
    url = (char *)malloc(url_size);
    if (url == NULL) {
        cwm_throw_io(env, "native URL allocation failed");
        goto cleanup;
    }
    memcpy(url, host, sizeof(host) - 1);
    strcpy(url + sizeof(host) - 1, path);

    static const char user_agent_prefix[] = "User-Agent: ";
    const size_t user_agent_size = sizeof(user_agent_prefix) - 1 + strlen(user_agent) + 1;
    user_agent_header = (char *)malloc(user_agent_size);
    if (user_agent_header == NULL) {
        cwm_throw_io(env, "native header allocation failed");
        goto cleanup;
    }
    memcpy(user_agent_header, user_agent_prefix, sizeof(user_agent_prefix) - 1);
    strcpy(user_agent_header + sizeof(user_agent_prefix) - 1, user_agent);

    curl = g_curl.easy_init();
    if (curl == NULL ||
        !cwm_append_header(&headers, "Content-Type: application/x-www-form-urlencoded") ||
        !cwm_append_header(&headers, "charsets: utf-8") ||
        !cwm_append_header(&headers, "Expect: ") ||
        !cwm_append_header(&headers, user_agent_header)) {
        cwm_throw_io(env, "native HTTP initialization failed");
        goto cleanup;
    }
    response.maximum = (size_t)maximum_response_bytes;

#define CWM_SETOPT(option, value)                                                \
    do {                                                                         \
        const CwmCurlCode option_result = g_curl.easy_setopt(curl, option, value); \
        if (option_result != CWM_CURLE_OK) {                                      \
            cwm_throw_curl(env, "native HTTP option failed: " #option, option_result); \
            goto cleanup;                                                        \
        }                                                                        \
    } while (0)
    CWM_SETOPT(CWM_CURLOPT_URL, url);
    CWM_SETOPT(CWM_CURLOPT_IPRESOLVE, CWM_CURL_IPRESOLVE_V4);
    /* This bundled libcurl has no HTTP/2 support; requesting it fails setopt. */
    CWM_SETOPT(CWM_CURLOPT_HTTP_VERSION, CWM_CURL_HTTP_VERSION_1_1);
    CWM_SETOPT(CWM_CURLOPT_HTTPHEADER, headers);
    CWM_SETOPT(CWM_CURLOPT_POST, 1L);
    CWM_SETOPT(CWM_CURLOPT_HEADER, 0L);
    CWM_SETOPT(CWM_CURLOPT_SSL_VERIFYHOST, 2L);
    CWM_SETOPT(CWM_CURLOPT_SSL_VERIFYPEER, 1L);
    CWM_SETOPT(CWM_CURLOPT_CAINFO, ca_bundle);
    CWM_SETOPT(CWM_CURLOPT_WRITEFUNCTION, cwm_write_response);
    CWM_SETOPT(CWM_CURLOPT_WRITEDATA, &response);
    CWM_SETOPT(CWM_CURLOPT_POSTFIELDS, body);
    CWM_SETOPT(CWM_CURLOPT_POSTFIELDSIZE, (long)body_size);
    CWM_SETOPT(CWM_CURLOPT_LOW_SPEED_LIMIT, 50L);
    CWM_SETOPT(CWM_CURLOPT_LOW_SPEED_TIME, 10L);
    CWM_SETOPT(CWM_CURLOPT_NOSIGNAL, 1L);
    CWM_SETOPT(CWM_CURLOPT_PROXY, "");
    CWM_SETOPT(CWM_CURLOPT_CONNECTTIMEOUT_MS, (long)connect_timeout_ms);
    CWM_SETOPT(CWM_CURLOPT_TIMEOUT_MS, (long)request_timeout_ms);
#undef CWM_SETOPT

    {
        const CwmCurlCode result = g_curl.easy_perform(curl);
        long http_status = 0;
        if (response.rejected) {
            cwm_throw_io(env, "official API response rejected locally");
            goto cleanup;
        }
        if (result != CWM_CURLE_OK) {
            cwm_throw_curl(env, "official libcurl request failed", result);
            goto cleanup;
        }
        const CwmCurlCode info_result =
            g_curl.easy_getinfo(curl, CWM_CURLINFO_RESPONSE_CODE, &http_status);
        if (info_result != CWM_CURLE_OK) {
            cwm_throw_curl(env, "official HTTP status query failed", info_result);
            goto cleanup;
        }
        if (http_status < 100 || http_status > 599 || response.size > (size_t)0x7fffffff - 4) {
            cwm_throw_io(env, "official HTTP response envelope invalid");
            goto cleanup;
        }
        envelope = (*env)->NewByteArray(env, (jsize)response.size + 4);
        if (envelope == NULL) goto cleanup;
        const unsigned char status_bytes[4] = {
            (unsigned char)((http_status >> 24) & 0xff),
            (unsigned char)((http_status >> 16) & 0xff),
            (unsigned char)((http_status >> 8) & 0xff),
            (unsigned char)(http_status & 0xff),
        };
        (*env)->SetByteArrayRegion(env, envelope, 0, 4, (const jbyte *)status_bytes);
        if (response.size > 0) {
            (*env)->SetByteArrayRegion(
                env, envelope, 4, (jsize)response.size, (const jbyte *)response.data);
        }
        if ((*env)->ExceptionCheck(env)) envelope = NULL;
    }

cleanup:
    if (headers != NULL) g_curl.list_free_all(headers);
    if (curl != NULL) g_curl.easy_cleanup(curl);
    free(response.data);
    free(user_agent_header);
    free(url);
    free(body);
    if (path != NULL) (*env)->ReleaseStringUTFChars(env, path_value, path);
    if (user_agent != NULL) (*env)->ReleaseStringUTFChars(env, user_agent_value, user_agent);
    if (ca_bundle != NULL) (*env)->ReleaseStringUTFChars(env, ca_bundle_value, ca_bundle);
    return envelope;
}
