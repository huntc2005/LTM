package org.example.p2pfileshare.network.control;

import javafx.application.Platform;
import javafx.scene.control.Alert;
import org.example.p2pfileshare.model.PeerInfo;

import java.io.*;
import java.net.Socket;
import java.util.ArrayList;
import java.util.List;

public class ControlClient {

    private final String myPeerId;
    private String myDisplayName;
    private List<String> peerIdList = new ArrayList<>();

    public ControlClient(String myPeerId, String myDisplayName) {
        this.myPeerId = myPeerId;
        this.myDisplayName = myDisplayName;
    }

    public void setMyDisplayName(String myDisplayName) {
        this.myDisplayName = myDisplayName;
    }

    public boolean sendConnectRequest(PeerInfo peer) {
        return sendConnectRequest(peer.getIp(), peer.getControlPort(), peer.getPeerId());
    }

    public boolean sendConnectRequest(String host, int controlPort, String toPeer) {
        System.out.println("[ControlClient] Connecting to " + host + ":" + controlPort +
                " to request connect → " + toPeer);

        try (Socket socket = new Socket(host, controlPort);
             BufferedReader reader = new BufferedReader(
                     new InputStreamReader(socket.getInputStream()));
             PrintWriter writer = new PrintWriter(
                     new OutputStreamWriter(socket.getOutputStream()), true)) {

            // 1) Gửi CONNECT_REQUEST (gửi peerId làm from)
            String msg = ControlProtocol.build(
                    ControlProtocol.CONNECT_REQUEST,
                    myPeerId,   // from (peerId)
                    toPeer,
                    myDisplayName// to (peerId)
            );

            writer.println(msg);

            // 2) Đọc 1 dòng response
            String respRaw = reader.readLine();
            if (respRaw == null || respRaw.isEmpty()) {
                return false;
            }

            ControlProtocol.ParsedMessage parsed = ControlProtocol.parse(respRaw);
            if (parsed == null) {
                return false;
            }

            if (!myPeerId.equals(parsed.toPeer)) {
                return false;
            }

            if (ControlProtocol.CONNECT_ACCEPT.equals(parsed.command)) {
                this.peerIdList.add(parsed.fromPeer);
                return true;
            } else if (ControlProtocol.CONNECT_REJECT.equals(parsed.command)) {
                return false;
            } else {
                return false;
            }

        } catch (IOException e) {
            e.printStackTrace();
            return false;
        }
    }

    public List<RemoteFile> listFiles(PeerInfo peer) {
        return listFiles(peer.getIp(), peer.getControlPort(), peer.getPeerId());
    }

    public List<RemoteFile> listFiles(String host, int controlPort, String toPeer) {
        System.out.println("[ControlClient] Request LIST_FILES → " + toPeer);
        List<RemoteFile> files = new ArrayList<>();

        try (Socket socket = new Socket(host, controlPort);
             BufferedReader reader = new BufferedReader(new InputStreamReader(socket.getInputStream()));
             PrintWriter writer = new PrintWriter(new OutputStreamWriter(socket.getOutputStream()), true)) {

            String msg = ControlProtocol.build(ControlProtocol.LIST_FILES, myPeerId, toPeer);
            writer.println(msg);

            String respRaw = reader.readLine();
            if (respRaw == null) return files;

            ControlProtocol.ParsedMessage parsed = ControlProtocol.parse(respRaw);
            if (parsed == null) return files;

            if (!myPeerId.equals(parsed.toPeer)) return files;

            if (ControlProtocol.LIST_FILES_RESPONSE.equals(parsed.command)) {
                String payload = parsed.note != null ? parsed.note : "";
                // Parse TSV lines: name\trelative\tsize
                String[] lines = payload.split("<NL>");
                System.out.println("Số dòng: " + lines.length);
                for (String line : lines) {
                    if (line.isBlank()) continue;
                    String[] cols = line.split("\t");
                    String name = cols.length > 0 ? cols[0] : "";
                    String rel  = cols.length > 1 ? cols[1] : name;
                    long size   = 0L;
                    try {
                        size = cols.length > 2 ? Long.parseLong(cols[2]) : 0L;
                    } catch (NumberFormatException ignored) {}
                    files.add(new RemoteFile(name, rel, size));
                }
            }
        } catch (IOException e) {
            e.printStackTrace();
        }

        return files;
    }

    public boolean sendDisconnectRequest(PeerInfo peer) {
        if (peer == null) return false;
        return sendDisconnectRequest(peer.getIp(), peer.getControlPort(), peer.getPeerId());
    }

