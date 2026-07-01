package io.audienceflow.desktop;

import javafx.scene.layout.StackPane;
import javafx.scene.shape.SVGPath;

public final class IconGlyph extends StackPane {
    public enum Name {
        ALERT,
        BUILDING,
        CAMERA,
        KEY,
        LIVE,
        LOGOUT,
        MARK,
        PEOPLE,
        PLUS,
        REFRESH,
        ROOMS,
        SNAPSHOT,
        SERVER,
        SHIELD,
        STOP,
        USER
    }

    public IconGlyph(Name name, double size) {
        this(name, size, size);
    }

    public IconGlyph(Name name, double boxSize, double glyphSize) {
        getStyleClass().add("app-icon");
        setMinSize(boxSize, boxSize);
        setPrefSize(boxSize, boxSize);
        setMaxSize(boxSize, boxSize);
        setMouseTransparent(true);

        SVGPath shape = new SVGPath();
        shape.getStyleClass().add("app-icon-shape");
        shape.setContent(path(name));
        double scale = glyphSize / 24.0;
        shape.setScaleX(scale);
        shape.setScaleY(scale);
        getChildren().add(shape);
    }

    private static String path(Name name) {
        return switch (name) {
            case ALERT -> "M12 3L22 21H2L12 3ZM11 9V15H13V9H11ZM11 17V19H13V17H11Z";
            case BUILDING -> "M4 21V6L12 3L20 6V21H15V16H9V21H4ZM7 9H9V11H7V9ZM11 8H13V10H11V8ZM15 9H17V11H15V9ZM7 13H9V15H7V13ZM15 13H17V15H15V13Z";
            case CAMERA -> "M4 7H7L9 5H15L17 7H20V19H4V7ZM12 16.5A4.5 4.5 0 1 0 12 7.5A4.5 4.5 0 0 0 12 16.5ZM12 14.5A2.5 2.5 0 1 1 12 9.5A2.5 2.5 0 0 1 12 14.5Z";
            case KEY -> "M8 14A4 4 0 1 1 11.5 12H22V15H20V17H17V15H11.5A4 4 0 0 1 8 14ZM8 16A2 2 0 1 0 8 12A2 2 0 0 0 8 16Z";
            case LIVE -> "M12 14.5A2.5 2.5 0 1 0 12 9.5A2.5 2.5 0 0 0 12 14.5ZM7.8 7.8L6.4 6.4A8 8 0 0 0 6.4 17.6L7.8 16.2A6 6 0 0 1 7.8 7.8ZM16.2 7.8A6 6 0 0 1 16.2 16.2L17.6 17.6A8 8 0 0 0 17.6 6.4L16.2 7.8ZM4.6 4.6L3.2 3.2A12 12 0 0 0 3.2 20.8L4.6 19.4A10 10 0 0 1 4.6 4.6ZM19.4 4.6A10 10 0 0 1 19.4 19.4L20.8 20.8A12 12 0 0 0 20.8 3.2L19.4 4.6Z";
            case LOGOUT -> "M5 4H13V6H7V18H13V20H5V4ZM14.5 8.5L16 7L21 12L16 17L14.5 15.5L17 13H10V11H17L14.5 8.5Z";
            case MARK -> "M12 2A10 10 0 1 1 12 22A10 10 0 0 1 12 2ZM7 12.2L10.3 15.5L17.4 8.4L15.9 7L10.3 12.6L8.5 10.8L7 12.2Z";
            case PEOPLE -> "M8.5 11A3.5 3.5 0 1 0 8.5 4A3.5 3.5 0 0 0 8.5 11ZM15.5 10A3 3 0 1 0 15.5 4A3 3 0 0 0 15.5 10ZM2 20C2.4 16.5 5.1 14 8.5 14C11.9 14 14.6 16.5 15 20H2ZM13.5 13.2C17.1 13.5 19.6 16 20 20H16.8A8.8 8.8 0 0 0 13.5 13.2Z";
            case PLUS -> "M11 5H13V11H19V13H13V19H11V13H5V11H11V5Z";
            case REFRESH -> "M17.7 6.3A7.8 7.8 0 0 0 4.5 10H2.3A10 10 0 0 1 19.1 4.9L21 3V9H15L17.7 6.3ZM6.3 17.7A7.8 7.8 0 0 0 19.5 14H21.7A10 10 0 0 1 4.9 19.1L3 21V15H9L6.3 17.7Z";
            case ROOMS -> "M3 20V5L11 2V17H13V7L21 10V20H3ZM6 8H8V10H6V8ZM6 12H8V14H6V12ZM16 12H18V14H16V12ZM16 16H18V18H16V16Z";
            case SNAPSHOT -> "M5 6H8L10 4H14L16 6H19V19H5V6ZM12 16A4 4 0 1 0 12 8A4 4 0 0 0 12 16ZM12 14A2 2 0 1 1 12 10A2 2 0 0 1 12 14Z";
            case SERVER -> "M4 4H20V10H4V4ZM6 7A1 1 0 1 0 6 5A1 1 0 0 0 6 7ZM4 12H20V20H4V12ZM6 16A1 1 0 1 0 6 14A1 1 0 0 0 6 16Z";
            case SHIELD -> "M12 2L19 5V11C19 16 16.1 20 12 22C7.9 20 5 16 5 11V5L12 2ZM10.8 14.6L16.2 9.2L14.8 7.8L10.8 11.8L9.2 10.2L7.8 11.6L10.8 14.6Z";
            case STOP -> "M7 7H17V17H7V7Z";
            case USER -> "M12 12A4 4 0 1 0 12 4A4 4 0 0 0 12 12ZM4 21C4.5 16.8 7.7 14 12 14C16.3 14 19.5 16.8 20 21H4Z";
        };
    }
}
