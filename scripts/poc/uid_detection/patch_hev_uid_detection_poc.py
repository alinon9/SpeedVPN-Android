#!/usr/bin/env python3
"""Apply the temporary UID-detection hook to the pinned hev-socks5-tunnel checkout."""

from pathlib import Path
import sys

BEGIN = "/* SPEEDVPN_UID_POC_BEGIN */"
END = "/* SPEEDVPN_UID_POC_END */"


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


def inject_native_start_step_logs(jni_text: str) -> str:
    """Inject ordered native startup breadcrumbs into the isolated UID POC JNI source."""
    if "STEP 1: nativeStart entered" in jni_text:
        return jni_text

    # Skip the prototype and anchor to the actual function definition, whose name starts a line.
    start = jni_text.find("\nnative_start_service (")
    if start < 0:
        raise SystemExit("Refusing unsafe native logging patch: native_start_service anchor missing")
    body_start = jni_text.find("{", start)
    if body_start < 0:
        raise SystemExit("Refusing unsafe native logging patch: native start body missing")

    depth = 0
    body_end = -1
    for index in range(body_start, len(jni_text)):
        char = jni_text[index]
        if char == "{":
            depth += 1
        elif char == "}":
            depth -= 1
            if depth == 0:
                body_end = index + 1
                break
    if body_end < 0:
        raise SystemExit("Refusing unsafe native logging patch: unterminated native start function")

    function = jni_text[start:body_end]

    def insert_before(source: str, needle: str, statement: str, label: str) -> str:
        if source.count(needle) != 1:
            raise SystemExit(
                f"Refusing unsafe native logging patch: expected one {label} anchor, "
                f"found {source.count(needle)}"
            )
        index = source.find(needle)
        line_start = source.rfind("\n", 0, index) + 1
        line = source[line_start:]
        indent = line[:len(line) - len(line.lstrip(" \t"))]
        return source[:line_start] + indent + statement + "\n" + source[line_start:]

    function = insert_before(
        function,
        "pthread_mutex_lock (&mutex);",
        '__android_log_print (ANDROID_LOG_INFO, "SpeedVPN-POC-Native", '
        '"STEP 1: nativeStart entered, fd=%d", fd);',
        "native start lock",
    )
    function = insert_before(
        function,
        "tdata->fd = dup (fd);",
        '__android_log_print (ANDROID_LOG_INFO, "SpeedVPN-POC-Native", '
        '"STEP 2: before dup(fd), fd=%d", fd);',
        "native fd duplication",
    )
    function = insert_before(
        function,
        "GetStringUTFChars (env, config_path, NULL)",
        '__android_log_print (ANDROID_LOG_INFO, "SpeedVPN-POC-Native", '
        '"STEP 3: before hev config path read");',
        "native config path",
    )
    function = insert_before(
        function,
        "pthread_create (",
        '__android_log_print (ANDROID_LOG_INFO, "SpeedVPN-POC-Native", '
        '"STEP 4: before pthread_create");',
        "native worker creation",
    )
    function = insert_before(
        function,
        "return result;",
        '__android_log_print (ANDROID_LOG_INFO, "SpeedVPN-POC-Native", '
        '"STEP 6: nativeStart returning result=%d", result == JNI_TRUE ? 1 : 0);',
        "native start return",
    )
    jni_text = jni_text[:start] + function + jni_text[body_end:]

    if jni_text.count("hev_socks5_tunnel_main (tdata->path, tdata->fd);") != 1:
        raise SystemExit("Refusing unsafe native logging patch: hev main anchor is not unique")
    needle = "hev_socks5_tunnel_main (tdata->path, tdata->fd);"
    index = jni_text.find(needle)
    line_start = jni_text.rfind("\n", 0, index) + 1
    line = jni_text[line_start:]
    indent = line[:len(line) - len(line.lstrip(" \t"))]
    logline = indent + '__android_log_print (ANDROID_LOG_INFO, "SpeedVPN-POC-Native", ' \
        '"STEP 5: before hev_socks5_tunnel_main");\n'
    jni_text = jni_text[:line_start] + logline + jni_text[line_start:]
    return jni_text

