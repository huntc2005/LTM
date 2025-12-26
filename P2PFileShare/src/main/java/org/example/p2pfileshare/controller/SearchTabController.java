package org.example.p2pfileshare.controller;

import javafx.animation.PauseTransition;
import javafx.application.Platform;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.event.ActionEvent;
import javafx.fxml.FXML;
import javafx.scene.control.*;
import javafx.scene.control.cell.PropertyValueFactory;
import javafx.scene.input.KeyCode;
import javafx.stage.DirectoryChooser;
import javafx.util.Duration;
import org.example.p2pfileshare.model.PeerInfo;
import org.example.p2pfileshare.model.SearchResult;
import org.example.p2pfileshare.network.control.ControlClient;
import org.example.p2pfileshare.service.DownloadJob;
import org.example.p2pfileshare.service.FileShareService;
import org.example.p2pfileshare.service.PeerService;
import org.example.p2pfileshare.service.SearchService;
import org.example.p2pfileshare.util.AppConfig;

import java.io.File;
import java.nio.file.Path;
import java.util.List;

public class SearchTabController {

    private SearchService searchService;
    private FileShareService fileShareService;
    private ControlClient controlClient;
    private PeerService peerService;
    private Label globalStatusLabel;

    @FXML private TextField searchField;
    @FXML private TextField filterSubjectField;
    @FXML private TextField filterPeerField;

    @FXML private TableView<SearchResult> searchResultTable;
    @FXML private TableColumn<SearchResult, String> colName;
    @FXML private TableColumn<SearchResult, String> colSubject;
    @FXML private TableColumn<SearchResult, Long> colSize;
    @FXML private TableColumn<SearchResult, String> colOwner;

    @FXML private ProgressBar downloadProgress;
    @FXML private Label downloadStatusLabel;

    @FXML private Button btnDownload;
    @FXML private Button btnPause;
    @FXML private Button btnResume;
    @FXML private Button btnCancel;

    private final ObservableList<SearchResult> searchResults = FXCollections.observableArrayList();

    // NEW: job tải hiện tại (giống ConnectedPeerController)
    private DownloadJob currentJob;

    public void init(SearchService searchService,
                     FileShareService fileShareService,
                     ControlClient controlClient,
                     PeerService peerService,
                     Label globalStatusLabel) {
        this.searchService = searchService;
        this.fileShareService = fileShareService;
        this.controlClient = controlClient;
        this.peerService = peerService;
        this.globalStatusLabel = globalStatusLabel;

        setupTable();
        setupEnterKey();

        // UI ban đầu
        resetButtons();
        downloadProgress.setProgress(0);
        downloadStatusLabel.setText("Sẵn sàng");
    }

    private void setupEnterKey() {
        searchField.setOnKeyPressed(event -> { if (event.getCode() == KeyCode.ENTER) onSearch(); });
        filterSubjectField.setOnKeyPressed(event -> { if (event.getCode() == KeyCode.ENTER) onSearch(); });
        filterPeerField.setOnKeyPressed(event -> { if (event.getCode() == KeyCode.ENTER) onSearch(); });
    }

    private void setupTable() {
        colName.setCellValueFactory(new PropertyValueFactory<>("fileName"));
        colSubject.setCellValueFactory(new PropertyValueFactory<>("subject"));
        colOwner.setCellValueFactory(new PropertyValueFactory<>("ownerName"));

        colSize.setCellValueFactory(new PropertyValueFactory<>("size"));
        colSize.setCellFactory(column -> new TableCell<SearchResult, Long>() {
            @Override
            protected void updateItem(Long item, boolean empty) {
                super.updateItem(item, empty);
                if (empty || item == null) setText(null);
                else setText(formatSize(item));
            }
        });

        searchResultTable.setItems(searchResults);
        searchResultTable.setPlaceholder(new Label("Nhập điều kiện và nhấn Tìm kiếm"));
    }

