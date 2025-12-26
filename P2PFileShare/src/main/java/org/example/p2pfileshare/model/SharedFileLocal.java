package org.example.p2pfileshare.model;

public class SharedFileLocal {

    private final String fileName;        // Tên file
    private final String relativePath;
    private final String extension;// Đường dẫn tương đối trong thư mục chia sẻ
    private final long size;              // Dung lượng file
    private final String subject;         // Chủ đề/môn học (optional)
    private final String tags;            // Tags (optional)

    private boolean visible;              // Có chia sẻ hay không

    public SharedFileLocal(String fileName,
                           String relativePath,
                           String extension,
                           long size,
                           String subject,
                           String tags,
                           boolean visible) {

        this.fileName = fileName;
        this.relativePath = relativePath;
        this.size = size;
        this.extension = extension;
        this.subject = subject;
        this.tags = tags;

        this.visible = visible;
    }

    // ===== GETTERS =====
    public String getFileName()        { return fileName; }
    public String getRelativePath()    { return relativePath; }
    public long getSize()              { return size; }
    public String getSubject()         { return subject; }
    public String getTags()            { return tags; }
    public boolean isVisible()         { return visible; }
    public String getExtension()       { return extension; }
    // ===== SETTERS =====
    public void setVisible(boolean visible) {
        this.visible = visible;
    }
}
