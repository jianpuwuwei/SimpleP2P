package com.simple_p2p.p2p;

import com.simple_p2p.config.ModConfig;
import com.simple_p2p.enums.ConnectionType;

import java.io.*;
import java.net.*;
import java.nio.ByteBuffer;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 可靠的 UDP 通道 - 在 UDP 之上实现类 TCP 连接（可靠、有序、带重传），
 * 用于承载 Minecraft 的 TCP 数据流；中继模式下底层实际走 TCP。
 *
 * 协议包结构(二进制)：[2字节类型][4字节包序号][4字节窗口/确认号][4字节长度][payload]
 * 类型: 1=DATA 2=ACK 3=FIN 4=KEEPALIVE
 */
public class ReliableUdpTunnel implements Closeable {

    // 包类型
    public static final short TYPE_DATA = 1;
    public static final short TYPE_ACK = 2;
    public static final short TYPE_FIN = 3;
    public static final short TYPE_KEEPALIVE = 4;

    // 头部长度 = 2+4+4+4 = 14
    private static final int HEADER_SIZE = 14;
    private static final int MAX_PAYLOAD = 1400;

    private final DatagramSocket udpSocket;
    private final InetSocketAddress remote;
    private final boolean isRelayMode; // true=中继(TCP), false=P2P(UDP)
    private final Socket relaySocket;   // 中继模式下使用

    private final AtomicInteger sendSeq = new AtomicInteger(1);
    private final AtomicInteger recvExpected = new AtomicInteger(1);
    private final Map<Integer, byte[]> unacked = new ConcurrentHashMap<>();
    private final Map<Integer, byte[]> recvBuf = new ConcurrentHashMap<>();

    private final PipedInputStream inputStream;
    private final PipedOutputStream inputPipeWriter;
    private final PipedOutputStream outputStream;
    private final PipedInputStream outputPipeReader;

    private final AtomicBoolean running = new AtomicBoolean(true);
    private Thread recvThread;
    private Thread sendThread;
    private Thread keepAliveThread;
    private long lastActivity = System.currentTimeMillis();

    private ConnectionType connectionType;
    private final ModConfig config;

    // ================== P2P(UDP)构造 ==================

    public ReliableUdpTunnel(DatagramSocket socket, InetSocketAddress remoteAddr) {
        this.udpSocket = socket;
        this.remote = remoteAddr;
        this.isRelayMode = false;
        this.relaySocket = null;
        this.connectionType = ConnectionType.P2P_DIRECT;
        this.config = ModConfig.getInstance();
        try {
            this.inputStream = new PipedInputStream(65536);
            this.inputPipeWriter = new PipedOutputStream(inputStream);
            this.outputStream = new PipedOutputStream();
            this.outputPipeReader = new PipedInputStream(outputStream, 65536);
        } catch (IOException e) {
            throw new RuntimeException("创建管道失败", e);
        }
    }

    // ================== 中继(TCP)构造 ==================

    public ReliableUdpTunnel(Socket relaySocket) {
        this.udpSocket = null;
        this.remote = null;
        this.isRelayMode = true;
        this.relaySocket = relaySocket;
        this.connectionType = ConnectionType.RELAY;
        this.config = ModConfig.getInstance();
        try {
            this.inputStream = new PipedInputStream(65536);
            this.inputPipeWriter = new PipedOutputStream(inputStream);
            this.outputStream = new PipedOutputStream();
            this.outputPipeReader = new PipedInputStream(outputStream, 65536);
        } catch (IOException e) {
            throw new RuntimeException("创建管道失败", e);
        }
    }

    public ConnectionType getConnectionType() {
        return connectionType;
    }

    public boolean isRelayMode() {
        return isRelayMode;
    }

    /** 启动隧道收发线程。 */
    public void start() {
        if (isRelayMode) {
            startRelayBridge();
        } else {
            startUdpReceiver();
            startUdpSender();
            startKeepAlive();
        }
    }

    /** 读取从对端收到的数据。 */
    public InputStream getInputStream() {
        return inputStream;
    }

