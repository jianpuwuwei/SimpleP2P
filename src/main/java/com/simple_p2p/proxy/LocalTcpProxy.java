package com.simple_p2p.proxy;

import com.simple_p2p.p2p.ReliableUdpTunnel;

import java.io.*;
import java.net.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 本地TCP端口代理
 * 职责：
 *
 * 【客户端侧】
 *  本地启动一个TCP监听端口（例如 127.0.0.1:25566），
 *  Minecraft客户端连接此端口时，代理将连接数据流桥接到已建立的P2P隧道，
 *  等于玩家通过房间号间接连接到了远端MC服务器。
 *
 * 【服务端侧】
 *  P2PServerAcceptor 有客户端连接进来时，将隧道的数据流桥接到本地MC服务端口(25565)。
 *  这通过静态方法 bridgeTunnelToLocalMc 实现。
 */
public class LocalTcpProxy {

    private final AtomicBoolean running = new AtomicBoolean(false);
    private ServerSocket serverSocket;
    private Thread acceptThread;
    private final ReliableUdpTunnelProvider tunnelProvider;
    private int listenPort;
    private final AtomicInteger connections = new AtomicInteger(0);

    /**
     * 提供隧道的回调：每次有新的MC客户端连接时，需要一条隧道
     */
    public interface ReliableUdpTunnelProvider {
        ReliableUdpTunnel provide() throws Exception;
    }

    public LocalTcpProxy(ReliableUdpTunnelProvider provider) {
        this.tunnelProvider = provider;
    }

    /**
     * 启动本地代理监听
     *
     * @param bindAddr   绑定地址（客户端侧建议127.0.0.1）
     * @param preferPort 期望端口，0=随机
     * @return 实际监听的端口
     */
    public synchronized int start(String bindAddr, int preferPort) throws IOException {
        if (running.get()) return listenPort;
        serverSocket = new ServerSocket();
        serverSocket.setReuseAddress(true);
        InetAddress addr = bindAddr != null && !bindAddr.isEmpty()
                ? InetAddress.getByName(bindAddr)
                : InetAddress.getLoopbackAddress();
        serverSocket.bind(new InetSocketAddress(addr, preferPort));
        listenPort = serverSocket.getLocalPort();
        running.set(true);

        acceptThread = new Thread(() -> {
            try {
                while (running.get() && !serverSocket.isClosed()) {
                    Socket client = serverSocket.accept();
                    new Thread(() -> handleClient(client), "AIO-Proxy-Conn-" + connections.incrementAndGet())
                            .start();
                }
            } catch (Exception e) {
                if (running.get())
                    System.err.println("[SimpleP2P] 代理监听异常: " + e.getMessage());
            }
        }, "AIO-Proxy-Accept");
        acceptThread.setDaemon(true);
        acceptThread.start();
        return listenPort;
    }

    private void handleClient(Socket mcClientSocket) {
        ReliableUdpTunnel tunnel = null;
        try {
            mcClientSocket.setTcpNoDelay(true);
            mcClientSocket.setSoTimeout(0);
            // 每次MC客户端连接，向P2P层申请一条隧道
            tunnel = tunnelProvider.provide();
            if (tunnel == null) {
                try { mcClientSocket.close(); } catch (Exception ignored) {}
                return;
            }
            // 双向拷贝
            bidirectionalPipe(
                    "MC客户端<->P2P",
                    mcClientSocket.getInputStream(),
                    mcClientSocket.getOutputStream(),
                    tunnel.getInputStream(),
                    tunnel.getOutputStream(),
                    mcClientSocket,
                    tunnel
            );
        } catch (Exception e) {
            System.err.println("[SimpleP2P] 处理MC客户端代理连接失败: " + e.getMessage());
            try { mcClientSocket.close(); } catch (Exception ignored) {}
            if (tunnel != null) try { tunnel.close(); } catch (Exception ignored) {}
        }
    }

    /**
     * 服务端侧：将连入的P2P隧道桥接到本地MC服务器端口
     *
     * @param tunnel       连入的P2P可靠隧道
     * @param mcHost       MC服务端地址（通常127.0.0.1）
     * @param mcPort       MC服务端端口（通常25565）
     */
    public static void bridgeTunnelToLocalMc(final ReliableUdpTunnel tunnel, String mcHost, int mcPort) {
        new Thread(() -> {
            Socket mcSocket = null;
            try {
                mcSocket = new Socket();
                mcSocket.connect(new InetSocketAddress(mcHost != null ? mcHost : "127.0.0.1", mcPort), 5000);
                mcSocket.setTcpNoDelay(true);
                mcSocket.setSoTimeout(0);

                bidirectionalPipe(
                        "P2P<->MC服务端",
                        tunnel.getInputStream(),
                        tunnel.getOutputStream(),
                        mcSocket.getInputStream(),
                        mcSocket.getOutputStream(),
                        mcSocket,
                        tunnel
                );
            } catch (Exception e) {
                System.err.println("[SimpleP2P] 桥接隧道到MC服务端失败: " + e.getMessage());
                if (mcSocket != null) try { mcSocket.close(); } catch (Exception ignored) {}
                try { tunnel.close(); } catch (Exception ignored) {}
            }
        }, "AIO-Bridge-ToMC").start();
    }

    /**
     * 双向流拷贝：aIn->bOut, bIn->aOut；任一端关闭时同时关闭closableA和closableB
     */
    private static void bidirectionalPipe(String label,
                                          InputStream aIn, OutputStream aOut,
                                          InputStream bIn, OutputStream bOut,
                                          Closeable closableA, Closeable closableB) {
        Thread t1 = new Thread(() -> {
            try {
                copy(aIn, bOut);
            } catch (Exception ignored) {
            } finally {
                safeClose(closableA);
                safeClose(closableB);
            }
        }, label + "-A");
        Thread t2 = new Thread(() -> {
            try {
                copy(bIn, aOut);
            } catch (Exception ignored) {
            } finally {
                safeClose(closableA);
                safeClose(closableB);
            }
        }, label + "-B");
        t1.setDaemon(true);
        t2.setDaemon(true);
        t1.start();
        t2.start();
        try {
            t1.join();
            t2.join();
        } catch (InterruptedException ignored) {
        }
    }

    private static void copy(InputStream in, OutputStream out) throws IOException {
        byte[] buf = new byte[8192];
        int n;
        while ((n = in.read(buf)) != -1) {
            out.write(buf, 0, n);
            out.flush();
        }
    }

    private static void safeClose(Closeable c) {
        try { if (c != null) c.close(); } catch (Exception ignored) {}
    }

    public synchronized void stop() {
        if (!running.compareAndSet(true, false)) return;
        if (serverSocket != null) try { serverSocket.close(); } catch (Exception ignored) {}
        if (acceptThread != null) acceptThread.interrupt();
    }

    public int getListenPort() {
        return listenPort;
    }
}
