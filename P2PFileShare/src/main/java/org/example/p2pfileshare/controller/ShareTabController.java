package org.example.p2pfileshare.controller;

import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.fxml.FXML;
import javafx.fxml.FXMLLoader;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.control.*;
import javafx.scene.control.cell.PropertyValueFactory;
import javafx.stage.DirectoryChooser;
import javafx.stage.Modality;
import javafx.stage.Stage;
import javafx.stage.StageStyle;
import org.example.p2pfileshare.model.SharedFileLocal;
import org.example.p2pfileshare.network.control.ControlClient;
import org.example.p2pfileshare.service.FileShareService;
import org.example.p2pfileshare.util.AppConfig;
import org.example.p2pfileshare.model.PeerInfo;
import org.example.p2pfileshare.service.PeerService;

import java.io.File;
import java.util.List;
import java.io.IOException;

public class ShareTabController {

    private FileShareService fileShareService;
    private Label globalStatusLabel;

    private static final String KEY_SHARE_DIR = "shared_folder";

    @FXML private TextField shareFolderField;

    // TableView dùng SharedFileLocal
    @FXML private TableView<SharedFileLocal> sharedFileTable;
    @FXML private TableColumn<SharedFileLocal, String> colSharedName;
    @FXML private TableColumn<SharedFileLocal, String> colSharedType;
    @FXML private TableColumn<SharedFileLocal, Long>   colSharedSize;
    @FXML private TableColumn<SharedFileLocal, String> colSharedSubject;
    @FXML private TableColumn<SharedFileLocal, String> colSharedTags;
    @FXML private TableColumn<SharedFileLocal, Boolean> colSharedVisibility;

    private final ObservableList<SharedFileLocal> sharedFiles =
            FXCollections.observableArrayList();

    private PeerTabController peerTabController;
    private ControlClient controlClient;
    private PeerService peerService;

    public void init(FileShareService fileShareService, Label globalStatusLabel, ControlClient controlClient, PeerTabController peerTabController) {
        this.fileShareService = fileShareService;
        this.globalStatusLabel = globalStatusLabel;
        this.controlClient = controlClient;
        this.peerTabController = peerTabController;

        setupTable();
        loadLastSharedFolder();
    }

    // ánh xạ data từ file sharefilelocal lên bảng
    private void setupTable() {
        colSharedName.setCellValueFactory(new PropertyValueFactory<>("fileName"));
        colSharedType.setCellValueFactory(new PropertyValueFactory<>("extension"));
        colSharedSize.setCellValueFactory(new PropertyValueFactory<>("size"));

        // Format hiển thị kích thước file
        colSharedSize.setCellFactory(col -> new TableCell<SharedFileLocal, Long>() { // chặn data trước để format
            @Override
            protected void updateItem(Long item, boolean empty) {
                super.updateItem(item, empty);
                if (empty || item == null) {
                    setText(null);
                } else {
                    setText(formatSize(item));
                }
            }
        });

        colSharedSubject.setCellValueFactory(new PropertyValueFactory<>("subject"));
        colSharedTags.setCellValueFactory(new PropertyValueFactory<>("tags"));
        colSharedVisibility.setCellValueFactory(new PropertyValueFactory<>("visible"));
        sharedFileTable.setItems(sharedFiles);
    }

    // Load thư mục chia sẻ đã lưu trong AppConfig
    private void loadLastSharedFolder() {
        String last = AppConfig.load(KEY_SHARE_DIR);
        if (last != null) {
            File dir = new File(last);
            if (dir.isDirectory()) {
                applyShareFolder(dir);
            }
        }
    }


    private void applyShareFolder(File dir) {
        shareFolderField.setText(dir.getAbsolutePath());
        fileShareService.setShareFolder(dir);
        refreshSharedFiles();

        if (globalStatusLabel != null) {
            globalStatusLabel.setText("Thư mục chia sẻ: " + dir.getName());
        }
    }

    // Chọn thư mục chia sẻ
    @FXML
    private void onChooseFolder() {
        DirectoryChooser chooser = new DirectoryChooser();
        chooser.setTitle("Chọn thư mục chia sẻ");

        String last = AppConfig.load(KEY_SHARE_DIR);
        if (last != null) {
            File prev = new File(last);
            if (prev.isDirectory()) chooser.setInitialDirectory(prev);
        }

        Stage stage = (Stage) shareFolderField.getScene().getWindow();
        File dir = chooser.showDialog(stage);   // show dialog

        if (dir != null) {
            shareFolderField.setText(dir.getAbsolutePath());
            AppConfig.save(KEY_SHARE_DIR, dir.getAbsolutePath()); // lưu cấu hình
            fileShareService.setShareFolder(dir);  // áp dụng thư mục chia sẻ

            refreshSharedFiles();

            if (globalStatusLabel != null) {
                globalStatusLabel.setText("Thư mục chia sẻ: " + dir.getName());
            }
        }


    }