def main() -> None:
    if len(sys.argv) != 2:
        raise SystemExit("usage: patch_hev_uid_detection_poc.py <hev-socks5-tunnel-dir>")
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
    link_flags = "LOCAL_LDLIBS += -llog -ldl"
    if link_flags not in android_text:
        source_anchor = "LOCAL_SRC_FILES := $(HEV_SOCKS5_TUNNEL_SRC)\n"
        count = android_text.count(source_anchor)
        if count < 1:
            raise SystemExit("Refusing unsafe native build patch: LOCAL_SRC_FILES anchor missing")
        android_text = android_text.replace(source_anchor, source_anchor + link_flags + "\n")
        android_mk.write_text(android_text)

    parser = r'''
/* SPEEDVPN_UID_POC_BEGIN */
#ifdef __ANDROID__
extern void hev_jni_uid_poc_packet (int protocol,
                                     const unsigned char *src, int src_len,
                                     int src_port,
                                     const unsigned char *dst, int dst_len,
                                     int dst_port);

/* Diagnostic parser only: outgoing IP packets as they enter lwIP from Android TUN.
 * IPv6 extension headers and non-initial IPv4 fragments are deliberately skipped. */
static void
uid_poc_report_packet (const struct pbuf *buf)
{
    unsigned char ip[60], l4[20], src[16], dst[16];
    unsigned int total, offset, ip_header_len, src_len, need;
    unsigned int protocol, src_port, dst_port;
    unsigned int version;

    if (!buf)
        return;
    total = buf->tot_len;
    if (total < 20 || pbuf_copy_partial (buf, ip, 1, 0) != 1)
        return;
    version = ip[0] >> 4;

    if (version == 4) {
        if (total < 20 || pbuf_copy_partial (buf, ip, 20, 0) != 20)
            return;
        ip_header_len = (ip[0] & 0x0f) * 4;
        if (ip_header_len < 20 || ip_header_len > sizeof (ip) ||
            total < ip_header_len + 8 ||
            pbuf_copy_partial (buf, ip, ip_header_len, 0) != ip_header_len)
            return;
        /* A non-initial fragment does not contain TCP/UDP ports. */
        if ((((unsigned int) ip[6] << 8) | ip[7]) & 0x1fff)
            return;
        protocol = ip[9];
        src_len = 4;
        offset = ip_header_len;
        memcpy (src, ip + 12, 4);
        memcpy (dst, ip + 16, 4);
    } else if (version == 6) {
        if (total < 48 || pbuf_copy_partial (buf, ip, 40, 0) != 40)
            return;
        protocol = ip[6];
        src_len = 16;
        offset = 40;
        memcpy (src, ip + 8, 16);
        memcpy (dst, ip + 24, 16);
        /* POC v1 handles the common direct TCP/UDP next-header case only. */
    } else {
        return;
    }

    if (protocol != 6 && protocol != 17)
        return;
    need = protocol == 6 ? 20 : 8;
    if (total < offset + need ||
        pbuf_copy_partial (buf, l4, need, offset) != need)
        return;

    src_port = ((unsigned int) l4[0] << 8) | l4[1];
    dst_port = ((unsigned int) l4[2] << 8) | l4[3];
    hev_jni_uid_poc_packet (protocol, src, src_len, src_port,
                            dst, src_len, dst_port);
}
/* SPEEDVPN_UID_POC_END */
#else
#define uid_poc_report_packet(buf) ((void) 0)
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
        "        if (!buf)\n            continue;\n\n        uid_poc_report_packet (buf);\n        stat_tx_packets++;",
    )

    # The packet path performs a native cache lookup and queue operation only.
    # Binder/JNI work is performed by a dedicated 1 MiB pthread in hev-jni.c.

    # JNI bridge: callback invocations are rate-limited and moved off the TUN thread in Kotlin.
    jni_text = jni_c.read_text()
    if BEGIN not in jni_text:
        crash_handler = r''' 
/* SPEEDVPN_NATIVE_CRASH_BEGIN */
#include <signal.h>
#include <unistd.h>
#include <fcntl.h>
#include <dlfcn.h>
#include <android/log.h>
#include <sys/syscall.h>
#include <sys/types.h>
#include <stdint.h>
#include <stdlib.h>
#include <string.h>
#include <time.h>

static int native_crash_fd = -1;
static struct sigaction native_prev_segv;
static struct sigaction native_prev_abrt;
static struct sigaction native_prev_bus;
static struct sigaction native_default_action;
static volatile sig_atomic_t native_crash_handling = 0;
static __thread void *native_alt_stack_mem = NULL;
static __thread int native_alt_stack_ready = 0;
typedef int (*native_backtrace_fn) (void **, int);
typedef char **(*native_backtrace_symbols_fn) (void *const *, int);
static native_backtrace_fn native_backtrace = NULL;
static native_backtrace_symbols_fn native_backtrace_symbols = NULL;

/* The first low-level write happens before optional symbolization. The latter is not
 * async-signal-safe; this is diagnostic-only and the original Android handler is chained. */
static void native_write_text (const char *text)
{
    if (native_crash_fd >= 0 && text)
        (void) write (native_crash_fd, text, strlen (text));
}
static void native_write_number (long value)
{
    char digits[24];
    unsigned int count = 0;
    unsigned long number;
    if (native_crash_fd < 0) return;
    if (value < 0) {
        (void) write (native_crash_fd, "-", 1);
        number = (unsigned long) (-value);
    } else number = (unsigned long) value;
    do {
        digits[count++] = (char) ('0' + (number % 10));
        number /= 10;
    } while (number && count < sizeof (digits));
    while (count) (void) write (native_crash_fd, &digits[--count], 1);
}

