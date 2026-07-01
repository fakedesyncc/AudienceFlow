module io.audienceflow.desktop {
    requires com.fasterxml.jackson.annotation;
    requires com.fasterxml.jackson.core;
    requires com.fasterxml.jackson.databind;
    requires com.fasterxml.jackson.datatype.jsr310;
    requires java.net.http;
    requires javafx.controls;

    exports io.audienceflow.desktop;
    opens io.audienceflow.desktop.model to com.fasterxml.jackson.databind;
}
