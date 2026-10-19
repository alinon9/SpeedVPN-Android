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
    session_c = root / "src/hev-socks5-session.c"
    session_h = root / "src/hev-socks5-session.h"
    session_tcp_c = root / "src/hev-socks5-session-tcp.c"
    session_tcp_h = root / "src/hev-socks5-session-tcp.h"
    session_udp_c = root / "src/hev-socks5-session-udp.c"
    session_udp_h = root / "src/hev-socks5-session-udp.h"
    for required in (
        tunnel_c, jni_c, session_c, session_h,
        session_tcp_c, session_tcp_h, session_udp_c, session_udp_h,
    ):
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
extern int hev_jni_uid_poc_packet (int protocol,
                                    const unsigned char *src, int src_len,
                                    int src_port,
                                    const unsigned char *dst, int dst_len,
                                    int dst_port);

/* Parse outbound IP packets before lwIP. A cached BLOCK decision returns 1 so
 * the caller frees the pbuf instead of handing the packet to netif->input(). */
static int
uid_poc_report_packet (const struct pbuf *buf)
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
            total < ip_header_len ||
            pbuf_copy_partial (buf, ip, ip_header_len, 0) != ip_header_len)
            return 0;
        fragment_field = ((unsigned int) ip[6] << 8) | ip[7];
        protocol = ip[9];
        src_len = 4;
        offset = ip_header_len;
        memcpy (src, ip + 12, 4);
        memcpy (dst, ip + 16, 4);
        /* Portless fragments fail closed while the app UID policy is active. */
        if (fragment_field & 0x1fff) {
            if (protocol == 6 || protocol == 17)
                return hev_jni_uid_poc_packet (protocol, src, src_len, -1,
                                               dst, src_len, -1);
            return 0;
        }
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
                /* Portless IPv6 fragments fail closed while UID enforcement is active. */
                if (fragment_offset != 0) {
                    int fragment_protocol = (next_header == 6 || next_header == 17) ? (int) next_header : 17;
                    return hev_jni_uid_poc_packet (fragment_protocol, src, src_len, -1,
                                                   dst, src_len, -1);
                }
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
    return hev_jni_uid_poc_packet (protocol, src, src_len, src_port,
                                   dst, src_len, dst_port);
}
/* SPEEDVPN_UID_POC_END */
#else
#define uid_poc_report_packet(buf) 0
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
        "        if (!buf)\n            continue;\n\n        if (uid_poc_report_packet (buf)) {\n            pbuf_free (buf);\n            continue;\n        }\n\n        stat_tx_packets++;",
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
            + "#define UID_POC_ACTION_ALLOW 0\n"
            + "#define UID_POC_ACTION_BLOCK 1\n"
            + "#define UID_POC_FLOW_IDLE_MS 30000ULL\n"
            + "#define UID_POC_NEGATIVE_CACHE_MS 750ULL\n"
            + "#define UID_POC_PENDING_TIMEOUT_MS 10000ULL\n"
            + "#define UID_POC_DECISION_TIMEOUT_MS 50ULL\n"
            + "typedef struct { int protocol; int addr_len; unsigned char a[16]; unsigned char b[16]; unsigned short port_a; unsigned short port_b; } uid_poc_flow_key;\n"
            + "typedef struct { int state; uid_poc_flow_key key; int uid; int action; int drop_logged; unsigned long long updated_ms; unsigned long long request_id; } uid_poc_cache_entry;\n"
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
            + "static int uid_poc_policy_active = 0;\n"
            + "static volatile unsigned long long uid_poc_next_request_id = 0;\n"
            + "static volatile unsigned long long uid_poc_packets = 0, uid_poc_cache_hits = 0, uid_poc_cache_misses = 0;\n"
            + "static volatile unsigned long long uid_poc_pending_hits = 0, uid_poc_unique_requests = 0, uid_poc_jni_lookups = 0;\n"
            + "static volatile unsigned long long uid_poc_queue_full = 0, uid_poc_lookup_failures = 0, uid_poc_dropped_packets = 0;\n"
            + END + "\n",
            1,
        )
        jni_text = jni_text.replace(
            "static jlongArray native_get_stats (JNIEnv *env, jobject thiz);\n",
            "static jlongArray native_get_stats (JNIEnv *env, jobject thiz);\n"
            "static jboolean native_set_uid_poc_callback (JNIEnv *env, jobject thiz, jobject callback);\n"
            "static jboolean native_set_uid_poc_policy_enabled (JNIEnv *env, jobject thiz, jboolean enabled);\n",
            1,
        )
        anchor = '    { "TProxyGetStats", "()[J", (void *)native_get_stats },\n'
        if jni_text.count(anchor) != 1:
            raise SystemExit("Refusing unsafe patch: JNI method table anchor is not unique")
        jni_text = jni_text.replace(
            anchor,
            anchor +
                '    { "TProxySetUidPocCallback", "(Ljava/lang/Object;)Z", (void *)native_set_uid_poc_callback },\n' +
                '    { "TProxySetUidPocPolicyEnabled", "(Z)Z", (void *)native_set_uid_poc_policy_enabled },\n',
            1,
        )
        fn_anchor = "static jboolean\nnative_is_running (JNIEnv *env, jobject thiz)\n"
        if jni_text.count(fn_anchor) != 1:
            raise SystemExit("Refusing unsafe patch: native_is_running anchor is not unique")
        functions = r'''/* SPEEDVPN_UID_POC_BEGIN */
#include <stdio.h>
#define UID_POC_CACHE_EMPTY 0
#define UID_POC_CACHE_USED 1
#define UID_POC_CACHE_DELETED 2

/* Format IPv4 as dotted decimal and IPv6 as eight hexadecimal groups. */
static void
uid_poc_format_address (const unsigned char *address, int addr_len,
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
    /* DROP entries, including unresolved owners, remain cached while the flow is active. */
    if (entry->action == UID_POC_ACTION_BLOCK)
        return age > UID_POC_FLOW_IDLE_MS;
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
            uid_poc_cache[i].action != UID_POC_ACTION_BLOCK &&
            uid_poc_cache[i].updated_ms < oldest_time) {
            oldest = i;
            oldest_time = uid_poc_cache[i].updated_ms;
        }
    }
    if (oldest < UID_POC_FLOW_TABLE_SLOTS)
        uid_poc_delete_cache_slot_locked (oldest);
}

static void
uid_poc_store_result_locked (const uid_poc_flow_key *key, int uid, int action,
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
    /* A BLOCK decision may intentionally have UID=-1 when attribution fails. */
    entry->action = action == UID_POC_ACTION_BLOCK
        ? UID_POC_ACTION_BLOCK : UID_POC_ACTION_ALLOW;
    entry->drop_logged = 0;
    entry->updated_ms = now;
    pthread_cond_broadcast (&uid_poc_condition);
    if (entry->action == UID_POC_ACTION_BLOCK) {
        __android_log_print (ANDROID_LOG_WARN, "SpeedVPN-POC-JNI",
            "UID_POLICY_RESULT uid=%d action=BLOCK packetBlocking=IMPLEMENTED",
            uid);
    } else {
        __android_log_print (ANDROID_LOG_INFO, "SpeedVPN-POC-JNI",
            "UID_POLICY_RESULT uid=%d action=ALLOW hookMode=HOOK_BLOCKING packetBlocking=IMPLEMENTED",
            uid);
    }
}

/* Caller holds uid_poc_mutex. Wait only for a bounded first-packet UID decision. */
static int
uid_poc_wait_for_decision_locked (const uid_poc_flow_key *key,
                                  unsigned long long request_id,
                                  int *should_drop)
{
    struct timespec deadline;
    if (!should_drop || clock_gettime (CLOCK_REALTIME, &deadline) != 0)
        return 0;

    deadline.tv_nsec += (long) UID_POC_DECISION_TIMEOUT_MS * 1000000L;
    deadline.tv_sec += deadline.tv_nsec / 1000000000L;
    deadline.tv_nsec %= 1000000000L;

    for (;;) {
        int found = 0;
        int slot = uid_poc_find_slot_locked (key, uid_poc_now_ms (), &found);
        uid_poc_cache_entry *entry;
        int wait_result;
        if (slot < 0 || !found)
            return 0;
        entry = &uid_poc_cache[slot];
        if (entry->request_id != request_id)
            return 0;
        if (entry->uid != UID_POC_UID_PENDING) {
            *should_drop = entry->action == UID_POC_ACTION_BLOCK;
            entry->updated_ms = uid_poc_now_ms ();
            return 1;
        }

        wait_result = pthread_cond_timedwait (&uid_poc_condition, &uid_poc_mutex, &deadline);
        if (wait_result != 0) {
            /* Drop this packet but keep the pending lookup alive. A late result may safely
             * allow an unblocked app; future packets remain fail-closed until that result. */
            __android_log_print (ANDROID_LOG_WARN, "SpeedVPN-POC-JNI",
                "UID_DECISION_TIMEOUT protocol=%d action=DROP pending_lookup_retained=1",
                key->protocol);
            return 0;
        }
    }
}

static void
uid_poc_log_metrics (unsigned long long packet_count)
{
    unsigned long long hits, misses, pending, lookups, full, failures, unique, drops;
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
    drops = __sync_add_and_fetch (&uid_poc_dropped_packets, 0ULL);
    hit_rate = (hits + misses) == 0 ? 0.0 : (100.0 * (double) hits / (double) (hits + misses));
    __android_log_print (ANDROID_LOG_INFO, "SpeedVPN-POC-JNI",
        "PACKETS=%llu CACHE_HITS=%llu CACHE_MISSES=%llu PENDING_HITS=%llu UNIQUE_LOOKUPS=%llu JNI_LOOKUPS=%llu HIT_RATE=%.2f%% QUEUE_FULL=%llu LOOKUP_FAILS=%llu DROPPED_PACKETS=%llu",
        packet_count, hits, misses, pending, unique, lookups, hit_rate, full, failures, drops);
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
        jlong packed_result = 0;
        jint uid = -1;
        int action = UID_POC_ACTION_ALLOW;
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
                packed_result = (*env)->CallLongMethod (env, uid_poc_callback, uid_poc_method,
                    (jint) request.protocol, src_array, (jint) request.src_port,
                    dst_array, (jint) request.dst_port);
                if ((*env)->ExceptionCheck (env)) {
                    __android_log_print (ANDROID_LOG_ERROR, "SpeedVPN-POC-JNI",
                                         "UID_CALLBACK_EXCEPTION protocol=%d src_port=%d dst_port=%d",
                                         request.protocol, request.src_port, request.dst_port);
                    (*env)->ExceptionDescribe (env);
                    (*env)->ExceptionClear (env);
                    uid = -1;
                    action = UID_POC_ACTION_ALLOW;
                } else {
                    unsigned long long packed_bits = (unsigned long long) packed_result;
                    uid = (jint) (packed_bits >> 32);
                    action = (packed_bits & 1ULL)
                        ? UID_POC_ACTION_BLOCK : UID_POC_ACTION_ALLOW;
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
                                     call_ok ? action :
                                         (uid_poc_policy_active ? UID_POC_ACTION_BLOCK : UID_POC_ACTION_ALLOW),
                                     uid_poc_now_ms (), request.request_id);
        pthread_cond_broadcast (&uid_poc_condition);
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
    int result, ready;
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

    /* Do not report callback registration success until this thread is JVM-attached. */
    pthread_mutex_lock (&uid_poc_mutex);
    while (!uid_poc_worker_ready && !uid_poc_worker_stop_requested)
        pthread_cond_wait (&uid_poc_condition, &uid_poc_mutex);
    ready = uid_poc_worker_ready;
    pthread_mutex_unlock (&uid_poc_mutex);
    if (!ready) {
        (void) pthread_join (uid_poc_worker_thread, NULL);
        pthread_mutex_lock (&uid_poc_mutex);
        uid_poc_worker_started = 0;
        uid_poc_worker_ready = 0;
        uid_poc_worker_stop_requested = 0;
        uid_poc_reset_locked ();
        pthread_mutex_unlock (&uid_poc_mutex);
        __android_log_print (ANDROID_LOG_ERROR, "SpeedVPN-POC-JNI",
                             "UID_WORKER_START_FAILED reason=attach_failed");
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

static jboolean
native_set_uid_poc_policy_enabled (JNIEnv *env, jobject thiz, jboolean enabled)
{
    (void) env;
    (void) thiz;
    pthread_mutex_lock (&uid_poc_mutex);
    uid_poc_policy_active = enabled == JNI_TRUE;
    /* Publish policy and flush cached ALLOW decisions in one critical section. */
    uid_poc_reset_locked ();
    pthread_cond_broadcast (&uid_poc_condition);
    pthread_mutex_unlock (&uid_poc_mutex);
    __android_log_print (ANDROID_LOG_INFO, "SpeedVPN-POC-JNI",
        "UID_POLICY_ENFORCEMENT enabled=%d cache=FLUSHED", enabled == JNI_TRUE ? 1 : 0);
    return JNI_TRUE;
}

/* Called for each candidate TCP/UDP packet by the HEV coroutine.
 * With enforcement active, wait briefly for first-packet UID attribution; timeout drops.
 * Return 1 when the decision denies the packet or cannot be resolved safely. */
int
hev_jni_uid_poc_packet (int protocol,
                        const unsigned char *src, int src_len, int src_port,
                        const unsigned char *dst, int dst_len, int dst_port)
{
    uid_poc_flow_key key;
    uid_poc_request request;
    unsigned long long now, packet_count;
    int found = 0, slot, should_drop = 0;
    unsigned long long request_id = 0;
    char src_text[64], dst_text[64], tuple_text[200];

    if (!src || !dst || src_len != dst_len ||
        (src_len != 4 && src_len != 16) ||
        (protocol != 6 && protocol != 17))
        return 0;

    /* Non-initial IP fragments have no socket ports. Deny them while UID policy is active. */
    if (src_port < 0 || dst_port < 0) {
        int deny_fragment;
        pthread_mutex_lock (&uid_poc_mutex);
        deny_fragment = uid_poc_policy_active;
        pthread_mutex_unlock (&uid_poc_mutex);
        return deny_fragment;
    }
    if (src_port > 65535 || dst_port > 65535)
        return 0;

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
        should_drop = uid_poc_policy_active;
        if (should_drop)
            __sync_add_and_fetch (&uid_poc_dropped_packets, 1ULL);
        pthread_mutex_unlock (&uid_poc_mutex);
        return should_drop;
    }
    packet_count = __sync_add_and_fetch (&uid_poc_packets, 1ULL);
    now = uid_poc_now_ms ();
    slot = uid_poc_find_slot_locked (&key, now, &found);
    if (found) {
        uid_poc_cache_entry *entry = &uid_poc_cache[slot];
        if (entry->uid == UID_POC_UID_PENDING) {
            __sync_add_and_fetch (&uid_poc_pending_hits, 1ULL);
            if (uid_poc_policy_active) {
                request_id = entry->request_id;
                if (!uid_poc_wait_for_decision_locked (&key, request_id, &should_drop)) {
                    should_drop = 1;
                    __sync_add_and_fetch (&uid_poc_dropped_packets, 1ULL);
                }
            }
        } else {
            __sync_add_and_fetch (&uid_poc_cache_hits, 1ULL);
            if (entry->action == UID_POC_ACTION_BLOCK) {
                entry->updated_ms = now;
                should_drop = 1;
                __sync_add_and_fetch (&uid_poc_dropped_packets, 1ULL);
                if (!entry->drop_logged) {
                    entry->drop_logged = 1;
                    uid_poc_format_address (src, src_len, src_text, sizeof (src_text));
                    uid_poc_format_address (dst, dst_len, dst_text, sizeof (dst_text));
                    (void) snprintf (tuple_text, sizeof (tuple_text),
                        "%s|[%s]:%d->[%s]:%d",
                        protocol == 6 ? "tcp" : "udp", src_text, src_port,
                        dst_text, dst_port);
                    __android_log_print (ANDROID_LOG_WARN, "SpeedVPN-POC-JNI",
                        "PACKET_DROPPED uid=%d tuple=%s action=DROP hookMode=HOOK_BLOCKING packetBlocking=IMPLEMENTED",
                        entry->uid, tuple_text);
                }
            } else if (entry->uid >= 0) {
                entry->updated_ms = now;
            }
        }
        pthread_mutex_unlock (&uid_poc_mutex);
        uid_poc_log_metrics (packet_count);
        return should_drop;
    }

    __sync_add_and_fetch (&uid_poc_cache_misses, 1ULL);
    if (slot < 0 || uid_poc_active_flows >= UID_POC_MAX_FLOWS) {
        uid_poc_evict_oldest_locked ();
        slot = uid_poc_find_slot_locked (&key, now, &found);
    }
    if (uid_poc_queue_count >= UID_POC_QUEUE_CAPACITY) {
        /* Overloaded attribution fails closed while block enforcement is active. */
        __sync_add_and_fetch (&uid_poc_queue_full, 1ULL);
        should_drop = uid_poc_policy_active;
        if (slot >= 0) {
            if (!found)
                uid_poc_active_flows++;
            uid_poc_cache[slot].state = UID_POC_CACHE_USED;
            uid_poc_cache[slot].key = key;
            uid_poc_cache[slot].uid = -1;
            uid_poc_cache[slot].action = should_drop ? UID_POC_ACTION_BLOCK : UID_POC_ACTION_ALLOW;
            uid_poc_cache[slot].drop_logged = 0;
            uid_poc_cache[slot].updated_ms = now;
            uid_poc_cache[slot].request_id = 0;
        }
        if (should_drop)
            __sync_add_and_fetch (&uid_poc_dropped_packets, 1ULL);
        pthread_mutex_unlock (&uid_poc_mutex);
        uid_poc_log_metrics (packet_count);
        return should_drop;
    }
    if (slot >= 0) {
        if (!found)
            uid_poc_active_flows++;
        request.request_id = __sync_add_and_fetch (&uid_poc_next_request_id, 1ULL);
        request_id = request.request_id;
        uid_poc_cache[slot].state = UID_POC_CACHE_USED;
        uid_poc_cache[slot].key = key;
        uid_poc_cache[slot].uid = UID_POC_UID_PENDING;
        uid_poc_cache[slot].action = UID_POC_ACTION_ALLOW;
        uid_poc_cache[slot].drop_logged = 0;
        uid_poc_cache[slot].updated_ms = now;
        uid_poc_cache[slot].request_id = request.request_id;
        uid_poc_queue[uid_poc_queue_tail] = request;
        uid_poc_queue_tail = (uid_poc_queue_tail + 1u) % UID_POC_QUEUE_CAPACITY;
        uid_poc_queue_count++;
        __sync_add_and_fetch (&uid_poc_unique_requests, 1ULL);
        pthread_cond_signal (&uid_poc_condition);
    } else {
        __sync_add_and_fetch (&uid_poc_queue_full, 1ULL);
        should_drop = uid_poc_policy_active;
    }
    if (uid_poc_policy_active && request_id != 0) {
        if (!uid_poc_wait_for_decision_locked (&key, request_id, &should_drop)) {
            /* A timed-out or invalidated first lookup must not leak a packet. */
            should_drop = 1;
            __sync_add_and_fetch (&uid_poc_dropped_packets, 1ULL);
        } else if (should_drop) {
            __sync_add_and_fetch (&uid_poc_dropped_packets, 1ULL);
        }
    } else if (should_drop) {
        __sync_add_and_fetch (&uid_poc_dropped_packets, 1ULL);
    }
    pthread_mutex_unlock (&uid_poc_mutex);
    uid_poc_log_metrics (packet_count);
    return should_drop;
}

/* Resolve the cached UID for one exact original app tuple. HEV calls this when it
 * creates a TCP session or a per-flow UDP association, then passes the UID to SOCKS5
 * as username#UID. A bounded wait lets the first-packet Android callback finish. */
int
hev_jni_uid_poc_resolve_uid (int protocol,
                             const unsigned char *src, int src_len, int src_port,
                             const unsigned char *dst, int dst_len, int dst_port)
{
    uid_poc_flow_key key;
    struct timespec deadline;
    int found = 0, slot, result_uid = -1;
    int exact_seen = 0, fallback_used = 0, ambiguous = 0;
    char src_text[64], dst_text[64];

    if (!src || !dst || src_len != dst_len ||
        (src_len != 4 && src_len != 16) ||
        (protocol != 6 && protocol != 17) ||
        src_port < 0 || src_port > 65535 ||
        dst_port < 0 || dst_port > 65535)
        return -1;

    uid_poc_make_key (&key, protocol, src, src_len, src_port, dst, dst_len, dst_port);
    if (clock_gettime (CLOCK_REALTIME, &deadline) != 0)
        return -1;
    deadline.tv_nsec += 1000L * 1000000L;
    deadline.tv_sec += deadline.tv_nsec / 1000000000L;
    deadline.tv_nsec %= 1000000000L;

    pthread_mutex_lock (&uid_poc_mutex);
    for (;;) {
        uid_poc_cache_entry *entry;
        int wait_result;

        slot = uid_poc_find_slot_locked (&key, uid_poc_now_ms (), &found);
        if (slot < 0 || !found)
            break;
        exact_seen = 1;
        entry = &uid_poc_cache[slot];
        if (entry->uid != UID_POC_UID_PENDING) {
            if (entry->uid >= 0 && entry->action != UID_POC_ACTION_BLOCK)
                result_uid = entry->uid;
            break;
        }

        wait_result = pthread_cond_timedwait (&uid_poc_condition, &uid_poc_mutex, &deadline);
        if (wait_result != 0)
            break;
    }

    /*
     * The transparent UDP listener may expose a destination tuple that differs
     * from the original TUN packet on some lwIP paths. If the exact 5-tuple was
     * not cached, attribute UDP only when every live cache entry for the same
     * source IP:port resolves to the same unblocked UID. Ambiguous, blocked,
     * unresolved, and timed-out entries remain fail-closed.
     */
    if (protocol == 17 && !exact_seen) {
        for (;;) {
            unsigned int i;
            unsigned long long now = uid_poc_now_ms ();
            int candidate_uid = -1, candidate_seen = 0, pending_seen = 0;
            int wait_result;

            ambiguous = 0;
            for (i = 0; i < UID_POC_FLOW_TABLE_SLOTS; i++) {
                uid_poc_cache_entry *entry = &uid_poc_cache[i];
                int source_matches;

                if (entry->state != UID_POC_CACHE_USED ||
                    uid_poc_entry_expired (entry, now) ||
                    entry->key.protocol != protocol ||
                    entry->key.addr_len != src_len)
                    continue;

                source_matches =
                    (entry->key.port_a == (unsigned short) src_port &&
                     memcmp (entry->key.a, src, (size_t) src_len) == 0) ||
                    (entry->key.port_b == (unsigned short) src_port &&
                     memcmp (entry->key.b, src, (size_t) src_len) == 0);
                if (!source_matches)
                    continue;

                if (entry->uid == UID_POC_UID_PENDING) {
                    pending_seen = 1;
                    continue;
                }
                if (entry->uid < 0 || entry->action == UID_POC_ACTION_BLOCK) {
                    ambiguous = 1;
                    break;
                }
                if (!candidate_seen) {
                    candidate_uid = entry->uid;
                    candidate_seen = 1;
                } else if (candidate_uid != entry->uid) {
                    ambiguous = 1;
                    break;
                }
            }

            if (ambiguous)
                break;
            if (pending_seen) {
                wait_result = pthread_cond_timedwait (&uid_poc_condition,
                                                      &uid_poc_mutex, &deadline);
                if (wait_result == 0)
                    continue;
                break;
            }
            if (candidate_seen) {
                result_uid = candidate_uid;
                fallback_used = 1;
            }
            break;
        }
    }

    pthread_mutex_unlock (&uid_poc_mutex);
    uid_poc_format_address (src, src_len, src_text, sizeof (src_text));
    uid_poc_format_address (dst, dst_len, dst_text, sizeof (dst_text));
    __android_log_print (ANDROID_LOG_INFO, "SpeedVPN-POC-JNI",
        "UID_SESSION_RESOLVE protocol=%d tuple=%s:%d->%s:%d exact=%d fallback=%d ambiguous=%d uid=%d",
        protocol, src_text, src_port, dst_text, dst_port,
        exact_seen, fallback_used, ambiguous, result_uid);
    return result_uid;
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

    # Refuse to produce an APK if the asynchronous worker or actual native DROP path
    # was lost in a source update. Logging-only UID observation is not enforcement.
    required_native_markers = (
        "AttachCurrentThread (java_vm, &env, NULL)",
        "DetachCurrentThread (java_vm)",
        "UID_WORKER_READY stack=1048576",
        "uid_poc_store_result_locked",
        "hev_jni_uid_poc_resolve_uid",
        "UID_SESSION_RESOLVE protocol=%d tuple=%s:%d",
        "native_set_uid_poc_policy_enabled",
        "UID_POC_DECISION_TIMEOUT_MS",
        "pthread_cond_timedwait (&uid_poc_condition",
        "PACKET_DROPPED uid=%d tuple=%s",
        "if (uid_poc_report_packet (buf))",
    )
    for marker in required_native_markers:
        if marker == "if (uid_poc_report_packet (buf))":
            source = Path(__file__).read_text()
        elif marker == "Normalize IPv4-mapped IPv6 tuples to their four-byte IPv4 form.":
            # Address normalization lives in the generated SOCKS session UID bridge,
            # not in the JNI translation unit.
            source = session_c_text
        else:
            source = jni_text
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


    # Carry native-resolved ownership through the HEV SOCKS5 session authentication. The UID
    # cache is keyed by the original 5-tuple; destination-only association is not authoritative.
    session_h_text = session_h.read_text()
    session_h_text = replace_text_once(
        session_h_text,
        "#define HEV_SOCKS5_SESSION(p)",
        "#include <lwip/ip_addr.h>\n\n#define HEV_SOCKS5_SESSION(p)",
        "session IP address type include",
    )
    session_h_text = replace_text_once(
        session_h_text,
        "    HevSocks5Session *self;\n};",
        "    HevSocks5Session *self;\n    int uid; /* Exact UID captured from the native 5-tuple cache, or -1. */\n};",
        "session UID storage",
    )
    session_h_text = replace_text_once(
        session_h_text,
        "    HevListNode *(*get_node) (HevSocks5Session *self);\n};",
        "    HevListNode *(*get_node) (HevSocks5Session *self);\n    int (*get_uid) (HevSocks5Session *self);\n};",
        "session UID interface",
    )
    session_h_text = replace_text_once(
        session_h_text,
        "void hev_socks5_session_terminate (HevSocks5Session *self);\n",
        "void hev_socks5_session_terminate (HevSocks5Session *self);\n"
        "int hev_socks5_session_resolve_uid (int protocol, const ip_addr_t *src_ip, u16_t src_port,\n"
        "                                    const ip_addr_t *dst_ip, u16_t dst_port);\n",
        "session UID resolver declaration",
    )
    session_h.write_text(session_h_text)

    session_c_text = session_c.read_text()
    session_uid_helper = r'''#include <stdio.h>
