package org.example.p2pfileshare;

import javafx.application.Application;
import javafx.fxml.FXMLLoader;
import javafx.scene.Scene;
import javafx.stage.Stage;

public class HelloApplication extends Application {

    @Override
    public void start(Stage stage) {
        try {
            FXMLLoader loader = new FXMLLoader(
                    HelloApplication.class.getResource("MainView.fxml")
            );

            Scene scene = new Scene(loader.load(), 1000, 650);

            stage.setTitle("P2P");
            stage.setScene(scene);
            stage.setMinWidth(900);
            stage.setMinHeight(600);
            stage.show();

        } catch (Exception e) {
            e.printStackTrace();
            System.out.println("❌ Không thể load file MainView.fxml");
        }
    }

    public static void main(String[] args) {
        launch();
    }
}
