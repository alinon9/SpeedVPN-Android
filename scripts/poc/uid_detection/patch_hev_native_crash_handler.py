#!/usr/bin/env python3
"""Apply POC-only native crash breadcrumbs to the pinned hev-socks5-tunnel checkout.

The signal handler uses write(2) for crash output, saves the last named native
stage, records fault/PC information, and attempts a best-effort address backtrace.
Android's tombstone/logcat remains the authoritative fully-symbolized trace.
"""
from __future__ import annotations

from pathlib import Path
import sys

BEGIN = "/* SPEEDVPN_NATIVE_CRASH_POC_BEGIN */"
END = "/* SPEEDVPN_NATIVE_CRASH_POC_END */"


def replace_once(path: Path, old: str, new: str) -> None:
    text = path.read_text(encoding="utf-8")
    if new in text:
        return
    count = text.count(old)
    if count != 1:
        raise SystemExit(f"Refusing unsafe patch: expected one anchor in {path}, found {count}")
    path.write_text(text.replace(old, new, 1), encoding="utf-8")


def main() -> None:
    if len(sys.argv) != 2:
        raise SystemExit("usage: patch_hev_native_crash_handler.py <hev-socks5-tunnel-dir>")

    root = Path(sys.argv[1]).resolve()
    jni_c = root / "src/hev-jni.c"
    tunnel_c = root / "src/hev-socks5-tunnel.c"
    for required in (jni_c, tunnel_c):
        if not required.is_file():
            raise SystemExit(f"Missing pinned hev source file: {required}")

    jni_includes = r'''
#include <fcntl.h>
#include <limits.h>
#include <stdint.h>
#include <sys/stat.h>
#include <sys/syscall.h>
#include <ucontext.h>
#include <unistd.h>
#include <unwind.h>
'''
    replace_once(jni_c, "#include <string.h>\n", "#include <string.h>\n" + jni_includes)

    native_code = r'''
/* SPEEDVPN_NATIVE_CRASH_POC_BEGIN */
#ifndef PATH_MAX
#define PATH_MAX 4096
#endif
#define UID_POC_STAGE_CAP 192
#define UID_POC_NATIVE_FRAMES 32

static char uid_poc_diag_dir[PATH_MAX] =
    "/data/data/com.speedvpn.app/files/uid-poc-diagnostics";
static char uid_poc_stage_path[PATH_MAX];
static char uid_poc_last_stage_path[PATH_MAX];
static char uid_poc_crash_path[PATH_MAX];
static int uid_poc_native_trace_fd = -1;
static int uid_poc_native_crash_fd = -1;
static volatile sig_atomic_t uid_poc_native_crashing;
static char uid_poc_last_stage[UID_POC_STAGE_CAP] = "JNI_OnLoad.begin";

static void
uid_poc_write_all (int fd, const char *buffer, size_t length)
{
    while (fd >= 0 && length) {
        ssize_t written = write (fd, buffer, length);
        if (written <= 0)
            return;
        buffer += written;
        length -= (size_t) written;
    }
}

static size_t
uid_poc_strlen_signal_safe (const char *text)
{
    size_t length = 0;
    if (!text)
        return 0;
    while (text[length])
        length++;
    return length;
}

static void
uid_poc_write_hex (int fd, uintptr_t value)
{
    static const char digits[] = "0123456789abcdef";
    char output[2 * sizeof (uintptr_t)];
    size_t i;
    for (i = 0; i < sizeof (output); i++) {
        unsigned int shift = (unsigned int) ((sizeof (output) - i - 1) * 4);
        output[i] = digits[(value >> shift) & 0xf];
    }
    uid_poc_write_all (fd, output, sizeof (output));
}

static void
uid_poc_write_named_hex (int fd, const char *label, uintptr_t value)
{
    uid_poc_write_all (fd, label, uid_poc_strlen_signal_safe (label));
    uid_poc_write_hex (fd, value);
    uid_poc_write_all (fd, "\n", 1);
}

typedef struct _UidPocCrashTrace UidPocCrashTrace;
struct _UidPocCrashTrace {
    int fd;
    int count;
};

static _Unwind_Reason_Code
uid_poc_collect_frame (struct _Unwind_Context *context, void *argument)
{
    UidPocCrashTrace *trace = argument;
    uintptr_t pc = (uintptr_t) _Unwind_GetIP (context);
    if (!pc)
        return _URC_END_OF_STACK;
    uid_poc_write_all (trace->fd, "  pc=0x", 7);
    uid_poc_write_hex (trace->fd, pc);
    uid_poc_write_all (trace->fd, "\n", 1);
    trace->count++;
    return trace->count >= UID_POC_NATIVE_FRAMES ? _URC_END_OF_STACK :
                                                   _URC_NO_REASON;
}

static void
uid_poc_write_last_stage (int fd)
{
    uid_poc_write_all (fd, "last_native_stage=", 18);
    uid_poc_write_all (fd, uid_poc_last_stage,
                       uid_poc_strlen_signal_safe (uid_poc_last_stage));
    uid_poc_write_all (fd, "\n", 1);
}

static void
uid_poc_native_signal_handler (int signal_number, siginfo_t *info, void *context)
{
    int fd = uid_poc_native_crash_fd;
    if (uid_poc_native_crashing)
        _exit (128 + signal_number);
    uid_poc_native_crashing = 1;

    if (fd >= 0) {
        UidPocCrashTrace trace;
        ucontext_t *uc = (ucontext_t *) context;

        uid_poc_write_all (fd, "\n*** NATIVE FATAL SIGNAL ***\n", 29);
        uid_poc_write_all (fd, "signal=", 7);
        /* Signal numbers are small positive integers; emit decimal without snprintf. */
        {
            char number[12];
            size_t used = 0;
            unsigned int n = signal_number < 0 ? 0U : (unsigned int) signal_number;
            do {
                number[used++] = (char) ('0' + (n % 10U));
                n /= 10U;
            } while (n && used < sizeof (number));
            while (used)
                uid_poc_write_all (fd, &number[--used], 1);
        }
        uid_poc_write_all (fd, "\n", 1);
        uid_poc_write_last_stage (fd);
        if (info)
            uid_poc_write_named_hex (fd, "fault_address=0x",
                                     (uintptr_t) info->si_addr);

#if defined(__aarch64__)
        if (uc) {
            uid_poc_write_named_hex (fd, "fault_pc=0x",
                                     (uintptr_t) uc->uc_mcontext.pc);
            uid_poc_write_named_hex (fd, "link_register=0x",
                                     (uintptr_t) uc->uc_mcontext.regs[30]);
            uid_poc_write_named_hex (fd, "stack_pointer=0x",
                                     (uintptr_t) uc->uc_mcontext.sp);
        }
#elif defined(__arm__)
        if (uc) {
            uid_poc_write_named_hex (fd, "fault_pc=0x",
                                     (uintptr_t) uc->uc_mcontext.arm_pc);
            uid_poc_write_named_hex (fd, "link_register=0x",
                                     (uintptr_t) uc->uc_mcontext.arm_lr);
            uid_poc_write_named_hex (fd, "stack_pointer=0x",
                                     (uintptr_t) uc->uc_mcontext.arm_sp);
        }
#elif defined(__x86_64__)
        if (uc) {
            uid_poc_write_named_hex (fd, "fault_pc=0x",
                                     (uintptr_t) uc->uc_mcontext.gregs[16]);
            uid_poc_write_named_hex (fd, "stack_pointer=0x",
                                     (uintptr_t) uc->uc_mcontext.gregs[15]);
        }
#elif defined(__i386__)
        if (uc) {
            uid_poc_write_named_hex (fd, "fault_pc=0x",
                                     (uintptr_t) uc->uc_mcontext.gregs[14]);
            uid_poc_write_named_hex (fd, "stack_pointer=0x",
                                     (uintptr_t) uc->uc_mcontext.gregs[7]);
        }
#endif

        uid_poc_write_all (fd,
            "best_effort_native_backtrace_addresses:\n", 39);
        trace.fd = fd;
        trace.count = 0;
        /* Best effort only in a fatal signal handler; see Android tombstone for symbols. */
        (void) _Unwind_Backtrace (uid_poc_collect_frame, &trace);
        uid_poc_write_all (fd,
            "end_native_backtrace; consult Android tombstone/logcat for full symbols\n", 69);
        (void) fsync (fd);
    }

    /* SA_RESETHAND restores the default action. Re-deliver to this thread so
       Android's debuggerd can still collect its native tombstone. */
    (void) syscall (SYS_tgkill, syscall (SYS_getpid), syscall (SYS_gettid),
                    signal_number);
    _exit (128 + signal_number);
}

static void
hev_jni_uid_poc_stage (const char *stage)
{
    size_t i = 0;
    char line[UID_POC_STAGE_CAP + 16];
    int count, fd;

    if (!stage)
        stage = "(null)";
    while (i < UID_POC_STAGE_CAP - 1 && stage[i]) {
        uid_poc_last_stage[i] = stage[i];
        i++;
    }
    uid_poc_last_stage[i] = '\0';

    count = snprintf (line, sizeof (line), "STAGE %s\n", uid_poc_last_stage);
    if (count > 0) {
        fd = open (uid_poc_last_stage_path, O_WRONLY | O_CREAT | O_TRUNC, 0600);
        if (fd >= 0) {
            uid_poc_write_all (fd, line,
                               (size_t) count < sizeof (line) ? (size_t) count :
                                                               sizeof (line));
            (void) fsync (fd);
            close (fd);
        }
        if (uid_poc_native_trace_fd >= 0) {
            uid_poc_write_all (uid_poc_native_trace_fd, line,
                               (size_t) count < sizeof (line) ? (size_t) count :
                                                               sizeof (line));
            (void) fsync (uid_poc_native_trace_fd);
        }
    }
}

static int
uid_poc_open_diagnostics (const char *directory, int reset_trace)
{
    if (!directory || !directory[0])
        directory = "/data/data/com.speedvpn.app/files/uid-poc-diagnostics";

    if (snprintf (uid_poc_diag_dir, sizeof (uid_poc_diag_dir), "%s", directory) >=
        (int) sizeof (uid_poc_diag_dir))
        return 0;
    if (mkdir (uid_poc_diag_dir, 0700) < 0 && errno != EEXIST)
        return 0;
    if (snprintf (uid_poc_stage_path, sizeof (uid_poc_stage_path),
                  "%s/SpeedVPN-POC-Native-Stage.txt", uid_poc_diag_dir) >=
        (int) sizeof (uid_poc_stage_path))
        return 0;
    if (snprintf (uid_poc_last_stage_path, sizeof (uid_poc_last_stage_path),
                  "%s/SpeedVPN-POC-Native-Last-Stage.txt", uid_poc_diag_dir) >=
        (int) sizeof (uid_poc_last_stage_path))
        return 0;
    if (snprintf (uid_poc_crash_path, sizeof (uid_poc_crash_path),
                  "%s/SpeedVPN-POC-Native-Crash.txt", uid_poc_diag_dir) >=
        (int) sizeof (uid_poc_crash_path))
        return 0;

    if (uid_poc_native_trace_fd >= 0)
        close (uid_poc_native_trace_fd);
    if (uid_poc_native_crash_fd >= 0)
        close (uid_poc_native_crash_fd);

    uid_poc_native_trace_fd = open (uid_poc_stage_path,
        O_WRONLY | O_CREAT | O_APPEND | O_CLOEXEC, 0600);
    if (reset_trace && uid_poc_native_trace_fd >= 0) {
        (void) ftruncate (uid_poc_native_trace_fd, 0);
        (void) lseek (uid_poc_native_trace_fd, 0, SEEK_SET);
    }
    uid_poc_native_crash_fd = open (uid_poc_crash_path,
        O_WRONLY | O_CREAT | O_TRUNC | O_CLOEXEC, 0600);

    return uid_poc_native_trace_fd >= 0 && uid_poc_native_crash_fd >= 0;
}

static void
uid_poc_install_crash_handlers (void)
{
    struct sigaction action;
    (void) uid_poc_open_diagnostics (uid_poc_diag_dir, 1);
    memset (&action, 0, sizeof (action));
    sigemptyset (&action.sa_mask);
    action.sa_sigaction = uid_poc_native_signal_handler;
    action.sa_flags = SA_SIGINFO | SA_RESETHAND;
    (void) sigaction (SIGSEGV, &action, NULL);
    (void) sigaction (SIGABRT, &action, NULL);
    (void) sigaction (SIGBUS, &action, NULL);
    (void) sigaction (SIGILL, &action, NULL);
    (void) sigaction (SIGFPE, &action, NULL);
}

static jboolean
native_set_diagnostic_path (JNIEnv *env, jobject thiz, jstring directory)
{
    const char *path;
    int ok;
    if (!directory)
        return JNI_FALSE;
    path = (*env)->GetStringUTFChars (env, directory, NULL);
    if (!path)
        return JNI_FALSE;
    ok = uid_poc_open_diagnostics (path, 1);
    (*env)->ReleaseStringUTFChars (env, directory, path);
    if (ok) {
        hev_jni_uid_poc_stage ("JNI diagnostics path configured");
        return JNI_TRUE;
    }
    return JNI_FALSE;
}
/* SPEEDVPN_NATIVE_CRASH_POC_END */

'''
    replace_once(
        jni_c,
        "static pthread_key_t current_jni_env;\n",
        "static pthread_key_t current_jni_env;\n" + native_code,
    )

    replace_once(
        jni_c,
        "static jlongArray native_get_stats (JNIEnv *env, jobject thiz);\n",
        "static jlongArray native_get_stats (JNIEnv *env, jobject thiz);\n"
        "static jboolean native_set_diagnostic_path (JNIEnv *env, jobject thiz, jstring directory);\n",
    )
    method_anchor = '    { "TProxyGetStats", "()[J", (void *)native_get_stats },\n'
    replace_once(
        jni_c,
        method_anchor,
        method_anchor
        + '    { "TProxySetDiagnosticPath", "(Ljava/lang/String;)Z", (void *)native_set_diagnostic_path },\n',
    )
    replace_once(
        jni_c,
        "    java_vm = vm;\n",
        "    java_vm = vm;\n"
        "    uid_poc_install_crash_handlers ();\n"
        "    hev_jni_uid_poc_stage (\"JNI_OnLoad.begin\");\n",
    )
    replace_once(
        jni_c,
        "static void *\nthread_handler (void *data)\n{\n    ThreadData *tdata = data;\n\n    hev_socks5_tunnel_main (tdata->path, tdata->fd);\n",
        "static void *\nthread_handler (void *data)\n{\n    ThreadData *tdata = data;\n\n"
        "    hev_jni_uid_poc_stage (\"native_worker.before_hev_socks5_tunnel_main\");\n"
        "    hev_socks5_tunnel_main (tdata->path, tdata->fd);\n"
        "    hev_jni_uid_poc_stage (\"native_worker.hev_socks5_tunnel_main_returned\");\n",
    )
    replace_once(
        jni_c,
        "static jboolean\nnative_start_service (JNIEnv *env, jobject thiz, jstring config_path, jint fd)\n{\n    const jbyte *bytes;",
        "static jboolean\nnative_start_service (JNIEnv *env, jobject thiz, jstring config_path, jint fd)\n{\n"
        "    hev_jni_uid_poc_stage (\"native_start_service.enter\");\n"
        "    const jbyte *bytes;",
    )
    replace_once(
        jni_c,
        "    pthread_mutex_lock (&mutex);\n\n    if (atomic_load_explicit (&is_running, memory_order_acquire))",
        "    hev_jni_uid_poc_stage (\"native_start_service.before_mutex_lock\");\n"
        "    pthread_mutex_lock (&mutex);\n"
        "    hev_jni_uid_poc_stage (\"native_start_service.after_mutex_lock\");\n\n"
        "    if (atomic_load_explicit (&is_running, memory_order_acquire))",
    )
    replace_once(
        jni_c,
        "    atomic_store_explicit (&is_running, 1, memory_order_release);\n    res = pthread_create (&work_thread, NULL, thread_handler, tdata);",
        "    hev_jni_uid_poc_stage (\"native_start_service.before_pthread_create\");\n"
        "    atomic_store_explicit (&is_running, 1, memory_order_release);\n"
        "    res = pthread_create (&work_thread, NULL, thread_handler, tdata);",
    )
    replace_once(
        jni_c,
        "    thread_joinable = 1;\n    result = JNI_TRUE;",
        "    thread_joinable = 1;\n"
        "    hev_jni_uid_poc_stage (\"native_start_service.after_pthread_create\");\n"
        "    result = JNI_TRUE;",
    )

    tunnel_prefix = r'''
#ifdef ANDROID
extern void hev_jni_uid_poc_stage (const char *stage);
#define UID_POC_NATIVE_STAGE(stage) hev_jni_uid_poc_stage (stage)
#else
#define UID_POC_NATIVE_STAGE(stage) ((void) 0)
#endif
'''
    replace_once(tunnel_c, "enum\n{\n    SYNC_SEND", tunnel_prefix + "\nenum\n{\n    SYNC_SEND")
    replace_once(
        tunnel_c,
        "    LOG_D (\"socks5 tunnel init\");\n\n    res = tunnel_init (tun_fd);",
        "    UID_POC_NATIVE_STAGE (\"hev_socks5_tunnel_init.begin\");\n"
        "    LOG_D (\"socks5 tunnel init\");\n\n"
        "    UID_POC_NATIVE_STAGE (\"hev_socks5_tunnel_init.before_tunnel_init\");\n"
        "    res = tunnel_init (tun_fd);\n"
        "    UID_POC_NATIVE_STAGE (\"hev_socks5_tunnel_init.after_tunnel_init\");",
    )
    replace_once(
        tunnel_c,
        "    res = gateway_init ();\n    if (res < 0)",
        "    UID_POC_NATIVE_STAGE (\"hev_socks5_tunnel_init.before_gateway_init\");\n"
        "    res = gateway_init ();\n"
        "    UID_POC_NATIVE_STAGE (\"hev_socks5_tunnel_init.after_gateway_init\");\n"
        "    if (res < 0)",
    )
    replace_once(
        tunnel_c,
        "    res = event_task_init ();\n    if (res < 0)",
        "    UID_POC_NATIVE_STAGE (\"hev_socks5_tunnel_init.before_event_task_init\");\n"
        "    res = event_task_init ();\n"
        "    UID_POC_NATIVE_STAGE (\"hev_socks5_tunnel_init.after_event_task_init\");\n"
        "    if (res < 0)",
    )
    replace_once(
        tunnel_c,
        "    res = lwip_io_task_init ();\n    if (res < 0)",
        "    UID_POC_NATIVE_STAGE (\"hev_socks5_tunnel_init.before_lwip_io_task_init\");\n"
        "    res = lwip_io_task_init ();\n"
        "    UID_POC_NATIVE_STAGE (\"hev_socks5_tunnel_init.after_lwip_io_task_init\");\n"
        "    if (res < 0)",
    )
    replace_once(
        tunnel_c,
        "    res = lwip_timer_task_init ();\n    if (res < 0)",
        "    UID_POC_NATIVE_STAGE (\"hev_socks5_tunnel_init.before_lwip_timer_task_init\");\n"
        "    res = lwip_timer_task_init ();\n"
        "    UID_POC_NATIVE_STAGE (\"hev_socks5_tunnel_init.after_lwip_timer_task_init\");\n"
        "    if (res < 0)",
    )
    replace_once(
        tunnel_c,
        "    res = mapped_dns_init ();\n    if (res < 0)",
        "    UID_POC_NATIVE_STAGE (\"hev_socks5_tunnel_init.before_mapped_dns_init\");\n"
        "    res = mapped_dns_init ();\n"
        "    UID_POC_NATIVE_STAGE (\"hev_socks5_tunnel_init.after_mapped_dns_init\");\n"
        "    if (res < 0)",
    )
    replace_once(
        tunnel_c,
        "    hev_task_mutex_init (&mutex);\n\n    atomic_fetch_or (&tsync, SYNC_SEND);",
        "    hev_task_mutex_init (&mutex);\n\n"
        "    atomic_fetch_or (&tsync, SYNC_SEND);\n"
        "    UID_POC_NATIVE_STAGE (\"hev_socks5_tunnel_init.success\");",
    )
    replace_once(
        tunnel_c,
        "int\nhev_socks5_tunnel_run (void)\n{\n    LOG_D (\"socks5 tunnel run\");",
        "int\nhev_socks5_tunnel_run (void)\n{\n"
        "    UID_POC_NATIVE_STAGE (\"hev_socks5_tunnel_run.begin\");\n"
        "    LOG_D (\"socks5 tunnel run\");",
    )
    replace_once(
        tunnel_c,
        "    WRITE_ONCE (run, 1);\n    hev_task_system_run ();",
        "    WRITE_ONCE (run, 1);\n"
        "    UID_POC_NATIVE_STAGE (\"hev_socks5_tunnel_run.before_task_system_run\");\n"
        "    hev_task_system_run ();\n"
        "    UID_POC_NATIVE_STAGE (\"hev_socks5_tunnel_run.after_task_system_run\");",
    )

    print("OK - native crash handler and C-stage breadcrumbs applied to pinned hev source")


if __name__ == "__main__":
    main()
