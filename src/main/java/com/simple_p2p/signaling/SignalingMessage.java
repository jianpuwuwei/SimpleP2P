package com.simple_p2p.signaling;

import com.simple_p2p.util.SimpleJson;

import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 信令协议消息定义
 * 用于客户端<->信令服务器，以及客户端之间交换打洞信息
 *
 * 消息格式(JSON):
 * {
 *   "type": "REGISTER | CONNECT | PUNCH | RELAY | PROBE | ACK | ERROR",
 *   "token": "openp2p token (可为空表示easytier模式)",
 *   "roomCode": "房间号",
 *   "mode": "easytier | openp2p",
 *   "peerId": "节点唯一ID",
 *   "publicIp"/"publicPort"/"privateIp"/"privatePort": 打洞地址
 *   "supportsEasyTier"/"supportsOpenP2P": 支持的模式
 *   "timestamp": 探测用时间戳
 *   "mcPort"/"proxyPort": MC相关端口
 *   "errorMsg": 错误信息
 *   "extra": 其他自定义字段(Map<String,Object>)
 * }
 */
public class SignalingMessage {

    public static final String TYPE_REGISTER = "REGISTER";
    public static final String TYPE_UNREGISTER = "UNREGISTER";
    public static final String TYPE_QUERY = "QUERY";
    public static final String TYPE_QUERY_RESP = "QUERY_RESP";
    public static final String TYPE_CONNECT = "CONNECT";
    public static final String TYPE_PUNCH = "PUNCH";
    public static final String TYPE_PUNCH_ACK = "PUNCH_ACK";
    public static final String TYPE_RELAY = "RELAY";
    public static final String TYPE_PROBE = "PROBE";
    public static final String TYPE_PROBE_RESP = "PROBE_RESP";
    public static final String TYPE_ACK = "ACK";
    public static final String TYPE_ERROR = "ERROR";

    public String type;
    public String token;
    public String roomCode;
    public String mode;
    public String peerId;
    public String publicIp;
    public int publicPort;
    public String privateIp;
    public int privatePort;
    public boolean supportsEasyTier;
    public boolean supportsOpenP2P;
    public long timestamp;
    public int mcPort;
    public int proxyPort;
    public String errorMsg;
    public Map<String, Object> extra;

    public SignalingMessage() {
        this.timestamp = System.currentTimeMillis();
    }

    public SignalingMessage(String type) {
        this.type = type;
        this.timestamp = System.currentTimeMillis();
    }

    // ================== 序列化 ==================

    public String toJson() {
        Map<String, Object> obj = new LinkedHashMap<>();
        if (type != null) obj.put("type", type);
        if (token != null && !token.isEmpty()) obj.put("token", token);
        if (roomCode != null && !roomCode.isEmpty()) obj.put("roomCode", roomCode);
        if (mode != null && !mode.isEmpty()) obj.put("mode", mode);
        if (peerId != null && !peerId.isEmpty()) obj.put("peerId", peerId);
        if (publicIp != null && !publicIp.isEmpty()) obj.put("publicIp", publicIp);
        if (publicPort > 0) obj.put("publicPort", publicPort);
        if (privateIp != null && !privateIp.isEmpty()) obj.put("privateIp", privateIp);
        if (privatePort > 0) obj.put("privatePort", privatePort);
        obj.put("supportsEasyTier", supportsEasyTier);
        obj.put("supportsOpenP2P", supportsOpenP2P);
        if (timestamp > 0) obj.put("timestamp", timestamp);
        if (mcPort > 0) obj.put("mcPort", mcPort);
        if (proxyPort > 0) obj.put("proxyPort", proxyPort);
        if (errorMsg != null && !errorMsg.isEmpty()) obj.put("errorMsg", errorMsg);
        if (extra != null) obj.put("extra", extra);
        return SimpleJson.toJsonString(obj);
    }

    public static SignalingMessage fromJson(String json) {
        try {
            Map<String, Object> obj = SimpleJson.parseObject(json);
            if (obj.isEmpty()) return null;
            SignalingMessage msg = new SignalingMessage();
            msg.type = SimpleJson.getStr(obj, "type");
            msg.token = SimpleJson.getStr(obj, "token");
            msg.roomCode = SimpleJson.getStr(obj, "roomCode");
            msg.mode = SimpleJson.getStr(obj, "mode");
            msg.peerId = SimpleJson.getStr(obj, "peerId");
            msg.publicIp = SimpleJson.getStr(obj, "publicIp");
            msg.publicPort = SimpleJson.getInt(obj, "publicPort");
            msg.privateIp = SimpleJson.getStr(obj, "privateIp");
            msg.privatePort = SimpleJson.getInt(obj, "privatePort");
            msg.supportsEasyTier = SimpleJson.getBool(obj, "supportsEasyTier");
            msg.supportsOpenP2P = SimpleJson.getBool(obj, "supportsOpenP2P");
            msg.timestamp = SimpleJson.getLong(obj, "timestamp");
            msg.mcPort = SimpleJson.getInt(obj, "mcPort");
            msg.proxyPort = SimpleJson.getInt(obj, "proxyPort");
            msg.errorMsg = SimpleJson.getStr(obj, "errorMsg");
            Map<String, Object> ex = SimpleJson.getObj(obj, "extra");
            if (ex != null) msg.extra = ex;
            return msg;
        } catch (Exception e) {
            return null;
        }
    }

    // ================== UDP快捷发送 ==================

    public void sendUdp(DatagramSocket socket, InetAddress addr, int port) throws IOException {
        byte[] data = toJson().getBytes(StandardCharsets.UTF_8);
        DatagramPacket packet = new DatagramPacket(data, data.length, addr, port);
        socket.send(packet);
    }

    /**
     * @return [message, senderAddress, senderPort] 或 null超时
     */
    public static Object[] receiveUdp(DatagramSocket socket, int timeoutMs) throws IOException {
        socket.setSoTimeout(timeoutMs);
        byte[] buf = new byte[8192];
        DatagramPacket packet = new DatagramPacket(buf, buf.length);
        try {
            socket.receive(packet);
        } catch (SocketTimeoutException e) {
            return null;
        }
        String json = new String(packet.getData(), 0, packet.getLength(), StandardCharsets.UTF_8);
        SignalingMessage msg = fromJson(json);
        if (msg == null) return null;
        return new Object[]{msg, packet.getAddress(), packet.getPort()};
    }

    // ================== TCP快捷发送 ==================

    public void sendTcp(Socket socket) throws IOException {
        OutputStream out = socket.getOutputStream();
        String line = toJson() + "\n";
        out.write(line.getBytes(StandardCharsets.UTF_8));
        out.flush();
    }

    public static SignalingMessage receiveTcp(Socket socket, int timeoutMs) throws IOException {
        socket.setSoTimeout(timeoutMs);
        BufferedReader reader = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
        String line = reader.readLine();
        if (line == null) return null;
        return fromJson(line);
    }
}