#ifdef __ANDROID__
extern int hev_jni_uid_poc_resolve_uid (int protocol,
                                        const unsigned char *src, int src_len, int src_port,
                                        const unsigned char *dst, int dst_len, int dst_port);
#endif

static int
hev_socks5_session_copy_ip (const ip_addr_t *addr, unsigned char *out)
{
    int i;
    if (!addr || !out)
        return 0;
    if (addr->type == IPADDR_TYPE_V4) {
        for (i = 0; i < 4; i++)
            out[i] = ip4_addr_get_byte (ip_2_ip4 (addr), i);
        return 4;
    }
    if (addr->type == IPADDR_TYPE_V6) {
        /* ip6_addr_t stores its four network-order words contiguously. */
        memcpy (out, ip_2_ip6 (addr)->addr, 16);
        /*
         * Normalize IPv4-mapped IPv6 tuples to their four-byte IPv4 form.
         * The original TUN packet parser records IPv4 addresses as four bytes;
         * retaining ::ffff:a.b.c.d here would create a different native cache
         * key for the same socket and make SOCKS UID attribution miss.
         */
        if (out[0] == 0 && out[1] == 0 && out[2] == 0 && out[3] == 0 &&
            out[4] == 0 && out[5] == 0 && out[6] == 0 && out[7] == 0 &&
            out[8] == 0 && out[9] == 0 && out[10] == 0xff && out[11] == 0xff) {
            memmove (out, out + 12, 4);
            return 4;
        }
        return 16;
    }
    return 0;
}

