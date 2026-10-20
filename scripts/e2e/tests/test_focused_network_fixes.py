#!/usr/bin/env python3
"""Lightweight unit and native C-harness tests for the 4 focused VPN network fixes."""

from pathlib import Path
import selectors
import socket
import subprocess
import tempfile
import threading
import time
import unittest


REPO_ROOT = Path(__file__).resolve().parents[3]


class FocusedNetworkFixesTest(unittest.TestCase):
    def test_encrypted_dns_resolver_accepts_a_and_aaaa_record_types(self) -> None:
        resolver_kt = (
            REPO_ROOT / "app/src/main/java/com/speedvpn/app/EncryptedDnsResolver.kt"
        ).read_text()

        self.assertIn("internal const val DNS_TYPE_A = 1", resolver_kt)
        self.assertIn("internal const val DNS_TYPE_AAAA = 28", resolver_kt)
        self.assertIn("internal fun expectedDnsRecordType(family: Int): Int", resolver_kt)
        self.assertIn("internal fun parseDnsAnswerAddress(", resolver_kt)
        self.assertNotIn(
            'if (answer.optInt("type", -1) != family) continue',
            resolver_kt,
            "DoH parser must not compare DNS RR type against IP family numbers (4/6)",
        )

    def test_socks5_udp_mux_eliminates_selector_monitor_deadlock(self) -> None:
        socks_kt = (
            REPO_ROOT / "app/src/main/java/com/speedvpn/app/Socks5Server.kt"
        ).read_text()

        self.assertNotIn("synchronized(selector)", socks_kt)
        self.assertIn("internal fun registerChannelWithSelector(", socks_kt)
        self.assertIn("internal fun yieldToPendingSelectorRegistrations(", socks_kt)
        self.assertIn("private val selectorRegistrationGate = Any()", socks_kt)
        self.assertIn("private val pendingSelectorRegistrations = AtomicInteger(0)", socks_kt)

        # Ensure gate fields are initialized before the worker thread starts runLoop().
        gate_pos = socks_kt.index("private val selectorRegistrationGate = Any()")
        worker_pos = socks_kt.index('private val worker = thread(name = "socks-udp-mux"')
        self.assertLess(gate_pos, worker_pos)

        # Verify concurrent wake + registration + UDP packet delivery without 500ms stalls.
        sel = selectors.DefaultSelector()
        wake_r, wake_w = socket.socketpair()
        wake_r.setblocking(False)
        wake_w.setblocking(False)
        sel.register(wake_r, selectors.EVENT_READ, data="wake")

        gate = threading.Lock()
        pending = 0
        pending_lock = threading.Lock()
        running = True
        entered_select = threading.Event()
        received = []
        packet_event = threading.Event()

        def worker_loop() -> None:
            while running:
                with pending_lock:
                    has_pending = pending > 0
                if has_pending:
                    with gate:
                        pass
                entered_select.set()
                events = sel.select(timeout=0.5)
                for key, _ in events:
                    if key.data == "wake":
                        try:
                            wake_r.recv(64)
                        except BlockingIOError:
                            pass
                    elif key.data == "udp":
                        sock_obj = key.fileobj
                        data = sock_obj.recv(256)
                        received.append(data)
                        packet_event.set()

        worker = threading.Thread(target=worker_loop, daemon=True)
        worker.start()
        peer = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
        peer.bind(("127.0.0.1", 0))
        client = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
        client.setblocking(False)
        client.bind(("127.0.0.1", 0))
        client.connect(peer.getsockname())
        try:
            self.assertTrue(entered_select.wait(timeout=1.0))
            time.sleep(0.02)
            start = time.monotonic()
            with pending_lock:
                pending += 1
            try:
                wake_w.send(b"w")
                with gate:
                    wake_w.send(b"w")
                    sel.register(client, selectors.EVENT_READ, data="udp")
            finally:
                with pending_lock:
                    pending -= 1
            elapsed_ms = (time.monotonic() - start) * 1000.0
            self.assertLess(elapsed_ms, 200.0)

            peer.sendto(b"quic-initial", client.getsockname())
            self.assertTrue(packet_event.wait(timeout=1.0))
            self.assertEqual([b"quic-initial"], received)
        finally:
            running = False
            try:
                wake_w.send(b"x")
            except OSError:
                pass
            worker.join(timeout=1.0)
            sel.close()
            wake_r.close()
            wake_w.close()
            client.close()
            peer.close()

    def test_internal_mapdns_unresolved_uid_policy_in_kotlin_and_native_c(self) -> None:
        policy_kt = (
            REPO_ROOT / "app/src/main/java/com/speedvpn/app/VpnApplicationPolicy.kt"
        ).read_text()
        uid_poc_kt = (
            REPO_ROOT / "app/src/main/java/com/speedvpn/app/UidDetectionPoc.kt"
        ).read_text()

        self.assertIn('private const val MAP_DNS_IPV4 = "198.18.0.2"', policy_kt)
        self.assertIn("fun isInternalMapDnsQuery(", policy_kt)
        self.assertIn("!isInternalMapDnsQuery(protocol, dstIp, dstPort)", policy_kt)
        self.assertIn("dstIp = dstIp,", uid_poc_kt)
        self.assertIn("dstPort = dstPort,", uid_poc_kt)

        # Compile and execute the native C MapDNS / UID policy logic from hev-jni.c.
        jni_c_path = REPO_ROOT / "app/src/main/jni/hev-socks5-tunnel/src/hev-jni.c"
        jni_c_text = jni_c_path.read_text()
        self.assertIn("uid_poc_is_internal_mapdns", jni_c_text)

        start_marker = "/* SPEEDVPN_UID_POC_BEGIN */"
        end_marker = "/* SPEEDVPN_UID_POC_END */"
        first_begin = jni_c_text.index(start_marker)
        first_end = jni_c_text.index(end_marker, first_begin) + len(end_marker)
        second_begin = jni_c_text.index(start_marker, first_end)
        second_end = jni_c_text.index(end_marker, second_begin) + len(end_marker)

        declarations_block = jni_c_text[first_begin:first_end]
        functions_block = jni_c_text[second_begin:second_end]

        c_harness = f"""
#include <pthread.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <time.h>
#include <assert.h>

typedef int jint;
typedef long long jlong;
typedef unsigned char jboolean;
typedef signed char jbyte;
typedef void *jobject;
typedef void *jclass;
typedef void *jmethodID;
typedef void * jbyteArray;
#define JNI_TRUE 1
#define JNI_FALSE 0
#define JNI_OK 0
#define ANDROID_LOG_INFO 4
#define ANDROID_LOG_WARN 5
#define ANDROID_LOG_ERROR 6
#define __android_log_print(prio, tag, fmt, ...) ((void)0)

typedef struct JNINativeInterface *JNIEnv;
typedef const struct JNIInvokeInterface *JavaVM;
struct JNINativeInterface {{
    jclass (*GetObjectClass)(JNIEnv *, jobject);
    jmethodID (*GetMethodID)(JNIEnv *, jclass, const char *, const char *);
    void (*DeleteLocalRef)(JNIEnv *, jobject);
    jboolean (*ExceptionCheck)(JNIEnv *);
    void (*ExceptionDescribe)(JNIEnv *);
    void (*ExceptionClear)(JNIEnv *);
    jobject (*NewGlobalRef)(JNIEnv *, jobject);
    void (*DeleteGlobalRef)(JNIEnv *, jobject);
    jbyteArray (*NewByteArray)(JNIEnv *, int);
    void (*SetByteArrayRegion)(JNIEnv *, jbyteArray, int, int, const jbyte *);
    jlong (*CallLongMethod)(JNIEnv *, jobject, jmethodID, ...);
}};
struct JNIInvokeInterface {{
    int (*AttachCurrentThread)(JavaVM *, JNIEnv **, void *);
    int (*DetachCurrentThread)(JavaVM *);
}};
static JavaVM *java_vm = NULL;

{declarations_block}
{functions_block}

int main(void) {{
    const unsigned char src_ip[4] = {{10, 0, 0, 2}};
    const unsigned char mapdns_ip[4] = {{198, 18, 0, 2}};
    const unsigned char ext_dns_ip[4] = {{8, 8, 8, 8}};
    const unsigned char ext_web_ip[4] = {{142, 250, 190, 78}};

    /* 1. Helper unit checks */
    assert(uid_poc_is_internal_mapdns(17, mapdns_ip, 4, 53) == 1);
    assert(uid_poc_is_internal_mapdns(6, mapdns_ip, 4, 53) == 0);
    assert(uid_poc_is_internal_mapdns(17, mapdns_ip, 4, 443) == 0);
    assert(uid_poc_is_internal_mapdns(17, ext_dns_ip, 4, 53) == 0);

    /* 2. With policy active and worker uninitialized/unresolved:
     * MapDNS UDP 198.18.0.2:53 must NOT be dropped, while all other traffic fails closed. */
    uid_poc_policy_active = 1;
    assert(hev_jni_uid_poc_packet(17, src_ip, 4, 54321, mapdns_ip, 4, 53) == 0);
    assert(hev_jni_uid_poc_packet(17, src_ip, 4, 54321, ext_dns_ip, 4, 53) == 1);
    assert(hev_jni_uid_poc_packet(6, src_ip, 4, 54321, mapdns_ip, 4, 53) == 1);
    assert(hev_jni_uid_poc_packet(17, src_ip, 4, 54321, mapdns_ip, 4, 443) == 1);
    assert(hev_jni_uid_poc_packet(6, src_ip, 4, 54321, ext_web_ip, 4, 443) == 1);

    /* 3. With worker marked ready and cached entries:
     * - Cached uid=-1 on MapDNS 198.18.0.2:53 must be allowed (return 0)
     * - Cached blocked app uid=10249 on MapDNS 198.18.0.2:53 must STILL be dropped (return 1)
     * - Cached uid=-1 on non-MapDNS destination must be dropped (return 1) */
    uid_poc_worker_started = 1;
    uid_poc_worker_ready = 1;
    uid_poc_callback = (void *)0x1;
    uid_poc_method = (void *)0x1;

    int found = 0;
    unsigned long long now = uid_poc_now_ms();

    uid_poc_flow_key mapdns_unresolved_key;
    uid_poc_make_key(&mapdns_unresolved_key, 17, src_ip, 4, 50001, mapdns_ip, 4, 53);
    int slot = uid_poc_find_slot_locked(&mapdns_unresolved_key, now, &found);
    uid_poc_cache[slot].state = UID_POC_CACHE_USED;
    uid_poc_cache[slot].key = mapdns_unresolved_key;
    uid_poc_cache[slot].uid = UID_POC_UID_PENDING;
    uid_poc_cache[slot].updated_ms = now;
    uid_poc_cache[slot].request_id = 1;
    uid_poc_store_result_locked(&mapdns_unresolved_key, -1, UID_POC_ACTION_ALLOW, now, 1);
    assert(hev_jni_uid_poc_packet(17, src_ip, 4, 50001, mapdns_ip, 4, 53) == 0);

    uid_poc_flow_key mapdns_blocked_uid_key;
    uid_poc_make_key(&mapdns_blocked_uid_key, 17, src_ip, 4, 50002, mapdns_ip, 4, 53);
    slot = uid_poc_find_slot_locked(&mapdns_blocked_uid_key, now, &found);
    uid_poc_cache[slot].state = UID_POC_CACHE_USED;
    uid_poc_cache[slot].key = mapdns_blocked_uid_key;
    uid_poc_cache[slot].uid = UID_POC_UID_PENDING;
    uid_poc_cache[slot].updated_ms = now;
    uid_poc_cache[slot].request_id = 2;
    uid_poc_store_result_locked(&mapdns_blocked_uid_key, 10249, UID_POC_ACTION_BLOCK, now, 2);
    assert(hev_jni_uid_poc_packet(17, src_ip, 4, 50002, mapdns_ip, 4, 53) == 1);

    uid_poc_flow_key ext_unresolved_key;
    uid_poc_make_key(&ext_unresolved_key, 17, src_ip, 4, 50003, ext_dns_ip, 4, 53);
    slot = uid_poc_find_slot_locked(&ext_unresolved_key, now, &found);
    uid_poc_cache[slot].state = UID_POC_CACHE_USED;
    uid_poc_cache[slot].key = ext_unresolved_key;
    uid_poc_cache[slot].uid = UID_POC_UID_PENDING;
    uid_poc_cache[slot].updated_ms = now;
    uid_poc_cache[slot].request_id = 3;
    uid_poc_store_result_locked(&ext_unresolved_key, -1, UID_POC_ACTION_BLOCK, now, 3);
    assert(hev_jni_uid_poc_packet(17, src_ip, 4, 50003, ext_dns_ip, 4, 53) == 1);

    printf("NATIVE_MAPDNS_UID_TEST_OK\\n");
    return 0;
}}
"""
        with tempfile.TemporaryDirectory() as tmpdir:
            c_file = Path(tmpdir) / "test_mapdns_uid.c"
            bin_file = Path(tmpdir) / "test_mapdns_uid"
            c_file.write_text(c_harness)
            subprocess.run(
                ["gcc", "-O2", "-pthread", str(c_file), "-o", str(bin_file)],
                check=True,
            )
            res = subprocess.run([str(bin_file)], capture_output=True, text=True, check=True)
            self.assertIn("NATIVE_MAPDNS_UID_TEST_OK", res.stdout)

    def test_first_udp_packet_is_not_enqueued_twice_in_native_path(self) -> None:
        tunnel_c = (
            REPO_ROOT / "app/src/main/jni/hev-socks5-tunnel/src/hev-socks5-tunnel.c"
        ).read_text()
        session_udp_c = (
            REPO_ROOT / "app/src/main/jni/hev-socks5-tunnel/src/hev-socks5-session-udp.c"
        ).read_text()

        self.assertNotIn(
            "hev_socks5_session_udp_enqueue_initial (udp, p, addr, port)",
            tunnel_c,
            "hev-socks5-tunnel.c must not pre-enqueue pbuf before lwIP's PRETEND_UDP retry",
        )
        self.assertIn("hev_socks5_session_udp_has_pbuf", session_udp_c)

        # Compile and run a C harness simulating lwIP's PRETEND_UDP two-pass dispatch
        # (accept callback before UDP_HLEN strip -> goto again -> UDP_HLEN strip -> session recv callback).
        c_harness = r"""
#include <assert.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>

typedef uint16_t u16_t;
typedef uint32_t u32_t;

typedef struct HevListNode {
    struct HevListNode *prev;
    struct HevListNode *next;
} HevListNode;

typedef struct {
    HevListNode *head;
    HevListNode *tail;
} HevList;

static inline HevListNode *hev_list_first(HevList *list) { return list->head; }
static inline HevListNode *hev_list_node_next(HevListNode *node) { return node->next; }
static inline void hev_list_add_tail(HevList *list, HevListNode *node) {
    node->prev = list->tail;
    node->next = NULL;
    if (list->tail) list->tail->next = node;
    else list->head = node;
    list->tail = node;
}
static inline void hev_list_del(HevList *list, HevListNode *node) {
    if (node->prev) node->prev->next = node->next;
    else list->head = node->next;
    if (node->next) node->next->prev = node->prev;
    else list->tail = node->prev;
}

#define container_of(ptr, type, member) \
    ((type *)((char *)(ptr) - __builtin_offsetof(type, member)))

struct pbuf {
    void *payload;
    u16_t tot_len;
    u16_t len;
    u16_t ref;
    int freed;
};

static void pbuf_ref(struct pbuf *p) { p->ref++; }
static void pbuf_free(struct pbuf *p) {
    assert(p->ref > 0);
    p->ref--;
    if (p->ref == 0) p->freed = 1;
}
static int pbuf_remove_header(struct pbuf *p, size_t header_size) {
    if (p->len < header_size) return 1;
    p->payload = (unsigned char *)p->payload + header_size;
    p->len -= (u16_t)header_size;
    p->tot_len -= (u16_t)header_size;
    return 0;
}

typedef struct { u32_t addr; } ip4_addr_t;
typedef struct { ip4_addr_t u_addr; int type; } ip_addr_t;
#define IPADDR_TYPE_V4 0
#define ip_2_ip4(ipaddr) (&((ipaddr)->u_addr))

struct udp_pcb;
typedef void (*udp_recv_fn)(void *arg, struct udp_pcb *pcb, struct pbuf *p,
                            const ip_addr_t *addr, u16_t port);

struct udp_pcb {
    ip_addr_t local_ip;
    u16_t local_port;
    ip_addr_t remote_ip;
    u16_t remote_port;
    udp_recv_fn recv;
    void *recv_arg;
};

static void udp_recv(struct udp_pcb *pcb, udp_recv_fn recv, void *recv_arg) {
    pcb->recv = recv;
    pcb->recv_arg = recv_arg;
}

#define HEV_SOCKS5_ADDR_TYPE_IPV4 1
#define HEV_SOCKS5_ADDR_TYPE_NAME 3
#define UDP_POOL_SIZE 512

typedef struct {
    unsigned char atype;
    u16_t port;
    u32_t ipv4;
} HevSocks5Addr;

static int hev_socks5_addr_from_lwip(HevSocks5Addr *addr, const ip_addr_t *ip, u16_t port) {
    if (!addr || !ip) return -1;
    addr->atype = HEV_SOCKS5_ADDR_TYPE_IPV4;
    addr->port = port;
    addr->ipv4 = ip->u_addr.addr;
    return 0;
}

typedef struct {
    HevListNode node;
    HevSocks5Addr addr;
    struct pbuf *data;
} HevSocks5UDPFrame;

typedef struct {
    HevList frame_list;
    struct udp_pcb *pcb;
    int frames;
    u32_t addr;
    u16_t port;
    struct { void *task; } data;
} HevSocks5SessionUDP;

#define hev_malloc malloc
#define hev_free free
#define hev_task_wakeup(t) ((void)(t))
#define hev_socks5_session_terminate(s) ((void)(s))
#define HEV_SOCKS5_SESSION(s) (s)

static int
hev_socks5_session_udp_has_pbuf (HevSocks5SessionUDP *self, const struct pbuf *p)
{
    HevListNode *node;

    if (!self || !p)
        return 0;
    for (node = hev_list_first (&self->frame_list); node;
         node = hev_list_node_next (node)) {
        HevSocks5UDPFrame *frame = container_of (node, HevSocks5UDPFrame, node);
        if (frame->data == p)
            return 1;
    }
    return 0;
}

static void
session_udp_recv_handler (void *arg, struct udp_pcb *pcb, struct pbuf *p,
                          const ip_addr_t *addr, u16_t port)
{
    HevSocks5SessionUDP *self = arg;
    HevSocks5UDPFrame *frame;
    (void)addr;
    (void)port;

    if (!p) {
        hev_socks5_session_terminate (HEV_SOCKS5_SESSION (self));
        return;
    }

    if (hev_socks5_session_udp_has_pbuf (self, p)) {
        pbuf_free (p);
        hev_task_wakeup (self->data.task);
        return;
    }

    if (self->frames > UDP_POOL_SIZE) {
        pbuf_free (p);
        return;
    }

    frame = hev_malloc (sizeof (HevSocks5UDPFrame));
    if (!frame) {
        pbuf_free (p);
        return;
    }

    frame->data = p;
    memset (&frame->node, 0, sizeof (frame->node));
    hev_socks5_addr_from_lwip (&frame->addr, &pcb->local_ip, pcb->local_port);

    if (frame->addr.atype == HEV_SOCKS5_ADDR_TYPE_NAME) {
        self->addr = ip_2_ip4 (&pcb->local_ip)->addr;
        self->port = pcb->local_port;
    }

    self->frames++;
    hev_list_add_tail (&self->frame_list, &frame->node);
    hev_task_wakeup (self->data.task);
}

int
hev_socks5_session_udp_enqueue_initial (HevSocks5SessionUDP *self, struct pbuf *p,
                                        const ip_addr_t *dst_ip, u16_t dst_port)
{
    HevSocks5UDPFrame *frame;
    int res;

    if (!self || !p)
        return -1;
    if (hev_socks5_session_udp_has_pbuf (self, p))
        return 0;
    pbuf_ref (p);
    if (self->frames > UDP_POOL_SIZE) {
        pbuf_free (p);
        return -1;
    }
    frame = hev_malloc (sizeof (HevSocks5UDPFrame));
    if (!frame) {
        pbuf_free (p);
        return -1;
    }
    memset (&frame->node, 0, sizeof (frame->node));
    if (!dst_ip) {
        pbuf_free (p);
        hev_free (frame);
        return -1;
    }
    res = hev_socks5_addr_from_lwip (&frame->addr, dst_ip, dst_port);
    if (res < 0) {
        pbuf_free (p);
        hev_free (frame);
        return -1;
    }
    frame->data = p;
    self->frames++;
    hev_list_add_tail (&self->frame_list, &frame->node);
    return 0;
}

int main(void) {
    unsigned char raw_packet[20] = {
        /* 8-byte UDP header */
        0xd4, 0x31, 0x01, 0xbb, 0x00, 0x14, 0x00, 0x00,
        /* 12-byte QUIC/UDP payload */
        'H', 'E', 'L', 'L', 'O', '_', 'U', 'D', 'P', '_', '0', '1'
    };
    struct pbuf pkt = {
        .payload = raw_packet,
        .tot_len = sizeof(raw_packet),
        .len = sizeof(raw_packet),
        .ref = 1,
        .freed = 0,
    };

    struct udp_pcb npcb;
    memset(&npcb, 0, sizeof(npcb));
    ip_addr_t dst_ip = { .u_addr = { .addr = 0x4ebeef8e }, .type = IPADDR_TYPE_V4 };
    u16_t dst_port = 443;

    HevSocks5SessionUDP session;
    memset(&session, 0, sizeof(session));
    session.pcb = &npcb;

    /* Pass 1 of lwip udp_input (before UDP_HLEN removal):
     * tunnel_c udp_recv_handler creates the session and binds session_udp_recv_handler
     * without calling hev_socks5_session_udp_enqueue_initial. */
    udp_recv(&npcb, session_udp_recv_handler, &session);
    assert(session.frames == 0);

    /* Pass 2 of lwip udp_input (goto again):
     * strips 8-byte UDP_HLEN, sets npcb->local_ip/local_port, and calls npcb->recv. */
    assert(pbuf_remove_header(&pkt, 8) == 0);
    npcb.local_ip = dst_ip;
    npcb.local_port = dst_port;
    npcb.recv(npcb.recv_arg, &npcb, &pkt, NULL, 54321);

    /* Verify exactly 1 frame is queued, payload has UDP header stripped, and ref == 1. */
    assert(session.frames == 1);
    assert(pkt.ref == 1);
    HevListNode *node = hev_list_first(&session.frame_list);
    assert(node != NULL);
    assert(hev_list_node_next(node) == NULL);
    HevSocks5UDPFrame *frame = container_of(node, HevSocks5UDPFrame, node);
    assert(frame->data == &pkt);
    assert(frame->data->len == 12);
    assert(memcmp(frame->data->payload, "HELLO_UDP_01", 12) == 0);

    /* Defense-in-depth: if session_udp_recv_handler or enqueue_initial is ever called
     * again with the same pbuf, it must not enqueue a second copy. */
    pbuf_ref(&pkt);
    session_udp_recv_handler(&session, &npcb, &pkt, NULL, 54321);
    assert(session.frames == 1);
    assert(pkt.ref == 1);
    assert(hev_socks5_session_udp_enqueue_initial(&session, &pkt, &dst_ip, dst_port) == 0);
    assert(session.frames == 1);
    assert(pkt.ref == 1);

    /* Cleanup single frame */
    hev_list_del(&session.frame_list, node);
    hev_free(frame);
    pbuf_free(&pkt);
    assert(pkt.ref == 0 && pkt.freed == 1);

    printf("NATIVE_UDP_SINGLE_ENQUEUE_OK\n");
    return 0;
}
"""
        with tempfile.TemporaryDirectory() as tmpdir:
            c_file = Path(tmpdir) / "test_udp_single_enqueue.c"
            bin_file = Path(tmpdir) / "test_udp_single_enqueue"
            c_file.write_text(c_harness)
            subprocess.run(
                ["gcc", "-O2", str(c_file), "-o", str(bin_file)],
                check=True,
            )
            res = subprocess.run([str(bin_file)], capture_output=True, text=True, check=True)
            self.assertIn("NATIVE_UDP_SINGLE_ENQUEUE_OK", res.stdout)


if __name__ == "__main__":
    unittest.main()
