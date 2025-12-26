package org.example.p2pfileshare.network.transfer;

import org.example.p2pfileshare.model.DownloadProgress;
import org.example.p2pfileshare.model.FileMetadata;
import org.example.p2pfileshare.network.protocol.FileTransferProtocol;
import org.example.p2pfileshare.util.FileHashUtil;

import java.io.*;
import java.net.Socket;
import java.nio.file.*;
import java.util.*;
import java.util.function.Consumer;

public class ChunkedFileClient {

    private static final int SOCKET_TIMEOUT_MS = 8000;
    private static final int MAX_RETRIES = 3;

    /**
     * Bước 1: Request metadata từ server (binary protocol)
     *
     * Expected server response:
     *   - type = FILE_META_RESPONSE
     *   - name (UTF)
     *   - fileSize (long)
     *   - chunkSize (int)
     *   - totalChunks (int)
     *   - fileSha256 (UTF)
     *   - chunkHash[i] (UTF) * totalChunks
     *
     * Or error:
     *   - type = ERROR
     *   - reason (UTF)
     */
    public static FileMetadata requestMetadata(String host, int port, String fileName) throws IOException {
        try (Socket socket = new Socket(host, port)) {
            socket.setSoTimeout(SOCKET_TIMEOUT_MS);

            try (DataInputStream in = new DataInputStream(socket.getInputStream());
                 DataOutputStream out = new DataOutputStream(socket.getOutputStream())) {

                // Send request
                String request = FileTransferProtocol.buildMetaRequest(fileName);
                out.writeUTF(request);
                // đảm bảo gửi ngay bầy giờ
                out.flush();

                // Read type
                String type = in.readUTF();
                if (type == null) throw new IOException("No response from server");

                if ("ERROR".equals(type)) {
                    String reason = in.readUTF();
                    throw new IOException("Server error: " + reason);
                }

                if (!FileTransferProtocol.FILE_META_RESPONSE.equals(type)) {
                    throw new IOException("Unexpected response type: " + type);
                }

                // Read fields
                String name = in.readUTF();
                long fileSize = in.readLong();
                int chunkSize = in.readInt();
                int totalChunks = in.readInt();
                String fileSha256 = in.readUTF();

                // Read chunk hashes
                List<String> chunkHashes = new ArrayList<>(totalChunks);
                for (int i = 0; i < totalChunks; i++) {
                    chunkHashes.add(in.readUTF());
                }

                return new FileMetadata(name, fileSize, chunkSize, totalChunks, fileSha256, chunkHashes);
            }
        }
    }

