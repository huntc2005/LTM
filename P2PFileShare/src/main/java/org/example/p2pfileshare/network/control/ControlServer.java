package org.example.p2pfileshare.network.control;

import org.example.p2pfileshare.model.PeerInfo;
import org.example.p2pfileshare.service.FileShareService;

import java.io.*;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BiConsumer;
import java.util.function.Function;
import java.util.function.Consumer;

public class ControlServer {

    private final int port;
    private volatile boolean running = false;

    // Đã chấp nhận kết nối từ peerId nào
    private final Set<String> acceptedPeers = ConcurrentHashMap.newKeySet();
    private final Function<String, Boolean> onIncomingConnect;

    // Để trả danh sách file local
    private FileShareService fileShareService;
    private Runnable onPeerAccepted;
    private Runnable onUpdatePeerName;
    private Consumer<ControlProtocol.ParsedMessage> onDisconnectNotify;
    // Callback nhận tin nhắn hệ thống
    private java.util.function.BiConsumer<String, String> onSystemMessageCallback;
    // lưu PeerInfo với peerID, với các peer đã connect
    private java.util.function.BiConsumer<String, String> onRenameTab;

    // CALLBACK TÌM KIẾM
    private BiConsumer<String, String> onSearchRequestReceived; // (SenderID, Keyword) -> Xử lý tìm và trả lời
    private BiConsumer<String, String> onSearchResultReceived;  // (SenderID, Data)    -> Cập nhật UI

    public ControlServer(int port, Function<String, Boolean> onIncomingConnect) {
        this.port = port;
        this.onIncomingConnect = onIncomingConnect;

    }

    // Cho phép inject FileShareService để phục vụ LIST_FILES
    public void setFileShareService(FileShareService fileShareService) {
        this.fileShareService = fileShareService;
    }

    public void setOnSystemMessageReceived(java.util.function.BiConsumer<String, String> callback) {
        this.onSystemMessageCallback = callback;
    }

    // SETTER CHO SEARCH
    public void setOnSearchRequestReceived(BiConsumer<String, String> callback) {
        this.onSearchRequestReceived = callback;
    }
    public void setOnSearchResultReceived(BiConsumer<String, String> callback) {
        this.onSearchResultReceived = callback;
    }

    public void start() {
        if (running) return;
        running = true;

        Thread t = new Thread(() -> {
            try (ServerSocket serverSocket = new ServerSocket(port)) {
                System.out.println("[ControlServer] Listening on port " + port);
                while (running) {
                    Socket client = serverSocket.accept();
                    handleClient(client);
                }
            } catch (IOException e) {
                if (running) {
                    e.printStackTrace();
                } else {
                    System.out.println("[ControlServer] Stopped");
                }
            }
        }, "control-server");

        t.setDaemon(true);
        t.start();
    }

    public void stop() {
        running = false;
        // có thể mở 1 socket ảo vào chính mình để giải phóng accept()
    }