    /** 写入的数据将发送到对端。 */
    public OutputStream getOutputStream() {
        return outputStream;
    }

    // ================== 中继模式：直接TCP桥接 ==================

    private void startRelayBridge() {
        // 中继TCP socket <-> 管道 双向拷贝
        Thread t1 = new Thread(() -> {
            try {
                byte[] buf = new byte[8192];
                InputStream in = relaySocket.getInputStream();
                int n;
                while (running.get() && (n = in.read(buf)) != -1) {
                    inputPipeWriter.write(buf, 0, n);
                    inputPipeWriter.flush();
                    lastActivity = System.currentTimeMillis();
                }
            } catch (Exception e) {
            } finally {
                close();
            }
        }, "AIO-Relay-In");
        t1.setDaemon(true);
        t1.start();

        Thread t2 = new Thread(() -> {
            try {
                byte[] buf = new byte[8192];
                OutputStream out = relaySocket.getOutputStream();
                int n;
                while (running.get() && (n = outputPipeReader.read(buf)) != -1) {
                    out.write(buf, 0, n);
                    out.flush();
                    lastActivity = System.currentTimeMillis();
                }
            } catch (Exception e) {
            } finally {
                close();
            }
        }, "AIO-Relay-Out");
        t2.setDaemon(true);
        t2.start();
    }

    // ================== UDP模式：可靠传输逻辑 ==================

    private void startUdpReceiver() {
        recvThread = new Thread(() -> {
            try {
                byte[] buf = new byte[65536];
                while (running.get() && !udpSocket.isClosed()) {
                    DatagramPacket p = new DatagramPacket(buf, buf.length);
                    try {
                        udpSocket.receive(p);
                    } catch (SocketTimeoutException e) {
                        continue;
                    } catch (IOException e) {
                        break;
                    }
                    lastActivity = System.currentTimeMillis();
                    if (p.getLength() < HEADER_SIZE) continue;
                    ByteBuffer bb = ByteBuffer.wrap(p.getData(), p.getOffset(), p.getLength());
                    short type = bb.getShort();
                    int seq = bb.getInt();
                    int ack = bb.getInt();
                    int len = bb.getInt();

                    switch (type) {
                        case TYPE_DATA:
                            if (len > 0 && len <= bb.remaining()) {
                                byte[] data = new byte[len];
                                bb.get(data);
                                handleDataPacket(seq, data);
                            }
                            break;
                        case TYPE_ACK:
                            handleAck(ack);
                            break;
                        case TYPE_FIN:
                            close();
                            return;
                        case TYPE_KEEPALIVE:
                            // 回保活
                            sendKeepAlive();
                            break;
                    }
                }
            } catch (Exception e) {
                // receiver exit
            }
        }, "AIO-RUDP-Recv");
        recvThread.setDaemon(true);
        recvThread.start();
    }

    private void startUdpSender() {
        sendThread = new Thread(() -> {
            try {
                byte[] buf = new byte[MAX_PAYLOAD];
                while (running.get()) {
                    int n = outputPipeReader.read(buf);
                    if (n <= 0) break;
                    int offset = 0;
                    while (offset < n) {
                        int toSend = Math.min(MAX_PAYLOAD, n - offset);
                        byte[] payload = new byte[toSend];
                        System.arraycopy(buf, offset, payload, 0, toSend);
                        sendDataPacket(payload);
                        offset += toSend;
                    }
                }
            } catch (Exception e) {
                // sender exit
            } finally {
                close();
            }
        }, "AIO-RUDP-Send");
        sendThread.setDaemon(true);
        sendThread.start();

        // 重传线程
        new Thread(() -> {
            try {
                while (running.get()) {
                    Thread.sleep(300);
                    long now = System.currentTimeMillis();
                    for (Map.Entry<Integer, byte[]> entry : unacked.entrySet()) {
                        // 每 300ms 重传未确认包
                        try {
                            udpSend(entry.getValue());
                        } catch (Exception ignored) {}
                    }
                    // 20秒无活动自动关闭
                    if (now - lastActivity > 20000) {
                        close();
                        return;
                    }
                }
            } catch (Exception ignored) {
            }
        }, "AIO-RUDP-Retx").start();
    }