    /**
     * Download: Chunk + Resume + Integrity (resume thật sự)
     */
    public static boolean downloadFile(String host, int port, String fileName, Path saveTo,
                                       Consumer<Double> progressCallback, DownloadControl control) throws IOException {

        // Paths for resume
        // địa chỉ file tạm khi đang tải
        Path partFile = Path.of(saveTo.toString() + ".part");
        // địa chỉ lưu thông tin metadata
        Path metaFile = Path.of(saveTo.toString() + ".meta.properties");
        // địa chỉ lưu bitmap tiến độ, đánh dấu chunk đã tải xong
        Path bitmapFile = Path.of(saveTo.toString() + ".bitmap");

        // 1) Request metadata
        FileMetadata meta = requestMetadata(host, port, fileName);
        System.out.println("[ChunkedFileClient] Metadata: chunks=" + meta.getTotalChunks()
                + ", size=" + meta.getFileSize() + ", chunkSize=" + meta.getChunkSize());

        // 2) Nếu có meta cũ mà mismatch -> reset (tránh resume nhầm file)
        if (Files.exists(metaFile)) {
            if (!isSameMeta(metaFile, meta)) {
                System.out.println("[ChunkedFileClient] Meta mismatch -> reset resume files");
                safeDelete(partFile);
                safeDelete(bitmapFile);
                safeDelete(metaFile);
            }
        }

        // 3) Lưu meta (để lần sau resume)
        saveMeta(metaFile, meta);

        // 4) Tạo .part đúng size (pre-allocate) hoặc fix size nếu lệch
        ensurePartFileSized(partFile, meta.getFileSize());

        // 5) Load bitmap (resume)
        DownloadProgress progress = new DownloadProgress(
                fileName, meta.getFileSize(), meta.getChunkSize(), meta.getTotalChunks(), meta.getFileSha256()
        );
        progress.loadBitmap(bitmapFile);

        // Update initial progress
        if (progressCallback != null) {
            progressCallback.accept(progress.getProgressPercent() / 100.0);
        }

        // 6) Download missing chunks
        for (int i = 0; i < meta.getTotalChunks(); i++) {
            try {
                if (control != null) control.checkpoint();
            } catch (InterruptedException e) {
                //  CANCEL: dọn file tạm
                cleanupOnCancel(partFile, bitmapFile, metaFile);
                return false;
            }
            if (progress.isChunkComplete(i)) continue;

            boolean ok = downloadChunk(host, port, fileName, i, meta, partFile, control);
            if (!ok) {
                System.err.println("[ChunkedFileClient] Failed to download chunk " + i);
                return false;
            }

            progress.markChunkComplete(i);
            progress.saveBitmap(bitmapFile);

            if (progressCallback != null) {
                progressCallback.accept(progress.getProgressPercent() / 100.0);
            }
        }

        // 7) Verify whole file hash (final integrity)
        try {
            if (control != null) control.checkpoint(); //  trước khi verify
        } catch (InterruptedException e) {
            cleanupOnCancel(partFile, bitmapFile, metaFile);
            return false;
        }
        String actualHash = FileHashUtil.sha256(partFile);
        if (!actualHash.equals(meta.getFileSha256())) {
            System.err.println("[ChunkedFileClient] File hash mismatch! expected=" + meta.getFileSha256()
                    + " actual=" + actualHash);
            return false;
        }

        // 8) Rename & cleanup
        Files.move(partFile, saveTo, StandardCopyOption.REPLACE_EXISTING);
        safeDelete(bitmapFile);
        safeDelete(metaFile);

        System.out.println("[ChunkedFileClient] Download complete: " + saveTo);
        return true;
    }

    /**
     * Download 1 chunk (binary protocol) + verify hash (integrity)
     *
     * EXPECTED server response for chunk:
     *   - type = CHUNK_DATA
     *   - receivedIndex (int)
     *   - dataLen (int)
     *   - chunkSha256 (UTF)
     *   - chunkBytes (byte[dataLen])
     *
     * Or:
     *   - type = ERROR
     *   - reason (UTF)
     */
    private static boolean downloadChunk(String host, int port, String fileName, int chunkIndex,
                                         FileMetadata meta, Path partFile,
                                         DownloadControl control) {

        for (int retry = 1; retry <= MAX_RETRIES; retry++) {
            try {
                // checkpoint trước khi request (đúng yêu cầu)
                if (control != null) control.checkpoint();

                try (Socket socket = new Socket(host, port)) {
                    socket.setSoTimeout(SOCKET_TIMEOUT_MS);

                    try (DataInputStream in = new DataInputStream(socket.getInputStream());
                         DataOutputStream out = new DataOutputStream(socket.getOutputStream())) {

                        // checkpoint trước khi request (lần nữa cũng OK)
                        if (control != null) control.checkpoint();

                        String request = FileTransferProtocol.buildChunkRequest(fileName, chunkIndex);
                        out.writeUTF(request);
                        out.flush();

                        String type = in.readUTF();
                        if (type == null) continue;

                        if ("ERROR".equals(type)) {
                            String reason = in.readUTF();
                            System.err.println("[ChunkedFileClient] Chunk " + chunkIndex + " error: " + reason);
                            continue;
                        }

                        if (!FileTransferProtocol.CHUNK_DATA.equals(type)) continue;

                        int receivedIndex = in.readInt();
                        int dataLen = in.readInt();
                        String expectedHash = in.readUTF();

                        if (receivedIndex != chunkIndex) continue;
                        if (dataLen < 0 || dataLen > meta.getChunkSize()) continue;

                        //  checkpoint trước khi readFully (đúng yêu cầu)
                        if (control != null) control.checkpoint();

                        byte[] chunkData = new byte[dataLen];
                        in.readFully(chunkData);

                        String actualHash = FileHashUtil.sha256(chunkData);
                        if (!actualHash.equals(expectedHash)) {
                            System.err.println("[ChunkedFileClient] Chunk " + chunkIndex + " hash mismatch (retry "
                                    + retry + "/" + MAX_RETRIES + ")");
                            continue;
                        }

                        //  checkpoint trước khi write (đúng yêu cầu)
                        if (control != null) control.checkpoint();

                        long offset = (long) chunkIndex * meta.getChunkSize();
                        try (RandomAccessFile raf = new RandomAccessFile(partFile.toFile(), "rw")) {
                            raf.seek(offset);
                            raf.write(chunkData);
                        }

                        return true;
                    }
                }
            } catch (InterruptedException e) {
                // cancel/pause: pause sẽ không ném exception, cancel sẽ ném -> ta coi như fail
                return false;
            } catch (IOException e) {
                System.err.println("[ChunkedFileClient] Chunk " + chunkIndex + " error (retry "
                        + retry + "/" + MAX_RETRIES + "): " + e.getMessage());
            }
        }

        return false;
    }

