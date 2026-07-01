package io.audienceflow.desktop;

import io.audienceflow.desktop.api.AudienceFlowApiClient;
import io.audienceflow.desktop.api.LiveConnection;
import io.audienceflow.desktop.api.PreviewClient;
import io.audienceflow.desktop.IconGlyph.Name;
import io.audienceflow.desktop.model.AuthSession;
import io.audienceflow.desktop.model.Camera;
import io.audienceflow.desktop.model.CameraRequest;
import io.audienceflow.desktop.model.CreateRoomRequest;
import io.audienceflow.desktop.model.CreateUserRequest;
import io.audienceflow.desktop.model.CurrentAttendance;
import io.audienceflow.desktop.model.PreviewState;
import io.audienceflow.desktop.model.Role;
import io.audienceflow.desktop.model.Room;
import io.audienceflow.desktop.model.UserView;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.function.Function;
import javafx.application.Application;
import javafx.application.Platform;
import javafx.beans.property.ReadOnlyObjectWrapper;
import javafx.beans.property.ReadOnlyStringWrapper;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.ListView;
import javafx.scene.control.PasswordField;
import javafx.scene.control.ProgressBar;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.SelectionMode;
import javafx.scene.control.SplitPane;
import javafx.scene.control.Tab;
import javafx.scene.control.TabPane;
import javafx.scene.control.TableCell;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableRow;
import javafx.scene.control.TableView;
import javafx.scene.control.TextField;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.stage.Stage;
import javafx.util.StringConverter;

public final class AudienceFlowDesktopApplication extends Application {
    private static final DateTimeFormatter TIME_FORMAT = DateTimeFormatter.ofPattern("HH:mm:ss")
            .withZone(ZoneId.systemDefault());
    private static final DateTimeFormatter DATE_TIME_FORMAT = DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm:ss")
            .withZone(ZoneId.systemDefault());

    private final AudienceFlowApiClient apiClient = new AudienceFlowApiClient();
    private final ObservableList<CurrentAttendance> currentRows = FXCollections.observableArrayList();
    private final ObservableList<Room> roomRows = FXCollections.observableArrayList();
    private final ObservableList<Camera> cameraRows = FXCollections.observableArrayList();
    private final ObservableList<UserView> userRows = FXCollections.observableArrayList();
    private final ObservableList<String> eventFeed = FXCollections.observableArrayList();

    private Stage stage;
    private AuthSession session;
    private ScheduledExecutorService refreshExecutor;
    private ScheduledExecutorService previewExecutor;
    private LiveConnection liveConnection;
    private PreviewClient previewClient;
    private byte[] latestPreviewFrame;
    private double previewZoom = 1.0;
    private long lastPreviewErrorAt;

    private Label liveStateLabel;
    private Label snapshotLabel;
    private Label statusLine;
    private Label userLabel;
    private Label roomsMetric;
    private Label peopleMetric;
    private Label attentionMetric;
    private Label camerasMetric;
    private TableView<CurrentAttendance> monitoringTable;
    private Label inspectorTitle;
    private Label inspectorMeta;
    private Label inspectorCount;
    private Label inspectorPercent;
    private Label inspectorConfidence;
    private Label inspectorCamera;
    private Label inspectorUpdated;
    private ProgressBar inspectorProgress;
    private ImageView previewImageView;
    private Label previewStatusLabel;
    private Label previewCountLabel;
    private Label previewConfidenceLabel;
    private Label previewFpsLabel;
    private Label previewMetaLabel;

    public static void main(String[] args) {
        launch(args);
    }

    @Override
    public void start(Stage primaryStage) {
        stage = primaryStage;
        stage.setTitle("AudienceFlow");
        stage.setMinWidth(1080);
        stage.setMinHeight(720);
        showLogin();
    }

    @Override
    public void stop() {
        stopBackgroundWork();
    }

    private void showLogin() {
        stopBackgroundWork();
        apiClient.clearSession();
        session = null;

        TextField apiUrl = new TextField("http://localhost:8080/api");
        TextField email = new TextField();
        PasswordField password = new PasswordField();
        Label error = new Label();
        Button submit = new Button("Подключиться");

        apiUrl.setPromptText("https://api.example.edu/api");
        email.setPromptText("user@example.edu");
        password.setPromptText("Пароль");
        error.getStyleClass().add("login-error");
        submit.getStyleClass().add("primary-button");
        submit.setGraphic(icon(Name.KEY, "on-primary"));

        GridPane form = new GridPane();
        form.getStyleClass().add("login-form");
        form.setHgap(12);
        form.setVgap(12);
        form.add(new Label("API URL"), 0, 0);
        form.add(apiUrl, 1, 0);
        form.add(new Label("Email"), 0, 1);
        form.add(email, 1, 1);
        form.add(new Label("Пароль"), 0, 2);
        form.add(password, 1, 2);
        form.add(error, 1, 3);
        form.add(submit, 1, 4);

        submit.setDefaultButton(true);

        HBox showcaseBrand = new HBox(14, icon(Name.MARK, 38, 24, "brand-icon", "on-dark"), label("AudienceFlow", "showcase-title"));
        showcaseBrand.setAlignment(Pos.CENTER_LEFT);

        VBox showcase = new VBox(22);
        showcase.getStyleClass().add("login-showcase");
        showcase.getChildren().addAll(
                label("ЛГТУ / Институт компьютерных наук", "showcase-eyebrow"),
                showcaseBrand,
                label("Desktop-клиент для живого контроля аудиторий, камер и загрузки учебных пространств.", "showcase-copy"),
                new HBox(10, chip("LIVE", "chip-live"), chip("WebSocket", "chip-muted"), chip("Java + Go", "chip-muted")),
                loginSignalPanel()
        );

        VBox formCard = new VBox(22);
        formCard.getStyleClass().add("login-card");
        formCard.getChildren().addAll(
                label("Защищённый вход", "login-title"),
                label("Введите адрес API и свою учётную запись. Пароль не сохраняется в приложении.", "login-copy"),
                form
        );

        HBox shell = new HBox(showcase, formCard);
        shell.getStyleClass().add("login-shell");
        StackPane root = new StackPane(shell);
        root.getStyleClass().add("login-root");

        submit.setOnAction(event -> {
            String passwordValue = password.getText();
            password.clear();
            submit.setDisable(true);
            error.setText("");
            apiClient.login(apiUrl.getText(), email.getText(), passwordValue)
                    .whenComplete((authSession, failure) -> Platform.runLater(() -> {
                        submit.setDisable(false);
                        if (failure != null) {
                            error.setText(userMessage(failure));
                            return;
                        }
                        showMain(authSession);
                    }));
        });

        Scene scene = new Scene(root, 1180, 760);
        attachStyles(scene);
        stage.setScene(scene);
        stage.show();
    }