static void native_install_alt_stack_for_thread (void)
{
    stack_t stack;
    if (native_alt_stack_ready) return;
    native_alt_stack_mem = malloc (64 * 1024);
    if (!native_alt_stack_mem) return;
    stack.ss_sp = native_alt_stack_mem;
    stack.ss_size = 64 * 1024;
    stack.ss_flags = 0;
    if (sigaltstack (&stack, NULL) == 0) native_alt_stack_ready = 1;
    else { free (native_alt_stack_mem); native_alt_stack_mem = NULL; }
}

static struct sigaction *native_previous_action (int signal_number)
{
    if (signal_number == SIGSEGV) return &native_prev_segv;
    if (signal_number == SIGABRT) return &native_prev_abrt;
    if (signal_number == SIGBUS) return &native_prev_bus;
    return NULL;
}

static void native_crash_handler (int signal_number, siginfo_t *info, void *context)
{
    struct timespec now;
    struct sigaction *previous;
    void *frames[32];
    int frame_count = 0;
    char **symbols = NULL;
    int i;
    if (native_crash_handling) _exit (128 + signal_number);
    native_crash_handling = 1;

    native_write_text ("\n=== SPEEDVPN POC NATIVE CRASH ===\nsignal=");
    native_write_number (signal_number);
    native_write_text (" pid=");
    native_write_number ((long) getpid ());
    native_write_text (" tid=");
    native_write_number ((long) syscall (SYS_gettid));
    if (clock_gettime (CLOCK_REALTIME, &now) == 0) {
        native_write_text (" epoch_sec=");
        native_write_number ((long) now.tv_sec);
    }
    if (info) {
        uintptr_t address = (uintptr_t) info->si_addr;
        static const char hex[] = "0123456789abcdef";
        char encoded[2 * sizeof (uintptr_t)];
        size_t j;
        for (j = 0; j < sizeof (encoded); j++) {
            unsigned int shift = (unsigned int) ((sizeof (encoded) - 1 - j) * 4);
            encoded[j] = hex[(address >> shift) & 0xf];
        }
        native_write_text (" addr=0x");
        if (native_crash_fd >= 0) (void) write (native_crash_fd, encoded, sizeof (encoded));
    }
    native_write_text ("\n");

    /* Dynamic resolution avoids a hard link dependency on API levels lacking these symbols. */
    if (native_backtrace) frame_count = native_backtrace (frames, (int) (sizeof (frames) / sizeof (frames[0])));
    native_write_text ("backtrace_count=");
    native_write_number (frame_count);
    native_write_text ("\n");
    if (frame_count > 0 && native_backtrace_symbols)
        symbols = native_backtrace_symbols (frames, frame_count);
    if (symbols) {
        for (i = 0; i < frame_count; i++) {
            native_write_text ("  ");
            native_write_text (symbols[i]);
            native_write_text ("\n");
        }
    } else native_write_text ("backtrace_symbols unavailable or failed\n");
    native_write_text ("=== END SPEEDVPN POC NATIVE CRASH ===\n");
    if (native_crash_fd >= 0) (void) fsync (native_crash_fd);
    __android_log_print (ANDROID_LOG_FATAL, "SpeedVPN-POC-NativeCrash",
                         "Captured signal %d; chaining original crash handler", signal_number);

    previous = native_previous_action (signal_number);
    if (previous && (previous->sa_flags & SA_SIGINFO) &&
        previous->sa_sigaction && previous->sa_sigaction != native_crash_handler)
        previous->sa_sigaction (signal_number, info, context);
    else if (previous && previous->sa_handler != SIG_DFL &&
             previous->sa_handler != SIG_IGN && previous->sa_handler != NULL)
        previous->sa_handler (signal_number);
    (void) sigaction (signal_number, &native_default_action, NULL);
    (void) syscall (SYS_tgkill, getpid (), syscall (SYS_gettid), signal_number);
    _exit (128 + signal_number);
}

