package org.example.p2pfileshare.model;

public class SearchResult {
    private final String fileName;
    private final long size;
    private final String subject; // Môn học (nếu có logic phân loại)
    private final PeerInfo owner; // Người sở hữu file

    public SearchResult(String fileName, long size, String subject, PeerInfo owner) {
        this.fileName = fileName;
        this.size = size;
        this.subject = subject;
        this.owner = owner;
    }

    public String getFileName() { return fileName; }
    public long getSize() { return size; }
    public String getSubject() { return subject; }
    public PeerInfo getOwner() { return owner; }

    // Helper để hiển thị tên chủ sở hữu trên bảng
    public String getOwnerName() {
        return owner != null ? owner.getName() : "Unknown";
    }
}