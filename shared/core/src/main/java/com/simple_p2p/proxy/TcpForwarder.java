package com.simple_p2p.proxy;

import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 纯 TCP 监听→转发代理：把 {@code bindHost:bindPort} 收到的连接转发到 {@code targetHost:targetPort}
 * （如 127.0.0.1:25565），用于 EasyTier 整合服场景。
 */
public final class TcpForwarder {

    private final AtomicBoolean running = new AtomicBoolean(false);
    private ServerSocket serverSocket;
    private Thread acceptThread;

    /** 启动转发：监听 bindHost:bindPort，转发到 targetHost:targetPort。返回实际绑定端口。 */
    public synchronized int start(String bindHost, int bindPort,
                                  String targetHost, int targetPort) throws IOException {
        if (running.get()) return serverSocket.getLocalPort();
        serverSocket = new ServerSocket();
        serverSocket.setReuseAddress(true);
        serverSocket.bind(new InetSocketAddress(bindHost, bindPort));
        running.set(true);
        final String tHost = targetHost == null ? "127.0.0.1" : targetHost;
        final int tPort = targetPort;

        acceptThread = new Thread(() -> {
            while (running.get() && !serverSocket.isClosed()) {
                try {
                    Socket client = serverSocket.accept();
                    new Thread(() -> handle(client, tHost, tPort), "SimpleP2P-Fwd")
                            .start();
                } catch (IOException e) {
                    if (running.get()) {
                    }
                }
            }
        }, "SimpleP2P-TcpFwd-Accept");
        acceptThread.setDaemon(true);
        acceptThread.start();
        return serverSocket.getLocalPort();
    }

    private void handle(Socket inbound, String targetHost, int targetPort) {
        Socket out = null;
        try {
            out = new Socket();
            out.connect(new InetSocketAddress(targetHost, targetPort), 5000);
            out.setTcpNoDelay(true);
            inbound.setTcpNoDelay(true);
            Thread t1 = pipe(inbound.getInputStream(), out.getOutputStream(), inbound, out);
            Thread t2 = pipe(out.getInputStream(), inbound.getOutputStream(), out, inbound);
            try {
                t1.join();
                t2.join();
            } catch (InterruptedException ignored) {
            }
        } catch (Exception e) {
            System.err.println("[SimpleP2P] 转发连接失败(目标 " + targetHost + ":" + targetPort + "): "
                    + e.getMessage() + "（客户端将看到 Connection reset）");
            closeQuietly(inbound);
            closeQuietly(out);
        }
    }

    private static Thread pipe(InputStream in, OutputStream out, Closeable a, Closeable b) {
        Thread t = new Thread(() -> {
            try {
                byte[] buf = new byte[8192];
                int n;
                while ((n = in.read(buf)) != -1) {
                    out.write(buf, 0, n);
                    out.flush();
                }
            } catch (Exception ignored) {
            } finally {
                closeQuietly(a);
                closeQuietly(b);
            }
        }, "SimpleP2P-Pipe");
        t.setDaemon(true);
        t.start();
        return t;
    }

    private static void closeQuietly(Closeable c) {
        try { if (c != null) c.close(); } catch (Exception ignored) {}
    }

    public synchronized void stop() {
        if (!running.compareAndSet(true, false)) return;
        if (serverSocket != null) try { serverSocket.close(); } catch (Exception ignored) {}
        if (acceptThread != null) acceptThread.interrupt();
    }

    public boolean isRunning() { return running.get(); }
}
