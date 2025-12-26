package org.example.p2pfileshare.network.discovery;

import org.example.p2pfileshare.model.PeerInfo;

import java.io.IOException;
import java.net.*;
import java.util.ArrayList;
import java.util.List;

public class PeerDiscovery {

    // Các port có thể dùng cho discovery (UDP)
//    private static final int[] DISCOVERY_PORTS = {
//            50000, 50001, 50002, 50003, 50004
//    };

    // cấu hình multicast
    private static final String MULTICAST_GROUP = "230.0.0.1";
    private static final int MULTICAST_PORT = 8888;

    private static final String DISCOVER_MSG    = "P2P_DISCOVER_REQUEST";
    private static final String RESPONSE_PREFIX = "P2P_DISCOVER_RESPONSE";

    private static volatile boolean responderRunning = false;
    private static volatile DatagramSocket responderSocket = null;
    private static InetAddress groupAddress = null;
    private static Thread responderThread = null;

    // chế độ lắng nghe, sử dụng thread
    public static synchronized void startResponder(
            String myPeerId,
            java.util.function.Supplier<String> displayNameSupplier,
            int filePort,
            int controlPort
    ) {
        if (responderRunning) {
            System.out.println("[Discovery] Responder already running");
            return;
        }

        responderRunning = true;

        responderThread = new Thread(() -> {
            try {
                // 1. Tạo Multicast Socket
                responderSocket = new MulticastSocket(MULTICAST_PORT);
                groupAddress = InetAddress.getByName(MULTICAST_GROUP);

                // 2. Tham gia nhóm
                InetSocketAddress group = new InetSocketAddress(groupAddress, MULTICAST_PORT);
                responderSocket.joinGroup(group, null);
                System.out.println("[Discovery] Responder joined Multicast Group: " + MULTICAST_GROUP + ":" + MULTICAST_PORT);

                byte[] buf = new byte[1024];

                while (responderRunning) {
                    try {
                        DatagramPacket packet = new DatagramPacket(buf, buf.length);
                        responderSocket.receive(packet);

                        String msg = new String(packet.getData(), 0, packet.getLength()).trim();
//                        if (!DISCOVER_MSG.equals(msg)) continue;

                        if (DISCOVER_MSG.equals(msg)) {
                            String response = RESPONSE_PREFIX + "|" +
                                    myPeerId + "|" +
                                    displayNameSupplier.get() + "|" +
                                    filePort + "|" +
                                    controlPort;

                            byte[] respData = response.getBytes();

                            DatagramPacket resp = new DatagramPacket(
                                    respData,
                                    respData.length,
                                    packet.getAddress(),
                                    packet.getPort()
                            );
                            responderSocket.send(resp);
                        }

                    } catch (SocketException se) {
                        // xảy ra khi socket.close() lúc stop
                        if (responderRunning) {
                            System.err.println("[Discovery] Socket closed: " + se.getMessage());
                        }
                        break;
                    }
                }

            } catch (IOException e) {
                if (responderRunning) {
                    e.printStackTrace();
                }
            } finally {
                stopResponder();
                System.out.println("[Discovery] Responder stopped");
            }
        }, "discovery-responder");

        responderThread.setDaemon(true);
        responderThread.start();
    }

    public static synchronized void stopResponder() {
        responderRunning = false;
        if (responderSocket != null && !responderSocket.isClosed()) {
            try {
                if (groupAddress != null) {
                    InetSocketAddress group = new InetSocketAddress(groupAddress, MULTICAST_PORT);
                    responderSocket.leaveGroup(group, null);
                }
                responderSocket.close();
            } catch (IOException e) {
                e.printStackTrace();
            }
        }
        responderSocket = null;
        System.out.println("[Discovery] Responder stopped");
    }

    // Quét tìm peer trong LAN
    public static List<PeerInfo> discoverPeers(String myPeerId, int timeoutMillis) {
        List<PeerInfo> result = new ArrayList<>();

        try (DatagramSocket socket = new DatagramSocket()) {
            socket.setSoTimeout(timeoutMillis); // timeout chỉ chờ trong 3s, quá thì thôi

            byte[] data = DISCOVER_MSG.getBytes();

            InetAddress group = InetAddress.getByName(MULTICAST_GROUP);
            DatagramPacket packet = new DatagramPacket(
                    data,
                    data.length,
                    group,
                    MULTICAST_PORT
            );
            socket.send(packet);
            System.out.println("[Discovery] Sent Multicast Request to " + MULTICAST_GROUP);


            byte[] buf = new byte[1024];
            long start = System.currentTimeMillis();

            while (System.currentTimeMillis() - start < timeoutMillis) {
                try {
                    DatagramPacket resp = new DatagramPacket(buf, buf.length);
                    socket.receive(resp);

                    String msg = new String(resp.getData(), 0, resp.getLength()).trim();

                    if (msg.startsWith(RESPONSE_PREFIX)) {
                        // Format mới: P2P_DISCOVER_RESPONSE|peerId|displayName|filePort|controlPort
                        String[] parts = msg.split("\\|");
                        if (parts.length >= 5) {
                            String peerId         = parts[1];
                            String displayName    = parts[2];
                            int peerFilePort      = Integer.parseInt(parts[3]);
                            int peerControlPort   = Integer.parseInt(parts[4]);
                            String ip             = resp.getAddress().getHostAddress();

                            // Bỏ qua chính mình dựa trên peerId
                            if (peerId.equals(myPeerId)) {
                                continue;
                            }

                            PeerInfo peer = new PeerInfo(
                                    peerId,
                                    displayName,
                                    ip,
                                    peerFilePort,
                                    peerControlPort,
                                    "Online"
                            );
                            result.add(peer);

                            System.out.println("[Discovery] Found: " + displayName +
                                    " ip: " + ip +
                                    " filePort:" + peerFilePort +
                                    " ctrlPort:" + peerControlPort +
                                    " id:" + peerId);

                        } else if (parts.length >= 4) {
                            // Khoản hỗ trợ format cũ: P2P_DISCOVER_RESPONSE|name|filePort|controlPort
                            String peerName      = parts[1];
                            int peerFilePort     = Integer.parseInt(parts[2]);
                            int peerControlPort  = Integer.parseInt(parts[3]);
                            String ip            = resp.getAddress().getHostAddress();

                            // Bỏ qua chính mình nếu myPeerId equals peerName fallback
                            if (peerName.equals(myPeerId)) {
                                continue;
                            }

                            PeerInfo peer = new PeerInfo(
                                    peerName,
                                    ip,
                                    peerFilePort,
                                    peerControlPort,
                                    "Online"
                            );
                            result.add(peer);

                            System.out.println("[Discovery] Found: " + peerName +
                                    " ip:" + ip +
                                    " filePort:" + peerFilePort +
                                    " ctrlPort:" + peerControlPort);
                        }
                    }
                } catch (SocketTimeoutException e) {
                    // hết thời gian đợi -> thoát vòng while
                    break;
                } catch (IOException ex) {
                    ex.printStackTrace();
                    break;
                }
            }

        } catch (IOException e) {
            e.printStackTrace();
        }

        return result;
    }
}
