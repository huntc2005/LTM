package org.example.p2pfileshare.controller;

import javafx.animation.PauseTransition;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.concurrent.Task;
import javafx.fxml.FXML;
import javafx.scene.control.*;
import javafx.scene.control.cell.PropertyValueFactory;
import javafx.stage.DirectoryChooser;
import org.example.p2pfileshare.model.PeerInfo;
import org.example.p2pfileshare.network.control.ControlClient;
import org.example.p2pfileshare.service.DownloadJob;
import org.example.p2pfileshare.service.FileShareService;
import org.example.p2pfileshare.util.AppConfig;

import java.io.File;
import java.nio.file.Path;
import javafx.util.Duration;
import java.util.List;
import javafx.application.Platform;

public class ConnectedPeerController {

    @FXML private Label peerNameLabel;
    @FXML private TableView<Row> fileTable;
    @FXML private TableColumn<Row, String> colName;
    @FXML private TableColumn<Row, String> colRelative;
    @FXML private TableColumn<Row, Long>   colSize;

    @FXML private ProgressBar progress;
    @FXML private Label statusLabel;

    @FXML private Button btnDownload;
    @FXML private Button btnPause;
    @FXML private Button btnResume;
    @FXML private Button btnCancel;

    private final ObservableList<Row> rows = FXCollections.observableArrayList();
    private DownloadJob currentJob;
    private PeerInfo peer;
    private ControlClient controlClient;
    private FileShareService fileShareService;

    // NEW: callback được PeerTabController đăng ký để biết khi tab này đã ngắt kết nối thành công
    private Runnable onDisconnectedCallback;

