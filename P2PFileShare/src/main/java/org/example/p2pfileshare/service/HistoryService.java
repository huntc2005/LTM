package org.example.p2pfileshare.service;

import org.example.p2pfileshare.model.DownloadHistory;
import org.example.p2pfileshare.util.DownloadHistoryManager;

import java.util.List;

public class HistoryService {

    private final DownloadHistoryManager manager;

    public HistoryService() {
        this.manager = new DownloadHistoryManager();
    }

    public List<DownloadHistory> listHistories() {
        return manager.loadHistory();
    }

    public void clearHistory() {
        manager.clearHistory();
    }
    public void addHistory(DownloadHistory h) {
        manager.addHistory(h);
    }
    public  List<DownloadHistory> loadHistory() {
        return manager.loadHistory();
    }
    // Listener registration forwarded to manager
    public void addHistoryChangeListener(Runnable listener) {
        manager.addChangeListener(listener);
    }

    public void removeHistoryChangeListener(Runnable listener) {
        manager.removeChangeListener(listener);
    }
}