int
hev_socks5_session_resolve_uid (int protocol,
                                const ip_addr_t *src_ip, u16_t src_port,
                                const ip_addr_t *dst_ip, u16_t dst_port)
{
#ifdef __ANDROID__
    unsigned char src[16], dst[16];
    int src_len, dst_len;
    src_len = hev_socks5_session_copy_ip (src_ip, src);
    dst_len = hev_socks5_session_copy_ip (dst_ip, dst);
    if (src_len == 0 || src_len != dst_len)
        return -1;
    return hev_jni_uid_poc_resolve_uid (protocol, src, src_len, (int) src_port,
                                        dst, dst_len, (int) dst_port);
#else
    (void) protocol;
    (void) src_ip;
    (void) src_port;
    (void) dst_ip;
    (void) dst_port;
    return -1;
#endif
}

'''
    session_c_text = replace_text_once(
        session_c_text,
        '#include "hev-socks5-session.h"\n',
        '#include "hev-socks5-session.h"\n\n' + session_uid_helper,
        "session UID resolver implementation",
    )
    session_c_text = replace_text_once(
        session_c_text,
        "    HevConfigServer *srv;\n    int res;\n",
        "    HevConfigServer *srv;\n    char flow_username[256];\n"
        "    const char *auth_username;\n    int flow_uid;\n    int res;\n",
        "per-session SOCKS auth locals",
    )
    session_c_text = replace_text_once(
        session_c_text,
        "    srv = hev_config_get_socks5_server ();\n\n    res = hev_socks5_client_connect",
        "    srv = hev_config_get_socks5_server ();\n"
        "    iface = HEV_OBJECT_GET_IFACE (self, HEV_SOCKS5_SESSION_TYPE);\n"
        "    flow_uid = iface && iface->get_uid ? iface->get_uid (self) : -1;\n"
        "    auth_username = srv->user;\n\n"
        "    res = hev_socks5_client_connect",
        "resolve session UID before SOCKS auth",
    )
    session_c_text = replace_text_once(
        session_c_text,
        "    if (srv->user && srv->pass) {\n"
        "        hev_socks5_client_set_auth (HEV_SOCKS5_CLIENT (self), srv->user,\n"
        "                                    srv->pass);\n"
        "        LOG_D (\"%p socks5 client auth %s:%s\", self, srv->user, srv->pass);\n"
        "    }",
        "    if (srv->user && srv->pass) {\n"
        "        if (flow_uid >= 0) {\n"
        "            (void) snprintf (flow_username, sizeof (flow_username), \"%s#%d\",\n"
        "                             srv->user, flow_uid);\n"
        "            auth_username = flow_username;\n"
        "        }\n"
        "        hev_socks5_client_set_auth (HEV_SOCKS5_CLIENT (self), auth_username,\n"
        "                                    srv->pass);\n"
        "        LOG_D (\"%p socks5 client auth uid=%d\", self, flow_uid);\n"
        "    }",
        "per-session UID in SOCKS username",
    )
    session_c.write_text(session_c_text)

    session_tcp_c_text = session_tcp_c.read_text()
    session_tcp_c_text = replace_text_once(
        session_tcp_c_text,
        "int\nhev_socks5_session_tcp_construct (HevSocks5SessionTCP *self,",
        "static int\nhev_socks5_session_tcp_get_uid (HevSocks5Session *base)\n"
        "{\n    HevSocks5SessionTCP *self = HEV_SOCKS5_SESSION_TCP (base);\n"
        "    return self->data.uid;\n}\n\n"
        "int\nhev_socks5_session_tcp_construct (HevSocks5SessionTCP *self,",
        "TCP UID accessor",
    )
    session_tcp_c_text = replace_text_once(
        session_tcp_c_text,
        "    self->data.self = self;\n\n    return 0;",
        "    self->data.self = self;\n"
        "    self->data.uid = hev_socks5_session_resolve_uid (6, &pcb->remote_ip,\n"
        "        pcb->remote_port, &pcb->local_ip, pcb->local_port);\n\n    return 0;",
        "capture TCP flow UID",
    )
    session_tcp_c_text = replace_text_once(
        session_tcp_c_text,
        "        siptr->get_node = hev_socks5_session_tcp_get_node;\n",
        "        siptr->get_node = hev_socks5_session_tcp_get_node;\n"
        "        siptr->get_uid = hev_socks5_session_tcp_get_uid;\n",
        "register TCP UID accessor",
    )
    session_tcp_c.write_text(session_tcp_c_text)

    session_udp_h_text = session_udp_h.read_text()
    session_udp_h_text = replace_text_once(
        session_udp_h_text,
        "#include <hev-socks5-client-udp.h>",
        "#include <lwip/pbuf.h>\n#include <hev-socks5-client-udp.h>",
        "UDP initial datagram pbuf type",
    )
    session_udp_h_text = replace_text_once(
        session_udp_h_text,
        "int hev_socks5_session_udp_construct (HevSocks5SessionUDP *self,\n"
        "                                      struct udp_pcb *pcb, HevTaskMutex *mutex);",
        "int hev_socks5_session_udp_construct (HevSocks5SessionUDP *self,\n"
        "                                      struct udp_pcb *pcb, const ip_addr_t *src_ip,\n"
        "                                      u16_t src_port, HevTaskMutex *mutex);",
        "UDP constructor UID parameters",
    )
    session_udp_h_text = replace_text_once(
        session_udp_h_text,
        "HevSocks5SessionUDP *hev_socks5_session_udp_new (struct udp_pcb *pcb,\n"
        "                                                 HevTaskMutex *mutex);",
        "HevSocks5SessionUDP *hev_socks5_session_udp_new (struct udp_pcb *pcb,\n"
        "                                                 const ip_addr_t *src_ip,\n"
        "                                                 u16_t src_port, HevTaskMutex *mutex);",
        "UDP factory UID parameters",
    )
    session_udp_h_text = replace_text_once(
        session_udp_h_text,
        "HevSocks5SessionUDP *hev_socks5_session_udp_new (struct udp_pcb *pcb,\n"
        "                                                 const ip_addr_t *src_ip,\n"
        "                                                 u16_t src_port, HevTaskMutex *mutex);",
        "HevSocks5SessionUDP *hev_socks5_session_udp_new (struct udp_pcb *pcb,\n"
        "                                                 const ip_addr_t *src_ip,\n"
        "                                                 u16_t src_port, HevTaskMutex *mutex);\n"
        "int hev_socks5_session_udp_enqueue_initial (HevSocks5SessionUDP *self, struct pbuf *p);\n",
        "UDP first packet enqueue declaration",
    )
    session_udp_h.write_text(session_udp_h_text)

    session_udp_c_text = session_udp_c.read_text()
    session_udp_c_text = replace_text_once(
        session_udp_c_text,
        "hev_socks5_session_udp_construct (HevSocks5SessionUDP *self,\n"
        "                                  struct udp_pcb *pcb, HevTaskMutex *mutex)",
        "hev_socks5_session_udp_construct (HevSocks5SessionUDP *self,\n"
        "                                  struct udp_pcb *pcb, const ip_addr_t *src_ip,\n"
        "                                  u16_t src_port, HevTaskMutex *mutex)",
        "UDP constructor UID parameters",
    )
    session_udp_c_text = replace_text_once(
        session_udp_c_text,
        "hev_socks5_session_udp_construct (self, pcb, mutex)",
        "hev_socks5_session_udp_construct (self, pcb, src_ip, src_port, mutex)",
        "pass UDP source tuple",
    )
    session_udp_c_text = replace_text_once(
        session_udp_c_text,
        "hev_socks5_session_udp_new (struct udp_pcb *pcb, HevTaskMutex *mutex)",
        "hev_socks5_session_udp_new (struct udp_pcb *pcb, const ip_addr_t *src_ip,\n"
        "                            u16_t src_port, HevTaskMutex *mutex)",
        "UDP factory UID parameters",
    )
    session_udp_c_text = replace_text_once(
        session_udp_c_text,
        "    self->data.self = self;\n\n    return 0;",
        "    self->data.self = self;\n"
        "        /*\n"
        "         * On the pinned PRETEND_UDP path, addr/port is the destination tuple, while\n"
        "         * pcb->local_ip/local_port carries the original TUN source endpoint. Use\n"
        "         * both endpoints so the session lookup matches the exact packet-hook key;\n"
        "         * never synthesize a same-endpoint tuple for UDP attribution.\n"
        "         */\n"
        "    self->data.uid = hev_socks5_session_resolve_uid (17, &pcb->local_ip,\n"
        "        pcb->local_port, src_ip, src_port);\n"
        "\n    return 0;",
        "capture UDP flow UID",
    )
    session_udp_c_text = replace_text_once(
        session_udp_c_text,
        "static HevListNode *\nhev_socks5_session_udp_get_node (HevSocks5Session *base)",
        "static int\nhev_socks5_session_udp_get_uid (HevSocks5Session *base)\n"
        "{\n    HevSocks5SessionUDP *self = HEV_SOCKS5_SESSION_UDP (base);\n"
        "    return self->data.uid;\n}\n\n"
        "static HevListNode *\nhev_socks5_session_udp_get_node (HevSocks5Session *base)",
        "UDP UID accessor",
    )
    session_udp_c_text = replace_text_once(
        session_udp_c_text,
        "        siptr->get_node = hev_socks5_session_udp_get_node;\n",
        "        siptr->get_node = hev_socks5_session_udp_get_node;\n"
        "        siptr->get_uid = hev_socks5_session_udp_get_uid;\n",
        "register UDP UID accessor",
    )
    session_udp_c_text = replace_text_once(
        session_udp_c_text,
        "HevSocks5SessionUDP *\nhev_socks5_session_udp_new (struct udp_pcb *pcb, const ip_addr_t *src_ip,\n"
        "                            u16_t src_port, HevTaskMutex *mutex)",
        "int\n"
        "hev_socks5_session_udp_enqueue_initial (HevSocks5SessionUDP *self, struct pbuf *p)\n"
        "{\n"
        "    HevSocks5UDPFrame *frame;\n"
        "    int res;\n\n"
        "    if (!self || !p) {\n"
        "        if (p) pbuf_free (p);\n"
        "        return -1;\n"
        "    }\n"
        "    if (self->frames > UDP_POOL_SIZE) {\n"
        "        pbuf_free (p);\n"
        "        return -1;\n"
        "    }\n"
        "    frame = hev_malloc (sizeof (HevSocks5UDPFrame));\n"
        "    if (!frame) {\n"
        "        pbuf_free (p);\n"
        "        return -1;\n"
        "    }\n"
        "    memset (&frame->node, 0, sizeof (frame->node));\n"
        "    res = hev_socks5_addr_from_lwip (&frame->addr, &self->pcb->local_ip, self->pcb->local_port);\n"
        "    if (res < 0) {\n"
        "        pbuf_free (p);\n"
        "        hev_free (frame);\n"
        "        return -1;\n"
        "    }\n"
        "    frame->data = p;\n"
        "    if (frame->addr.atype == HEV_SOCKS5_ADDR_TYPE_NAME) {\n"
        "        self->addr = ip_2_ip4 (&self->pcb->local_ip)->addr;\n"
        "        self->port = self->pcb->local_port;\n"
        "    }\n"
        "    self->frames++;\n"
        "    hev_list_add_tail (&self->frame_list, &frame->node);\n"
        "    return 0;\n"
        "}\n\n"
        "HevSocks5SessionUDP *\nhev_socks5_session_udp_new (struct udp_pcb *pcb, const ip_addr_t *src_ip,\n"
        "                            u16_t src_port, HevTaskMutex *mutex)",
        "preserve first UDP datagram",
    )
    session_udp_c.write_text(session_udp_c_text)

    tunnel_text = tunnel_c.read_text()
    tunnel_text = replace_text_once(
        tunnel_text,
        "udp = hev_socks5_session_udp_new (pcb, &mutex);",
        "udp = hev_socks5_session_udp_new (pcb, addr, port, &mutex);",
        "pass original UDP tuple into session",
    )
    tunnel_text = replace_text_once(
        tunnel_text,
        "    hev_socks5_session_set_task (HEV_SOCKS5_SESSION (udp), task);\n"
        "    node = hev_socks5_session_get_node (HEV_SOCKS5_SESSION (udp));",
        "    hev_socks5_session_set_task (HEV_SOCKS5_SESSION (udp), task);\n"
        "    if (hev_socks5_session_udp_enqueue_initial (udp, p) < 0)\n"
        "        LOG_W (\"socks5 tunnel udp initial packet enqueue failed\");\n"
        "    node = hev_socks5_session_get_node (HEV_SOCKS5_SESSION (udp));",
        "enqueue first UDP packet before starting session task",
    )
    tunnel_c.write_text(tunnel_text)

    # Refuse a broken generated native patch before Gradle builds an APK.
    for source_path, marker in (
        (session_c, "hev_socks5_session_resolve_uid"),
        (session_c, "Normalize IPv4-mapped IPv6 tuples to their four-byte IPv4 form."),
        (session_tcp_c, "self->data.uid = hev_socks5_session_resolve_uid (6"),
        (session_udp_c, "self->data.uid = hev_socks5_session_resolve_uid (17, &pcb->local_ip,"),
        (session_udp_c, "hev_socks5_session_udp_enqueue_initial (HevSocks5SessionUDP *self, struct pbuf *p)"),
        (session_udp_c, "siptr->get_uid = hev_socks5_session_udp_get_uid;"),
        (tunnel_c, "hev_socks5_session_udp_new (pcb, addr, port, &mutex)"),
        (tunnel_c, "hev_socks5_session_udp_enqueue_initial (udp, p)"),
    ):
        if marker not in source_path.read_text():
            raise SystemExit(f"Refusing incomplete per-session UID bridge: missing {marker}")

    print("OK - per-session UID propagation, UID detection hook, and native diagnostics applied")


if __name__ == "__main__":
    main()
