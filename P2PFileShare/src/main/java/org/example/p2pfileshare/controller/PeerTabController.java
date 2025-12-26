package org.example.p2pfileshare.controller;

import javafx.application.Platform;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.concurrent.Task;
import javafx.fxml.FXML;
import javafx.fxml.FXMLLoader;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.control.*;
import javafx.scene.control.cell.PropertyValueFactory;
import javafx.scene.layout.AnchorPane;
import javafx.stage.Modality;
import javafx.stage.Stage;
import javafx.stage.StageStyle;
import org.example.p2pfileshare.model.PeerInfo;
import org.example.p2pfileshare.network.control.ControlClient;
import org.example.p2pfileshare.network.control.ControlServer;
import org.example.p2pfileshare.service.FileShareService;
import org.example.p2pfileshare.service.PeerService;

import java.io.IOException;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

public class PeerTabController {

    private PeerService peerService;
    private FileShareService fileShareService;
    private ControlClient controlClient;
    private Label globalStatusLabel;
    private ControlServer controlServer;

    // map lưu nhiều controller, key = peerId
    private final Map<String, ConnectedPeerController> connectedControllers = new HashMap<>();
   // map lưu tab đang mở để đổi tên hoặc xoá tab khi cần

    private final Map<String, Tab> connectedTabs = new HashMap<>();
    @FXML private TableView<PeerInfo> peerTable;
    @FXML private TableColumn<PeerInfo, String> colPeerName;
    @FXML private TableColumn<PeerInfo, String> colPeerIp;
    @FXML private TableColumn<PeerInfo, Number> colPeerPort;
    @FXML private TableColumn<PeerInfo, PeerInfo.ConnectionState> colPeerStatus;
    @FXML private Label peerStatusLabel;
    @FXML private TabPane mainTabPane; // nếu không có trong FXML, có thể set từ RootController

    @FXML private ProgressBar downloadProgress;
    @FXML private Label downloadStatusLabel;

    private final ObservableList<PeerInfo> peerList = FXCollections.observableArrayList();

    public void init(PeerService peerService,
                     FileShareService fileShareService,
                     ControlClient controlClient,
                     ControlServer controlServer,
                     Label globalStatusLabel) {
        // nhận các service bên ngoài truyền vào
        this.peerService = peerService;
        this.fileShareService = fileShareService;
        this.controlClient = controlClient;
        this.controlServer = controlServer;
        this.globalStatusLabel = globalStatusLabel;

        setupTable();
        onScanPeers();

        // Lắng nghe cập nhật tên
        if (this.controlServer != null) {
            this.controlServer.setpeerUpdateName(() -> {
                System.out.println("[IncomingConnection] Peer accepted → reload table");
                Platform.runLater(this::onScanPeers);
            });

            // Lắng nghe tin nhắn hệ thống từ Server
            this.controlServer.setOnSystemMessageReceived((senderId, msg) -> {
                // Chuyển luồng về JavaFX Thread để an toàn cập nhật UI
                Platform.runLater(() -> {
                    this.routeSystemMessage(senderId, msg);
                });
            });
        }
        controlServer.setOnRenameTab(this::renameConnectedTab);

    }

    private void setupTable() {
        colPeerName.setCellValueFactory(new PropertyValueFactory<>("name"));
        colPeerIp.setCellValueFactory(new PropertyValueFactory<>("ip"));
        colPeerPort.setCellValueFactory(new PropertyValueFactory<>("fileServerPort"));

        // Hiển thị trạng thái kết nối thực tế thay vì "Online"
        colPeerStatus.setCellValueFactory(new PropertyValueFactory<>("connectionState"));
        colPeerStatus.setCellFactory(column -> new TableCell<PeerInfo, PeerInfo.ConnectionState>() {
            @Override
            protected void updateItem(PeerInfo.ConnectionState state, boolean empty) {
                super.updateItem(state, empty);
                if (empty || state == null) {
                    setText(null);
                    setStyle("");
                } else {
                    switch (state) {
                        case NOT_CONNECTED:
                            setText("Chưa kết nối");
                            setStyle("-fx-text-fill: #EBE1D1;");
                            break;
                        case PENDING:
                            setText("Đang kết nối...");
                            setStyle("-fx-text-fill: #EBE1D1; -fx-font-weight: bold;");
                            break;
                        case CONNECTED:
                            setText("Đã kết nối");
                            setStyle("-fx-text-fill: #EBE1D1; -fx-font-weight: bold;");
                            break;
                        case REJECTED:
                            setText("Bị từ chối");
                            setStyle("-fx-text-fill: #EBE1D1;");
                            break;
                    }
                }
            }
        });

        peerTable.setItems(peerList);

    }

