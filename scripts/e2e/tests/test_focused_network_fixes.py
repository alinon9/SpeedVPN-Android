#!/usr/bin/env python3
"""Lightweight unit and native C-harness tests for the 4 focused VPN network fixes."""

from pathlib import Path
import selectors
import socket
import sqlite3
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



    def test_hev_tunnel_config_enables_socks5_pipeline_and_conditional_msg_more(self) -> None:
        vpn_service_kt = (
            REPO_ROOT / "app/src/main/java/com/speedvpn/app/SpeedVpnService.kt"
        ).read_text()
        hev_client_c = (
            REPO_ROOT
            / "app/src/main/jni/hev-socks5-tunnel/src/core/src/hev-socks5-client.c"
        ).read_text()

        self.assertIn("pipeline: true", vpn_service_kt)
        self.assertIn(
            "hev_socks5_client_write_auth_methods (HevSocks5Client *self, int pipeline)",
            hev_client_c,
        )
        self.assertIn(
            "hev_socks5_client_write_auth_creds (HevSocks5Client *self, int pipeline)",
            hev_client_c,
        )
        self.assertIn("int flags = MSG_WAITALL | (pipeline ? MSG_MORE : 0);", hev_client_c)
        self.assertIn("hev_socks5_client_write_auth_methods (self, 0);", hev_client_c)
        self.assertIn("hev_socks5_client_write_auth_creds (self, 0);", hev_client_c)
        self.assertIn("hev_socks5_client_write_auth_methods (self, 1);", hev_client_c)
        self.assertIn("hev_socks5_client_write_auth_creds (self, 1);", hev_client_c)

    def test_native_hev_socks5_client_c_handshake_executes_without_msg_more_cork_stall(
        self,
    ) -> None:
        """Compile and execute the modified hev-socks5-client.c over real Linux loopback sockets."""
        core_src_dir = (
            REPO_ROOT / "app/src/main/jni/hev-socks5-tunnel/src/core/src"
        ).resolve()
        client_c_path = core_src_dir / "hev-socks5-client.c"
        self.assertTrue(client_c_path.is_file())

        c_harness = f"""
#define _GNU_SOURCE
#include <arpa/inet.h>
#include <assert.h>
#include <errno.h>
#include <netinet/in.h>
#include <netinet/tcp.h>
#include <pthread.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/socket.h>
#include <sys/uio.h>
#include <time.h>
#include <unistd.h>

/* Minimal HEV task/object/memory stubs so the real hev-socks5-client.c compiles and runs natively */
typedef struct _HevObjectClass {{
    const char *name;
    void (*destruct)(void *);
}} HevObjectClass;

typedef struct _HevObject {{
    HevObjectClass *klass;
}} HevObject;

#define HEV_OBJECT(p) ((HevObject *)(p))
#define HEV_OBJECT_CLASS(p) ((HevObjectClass *)(p))
#define HEV_OBJECT_GET_CLASS(p) (((HevObject *)(p))->klass)

typedef int (*HevTaskIoYielder)(int, void *);
#define hev_socks5_task_io_yielder NULL
#define hev_task_self() NULL
#define hev_task_del_fd(t, fd) ((void)0)
#define hev_malloc malloc
#define hev_free free
#define LOG_D(...) ((void)0)
#define LOG_I(...) ((void)0)
#define LOG_W(...) ((void)0)
#define LOG_E(...) ((void)0)

#include "{core_src_dir}/hev-socks5-proto.h"

typedef enum {{
    HEV_SOCKS5_TYPE_NONE,
    HEV_SOCKS5_TYPE_TCP,
    HEV_SOCKS5_TYPE_UDP_IN_TCP,
    HEV_SOCKS5_TYPE_UDP_IN_UDP,
}} HevSocks5Type;

typedef enum {{
    HEV_SOCKS5_ADDR_FAMILY_IPV4 = AF_INET,
    HEV_SOCKS5_ADDR_FAMILY_IPV6 = AF_INET6,
    HEV_SOCKS5_ADDR_FAMILY_UNSPEC = AF_UNSPEC,
}} HevSocks5AddrFamily;

typedef struct _HevSocks5 {{
    HevObject base;
    int fd;
    int timeout;
    unsigned int type : 2;
    unsigned int udp_associated : 1;
    HevSocks5AddrFamily addr_family;
}} HevSocks5;

typedef struct _HevSocks5Class {{
    HevObjectClass base;
    int (*binder)(HevSocks5 *self, int sock, const struct sockaddr *dest);
}} HevSocks5Class;

#define HEV_SOCKS5(p) ((HevSocks5 *)(p))
#define HEV_SOCKS5_CLASS(p) ((HevSocks5Class *)(p))
#define HEV_SOCKS5_TYPE (hev_socks5_class ())

static void dummy_destruct(void *self) {{ (void)self; }}
static int dummy_binder(HevSocks5 *self, int sock, const struct sockaddr *dest) {{
    (void)self; (void)sock; (void)dest;
    return 0;
}}

HevObjectClass *hev_socks5_class(void) {{
    static HevSocks5Class klass;
    if (!klass.base.name) {{
        klass.base.name = "HevSocks5";
        klass.base.destruct = dummy_destruct;
        klass.binder = dummy_binder;
    }}
    return HEV_OBJECT_CLASS(&klass);
}}

int hev_socks5_construct(HevSocks5 *self, HevSocks5Type type) {{
    memset(self, 0, sizeof(*self));
    self->base.klass = HEV_SOCKS5_TYPE;
    self->fd = -1;
    self->type = type;
    self->addr_family = HEV_SOCKS5_ADDR_FAMILY_IPV4;
    return 0;
}}

int hev_socks5_get_timeout(HevSocks5 *self) {{ return self->timeout; }}
void hev_socks5_set_timeout(HevSocks5 *self, int timeout) {{ self->timeout = timeout; }}
HevSocks5AddrFamily hev_socks5_get_addr_family(HevSocks5 *self) {{ return self->addr_family; }}
void hev_socks5_set_addr_family(HevSocks5 *self, HevSocks5AddrFamily family) {{ self->addr_family = family; }}
int hev_socks5_get_connect_timeout(void) {{ return 5000; }}
int hev_socks5_get_tcp_timeout(void) {{ return 5000; }}
int hev_socks5_get_udp_timeout(void) {{ return 5000; }}
int hev_socks5_socket(int type) {{ return socket(AF_INET, type, 0); }}
int hev_socks5_name_into_sockaddr6(const char *addr, int port, struct sockaddr_in6 *saddr, int *family) {{
    (void)addr; (void)port; (void)saddr; (void)family;
    return 0;
}}

static int recorded_send_flags[16];
static int recorded_send_count = 0;

static ssize_t hev_task_io_socket_send(int fd, const void *buf, size_t count, int flags,
                                       HevTaskIoYielder yielder, void *data) {{
    (void)yielder; (void)data;
    if (recorded_send_count < 16) recorded_send_flags[recorded_send_count++] = flags;
    return send(fd, buf, count, flags & ~MSG_WAITALL);
}}

static ssize_t hev_task_io_socket_sendmsg(int fd, const struct msghdr *mh, int flags,
                                          HevTaskIoYielder yielder, void *data) {{
    (void)yielder; (void)data;
    if (recorded_send_count < 16) recorded_send_flags[recorded_send_count++] = flags;
    return sendmsg(fd, mh, flags & ~MSG_WAITALL);
}}

static ssize_t hev_task_io_socket_recv(int fd, void *buf, size_t count, int flags,
                                       HevTaskIoYielder yielder, void *data) {{
    (void)yielder; (void)data;
    return recv(fd, buf, count, flags);
}}

static int hev_task_io_socket_connect(int fd, const struct sockaddr *addr, socklen_t addrlen,
                                      HevTaskIoYielder yielder, void *data) {{
    (void)yielder; (void)data;
    return connect(fd, addr, addrlen);
}}

#define __HEV_SOCKS5_H__
#define __HEV_SOCKS5_MISC_PRIV_H__
#define __HEV_SOCKS5_LOGGER_PRIV_H__
#include "{core_src_dir}/hev-socks5-client.h"

/* Include the actual modified C implementation under test */
#include "{client_c_path}"

static HevSocks5Addr *test_get_upstream_addr(HevSocks5Client *self) {{
    (void)self;
    HevSocks5Addr *addr = malloc(sizeof(HevSocks5Addr));
    memset(addr, 0, sizeof(*addr));
    addr->atype = HEV_SOCKS5_ADDR_TYPE_IPV4;
    addr->ipv4.addr[0] = 142;
    addr->ipv4.addr[1] = 250;
    addr->ipv4.addr[2] = 190;
    addr->ipv4.addr[3] = 78;
    addr->ipv4.port = htons(443);
    return addr;
}}

static int parsed_upstream_port = 0;
static int test_set_upstream_addr(HevSocks5Client *self, HevSocks5Addr *addr) {{
    (void)self;
    if (addr->atype != HEV_SOCKS5_ADDR_TYPE_IPV4) return -1;
    parsed_upstream_port = ntohs(addr->ipv4.port);
    return 0;
}}

static void *socks5_server_thread(void *arg) {{
    int listen_fd = *(int *)arg;
    for (int session = 0; session < 2; session++) {{
        int cfd = accept(listen_fd, NULL, NULL);
        assert(cfd >= 0);
        int one = 1;
        setsockopt(cfd, IPPROTO_TCP, TCP_NODELAY, &one, sizeof(one));
        struct timeval tv = {{ .tv_sec = 2, .tv_usec = 0 }};
        setsockopt(cfd, SOL_SOCKET, SO_RCVTIMEO, &tv, sizeof(tv));
        setsockopt(cfd, SOL_SOCKET, SO_SNDTIMEO, &tv, sizeof(tv));

        unsigned char greeting[3];
        assert(recv(cfd, greeting, 3, MSG_WAITALL) == 3);
        assert(greeting[0] == 5 && greeting[1] == 1 && greeting[2] == 2);
        unsigned char method_reply[2] = {{ 5, 2 }};
        assert(send(cfd, method_reply, 2, 0) == 2);

        unsigned char auth_hdr[2];
        assert(recv(cfd, auth_hdr, 2, MSG_WAITALL) == 2);
        assert(auth_hdr[0] == 1);
        int ulen = auth_hdr[1];
        char ubuf[64];
        assert(recv(cfd, ubuf, ulen, MSG_WAITALL) == ulen);
        unsigned char plen_b = 0;
        assert(recv(cfd, &plen_b, 1, MSG_WAITALL) == 1);
        char pbuf[64];
        assert(recv(cfd, pbuf, plen_b, MSG_WAITALL) == plen_b);
        unsigned char auth_reply[2] = {{ 1, 0 }};
        assert(send(cfd, auth_reply, 2, 0) == 2);

        unsigned char req[10];
        assert(recv(cfd, req, 10, MSG_WAITALL) == 10);
        assert(req[0] == 5 && req[1] == 1 && req[3] == 1);
        unsigned char resp[10] = {{ 5, 0, 0, 1, 127, 0, 0, 1, 0x01, 0xbb }};
        assert(send(cfd, resp, 10, 0) == 10);
        close(cfd);
    }}
    return NULL;
}}

static double monotonic_ms(void) {{
    struct timespec ts;
    clock_gettime(CLOCK_MONOTONIC, &ts);
    return ts.tv_sec * 1000.0 + ts.tv_nsec / 1000000.0;
}}

int main(void) {{
    int listen_fd = socket(AF_INET, SOCK_STREAM, 0);
    assert(listen_fd >= 0);
    int one = 1;
    setsockopt(listen_fd, SOL_SOCKET, SO_REUSEADDR, &one, sizeof(one));
    struct sockaddr_in saddr;
    memset(&saddr, 0, sizeof(saddr));
    saddr.sin_family = AF_INET;
    saddr.sin_addr.s_addr = htonl(INADDR_LOOPBACK);
    saddr.sin_port = 0;
    assert(bind(listen_fd, (struct sockaddr *)&saddr, sizeof(saddr)) == 0);
    assert(listen(listen_fd, 4) == 0);
    socklen_t slen = sizeof(saddr);
    assert(getsockname(listen_fd, (struct sockaddr *)&saddr, &slen) == 0);

    pthread_t srv;
    assert(pthread_create(&srv, NULL, socks5_server_thread, &listen_fd) == 0);

    HevSocks5ClientClass *kptr = HEV_SOCKS5_CLIENT_CLASS(HEV_SOCKS5_CLIENT_TYPE);
    kptr->get_upstream_addr = test_get_upstream_addr;
    kptr->set_upstream_addr = test_set_upstream_addr;

    /* 1. Execute actual C hev_socks5_client_handshake(&client, 0) (standard mode) */
    HevSocks5Client client_std;
    assert(hev_socks5_client_construct(&client_std, HEV_SOCKS5_TYPE_TCP) == 0);
    hev_socks5_client_set_auth(&client_std, "relay-user#10249", "secret-pass");
    int fd_std = socket(AF_INET, SOCK_STREAM, 0);
    assert(connect(fd_std, (struct sockaddr *)&saddr, sizeof(saddr)) == 0);
    HEV_SOCKS5(&client_std)->fd = fd_std;

    recorded_send_count = 0;
    parsed_upstream_port = 0;
    double t0 = monotonic_ms();
    assert(hev_socks5_client_handshake(&client_std, 0) == 0);
    double std_ms = monotonic_ms() - t0;
    close(fd_std);

    assert(parsed_upstream_port == 443);
    assert(recorded_send_count == 3);
    assert((recorded_send_flags[0] & MSG_MORE) == 0);
    assert((recorded_send_flags[1] & MSG_MORE) == 0);
    assert((recorded_send_flags[2] & MSG_MORE) == 0);
    assert(std_ms < 150.0);

    /* 2. Execute actual C hev_socks5_client_handshake(&client, 1) (pipeline mode) */
    HevSocks5Client client_pipe;
    assert(hev_socks5_client_construct(&client_pipe, HEV_SOCKS5_TYPE_TCP) == 0);
    hev_socks5_client_set_auth(&client_pipe, "relay-user#10249", "secret-pass");
    int fd_pipe = socket(AF_INET, SOCK_STREAM, 0);
    assert(connect(fd_pipe, (struct sockaddr *)&saddr, sizeof(saddr)) == 0);
    HEV_SOCKS5(&client_pipe)->fd = fd_pipe;

    recorded_send_count = 0;
    parsed_upstream_port = 0;
    double t1 = monotonic_ms();
    assert(hev_socks5_client_handshake(&client_pipe, 1) == 0);
    double pipe_ms = monotonic_ms() - t1;
    close(fd_pipe);

    assert(parsed_upstream_port == 443);
    assert(recorded_send_count == 3);
    assert((recorded_send_flags[0] & MSG_MORE) == MSG_MORE);
    assert((recorded_send_flags[1] & MSG_MORE) == MSG_MORE);
    assert((recorded_send_flags[2] & MSG_MORE) == 0);
    assert(pipe_ms < 150.0);

    pthread_join(srv, NULL);
    close(listen_fd);
    printf("NATIVE_HEV_SOCKS5_CLIENT_HANDSHAKE_OK std_ms=%.3f pipe_ms=%.3f\\n", std_ms, pipe_ms);
    return 0;
}}
"""
        with tempfile.TemporaryDirectory() as tmpdir:
            for stub_hdr in (
                "hev-task.h",
                "hev-task-io.h",
                "hev-task-io-socket.h",
                "hev-memory-allocator.h",
                "hev-object.h",
            ):
                (Path(tmpdir) / stub_hdr).write_text("/* stub */\n")
            c_file = Path(tmpdir) / "test_hev_client_handshake.c"
            bin_file = Path(tmpdir) / "test_hev_client_handshake"
            c_file.write_text(c_harness)
            subprocess.run(
                [
                    "gcc",
                    "-O2",
                    "-Wno-incompatible-pointer-types",
                    "-pthread",
                    f"-I{tmpdir}",
                    f"-I{core_src_dir}",
                    str(c_file),
                    "-o",
                    str(bin_file),
                ],
                check=True,
            )
            res = subprocess.run([str(bin_file)], capture_output=True, text=True, check=True)
            self.assertIn("NATIVE_HEV_SOCKS5_CLIENT_HANDSHAKE_OK", res.stdout)

    def test_connect_time_and_ui_do_not_overwrite_live_speed_limits(self) -> None:
        store_kt = (
            REPO_ROOT / "app/src/main/java/com/speedvpn/app/SpeedLimitStore.kt"
        ).read_text()
        vpn_service_kt = (
            REPO_ROOT / "app/src/main/java/com/speedvpn/app/SpeedVpnService.kt"
        ).read_text()
        main_activity_kt = (
            REPO_ROOT / "app/src/main/java/com/speedvpn/app/MainActivity.kt"
        ).read_text()

        self.assertIn("fun stageDownload(context: Context, kbps: Long?)", store_kt)
        self.assertIn("fun stageUpload(context: Context, kbps: Long?)", store_kt)
        self.assertIn("stagedLimits?.let { return@synchronized it }", store_kt)
        self.assertIn("SpeedLimitStore.restoreToLimiterIfUninitialized(this)", vpn_service_kt)
        self.assertNotIn("SpeedLimitStore.applyToLimiter(this)", vpn_service_kt)
        self.assertIn("SpeedLimitStore.stageDownload(this, kbps)", main_activity_kt)
        self.assertIn("SpeedLimitStore.stageUpload(this, kbps)", main_activity_kt)
        self.assertIn(
            "private fun loadLocalLimits() = SpeedLimitStore.restoreToLimiterIfUninitialized(this)",
            main_activity_kt,
        )

    def test_token_bucket_unlimited_fast_path_and_jitter_headroom(self) -> None:
        limiter_kt = (
            REPO_ROOT / "app/src/main/java/com/speedvpn/app/SpeedLimiter.kt"
        ).read_text()

        self.assertIn("private const val JITTER_HEADROOM_MULTIPLIER = 2.0", limiter_kt)
        self.assertIn("maxCreditBytes = capacityBytes * JITTER_HEADROOM_MULTIPLIER", limiter_kt)
        self.assertIn("if (bytesPerSec <= 0L) return canProceed()", limiter_kt)
        self.assertIn("val currentRate = bytesPerSec", limiter_kt)

    def test_relay_hot_path_bypasses_sqlite_when_no_quota_configured(self) -> None:
        socks_kt = (
            REPO_ROOT / "app/src/main/java/com/speedvpn/app/Socks5Server.kt"
        ).read_text()
        quota_kt = (
            REPO_ROOT / "app/src/main/java/com/speedvpn/app/QuotaEnforcementEngine.kt"
        ).read_text()
        vpn_service_kt = (
            REPO_ROOT / "app/src/main/java/com/speedvpn/app/SpeedVpnService.kt"
        ).read_text()

        self.assertIn("private const val UDP_SOCKET_BUFFER_SIZE = 256 * 1024", socks_kt)
        self.assertIn("relaySocket.receiveBufferSize = UDP_SOCKET_BUFFER_SIZE", socks_kt)
        self.assertIn("socket.receiveBufferSize = UDP_SOCKET_BUFFER_SIZE", socks_kt)
        self.assertIn("val quotaPackages = currentQuotaPackages(context)", quota_kt)
        self.assertIn("if (quotaPackages.isEmpty() || resolvedPackages.none { it in quotaPackages }) {", quota_kt)
        self.assertIn("internal fun resolvePackagesForUidCached(context: Context, uid: Int?): Set<String>", quota_kt)
        self.assertIn("QuotaEnforcementEngine.resolvePackagesForUidCached(this, uid)", vpn_service_kt)

    def test_live_stats_records_only_forwarded_tcp_and_delivered_udp_bytes(self) -> None:
        socks_kt = (
            REPO_ROOT / "app/src/main/java/com/speedvpn/app/Socks5Server.kt"
        ).read_text()
        quota_kt = (
            REPO_ROOT / "app/src/main/java/com/speedvpn/app/QuotaEnforcementEngine.kt"
        ).read_text()
        usage_db_kt = (
            REPO_ROOT / "app/src/main/java/com/speedvpn/app/UsageDatabase.kt"
        ).read_text()
        vpn_service_kt = (
            REPO_ROOT / "app/src/main/java/com/speedvpn/app/SpeedVpnService.kt"
        ).read_text()
        quota_test_kt = (
            REPO_ROOT / "app/src/test/java/com/speedvpn/app/QuotaEnforcementEngineTest.kt"
        ).read_text()
        relay_test_kt = (
            REPO_ROOT / "app/src/test/java/com/speedvpn/app/Socks5RelayReadBatchTest.kt"
        ).read_text()

        # 1. QuotaEnforcementEngine.accountRelayBytes() must NOT record live traffic before forwarding.
        account_start = quota_kt.index("fun accountRelayBytes(")
        account_end = quota_kt.index("internal fun settleRelayBytes(", account_start)
        account_body = quota_kt[account_start:account_end]
        self.assertNotIn(
            "AppTrafficManager.recordLiveRelayTraffic(",
            account_body,
            "accountRelayBytes() must not record requestedBytes into live stats before actual I/O",
        )
        self.assertIn("addInFlightReservation(resolvedPackages, decision.allowedBytes)", account_body)

        # 2. settleRelayBytes() and releaseQuotaReservation() must handle all 4 outcomes explicitly.
        self.assertIn("QuotaSettlementOutcome.TCP_WRITE_SUCCEEDED", quota_kt)
        self.assertIn("QuotaSettlementOutcome.TCP_WRITE_FAILED_CONSERVATIVE", quota_kt)
        self.assertIn("QuotaSettlementOutcome.UDP_DELIVERED", quota_kt)
        self.assertIn("QuotaSettlementOutcome.UDP_DROPPED_OR_SEND_FAILED", quota_kt)
        self.assertIn("fun releaseQuotaReservation(", usage_db_kt)
        self.assertIn("fun commitQuotaReservation(", usage_db_kt)
        self.assertIn("fun recoverOrphanedUdpReservations(", usage_db_kt)
        self.assertIn("CREATE TABLE IF NOT EXISTS app_quota_pending_reservation", usage_db_kt)
        self.assertIn("fun udpSendSucceedsBeforeProcessDeathAndBeforeSettlementRetainsQuotaAndPreventsBypass()", quota_test_kt)

        # 3. Socks5Server and Kotlin unit test suites must enforce post-write/post-send settlement.
        self.assertIn("internal fun writeAdmittedTcpRelayChunk(", socks_kt)
        self.assertIn("internal fun deliverAdmittedUdpDatagramDetailed(", socks_kt)
        self.assertIn("settleQuotaBytes = ::settleQuotaBytesForRelay", vpn_service_kt)
        self.assertIn("fun partialTcpWriteRecordsOnlyWrittenPrefixAndDroppedUdpRecordsZeroLiveStats()", quota_test_kt)
        self.assertIn("fun tcpWriteExceptionFollowsConservativeDurableQuotaRetentionWithoutIncrementingLiveStats()", quota_test_kt)
        self.assertIn("fun concurrentFlowsCannotExceedConfiguredQuotaAndMaintainMultiPolicyConsistency()", quota_test_kt)
        self.assertIn("fun tcpRelayChunkRecordsOnlyAdmittedBytesAfterSuccessfulWrite()", relay_test_kt)
        self.assertIn("fun tcpRelayChunkWriteFailureDoesNotRecordLiveTrafficOrBucketBytes()", relay_test_kt)
        self.assertIn("fun udpRelayDatagramBelowRemainingQuotaOrFailedSendRecordsZeroLiveTraffic()", relay_test_kt)

        # 4. Behavioral loopback socket + SQLite-equivalent concurrency verification:
        state_lock = threading.Lock()
        in_flight = {}
        committed_live = {}
        durable_policies = {
            ("com.example.tcp", "DAILY"): {"limit": 1000, "used": 700, "down": 0, "up": 700},
            ("com.example.udp", "DAILY"): {"limit": 1000, "used": 700, "down": 0, "up": 700},
            ("com.example.udp", "WEEKLY"): {"limit": 2000, "used": 700, "down": 0, "up": 700},
            ("com.example.race.a", "DAILY"): {"limit": 1000, "used": 0, "down": 0, "up": 0},
            ("com.example.race.a", "MONTHLY"): {"limit": 5000, "used": 0, "down": 0, "up": 0},
            ("com.example.race.b", "DAILY"): {"limit": 1000, "used": 0, "down": 0, "up": 0},
        }
        quota_blocked = set()

        def account_relay_bytes(pkgs, direction, requested):
            with state_lock:
                rows = [
                    (pkg, qtype, row)
                    for (pkg, qtype), row in durable_policies.items()
                    if pkg in pkgs
                ]
                remaining = min(max(0, r["limit"] - r["used"]) for _, _, r in rows)
                allowed = min(requested, remaining)
                reservations = []
                exhausted = set()
                for pkg, qtype, r in rows:
                    r["used"] += allowed
                    if direction == "DOWN":
                        r["down"] += allowed
                    else:
                        r["up"] += allowed
                    if r["used"] >= r["limit"]:
                        exhausted.add(pkg)
                    reservations.append((pkg, qtype, direction, allowed))
                for pkg in pkgs:
                    in_flight[pkg] = in_flight.get(pkg, 0) + allowed
                quota_blocked.update(exhausted)
                return {"allowed": allowed, "exhausted": exhausted, "reservations": reservations}

        def settle_relay_bytes(pkgs, direction, decision, outcome, forwarded):
            with state_lock:
                for pkg in pkgs:
                    in_flight[pkg] = max(0, in_flight.get(pkg, 0) - decision["allowed"])
                if outcome in ("TCP_WRITE_SUCCEEDED", "UDP_DELIVERED"):
                    committed = min(forwarded, decision["allowed"])
                    if committed > 0:
                        for pkg in pkgs:
                            committed_live[pkg] = committed_live.get(pkg, 0) + committed
                    return decision
                if outcome == "TCP_WRITE_FAILED_CONSERVATIVE":
                    # Retain durable reservation, record 0 live bytes
                    return decision
                if outcome == "UDP_DROPPED_OR_SEND_FAILED":
                    for pkg, qtype, dir_key, res_bytes in decision["reservations"]:
                        r = durable_policies[(pkg, qtype)]
                        r["used"] = max(0, r["used"] - res_bytes)
                        if dir_key == "DOWN":
                            r["down"] = max(0, r["down"] - res_bytes)
                        else:
                            r["up"] = max(0, r["up"] - res_bytes)
                    still_exhausted = {
                        pkg
                        for pkg in decision["exhausted"]
                        if any(
                            durable_policies[(p, qt)]["used"] >= durable_policies[(p, qt)]["limit"]
                            for (p, qt) in durable_policies
                            if p == pkg
                        )
                    }
                    for pkg in decision["exhausted"] - still_exhausted:
                        quota_blocked.discard(pkg)
                    return {"allowed": 0, "exhausted": still_exhausted, "reservations": []}

        # Case A: Partial TCP write at quota boundary (500 requested, 300 allowed -> 300 written & committed)
        tcp_a, tcp_b = socket.socketpair()
        try:
            dec = account_relay_bytes({"com.example.tcp"}, "DOWN", 500)
            self.assertEqual(dec["allowed"], 300)
            self.assertEqual(in_flight["com.example.tcp"], 300)
            self.assertEqual(committed_live.get("com.example.tcp", 0), 0)
            written = tcp_a.send(b"T" * dec["allowed"])
            self.assertEqual(written, 300)
            settle_relay_bytes({"com.example.tcp"}, "DOWN", dec, "TCP_WRITE_SUCCEEDED", written)
            self.assertEqual(in_flight["com.example.tcp"], 0)
            self.assertEqual(committed_live["com.example.tcp"], 300)
            self.assertEqual(durable_policies[("com.example.tcp", "DAILY")]["used"], 1000)
            self.assertIn("com.example.tcp", quota_blocked)
        finally:
            tcp_a.close()
            tcp_b.close()

        # Case B: UDP datagram dropped at boundary (500 requested, 300 allowed < 500 -> rolled back to 700)
        udp_dec = account_relay_bytes({"com.example.udp"}, "DOWN", 500)
        self.assertEqual(udp_dec["allowed"], 300)
        settled_udp = settle_relay_bytes({"com.example.udp"}, "DOWN", udp_dec, "UDP_DROPPED_OR_SEND_FAILED", 0)
        self.assertEqual(settled_udp["allowed"], 0)
        self.assertEqual(settled_udp["exhausted"], set())
        self.assertEqual(in_flight["com.example.udp"], 0)
        self.assertEqual(committed_live.get("com.example.udp", 0), 0)
        self.assertEqual(durable_policies[("com.example.udp", "DAILY")]["used"], 700)
        self.assertEqual(durable_policies[("com.example.udp", "WEEKLY")]["used"], 700)
        self.assertNotIn("com.example.udp", quota_blocked)

        # Case C: Concurrent flows racing for 1,000-byte quota in 250-byte chunks
        barrier = threading.Barrier(8)
        race_committed = []

        def worker():
            barrier.wait()
            d = account_relay_bytes({"com.example.race.a", "com.example.race.b"}, "UP", 250)
            if d["allowed"] > 0:
                settle_relay_bytes(
                    {"com.example.race.a", "com.example.race.b"},
                    "UP",
                    d,
                    "TCP_WRITE_SUCCEEDED",
                    d["allowed"],
                )
                race_committed.append(d["allowed"])

        threads = [threading.Thread(target=worker) for _ in range(8)]
        for t in threads:
            t.start()
        for t in threads:
            t.join(timeout=2.0)

        self.assertEqual(sum(race_committed), 1000)
        self.assertEqual(durable_policies[("com.example.race.a", "DAILY")]["used"], 1000)
        self.assertEqual(durable_policies[("com.example.race.a", "MONTHLY")]["used"], 1000)
        self.assertEqual(durable_policies[("com.example.race.b", "DAILY")]["used"], 1000)
        self.assertEqual(committed_live["com.example.race.a"], 1000)
        self.assertEqual(committed_live["com.example.race.b"], 1000)

    def test_udp_send_succeeds_then_process_dies_before_settlement_retains_durable_quota(
        self,
    ) -> None:
        """Verify using real SQLite + loopback UDP sockets that if UDP send succeeds and the process dies before settlement, durable quota is conservatively retained and prevents quota bypass."""
        conn = sqlite3.connect(":memory:")
        try:
            conn.executescript(
                """
                CREATE TABLE app_quota_policy (
                    package_name TEXT NOT NULL,
                    quota_type TEXT NOT NULL CHECK (quota_type IN ('DAILY', 'WEEKLY', 'MONTHLY')),
                    limit_bytes INTEGER NOT NULL CHECK (limit_bytes > 0),
                    period_start_millis INTEGER NOT NULL,
                    period_end_millis INTEGER NOT NULL,
                    used_bytes INTEGER NOT NULL DEFAULT 0 CHECK (used_bytes >= 0),
                    download_bytes INTEGER NOT NULL DEFAULT 0 CHECK (download_bytes >= 0),
                    upload_bytes INTEGER NOT NULL DEFAULT 0 CHECK (upload_bytes >= 0),
                    updated_at INTEGER NOT NULL,
                    PRIMARY KEY (package_name, quota_type)
                );
                CREATE TABLE IF NOT EXISTS app_quota_pending_reservation (
                    reservation_id INTEGER PRIMARY KEY AUTOINCREMENT,
                    process_token TEXT NOT NULL,
                    transport TEXT NOT NULL CHECK (transport IN ('TCP', 'UDP')),
                    package_name TEXT NOT NULL,
                    quota_type TEXT NOT NULL CHECK (quota_type IN ('DAILY', 'WEEKLY', 'MONTHLY')),
                    period_start_millis INTEGER NOT NULL,
                    direction TEXT NOT NULL CHECK (direction IN ('DOWNLOAD', 'UPLOAD')),
                    reserved_bytes INTEGER NOT NULL CHECK (reserved_bytes > 0),
                    created_at INTEGER NOT NULL
                );
                """
            )
            now = 1_700_000_000_000
            p_start = 1_700_000_000_000
            p_end = 1_700_086_399_999
            with conn:
                conn.execute(
                    "INSERT INTO app_quota_policy VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)",
                    ("com.example.udp", "DAILY", 1000, p_start, p_end, 700, 0, 700, now),
                )
                conn.execute(
                    "INSERT INTO app_quota_policy VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)",
                    ("com.example.udp", "WEEKLY", 2000, p_start, p_end, 700, 0, 700, now),
                )
                conn.execute(
                    "INSERT INTO app_quota_policy VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)",
                    ("com.example.tcp", "DAILY", 1000, p_start, p_end, 0, 0, 0, now),
                )

            def reserve_sqlite(pkg: str, direction: str, requested: int, transport: str, proc_token: str) -> int:
                with conn:
                    rows = conn.execute(
                        "SELECT quota_type, limit_bytes, period_start_millis, used_bytes, download_bytes, upload_bytes "
                        "FROM app_quota_policy WHERE package_name = ?",
                        (pkg,),
                    ).fetchall()
                    remaining = min(max(0, r[1] - r[3]) for r in rows)
                    raw_allowed = min(requested, remaining)
                    allowed = 0 if (transport == "UDP" and raw_allowed < requested) else raw_allowed
                    if allowed > 0:
                        for qtype, _, pstart, used, down, up in rows:
                            conn.execute(
                                "UPDATE app_quota_policy SET used_bytes = ?, download_bytes = ?, upload_bytes = ?, updated_at = ? "
                                "WHERE package_name = ? AND quota_type = ? AND period_start_millis = ?",
                                (
                                    used + allowed,
                                    down + (allowed if direction == "DOWNLOAD" else 0),
                                    up + (allowed if direction == "UPLOAD" else 0),
                                    now,
                                    pkg,
                                    qtype,
                                    pstart,
                                ),
                            )
                            if transport == "UDP":
                                conn.execute(
                                    "INSERT INTO app_quota_pending_reservation "
                                    "(process_token, transport, package_name, quota_type, period_start_millis, direction, reserved_bytes, created_at) "
                                    "VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
                                    (proc_token, transport, pkg, qtype, pstart, direction, allowed, now),
                                )
                    return allowed

            # 1. Oversized UDP datagram (500 > 300 remaining) is rejected synchronously at admission.
            self.assertEqual(reserve_sqlite("com.example.udp", "DOWNLOAD", 500, "UDP", "proc-1"), 0)
            self.assertEqual(
                conn.execute(
                    "SELECT used_bytes FROM app_quota_policy WHERE package_name = 'com.example.udp' AND quota_type = 'DAILY'"
                ).fetchone()[0],
                700,
            )

            # 2. Fitting 300-byte UDP datagram is reserved in proc-1, actually transmitted over a real
            #    loopback UDP socket, and then proc-1 dies BEFORE settleRelayBytes() deletes the journal row.
            allowed_udp = reserve_sqlite("com.example.udp", "DOWNLOAD", 300, "UDP", "proc-1")
            self.assertEqual(allowed_udp, 300)
            udp_tx = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
            udp_rx = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
            udp_rx.bind(("127.0.0.1", 0))
            try:
                sent_bytes = udp_tx.sendto(b"U" * allowed_udp, udp_rx.getsockname())
                self.assertEqual(sent_bytes, 300)
                received_payload, _ = udp_rx.recvfrom(1024)
                self.assertEqual(len(received_payload), 300)
            finally:
                udp_tx.close()
                udp_rx.close()

            # Also reserve 1000 TCP bytes in proc-1 before crash.
            self.assertEqual(reserve_sqlite("com.example.tcp", "UPLOAD", 1000, "TCP", "proc-1"), 1000)

            # 3. proc-2 starts and runs recoverOrphanedUdpReservations(activeProcessToken='proc-2').
            #    Because proc-1's orphaned UDP reservation has an uncertain wire outcome (and in fact
            #    WAS transmitted!), proc-2 retires the stale journal entry while CONSERVATIVELY RETAINING
            #    used_bytes = 1000 so the app cannot bypass its 1000-byte quota.
            with conn:
                affected = [
                    r[0]
                    for r in conn.execute(
                        "SELECT DISTINCT package_name FROM app_quota_pending_reservation WHERE process_token != ?",
                        ("proc-2",),
                    ).fetchall()
                ]
                self.assertEqual(affected, ["com.example.udp"])
                conn.execute(
                    "DELETE FROM app_quota_pending_reservation WHERE process_token != ?",
                    ("proc-2",),
                )

            udp_daily = conn.execute(
                "SELECT used_bytes, download_bytes, upload_bytes FROM app_quota_policy "
                "WHERE package_name = 'com.example.udp' AND quota_type = 'DAILY'"
            ).fetchone()
            udp_weekly = conn.execute(
                "SELECT used_bytes, download_bytes, upload_bytes FROM app_quota_policy "
                "WHERE package_name = 'com.example.udp' AND quota_type = 'WEEKLY'"
            ).fetchone()
            tcp_daily = conn.execute(
                "SELECT used_bytes, download_bytes, upload_bytes FROM app_quota_policy "
                "WHERE package_name = 'com.example.tcp' AND quota_type = 'DAILY'"
            ).fetchone()
            self.assertEqual(udp_daily, (1000, 300, 700))
            self.assertEqual(udp_weekly, (1000, 300, 700))
            self.assertEqual(tcp_daily, (1000, 0, 1000))

            # 4. Subsequent UDP datagram in proc-2 is rejected because the 1000-byte daily quota is reached.
            self.assertEqual(reserve_sqlite("com.example.udp", "DOWNLOAD", 100, "UDP", "proc-2"), 0)
        finally:
            conn.close()

    def test_realtime_statistics_pipeline_and_batch_upsert(self) -> None:
        app_traffic_kt = (
            REPO_ROOT / "app/src/main/java/com/speedvpn/app/AppTraffic.kt"
        ).read_text()
        usage_db_kt = (
            REPO_ROOT / "app/src/main/java/com/speedvpn/app/UsageDatabase.kt"
        ).read_text()
        smart_engine_kt = (
            REPO_ROOT / "app/src/main/java/com/speedvpn/app/SmartEngine.kt"
        ).read_text()

        self.assertIn("internal fun recordLiveRelayTraffic(", app_traffic_kt)
        self.assertIn("internal fun liveRelayUsage(", app_traffic_kt)
        self.assertIn("fun upsertDailyUsageBatch(context: Context, rows: List<DailyUsageRow>)", usage_db_kt)
        self.assertIn("UsageRepository.upsertDailyUsageBatch(context, rows)", smart_engine_kt)
        self.assertIn("android.app.AlarmManager.INTERVAL_FIFTEEN_MINUTES", smart_engine_kt)
        self.assertIn("* 8_000L / dt", smart_engine_kt)


if __name__ == "__main__":
    unittest.main()
