module com.pvb.smperf {
    requires javafx.controls;
    requires javafx.fxml;
    requires com.google.gson;
    requires java.prefs;

    opens com.pvb.smperf.model to com.google.gson;
    opens com.pvb.smperf.engine to com.google.gson;

    opens com.pvb.smperf to javafx.fxml;
    opens com.pvb.smperf.ui to javafx.fxml;
    exports com.pvb.smperf;
}