    @FXML
    private void onSearch() {
        String keyword = searchField.getText() == null ? "" : searchField.getText().trim();
        String subject = filterSubjectField.getText() == null ? "" : filterSubjectField.getText().trim();
        String peerName = filterPeerField.getText() == null ? "" : filterPeerField.getText().trim();

        if (keyword.isEmpty() && subject.isEmpty() && peerName.isEmpty()) {
            showInfoDialog("Cảnh báo", "Thiếu thông tin",
                    "Vui lòng nhập ít nhất một điều kiện tìm kiếm (Tên file, Môn hoặc Peer).", false);
            return;
        }

        // reset
        searchResults.clear();
        downloadStatusLabel.setText("Đang gửi yêu cầu tìm kiếm...");
        searchResultTable.setPlaceholder(new Label("⏳ Đang tìm kiếm..."));

        // lấy peer đang kết nối
        List<String> connectedIds = controlClient.getPeerIdList();
        if (connectedIds == null || connectedIds.isEmpty()) {
            downloadStatusLabel.setText("Không có peer nào kết nối.");
            searchResultTable.setPlaceholder(new Label("Không có kết nối nào."));
            return;
        }

        List<PeerInfo> connectedPeers = peerService.getPeersByIds(connectedIds);

        int sentCount = 0;
        for (PeerInfo p : connectedPeers) {
            if (p.getPeerId().equals(peerService.getMyPeerId())) continue;

            // (Bạn đang gửi keyword, còn subject/peer filter sẽ lọc ở client)
            controlClient.sendSearchRequest(p, keyword);
            sentCount++;
        }

        downloadStatusLabel.setText("Đã gửi yêu cầu đến " + sentCount + " peer.");

        PauseTransition delay = new PauseTransition(Duration.seconds(3));
        delay.setOnFinished(e -> {
            if (searchResults.isEmpty()) {
                searchResultTable.setPlaceholder(new Label("❌ Không tìm thấy kết quả nào phù hợp."));
                downloadStatusLabel.setText("Hoàn tất tìm kiếm. Không có kết quả.");
            }
        });
        delay.play();
    }

    // Được gọi từ ControlClient khi nhận kết quả search trả về
    public void onReceiveSearchResult(PeerInfo sender, String fileData) {
        Platform.runLater(() -> {
            try {
                String[] parts = fileData.split(":");
                if (parts.length >= 2) {
                    String fName = parts[0];

                    long fSize = 0;
                    try { fSize = Long.parseLong(parts[1]); } catch (Exception ignore) {}

                    String fSubject = parts.length > 2 ? parts[2] : "Khác";

                    // filter hiện tại
                    String curKey  = searchField.getText() == null ? "" : searchField.getText().trim().toLowerCase();
                    String curSub  = filterSubjectField.getText() == null ? "" : filterSubjectField.getText().trim().toLowerCase();
                    String curPeer = filterPeerField.getText() == null ? "" : filterPeerField.getText().trim().toLowerCase();

                    if (!curKey.isEmpty() && !fName.toLowerCase().contains(curKey)) return;
                    if (!curSub.isEmpty() && !fSubject.toLowerCase().contains(curSub)) return;
                    if (!curPeer.isEmpty() && !sender.getName().toLowerCase().contains(curPeer)) return;

                    SearchResult result = new SearchResult(fName, fSize, fSubject, sender);
                    searchResults.add(result);
                    downloadStatusLabel.setText("Tìm thấy " + searchResults.size() + " kết quả.");
                }
            } catch (Exception e) {
                System.err.println("Lỗi parse search result: " + e.getMessage());
            }
        });
    }

    @FXML
    private void onViewFileDetails() {
        SearchResult selected = searchResultTable.getSelectionModel().getSelectedItem();
        if (selected == null) return;

        String content = "Kích thước: " + formatSize(selected.getSize()) + "\n" +
                "Môn học: " + selected.getSubject() + "\n" +
                "Chủ sở hữu: " + selected.getOwner().getName() + "\n" +
                "IP: " + selected.getOwner().getIp();

        showInfoDialog("Chi tiết file", selected.getFileName(), content, true);
    }

    // =========================
    // DOWNLOAD / PAUSE / RESUME / CANCEL (THẬT)
    // =========================

    @FXML
    public void onDownloadSelected(ActionEvent actionEvent) {
        SearchResult sel = searchResultTable.getSelectionModel().getSelectedItem();
        if (sel == null) {
            showInfoDialog("Thông báo", "Chưa chọn file", "Hãy chọn 1 file để tải.", false);
            return;
        }

        // tránh tải chồng
        if (currentJob != null &&
                (currentJob.getState() == DownloadJob.State.RUNNING ||
                        currentJob.getState() == DownloadJob.State.PAUSED)) {
            new Alert(Alert.AlertType.INFORMATION, "Đang có file đang tải. Hãy Pause/Cancel trước!").showAndWait();
            return;
        }

        DirectoryChooser dirChooser = new DirectoryChooser();
        dirChooser.setTitle("Chọn thư mục lưu file");

        final String KEY_LAST_DOWNLOAD_DIR = "last_download_dir";
        String last = AppConfig.load(KEY_LAST_DOWNLOAD_DIR);
        if (last != null) {
            File lastDir = new File(last);
            if (lastDir.isDirectory()) dirChooser.setInitialDirectory(lastDir);
        }

        File selectedDir = dirChooser.showDialog(searchResultTable.getScene().getWindow());
        if (selectedDir == null) {
            downloadStatusLabel.setText("Đã hủy tải xuống");
            return;
        }

        AppConfig.save(KEY_LAST_DOWNLOAD_DIR, selectedDir.getAbsolutePath());

        File saveFile = new File(selectedDir, sel.getFileName());
        Path saveTo = saveFile.toPath();

        downloadProgress.setProgress(0);
        downloadStatusLabel.setText("Đang chuẩn bị tải: " + sel.getFileName());

        // IMPORTANT:
        // - remotePath: nếu bên server yêu cầu relativePath (không chỉ fileName)
        //   thì SearchResult phải có field relativePath. Nếu chưa có, tạm dùng fileName.
        String remotePath = sel.getFileName();

        currentJob = fileShareService.startDownload(
                sel.getOwner(),
                remotePath,
                saveTo,

                p -> Platform.runLater(() -> {
                    downloadProgress.setProgress(p);
                    downloadStatusLabel.setText(
                            String.format("Đang tải: %s (%.1f%%)", sel.getFileName(), p * 100)
                    );
                }),

                s -> Platform.runLater(() -> {
                    downloadStatusLabel.setText(s);

                    // nếu service báo hoàn tất
                    if ("Tải xong".equals(s) || s.startsWith("Hoàn tất")) {
                        downloadProgress.setProgress(1.0);
                        resetButtons();
                        currentJob = null;
                    }
                })
        );

        // UI state
        btnDownload.setDisable(true);
        btnPause.setDisable(false);
        btnResume.setDisable(true);
        btnCancel.setDisable(false);
    }

