package org.example.p2pfileshare.service;

import org.example.p2pfileshare.model.DownloadHistory;
import org.example.p2pfileshare.model.PeerInfo;
import org.example.p2pfileshare.model.SharedFileLocal;
import org.example.p2pfileshare.network.transfer.ChunkedFileClient;
import org.example.p2pfileshare.network.transfer.ChunkedFileServer;
import org.example.p2pfileshare.util.DownloadHistoryManager;
import org.example.p2pfileshare.service.DownloadJob;
import java.util.function.Consumer;
import java.io.File;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

public class FileShareService {

    private final int fileServerPort;
    private File shareFolder;
    private ChunkedFileServer fileServer; // dùng ChunkedFileServer để chia sẻ file
    private HistoryService historyService;
    private String myDisplayName;

    public FileShareService(int fileServerPort, HistoryService historyService) {
        this.fileServerPort = fileServerPort;
        this.historyService = historyService;
    }

    // khởi tạo server từ người share
    public synchronized void startServer() {
        if (fileServer == null && shareFolder != null) {
            fileServer = new ChunkedFileServer(fileServerPort, shareFolder.toPath());
            fileServer.start();
        }
    }

    // dừng server
    public void stopServer() {
        if (fileServer != null) {
            fileServer.stopServer();
            fileServer = null;
        }
    }

    // đổi folder chia sẻ
    public synchronized void setShareFolder(File folder) {
        // folder null → không share
        if (folder == null || !folder.exists() || !folder.isDirectory()) {
            this.shareFolder = null;
            if (fileServer != null) {
                fileServer.changeFolder(null);
            }
            return;
        }

        // set thư mục mới
        this.shareFolder = folder;

        // nếu server chưa chạy → khởi động
        if (fileServer == null) {
            fileServer = new ChunkedFileServer(fileServerPort, folder.toPath());
            fileServer.start();
        }
        // nếu server đang chạy → đổi folder ngay lập tức không cần restart
        else {
            fileServer.changeFolder(folder.toPath());
        }
    }

    public File getShareFolder() {
        return shareFolder;
    }

    // tải file từ peer khác (client)
//    public boolean download(PeerInfo peer, String relativePath, Path saveTo,
//                           java.util.function.Consumer<Double> progressCallback) {
//        boolean success = false;
//        try {
//            success = ChunkedFileClient.downloadFile(
//                    peer.getIp(),
//                    peer.getFileServerPort(),
//                    relativePath,
//                    saveTo,
//                    progressCallback
//            );
//        } catch (Exception e) {
//            e.printStackTrace();
//            return false;
//        }
//
//        System.out.println("Trạng thái download: " + success);
//
//        if (success) {
//            try {
//                // Lưu lịch sử tải xuống
//                DownloadHistory history = new DownloadHistory(
//                        saveTo.getFileName().toString(),
//                        saveTo.toAbsolutePath().toString(),
//                        peer.getName(),
//                        peer.getIp(),
//                        LocalDateTime.now()
//                );
//                historyService.addHistory(history);
//                System.out.println("✓ Đã lưu lịch sử tải xuống: " + saveTo.getFileName());
//            } catch (Exception e) {
//                System.err.println("✗ Lỗi khi lưu lịch sử tải xuống:");
//                e.printStackTrace();
//            }
//        }
//
//        return success;
//    }

    // Overload cũ để tương thích
//    public boolean download(PeerInfo peer, String relativePath, Path saveTo) {
//        return download(peer, relativePath, saveTo, null);
//    }

    // lưu lịch sử download
    public List<DownloadHistory> listDownloadHistory() {
        return historyService.loadHistory();
    }

    // liệt kê file trong thư mục chia sẻ
    public List<SharedFileLocal> listSharedFiles() {
        List<SharedFileLocal> result = new ArrayList<>();

        if (shareFolder == null) return result;

        File[] files = shareFolder.listFiles();
        if (files == null) return result;

        for (File f : files) {
            if (f.isFile()) {
                result.add(buildMetadata(f));
            }
        }

        return result;
    }

