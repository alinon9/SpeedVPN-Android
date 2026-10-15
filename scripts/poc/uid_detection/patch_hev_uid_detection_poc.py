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

    start = jni_text.find("native_start_service (")
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

    # The packet hook runs on HEV's lwIP I/O coroutine (64 KiB default), not a
    # per-session task. Give that coroutine the same 128 KiB headroom required by ART.
    replace_once(
        tunnel_c,
        "    task_lwip_io = hev_task_new (-1);\n",
        "    /* POC callback crosses into ART from this coroutine: use a larger stack. */\n"
        "    task_lwip_io = hev_task_new (131072);\n",
    )

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
            + "static pthread_mutex_t uid_poc_mutex = PTHREAD_MUTEX_INITIALIZER;\n"
            + "static jobject uid_poc_callback;\n"
            + "static jmethodID uid_poc_method;\n"
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
static jboolean
native_set_uid_poc_callback (JNIEnv *env, jobject thiz, jobject callback)
{
    jobject replacement = NULL;
    jobject previous;
    jmethodID method = NULL;

    if (callback) {
        jclass klass = (*env)->GetObjectClass (env, callback);
        if (!klass)
            return JNI_FALSE;
        method = (*env)->GetMethodID (env, klass, "onTunPacket", "(I[BI[BI)V");
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

    pthread_mutex_lock (&uid_poc_mutex);
    previous = uid_poc_callback;
    uid_poc_callback = replacement;
    uid_poc_method = method;
    pthread_mutex_unlock (&uid_poc_mutex);

    if (previous)
        (*env)->DeleteGlobalRef (env, previous);
    return (!callback || replacement) ? JNI_TRUE : JNI_FALSE;
}

/* Called only in POC builds from the native TUN read loop. */
void
hev_jni_uid_poc_packet (int protocol,
                       const unsigned char *src, int src_len, int src_port,
                       const unsigned char *dst, int dst_len, int dst_port)
{
    JNIEnv *env = NULL;
    jobject callback = NULL;
    jmethodID method = NULL;
    jbyteArray src_array = NULL, dst_array = NULL;
    jint status;
    static unsigned long long callback_sequence = 0;
    unsigned long long callback_id = __sync_add_and_fetch (&callback_sequence, 1);
    int log_callback = callback_id <= 16 || (callback_id % 128) == 0;

    native_install_alt_stack_for_thread ();
    if (log_callback)
        __android_log_print (ANDROID_LOG_DEBUG, "SpeedVPN-POC-JNI",
                             "C_CALLBACK_ENTRY id=%llu protocol=%d src_port=%d dst_port=%d",
                             callback_id, protocol, src_port, dst_port);
    if (!java_vm) {
        if (log_callback)
            __android_log_print (ANDROID_LOG_ERROR, "SpeedVPN-POC-JNI", "C_CALLBACK_EXIT id=%llu outcome=no_java_vm", callback_id);
        return;
    }
    status = (*java_vm)->GetEnv (java_vm, (void **) &env, JNI_VERSION_1_4);
    if (status == JNI_EDETACHED) {
        if ((*java_vm)->AttachCurrentThread (java_vm, &env, NULL) != JNI_OK)
            return;
        /* Existing key destructor detaches this native worker on thread exit. */
        pthread_setspecific (current_jni_env, env);
    } else if (status != JNI_OK) {
        return;
    }

    pthread_mutex_lock (&uid_poc_mutex);
    if (uid_poc_callback && uid_poc_method) {
        callback = (*env)->NewLocalRef (env, uid_poc_callback);
        method = uid_poc_method;
    }
    pthread_mutex_unlock (&uid_poc_mutex);
    if (!callback || !method)
        goto done;

    src_array = (*env)->NewByteArray (env, src_len);
    dst_array = (*env)->NewByteArray (env, dst_len);
    if (!src_array || !dst_array)
        goto done;
    (*env)->SetByteArrayRegion (env, src_array, 0, src_len, (const jbyte *) src);
    (*env)->SetByteArrayRegion (env, dst_array, 0, dst_len, (const jbyte *) dst);
    if ((*env)->ExceptionCheck (env)) {
        (*env)->ExceptionClear (env);
        goto done;
    }

    (*env)->CallVoidMethod (env, callback, method, (jint) protocol,
                            src_array, (jint) src_port,
                            dst_array, (jint) dst_port);
    {
        jboolean exception_pending = (*env)->ExceptionCheck (env);
        if (log_callback)
            __android_log_print (exception_pending ? ANDROID_LOG_ERROR : ANDROID_LOG_DEBUG,
                                 "SpeedVPN-POC-JNI",
                                 "C_CALLBACK_AFTER_CALL id=%llu exception_pending=%d",
                                 callback_id, exception_pending ? 1 : 0);
        if (exception_pending) {
            (*env)->ExceptionDescribe (env);
            (*env)->ExceptionClear (env);
        }
    }
    if (log_callback)
        __android_log_print (ANDROID_LOG_DEBUG, "SpeedVPN-POC-JNI",
                             "C_CALLBACK_EXIT id=%llu outcome=returned", callback_id);

done:
    if (src_array)
        (*env)->DeleteLocalRef (env, src_array);
    if (dst_array)
        (*env)->DeleteLocalRef (env, dst_array);
    if (callback)
        (*env)->DeleteLocalRef (env, callback);
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

    # Never retain JNIEnv*: it is valid only for the current OS thread and this call.
    # The pthread key stores an attachment marker solely so its destructor detaches.
    jni_text = replace_text_once(
        jni_text,
        "        pthread_setspecific (current_jni_env, env);",
        "        if (pthread_setspecific (current_jni_env, (void *) 1) != 0) {\n"
        "            (void) (*java_vm)->DetachCurrentThread (java_vm);\n"
        "            return;\n"
        "        }",
        "JNI attachment marker",
    )

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
