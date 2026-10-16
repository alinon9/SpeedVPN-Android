#!/usr/bin/env python3
"""Apply per-application UID-based packet blocking to the pinned hev-socks5-tunnel checkout."""

from pathlib import Path
import sys

BEGIN = "/* SPEEDVPN_APP_BLOCK_BEGIN */"
END = "/* SPEEDVPN_APP_BLOCK_END */"


def replace_once(path: Path, old: str, new: str) -> None:
    text = path.read_text()
    if new in text:
        return
    count = text.count(old)
    if count != 1:
        raise SystemExit(f"Refusing unsafe patch: expected one anchor in {path}, found {count}")
    path.write_text(text.replace(old, new, 1))


def replace_text_once(text: str, old: str, new: str, label: str) -> str:
    """Apply a unique in-memory replacement, including on idempotent reruns."""
    if new in text:
        return text
    count = text.count(old)
    if count != 1:
        raise SystemExit(f"Refusing unsafe patch: expected one {label} anchor, found {count}")
    return text.replace(old, new, 1)


def main() -> None:
    if len(sys.argv) != 2:
        raise SystemExit("usage: patch_hev_uid_blocking.py <hev-socks5-tunnel-dir>")
    root = Path(sys.argv[1]).resolve()
    tunnel_c = root / "src/hev-socks5-tunnel.c"
    jni_c = root / "src/hev-jni.c"
    for required in (tunnel_c, jni_c):
        if not required.is_file():
            raise SystemExit(f"Missing pinned hev source file: {required}")

    # The injected signal handler uses Android log + dlsym; link these libraries explicitly.
    android_mk = root / "Android.mk"
    if not android_mk.is_file():
        raise SystemExit(f"Missing native Android.mk: {android_mk}")
    android_text = android_mk.read_text()
    link_flags = "LOCAL_LDLIBS += -llog"
    if link_flags not in android_text:
        source_anchor = "LOCAL_SRC_FILES := $(HEV_SOCKS5_TUNNEL_SRC)\n"
        count = android_text.count(source_anchor)
        if count < 1:
            raise SystemExit("Refusing unsafe native build patch: LOCAL_SRC_FILES anchor missing")
        android_text = android_text.replace(source_anchor, source_anchor + link_flags + "\n")
        android_mk.write_text(android_text)

    parser = r'''
/* SPEEDVPN_APP_BLOCK_BEGIN */
#ifdef __ANDROID__
extern int hev_jni_app_uid_block_packet (int protocol,
                                    const unsigned char *src, int src_len,
                                    int src_port,
                                    const unsigned char *dst, int dst_len,
                                    int dst_port);

/* Parse outbound IP packets before lwIP. A cached BLOCK decision returns 1 so
 * the caller frees the pbuf instead of handing the packet to netif->input(). */
static int
app_uid_block_report_packet (const struct pbuf *buf)
{
    unsigned char ip[60], l4[20], src[16], dst[16];
    unsigned int total, offset, ip_header_len, src_len, need;
    unsigned int protocol, src_port, dst_port, version;

    if (!buf)
        return 0;
    total = buf->tot_len;
    if (total < 20 || pbuf_copy_partial (buf, ip, 1, 0) != 1)
        return 0;
    version = ip[0] >> 4;

    if (version == 4) {
        unsigned int fragment_field;
        if (total < 20 || pbuf_copy_partial (buf, ip, 20, 0) != 20)
            return 0;
        ip_header_len = (ip[0] & 0x0f) * 4;
        if (ip_header_len < 20 || ip_header_len > sizeof (ip) ||
            total < ip_header_len + 8 ||
            pbuf_copy_partial (buf, ip, ip_header_len, 0) != ip_header_len)
            return 0;
        fragment_field = ((unsigned int) ip[6] << 8) | ip[7];
        /* Non-initial IPv4 fragments do not contain the TCP/UDP ports. */
        if (fragment_field & 0x1fff)
            return 0;
        protocol = ip[9];
        src_len = 4;
        offset = ip_header_len;
        memcpy (src, ip + 12, 4);
        memcpy (dst, ip + 16, 4);
    } else if (version == 6) {
        unsigned int extensions;
        if (total < 40 || pbuf_copy_partial (buf, ip, 40, 0) != 40)
            return 0;
        protocol = ip[6];
        src_len = 16;
        offset = 40;
        memcpy (src, ip + 8, 16);
        memcpy (dst, ip + 24, 16);

        /* Handle common IPv6 extension headers, including Fragment and AH. */
        for (extensions = 0; extensions < 8; extensions++) {
            unsigned int next_header, extension_length;
            if (protocol == 0 || protocol == 43 || protocol == 60 ||
                protocol == 135 || protocol == 139 || protocol == 140) {
                if (total < offset + 2 ||
                    pbuf_copy_partial (buf, ip, 2, offset) != 2)
                    return 0;
                next_header = ip[0];
                extension_length = ((unsigned int) ip[1] + 1u) * 8u;
            } else if (protocol == 44) {
                unsigned int fragment_offset;
                if (total < offset + 8 ||
                    pbuf_copy_partial (buf, ip, 8, offset) != 8)
                    return 0;
                next_header = ip[0];
                fragment_offset = (((unsigned int) ip[2] << 8) | ip[3]) & 0xfff8u;
                /* Non-initial IPv6 fragments do not contain the TCP/UDP ports. */
                if (fragment_offset != 0)
                    return 0;
                extension_length = 8;
            } else if (protocol == 51) {
                if (total < offset + 2 ||
                    pbuf_copy_partial (buf, ip, 2, offset) != 2)
                    return 0;
                next_header = ip[0];
                extension_length = ((unsigned int) ip[1] + 2u) * 4u;
            } else {
                break;
            }

            if (extension_length == 0 ||
                extension_length > total - offset)
                return 0;
            protocol = next_header;
            offset += extension_length;
        }
    } else {
        return 0;
    }

    if (protocol != 6 && protocol != 17)
        return 0;
    need = protocol == 6 ? 20 : 8;
    if (total < offset + need ||
        pbuf_copy_partial (buf, l4, need, offset) != need)
        return 0;

    src_port = ((unsigned int) l4[0] << 8) | l4[1];
    dst_port = ((unsigned int) l4[2] << 8) | l4[3];
    /* No port allow-list: block all TCP/UDP destinations for a matched UID. */
    return hev_jni_app_uid_block_packet (protocol, src, src_len, src_port,
                                   dst, src_len, dst_port);
}
 /* SPEEDVPN_APP_BLOCK_END */
#else
#define app_uid_block_report_packet(buf) 0
#endif

static int
task_io_yielder (HevTaskYieldType type, void *data)
'''
    replace_once(
        tunnel_c,
        "static int\ntask_io_yielder (HevTaskYieldType type, void *data)\n",
        parser,
    )
    replace_once(
        tunnel_c,
        "        if (!buf)\n            continue;\n\n        stat_tx_packets++;",
        "        if (!buf)\n            continue;\n\n        if (app_uid_block_report_packet (buf)) {\n            pbuf_free (buf);\n            continue;\n        }\n\n        stat_tx_packets++;",
    )

    # The packet path performs a native cache lookup and queue operation only.
    # Binder/JNI work is performed by a dedicated 1 MiB pthread in hev-jni.c.

    # JNI bridge: callback invocations are rate-limited and moved off the TUN thread in Kotlin.
    jni_text = jni_c.read_text()

    # This native bridge owns a duplicated TUN fd and writes Android log messages.
    # Keep these headers present even when the patch is rerun on an already-patched tree.
    required_includes = ("#include <unistd.h>", "#include <android/log.h>")
    missing_includes = [include for include in required_includes if include not in jni_text]
    if missing_includes:
        include_anchor = "static pthread_key_t current_jni_env;\\n"
        if jni_text.count(include_anchor) != 1:
            raise SystemExit("Refusing unsafe patch: native JNI include anchor is not unique")
        include_block = "".join(f"{include}\\n" for include in missing_includes)
        jni_text = jni_text.replace(include_anchor, include_block + include_anchor, 1)

    if BEGIN not in jni_text:
        jni_text = jni_text.replace(
            "static pthread_key_t current_jni_env;\n",
            "static pthread_key_t current_jni_env;\n"
            + BEGIN + "\n"
            + "#define APP_BLOCK_FLOW_TABLE_SLOTS 8192u\n"
            + "#define APP_BLOCK_MAX_FLOWS 4096u\n"
            + "#define APP_BLOCK_QUEUE_CAPACITY 256u\n"
            + "#define APP_BLOCK_UID_PENDING (-2)\n"
            + "#define APP_BLOCK_ACTION_ALLOW 0\n"
            + "#define APP_BLOCK_ACTION_BLOCK 1\n"
            + "#define APP_BLOCK_FLOW_IDLE_MS 30000ULL\n"
            + "#define APP_BLOCK_NEGATIVE_CACHE_MS 750ULL\n"
            + "#define APP_BLOCK_PENDING_TIMEOUT_MS 10000ULL\n"
            + "typedef struct { int protocol; int addr_len; unsigned char a[16]; unsigned char b[16]; unsigned short port_a; unsigned short port_b; } app_uid_block_flow_key;\n"
            + "typedef struct { int state; app_uid_block_flow_key key; int uid; int action; int drop_logged; unsigned long long updated_ms; unsigned long long request_id; } app_uid_block_cache_entry;\n"
            + "typedef struct { app_uid_block_flow_key key; int protocol; int src_len; unsigned char src[16]; int src_port; int dst_len; unsigned char dst[16]; int dst_port; unsigned long long request_id; } app_uid_block_request;\n"
            + "static pthread_mutex_t app_uid_block_mutex = PTHREAD_MUTEX_INITIALIZER;\n"
            + "static pthread_cond_t app_uid_block_condition = PTHREAD_COND_INITIALIZER;\n"
            + "static jobject app_uid_block_callback = NULL;\n"
            + "static jmethodID app_uid_block_method = NULL;\n"
            + "static app_uid_block_cache_entry app_uid_block_cache[APP_BLOCK_FLOW_TABLE_SLOTS];\n"
            + "static app_uid_block_request app_uid_block_queue[APP_BLOCK_QUEUE_CAPACITY];\n"
            + "static unsigned int app_uid_block_queue_head = 0, app_uid_block_queue_tail = 0, app_uid_block_queue_count = 0;\n"
            + "static unsigned int app_uid_block_active_flows = 0;\n"
            + "static pthread_t app_uid_block_worker_thread;\n"
            + "static int app_uid_block_worker_started = 0, app_uid_block_worker_ready = 0, app_uid_block_worker_stop_requested = 0;\n"
            + "static volatile unsigned long long app_uid_block_next_request_id = 0;\n"
            + END + "\n",
            1,
        )
        jni_text = jni_text.replace(
            "static jlongArray native_get_stats (JNIEnv *env, jobject thiz);\n",
            "static jlongArray native_get_stats (JNIEnv *env, jobject thiz);\n"
            "static jboolean native_set_app_uid_block_callback (JNIEnv *env, jobject thiz, jobject callback);\n",
            1,
        )
        anchor = '    { "TProxyGetStats", "()[J", (void *)native_get_stats },\n'
        if jni_text.count(anchor) != 1:
            raise SystemExit("Refusing unsafe patch: JNI method table anchor is not unique")
        jni_text = jni_text.replace(
            anchor,
            anchor + '    { "TProxySetAppUidBlockCallback", "(Ljava/lang/Object;)Z", (void *)native_set_app_uid_block_callback },\n',
            1,
        )
        fn_anchor = "static jboolean\nnative_is_running (JNIEnv *env, jobject thiz)\n"
        if jni_text.count(fn_anchor) != 1:
            raise SystemExit("Refusing unsafe patch: native_is_running anchor is not unique")
        functions = r'''/* SPEEDVPN_APP_BLOCK_BEGIN */
#include <stdio.h>
#define APP_BLOCK_CACHE_EMPTY 0
#define APP_BLOCK_CACHE_USED 1
#define APP_BLOCK_CACHE_DELETED 2

/* Format IPv4 as dotted decimal and IPv6 as eight hexadecimal groups. */
static void
app_uid_block_format_address (const unsigned char *address, int addr_len,
                        char *buffer, size_t buffer_len)
{
    size_t used = 0;
    int i;

    if (!buffer || buffer_len == 0)
        return;
    buffer[0] = '\0';
    if (!address) {
        (void) snprintf (buffer, buffer_len, "?");
        return;
    }
    if (addr_len == 4) {
        (void) snprintf (buffer, buffer_len, "%u.%u.%u.%u",
            (unsigned int) address[0], (unsigned int) address[1],
            (unsigned int) address[2], (unsigned int) address[3]);
        return;
    }
    if (addr_len != 16) {
        (void) snprintf (buffer, buffer_len, "?");
        return;
    }
    for (i = 0; i < 16; i += 2) {
        int written = snprintf (buffer + used, buffer_len - used,
            "%s%02x%02x", i == 0 ? "" : ":",
            (unsigned int) address[i], (unsigned int) address[i + 1]);
        if (written < 0 || (size_t) written >= buffer_len - used) {
            buffer[buffer_len - 1] = '\0';
            return;
        }
        used += (size_t) written;
    }
}

static unsigned long long
app_uid_block_now_ms (void)
{
    struct timespec now;
    if (clock_gettime (CLOCK_MONOTONIC, &now) != 0)
        return 0;
    return (unsigned long long) now.tv_sec * 1000ULL +
           (unsigned long long) now.tv_nsec / 1000000ULL;
}

static int
app_uid_block_endpoint_compare (const unsigned char *a, int a_port,
                          const unsigned char *b, int b_port, int len)
{
    int compare = memcmp (a, b, (size_t) len);
    if (compare != 0)
        return compare;
    return (a_port > b_port) - (a_port < b_port);
}

/* Canonicalize both directions of a 5-tuple to the same cache key. */
static void
app_uid_block_make_key (app_uid_block_flow_key *key, int protocol,
                  const unsigned char *src, int src_len, int src_port,
                  const unsigned char *dst, int dst_len, int dst_port)
{
    int reverse;
    memset (key, 0, sizeof (*key));
    key->protocol = protocol;
    key->addr_len = src_len;
    reverse = app_uid_block_endpoint_compare (src, src_port, dst, dst_port, src_len) > 0;
    if (reverse) {
        memcpy (key->a, dst, (size_t) dst_len);
        memcpy (key->b, src, (size_t) src_len);
        key->port_a = (unsigned short) dst_port;
        key->port_b = (unsigned short) src_port;
    } else {
        memcpy (key->a, src, (size_t) src_len);
        memcpy (key->b, dst, (size_t) dst_len);
        key->port_a = (unsigned short) src_port;
        key->port_b = (unsigned short) dst_port;
    }
}

static int
app_uid_block_key_equal (const app_uid_block_flow_key *a, const app_uid_block_flow_key *b)
{
    return a->protocol == b->protocol && a->addr_len == b->addr_len &&
           a->port_a == b->port_a && a->port_b == b->port_b &&
           memcmp (a->a, b->a, (size_t) a->addr_len) == 0 &&
           memcmp (a->b, b->b, (size_t) a->addr_len) == 0;
}

static unsigned int
app_uid_block_key_hash (const app_uid_block_flow_key *key)
{
    unsigned int hash = 2166136261u;
    int i;
#define APP_BLOCK_HASH_BYTE(value) do { hash ^= (unsigned char) (value); hash *= 16777619u; } while (0)
    APP_BLOCK_HASH_BYTE (key->protocol);
    APP_BLOCK_HASH_BYTE (key->addr_len);
    for (i = 0; i < key->addr_len; i++) APP_BLOCK_HASH_BYTE (key->a[i]);
    for (i = 0; i < key->addr_len; i++) APP_BLOCK_HASH_BYTE (key->b[i]);
    APP_BLOCK_HASH_BYTE (key->port_a >> 8);
    APP_BLOCK_HASH_BYTE (key->port_a);
    APP_BLOCK_HASH_BYTE (key->port_b >> 8);
    APP_BLOCK_HASH_BYTE (key->port_b);
#undef APP_BLOCK_HASH_BYTE
    return hash;
}

static int
app_uid_block_entry_expired (const app_uid_block_cache_entry *entry, unsigned long long now)
{
    unsigned long long age = now >= entry->updated_ms ? now - entry->updated_ms : 0;
    if (entry->uid == APP_BLOCK_UID_PENDING)
        return age > APP_BLOCK_PENDING_TIMEOUT_MS;
    if (entry->uid < 0)
        return age > APP_BLOCK_NEGATIVE_CACHE_MS;
    /* A confirmed DROP flow is pinned until the callback/session is reset. */
    if (entry->action == APP_BLOCK_ACTION_BLOCK)
        return 0;
    return age > APP_BLOCK_FLOW_IDLE_MS;
}

static void
app_uid_block_delete_cache_slot_locked (unsigned int index)
{
    if (app_uid_block_cache[index].state == APP_BLOCK_CACHE_USED) {
        app_uid_block_cache[index].state = APP_BLOCK_CACHE_DELETED;
        if (app_uid_block_active_flows > 0)
            app_uid_block_active_flows--;
    }
}

/* Must be called with app_uid_block_mutex held. Cleans expired entries encountered during probing. */
static int
app_uid_block_find_slot_locked (const app_uid_block_flow_key *key,
                          unsigned long long now, int *found)
{
    unsigned int start = app_uid_block_key_hash (key) % APP_BLOCK_FLOW_TABLE_SLOTS;
    int deleted = -1;
    unsigned int probe;
    for (probe = 0; probe < APP_BLOCK_FLOW_TABLE_SLOTS; probe++) {
        unsigned int index = (start + probe) % APP_BLOCK_FLOW_TABLE_SLOTS;
        app_uid_block_cache_entry *entry = &app_uid_block_cache[index];
        if (entry->state == APP_BLOCK_CACHE_EMPTY) {
            *found = 0;
            return deleted >= 0 ? deleted : (int) index;
        }
        if (entry->state == APP_BLOCK_CACHE_DELETED) {
            if (deleted < 0) deleted = (int) index;
            continue;
        }
        if (app_uid_block_entry_expired (entry, now)) {
            app_uid_block_delete_cache_slot_locked (index);
            if (deleted < 0) deleted = (int) index;
            continue;
        }
        if (app_uid_block_key_equal (&entry->key, key)) {
            *found = 1;
            return (int) index;
        }
    }
    *found = 0;
    return deleted;
}

static void
app_uid_block_evict_oldest_locked (void)
{
    unsigned int i;
    unsigned int oldest = APP_BLOCK_FLOW_TABLE_SLOTS;
    unsigned long long oldest_time = ~0ULL;
    for (i = 0; i < APP_BLOCK_FLOW_TABLE_SLOTS; i++) {
        if (app_uid_block_cache[i].state == APP_BLOCK_CACHE_USED &&
            app_uid_block_cache[i].action != APP_BLOCK_ACTION_BLOCK &&
            app_uid_block_cache[i].updated_ms < oldest_time) {
            oldest = i;
            oldest_time = app_uid_block_cache[i].updated_ms;
        }
    }
    if (oldest < APP_BLOCK_FLOW_TABLE_SLOTS)
        app_uid_block_delete_cache_slot_locked (oldest);
}

static void
app_uid_block_store_result_locked (const app_uid_block_flow_key *key, int uid, int action,
                             unsigned long long now,
                             unsigned long long request_id)
{
    int found = 0;
    int slot = app_uid_block_find_slot_locked (key, now, &found);
    app_uid_block_cache_entry *entry;
    /* Do not let a slow/expired request overwrite a newer request for the same tuple. */
    if (slot < 0 || !found)
        return;
    entry = &app_uid_block_cache[slot];
    if (entry->uid != APP_BLOCK_UID_PENDING || entry->request_id != request_id)
        return;
    entry->uid = uid;
    entry->action = (uid >= 0 && action == APP_BLOCK_ACTION_BLOCK)
        ? APP_BLOCK_ACTION_BLOCK : APP_BLOCK_ACTION_ALLOW;
    entry->drop_logged = 0;
    entry->updated_ms = now;
}

static void *
app_uid_block_worker_main (void *unused)
{
    JNIEnv *env = NULL;
    (void) unused;
    if (!java_vm || (*java_vm)->AttachCurrentThread (java_vm, &env, NULL) != JNI_OK) {
        __android_log_print (ANDROID_LOG_ERROR, "SpeedVPN-APP_BLOCK-JNI",
                             "UID_WORKER_ATTACH_FAILED");
        pthread_mutex_lock (&app_uid_block_mutex);
        app_uid_block_worker_ready = 0;
        app_uid_block_worker_stop_requested = 1;
        pthread_cond_broadcast (&app_uid_block_condition);
        pthread_mutex_unlock (&app_uid_block_mutex);
        return NULL;
    }

    pthread_mutex_lock (&app_uid_block_mutex);
    app_uid_block_worker_ready = 1;
    __android_log_print (ANDROID_LOG_INFO, "SpeedVPN-APP_BLOCK-JNI",
                         "UID_WORKER_READY stack=1048576");
    pthread_cond_broadcast (&app_uid_block_condition);
    pthread_mutex_unlock (&app_uid_block_mutex);

    for (;;) {
        app_uid_block_request request;
        jbyteArray src_array = NULL, dst_array = NULL;
        jlong packed_result = 0;
        jint uid = -1;
        int action = APP_BLOCK_ACTION_ALLOW;
        int call_ok = 0;

        pthread_mutex_lock (&app_uid_block_mutex);
        while (app_uid_block_queue_count == 0 && !app_uid_block_worker_stop_requested)
            pthread_cond_wait (&app_uid_block_condition, &app_uid_block_mutex);
        if (app_uid_block_worker_stop_requested) {
            app_uid_block_queue_head = app_uid_block_queue_tail = app_uid_block_queue_count = 0;
            pthread_mutex_unlock (&app_uid_block_mutex);
            break;
        }
        request = app_uid_block_queue[app_uid_block_queue_head];
        app_uid_block_queue_head = (app_uid_block_queue_head + 1u) % APP_BLOCK_QUEUE_CAPACITY;
        app_uid_block_queue_count--;
        pthread_mutex_unlock (&app_uid_block_mutex);

        if (app_uid_block_callback && app_uid_block_method) {
            src_array = (*env)->NewByteArray (env, request.src_len);
            dst_array = (*env)->NewByteArray (env, request.dst_len);
            if (src_array && dst_array) {
                (*env)->SetByteArrayRegion (env, src_array, 0, request.src_len,
                                            (const jbyte *) request.src);
                (*env)->SetByteArrayRegion (env, dst_array, 0, request.dst_len,
                                            (const jbyte *) request.dst);
            }
            if ((*env)->ExceptionCheck (env)) {
                (*env)->ExceptionDescribe (env);
                (*env)->ExceptionClear (env);
            } else if (src_array && dst_array) {
                packed_result = (*env)->CallLongMethod (env, app_uid_block_callback, app_uid_block_method,
                    (jint) request.protocol, src_array, (jint) request.src_port,
                    dst_array, (jint) request.dst_port);
                if ((*env)->ExceptionCheck (env)) {
                    __android_log_print (ANDROID_LOG_ERROR, "SpeedVPN-APP_BLOCK-JNI",
                                         "UID_CALLBACK_EXCEPTION protocol=%d src_port=%d dst_port=%d",
                                         request.protocol, request.src_port, request.dst_port);
                    (*env)->ExceptionDescribe (env);
                    (*env)->ExceptionClear (env);
                    uid = -1;
                    action = APP_BLOCK_ACTION_ALLOW;
                } else {
                    unsigned long long packed_bits = (unsigned long long) packed_result;
                    uid = (jint) (packed_bits >> 32);
                    action = (packed_bits & 1ULL)
                        ? APP_BLOCK_ACTION_BLOCK : APP_BLOCK_ACTION_ALLOW;
                    if (uid < 0)
                        action = APP_BLOCK_ACTION_ALLOW;
                    call_ok = 1;
                }
            } else {
                __android_log_print (ANDROID_LOG_ERROR, "SpeedVPN-APP_BLOCK-JNI",
                                     "UID_CALLBACK_ALLOCATION_FAILED");
            }
        }

        if (src_array) (*env)->DeleteLocalRef (env, src_array);
        if (dst_array) (*env)->DeleteLocalRef (env, dst_array);

        if (!call_ok || uid < 0)

        pthread_mutex_lock (&app_uid_block_mutex);
        app_uid_block_store_result_locked (&request.key, call_ok ? (int) uid : -1,
                                     call_ok ? action : APP_BLOCK_ACTION_ALLOW,
                                     app_uid_block_now_ms (), request.request_id);
        pthread_mutex_unlock (&app_uid_block_mutex);
    }

    (*java_vm)->DetachCurrentThread (java_vm);
    __android_log_print (ANDROID_LOG_INFO, "SpeedVPN-APP_BLOCK-JNI", "UID_WORKER_STOPPED");
    return NULL;
}

static void
app_uid_block_reset_locked (void)
{
    memset (app_uid_block_cache, 0, sizeof (app_uid_block_cache));
    memset (app_uid_block_queue, 0, sizeof (app_uid_block_queue));
    app_uid_block_active_flows = 0;
    app_uid_block_queue_head = app_uid_block_queue_tail = app_uid_block_queue_count = 0;
}

static void
app_uid_block_worker_stop (void)
{
    pthread_t worker;
    int join_worker;
    pthread_mutex_lock (&app_uid_block_mutex);
    join_worker = app_uid_block_worker_started;
    worker = app_uid_block_worker_thread;
    if (join_worker) {
        app_uid_block_worker_stop_requested = 1;
        pthread_cond_broadcast (&app_uid_block_condition);
    }
    pthread_mutex_unlock (&app_uid_block_mutex);
    if (join_worker)
        (void) pthread_join (worker, NULL);
    pthread_mutex_lock (&app_uid_block_mutex);
    app_uid_block_worker_started = 0;
    app_uid_block_worker_ready = 0;
    app_uid_block_worker_stop_requested = 0;
    app_uid_block_reset_locked ();
    pthread_mutex_unlock (&app_uid_block_mutex);
}

static int
app_uid_block_worker_start (void)
{
    pthread_attr_t attr;
    int result, ready;
    pthread_mutex_lock (&app_uid_block_mutex);
    app_uid_block_worker_stop_requested = 0;
    app_uid_block_worker_ready = 0;
    pthread_mutex_unlock (&app_uid_block_mutex);

    if (pthread_attr_init (&attr) != 0)
        return 0;
    result = pthread_attr_setstacksize (&attr, 1024u * 1024u);
    if (result == 0) {
        pthread_mutex_lock (&app_uid_block_mutex);
        app_uid_block_worker_started = 1;
        pthread_mutex_unlock (&app_uid_block_mutex);
        result = pthread_create (&app_uid_block_worker_thread, &attr, app_uid_block_worker_main, NULL);
        if (result != 0) {
            pthread_mutex_lock (&app_uid_block_mutex);
            app_uid_block_worker_started = 0;
            pthread_mutex_unlock (&app_uid_block_mutex);
        }
    }
    pthread_attr_destroy (&attr);
    if (result != 0) {
        __android_log_print (ANDROID_LOG_ERROR, "SpeedVPN-APP_BLOCK-JNI",
                             "UID_WORKER_START_FAILED error=%d", result);
        return 0;
    }

    /* Do not report callback registration success until this thread is JVM-attached. */
    pthread_mutex_lock (&app_uid_block_mutex);
    while (!app_uid_block_worker_ready && !app_uid_block_worker_stop_requested)
        pthread_cond_wait (&app_uid_block_condition, &app_uid_block_mutex);
    ready = app_uid_block_worker_ready;
    pthread_mutex_unlock (&app_uid_block_mutex);
    if (!ready) {
        (void) pthread_join (app_uid_block_worker_thread, NULL);
        pthread_mutex_lock (&app_uid_block_mutex);
        app_uid_block_worker_started = 0;
        app_uid_block_worker_ready = 0;
        app_uid_block_worker_stop_requested = 0;
        app_uid_block_reset_locked ();
        pthread_mutex_unlock (&app_uid_block_mutex);
        __android_log_print (ANDROID_LOG_ERROR, "SpeedVPN-APP_BLOCK-JNI",
                             "UID_WORKER_START_FAILED reason=attach_failed");
        return 0;
    }
    return 1;
}

static jboolean
native_set_app_uid_block_callback (JNIEnv *env, jobject thiz, jobject callback)
{
    jobject replacement = NULL, previous = NULL;
    jmethodID method = NULL;
    (void) thiz;

    if (callback) {
        jclass klass = (*env)->GetObjectClass (env, callback);
        if (!klass)
            return JNI_FALSE;
        method = (*env)->GetMethodID (env, klass, "onTunPacket", "(I[BI[BI)J");
        (*env)->DeleteLocalRef (env, klass);
        if (!method) {
            if ((*env)->ExceptionCheck (env))
                (*env)->ExceptionClear (env);
            return JNI_FALSE;
        }
        replacement = (*env)->NewGlobalRef (env, callback);
        if (!replacement)
            return JNI_FALSE;
    }

    app_uid_block_worker_stop ();
    pthread_mutex_lock (&app_uid_block_mutex);
    previous = app_uid_block_callback;
    app_uid_block_callback = replacement;
    app_uid_block_method = method;
    pthread_mutex_unlock (&app_uid_block_mutex);
    if (previous)
        (*env)->DeleteGlobalRef (env, previous);

    if (!callback)
        return JNI_TRUE;
    if (!app_uid_block_worker_start ()) {
        pthread_mutex_lock (&app_uid_block_mutex);
        app_uid_block_callback = NULL;
        app_uid_block_method = NULL;
        pthread_mutex_unlock (&app_uid_block_mutex);
        (*env)->DeleteGlobalRef (env, replacement);
        return JNI_FALSE;
    }
    return JNI_TRUE;
}

/* Called for each candidate TCP/UDP packet by the HEV coroutine.
 * It does only a native cache lookup and bounded queue operation; JNI is worker-only.
 * Return 1 only when the known UID policy says to drop this packet. */
int
hev_jni_app_uid_block_packet (int protocol,
                        const unsigned char *src, int src_len, int src_port,
                        const unsigned char *dst, int dst_len, int dst_port)
{
    app_uid_block_flow_key key;
    app_uid_block_request request;
    unsigned long long now;
    int found = 0, slot, should_drop = 0;
    char src_text[64], dst_text[64], tuple_text[200];

    if (!src || !dst || src_len != dst_len ||
        (src_len != 4 && src_len != 16) ||
        (protocol != 6 && protocol != 17) ||
        src_port < 0 || src_port > 65535 || dst_port < 0 || dst_port > 65535)
        return 0;

    app_uid_block_make_key (&key, protocol, src, src_len, src_port, dst, dst_len, dst_port);
    memset (&request, 0, sizeof (request));
    request.key = key;
    request.protocol = protocol;
    request.src_len = src_len;
    memcpy (request.src, src, (size_t) src_len);
    request.src_port = src_port;
    request.dst_len = dst_len;
    memcpy (request.dst, dst, (size_t) dst_len);
    request.dst_port = dst_port;

    pthread_mutex_lock (&app_uid_block_mutex);
    if (!app_uid_block_worker_started || !app_uid_block_worker_ready ||
        app_uid_block_worker_stop_requested || !app_uid_block_callback || !app_uid_block_method) {
        pthread_mutex_unlock (&app_uid_block_mutex);
        return 0;
    }
    now = app_uid_block_now_ms ();
    slot = app_uid_block_find_slot_locked (&key, now, &found);
    if (found) {
        app_uid_block_cache_entry *entry = &app_uid_block_cache[slot];
        if (entry->uid == APP_BLOCK_UID_PENDING) {
        } else {
            if (entry->uid >= 0 && entry->action == APP_BLOCK_ACTION_BLOCK) {
                should_drop = 1;
                if (!entry->drop_logged) {
                    entry->drop_logged = 1;
                    app_uid_block_format_address (src, src_len, src_text, sizeof (src_text));
                    app_uid_block_format_address (dst, dst_len, dst_text, sizeof (dst_text));
                    (void) snprintf (tuple_text, sizeof (tuple_text),
                        "%s|[%s]:%d->[%s]:%d",
                        protocol == 6 ? "tcp" : "udp", src_text, src_port,
                        dst_text, dst_port);
                    __android_log_print (ANDROID_LOG_WARN, "SpeedVPN-APP_BLOCK-JNI",
                        "PACKET_DROPPED uid=%d tuple=%s action=DROP hookMode=HOOK_BLOCKING packetBlocking=IMPLEMENTED",
                        entry->uid, tuple_text);
                }
            } else if (entry->uid >= 0) {
                entry->updated_ms = now;
            }
        }
        pthread_mutex_unlock (&app_uid_block_mutex);
        return should_drop;
    }

    if (slot < 0 || app_uid_block_active_flows >= APP_BLOCK_MAX_FLOWS) {
        app_uid_block_evict_oldest_locked ();
        slot = app_uid_block_find_slot_locked (&key, now, &found);
    }
    if (app_uid_block_queue_count >= APP_BLOCK_QUEUE_CAPACITY) {
        /* Briefly negative-cache overload so every packet does not retry the full queue. */
        if (slot >= 0) {
            if (!found)
                app_uid_block_active_flows++;
            app_uid_block_cache[slot].state = APP_BLOCK_CACHE_USED;
            app_uid_block_cache[slot].key = key;
            app_uid_block_cache[slot].uid = -1;
            app_uid_block_cache[slot].action = APP_BLOCK_ACTION_ALLOW;
            app_uid_block_cache[slot].drop_logged = 0;
            app_uid_block_cache[slot].updated_ms = now;
            app_uid_block_cache[slot].request_id = 0;
        }
        pthread_mutex_unlock (&app_uid_block_mutex);
        return 0;
    }
    if (slot >= 0) {
        if (!found)
            app_uid_block_active_flows++;
        request.request_id = __sync_add_and_fetch (&app_uid_block_next_request_id, 1ULL);
        app_uid_block_cache[slot].state = APP_BLOCK_CACHE_USED;
        app_uid_block_cache[slot].key = key;
        app_uid_block_cache[slot].uid = APP_BLOCK_UID_PENDING;
        app_uid_block_cache[slot].action = APP_BLOCK_ACTION_ALLOW;
        app_uid_block_cache[slot].drop_logged = 0;
        app_uid_block_cache[slot].updated_ms = now;
        app_uid_block_cache[slot].request_id = request.request_id;
        app_uid_block_queue[app_uid_block_queue_tail] = request;
        app_uid_block_queue_tail = (app_uid_block_queue_tail + 1u) % APP_BLOCK_QUEUE_CAPACITY;
        app_uid_block_queue_count++;
        pthread_cond_signal (&app_uid_block_condition);
    } else {
    }
    pthread_mutex_unlock (&app_uid_block_mutex);
    return 0;
}
/* SPEEDVPN_APP_BLOCK_END */

static jboolean
native_is_running (JNIEnv *env, jobject thiz)
'''
        jni_text = jni_text.replace(fn_anchor, functions, 1)
        jni_c.write_text(jni_text)

    # Harden JNI/thread/FD ownership on every run (even if the injected marker already
    # exists from an earlier invocation of this patcher).
    jni_text = jni_c.read_text()

    # Refuse to produce an APK if the asynchronous worker or actual native DROP path
    # was lost in a source update. Logging-only UID observation is not enforcement.
    required_native_markers = (
        "AttachCurrentThread (java_vm, &env, NULL)",
        "DetachCurrentThread (java_vm)",
        "UID_WORKER_READY stack=1048576",
        "app_uid_block_store_result_locked",
        "PACKET_DROPPED uid=%d tuple=%s",
        "if (app_uid_block_report_packet (buf))",
    )
    for marker in required_native_markers:
        source = Path(__file__).read_text() if marker == "if (app_uid_block_report_packet (buf))" else jni_text
        if marker not in source:
            raise SystemExit(f"Refusing incomplete UID firewall patch: missing native marker: {marker}")

    # The UID worker attaches to the JVM once per worker lifetime and detaches on exit.
    # The Java PFD remains owned by Service, but Native now duplicates the descriptor:
    # closing/collecting the Java wrapper cannot invalidate the FD used by HEV.
    jni_text = replace_text_once(
        jni_text,
        "    tdata->fd = fd;",
        "    tdata->fd = dup (fd);\n"
        "    if (tdata->fd < 0) {\n"
        "        free (tdata);\n"
        "        goto exit;\n"
        "    }",
        "owned TUN descriptor",
    )
    jni_text = replace_text_once(
        jni_text,
        "    if (!bytes) {\n        free (tdata);\n        goto exit;\n    }",
        "    if (!bytes) {\n        close (tdata->fd);\n        free (tdata);\n        goto exit;\n    }",
        "GetStringUTFChars cleanup",
    )
    jni_text = replace_text_once(
        jni_text,
        "    if (!tdata->path) {\n        free (tdata);\n        goto exit;\n    }",
        "    if (!tdata->path) {\n        close (tdata->fd);\n        free (tdata);\n        goto exit;\n    }",
        "path allocation cleanup",
    )
    jni_text = replace_text_once(
        jni_text,
        "    hev_socks5_tunnel_main (tdata->path, tdata->fd);\n\n"
        "    atomic_store_explicit (&is_running, 0, memory_order_release);",
        "    hev_socks5_tunnel_main (tdata->path, tdata->fd);\n\n"
        "    /* TUN FD is a native-owned dup; close only after the engine exits. */\n"
        "    close (tdata->fd);\n\n"
        "    atomic_store_explicit (&is_running, 0, memory_order_release);",
        "native worker stack/FD lifetime",
    )
    jni_text = replace_text_once(
        jni_text,
        "    if (res != 0) {\n"
        "        atomic_store_explicit (&is_running, 0, memory_order_release);\n"
        "        free (tdata->path);",
        "    if (res != 0) {\n"
        "        atomic_store_explicit (&is_running, 0, memory_order_release);\n"
        "        close (tdata->fd);\n"
        "        free (tdata->path);",
        "pthread_create cleanup",
    )
    jni_c.write_text(jni_text)

    print("OK - native per-application UID blocking hook applied")


if __name__ == "__main__":
    main()
