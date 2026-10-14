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
        void onTunPacket(int protocol, byte[] srcAddress, int srcPort,
                         byte[] dstAddress, int dstPort);
    }

    /** Temporary POC JNI hook. Present only on poc/uid-detection-test. */
    public static native boolean TProxySetUidPocCallback(Object callback);

    /** POC-only native crash diagnostics path, located under app-private filesDir. */
    public static native boolean TProxySetDiagnosticPath(String directory);

    /** [txPackets, txBytes, rxPackets, rxBytes] */
    public static native long[] TProxyGetStats();
}
