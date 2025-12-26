package org.example.p2pfileshare.controller;

import javafx.application.Platform;
import javafx.event.ActionEvent;
import javafx.fxml.FXML;
import javafx.scene.control.*;
import javafx.stage.Stage;
import org.example.p2pfileshare.model.PeerInfo;
import org.example.p2pfileshare.model.SharedFileLocal;
import org.example.p2pfileshare.network.control.ControlClient;
import org.example.p2pfileshare.network.control.ControlServer;
import org.example.p2pfileshare.service.FileShareService;
import org.example.p2pfileshare.service.HistoryService;
import org.example.p2pfileshare.service.PeerService;
import org.example.p2pfileshare.service.SearchService;
import org.example.p2pfileshare.util.AppConfig;

import java.util.List;
import java.util.Random;
import java.util.UUID;

public class RootController {

    // ====================== UI ======================
    @FXML private TabPane mainTabPane;
    @FXML private Label globalStatusLabel;
    @FXML private Label userNameLabel; // hiển thị tên người dùng trên status bar

    // Include các tab con
    @FXML private PeerTabController peerTabController;
    @FXML private ShareTabController shareTabController;
    @FXML private SearchTabController searchTabController;
    @FXML private HistoryTabController historyTabController;
    @FXML private IncomingConnectionController incomingConnectionTabController;

    // ====================== Services ======================
    private PeerService peerService;
    private FileShareService fileShareService;
    private SearchService searchService;
    private HistoryService historyService;
    private volatile boolean shuttingDown = false;

    // ====================== Control ======================
    private ControlServer controlServer;
    private ControlClient controlClient;

    // ====================== Peer info ======================
    private String myPeerId;
    private String myName; // displayName
    private final int FILE_PORT      = 6000  + new Random().nextInt(1000);
    private final int CONTROL_PORT   = 7000  + new Random().nextInt(1000);
    private static final String KEY_PEER_NAME = "peer_display_name";