static void native_install_crash_handler (void)
{
    struct sigaction action;
    if (native_crash_fd < 0) {
        native_crash_fd = open (
            "/data/user/0/com.speedvpn.app/files/uid-poc-diagnostics/Native-Crash.txt",
            O_WRONLY | O_CREAT | O_APPEND | O_CLOEXEC, 0600);
    }
    native_backtrace = (native_backtrace_fn) dlsym (RTLD_DEFAULT, "backtrace");
    native_backtrace_symbols = (native_backtrace_symbols_fn) dlsym (RTLD_DEFAULT, "backtrace_symbols");
    native_install_alt_stack_for_thread ();
    memset (&native_default_action, 0, sizeof (native_default_action));
    native_default_action.sa_handler = SIG_DFL;
    sigemptyset (&native_default_action.sa_mask);
    memset (&action, 0, sizeof (action));
    sigemptyset (&action.sa_mask);
    action.sa_sigaction = native_crash_handler;
    action.sa_flags = SA_SIGINFO | SA_ONSTACK;
    (void) sigaction (SIGSEGV, &action, &native_prev_segv);
    (void) sigaction (SIGABRT, &action, &native_prev_abrt);
    (void) sigaction (SIGBUS, &action, &native_prev_bus);
}
__attribute__((constructor)) static void native_poc_crash_init (void)
{
    native_install_crash_handler ();
}
/* SPEEDVPN_NATIVE_CRASH_END */
'''
        jni_text = jni_text.replace(
            "static pthread_key_t current_jni_env;\n",
            "static pthread_key_t current_jni_env;\n"
            + crash_handler + "\n"
            + BEGIN + "\n"
            + "#define UID_POC_FLOW_TABLE_SLOTS 8192u\n"
            + "#define UID_POC_MAX_FLOWS 4096u\n"
            + "#define UID_POC_QUEUE_CAPACITY 256u\n"
            + "#define UID_POC_UID_PENDING (-2)\n"
            + "#define UID_POC_FLOW_IDLE_MS 30000ULL\n"
            + "#define UID_POC_NEGATIVE_CACHE_MS 750ULL\n"
            + "#define UID_POC_PENDING_TIMEOUT_MS 10000ULL\n"
            + "typedef struct { int protocol; int addr_len; unsigned char a[16]; unsigned char b[16]; unsigned short port_a; unsigned short port_b; } uid_poc_flow_key;\n"
            + "typedef struct { int state; uid_poc_flow_key key; int uid; unsigned long long updated_ms; unsigned long long request_id; } uid_poc_cache_entry;\n"
            + "typedef struct { uid_poc_flow_key key; int protocol; int src_len; unsigned char src[16]; int src_port; int dst_len; unsigned char dst[16]; int dst_port; unsigned long long request_id; } uid_poc_request;\n"
            + "static pthread_mutex_t uid_poc_mutex = PTHREAD_MUTEX_INITIALIZER;\n"
            + "static pthread_cond_t uid_poc_condition = PTHREAD_COND_INITIALIZER;\n"
            + "static jobject uid_poc_callback = NULL;\n"
            + "static jmethodID uid_poc_method = NULL;\n"
            + "static uid_poc_cache_entry uid_poc_cache[UID_POC_FLOW_TABLE_SLOTS];\n"
            + "static uid_poc_request uid_poc_queue[UID_POC_QUEUE_CAPACITY];\n"
            + "static unsigned int uid_poc_queue_head = 0, uid_poc_queue_tail = 0, uid_poc_queue_count = 0;\n"
            + "static unsigned int uid_poc_active_flows = 0;\n"
            + "static pthread_t uid_poc_worker_thread;\n"
            + "static int uid_poc_worker_started = 0, uid_poc_worker_ready = 0, uid_poc_worker_stop_requested = 0;\n"
            + "static volatile unsigned long long uid_poc_next_request_id = 0;\n"
            + "static volatile unsigned long long uid_poc_packets = 0, uid_poc_cache_hits = 0, uid_poc_cache_misses = 0;\n"
            + "static volatile unsigned long long uid_poc_pending_hits = 0, uid_poc_unique_requests = 0, uid_poc_jni_lookups = 0;\n"
            + "static volatile unsigned long long uid_poc_queue_full = 0, uid_poc_lookup_failures = 0;\n"
            + END + "\n",
            1,
        )
        jni_text = jni_text.replace(
            "static jlongArray native_get_stats (JNIEnv *env, jobject thiz);\n",
            "static jlongArray native_get_stats (JNIEnv *env, jobject thiz);\n"
            "static jboolean native_set_uid_poc_callback (JNIEnv *env, jobject thiz, jobject callback);\n",
            1,
        )
        anchor = '    { "TProxyGetStats", "()[J", (void *)native_get_stats },\n'
        if jni_text.count(anchor) != 1:
            raise SystemExit("Refusing unsafe patch: JNI method table anchor is not unique")
        jni_text = jni_text.replace(
            anchor,
            anchor + '    { "TProxySetUidPocCallback", "(Ljava/lang/Object;)Z", (void *)native_set_uid_poc_callback },\n',
            1,
        )
        fn_anchor = "static jboolean\nnative_is_running (JNIEnv *env, jobject thiz)\n"
        if jni_text.count(fn_anchor) != 1:
            raise SystemExit("Refusing unsafe patch: native_is_running anchor is not unique")
        functions = r'''/* SPEEDVPN_UID_POC_BEGIN */
#define UID_POC_CACHE_EMPTY 0
#define UID_POC_CACHE_USED 1
#define UID_POC_CACHE_DELETED 2

