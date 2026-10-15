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

    /** Temporary POC callback for outbound packets observed at the native TUN read point. */
    public interface UidPocCallback {
        /** Synchronous UID resolution. Native invokes this only on its dedicated UID worker,
         * never on the HEV packet coroutine. */
        int onTunPacket(int protocol, byte[] srcAddress, int srcPort,
                        byte[] dstAddress, int dstPort);
    }

    /** Temporary POC JNI hook. Present only on poc/uid-detection-test. */
    public static native boolean TProxySetUidPocCallback(Object callback);

    /** [txPackets, txBytes, rxPackets, rxBytes] */
    public static native long[] TProxyGetStats();
}