    private void handleClient(Socket socket) {
        Thread t = new Thread(() -> {
            try (Socket s = socket;
                 BufferedReader reader = new BufferedReader(new InputStreamReader(s.getInputStream()));
                 PrintWriter writer = new PrintWriter(new OutputStreamWriter(s.getOutputStream()), true)) {

                String raw = reader.readLine();
                if (raw == null || raw.isEmpty()) return;

                if (raw.startsWith("SEARCH_REQ|") || raw.startsWith("SEARCH_RES|")) {
                    System.out.println("[ControlServer] Received Search CMD: " + raw);
                    handleSearchCommand(raw);
                    return;
                }

                if (raw.startsWith("CMD:")) {
                    System.out.println("[ControlServer] Received System CMD: " + raw);

                    String[] parts = raw.split("\\|");
                    // parts[0] = CMD:REMOVE_FILE
                    // parts[1] = SenderID (quan trọng để định tuyến)
                    // parts[2] = FileName

                    if (parts.length >= 3) {
                        String senderId = parts[1];

                        // Tái tạo lại tin nhắn để gửi cho Controller xử lý
                        // Controller mong đợi: "CMD:REMOVE_FILE|FileName"
                        String originalCmd = parts[0] + "|" + parts[2];

                        if (onSystemMessageCallback != null) {
                            // Gọi về PeerTabController
                            onSystemMessageCallback.accept(senderId, originalCmd);
                        }
                    }
                    return; // Xử lý xong thì thoát, không parse tiếp
                }

                ControlProtocol.ParsedMessage msg = ControlProtocol.parse(raw);
                if (msg == null) return;

                System.out.println("[ControlServer] Received: " + raw);

                if (ControlProtocol.CONNECT_REQUEST.equals(msg.command)) {
                    String fromPeer = msg.fromPeer; // người yêu cầu
                    String toPeer   = msg.toPeer;   // mình
                    String namespace = msg.note;    // optional namespace
                    boolean accept = true;
                    if (onIncomingConnect != null) {
                        try {
                            accept = Boolean.TRUE.equals(onIncomingConnect.apply(namespace));
                            if (accept) {
                                acceptedPeers.add(fromPeer);

                                // load lại danh sách incomming connection để cập nhật danh sách
                                if (onPeerAccepted != null) {
                                    onPeerAccepted.run();
                                }
                            }
                        } catch (Exception ex) {
                            ex.printStackTrace();
                            accept = false;
                        }
                    }

                    String respCmd  = accept ? ControlProtocol.CONNECT_ACCEPT : ControlProtocol.CONNECT_REJECT;
                    String respNote = accept ? "Accepted" : "Rejected";

                    // Lưu ý: từ phía server, fromPeer = "mình", toPeer = "người gửi request"
                    String resp = ControlProtocol.build(
                            respCmd,
                            toPeer,     // from = mình
                            fromPeer,   // to   = người yêu cầu
                            respNote
                    );

                    writer.println(resp);
                    System.out.println("[ControlServer] Sent: " + resp);
                }
                else if (ControlProtocol.LIST_FILES.equals(msg.command)) {
                    // Trả về danh sách file chia sẻ (tên/relative path)
                    String toPeer   = msg.toPeer;   // mình
                    String fromPeer = msg.fromPeer; // client
                    // CHẶN NẾU CHƯA ĐƯỢC ACCEPT
                    if (!acceptedPeers.contains(fromPeer)) {
                        String denyResp = ControlProtocol.build(
                                ControlProtocol.LIST_FILES_RESPONSE,
                                msg.toPeer,
                                fromPeer,
                                ""   // payload rỗng
                        );
                        writer.println(denyResp);
                        return;
                    }
                    // Build payload TSV: fileName\trelativePath\tsize
                    String payload = buildFileListPayload();
                    String resp = ControlProtocol.build(
                            ControlProtocol.LIST_FILES_RESPONSE,
                            toPeer,     // from = mình
                            fromPeer,   // to   = requester
                            payload
                    );

                    writer.println(resp);
                    System.out.println("[ControlServer] Sent LIST_FILES_RESPONSE (" + payload.length() + " bytes)");
                }
                else if (ControlProtocol.DISCONNECT_REQUEST.equals(msg.command)) {
                    String fromPeer = msg.fromPeer; // người yêu cầu ngắt (mình) hoặc client
                    String toPeer   = msg.toPeer;   // người bị ngắt
                    acceptedPeers.remove(fromPeer); //remove(toPeer)
                    // gọi cập nhật lại trạng thái của peertab
                    if (onUpdatePeerName != null) {
                        onUpdatePeerName.run();
                    }
                    // gọi cập nhật lại trạng thái của incoming connection, peer client gọi đến peer server
                    if (onPeerAccepted != null) {
                        onPeerAccepted.run();
                    }
//                // Gửi phản hồi DISCONNECT_NOTIFY cho client bt là ok đã ngắt
                    writer.println(ControlProtocol.build(ControlProtocol.DISCONNECT_NOTIFY,
                            msg.toPeer, msg.fromPeer, "Disconnected"));
                    System.out.println("[ControlServer] Disconnected: " + fromPeer);
                }
                // xử lý thông báo DISCONNECT_NOTIFY nhận từ peer sever
                else if (ControlProtocol.DISCONNECT_NOTIFY.equals(msg.command)) {
                    System.out.println("[ControlServer] Received DISCONNECT_NOTIFY from " + msg.fromPeer
                            + " note=" + msg.note);
                    // Gọi callback nếu đã đăng ký để UI xử lý hiển alert thông báo ở root
                    if (onDisconnectNotify != null) {
                        try {
                            onDisconnectNotify.accept(msg);
                        } catch (Exception ex) {
                            ex.printStackTrace();
                        }
                    }
                    // không cần gửi phản hồi
                }
                //server gửi gói tin và server ở client nhận hàm này để cập nhật tên peer
                else if (ControlProtocol.UPDATE_NAMESERVER.equals(msg.command)) {
                    // to peer ở đây t co cai là name mới
                    System.out.println("[ControlServer] Received UPDATE_NAME from " + msg.fromPeer +"va name mới"+ msg.toPeer);

                    if (onUpdatePeerName != null) {
                        onUpdatePeerName.run();
                    }
                    if (onRenameTab != null) {
                        onRenameTab.accept(msg.fromPeer, msg.toPeer);
                    }
                }

            } catch (IOException e) {
                e.printStackTrace();
            }
        }, "control-handler");

        t.setDaemon(true);
        t.start();
    }