    // Refresh lại bảng file
    @FXML
    private void onRefreshSharedFiles() {
        refreshSharedFiles();
    }

    private void refreshSharedFiles() {
        List<SharedFileLocal> list = fileShareService.listSharedFiles(); // lấy danh sách file chia sẻ từ ổ cứng
        sharedFiles.setAll(list); // cập nhật lên bảng
    }

    // Chưa implement add/remove
    @FXML
    private void onAddSharedFile() {
        Alert a = new Alert(Alert.AlertType.INFORMATION,
                "Demo: thêm file vào thư mục chia sẻ bằng cách copy thủ công.");
        a.showAndWait();
    }

    @FXML
    private void onRemoveSharedFile() {
        SharedFileLocal selected = sharedFileTable.getSelectionModel().getSelectedItem();
        if (selected == null) {
            Alert a = new Alert(Alert.AlertType.INFORMATION, "Chưa chọn file để xóa.");
            a.showAndWait();
            return;
        }

        boolean confirmed = showConfirmDialog(
                "🗑 Xác nhận xóa",
                "Xóa file: " + selected.getFileName() + "?",
                "Hành động này sẽ xóa file khỏi ổ cứng vĩnh viễn."
        );

        if (confirmed) {
            // Lấy đường dẫn file thật
            File fileToDelete = new File(shareFolderField.getText(), selected.getFileName());
            // Thực hiện xóa
            if (fileToDelete.exists() && fileToDelete.delete()) {
                // Xóa thành công -> Cập nhật lại giao diện
                sharedFiles.remove(selected); // Xóa khỏi bảng
                notifyPeersFileRemoved(selected.getFileName());
                refreshSharedFiles();
                showSuccessDialog("Thành công", "Đã xóa file thành công!");
            } else {
                new Alert(Alert.AlertType.ERROR, "Không thể xóa file (Có thể đang mở hoặc thiếu quyền).").showAndWait();
            }
        }
    }

    private void notifyPeersFileRemoved(String fileName) {
        List<PeerInfo> activePeers = peerTabController.getActiveConnectedPeers();
        String command = "CMD:REMOVE_FILE|" + fileName;
        for (PeerInfo p : activePeers) {
            controlClient.sendSystemCommand(p, "REMOVE_FILE|" + fileName);
            System.out.println("Đã báo cho " + p.getName() + " xóa file: " + fileName);
        }
    }

    private boolean showConfirmDialog(String title, String header, String content) {
        try {
            javafx.fxml.FXMLLoader loader = new javafx.fxml.FXMLLoader(
                    getClass().getResource("/org/example/p2pfileshare/ConfirmationDialog.fxml"));
            javafx.scene.Parent page = loader.load();

            Stage dialogStage = new Stage();
            dialogStage.initStyle(javafx.stage.StageStyle.UNDECORATED);
            dialogStage.initModality(javafx.stage.Modality.APPLICATION_MODAL);

            // Lấy window hiện tại làm chủ
            if (shareFolderField.getScene() != null) {
                dialogStage.initOwner(shareFolderField.getScene().getWindow());
            }

            javafx.scene.Scene scene = new javafx.scene.Scene(page);
            dialogStage.setScene(scene);

            ConfirmationController controller = loader.getController();
            controller.setDialogStage(dialogStage);
            controller.setContent(title, header, content, "Xóa ngay");
            controller.setStyleDanger(); // Chuyển sang màu đỏ vì là xóa

            dialogStage.showAndWait();
            return controller.isConfirmed();

        } catch (Exception e) {
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

            if (sharedFileTable.getScene() != null) {
                dialogStage.initOwner(sharedFileTable.getScene().getWindow());
            }

            dialogStage.setScene(new Scene(page));

            ConfirmationController controller = loader.getController();
            controller.setDialogStage(dialogStage);

            // Set nội dung
            controller.setContent("Thông báo", header, content, "Đóng");

            // GỌI HÀM MỚI ĐỂ CHUYỂN GIAO DIỆN SANG MÀU XANH & ẨN NÚT HỦY
            controller.setStyleSuccess();

            dialogStage.showAndWait();

        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    // hàm format size của file
    private static String formatSize(long bytes) {
        final double KB = 1024.0;
        final double MB = KB * 1024.0;
        final double GB = MB * 1024.0;
        if (bytes >= GB) {
            return String.format("%.2f GB", bytes / GB);
        } else if (bytes >= MB) {
            return String.format("%.2f MB", bytes / MB);
        } else {
            return String.format("%.2f KB", bytes / KB);
        }
    }
}