    @FXML
    public void onPauseDownload(ActionEvent actionEvent) {
        if (currentJob == null) {
            downloadStatusLabel.setText("Chưa có tác vụ tải");
            return;
        }

        currentJob.pause();
        downloadStatusLabel.setText("Đã tạm dừng");

        btnPause.setDisable(true);
        btnResume.setDisable(false);
    }

    @FXML
    public void onResumeDownload(ActionEvent actionEvent) {
        if (currentJob == null) {
            downloadStatusLabel.setText("Chưa có tác vụ tải");
            return;
        }

        currentJob.resume();
        downloadStatusLabel.setText("Đang tiếp tục tải...");

        btnPause.setDisable(false);
        btnResume.setDisable(true);
    }

    @FXML
    public void onCancelDownload(ActionEvent actionEvent) {
        if (currentJob == null) {
            downloadStatusLabel.setText("Chưa có tác vụ tải");
            return;
        }

        // giống ConnectedPeerController: pause trước, delay chút rồi cancel
        currentJob.pause();
        downloadStatusLabel.setText("Đang hủy...");

        PauseTransition delay = new PauseTransition(Duration.seconds(2));
        delay.setOnFinished(e -> {
            if (currentJob == null) return;

            currentJob.cancel(); // cancel sẽ hiệu lực ở checkpoint
            currentJob = null;

            downloadProgress.setProgress(0);
            downloadStatusLabel.setText("Đã hủy tải");
            resetButtons();
        });
        delay.play();
    }

    // =========================
    // UI helpers
    // =========================

    private void resetButtons() {
        if (btnDownload != null) btnDownload.setDisable(false);
        if (btnPause != null) btnPause.setDisable(true);
        if (btnResume != null) btnResume.setDisable(true);
        if (btnCancel != null) btnCancel.setDisable(true);
    }

    private String formatSize(long bytes) {
        if (bytes >= 1024L * 1024 * 1024) return String.format("%.2f GB", bytes / (1024.0 * 1024 * 1024));
        if (bytes >= 1024L * 1024) return String.format("%.2f MB", bytes / (1024.0 * 1024));
        if (bytes >= 1024L) return String.format("%.2f KB", bytes / 1024.0);
        return bytes + " B";
    }

    // custom dialog
    private void showInfoDialog(String title, String header, String content, boolean isSuccess) {
        try {
            javafx.fxml.FXMLLoader loader = new javafx.fxml.FXMLLoader(
                    getClass().getResource("/org/example/p2pfileshare/ConfirmationDialog.fxml"));
            javafx.scene.Parent page = loader.load();

            javafx.stage.Stage dialogStage = new javafx.stage.Stage();
            dialogStage.initStyle(javafx.stage.StageStyle.UNDECORATED);
            dialogStage.initModality(javafx.stage.Modality.APPLICATION_MODAL);

            if (searchResultTable.getScene() != null) {
                dialogStage.initOwner(searchResultTable.getScene().getWindow());
            }

            javafx.scene.Scene scene = new javafx.scene.Scene(page);
            dialogStage.setScene(scene);

            ConfirmationController controller = loader.getController();
            controller.setDialogStage(dialogStage);
            controller.setContent(title, header, content, "Đóng");

            if (isSuccess) controller.setStyleSuccess();
            else controller.setStyleDanger();

            dialogStage.showAndWait();
        } catch (Exception e) {
            e.printStackTrace();
        }
    }
}
