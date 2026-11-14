package com.pvb.smperf;

import com.pvb.smperf.ui.MainController;
import javafx.application.Application;
import javafx.application.Platform;
import javafx.fxml.FXMLLoader;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.layout.StackPane;
import javafx.stage.Stage;

import java.io.IOException;

public class App extends Application {

    @Override
    public void start(Stage stage) throws IOException {
        FXMLLoader loader = new FXMLLoader(App.class.getResource("ui/main.fxml"));
        Parent content = loader.load();

        // The StackPane is only a host: the first-run tour draws its dimmed layer on top of the
        // whole window (menus and tabs included) by adding a child here.
        StackPane shell = new StackPane(content);
        Scene scene = new Scene(shell, 1100, 700);
        scene.getStylesheets().clear();

        scene.getStylesheets().add(getClass().getResource("/com/pvb/smperf/ui/style.css").toExternalForm());
        stage.setTitle("SMPerf");
        stage.setScene(scene);
        stage.show();

        MainController controller = loader.getController();
        Platform.runLater(controller::maybeStartTour);     // only the first time (see GuidedTour)
    }
}