    // QUÉT PEER
    @FXML
    private void onScanPeers() {
        // snapshot trạng thái TRƯỚC KHI clear
        Map<String, PeerInfo.ConnectionState> prevStates = peerList.stream()
                .collect(Collectors.toMap(
                        PeerInfo::getPeerId,
                        PeerInfo::getConnectionState,
                        (a,b) -> a
                ));

        peerStatusLabel.setText("Đang quét...");
        peerTable.setDisable(true);

        Task<List<PeerInfo>> task = new Task<>() {
            @Override
            protected List<PeerInfo> call() {
                return peerService.scanPeers();
            }
        };

        task.setOnSucceeded(e -> {
            List<PeerInfo> scanned = task.getValue();
            // gán lại state
            for (PeerInfo p : scanned) {
                PeerInfo.ConnectionState prev = prevStates.get(p.getPeerId());
                if (prev == PeerInfo.ConnectionState.CONNECTED) {
                    p.setConnectionState(PeerInfo.ConnectionState.CONNECTED);
                } else {
                    p.setConnectionState(PeerInfo.ConnectionState.NOT_CONNECTED);
                }
            }

            peerList.setAll(scanned); // đổ data mới vào table
            peerStatusLabel.setText("Đã tìm thấy " + scanned.size() + " peer");
            if (globalStatusLabel != null) globalStatusLabel.setText("Quét LAN xong");
            peerTable.setDisable(false);
        });

        task.setOnFailed(e -> {
            peerStatusLabel.setText("Lỗi khi quét");
            peerTable.setDisable(false);
        });

        new Thread(task).start();
    }

    // sử dụng để refresh từ bên ngoài
    public void refresh() {
        onScanPeers();
    }

    // KẾT NỐI PEER
    @FXML
    private void onConnectPeer() {
        PeerInfo peer = peerTable.getSelectionModel().getSelectedItem();
        if (peer == null) {
            new Alert(Alert.AlertType.WARNING, "Vui lòng chọn peer để kết nối!").showAndWait();
            return;
        }

        peerStatusLabel.setText("Đang gửi CONNECT_REQUEST...");
        peer.setConnectionState(PeerInfo.ConnectionState.PENDING);
        peerTable.refresh();

        Task<Boolean> task = new Task<>() {
            @Override
            protected Boolean call() {
                return controlClient.sendConnectRequest(peer);
            }
        };

        task.setOnSucceeded(e -> {
            boolean ok = task.getValue();

            if (ok) {
                peer.setConnectionState(PeerInfo.ConnectionState.CONNECTED);
                peerStatusLabel.setText("Kết nối thành công!");
                openConnectedTab(peer);
            } else {
                peer.setConnectionState(PeerInfo.ConnectionState.REJECTED);
                peerStatusLabel.setText("Peer từ chối hoặc không phản hồi");
                showConfirmDialog("Kết nối thất bại", "Peer từ chối kết nối", "Vui lòng thử lại sau hoặc liên hệ người dùng đó.");
            }
            peerTable.refresh();
        });

        new Thread(task).start();
    }