static unsigned long long
uid_poc_now_ms (void)
{
    struct timespec now;
    if (clock_gettime (CLOCK_MONOTONIC, &now) != 0)
        return 0;
    return (unsigned long long) now.tv_sec * 1000ULL +
           (unsigned long long) now.tv_nsec / 1000000ULL;
}

static int
uid_poc_endpoint_compare (const unsigned char *a, int a_port,
                          const unsigned char *b, int b_port, int len)
{
    int compare = memcmp (a, b, (size_t) len);
    if (compare != 0)
        return compare;
    return (a_port > b_port) - (a_port < b_port);
}

/* Canonicalize both directions of a 5-tuple to the same cache key. */
static void
uid_poc_make_key (uid_poc_flow_key *key, int protocol,
                  const unsigned char *src, int src_len, int src_port,
                  const unsigned char *dst, int dst_len, int dst_port)
{
    int reverse;
    memset (key, 0, sizeof (*key));
    key->protocol = protocol;
    key->addr_len = src_len;
    reverse = uid_poc_endpoint_compare (src, src_port, dst, dst_port, src_len) > 0;
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
uid_poc_key_equal (const uid_poc_flow_key *a, const uid_poc_flow_key *b)
{
    return a->protocol == b->protocol && a->addr_len == b->addr_len &&
           a->port_a == b->port_a && a->port_b == b->port_b &&
           memcmp (a->a, b->a, (size_t) a->addr_len) == 0 &&
           memcmp (a->b, b->b, (size_t) a->addr_len) == 0;
}

static unsigned int
uid_poc_key_hash (const uid_poc_flow_key *key)
{
    unsigned int hash = 2166136261u;
    int i;
#define UID_POC_HASH_BYTE(value) do { hash ^= (unsigned char) (value); hash *= 16777619u; } while (0)
    UID_POC_HASH_BYTE (key->protocol);
    UID_POC_HASH_BYTE (key->addr_len);
    for (i = 0; i < key->addr_len; i++) UID_POC_HASH_BYTE (key->a[i]);
    for (i = 0; i < key->addr_len; i++) UID_POC_HASH_BYTE (key->b[i]);
    UID_POC_HASH_BYTE (key->port_a >> 8);
    UID_POC_HASH_BYTE (key->port_a);
    UID_POC_HASH_BYTE (key->port_b >> 8);
    UID_POC_HASH_BYTE (key->port_b);
#undef UID_POC_HASH_BYTE
    return hash;
}

static int
uid_poc_entry_expired (const uid_poc_cache_entry *entry, unsigned long long now)
{
    unsigned long long age = now >= entry->updated_ms ? now - entry->updated_ms : 0;
    if (entry->uid == UID_POC_UID_PENDING)
        return age > UID_POC_PENDING_TIMEOUT_MS;
    if (entry->uid < 0)
        return age > UID_POC_NEGATIVE_CACHE_MS;
    return age > UID_POC_FLOW_IDLE_MS;
}

static void
uid_poc_delete_cache_slot_locked (unsigned int index)
{
    if (uid_poc_cache[index].state == UID_POC_CACHE_USED) {
        uid_poc_cache[index].state = UID_POC_CACHE_DELETED;
        if (uid_poc_active_flows > 0)
            uid_poc_active_flows--;
    }
}

/* Must be called with uid_poc_mutex held. Cleans expired entries encountered during probing. */
static int
uid_poc_find_slot_locked (const uid_poc_flow_key *key,
                          unsigned long long now, int *found)
{
    unsigned int start = uid_poc_key_hash (key) % UID_POC_FLOW_TABLE_SLOTS;
    int deleted = -1;
    unsigned int probe;
    for (probe = 0; probe < UID_POC_FLOW_TABLE_SLOTS; probe++) {
        unsigned int index = (start + probe) % UID_POC_FLOW_TABLE_SLOTS;
        uid_poc_cache_entry *entry = &uid_poc_cache[index];
        if (entry->state == UID_POC_CACHE_EMPTY) {
            *found = 0;
            return deleted >= 0 ? deleted : (int) index;
        }
        if (entry->state == UID_POC_CACHE_DELETED) {
            if (deleted < 0) deleted = (int) index;
            continue;
        }
        if (uid_poc_entry_expired (entry, now)) {
            uid_poc_delete_cache_slot_locked (index);
            if (deleted < 0) deleted = (int) index;
            continue;
        }
        if (uid_poc_key_equal (&entry->key, key)) {
            *found = 1;
            return (int) index;
        }
    }
    *found = 0;
    return deleted;
}

static void
uid_poc_evict_oldest_locked (void)
{
    unsigned int i;
    unsigned int oldest = UID_POC_FLOW_TABLE_SLOTS;
    unsigned long long oldest_time = ~0ULL;
    for (i = 0; i < UID_POC_FLOW_TABLE_SLOTS; i++) {
        if (uid_poc_cache[i].state == UID_POC_CACHE_USED &&
            uid_poc_cache[i].updated_ms < oldest_time) {
            oldest = i;
            oldest_time = uid_poc_cache[i].updated_ms;
        }
    }
    if (oldest < UID_POC_FLOW_TABLE_SLOTS)
        uid_poc_delete_cache_slot_locked (oldest);
}

static void
uid_poc_store_result_locked (const uid_poc_flow_key *key, int uid,
                             unsigned long long now,
                             unsigned long long request_id)
{
    int found = 0;
    int slot = uid_poc_find_slot_locked (key, now, &found);
    uid_poc_cache_entry *entry;
    /* Do not let a slow/expired request overwrite a newer request for the same tuple. */
    if (slot < 0 || !found)
        return;
    entry = &uid_poc_cache[slot];
    if (entry->uid != UID_POC_UID_PENDING || entry->request_id != request_id)
        return;
    entry->uid = uid;
    entry->updated_ms = now;
}

static void
uid_poc_log_metrics (unsigned long long packet_count)
{
    unsigned long long hits, misses, pending, lookups, full, failures, unique;
    double hit_rate;
    if (packet_count == 0 || packet_count % 1000ULL != 0)
        return;
    hits = __sync_add_and_fetch (&uid_poc_cache_hits, 0ULL);
    misses = __sync_add_and_fetch (&uid_poc_cache_misses, 0ULL);
    pending = __sync_add_and_fetch (&uid_poc_pending_hits, 0ULL);
    lookups = __sync_add_and_fetch (&uid_poc_jni_lookups, 0ULL);
    full = __sync_add_and_fetch (&uid_poc_queue_full, 0ULL);
    failures = __sync_add_and_fetch (&uid_poc_lookup_failures, 0ULL);
    unique = __sync_add_and_fetch (&uid_poc_unique_requests, 0ULL);
    hit_rate = (hits + misses) == 0 ? 0.0 : (100.0 * (double) hits / (double) (hits + misses));
    __android_log_print (ANDROID_LOG_INFO, "SpeedVPN-POC-JNI",
        "PACKETS=%llu CACHE_HITS=%llu CACHE_MISSES=%llu PENDING_HITS=%llu UNIQUE_LOOKUPS=%llu JNI_LOOKUPS=%llu HIT_RATE=%.2f%% QUEUE_FULL=%llu LOOKUP_FAILS=%llu",
        packet_count, hits, misses, pending, unique, lookups, hit_rate, full, failures);
}

static void *
uid_poc_worker_main (void *unused)
{
    JNIEnv *env = NULL;
    (void) unused;
    if (!java_vm || (*java_vm)->AttachCurrentThread (java_vm, &env, NULL) != JNI_OK) {
        __android_log_print (ANDROID_LOG_ERROR, "SpeedVPN-POC-JNI",
                             "UID_WORKER_ATTACH_FAILED");
        pthread_mutex_lock (&uid_poc_mutex);
        uid_poc_worker_ready = 0;
        uid_poc_worker_stop_requested = 1;
        pthread_cond_broadcast (&uid_poc_condition);
        pthread_mutex_unlock (&uid_poc_mutex);
        return NULL;
    }

    pthread_mutex_lock (&uid_poc_mutex);
    uid_poc_worker_ready = 1;
    __android_log_print (ANDROID_LOG_INFO, "SpeedVPN-POC-JNI",
                         "UID_WORKER_READY stack=1048576");
    pthread_cond_broadcast (&uid_poc_condition);
    pthread_mutex_unlock (&uid_poc_mutex);

    for (;;) {
        uid_poc_request request;
        jbyteArray src_array = NULL, dst_array = NULL;
        jint uid = -1;
        int call_ok = 0;

        pthread_mutex_lock (&uid_poc_mutex);
        while (uid_poc_queue_count == 0 && !uid_poc_worker_stop_requested)
            pthread_cond_wait (&uid_poc_condition, &uid_poc_mutex);
        if (uid_poc_worker_stop_requested) {
            uid_poc_queue_head = uid_poc_queue_tail = uid_poc_queue_count = 0;
            pthread_mutex_unlock (&uid_poc_mutex);
            break;
        }
        request = uid_poc_queue[uid_poc_queue_head];
        uid_poc_queue_head = (uid_poc_queue_head + 1u) % UID_POC_QUEUE_CAPACITY;
        uid_poc_queue_count--;
        pthread_mutex_unlock (&uid_poc_mutex);

        if (uid_poc_callback && uid_poc_method) {
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
                __sync_add_and_fetch (&uid_poc_jni_lookups, 1ULL);
                uid = (*env)->CallIntMethod (env, uid_poc_callback, uid_poc_method,
                    (jint) request.protocol, src_array, (jint) request.src_port,
                    dst_array, (jint) request.dst_port);
                if ((*env)->ExceptionCheck (env)) {
                    __android_log_print (ANDROID_LOG_ERROR, "SpeedVPN-POC-JNI",
                                         "UID_CALLBACK_EXCEPTION protocol=%d src_port=%d dst_port=%d",
                                         request.protocol, request.src_port, request.dst_port);
                    (*env)->ExceptionDescribe (env);
                    (*env)->ExceptionClear (env);
                    uid = -1;
                } else {
                    call_ok = 1;
                }
            } else {
                __android_log_print (ANDROID_LOG_ERROR, "SpeedVPN-POC-JNI",
                                     "UID_CALLBACK_ALLOCATION_FAILED");
            }
        }

        if (src_array) (*env)->DeleteLocalRef (env, src_array);
        if (dst_array) (*env)->DeleteLocalRef (env, dst_array);

        if (!call_ok || uid < 0)
            __sync_add_and_fetch (&uid_poc_lookup_failures, 1ULL);

        pthread_mutex_lock (&uid_poc_mutex);
        uid_poc_store_result_locked (&request.key, call_ok ? (int) uid : -1,
                                     uid_poc_now_ms (), request.request_id);
        pthread_mutex_unlock (&uid_poc_mutex);
    }

    (*java_vm)->DetachCurrentThread (java_vm);
    __android_log_print (ANDROID_LOG_INFO, "SpeedVPN-POC-JNI", "UID_WORKER_STOPPED");
    return NULL;
}

