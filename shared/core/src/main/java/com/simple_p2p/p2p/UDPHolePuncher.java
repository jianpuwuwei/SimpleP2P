package com.simple_p2p.p2p;

import com.simple_p2p.config.ModConfig;
import com.simple_p2p.enums.ConnectionType;
import com.simple_p2p.signaling.SignalingMessage;

import java.io.IOException;
import java.net.*;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * UDP 打洞器：双方同时向对端的公网/内网地址发送 PUNCH 包，
 * 收到任一 PUNCH_ACK 即打洞成功；超时失败由上层切换到中继模式。
 */
public class UDPHolePuncher {

    private final ModConfig config;
    private DatagramSocket socket;
    private final AtomicBoolean punched = new AtomicBoolean(false);
    private InetSocketAddress successRemote;
    private Thread receiverThread;

    public UDPHolePuncher() {
        this.config = ModConfig.getInstance();
    }

    public static class PunchResult {
        public final boolean success;
        public final ConnectionType connectionType;
        public final InetSocketAddress remoteAddress;
        public final DatagramSocket socket; // 打洞成功时可继续使用的 socket
        public final String errorMessage;

        PunchResult(boolean success, ConnectionType type, InetSocketAddress addr, DatagramSocket s, String err) {
            this.success = success;
            this.connectionType = type;
            this.remoteAddress = addr;
            this.socket = s;
            this.errorMessage = err;
        }

        public static PunchResult successP2P(InetSocketAddress addr, DatagramSocket s) {
            return new PunchResult(true, ConnectionType.P2P_DIRECT, addr, s, null);
        }

        public static PunchResult fail(String err) {
            return new PunchResult(false, ConnectionType.NONE, null, null, err);
        }
    }

    /** 主动方打洞：向对端公网和内网地址发送打洞包，参数为对端公网/内网 IP:Port。 */
    public PunchResult punch(String remotePublicIp, int remotePublicPort,
                             String remotePrivateIp, int remotePrivatePort) {
        try {
            socket = new DatagramSocket();
            socket.setSoTimeout(config.getHolePunchTimeoutMs());

            // 启动接收线程，等待PUNCH_ACK
            startReceiver();

            InetAddress publicAddr = null;
            InetAddress privateAddr = null;
            try {
                if (remotePublicIp != null && remotePublicPort > 0) {
                    publicAddr = InetAddress.getByName(remotePublicIp);
                }
            } catch (Exception ignored) {}
            try {
                if (remotePrivateIp != null && remotePrivatePort > 0) {
                    privateAddr = InetAddress.getByName(remotePrivateIp);
                }
            } catch (Exception ignored) {}

            if (publicAddr == null && privateAddr == null) {
                close();
                return PunchResult.fail("没有可用的对端地址");
            }

            SignalingMessage punchMsg = new SignalingMessage(SignalingMessage.TYPE_PUNCH);
            punchMsg.peerId = "client";

            // 在超时时间内，分多次向公网和内网地址发送打洞包
            int retries = config.getHolePunchRetries();
            long interval = Math.max(200, config.getHolePunchTimeoutMs() / retries);
            for (int i = 0; i < retries && !punched.get(); i++) {
                if (publicAddr != null) {
                    punchMsg.sendUdp(socket, publicAddr, remotePublicPort);
                }
                if (privateAddr != null && (publicAddr == null || !privateAddr.equals(publicAddr))) {
                    punchMsg.sendUdp(socket, privateAddr, remotePrivatePort);
                }
                if (punched.get()) break;
                Thread.sleep(interval);
            }

            // 再多等一会等待ACK
            Thread.sleep(500);

            if (punched.get() && successRemote != null) {
                return PunchResult.successP2P(successRemote, socket);
            }

            close();
            return PunchResult.fail("打洞超时，无ACK响应");
        } catch (Exception e) {
            close();
            return PunchResult.fail("打洞异常: " + e.getMessage());
        }
    }

    /** 被动方（服务端）打洞：等待客户端打洞包并回复 ACK。 */
    public PunchResult waitForPunch(int localBindPort) {
        try {
            socket = new DatagramSocket(localBindPort > 0 ? localBindPort : 0);
            socket.setSoTimeout(config.getHolePunchTimeoutMs() * 2);
            startReceiver();

            long deadline = System.currentTimeMillis() + (long) config.getHolePunchTimeoutMs() * 2;
            while (System.currentTimeMillis() < deadline && !punched.get()) {
                Thread.sleep(200);
            }

            if (punched.get() && successRemote != null) {
                return PunchResult.successP2P(successRemote, socket);
            }
            close();
            return PunchResult.fail("等待打洞连接超时");
        } catch (Exception e) {
            close();
            return PunchResult.fail("等待异常: " + e.getMessage());
        }
    }

    private void startReceiver() {
        receiverThread = new Thread(() -> {
            try {
                byte[] buf = new byte[4096];
                while (!Thread.interrupted() && socket != null && !socket.isClosed()) {
                    DatagramPacket p = new DatagramPacket(buf, buf.length);
                    try {
                        socket.receive(p);
                    } catch (SocketTimeoutException e) {
                        continue;
                    } catch (IOException e) {
                        break;
                    }
                    String json = new String(p.getData(), 0, p.getLength());
                    SignalingMessage msg = SignalingMessage.fromJson(json);
                    if (msg == null) continue;

                    if (SignalingMessage.TYPE_PUNCH.equals(msg.type)) {
                        // 收到打洞包，回复ACK到对端
                        SignalingMessage ack = new SignalingMessage(SignalingMessage.TYPE_PUNCH_ACK);
                        try {
                            ack.sendUdp(socket, p.getAddress(), p.getPort());
                        } catch (IOException ignored) {}
                        if (punched.compareAndSet(false, true)) {
                            successRemote = new InetSocketAddress(p.getAddress(), p.getPort());
                        }
                    } else if (SignalingMessage.TYPE_PUNCH_ACK.equals(msg.type)) {
                        if (punched.compareAndSet(false, true)) {
                            successRemote = new InetSocketAddress(p.getAddress(), p.getPort());
                        }
                    }
                }
            } catch (Exception e) {
            }
        }, "AIO-P2P-HolePunch-Recv");
        receiverThread.setDaemon(true);
        receiverThread.start();
    }

    public void close() {
        punched.set(false);
        successRemote = null;
        if (receiverThread != null) {
            receiverThread.interrupt();
            receiverThread = null;
        }
        if (socket != null && !socket.isClosed()) {
            try { socket.close(); } catch (Exception ignored) {}
        }
        socket = null;
    }
}
