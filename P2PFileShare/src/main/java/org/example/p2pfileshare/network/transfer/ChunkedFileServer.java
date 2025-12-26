package org.example.p2pfileshare.network.transfer;

import org.example.p2pfileshare.network.protocol.FileTransferProtocol;
import org.example.p2pfileshare.util.FileHashUtil;

import java.io.*;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketException;
import java.nio.file.*;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;

public class ChunkedFileServer {

    private final int port;
    private final AtomicReference<Path> shareFolder = new AtomicReference<>();
    private static final int DEFAULT_CHUNK_SIZE = 1024 * 1024; // 1 MB

    // Đỡ tạo vô hạn thread
    private ExecutorService pool = Executors.newFixedThreadPool(32);

    // ===== NEW: lifecycle =====
    private volatile boolean running = false;
    private volatile ServerSocket serverSocket;
    private Thread serverThread;

    public ChunkedFileServer(int port, Path initialFolder) {
        this.port = port;
        this.shareFolder.set(initialFolder);
    }

    /** Cho phép set null để "tắt share" */
    public void changeFolder(Path newFolder) {
        if (newFolder == null) {
            shareFolder.set(null);
            System.out.println("[ChunkedFileServer] Share folder cleared (no sharing)");
            return;
        }
        if (Files.isDirectory(newFolder)) {
            shareFolder.set(newFolder);
            System.out.println("[ChunkedFileServer] Folder changed to: " + newFolder);
        } else {
            System.out.println("[ChunkedFileServer] Ignored changeFolder (not a directory): " + newFolder);
        }
    }

    /** Trạng thái chạy để UI check */
    public boolean isRunning() {
        return running;
    }

    /** Start server (idempotent) */
    public synchronized void start() {
        if (running) {
            System.out.println("[ChunkedFileServer] Already running on port " + port);
            return;
        }

        running = true;

        // Nếu pool đã shutdown từ lần trước, tạo lại
        if (pool == null || pool.isShutdown() || pool.isTerminated()) {
            pool = Executors.newFixedThreadPool(32);
        }

        serverThread = new Thread(this::runLoop, "chunked-file-server");
        serverThread.setDaemon(true);
        serverThread.start();
    }

    private void runLoop() {
        try {
            serverSocket = new ServerSocket(port);
            System.out.println("[ChunkedFileServer] Listening on port " + port);

            while (running) {
                try {
                    Socket client = serverSocket.accept();
                    pool.submit(() -> handleClient(client));
                } catch (SocketException se) {
                    // thường xảy ra khi stopServer() -> serverSocket.close()
                    if (running) {
                        System.err.println("[ChunkedFileServer] accept() socket error: " + se.getMessage());
                    }
                    break;
                } catch (IOException e) {
                    if (running) {
                        System.err.println("[ChunkedFileServer] accept() error: " + e.getMessage());
                    }
                }
            }
        } catch (IOException e) {
            if (running) {
                System.err.println("[ChunkedFileServer] Failed to bind/listen port " + port + ": " + e.getMessage());
            }
        } finally {
            // cleanup
            closeServerSocketQuietly();
            System.out.println("[ChunkedFileServer] Server loop exited");
        }
    }