    public List<SharedFileLocal> searchLocalFiles(String keyword) {
        // 1. Lấy tất cả file đang chia sẻ
        List<SharedFileLocal> allFiles = listSharedFiles();

        // 2. Nếu từ khóa trống thì trả về danh sách rỗng (hoặc trả hết tùy ý)
        if (keyword == null || keyword.trim().isEmpty() || keyword.equals("*")) {
            return new ArrayList<>(allFiles);
        }

        String searchKey = keyword.toLowerCase().trim();

        // 3. Lọc file khớp tên
        return allFiles.stream()
                .filter(f -> f.getFileName().toLowerCase().contains(searchKey))
                .collect(Collectors.toList());
    }

    // tạo metadata cho file
    private SharedFileLocal buildMetadata(File f) {
        String fileName = f.getName();
        long size = f.length();

        // đường dẫn tương đối để bên client tải về đúng
        String relativePath = shareFolder.toPath()
                .relativize(f.toPath())
                .toString();

        String extension = "";
        int dot = fileName.lastIndexOf('.');
        if (dot >= 0 && dot < fileName.length() - 1) {
            extension = fileName.substring(dot + 1).toLowerCase();
        }

        String subject = detectSubject(folderNameOrFileName(f));
        String tags = "Toán";

        return new SharedFileLocal(
                fileName,
                relativePath,
                extension,
                size,
                subject,
                tags,
                true
        );
    }

    private String detectSubject(String name) {
        name = name.toLowerCase();

        if (name.contains("java") || name.contains("oop")) return "Java";
        if (name.contains("network")) return "Network";
        if (name.contains("os") || name.contains("process") || name.contains("thread")) return "Operating System";
        if (name.contains("ai") || name.contains("machine")) return "AI";
        if (name.contains("math")) return "Math";

        return "Khác";
    }

    private String folderNameOrFileName(File f) {
        File parent = f.getParentFile();
        if (parent != null) return parent.getName() + " " + f.getName();
        return f.getName();
    }

    public void setMyDisplayName(String myDisplayName) {
        this.myDisplayName = myDisplayName;
    }

    public String getMyDisplayName() {
        return myDisplayName;
    }

    public DownloadJob startDownload(PeerInfo peer, String relativePath, Path saveTo,
                                     Consumer<Double> progressCallback,
                                     Consumer<String> statusCallback) {

        // Tạo Job: service chỉ tạo & trả về, UI giữ job để pause/resume/cancel
        DownloadJob job = new DownloadJob(
                peer.getIp(),
                peer.getFileServerPort(),
                relativePath,
                saveTo,
                p -> {
                    if (progressCallback != null) progressCallback.accept(p);
                },
                state -> {
                    // Map state -> status text
                    if (statusCallback != null) {
                        switch (state) {
                            case NEW -> statusCallback.accept("Đang chuẩn bị...");
                            case RUNNING -> statusCallback.accept("Đang tải...");
                            case PAUSED -> statusCallback.accept("Đã tạm dừng");
                            case CANCELLED -> statusCallback.accept("Đã hủy tải");
                            case COMPLETED -> statusCallback.accept("Tải xong");
                            case FAILED -> statusCallback.accept("Tải thất bại");
                        }
                    }

                    // Nếu COMPLETED thì lưu history ngay tại service
                    if (state == DownloadJob.State.COMPLETED) {
                        try {
                            DownloadHistory history = new DownloadHistory(
                                    saveTo.getFileName().toString(),
                                    saveTo.toAbsolutePath().toString(),
                                    peer.getName(),
                                    peer.getIp(),
                                    LocalDateTime.now()
                            );
                            historyService.addHistory(history);

                            if (statusCallback != null) {
                                statusCallback.accept("✓ Đã lưu lịch sử tải xuống");
                            }
                        } catch (Exception e) {
                            System.err.println("✗ Lỗi khi lưu lịch sử tải xuống:");
                            e.printStackTrace();
                        }
                    }
                },
                err -> {
                    // lỗi kỹ thuật (IO, timeout...) -> statusCallback
                    if (statusCallback != null) {
                        statusCallback.accept("Lỗi: " + err.getMessage());
                    }
                }
        );

        // Service KHÔNG block: chỉ start thread và trả job về cho UI
        job.start();
        return job;
    }

}