    private void showMain(AuthSession authSession) {
        session = authSession;

        BorderPane root = new BorderPane();
        root.getStyleClass().add("app-root");
        root.setLeft(buildSidebar());
        root.setTop(buildHeader());
        root.setCenter(buildTabs());

        Scene scene = new Scene(root, 1280, 820);
        attachStyles(scene);
        stage.setScene(scene);
        stage.setTitle("AudienceFlow - " + authSession.user().displayName());

        refreshData(true);
        openLiveConnection();
        startPolling();
    }

    private Node buildSidebar() {
        HBox brand = new HBox(12, icon(Name.MARK, 34, 22, "brand-icon"), label("AudienceFlow", "sidebar-title"));
        brand.setAlignment(Pos.CENTER_LEFT);
        Label caption = label("ЛГТУ · операторский контур", "sidebar-caption");
        userLabel = label("", "sidebar-user");
        liveStateLabel = label("Подключение", "live-pill");
        snapshotLabel = label("Снимок ещё не получен", "sidebar-caption");

        Button logout = new Button("Выйти");
        logout.getStyleClass().add("secondary-button");
        logout.setGraphic(icon(Name.LOGOUT, "button-icon"));
        logout.setMaxWidth(Double.MAX_VALUE);
        logout.setOnAction(event -> showLogin());

        VBox campus = new VBox(8,
                label("Контур", "sidebar-section"),
                label("Аудитории · камеры · live-события", "sidebar-caption")
        );
        campus.getStyleClass().add("sidebar-card");

        VBox sidebar = new VBox(18, brand, caption, separator(), campus, userLabel, liveStateLabel, snapshotLabel, spacer(), logout);
        sidebar.getStyleClass().add("sidebar");
        sidebar.setPrefWidth(270);
        VBox.setVgrow(sidebar.getChildren().get(7), Priority.ALWAYS);
        updateUserCaption();
        return sidebar;
    }

    private Node buildHeader() {
        Label title = label("Мониторинг посещаемости в реальном времени", "page-title");
        statusLine = label("Готово к загрузке данных", "page-status");
        Button refresh = new Button("Обновить");
        refresh.getStyleClass().add("secondary-button");
        refresh.setGraphic(icon(Name.REFRESH, "button-icon"));
        refresh.setOnAction(event -> refreshData(true));

        VBox titles = new VBox(4, title, statusLine);
        HBox header = new HBox(16, titles, spacer(), refresh);
        header.getStyleClass().add("header");
        header.setAlignment(Pos.CENTER_LEFT);
        HBox.setHgrow(titles, Priority.ALWAYS);
        return header;
    }

    private Node buildTabs() {
        TabPane tabs = new TabPane();
        tabs.getStyleClass().add("workspace-tabs");
        tabs.getTabs().add(tab("Камера", buildLiveCameraView(), Name.CAMERA));
        tabs.getTabs().add(tab("Оперативно", buildMonitoringView(), Name.LIVE));
        tabs.getTabs().add(tab("Аудитории", buildRoomsView(), Name.ROOMS));
        tabs.getTabs().add(tab("Источники", buildCamerasView(), Name.SERVER));
        if (isAdmin()) {
            tabs.getTabs().add(tab("Доступ", buildUsersView(), Name.SHIELD));
        }
        return tabs;
    }

