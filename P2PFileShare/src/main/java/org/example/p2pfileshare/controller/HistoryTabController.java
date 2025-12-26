package org.example.p2pfileshare.controller;

import javafx.application.Platform;
import javafx.beans.property.ReadOnlyStringWrapper;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.fxml.FXML;
import javafx.fxml.FXMLLoader;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.control.*;
import javafx.scene.control.cell.PropertyValueFactory;
import javafx.stage.Modality;
import javafx.stage.Stage;
import javafx.stage.StageStyle;
import org.example.p2pfileshare.model.DownloadHistory;
import org.example.p2pfileshare.service.HistoryService;

import java.io.IOException;
import java.time.format.DateTimeFormatter;
import java.util.List;

public class HistoryTabController {

    private HistoryService historyService;
    private Label globalStatusLabel;

    @FXML private TableView<DownloadHistory> historyTable;

    // data table
    private final ObservableList<DownloadHistory> histories = FXCollections.observableArrayList();
    private final DateTimeFormatter dateFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    // callback lắng nghe thay đổi
    private Runnable historyChangeListener;

    public void init(HistoryService historyService, Label globalStatusLabel) {
        this.historyService = historyService;
        this.globalStatusLabel = globalStatusLabel;

        setupTable();
        // initial load
        refreshHistory();

        // Đăng ký lắng nghe để tự động cập nhật UI khi file lịch sử thay đổi
        if (this.historyService != null) {
            historyChangeListener = () -> Platform.runLater(this::refreshHistory);
            this.historyService.addHistoryChangeListener(historyChangeListener);
            System.out.println("Đã đăng ký callback theo dõi file lịch sử.");
        }
    }

    public void setupTable() {
        if (historyTable == null) return;

        historyTable.setItems(histories);
        historyTable.getColumns().clear();

        // tên file
        TableColumn<DownloadHistory, String> colName = new TableColumn<>("Tên File");
        colName.setCellValueFactory(new PropertyValueFactory<>("fileName"));
        colName.setPrefWidth(200);

        // đường dẫn lưu
        TableColumn<DownloadHistory, String> colPath = new TableColumn<>("Đường dẫn lưu");
        colPath.setCellValueFactory(new PropertyValueFactory<>("savedPath"));
        colPath.setPrefWidth(350);

        // Nguồn Peer
        TableColumn<DownloadHistory, String> colPeer = new TableColumn<>("Peer");
        colPeer.setCellValueFactory(cell -> {
            DownloadHistory d = cell.getValue();
            String v = (d.getPeerName() == null ? "" : d.getPeerName()) +
                    (d.getPeerIp() == null || d.getPeerIp().isBlank() ? "" : " (" + d.getPeerIp() + ")");
            return new ReadOnlyStringWrapper(v);

        });
        colPeer.setPrefWidth(200);

        // ngày tải
        TableColumn<DownloadHistory, String> colDate = new TableColumn<>("Downloaded At");
        colDate.setCellValueFactory(cell -> {
            DownloadHistory d = cell.getValue();
            String s = d.getDownloadDate() != null ? d.getDownloadDate().format(dateFormatter) : "";
            return new ReadOnlyStringWrapper(s);
        });
        colDate.setPrefWidth(160);

        historyTable.getColumns().addAll(colName, colPath, colPeer, colDate);
    }

    @FXML
    private void onRefreshHistory() {
        refreshHistory();
        if (globalStatusLabel != null) {
            globalStatusLabel.setText("Lịch sử đã nạp: " + histories.size() + " mục");
        }
    }

    private void refreshHistory() {
        if (historyService == null) return;
        List<DownloadHistory> list = historyService.listHistories();
        histories.setAll(list);
        System.out.println("da goi lai ham callback thanh cong - so muc: " + list.size());
    }

    @FXML
    private void onClearHistory() {
        if (historyService == null) return;

        if (histories.isEmpty()) {
            // Nếu trống thì không cần xóa, chỉ báo nhẹ
            if (globalStatusLabel != null) globalStatusLabel.setText("Lịch sử đang trống.");
            return;
        }

        try {
            // 1. Load Dialog
            FXMLLoader loader = new FXMLLoader(getClass().getResource("/org/example/p2pfileshare/ConfirmationDialog.fxml"));
            Parent page = loader.load();

            // 2. Tạo Stage không viền
            Stage dialogStage = new Stage();
            dialogStage.initStyle(StageStyle.UNDECORATED);
            dialogStage.initModality(Modality.APPLICATION_MODAL);

            if (historyTable.getScene() != null) {
                dialogStage.initOwner(historyTable.getScene().getWindow());
            }

            dialogStage.setScene(new Scene(page));

            // 3. Cấu hình Controller
            ConfirmationController controller = loader.getController();
            controller.setDialogStage(dialogStage);

            // Thiết lập nội dung hiển thị dialog
            controller.setContent(
                    "🗑 Xóa lịch sử",
                    "Bạn có muốn xóa toàn bộ " + histories.size() + " mục lịch sử không?",
                    "Hành động này sẽ xóa danh sách nhật ký tải xuống vĩnh viễn.",
                    "Xóa ngay"
            );

            // Chuyển sang màu đỏ cảnh báo
            controller.setStyleDanger();

            // 4. Hiển thị
            dialogStage.showAndWait();

            // 5. Xử lý kết quả
            if (controller.isConfirmed()) {
                historyService.clearHistory();
                refreshHistory();
                if (globalStatusLabel != null) {
                    globalStatusLabel.setText("Đã xóa toàn bộ lịch sử tải xuống.");
                }
            }

        } catch (IOException e) {
            e.printStackTrace();
            // Fallback nếu lỗi load FXML
            new Alert(Alert.AlertType.ERROR, "Lỗi hiển thị hộp thoại: " + e.getMessage()).showAndWait();
        }
    }

    private void showSuccessDialog(String header, String content) {
        try {
            FXMLLoader loader = new FXMLLoader(getClass().getResource("/org/example/p2pfileshare/ConfirmationDialog.fxml"));
            Parent page = loader.load();

            Stage dialogStage = new Stage();
            dialogStage.initStyle(StageStyle.UNDECORATED);
            dialogStage.initModality(Modality.APPLICATION_MODAL);
            if (historyTable.getScene() != null) {
                dialogStage.initOwner(historyTable.getScene().getWindow());
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

    // optional: call this when controller is disposed to avoid leaks
    public void dispose() {
        if (historyService != null && historyChangeListener != null) {
            historyService.removeHistoryChangeListener(historyChangeListener);
        }
    }
}