    public boolean sendDisconnectRequest(String host, int controlPort, String toPeer) {
        System.out.println("[ControlClient] Sending DISCONNECT_REQUEST to " + host + ":" + controlPort + " -> " + toPeer);
        try (Socket socket = new Socket(host, controlPort);
             BufferedReader reader = new BufferedReader(new InputStreamReader(socket.getInputStream()));
             PrintWriter writer = new PrintWriter(new OutputStreamWriter(socket.getOutputStream()), true)) {

            String msg = ControlProtocol.build(
                    ControlProtocol.DISCONNECT_REQUEST,
                    myPeerId,   // from = mình
                    toPeer,     // to = peer đích
                    "Request disconnect"
            );
            writer.println(msg);

            // Đọc phản hồi 1 dòng (ControlServer hiện gửi DISCONNECT_NOTIFY)
            String respRaw = reader.readLine();
            if (respRaw == null || respRaw.isBlank()) {
                return false;
            }

            ControlProtocol.ParsedMessage parsed = ControlProtocol.parse(respRaw);
            if (parsed == null) return false;

            // Nếu là DISCONNECT_NOTIFY cho mình -> gọi handler hiển thị alert và trả về true
            if (ControlProtocol.DISCONNECT_NOTIFY.equals(parsed.command) && myPeerId.equals(parsed.toPeer)) {
//                handleDisconnectNotifyClient(parsed);
                return true;
            }
            return false;
        } catch (IOException e) {
            e.printStackTrace();
            return false;
        }
    }

    // gửi yêu cầu tìm kiếm file
    public void sendSearchRequest(PeerInfo peer, String keyword) {
        if (peer == null) return;
        // Gửi lệnh SEARCH_REQ|keyword
        // ControlServer bên kia phải xử lý cmd.startsWith("SEARCH_REQ")
        String command = "SEARCH_REQ|" + keyword;
        // sendSystemCommand sẽ tự nối thêm myPeerId vào (xem logic bên dưới)
        sendSystemCommand(peer, command);
    }

    // gửi phản hồi tìm kiếm file
    public void sendSearchResponse(PeerInfo peer, String fileData) {
        if (peer == null) return;
        String command = "SEARCH_RES|" + fileData;
        sendSystemCommand(peer, command);
    }

    // DTO đơn giản cho UI
    public static class RemoteFile {
        public final String name;
        public final String relativePath;
        public final long size;
        public RemoteFile(String name, String relativePath, long size) {
            this.name = name;
            this.relativePath = relativePath;
            this.size = size;
        }
    }

    // Hai hàm dưới này là "notify 1 chiều" – hiện tại chưa dùng,
    // nhưng để sẵn nếu sau này muốn gửi ACCEPT/REJECT trên connection khác.

    public void sendConnectAccept(String host, int controlPort, String toPeer) {
        sendOneWay(host, controlPort,
                ControlProtocol.build(ControlProtocol.CONNECT_ACCEPT, myPeerId, toPeer, "Accepted"));
    }

    public void sendConnectReject(String host, int controlPort, String toPeer, String reason) {
        sendOneWay(host, controlPort,
                ControlProtocol.build(ControlProtocol.CONNECT_REJECT, myPeerId, toPeer, reason));
    }

    private void sendOneWay(String host, int port, String msg) {
        try (Socket socket = new Socket(host, port);
             PrintWriter writer = new PrintWriter(
                     new OutputStreamWriter(socket.getOutputStream()), true)) {

            writer.println(msg);
            System.out.println("[ControlClient] One-way send: " + msg);

        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    public void sendSystemCommand(PeerInfo peer, String rawCommand) {
        if (peer == null) return;

        String[] parts = rawCommand.split("\\|", 2);
        if (parts.length < 2) return;

        String prefix = parts[0]; // CMD:REMOVE_FILE
        String content = parts[1]; // game.exe

        String finalMsg = prefix + "|" + myPeerId + "|" + content;

        System.out.println("[ControlClient] Sending System Cmd to " + peer.getName() + ": " + finalMsg);
        sendOneWay(peer.getIp(), peer.getControlPort(), finalMsg);
    }
//
//    private void handleDisconnectNotifyClient(ControlProtocol.ParsedMessage msg) {
//        System.out.println("[ControlClient] Disconnect notify received: " + msg.note);
////        String disconnectorName = msg.note != null ? msg.note : "Unknown";
////        Platform.runLater(() -> {
////            Alert alert = new Alert(Alert.AlertType.INFORMATION);
////            alert.setTitle("Ngắt kết nối");
////            alert.setHeaderText("ngắt kết nối thành công");
////            alert.setContentText(disconnectorName);
////            alert.showAndWait();
////        });
//    }
    public void broadcastUpdateName(List<PeerInfo> connectedPeers, String newName) {
        if (connectedPeers == null || connectedPeers.isEmpty()) {
            return;
        }

        if (newName == null) newName = "";
        newName = newName.trim();
        if (newName.isEmpty()) {
            System.out.println("[ControlClient] broadcastUpdateName: newName is empty");
            return;
        }

        // Nếu bạn có ControlProtocol.UPDATE_NAME thì nên dùng build() cho đồng bộ format
        // Ví dụ: ControlProtocol.build(ControlProtocol.UPDATE_NAME, myPeerId, "*", newName);
        String msg = "UPDATE_NAME|" + myPeerId + "|" + newName;

        System.out.println("[ControlClient] broadcastUpdateName -> peers=" + connectedPeers.size()
                + " msg=" + msg);

        int ok = 0, fail = 0;

        for (PeerInfo p : connectedPeers) {
            if (p == null) continue;

            // tránh gửi cho chính mình nếu list có chứa mình
            if (myPeerId.equals(p.getPeerId())) continue;

            try {
                sendOneWay(p.getIp(), p.getControlPort(), msg);
                ok++;
            } catch (Exception e) {
                fail++;
            }
        }
    }

    public List<String> getPeerIdList() {
        return peerIdList;
    }

}
