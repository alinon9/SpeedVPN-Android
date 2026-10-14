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


def main() -> None:
    if len(sys.argv) != 2:
        raise SystemExit("usage: patch_hev_uid_detection_poc.py <hev-socks5-tunnel-dir>")
    root = Path(sys.argv[1]).resolve()
    tunnel_c = root / "src/hev-socks5-tunnel.c"
    jni_c = root / "src/hev-jni.c"
    for required in (tunnel_c, jni_c):
        if not required.is_file():
            raise SystemExit(f"Missing pinned hev source file: {required}")

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

    # JNI bridge: callback invocations are rate-limited and moved off the TUN thread in Kotlin.
    jni_text = jni_c.read_text()
    if BEGIN not in jni_text:
        jni_text = jni_text.replace(
            "static pthread_key_t current_jni_env;\n",
            "static pthread_key_t current_jni_env;\n"
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

    if (!java_vm)
        return;
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
    if ((*env)->ExceptionCheck (env))
        (*env)->ExceptionClear (env);

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

    print("OK - temporary UID detection POC hook applied to pinned hev source")


if __name__ == "__main__":
    main()