    // mở tab riêng
    private void openConnectedTab(PeerInfo peer) {
        try {
            FXMLLoader loader = new FXMLLoader(getClass().getResource("/org/example/p2pfileshare/ConnectedPeerTab.fxml"));
            AnchorPane content = loader.load();

            // lấy controller của tab con để cài đặt
            ConnectedPeerController controller = loader.getController();
            controller.init(peer, controlClient, fileShareService);

            // đăng ký callback để khi ngắt kết nối thì cập nhật peerList và xoá mapping
            controller.setOnDisconnected(() -> {
                // chạy trên JavaFX thread
                Platform.runLater(() -> {
                    // Đặt trạng thái của peer trong danh sách về NOT_CONNECTED
                    for (PeerInfo p : peerList) {
                        if (peer.getPeerId().equals(p.getPeerId())) {
                            p.setConnectionState(PeerInfo.ConnectionState.NOT_CONNECTED);
                            break;
                        }
                    }
                    // Xoá controller mapping và cập nhật UI
                    connectedControllers.remove(peer.getPeerId());
                    if (peerTable != null) peerTable.refresh();
                    if (peerStatusLabel != null) peerStatusLabel.setText("Đã ngắt kết nối: " + peer.getName());
                });
            });

            Tab tab = new Tab("Kết nối: " + peer.getName());
            tab.setContent(content);
            tab.setClosable(true);

            // lưu tab vào map để có thể đổi tên sau này
            connectedTabs.put(peer.getPeerId(), tab);
            // đăng ký controller theo peerId để có thể cập nhật sau này
            connectedControllers.put(peer.getPeerId(), controller);

            // khi tab đóng thì xoá mapping
            tab.setOnClosed(ev -> {
                connectedControllers.remove(peer.getPeerId());
                connectedTabs.remove(peer.getPeerId());
            });
//            connectedTabs.remove(peer.getPeerId());
            // tìm TabPane từ một control trong scene
            TabPane tabPane = mainTabPane;
            if (tabPane == null) {
                Node n = peerTable;
                while (n != null && !(n instanceof TabPane)) {
                    n = n.getParent();
                }
                if (n instanceof TabPane) tabPane = (TabPane) n;
            }
            if (tabPane != null) {
                tabPane.getTabs().add(tab);
                tabPane.getSelectionModel().select(tab);
            } else {
                showConfirmDialog("Cảnh báo", null, "Không tìm thấy TabPane để mở tab mớ.");
//                new Alert(Alert.AlertType.WARNING, "Không tìm thấy TabPane để mở tab mới").showAndWait();
            }

        } catch (IOException ex) {
            showConfirmDialog("Lỗi", "Lỗi tải UI tab kết nối:", " " + ex.getMessage());
//            new Alert(Alert.AlertType.ERROR, "Lỗi tải UI tab kết nối: " + ex.getMessage()).showAndWait();
        }
    }


    // NGẮT KẾT NỐI
    @FXML
    private void onDisconnectPeer() {
        PeerInfo peer = peerTable.getSelectionModel().getSelectedItem();
        if (peer == null) {
            showConfirmDialog("Cảnh báo", "Hãy chọn peer trước!", "Vui lòng chọn peer để kết nối.");
            return;
        }

        // Nếu chưa kết nối thì chỉ cập nhật UI
        if (peer.getConnectionState() != PeerInfo.ConnectionState.CONNECTED) {
            peer.setConnectionState(PeerInfo.ConnectionState.NOT_CONNECTED);
            peerTable.refresh();
            peerStatusLabel.setText("Chưa kết nối đến peer này");
            return;
        }

        boolean confirmed = showConfirmDialog(
                "🔌 Ngắt kết nối",
                "Ngắt kết nối với " + peer.getName() + "?",
                "Hành động này sẽ đóng tab chia sẻ file và dừng mọi tải xuống."
        );

        if (!confirmed) return; // Nếu chọn Hủy thì thoát

        peerStatusLabel.setText("Đang ngắt kết nối...");
        peer.setConnectionState(PeerInfo.ConnectionState.PENDING);
        peerTable.refresh();

        Task<Boolean> task = new Task<>() {
            @Override
            protected Boolean call() {
                // Gọi ControlClient để gửi DISCONNECT_REQUEST tới peer
                return controlClient.sendDisconnectRequest(peer);
            }
        };

        task.setOnSucceeded(e -> {
            boolean ok = Boolean.TRUE.equals(task.getValue());
            if (ok) {
                // Thành công: cập nhật trạng thái trong peerList
                peer.setConnectionState(PeerInfo.ConnectionState.NOT_CONNECTED);
                peerStatusLabel.setText("Đã ngắt kết nối thành công");

                // Nếu có tab kết nối đang mở, báo để cập nhật UI tab và xoá mapping controller
                ConnectedPeerController ctrl = connectedControllers.remove(peer.getPeerId());
                if (ctrl != null) {
                    ctrl.onPeerDisconnected();
                    // Không tự động đóng tab, để người dùng xem thông báo
                }

                peerTable.refresh();
//                showSuccessDialog("Thành công", "Đã ngắt kết nối với " + peer.getName());
            } else {
                peer.setConnectionState(PeerInfo.ConnectionState.CONNECTED);
                peerStatusLabel.setText("Ngắt kết nối thất bại");
                showConfirmDialog("Lỗi", "Không thể ngắt kết nối", "Peer không phản hồi yêu cầu.");
                peerTable.refresh();
            }
        });

        task.setOnFailed(e -> {
            peer.setConnectionState(PeerInfo.ConnectionState.CONNECTED);
            peerStatusLabel.setText("Lỗi khi ngắt kết nối");
            peerTable.refresh();
        });

        new Thread(task, "disconnect-peer").start();
    }