    public void init(PeerInfo peer, ControlClient controlClient, FileShareService fileShareService) {
        this.peer = peer;
        this.controlClient = controlClient;
        this.fileShareService = fileShareService;

        peerNameLabel.setText(peer.getName() + " (" + peer.getIp() + ")");

        colName.setCellValueFactory(new PropertyValueFactory<>("name"));
        colRelative.setCellValueFactory(new PropertyValueFactory<>("relativePath"));
        colSize.setCellValueFactory(new PropertyValueFactory<>("size"));
        // Format hiển thị kích thước: KB/MB/GB
        colSize.setCellFactory(col -> new TableCell<Row, Long>() {
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
        fileTable.setItems(rows);

        // Thêm context menu chuột phải
        setupContextMenu();

        // nạp lần đầu
        reload();
    }
    @FXML
    private void onPauseDownload() {
        if (currentJob == null) {
            statusLabel.setText("Chưa có tác vụ tải");
            return;
        }
        currentJob.pause();
        statusLabel.setText("Đã tạm dừng");
        if (btnPause != null) btnPause.setDisable(true);      // Mở nút Pause
        if (btnResume != null) btnResume.setDisable(false);     // Khóa nút Resume

    }

    @FXML
    private void onResumeDownload() {
        if (currentJob == null) {
            statusLabel.setText("Chưa có tác vụ tải");
            return;
        }
        currentJob.resume();
        statusLabel.setText("Đang tiếp tục tải...");
        if (btnPause != null) btnPause.setDisable(false);      // Mở nút Pause
        if (btnResume != null) btnResume.setDisable(true);     // Khóa nút Resume

    }

    @FXML
    private void onCancelDownload() {
        if (currentJob == null) {
            statusLabel.setText("Chưa có tác vụ tải");
            return;
        }
        currentJob.pause();

        PauseTransition delay = new PauseTransition(Duration.seconds(3));
        delay.setOnFinished(e -> {
            if (currentJob == null) return; // phòng trường hợp đã bị đổi job
            currentJob.cancel(); // cancel sẽ hiệu lực ở checkpoint
            currentJob = null;
            progress.setProgress(0);
            statusLabel.setText("Đã hủy tải");
            resetButtons();
        });
        delay.play();
    }

    private void setupContextMenu() {
        ContextMenu contextMenu = new ContextMenu();

        MenuItem downloadItem = new MenuItem("Tải xuống");
        downloadItem.setOnAction(e -> {
            Row selected = fileTable.getSelectionModel().getSelectedItem();
            if (selected != null) {
                downloadFile(selected);
            }
        });

        contextMenu.getItems().add(downloadItem);

        // Chỉ hiển thị menu khi có item được chọn
        fileTable.setContextMenu(contextMenu);

        // Hoặc có thể hiển thị menu chỉ khi chuột phải vào row có dữ liệu
        fileTable.setRowFactory(tv -> {
            TableRow<Row> row = new TableRow<>();
            row.setOnContextMenuRequested(event -> {
                if (!row.isEmpty()) {
                    fileTable.getSelectionModel().select(row.getItem());
                    contextMenu.show(row, event.getScreenX(), event.getScreenY());
                }
            });
            return row;
        });
    }

    @FXML
    public void onReload() {
        reload();
    }

    // thay đổi: public để có thể gọi reload từ bên ngoài (PeerTabController)
    public void reload() {
        statusLabel.setText("Đang tải danh sách...");
        Task<List<ControlClient.RemoteFile>> task = new Task<>() {
            @Override
            protected List<ControlClient.RemoteFile> call() {
                return controlClient.listFiles(peer);
            }
        };

        task.setOnSucceeded(e -> {
            rows.clear();
            for (var rf : task.getValue()) {
                rows.add(new Row(rf.name, rf.relativePath, rf.size));
            }
            statusLabel.setText("Đã nạp " + rows.size() + " file");
        });
        task.setOnFailed(e -> statusLabel.setText("Lỗi tải danh sách"));

        new Thread(task, "reload-remote-files").start();
    }

    // mới: gọi khi peer remote bị server ngắt kết nối để cập nhật UI tab
    public void onPeerDisconnected() {
        // chạy trên JavaFX thread nếu gọi từ background
        statusLabel.setText("Peer đã bị ngắt kết nối");
        progress.setProgress(0);
        // Có thể disable các control nếu muốn
        fileTable.setDisable(true);
    }

    @FXML
    private void onDownloadSelected() {
        Row sel = fileTable.getSelectionModel().getSelectedItem();
        if (sel == null) {
            showSuccessDialog("Thông báo", "Hãy chọn 1 file để tải");
            return;
        }
        downloadFile(sel);
    }

    private void downloadFile(Row fileRow) {
        // Nếu đang có job chạy, tránh tải chồng (tùy bạn cho phép nhiều job)
        if (currentJob != null && (currentJob.getState() == DownloadJob.State.RUNNING || currentJob.getState() == DownloadJob.State.PAUSED)) {
            new Alert(Alert.AlertType.INFORMATION, "Đang có file đang tải. Hãy Pause/Cancel trước!").showAndWait();
            return;
        }

        DirectoryChooser dirChooser = new DirectoryChooser();
        dirChooser.setTitle("Chọn thư mục lưu file");

        final String KEY_LAST_DOWNLOAD_DIR = "last_download_dir";
        String last = AppConfig.load(KEY_LAST_DOWNLOAD_DIR);
        if (last != null) {
            File lastDir = new File(last);
            if (lastDir.isDirectory()) {
                dirChooser.setInitialDirectory(lastDir);
            }
        }

        File selectedDir = dirChooser.showDialog(peerNameLabel.getScene().getWindow());
        if (selectedDir == null) {
            statusLabel.setText("Đã hủy tải xuống");
            return;
        }

        AppConfig.save(KEY_LAST_DOWNLOAD_DIR, selectedDir.getAbsolutePath());

        File selectedFile = new File(selectedDir, fileRow.name);
        Path saveTo = selectedFile.toPath();

        progress.setProgress(0);
        statusLabel.setText("Đang chuẩn bị tải: " + fileRow.name);


        //  service tạo job + chạy nền + trả về handle
        currentJob = fileShareService.startDownload(
                peer,
                fileRow.relativePath,
                saveTo,
                // progressCallback: 0..1
                p -> Platform.runLater(() -> {
                    progress.setProgress(p);
                    statusLabel.setText(String.format("Đang tải: %s (%.1f%%)", fileRow.name, p * 100));
                }),
                // statusCallback: text trạng thái
                s -> Platform.runLater(() -> {
                    // Nếu muốn: không ghi đè status khi đang hiển thị % thì bạn có thể refine logic
                    statusLabel.setText(s);
                    if ("Tải xong".equals(s) || s.startsWith("Hoàn tất")) {
                        this.resetButtons();
                        progress.setProgress(1.0);
                    }
                })
        );

        if (btnDownload != null) btnDownload.setDisable(true); // Đang tải thì khóa nút tải
        if (btnPause != null) btnPause.setDisable(false);      // Mở nút Pause
        if (btnResume != null) btnResume.setDisable(true);     // Khóa nút Resume
        if (btnCancel != null) btnCancel.setDisable(false);    // Mở nút Cancel


    }


    // Hàm reset trạng thái nút về ban đầu
    private void resetButtons() {
        if (btnDownload != null) btnDownload.setDisable(false);
        if (btnPause != null) btnPause.setDisable(true);
        if (btnResume != null) btnResume.setDisable(true);
        if (btnCancel != null) btnCancel.setDisable(true);
    }

    // NEW: setter callback
    public void setOnDisconnected(Runnable callback) {
        this.onDisconnectedCallback = callback;
    }

    public void receivedMessage(String message) {
        if (message == null) return;

        if (message.startsWith("CMD:REMOVE_FILE|")) {
            String[] parts = message.split("\\|");
            if (parts.length >= 2) {
                String fileNameToRemove = parts[1];

                Platform.runLater(() -> {
                    removeFileFromList(fileNameToRemove);
                });
            }
        }
    }

    private void removeFileFromList(String fileName) {
        Platform.runLater(() -> {

            System.out.println("[DEBUG] Bắt đầu xóa file: [" + fileName + "]");
            System.out.println("[DEBUG] Số dòng hiện tại: " + rows.size());

            boolean removed = rows.removeIf(row -> {
                String rowName = row.getName().trim();
                String targetName = fileName.trim();
                return rowName.equalsIgnoreCase(targetName);
            });


            if (removed) {
                statusLabel.setText("Đối phương vừa xóa file: " + fileName);
                fileTable.refresh();
            } else {
                System.out.println("Không tìm thấy file để xóa!");
            }
        });
    }


    @FXML
    private void onDisconnect() {
        // Thực hiện tương tự logic ở PeerTabController: gửi request tới peer, chờ phản hồi rồi cập nhật UI
        if (peer == null) {
            new Alert(Alert.AlertType.INFORMATION, "Peer không hợp lệ").showAndWait();
            return;
        }

        // hiển thị dialog hỏi xác nhận
        boolean confirmed = showConfirmDialog(
                "🔌 Ngắt kết nối",
                "Bạn có chắc muốn ngắt kết nối với " + peer.getName() + "?",
                "Hành động này sẽ dừng mọi tiến trình tải file đang chạy."
        );

        // nếu hủy hoặc tắt thì thoát luôn
        if (!confirmed) {
            return;
        }

        if (currentJob != null) {
            currentJob.cancel();
            currentJob = null;
        }

        // Reset UI nút bấm
        resetButtons();

        // Nếu chưa kết nối thì thoát luôn
        if (peer.getConnectionState() != PeerInfo.ConnectionState.CONNECTED) {
            statusLabel.setText("Đã ngắt kết nối");
            fileTable.setDisable(true);
            return;
        }

        // update UI
        statusLabel.setText("Đang ngắt kết nối...");
        peer.setConnectionState(PeerInfo.ConnectionState.PENDING);
        // Cập nhật progress/disable UI tạm thời
        progress.setProgress(-1);
        fileTable.setDisable(true);

        // send request qua mạng
        Task<Boolean> task = new Task<>() {
            @Override
            protected Boolean call() {
//                currentJob.pause();

//                PauseTransition delay = new PauseTransition(Duration.seconds(3));
//                delay.setOnFinished(e -> {
//                    if (currentJob == null) return; // phòng trường hợp đã bị đổi job
//                    currentJob.cancel(); // cancel sẽ hiệu lực ở checkpoint
//                    currentJob = null;
//                    progress.setProgress(0);
//                    statusLabel.setText("Đã hủy tải");
//                    resetButtons();
//                });
//                delay.play();
                return controlClient.sendDisconnectRequest(peer);
            }
        };

        task.setOnSucceeded(e -> {
            boolean ok = Boolean.TRUE.equals(task.getValue());
            if (ok) {

                // Thành công: cập nhật UI tab
                statusLabel.setText("Đã ngắt kết nối");
                progress.setProgress(0);
                fileTable.setDisable(true);

                showSuccessDialog("Thành công", "Đã ngắt kết nối với peer.");

                // Gọi callback để PeerTabController cập nhật danh sách peer và remove controller
                if (onDisconnectedCallback != null) {
                    try { onDisconnectedCallback.run(); } catch (Exception ex) { ex.printStackTrace(); }
                }

            } else {
                // Thất bại: rollback UI, cho phép thao tác lại
                statusLabel.setText("Ngắt kết nối thất bại");
                progress.setProgress(0);
                fileTable.setDisable(false);
                peer.setConnectionState(PeerInfo.ConnectionState.CONNECTED);
                new Alert(Alert.AlertType.WARNING, "Không thể gửi yêu cầu ngắt kết nối tới peer").showAndWait();
            }
        });

        task.setOnFailed(e -> {
            statusLabel.setText("Lỗi khi ngắt kết nối");
            progress.setProgress(0);
            fileTable.setDisable(false);
            peer.setConnectionState(PeerInfo.ConnectionState.CONNECTED);
            new Alert(Alert.AlertType.ERROR, "Lỗi khi thực hiện ngắt kết nối: " + task.getException()).showAndWait();
        });

        new Thread(task, "disconnect-from-connected-tab").start();
    }

    // custom dialog
    private boolean showConfirmDialog(String title, String header, String content) {
        try {
            javafx.fxml.FXMLLoader loader = new javafx.fxml.FXMLLoader(
                    getClass().getResource("/org/example/p2pfileshare/ConfirmationDialog.fxml"));
            javafx.scene.Parent page = loader.load();

            javafx.stage.Stage dialogStage = new javafx.stage.Stage();
            dialogStage.initStyle(javafx.stage.StageStyle.UNDECORATED);
            dialogStage.initModality(javafx.stage.Modality.APPLICATION_MODAL);

            // Lấy window hiện tại làm chủ để hiện dialog ở giữa
            if (peerNameLabel.getScene() != null) {
                dialogStage.initOwner(peerNameLabel.getScene().getWindow());
            }
            dialogStage.setScene(new javafx.scene.Scene(page));

            ConfirmationController controller = loader.getController();
            controller.setDialogStage(dialogStage);

            // Thiết lập nội dung
            controller.setContent(title, header, content, "Ngắt kết nối");
            controller.setStyleDanger(); // Màu đỏ cảnh báo

            dialogStage.showAndWait();
            return controller.isConfirmed();

        } catch (Exception e) {
            e.printStackTrace();
            return false;
        }
    }

    private void showSuccessDialog(String header, String content) {
        try {
            javafx.fxml.FXMLLoader loader = new javafx.fxml.FXMLLoader(
                    getClass().getResource("/org/example/p2pfileshare/ConfirmationDialog.fxml"));
            javafx.scene.Parent page = loader.load();

            javafx.stage.Stage dialogStage = new javafx.stage.Stage();
            dialogStage.initStyle(javafx.stage.StageStyle.UNDECORATED);
            dialogStage.initModality(javafx.stage.Modality.APPLICATION_MODAL);

            if (peerNameLabel.getScene() != null) {
                dialogStage.initOwner(peerNameLabel.getScene().getWindow());
            }
            dialogStage.setScene(new javafx.scene.Scene(page));

            ConfirmationController controller = loader.getController();
            controller.setDialogStage(dialogStage);
            controller.setContent("Thông báo", header, content, "Đóng");
            controller.setStyleSuccess(); // Màu xanh thành công

            dialogStage.showAndWait();

        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    // Row model cho TableView
    public static class Row {
        private final String name;
        private final String relativePath;
        private final long size;
        public Row(String name, String relativePath, long size) {
            this.name = name; this.relativePath = relativePath; this.size = size;
        }
        public String getName() { return name; }
        public String getRelativePath() { return relativePath; }
        public long getSize() { return size; }
    }

    // Helper: đổi bytes -> KB/MB/GB theo ngưỡng
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
    public void updatePeerDisplayName(String newName) {
        if (newName == null) return;
        Platform.runLater(() -> {
            peerNameLabel.setText(newName + " (" + peer.getIp() + ")");
        });

    }

}