    private Node buildLiveCameraView() {
        TextField previewUrl = new TextField("http://localhost:8090");
        PasswordField previewToken = new PasswordField();
        Button connect = new Button("Подключить");
        Button stop = new Button("Остановить");
        Button zoomOut = new Button("-25%");
        Button zoomReset = new Button("100%");
        Button zoomIn = new Button("+25%");
        Button snapshot = new Button("Снимок");

        previewUrl.setPromptText("http://localhost:8090");
        previewToken.setPromptText("Preview token, если задан");
        connect.getStyleClass().add("primary-button");
        connect.setGraphic(icon(Name.LIVE, "on-primary"));
        stop.getStyleClass().add("secondary-button");
        stop.setGraphic(icon(Name.STOP, "button-icon"));
        snapshot.getStyleClass().add("secondary-button");
        snapshot.setGraphic(icon(Name.SNAPSHOT, "button-icon"));
        zoomOut.getStyleClass().add("secondary-button");
        zoomReset.getStyleClass().add("secondary-button");
        zoomIn.getStyleClass().add("secondary-button");

        previewImageView = new ImageView();
        previewImageView.setPreserveRatio(true);
        previewImageView.setSmooth(true);
        previewImageView.setFitWidth(900);

        Label empty = label("Подключите vision-worker preview, чтобы увидеть live-видео с рамками детекции.", "video-empty");
        StackPane videoStage = new StackPane(empty, previewImageView);
        videoStage.getStyleClass().add("video-stage");
        ScrollPane videoScroll = new ScrollPane(videoStage);
        videoScroll.getStyleClass().add("video-scroll");
        videoScroll.setFitToWidth(true);
        videoScroll.setFitToHeight(true);

        previewStatusLabel = label("Preview не подключён", "preview-status");
        previewCountLabel = label("0", "camera-value");
        previewConfidenceLabel = label("0%", "camera-value");
        previewFpsLabel = label("0.0", "camera-value");
        previewMetaLabel = label("Источник не выбран", "muted");

        VBox controls = new VBox(14,
                label("Live камера", "panel-title"),
                field("Preview URL", previewUrl),
                field("Токен preview", previewToken),
                new HBox(10, connect, stop),
                separator(),
                cameraStat("Людей в кадре", previewCountLabel),
                cameraStat("Достоверность", previewConfidenceLabel),
                cameraStat("FPS preview", previewFpsLabel),
                previewMetaLabel,
                separator(),
                label("Масштаб", "field-label"),
                new HBox(8, zoomOut, zoomReset, zoomIn),
                snapshot
        );
        controls.getStyleClass().addAll("panel", "camera-controls");

        connect.setOnAction(event -> startPreview(previewUrl.getText(), previewToken.getText()));
        stop.setOnAction(event -> stopPreview("Preview остановлен"));
        zoomOut.setOnAction(event -> setPreviewZoom(Math.max(0.5, previewZoom - 0.25)));
        zoomReset.setOnAction(event -> setPreviewZoom(1.0));
        zoomIn.setOnAction(event -> setPreviewZoom(Math.min(3.0, previewZoom + 0.25)));
        snapshot.setOnAction(event -> savePreviewSnapshot());

        BorderPane videoPanel = new BorderPane(videoScroll);
        videoPanel.getStyleClass().add("panel");
        videoPanel.setTop(new HBox(12, icon(Name.CAMERA, 34, 18, "metric-icon"), previewStatusLabel));
        BorderPane.setAlignment(videoPanel.getTop(), Pos.CENTER_LEFT);

        BorderPane view = new BorderPane(videoPanel);
        view.getStyleClass().add("content");
        BorderPane.setMargin(controls, new Insets(0, 0, 0, 16));
        view.setRight(controls);
        return view;
    }

    private Node buildMonitoringView() {
        roomsMetric = label("0", "metric-value");
        peopleMetric = label("0", "metric-value");
        attentionMetric = label("0", "metric-value");
        camerasMetric = label("0", "metric-value");

        HBox metrics = new HBox(14,
                metricCard("Аудиторий", roomsMetric, "в активном контуре", Name.ROOMS, "teal"),
                metricCard("Людей сейчас", peopleMetric, "по последнему снимку", Name.PEOPLE, "blue"),
                metricCard("Требуют внимания", attentionMetric, "высокая загрузка", Name.ALERT, "amber"),
                metricCard("Камер онлайн", camerasMetric, "доступные источники", Name.CAMERA, "violet")
        );
        metrics.getStyleClass().add("metrics-row");

        monitoringTable = new TableView<>(currentRows);
        monitoringTable.getStyleClass().add("data-table");
        monitoringTable.getSelectionModel().setSelectionMode(SelectionMode.SINGLE);
        monitoringTable.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY);
        monitoringTable.getColumns().add(textColumn("Аудитория", CurrentAttendance::roomName, 180));
        monitoringTable.getColumns().add(textColumn("Корпус", item -> item.building() + ", этаж " + item.floor(), 170));
        monitoringTable.getColumns().add(textColumn("Люди", item -> item.count() + "/" + item.capacity(), 90));
        monitoringTable.getColumns().add(occupancyColumn());
        monitoringTable.getColumns().add(statusColumn());
        monitoringTable.setRowFactory(table -> new TableRow<>() {
            @Override
            protected void updateItem(CurrentAttendance item, boolean empty) {
                super.updateItem(item, empty);
                getStyleClass().removeAll("row-normal", "row-warning", "row-full");
                if (!empty && item != null) {
                    getStyleClass().add("row-" + item.status());
                }
            }
        });
        monitoringTable.getSelectionModel().selectedItemProperty().addListener((observable, oldValue, newValue) -> updateInspector(newValue));

        VBox tablePanel = panel("Аудиторный фонд", monitoringTable);
        inspectorProgress = new ProgressBar(0);
        inspectorProgress.setMaxWidth(Double.MAX_VALUE);
        inspectorTitle = label("Аудитория не выбрана", "inspector-title");
        inspectorMeta = label("Дождитесь первого снимка", "muted");
        inspectorCount = label("0 / 0", "inspector-count");
        inspectorPercent = label("0%", "inspector-kpi");
        inspectorConfidence = label("0%", "muted");
        inspectorCamera = label("Камера не назначена", "muted");
        inspectorUpdated = label("Нет данных", "muted");