    private boolean showConfirmDialog(String title, String header, String content) {
        try {
            FXMLLoader loader = new FXMLLoader(getClass().getResource("/org/example/p2pfileshare/ConfirmationDialog.fxml"));
            Parent page = loader.load();

            Stage dialogStage = new Stage();
            dialogStage.initStyle(StageStyle.UNDECORATED);
            dialogStage.initModality(Modality.APPLICATION_MODAL);

            if (peerTable.getScene() != null) {
                dialogStage.initOwner(peerTable.getScene().getWindow());
            }
            dialogStage.setScene(new Scene(page));

            ConfirmationController controller = loader.getController();
            controller.setDialogStage(dialogStage);

            // Nội dung & Style
            controller.setContent(title, header, content, "Đồng ý");
            controller.setStyleDanger(); // Màu đỏ

            dialogStage.showAndWait();
            return controller.isConfirmed();

        } catch (IOException e) {
            e.printStackTrace();
            return false;
        }
    }

    private void showSuccessDialog(String header, String content) {
        try {
            FXMLLoader loader = new FXMLLoader(getClass().getResource("/org/example/p2pfileshare/ConfirmationDialog.fxml"));
            Parent page = loader.load();

            Stage dialogStage = new Stage();
            dialogStage.initStyle(StageStyle.UNDECORATED);
            dialogStage.initModality(Modality.APPLICATION_MODAL);

            if (peerTable.getScene() != null) {
                dialogStage.initOwner(peerTable.getScene().getWindow());
            }
            dialogStage.setScene(new Scene(page));

            ConfirmationController controller = loader.getController();
            controller.setDialogStage(dialogStage);

            // Nội dung & Style
            controller.setContent("Thông báo", header, content, "Đóng");
            controller.setStyleSuccess(); // Màu xanh

            dialogStage.showAndWait();

        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    public void routeSystemMessage(String senderPeerId, String message) {
        // 1. Tìm controller tương ứng với người gửi
        ConnectedPeerController targetController = connectedControllers.get(senderPeerId);

        // 2. Nếu tìm thấy (tức là đang mở tab chat với người này)
        if (targetController != null) {
            // Gọi hàm xử lý tin nhắn mà chúng ta vừa sửa lúc nãy
            targetController.receivedMessage(message);
        } else {
            System.out.println("Nhận tin từ " + senderPeerId + " nhưng không tìm thấy tab nào đang mở.");
        }
    }

    public List<PeerInfo> getActiveConnectedPeers() {
        // Lấy danh sách các peer có trạng thái CONNECTED
        return peerList.stream()
                .filter(p -> p.getConnectionState() == PeerInfo.ConnectionState.CONNECTED)
                .collect(Collectors.toList());
    }

    // gọi khi remote peer bị ngắt (hoặc khi muốn đặt trạng thái peer về "chưa kết nối")
    public void onRemotePeerDisconnected(String peerId) {
        if (peerId == null || peerId.isBlank()) return;

        // Cập nhật trên JavaFX thread để tránh lỗi đa luồng
        Platform.runLater(() -> {
            boolean updated = false;

            // 1) cập nhật trạng thái trong peer list
            for (PeerInfo p : peerList) {
                if (peerId.equals(p.getPeerId())) {
                    p.setConnectionState(PeerInfo.ConnectionState.NOT_CONNECTED);
                    updated = true;
                    if (peerStatusLabel != null) {
                        peerStatusLabel.setText("Peer " + p.getName() + " đã bị ngắt kết nối");
                    }
                    break;
                }
            }

            // 2) tìm controller của tab tương ứng và gọi method để cập nhật UI tab nếu tab đang mở
            ConnectedPeerController ctrl = connectedControllers.get(peerId);
            if (ctrl != null) {
                ctrl.onPeerDisconnected();
            }

            // Nếu không tìm thấy controller thì peerList cũng đã được cập nhật
            if (!updated) {
                // reload toàn bộ peers từ service
                refresh();
            }

            if (peerTable != null) peerTable.refresh();
        });
    }

    // đổi tên tab và update trên UI con
    public void renameConnectedTab(String peerId, String newName) {
        Platform.runLater(() -> {
            Tab tab = connectedTabs.get(peerId);
            if (tab != null) {
                tab.setText("Kết nối: " + newName);
            }

            ConnectedPeerController ctrl = connectedControllers.get(peerId);
            if (ctrl != null) {
                ctrl.updatePeerDisplayName(newName);
            }
        });
    }
}
