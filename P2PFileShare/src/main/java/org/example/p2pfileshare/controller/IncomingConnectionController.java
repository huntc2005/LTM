package org.example.p2pfileshare.controller;

import javafx.application.Platform;
import javafx.beans.property.SimpleStringProperty;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.fxml.FXML;
import javafx.fxml.FXMLLoader;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.control.*;
import javafx.stage.Modality;
import javafx.stage.Stage;
import javafx.stage.StageStyle;
import org.example.p2pfileshare.model.PeerInfo;
import org.example.p2pfileshare.network.control.ControlServer;
import org.example.p2pfileshare.service.PeerService;

import java.io.IOException;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;

public class IncomingConnectionController {

    @FXML private TableView<PeerInfo> connectedPeerTable;
    @FXML private TableColumn<PeerInfo, String> colPeerId;
    @FXML private TableColumn<PeerInfo, String> colDisplayName;
    @FXML private TableColumn<PeerInfo, String> colIp;
    @FXML private TableColumn<PeerInfo, String> colConnectTime;
    @FXML private Label statusLabel;
    @FXML private Button refreshButton;
    @FXML private Button disconnectButton;

    private PeerService peerService;
    private ControlServer controlServer;
    private Label globalStatusLabel;
    private String myUID;
    private final ObservableList<PeerInfo> incomingPeerList = FXCollections.observableArrayList();

    public void init(PeerService peerService, ControlServer controlServer, Label globalStatusLabel, String myUID) {
        this.myUID = myUID;
        this.peerService = peerService;
        this.controlServer = controlServer;
        this.globalStatusLabel = globalStatusLabel;

        setupTable();
        loadIncomingConnections();

        // Đăng ký listener để tự động reload khi có peer mới được chấp nhận
        controlServer.setOnPeerAccepted(() -> {
            System.out.println("[IncomingConnection] Peer accepted → reload table");
            Platform.runLater(this::loadIncomingConnections);
        });
    }

    private void setupTable() {
        colPeerId.setCellValueFactory(data ->
                new SimpleStringProperty(data.getValue().getPeerId()));

        colDisplayName.setCellValueFactory(data ->
                new SimpleStringProperty(data.getValue().getName()));

        colIp.setCellValueFactory(data ->
                new SimpleStringProperty(data.getValue().getIp()));

        // Hiện thời gian tải
        colConnectTime.setCellValueFactory(data ->
                new SimpleStringProperty(LocalDateTime.now().format(DateTimeFormatter.ofPattern("HH:mm:ss dd/MM/yyyy"))));

        connectedPeerTable.setItems(incomingPeerList);
    }

    private void loadIncomingConnections() {
        // Lấy tất cả peers đã được phát hiện
        List<PeerInfo> allPeers = peerService.getListPeer();
        // Debug: in ra thông tin để kiểm tra
//        System.out.println("[IncomingConnection] allPeers size = " + (allPeers == null ? 0 : allPeers.size()));
//        if (allPeers != null) {
//            allPeers.forEach(p -> System.out.println("[IncomingConnection] discovered peer: " + p.getPeerId() + " / " + p.getName() + " / " + p.getIp()));
//        }

        // Lọc ra những peer đã được chấp nhận kết nối
        List<PeerInfo> acceptedPeers = controlServer.getAcceptedPeers(allPeers);

        // Debug: in accepted
//        System.out.println("[IncomingConnection] acceptedPeers size = " + (acceptedPeers == null ? 0 : acceptedPeers.size()));
//        if (acceptedPeers != null) {
//            acceptedPeers.forEach(p -> System.out.println("[IncomingConnection] accepted peer: " + p.getPeerId() + " / " + p.getName() + " / " + p.getIp()));
//        }

        // Cập nhật UI trên JavaFX thread để chắc chắn TableView được refresh
        Platform.runLater(() -> {
            incomingPeerList.setAll(acceptedPeers);
            statusLabel.setText("Có " + (acceptedPeers == null ? 0 : acceptedPeers.size()) + " peer đang kết nối đến");

            if (globalStatusLabel != null) {
                globalStatusLabel.setText("Đã tải danh sách peer kết nối đến");
            }
        });
    }

    @FXML
    private void onRefresh() {
        statusLabel.setText("Đang làm mới...");
        loadIncomingConnections();
    }

    @FXML
    private void onDisconnect() {
        PeerInfo selected = connectedPeerTable.getSelectionModel().getSelectedItem();

        // Kiểm tra nếu chưa chọn
        if (selected == null) {
            showConfirmDialog("Cảnh báo", "Chưa chọn Peer", "Vui lòng chọn một dòng để ngắt kết nối.");
            return;
        }

        // Hiện Dialog Xác nhận
        boolean confirmed = showConfirmDialog(
                "🔌 Ngắt kết nối",
                "Ngắt kết nối với " + selected.getName() + "?",
                "Peer này sẽ không thể tải file của bạn nữa cho đến khi kết nối lại."
        );
        if (confirmed) {
            // Gọi ControlServer để chặn kết nối
            boolean success = controlServer.disconnectPeer(selected, this.myUID);

            if (success) {
                // Xóa khỏi bảng
                incomingPeerList.remove(selected);
                statusLabel.setText("Đã ngắt kết nối với " + selected.getName());

                // 3. Hiện Dialog Thành công
                showSuccessDialog("Thành công", "Đã chặn kết nối từ " + selected.getName());
            } else {
                // Báo lỗi
                showConfirmDialog("Lỗi", "Thất bại", "Đã xảy ra lỗi khi ngắt kết nối với " + selected.getName());
            }
        }
    }
    private boolean showConfirmDialog(String title, String header, String content) {
        try {
            FXMLLoader loader = new FXMLLoader(getClass().getResource("/org/example/p2pfileshare/ConfirmationDialog.fxml"));
            Parent page = loader.load();

            Stage dialogStage = new Stage();
            dialogStage.initStyle(StageStyle.UNDECORATED);
            dialogStage.initModality(Modality.APPLICATION_MODAL);

            if (connectedPeerTable.getScene() != null) {
                dialogStage.initOwner(connectedPeerTable.getScene().getWindow());
            }
            dialogStage.setScene(new Scene(page));

            ConfirmationController controller = loader.getController();
            controller.setDialogStage(dialogStage);

            // Thiết lập nội dung
            controller.setContent(title, header, content, "Đồng ý");
            controller.setStyleDanger(); // Màu đỏ cảnh báo

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

            if (connectedPeerTable.getScene() != null) {
                dialogStage.initOwner(connectedPeerTable.getScene().getWindow());
            }
            dialogStage.setScene(new Scene(page));

            ConfirmationController controller = loader.getController();
            controller.setDialogStage(dialogStage);

            controller.setContent("Thông báo", header, content, "Đóng");
            controller.setStyleSuccess(); // Màu xanh thành công

            dialogStage.showAndWait();

        } catch (IOException e) {
            e.printStackTrace();
        }
    }
}