    private void startKeepAlive() {
        keepAliveThread = new Thread(() -> {
            try {
                while (running.get()) {
                    Thread.sleep(10000);
                    if (System.currentTimeMillis() - lastActivity > 5000) {
                        sendKeepAlive();
                    }
                }
            } catch (Exception ignored) {
            }
        }, "AIO-RUDP-KA");
        keepAliveThread.setDaemon(true);
        keepAliveThread.start();
    }

    private void handleDataPacket(int seq, byte[] data) {
        sendAck(seq);
        if (seq == recvExpected.get()) {
            // 顺序到达，直接写入
            try {
                inputPipeWriter.write(data);
                inputPipeWriter.flush();
            } catch (IOException ignored) {}
            int next = recvExpected.incrementAndGet();
            // 检查有没有后续连续的包
            while (recvBuf.containsKey(next)) {
                byte[] d = recvBuf.remove(next);
                try {
                    inputPipeWriter.write(d);
                    inputPipeWriter.flush();
                } catch (IOException ignored) {}
                next = recvExpected.incrementAndGet();
            }
        } else if (seq > recvExpected.get()) {
            // 乱序，先缓存
            recvBuf.put(seq, data);
        }
        // seq < expected: 重复包，丢弃但已回ACK
    }

    private void handleAck(int ack) {
        // 移除序号<=ack的未确认包
        unacked.entrySet().removeIf(e -> e.getKey() <= ack);
    }

    private void sendDataPacket(byte[] payload) {
        int seq = sendSeq.getAndIncrement();
        ByteBuffer bb = ByteBuffer.allocate(HEADER_SIZE + payload.length);
        bb.putShort(TYPE_DATA);
        bb.putInt(seq);
        bb.putInt(recvExpected.get() - 1); // 捎带ACK
        bb.putInt(payload.length);
        bb.put(payload);
        byte[] raw = bb.array();
        unacked.put(seq, raw);
        try {
            udpSend(raw);
        } catch (Exception ignored) {}
    }

    private void sendAck(int seq) {
        ByteBuffer bb = ByteBuffer.allocate(HEADER_SIZE);
        bb.putShort(TYPE_ACK);
        bb.putInt(0);
        bb.putInt(seq);
        bb.putInt(0);
        try {
            udpSend(bb.array());
        } catch (Exception ignored) {}
    }

    private void sendKeepAlive() {
        ByteBuffer bb = ByteBuffer.allocate(HEADER_SIZE);
        bb.putShort(TYPE_KEEPALIVE);
        bb.putInt(0);
        bb.putInt(0);
        bb.putInt(0);
        try {
            udpSend(bb.array());
        } catch (Exception ignored) {}
    }

    private void udpSend(byte[] data) throws IOException {
        if (udpSocket == null || udpSocket.isClosed() || remote == null) return;
        DatagramPacket p = new DatagramPacket(data, data.length, remote);
        udpSocket.send(p);
    }

    @Override
    public void close() {
        if (!running.compareAndSet(true, false)) return;
        try {
            if (!isRelayMode && udpSocket != null && !udpSocket.isClosed()) {
                // 发FIN
                ByteBuffer bb = ByteBuffer.allocate(HEADER_SIZE);
                bb.putShort(TYPE_FIN);
                bb.putInt(0); bb.putInt(0); bb.putInt(0);
                try { udpSend(bb.array()); } catch (Exception ignored) {}
            }
        } catch (Exception ignored) {}
        try { inputPipeWriter.close(); } catch (Exception ignored) {}
        try { inputStream.close(); } catch (Exception ignored) {}
        try { outputStream.close(); } catch (Exception ignored) {}
        try { outputPipeReader.close(); } catch (Exception ignored) {}
        if (!isRelayMode) {
            if (udpSocket != null) try { udpSocket.close(); } catch (Exception ignored) {}
        } else {
            if (relaySocket != null) try { relaySocket.close(); } catch (Exception ignored) {}
        }
    }
}