static void
uid_poc_reset_locked (void)
{
    memset (uid_poc_cache, 0, sizeof (uid_poc_cache));
    memset (uid_poc_queue, 0, sizeof (uid_poc_queue));
    uid_poc_active_flows = 0;
    uid_poc_queue_head = uid_poc_queue_tail = uid_poc_queue_count = 0;
}

static void
uid_poc_worker_stop (void)
{
    pthread_t worker;
    int join_worker;
    pthread_mutex_lock (&uid_poc_mutex);
    join_worker = uid_poc_worker_started;
    worker = uid_poc_worker_thread;
    if (join_worker) {
        uid_poc_worker_stop_requested = 1;
        pthread_cond_broadcast (&uid_poc_condition);
    }
    pthread_mutex_unlock (&uid_poc_mutex);
    if (join_worker)
        (void) pthread_join (worker, NULL);
    pthread_mutex_lock (&uid_poc_mutex);
    uid_poc_worker_started = 0;
    uid_poc_worker_ready = 0;
    uid_poc_worker_stop_requested = 0;
    uid_poc_reset_locked ();
    pthread_mutex_unlock (&uid_poc_mutex);
}

static int
uid_poc_worker_start (void)
{
    pthread_attr_t attr;
    int result;
    pthread_mutex_lock (&uid_poc_mutex);
    uid_poc_worker_stop_requested = 0;
    uid_poc_worker_ready = 0;
    pthread_mutex_unlock (&uid_poc_mutex);

    if (pthread_attr_init (&attr) != 0)
        return 0;
    result = pthread_attr_setstacksize (&attr, 1024u * 1024u);
    if (result == 0) {
        pthread_mutex_lock (&uid_poc_mutex);
        uid_poc_worker_started = 1;
        pthread_mutex_unlock (&uid_poc_mutex);
        result = pthread_create (&uid_poc_worker_thread, &attr, uid_poc_worker_main, NULL);
        if (result != 0) {
            pthread_mutex_lock (&uid_poc_mutex);
            uid_poc_worker_started = 0;
            pthread_mutex_unlock (&uid_poc_mutex);
        }
    }
    pthread_attr_destroy (&attr);
    if (result != 0) {
        __android_log_print (ANDROID_LOG_ERROR, "SpeedVPN-POC-JNI",
                             "UID_WORKER_START_FAILED error=%d", result);
        return 0;
    }
    return 1;
}