        GridPane details = new GridPane();
        details.getStyleClass().add("details-grid");
        details.setHgap(12);
        details.setVgap(10);
        details.add(new Label("Заполненность"), 0, 0);
        details.add(inspectorPercent, 1, 0);
        details.add(new Label("Достоверность"), 0, 1);
        details.add(inspectorConfidence, 1, 1);
        details.add(new Label("Камера"), 0, 2);
        details.add(inspectorCamera, 1, 2);
        details.add(new Label("Обновлено"), 0, 3);
        details.add(inspectorUpdated, 1, 3);

        VBox inspector = new VBox(14, inspectorTitle, inspectorMeta, inspectorCount, inspectorProgress, details);
        inspector.getStyleClass().addAll("panel", "inspector-panel");
        inspector.setMinWidth(320);

        SplitPane split = new SplitPane(tablePanel, inspector);
        split.setDividerPositions(0.68);

        ListView<String> feed = new ListView<>(eventFeed);
        feed.getStyleClass().add("event-feed");
        VBox feedPanel = panel("Лента событий", feed);
        feedPanel.setMaxHeight(170);

        VBox content = new VBox(16, metrics, split, feedPanel);
        content.getStyleClass().add("content");
        VBox.setVgrow(split, Priority.ALWAYS);
        VBox.setVgrow(feedPanel, Priority.NEVER);
        return content;
    }

    private Node buildRoomsView() {
        TableView<Room> table = new TableView<>(roomRows);
        table.getStyleClass().add("data-table");
        table.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY);
        table.getColumns().add(textColumn("Название", Room::name, 180));
        table.getColumns().add(textColumn("Корпус", Room::building, 160));
        table.getColumns().add(textColumn("Этаж", Room::floor, 80));
        table.getColumns().add(numberColumn("Вместимость", Room::capacity, "", 110));

        BorderPane view = new BorderPane(panel("Реестр аудиторий", table));
        view.getStyleClass().add("content");
        if (canManageInfrastructure()) {
            Node form = buildRoomForm();
            BorderPane.setMargin(form, new Insets(0, 0, 0, 16));
            view.setRight(form);
        }
        return view;
    }

    private Node buildRoomForm() {
        TextField name = new TextField();
        TextField building = new TextField();
        TextField floor = new TextField();
        TextField capacity = new TextField();
        Button submit = new Button("Добавить аудиторию");
        submit.getStyleClass().add("primary-button");
        submit.setGraphic(icon(Name.PLUS, "on-primary"));

        name.setPromptText("Аудитория 305");
        building.setPromptText("Главный корпус");
        floor.setPromptText("3");
        capacity.setPromptText("64");

        submit.setOnAction(event -> {
            int parsedCapacity;
            try {
                parsedCapacity = Integer.parseInt(capacity.getText().trim());
            } catch (NumberFormatException e) {
                showStatus("Вместимость должна быть числом", true);
                return;
            }
            CreateRoomRequest payload = new CreateRoomRequest(
                    name.getText().trim(),
                    building.getText().trim(),
                    floor.getText().trim(),
                    parsedCapacity
            );
            execute(apiClient.createRoom(payload), created -> {
                name.clear();
                building.clear();
                floor.clear();
                capacity.clear();
                refreshData(true);
            }, "Аудитория добавлена");
        });

        return formPanel("Новая аудитория", new Node[]{
                field("Название", name),
                field("Корпус", building),
                field("Этаж", floor),
                field("Вместимость", capacity),
                submit
        });
    }

    private Node buildCamerasView() {
        TableView<Camera> table = new TableView<>(cameraRows);
        table.getStyleClass().add("data-table");
        table.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY);
        table.getColumns().add(textColumn("Название", Camera::name, 170));
        table.getColumns().add(textColumn("Аудитория", Camera::roomName, 160));
        table.getColumns().add(textColumn("Тип", item -> streamLabel(item.streamType()), 90));
        table.getColumns().add(textColumn("Статус", item -> cameraStatusLabel(item.status()), 100));
        table.getColumns().add(textColumn("Источник", item -> item.sourceUrl() == null ? "скрыт ролью" : item.sourceUrl(), 220));

        BorderPane view = new BorderPane(panel("Камеры и источники", table));
        view.getStyleClass().add("content");
        if (canManageInfrastructure()) {
            Node form = buildCameraForm();
            BorderPane.setMargin(form, new Insets(0, 0, 0, 16));
            view.setRight(form);
        }
        return view;
    }

    private Node buildCameraForm() {
        ComboBox<Room> room = new ComboBox<>(roomRows);
        TextField name = new TextField();
        TextField source = new TextField();
        ComboBox<String> streamType = new ComboBox<>(FXCollections.observableArrayList("rtsp", "http", "device", "simulation"));
        ComboBox<String> status = new ComboBox<>(FXCollections.observableArrayList("online", "offline", "maintenance"));
        CheckBox enabled = new CheckBox("Включена");
        Button submit = new Button("Подключить камеру");
        submit.setGraphic(icon(Name.CAMERA, "on-primary"));

        room.setConverter(new StringConverter<>() {
            @Override
            public String toString(Room value) {
                return value == null ? "" : value.name();
            }

            @Override
            public Room fromString(String value) {
                return null;
            }
        });
        streamType.getSelectionModel().select("rtsp");
        status.getSelectionModel().select("offline");
        enabled.setSelected(true);
        name.setPromptText("Камера входной зоны");
        source.setPromptText("rtsp://camera-host/live");
        submit.getStyleClass().add("primary-button");

        submit.setOnAction(event -> {
            Room selectedRoom = room.getSelectionModel().getSelectedItem();
            if (selectedRoom == null) {
                showStatus("Выберите аудиторию для камеры", true);
                return;
            }
            CameraRequest payload = new CameraRequest(
                    selectedRoom.id(),
                    name.getText().trim(),
                    source.getText().trim(),
                    streamType.getValue(),
                    status.getValue(),
                    enabled.isSelected()
            );
            execute(apiClient.createCamera(payload), created -> {
                name.clear();
                source.clear();
                status.getSelectionModel().select("offline");
                refreshData(true);
            }, "Камера добавлена");
        });

        return formPanel("Новая камера", new Node[]{
                field("Аудитория", room),
                field("Название", name),
                field("Источник", source),
                field("Тип потока", streamType),
                field("Статус", status),
                enabled,
                submit
        });
    }

    private Node buildUsersView() {
        TableView<UserView> table = new TableView<>(userRows);
        table.getStyleClass().add("data-table");
        table.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY);
        table.getColumns().add(textColumn("Email", UserView::email, 210));
        table.getColumns().add(textColumn("Имя", UserView::displayName, 180));
        table.getColumns().add(textColumn("Роль", item -> roleLabel(item.role()), 120));
        table.getColumns().add(textColumn("Статус", item -> item.active() ? "Активен" : "Отключён", 110));

        TextField email = new TextField();
        TextField displayName = new TextField();
        ComboBox<Role> role = new ComboBox<>(FXCollections.observableArrayList(Role.TEACHER, Role.TECHNICIAN, Role.ADMIN));
        PasswordField password = new PasswordField();
        Button submit = new Button("Создать пользователя");
        submit.setGraphic(icon(Name.USER, "on-primary"));

        role.getSelectionModel().select(Role.TEACHER);
        email.setPromptText("name@example.edu");
        displayName.setPromptText("Фамилия Имя");
        password.setPromptText("Минимум 12 символов");
        submit.getStyleClass().add("primary-button");
        submit.setOnAction(event -> {
            String passwordValue = password.getText();
            password.clear();
            CreateUserRequest payload = new CreateUserRequest(
                    email.getText().trim(),
                    displayName.getText().trim(),
                    role.getValue(),
                    passwordValue
            );
            execute(apiClient.createUser(payload), created -> {
                email.clear();
                displayName.clear();
                role.getSelectionModel().select(Role.TEACHER);
                refreshData(true);
            }, "Пользователь создан");
        });

        BorderPane view = new BorderPane(panel("Пользователи", table));
        view.getStyleClass().add("content");
        Node form = formPanel("Новый пользователь", new Node[]{
                field("Email", email),
                field("Имя", displayName),
                field("Роль", role),
                field("Пароль", password),
                submit
        });
        BorderPane.setMargin(form, new Insets(0, 0, 0, 16));
        view.setRight(form);
        return view;
    }

    private void refreshData(boolean manual) {
        if (session == null) {
            return;
        }
        if (manual) {
            showStatus("Запрашиваю свежий снимок", false);
        }

        CompletableFuture<List<CurrentAttendance>> currentFuture = apiClient.current();
        CompletableFuture<List<Room>> roomsFuture = apiClient.rooms();
        CompletableFuture<List<Camera>> camerasFuture = apiClient.cameras();
        CompletableFuture<List<UserView>> usersFuture = isAdmin()
                ? apiClient.users()
                : CompletableFuture.completedFuture(List.of());

        CompletableFuture.allOf(currentFuture, roomsFuture, camerasFuture, usersFuture)
                .whenComplete((ignored, failure) -> Platform.runLater(() -> {
                    if (failure != null) {
                        updateLiveState("polling");
                        showStatus(userMessage(failure), true);
                        return;
                    }
                    roomRows.setAll(roomsFuture.join());
                    cameraRows.setAll(camerasFuture.join());
                    userRows.setAll(usersFuture.join());
                    applySnapshot(currentFuture.join(), manual ? "REST" : "polling");
                    showStatus("Данные обновлены", false);
                }));
    }

    private void openLiveConnection() {
        apiClient.openLive(
                snapshot -> Platform.runLater(() -> applySnapshot(snapshot, "live")),
                state -> Platform.runLater(() -> updateLiveState(state))
        ).whenComplete((connection, failure) -> Platform.runLater(() -> {
            if (failure != null) {
                updateLiveState("polling");
                appendFeed("Live-канал недоступен, используется polling");
                return;
            }
            liveConnection = connection;
            updateLiveState("live");
        }));
    }

    private void startPolling() {
        refreshExecutor = Executors.newSingleThreadScheduledExecutor(task -> {
            Thread thread = new Thread(task, "audienceflow-refresh");
            thread.setDaemon(true);
            return thread;
        });
        refreshExecutor.scheduleWithFixedDelay(() -> refreshData(false), 5, 5, TimeUnit.SECONDS);
    }

    private void stopBackgroundWork() {
        if (liveConnection != null) {
            liveConnection.close();
            liveConnection = null;
        }
        stopPreview(null);
        if (refreshExecutor != null) {
            refreshExecutor.shutdownNow();
            refreshExecutor = null;
        }
    }

    private void startPreview(String url, String token) {
        stopPreview(null);
        try {
            previewClient = new PreviewClient(url, token);
        } catch (RuntimeException e) {
            updatePreviewStatus(userMessage(e), true);
            return;
        }

        lastPreviewErrorAt = 0;
        updatePreviewStatus("Подключаюсь к preview worker", false);
        previewExecutor = Executors.newSingleThreadScheduledExecutor(task -> {
            Thread thread = new Thread(task, "audienceflow-preview");
            thread.setDaemon(true);
            return thread;
        });
        previewExecutor.scheduleWithFixedDelay(this::fetchPreviewFrame, 0, 180, TimeUnit.MILLISECONDS);
    }

    private void fetchPreviewFrame() {
        PreviewClient client = previewClient;
        if (client == null) {
            return;
        }
        try {
            byte[] frame = client.frame().join();
            PreviewState state = client.state().join();
            Platform.runLater(() -> applyPreviewFrame(frame, state));
        } catch (RuntimeException e) {
            Platform.runLater(() -> reportPreviewError(userMessage(e)));
        }
    }

    private void applyPreviewFrame(byte[] frame, PreviewState state) {
        latestPreviewFrame = frame;
        if (previewImageView != null) {
            previewImageView.setImage(new Image(new ByteArrayInputStream(frame)));
        }
        setText(previewCountLabel, String.valueOf(state.count()));
        setText(previewConfidenceLabel, Math.round(state.confidence() * 100) + "%");
        setText(previewFpsLabel, String.format("%.1f", state.fps()));
        setText(
                previewMetaLabel,
                "room_id " + state.roomId()
                        + " · " + nullSafe(state.detector())
                        + " · " + nullSafe(state.source())
                        + " · boxes " + (state.detections() == null ? 0 : state.detections().size())
        );
        setPreviewStatusText("LIVE preview · " + TIME_FORMAT.format(Instant.now()), false);
    }

    private void stopPreview(String message) {
        previewClient = null;
        if (previewExecutor != null) {
            previewExecutor.shutdownNow();
            previewExecutor = null;
        }
        if (message != null) {
            updatePreviewStatus(message, false);
        }
    }

    private void setPreviewZoom(double value) {
        previewZoom = value;
        if (previewImageView != null) {
            previewImageView.setScaleX(value);
            previewImageView.setScaleY(value);
        }
        updatePreviewStatus("Масштаб " + Math.round(value * 100) + "%", false);
    }

    private void savePreviewSnapshot() {
        if (latestPreviewFrame == null || latestPreviewFrame.length == 0) {
            updatePreviewStatus("Нет кадра для снимка", true);
            return;
        }
        Path directory = Path.of(System.getProperty("user.home"), "Pictures", "AudienceFlow");
        Path target = directory.resolve("audienceflow-" + DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")
                .withZone(ZoneId.systemDefault())
                .format(Instant.now()) + ".jpg");
        try {
            Files.createDirectories(directory);
            Files.write(target, latestPreviewFrame);
            updatePreviewStatus("Снимок сохранён: " + target, false);
        } catch (IOException e) {
            updatePreviewStatus("Не удалось сохранить снимок: " + e.getMessage(), true);
        }
    }

    private void applySnapshot(List<CurrentAttendance> snapshot, String source) {
        int selectedId = monitoringTable == null || monitoringTable.getSelectionModel().getSelectedItem() == null
                ? -1
                : monitoringTable.getSelectionModel().getSelectedItem().roomId();
        currentRows.setAll(snapshot);
        updateMetrics();
        updateLiveState(source.equals("live") ? "live" : "polling");
        if (snapshotLabel != null) {
            snapshotLabel.setText("Снимок: " + TIME_FORMAT.format(Instant.now()));
        }
        appendFeed(sourceLabel(source) + ": обновлено аудиторий " + snapshot.size());

        if (monitoringTable != null && !snapshot.isEmpty()) {
            CurrentAttendance selected = snapshot.stream()
                    .filter(item -> item.roomId() == selectedId)
                    .findFirst()
                    .orElse(snapshot.get(0));
            monitoringTable.getSelectionModel().select(selected);
            updateInspector(selected);
        } else {
            updateInspector(null);
        }
    }

    private void updateMetrics() {
        int people = currentRows.stream().mapToInt(CurrentAttendance::count).sum();
        long attention = currentRows.stream().filter(item -> !"normal".equals(item.status())).count();
        long onlineCameras = cameraRows.stream().filter(item -> "online".equals(item.status())).count();

        setText(roomsMetric, String.valueOf(currentRows.size()));
        setText(peopleMetric, String.valueOf(people));
        setText(attentionMetric, String.valueOf(attention));
        setText(camerasMetric, String.valueOf(onlineCameras));
    }

    private void updateInspector(CurrentAttendance item) {
        if (item == null) {
            setText(inspectorTitle, "Аудитория не выбрана");
            setText(inspectorMeta, "Нет активного снимка");
            setText(inspectorCount, "0 / 0");
            setText(inspectorPercent, "0%");
            setText(inspectorConfidence, "0%");
            setText(inspectorCamera, "Камера не назначена");
            setText(inspectorUpdated, "Нет данных");
            if (inspectorProgress != null) {
                inspectorProgress.setProgress(0);
            }
            return;
        }

        Camera camera = cameraRows.stream()
                .filter(candidate -> candidate.roomId() == item.roomId())
                .findFirst()
                .orElse(null);
        setText(inspectorTitle, item.roomName());
        setText(inspectorMeta, item.building() + ", этаж " + item.floor());
        setText(inspectorCount, item.count() + " / " + item.capacity());
        setText(inspectorPercent, item.occupancyPercent() + "%");
        setText(inspectorConfidence, Math.round(item.confidence() * 100) + "%");
        setText(inspectorCamera, camera == null ? "Камера не назначена" : camera.name() + " - " + cameraStatusLabel(camera.status()));
        setText(inspectorUpdated, item.timestamp() == null ? "Нет данных" : DATE_TIME_FORMAT.format(item.timestamp()));
        if (inspectorProgress != null) {
            inspectorProgress.setProgress(Math.min(1, item.occupancyPercent() / 100.0));
        }
    }

    private <T> void execute(CompletableFuture<T> operation, Consumer<T> onSuccess, String successMessage) {
        operation.whenComplete((result, failure) -> Platform.runLater(() -> {
            if (failure != null) {
                showStatus(userMessage(failure), true);
                return;
            }
            onSuccess.accept(result);
            showStatus(successMessage, false);
        }));
    }

    private void updateLiveState(String state) {
        if (liveStateLabel == null) {
            return;
        }
        liveStateLabel.getStyleClass().removeAll("live", "polling", "offline");
        if ("live".equals(state)) {
            liveStateLabel.setText("LIVE WebSocket");
            liveStateLabel.setGraphic(icon(Name.LIVE, "on-dark"));
            liveStateLabel.getStyleClass().add("live");
        } else if ("polling".equals(state)) {
            liveStateLabel.setText("Polling fallback");
            liveStateLabel.setGraphic(icon(Name.REFRESH, "on-dark"));
            liveStateLabel.getStyleClass().add("polling");
        } else {
            liveStateLabel.setText("Офлайн");
            liveStateLabel.setGraphic(icon(Name.ALERT, "on-dark"));
            liveStateLabel.getStyleClass().add("offline");
        }
    }

    private void updateUserCaption() {
        if (session == null || userLabel == null) {
            return;
        }
        userLabel.setText(session.user().displayName() + "\n" + roleLabel(session.user().role()));
    }

    private boolean canManageInfrastructure() {
        return session != null && (session.user().role() == Role.ADMIN || session.user().role() == Role.TECHNICIAN);
    }

    private boolean isAdmin() {
        return session != null && session.user().role() == Role.ADMIN;
    }

    private Tab tab(String title, Node content, Name iconName) {
        Tab tab = new Tab(title, content);
        tab.setGraphic(icon(iconName, "tab-icon"));
        tab.setClosable(false);
        return tab;
    }

    private VBox panel(String title, Node content) {
        VBox panel = new VBox(12, label(title, "panel-title"), content);
        panel.getStyleClass().add("panel");
        VBox.setVgrow(content, Priority.ALWAYS);
        return panel;
    }

    private VBox formPanel(String title, Node[] fields) {
        VBox panel = new VBox(12);
        panel.getStyleClass().addAll("panel", "form-panel");
        panel.getChildren().add(label(title, "panel-title"));
        panel.getChildren().addAll(fields);
        return panel;
    }

    private Node field(String title, Node control) {
        Label label = new Label(title);
        label.getStyleClass().add("field-label");
        VBox box = new VBox(6, label, control);
        box.getStyleClass().add("field");
        return box;
    }

    private Node metricCard(String title, Label value, String note, Name iconName, String accent) {
        HBox header = new HBox(10, icon(iconName, 34, 18, "metric-icon"), label(title, "metric-title"));
        header.setAlignment(Pos.CENTER_LEFT);
        VBox card = new VBox(8, header, value, label(note, "metric-note"));
        card.getStyleClass().addAll("metric-card", "metric-" + accent);
        HBox.setHgrow(card, Priority.ALWAYS);
        card.setMaxWidth(Double.MAX_VALUE);
        return card;
    }

    private Node cameraStat(String title, Label value) {
        VBox stat = new VBox(4, label(title, "metric-title"), value);
        stat.getStyleClass().add("camera-stat");
        return stat;
    }

    private Node loginSignalPanel() {
        VBox panel = new VBox(14);
        panel.getStyleClass().add("signal-panel");
        panel.getChildren().addAll(
                new HBox(10, icon(Name.SERVER, "on-dark"), label("Срез состояния", "signal-title")),
                signalRow("305", "Норма", "42 / 64", "normal"),
                signalRow("101", "Высокая", "98 / 120", "warning"),
                signalRow("Б-204", "Сервис камеры", "0 / 90", "service")
        );
        return panel;
    }

    private Node signalRow(String room, String state, String count, String style) {
        Label roomLabel = label(room, "signal-room");
        Label stateLabel = label(state, "signal-state");
        stateLabel.getStyleClass().add("signal-" + style);
        Label countLabel = label(count, "signal-count");
        HBox row = new HBox(12, roomLabel, stateLabel, spacer(), countLabel);
        row.getStyleClass().add("signal-row");
        row.setAlignment(Pos.CENTER_LEFT);
        return row;
    }

    private Label chip(String text, String style) {
        Label chip = label(text, "chip");
        chip.getStyleClass().add(style);
        return chip;
    }

    private IconGlyph icon(Name name, String... styles) {
        return icon(name, 20, styles);
    }

    private IconGlyph icon(Name name, double size, String... styles) {
        IconGlyph glyph = new IconGlyph(name, size);
        glyph.getStyleClass().addAll(styles);
        return glyph;
    }

    private IconGlyph icon(Name name, double boxSize, double glyphSize, String... styles) {
        IconGlyph glyph = new IconGlyph(name, boxSize, glyphSize);
        glyph.getStyleClass().addAll(styles);
        return glyph;
    }

    private TableColumn<CurrentAttendance, String> statusColumn() {
        TableColumn<CurrentAttendance, String> column = textColumn("Сигнал", item -> statusLabel(item.status()), 120);
        column.setCellFactory(ignored -> new TableCell<>() {
            @Override
            protected void updateItem(String item, boolean empty) {
                super.updateItem(item, empty);
                setText(empty ? null : item);
                getStyleClass().removeAll("status-normal", "status-warning", "status-full");
                if (!empty && getTableRow() != null && getTableRow().getItem() instanceof CurrentAttendance row) {
                    getStyleClass().add("status-" + row.status());
                }
            }
        });
        return column;
    }

    private TableColumn<CurrentAttendance, CurrentAttendance> occupancyColumn() {
        TableColumn<CurrentAttendance, CurrentAttendance> column = new TableColumn<>("Загрузка");
        column.setCellValueFactory(data -> new ReadOnlyObjectWrapper<>(data.getValue()));
        column.setPrefWidth(150);
        column.setCellFactory(ignored -> new TableCell<>() {
            @Override
            protected void updateItem(CurrentAttendance item, boolean empty) {
                super.updateItem(item, empty);
                if (empty || item == null) {
                    setGraphic(null);
                    return;
                }
                ProgressBar progress = new ProgressBar(Math.min(1, item.occupancyPercent() / 100.0));
                progress.getStyleClass().addAll("table-progress", "progress-" + item.status());
                progress.setMaxWidth(Double.MAX_VALUE);
                Label percent = label(item.occupancyPercent() + "%", "table-percent");
                HBox wrap = new HBox(10, progress, percent);
                wrap.setAlignment(Pos.CENTER_LEFT);
                HBox.setHgrow(progress, Priority.ALWAYS);
                setGraphic(wrap);
            }
        });
        return column;
    }

    private <T> TableColumn<T, String> textColumn(String title, Function<T, String> mapper, double width) {
        TableColumn<T, String> column = new TableColumn<>(title);
        column.setCellValueFactory(data -> new ReadOnlyStringWrapper(mapper.apply(data.getValue())));
        column.setPrefWidth(width);
        return column;
    }

    private <T> TableColumn<T, Number> numberColumn(String title, Function<T, Number> mapper, String suffix, double width) {
        TableColumn<T, Number> column = new TableColumn<>(title);
        column.setCellValueFactory(data -> new ReadOnlyObjectWrapper<>(mapper.apply(data.getValue())));
        column.setCellFactory(ignored -> new TableCell<>() {
            @Override
            protected void updateItem(Number item, boolean empty) {
                super.updateItem(item, empty);
                setText(empty || item == null ? null : item + suffix);
            }
        });
        column.setPrefWidth(width);
        return column;
    }

    private Label label(String text, String styleClass) {
        Label label = new Label(text);
        label.getStyleClass().add(styleClass);
        label.setWrapText(true);
        return label;
    }

    private Region spacer() {
        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        VBox.setVgrow(spacer, Priority.ALWAYS);
        return spacer;
    }

    private Node separator() {
        Region separator = new Region();
        separator.getStyleClass().add("separator");
        separator.setMinHeight(1);
        return separator;
    }

    private void appendFeed(String message) {
        eventFeed.add(0, TIME_FORMAT.format(Instant.now()) + "  " + message);
        if (eventFeed.size() > 60) {
            eventFeed.remove(60, eventFeed.size());
        }
    }

    private void showStatus(String message, boolean error) {
        if (statusLine != null) {
            statusLine.setText(message);
            statusLine.getStyleClass().removeAll("error");
            if (error) {
                statusLine.getStyleClass().add("error");
            }
        }
        appendFeed((error ? "Ошибка: " : "") + message);
    }

    private void updatePreviewStatus(String message, boolean error) {
        setPreviewStatusText(message, error);
        appendFeed((error ? "Preview: ошибка: " : "Preview: ") + message);
    }

    private void reportPreviewError(String message) {
        long now = System.currentTimeMillis();
        if (now - lastPreviewErrorAt > 3000) {
            lastPreviewErrorAt = now;
            updatePreviewStatus(message, true);
        } else {
            setPreviewStatusText(message, true);
        }
    }

    private void setPreviewStatusText(String message, boolean error) {
        if (previewStatusLabel != null) {
            previewStatusLabel.setText(message);
            previewStatusLabel.getStyleClass().removeAll("error");
            if (error) {
                previewStatusLabel.getStyleClass().add("error");
            }
        }
    }

    private void setText(Label label, String text) {
        if (label != null) {
            label.setText(text);
        }
    }

    private void attachStyles(Scene scene) {
        scene.getStylesheets().add(Objects.requireNonNull(
                AudienceFlowDesktopApplication.class.getResource("/io/audienceflow/desktop/app.css")
        ).toExternalForm());
    }

    private String userMessage(Throwable throwable) {
        Throwable current = throwable;
        while (current instanceof CompletionException && current.getCause() != null) {
            current = current.getCause();
        }
        return current.getMessage() == null ? "Операция не выполнена" : current.getMessage();
    }

    private String nullSafe(String value) {
        return value == null || value.isBlank() ? "n/a" : value;
    }

    private String sourceLabel(String source) {
        return switch (source) {
            case "live" -> "Live";
            case "REST" -> "Ручное обновление";
            default -> "Polling";
        };
    }

    private String roleLabel(Role role) {
        return switch (role) {
            case ADMIN -> "Администратор";
            case TECHNICIAN -> "Техник";
            case TEACHER -> "Преподаватель";
        };
    }

    private String statusLabel(String status) {
        return switch (status) {
            case "full" -> "Переполнение";
            case "warning" -> "Высокая";
            default -> "Норма";
        };
    }

    private String cameraStatusLabel(String status) {
        return switch (status) {
            case "online" -> "Онлайн";
            case "maintenance" -> "Сервис";
            default -> "Офлайн";
        };
    }

    private String streamLabel(String streamType) {
        return switch (streamType) {
            case "rtsp" -> "RTSP";
            case "http" -> "HTTP";
            case "device" -> "Device";
            default -> "Simulation";
        };
    }
}
