package org.example.p2pfileshare.network.protocol;

public class FileTransferProtocol {

    // Commands
    public static final String FILE_META_REQUEST = "FILE_META_REQUEST";
    public static final String FILE_META_RESPONSE = "FILE_META_RESPONSE";
    public static final String GET_CHUNK = "GET_CHUNK";
    public static final String CHUNK_DATA = "CHUNK_DATA";
    public static final String CHUNK_ERROR = "CHUNK_ERROR";

    // Build request for file metadata
    public static String buildMetaRequest(String fileName) {
        return FILE_META_REQUEST + "|" + fileName;
    }

    // Build request for specific chunk
    public static String buildChunkRequest(String fileName, int chunkIndex) {
        return GET_CHUNK + "|" + fileName + "|" + chunkIndex;
    }

    // Parse command
    public static ParsedCommand parse(String line) {
        if (line == null || line.isEmpty()) return null;
        String[] parts = line.split("\\|");
        if (parts.length == 0) return null;
        return new ParsedCommand(parts[0], parts);
    }

    public static class ParsedCommand {
        public final String command;
        public final String[] parts;

        public ParsedCommand(String command, String[] parts) {
            this.command = command;
            this.parts = parts;
        }

        public String get(int index) {
            return index < parts.length ? parts[index] : null;
        }
    }
}