static jboolean
native_set_uid_poc_callback (JNIEnv *env, jobject thiz, jobject callback)
{
    jobject replacement = NULL, previous = NULL;
    jmethodID method = NULL;
    (void) thiz;

    if (callback) {
        jclass klass = (*env)->GetObjectClass (env, callback);
        if (!klass)
            return JNI_FALSE;
        method = (*env)->GetMethodID (env, klass, "onTunPacket", "(I[BI[BI)I");
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

    uid_poc_worker_stop ();
    pthread_mutex_lock (&uid_poc_mutex);
    previous = uid_poc_callback;
    uid_poc_callback = replacement;
    uid_poc_method = method;
    pthread_mutex_unlock (&uid_poc_mutex);
    if (previous)
        (*env)->DeleteGlobalRef (env, previous);

    if (!callback)
        return JNI_TRUE;
    if (!uid_poc_worker_start ()) {
        pthread_mutex_lock (&uid_poc_mutex);
        uid_poc_callback = NULL;
        uid_poc_method = NULL;
        pthread_mutex_unlock (&uid_poc_mutex);
        (*env)->DeleteGlobalRef (env, replacement);
        return JNI_FALSE;
    }
    return JNI_TRUE;
}

/* Called for each candidate TCP/UDP packet by the HEV coroutine. This function
 * only performs a native table lookup and queues a cache miss; it never calls ART. */
void
hev_jni_uid_poc_packet (int protocol,
                        const unsigned char *src, int src_len, int src_port,
                        const unsigned char *dst, int dst_len, int dst_port)
{
    uid_poc_flow_key key;
    uid_poc_request request;
    unsigned long long now, packet_count;
    int found = 0, slot;

    if (!src || !dst || src_len != dst_len ||
        (src_len != 4 && src_len != 16) ||
        (protocol != 6 && protocol != 17) ||
        src_port < 0 || src_port > 65535 || dst_port < 0 || dst_port > 65535)
        return;

    uid_poc_make_key (&key, protocol, src, src_len, src_port, dst, dst_len, dst_port);
    memset (&request, 0, sizeof (request));
    request.key = key;
    request.protocol = protocol;
    request.src_len = src_len;
    memcpy (request.src, src, (size_t) src_len);
    request.src_port = src_port;
    request.dst_len = dst_len;
    memcpy (request.dst, dst, (size_t) dst_len);
    request.dst_port = dst_port;

    pthread_mutex_lock (&uid_poc_mutex);
    if (!uid_poc_worker_started || !uid_poc_worker_ready ||
        uid_poc_worker_stop_requested || !uid_poc_callback || !uid_poc_method) {
        pthread_mutex_unlock (&uid_poc_mutex);
        return;
    }
    packet_count = __sync_add_and_fetch (&uid_poc_packets, 1ULL);
    now = uid_poc_now_ms();
    slot = uid_poc_find_slot_locked (&key, now, &found);
    if (found) {
        uid_poc_cache_entry *entry = &uid_poc_cache[slot];
        __sync_add_and_fetch (&uid_poc_cache_hits, 1ULL);
        if (entry->uid == UID_POC_UID_PENDING)
            __sync_add_and_fetch (&uid_poc_pending_hits, 1ULL);
        else if (entry->uid >= 0)
            entry->updated_ms = now;
        pthread_mutex_unlock (&uid_poc_mutex);
        uid_poc_log_metrics (packet_count);
        return;
    }

    __sync_add_and_fetch (&uid_poc_cache_misses, 1ULL);
    if (uid_poc_queue_count >= UID_POC_QUEUE_CAPACITY) {
        __sync_add_and_fetch (&uid_poc_queue_full, 1ULL);
        pthread_mutex_unlock (&uid_poc_mutex);
        uid_poc_log_metrics (packet_count);
        return;
    }
    if (slot < 0 || uid_poc_active_flows >= UID_POC_MAX_FLOWS) {
        uid_poc_evict_oldest_locked ();
        slot = uid_poc_find_slot_locked (&key, now, &found);
    }
    if (slot >= 0) {
        if (!found)
            uid_poc_active_flows++;
        request.request_id = __sync_add_and_fetch (&uid_poc_next_request_id, 1ULL);
        uid_poc_cache[slot].state = UID_POC_CACHE_USED;
        uid_poc_cache[slot].key = key;
        uid_poc_cache[slot].uid = UID_POC_UID_PENDING;
        uid_poc_cache[slot].updated_ms = now;
        uid_poc_cache[slot].request_id = request.request_id;
        uid_poc_queue[uid_poc_queue_tail] = request;
        uid_poc_queue_tail = (uid_poc_queue_tail + 1u) % UID_POC_QUEUE_CAPACITY;
        uid_poc_queue_count++;
        __sync_add_and_fetch (&uid_poc_unique_requests, 1ULL);
        pthread_cond_signal (&uid_poc_condition);
    } else {
        __sync_add_and_fetch (&uid_poc_queue_full, 1ULL);
    }
    pthread_mutex_unlock (&uid_poc_mutex);
    uid_poc_log_metrics (packet_count);
}
/* SPEEDVPN_UID_POC_END */

static jboolean
native_is_running (JNIEnv *env, jobject thiz)
'''
        jni_text = jni_text.replace(fn_anchor, functions, 1)
        jni_c.write_text(jni_text)

    # Harden JNI/thread/FD ownership on every run (even if the injected marker already
    # exists from an earlier invocation of this patcher).
    jni_text = jni_c.read_text()

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
        "    /* Install SA_ONSTACK storage on the worker OS thread before HEV switches stacks. */\n"
        "    native_install_alt_stack_for_thread ();\n"
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
    jni_text = inject_native_start_step_logs(jni_text)
    jni_c.write_text(jni_text)

    print("OK - temporary UID detection POC hook and native STEP 1-6 logging applied")


if __name__ == "__main__":
    main()