    /** NEW: Stop server */
    public synchronized void stopServer() {
        if (!running) {
            System.out.println("[ChunkedFileServer] Already stopped");
            return;
        }

        System.out.println("[ChunkedFileServer] Stopping server...");
        running = false;

        // 1) Đóng server socket để accept() thoát ngay
        closeServerSocketQuietly();

        // 2) Dừng worker đang xử lý client (tuỳ bạn muốn graceful hay hard-stop)
        if (pool != null) {
            pool.shutdownNow();
            try {
                pool.awaitTermination(2, TimeUnit.SECONDS);
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
        }

        // 3) Cho thread server kết thúc (không bắt buộc nhưng debug dễ)
        if (serverThread != null && serverThread.isAlive()) {
            try {
                serverThread.join(1000);
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
        }

        System.out.println("[ChunkedFileServer] Stopped");
    }

    private void closeServerSocketQuietly() {
        try {
            if (serverSocket != null && !serverSocket.isClosed()) {
                serverSocket.close();
            }
        } catch (IOException ignored) {
        } finally {
            serverSocket = null;
        }
    }

    private void handleClient(Socket socket) {
        try (Socket s = socket;
             DataInputStream in = new DataInputStream(s.getInputStream());
             DataOutputStream out = new DataOutputStream(s.getOutputStream())) {

            // Client gửi writeUTF -> server phải readUTF
            String request = in.readUTF();
            if (request == null || request.isBlank()) return;

            FileTransferProtocol.ParsedCommand cmd = FileTransferProtocol.parse(request);
            if (cmd == null) {
                sendError(out, "Invalid command");
                return;
            }

            Path root = shareFolder.get();
            if (root == null) {
                sendError(out, "No share folder set");
                return;
            }

            if (FileTransferProtocol.FILE_META_REQUEST.equals(cmd.command)) {
                handleMetaRequest(cmd, root, out);
            } else if (FileTransferProtocol.GET_CHUNK.equals(cmd.command)) {
                handleChunkRequest(cmd, root, out);
            } else {
                sendError(out, "Unknown command: " + cmd.command);
            }

        } catch (EOFException eof) {
            // client đóng sớm
        } catch (IOException e) {
            System.err.println("[ChunkedFileServer] Client error: " + e.getMessage());
        } catch (RejectedExecutionException ree) {
            // xảy ra khi pool đã shutdown mà vẫn có accept/submit
            System.err.println("[ChunkedFileServer] Rejected client task (server stopping): " + ree.getMessage());
        }
    }
    // hàm xử lý handleMetaRequest
    private void handleMetaRequest(FileTransferProtocol.ParsedCommand cmd, Path root, DataOutputStream out) throws IOException {
        String fileName = cmd.get(1);
        if (fileName == null) {
            sendError(out, "Missing filename");
            return;
        }

        Path filePath = root.resolve(fileName).normalize();
        if (!filePath.startsWith(root) || !Files.exists(filePath) || Files.isDirectory(filePath)) {
            sendError(out, "File not found");
            return;
        }

        long fileSize = Files.size(filePath);
        int totalChunks = (int) Math.ceil((double) fileSize / DEFAULT_CHUNK_SIZE);

        // hash toàn file
        String fileSha256 = FileHashUtil.sha256(filePath);

        // hash từng chunk
        List<String> chunkHashes = new ArrayList<>(totalChunks);
        // truy cập đến file để đọc từng chunk và băm, randomfile không cần phải đọc
        // lần lượt từ 1 -> 10 mà có thể nhảy ngay đến 10 để đọc
        try (RandomAccessFile raf = new RandomAccessFile(filePath.toFile(), "r")) {
            byte[] buffer = new byte[DEFAULT_CHUNK_SIZE];
            for (int i = 0; i < totalChunks; i++) {
                long offset = (long) i * DEFAULT_CHUNK_SIZE;
                raf.seek(offset);

                int toRead = (int) Math.min(DEFAULT_CHUNK_SIZE, fileSize - offset);
                int read = raf.read(buffer, 0, toRead);

                if (read <= 0) {
                    chunkHashes.add(FileHashUtil.sha256(new byte[0]));
                } else {
                    byte[] chunkData = new byte[read];
                    // copy buffer -> chunkData, chunk cuối có thể có kích thước nhỏ hơn
                    System.arraycopy(buffer, 0, chunkData, 0, read);
                    chunkHashes.add(FileHashUtil.sha256(chunkData));
                }
            }
        }

        out.writeUTF(FileTransferProtocol.FILE_META_RESPONSE);
        out.writeUTF(fileName);
        out.writeLong(fileSize);
        out.writeInt(DEFAULT_CHUNK_SIZE);
        out.writeInt(totalChunks);
        out.writeUTF(fileSha256);
        for (int i = 0; i < totalChunks; i++) {
            out.writeUTF(chunkHashes.get(i));
        }
        out.flush();

        System.out.println("[ChunkedFileServer] Sent metadata for " + fileName + " chunks=" + totalChunks);
    }

    private void handleChunkRequest(FileTransferProtocol.ParsedCommand cmd, Path root, DataOutputStream out) throws IOException {
        String fileName = cmd.get(1);
        String indexStr = cmd.get(2);

        if (fileName == null || indexStr == null) {
            sendError(out, "Missing parameters");
            return;
        }

        int chunkIndex;
        try {
            chunkIndex = Integer.parseInt(indexStr);
        } catch (NumberFormatException e) {
            sendError(out, "Invalid chunk index");
            return;
        }

        Path filePath = root.resolve(fileName).normalize();
        if (!filePath.startsWith(root) || !Files.exists(filePath) || Files.isDirectory(filePath)) {
            sendError(out, "File not found");
            return;
        }

        long fileSize = Files.size(filePath);
        long offset = (long) chunkIndex * DEFAULT_CHUNK_SIZE;
        if (offset >= fileSize || chunkIndex < 0) {
            sendError(out, "Chunk index out of range");
            return;
        }

        int dataLen = (int) Math.min(DEFAULT_CHUNK_SIZE, fileSize - offset);

        byte[] chunkData = new byte[dataLen];
        try (RandomAccessFile raf = new RandomAccessFile(filePath.toFile(), "r")) {
            raf.seek(offset);
            raf.readFully(chunkData);
        }

        String chunkHash = FileHashUtil.sha256(chunkData);

        out.writeUTF(FileTransferProtocol.CHUNK_DATA);
        out.writeInt(chunkIndex);
        out.writeInt(dataLen);
        out.writeUTF(chunkHash);
        out.write(chunkData);
        out.flush();

        System.out.println("[ChunkedFileServer] Sent chunk " + chunkIndex + " len=" + dataLen);
    }

    private void sendError(DataOutputStream out, String reason) throws IOException {
        out.writeUTF("ERROR");
        out.writeUTF(reason);
        out.flush();
    }
}