    // XỬ LÝ LỆNH TÌM KIẾM
    private void handleSearchCommand(String raw) {
        // Format: SEARCH_REQ|SenderID|Keyword
        // Format: SEARCH_RES|SenderID|FileData
        String[] parts = raw.split("\\|", 3);
        if (parts.length < 3) return;

        String cmd = parts[0];
        String senderId = parts[1];
        String content = parts[2];

        if ("SEARCH_REQ".equals(cmd)) {
            // Có người hỏi -> Gọi callback để Root xử lý (tìm file & gửi trả)
            if (onSearchRequestReceived != null) {
                onSearchRequestReceived.accept(senderId, content);
            }
        } else if ("SEARCH_RES".equals(cmd)) {
            // Có người trả lời -> Gọi callback để cập nhật bảng kết quả
            if (onSearchResultReceived != null) {
                onSearchResultReceived.accept(senderId, content);
            }
        }
    }

    // build payload danh sách file được chia sẻ theo dạng 2A.docx	2A.docx	27084<NL>AI4life (2).docx	AI4life (2).docx	933162<NL>
    private String buildFileListPayload() {
        if (fileShareService == null) return "";
        // liệt kê danh sách file được chia sẻ
        List<org.example.p2pfileshare.model.SharedFileLocal> list = fileShareService.listSharedFiles();
        StringBuilder sb = new StringBuilder();

        for (var f : list) {
            // Encode tab-safe by replacing tabs/newlines
            String name = safe(f.getFileName());
            String rel  = safe(f.getRelativePath());
            long size   = f.getSize();
            if (sb.length() > 0) sb.append("<NL>");
            sb.append(name).append('\t').append(rel).append('\t').append(size);
        }
        return sb.toString();
    }

    private String safe(String s) {
        if (s == null) return "";
        return s.replace("\t", " ").replace("\n", " ");
    }
    public List<PeerInfo> getAcceptedPeers(List<PeerInfo> allPeers) {
        // Trả về danh sách peerId đã được chấp nhận kết nối
        return allPeers.stream()
                .filter(p -> acceptedPeers.contains(p.getPeerId()))
                .toList();
    }
    public void setOnPeerAccepted(Runnable callback) {
        this.onPeerAccepted = callback;
    }
    public void setpeerUpdateName(Runnable callback) {
        this.onUpdatePeerName = callback;
    }
    public void setOnRenameTab(java.util.function.BiConsumer<String, String> cb) {
        this.onRenameTab = cb;
    }
    // setter để UI đăng ký listener khi nhận DISCONNECT_NOTIFY
    public void setOnDisconnectNotify(Consumer<ControlProtocol.ParsedMessage> callback) {
        this.onDisconnectNotify = callback;
    }

    public boolean disconnectPeer(PeerInfo peer, String myUID) {
        if (peer == null) return false;

        // 1) Remove the peer from the accepted set to block further file access
        acceptedPeers.remove(peer.getPeerId());

        // 2) Notify the peer about the disconnection
        try (Socket s = new Socket(peer.getIp(), peer.getControlPort());
             PrintWriter w = new PrintWriter(new OutputStreamWriter(s.getOutputStream()), true)) {

            String msg = ControlProtocol.build(
                    ControlProtocol.DISCONNECT_NOTIFY,
                    myUID,                 // from (server)
                    peer.getPeerId(),         // to (disconnected peer)
                    "Bạn đã bị peer '" + fileShareService.getMyDisplayName() + "' ngắt kết nối"
            );

            w.println(msg);
            System.out.println("[ControlServer] Sent DISCONNECT_NOTIFY to " + peer.getIp());
            return true;
        } catch (IOException e) {
            // Even if the notification fails, the peer is still removed from the accepted list
            System.out.println("[ControlServer] Notify failed: " + e.getMessage());
            return false;
        }
    }

    private int tryParseInt(String s, int def) {
        try { return Integer.parseInt(s.trim()); }
        catch (Exception e) { return def; }
    }

    public List<String> getConnectedPeers() {
        return new ArrayList<>(acceptedPeers);
    }
}
