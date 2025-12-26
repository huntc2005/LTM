package org.example.p2pfileshare.util;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;
import org.example.p2pfileshare.model.DownloadHistory;

import java.io.*;
import java.lang.reflect.Type;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

public class DownloadHistoryManager {

    private static final String HISTORY_FILE = "download_history.json";

    private final Path historyPath;
    private final Gson gson;

    // listeners to notify when history changes
    private final List<Runnable> listeners = new CopyOnWriteArrayList<>();

    public DownloadHistoryManager() {
        this.historyPath = Paths.get(HISTORY_FILE);

        this.gson = new GsonBuilder()
                .setPrettyPrinting()
                .registerTypeAdapter(LocalDateTime.class, new LocalDateTimeAdapter())
                .create();
    }

    // Load lịch sử từ file
    public synchronized List<DownloadHistory> loadHistory() {
        try {
            if (!Files.exists(historyPath)) {
                return new ArrayList<>();
            }

            try (Reader reader = new FileReader(historyPath.toFile())) {
                Type listType = new TypeToken<ArrayList<DownloadHistory>>() {}.getType();
                List<DownloadHistory> histories = gson.fromJson(reader, listType);
                return histories != null ? histories : new ArrayList<>();
            }

        } catch (IOException e) {
            e.printStackTrace();
            return new ArrayList<>();
        }
    }

    // Lưu lịch sử xuống file
    private synchronized void saveHistory(List<DownloadHistory> histories) {
        try (Writer writer = new FileWriter(historyPath.toFile())) {
            gson.toJson(histories, writer);
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    // Thêm 1 lịch sử mới
    public synchronized void addHistory(DownloadHistory history) {
        List<DownloadHistory> histories = loadHistory();
        histories.add(history);
        saveHistory(histories);
        notifyListeners();
    }

    // Xóa toàn bộ lịch sử (ghi file rỗng)
    public synchronized void clearHistory() {
        // Write empty list to the history file (keeps file present but empty)
        saveHistory(new ArrayList<>());
        notifyListeners();
    }

    // Listener API
    public void addChangeListener(Runnable listener) {
        if (listener != null) listeners.add(listener);
    }

    public void removeChangeListener(Runnable listener) {
        if (listener != null) listeners.remove(listener);
    }

    private void notifyListeners() {
        for (Runnable r : listeners) {
            try {
                r.run();
            } catch (Exception ex) {
                ex.printStackTrace();
            }
        }
    }
}