    // ====================== INITIALIZE ======================
    @FXML
    public void initialize() {
        // 1) Lấy tên peer
        myName = loadOrAskPeerName();
        myPeerId = UUID.randomUUID().toString();
        historyService = new HistoryService();

        // 2) Khởi tạo Service
        peerService = new PeerService(myPeerId, myName, FILE_PORT, CONTROL_PORT);
        peerService.start();
        fileShareService = new FileShareService(FILE_PORT, historyService);
        fileShareService.setMyDisplayName(myName);
        searchService = new SearchService();

        // 3) ControlClient
        controlClient = new ControlClient(myPeerId, myName);

        // 4) ControlServer
        controlServer = new ControlServer(CONTROL_PORT, fromPeer -> {
            java.util.concurrent.atomic.AtomicBoolean accepted = new java.util.concurrent.atomic.AtomicBoolean(false);
            java.util.concurrent.CountDownLatch latch = new java.util.concurrent.CountDownLatch(1);

            Platform.runLater(() -> {
                try {
                    javafx.fxml.FXMLLoader loader = new javafx.fxml.FXMLLoader(
                            getClass().getResource("/org/example/p2pfileshare/ConfirmationDialog.fxml"));
                    javafx.scene.Parent page = loader.load();

                    Stage dialogStage = new Stage();
                    dialogStage.initStyle(javafx.stage.StageStyle.UNDECORATED);
                    dialogStage.initModality(javafx.stage.Modality.APPLICATION_MODAL);
                    if (mainTabPane.getScene() != null) dialogStage.initOwner(mainTabPane.getScene().getWindow());

                    javafx.scene.Scene scene = new javafx.scene.Scene(page);
                    dialogStage.setScene(scene);

                    ConfirmationController controller = loader.getController();
                    controller.setDialogStage(dialogStage);
                    controller.setContent(
                            "🔗 Yêu cầu kết nối",
                            "Peer \"" + fromPeer + "\" muốn kết nối!",
                            "Bạn có muốn cho phép thiết bị này truy cập kho file chia sẻ của bạn không?",
                            "Chấp nhận"
                    );

                    dialogStage.showAndWait();
                    accepted.set(controller.isConfirmed());

                } catch (Exception e) {
                    e.printStackTrace();
                    Alert alert = new Alert(Alert.AlertType.CONFIRMATION, "Peer " + fromPeer + " connect?");
                    accepted.set(alert.showAndWait().orElse(ButtonType.CANCEL) == ButtonType.OK);
                } finally {
                    latch.countDown();
                }
            });

            try { latch.await(); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            return accepted.get();
        });

        controlServer.setFileShareService(fileShareService);

        // SEARCH_REQ
        controlServer.setOnSearchRequestReceived((senderId, keyword) -> {
            PeerInfo senderInfo = peerService.getPeerFromId(senderId);
            if (senderInfo != null) {
                List<SharedFileLocal> foundFiles = fileShareService.searchLocalFiles(keyword);
                for (SharedFileLocal f : foundFiles) {
                    String data = f.getFileName() + ":" + f.getSize() + ":" +
                            (f.getSubject() == null ? "" : f.getSubject());
                    controlClient.sendSearchResponse(senderInfo, data);
                }
            }
        });

        // SEARCH_RES
        controlServer.setOnSearchResultReceived((senderId, data) -> {
            PeerInfo senderInfo = peerService.getPeerFromId(senderId);
            if (senderInfo != null && searchTabController != null) {
                searchTabController.onReceiveSearchResult(senderInfo, data);
            }
        });

        controlServer.setOnDisconnectNotify(msg -> {
            Platform.runLater(() -> {
                String content = (msg.note != null && !msg.note.isBlank())
                        ? msg.note
                        : ("Bởi peer: " + (msg.fromPeer != null ? msg.fromPeer : "Unknown"));

                showInfoDialog("Thông báo", "Bạn đã bị ngắt kết nối", content, false);
                if (globalStatusLabel != null) {
                    globalStatusLabel.setText("Bạn đã bị ngắt kết nối: " + (msg.fromPeer != null ? msg.fromPeer : "Unknown"));
                }
                if (peerTabController != null) peerTabController.onRemotePeerDisconnected(msg.fromPeer);
            });
        });

        controlServer.start();

        // Inject service vào UI controllers
        if (peerTabController != null)
            peerTabController.init(peerService, fileShareService, controlClient, controlServer, globalStatusLabel);

        if (shareTabController != null)
            shareTabController.init(fileShareService, globalStatusLabel, controlClient, peerTabController);

        if (searchTabController != null)
            searchTabController.init(searchService, fileShareService, controlClient, peerService, globalStatusLabel);

        if (historyTabController != null)
            historyTabController.init(historyService, globalStatusLabel);

        if (incomingConnectionTabController != null)
            incomingConnectionTabController.init(peerService, controlServer, globalStatusLabel, myPeerId);

        globalStatusLabel.setText("Sẵn sàng");
        if (userNameLabel != null && myName != null)
            userNameLabel.setText(myName);

        fileShareService.startServer();

        Platform.runLater(() -> {
            Stage stage = (Stage) mainTabPane.getScene().getWindow();
            stage.setOnCloseRequest(event -> {
                event.consume();
                onExit();
                Platform.exit();
            });
        });
    }

    // ====================== TAB SWITCH ======================
    @FXML private void selectPeerTab(ActionEvent event) { mainTabPane.getSelectionModel().select(0); }
    @FXML private void selectShareTab(ActionEvent event) { mainTabPane.getSelectionModel().select(1); }
    @FXML private void selectSearchTab(ActionEvent event) { mainTabPane.getSelectionModel().select(2); }
    @FXML private void selectHistoryTab(ActionEvent event) { mainTabPane.getSelectionModel().select(3); }
    @FXML private void selectIncomingTab(ActionEvent event) { mainTabPane.getSelectionModel().select(4); }

    // ====================== MENU ======================
    @FXML
    private void onChooseShareFolder() { mainTabPane.getSelectionModel().select(1); }

    @FXML
    private void onExit() { shutdownGracefully(); }

    @FXML
    private void onAbout() {
        showInfoDialog(
                "Giới thiệu | About",
                "Ứng dụng truyền file P2P - JavaFX\nP2P File Sharing Application",
                "Ứng dụng desktop chia sẻ file P2P trong LAN\nVersion 1.0",
                true
        );
    }

    @FXML
    private void onChangeName() {
        var opt = ChangeNameController.showDialog(mainTabPane.getScene().getWindow(),
                myName,
                controlClient,
                controlServer,
                peerService,
                newName -> {
                    this.myName = newName;
                    if (userNameLabel != null) userNameLabel.setText(myName);
                    if (peerService != null) peerService.setMyDisplayName(myName);
                    if (fileShareService != null) fileShareService.setMyDisplayName(myName);
                    if (controlClient != null) controlClient.setMyDisplayName(myName);
                });
        if (opt.isPresent()) {
            String newName = opt.get().trim();
            if (!newName.isEmpty() && !newName.equals(myName)) {
                AppConfig.save(KEY_PEER_NAME, newName);
                myName = newName;
                if (userNameLabel != null) userNameLabel.setText(myName);
                if (peerTabController != null) peerTabController.refresh();
                globalStatusLabel.setText("Đã đổi tên thành: " + myName);
            }
        }
    }

    // ====================== HELPERS ======================
    private String loadOrAskPeerName() {
        String saved = AppConfig.load(KEY_PEER_NAME);
        if (saved != null && !saved.isBlank()) return saved;

        TextInputDialog dialog = new TextInputDialog("Peer1");
        dialog.setTitle("Tên Peer");
        dialog.setHeaderText("Nhập tên Peer:");
        dialog.setContentText("Tên:");
        String name = dialog.showAndWait().orElse("Peer_" + System.currentTimeMillis());
        AppConfig.save(KEY_PEER_NAME, name);
        return name;
    }

    private void shutdownGracefully() {
        if (shuttingDown) return;
        shuttingDown = true;

        new Thread(() -> {
            try {
                if (globalStatusLabel != null) globalStatusLabel.setText("Đang ngắt kết nối...");

                var peers = peerService != null ? peerService.getPeersByIds(controlServer.getConnectedPeers()) : List.<PeerInfo>of();
                var peersListConnected = peerService != null ? peerService.getPeersByIds(controlClient.getPeerIdList()) : List.<PeerInfo>of();

                for (PeerInfo p : peers) { try { controlServer.disconnectPeer(p, myPeerId); } catch (Exception ignored) {} }
                for (PeerInfo p : peersListConnected) { try { controlClient.sendDisconnectRequest(p); } catch (Exception ignored) {} }

                try { if (controlServer != null) controlServer.stop(); } catch (Exception ignored) {}
                try { if (fileShareService != null) fileShareService.stopServer(); } catch (Exception ignored) {}
                try { if (peerService != null) peerService.stop(); } catch (Exception ignored) {}

                Platform.runLater(() -> {
                    if (mainTabPane.getScene() != null) mainTabPane.getScene().getWindow().hide();
                    Platform.exit();
                    System.exit(0);
                });
            } catch (Exception e) { e.printStackTrace(); }
        }, "shutdown-thread").start();
    }

    private void showInfoDialog(String title, String header, String content, boolean isSuccess) {
        try {
            javafx.fxml.FXMLLoader loader = new javafx.fxml.FXMLLoader(
                    getClass().getResource("/org/example/p2pfileshare/ConfirmationDialog.fxml"));
            javafx.scene.Parent page = loader.load();

            Stage dialogStage = new Stage();
            dialogStage.initStyle(javafx.stage.StageStyle.UNDECORATED);
            dialogStage.initModality(javafx.stage.Modality.APPLICATION_MODAL);
            if (mainTabPane.getScene() != null) dialogStage.initOwner(mainTabPane.getScene().getWindow());

            javafx.scene.Scene scene = new javafx.scene.Scene(page);
            dialogStage.setScene(scene);

            ConfirmationController controller = loader.getController();
            controller.setDialogStage(dialogStage);
            controller.setContent(title, header, content, "Đóng");
            if (isSuccess) controller.setStyleSuccess(); else controller.setStyleDanger();

            dialogStage.showAndWait();

        } catch (Exception e) {
            e.printStackTrace();
            new Alert(Alert.AlertType.INFORMATION, content).showAndWait();
        }
    }
}