    // ---------------- helpers: meta/bitmap/part ----------------

    private static void ensurePartFileSized(Path partFile, long fileSize) throws IOException {
        if (!Files.exists(partFile)) {
            try (RandomAccessFile raf = new RandomAccessFile(partFile.toFile(), "rw")) {
                raf.setLength(fileSize);
            }
            return;
        }
        long current = Files.size(partFile);
        if (current != fileSize) {
            System.out.println("[ChunkedFileClient] .part size mismatch (" + current + " != " + fileSize + ") -> reset");
            safeDelete(partFile);
            try (RandomAccessFile raf = new RandomAccessFile(partFile.toFile(), "rw")) {
                raf.setLength(fileSize);
            }
        }
    }

    private static void safeDelete(Path p) {
        try {
            if (p != null) Files.deleteIfExists(p);
        } catch (IOException ignored) {}
    }

    private static void saveMeta(Path metaFile, FileMetadata meta) throws IOException {
        Properties props = new Properties();
        props.setProperty("fileName", meta.getFileName());
        props.setProperty("fileSize", String.valueOf(meta.getFileSize()));
        props.setProperty("chunkSize", String.valueOf(meta.getChunkSize()));
        props.setProperty("totalChunks", String.valueOf(meta.getTotalChunks()));
        props.setProperty("fileSha256", meta.getFileSha256());

        try (OutputStream os = Files.newOutputStream(metaFile, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING)) {
            props.store(os, "P2P download metadata");
        }
    }

    private static boolean isSameMeta(Path metaFile, FileMetadata meta) {
        try (InputStream is = Files.newInputStream(metaFile)) {
            Properties props = new Properties();
            props.load(is);

            String fileName = props.getProperty("fileName", "");
            long fileSize = Long.parseLong(props.getProperty("fileSize", "-1"));
            int chunkSize = Integer.parseInt(props.getProperty("chunkSize", "-1"));
            int totalChunks = Integer.parseInt(props.getProperty("totalChunks", "-1"));
            String fileSha256 = props.getProperty("fileSha256", "");

            return fileName.equals(meta.getFileName())
                    && fileSize == meta.getFileSize()
                    && chunkSize == meta.getChunkSize()
                    && totalChunks == meta.getTotalChunks()
                    && fileSha256.equals(meta.getFileSha256());

        } catch (Exception e) {
            return false;
        }
    }
    private static void cleanupOnCancel(Path partFile, Path bitmapFile, Path metaFile) {
        System.out.println("[ChunkedFileClient] Cancel -> cleanup temp files");
        safeDelete(partFile);
        safeDelete(bitmapFile);
        safeDelete(metaFile);
    }

}
