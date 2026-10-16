package hev.htproxy;

/** JNI bridge to hev-socks5-tunnel (open-source tun2socks, runs its own native thread). */
public final class TProxyService {
    static {
        System.loadLibrary("hev-socks5-tunnel");
    }

    private TProxyService() {}

    public static native boolean TProxyStartService(String configPath, int fd);

    public static native boolean TProxyStopService();

    public static native boolean TProxyIsRunning();

    /** Temporary POC callback for per-flow UID resolution and packet policy decisions. */
    public interface UidPocCallback {
        /**
         * Synchronous resolver callback on the dedicated native UID worker (never the HEV
         * packet coroutine). Returns a packed long: signed UID in high 32 bits, low bit 1
         * for BLOCK/DROP and 0 for allow/observe.
         */
        long onTunPacket(int protocol, byte[] srcAddress, int srcPort,
                         byte[] dstAddress, int dstPort);
    }

    /** Temporary POC JNI hook. Present only on poc/uid-detection-test. */
    public static native boolean TProxySetUidPocCallback(Object callback);

    /** [txPackets, txBytes, rxPackets, rxBytes] */
    public static native long[] TProxyGetStats();
}
