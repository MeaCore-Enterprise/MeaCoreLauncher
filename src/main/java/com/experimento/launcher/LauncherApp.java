package com.experimento.launcher;

import com.experimento.launcher.model.*;
import com.experimento.launcher.mojang.*;
import com.experimento.launcher.paths.*;
import com.experimento.launcher.service.*;
import com.experimento.launcher.store.*;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Path;
import com.experimento.launcher.modloaders.ModloaderInstallerService;
import com.experimento.launcher.util.OfflineUuid;
import com.experimento.launcher.util.VersionComparator;
import javafx.application.Application;
import javafx.application.Platform;
import javafx.beans.property.BooleanProperty;
import javafx.beans.property.SimpleBooleanProperty;
import javafx.beans.property.SimpleStringProperty;
import javafx.collections.FXCollections;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Cursor;
import javafx.scene.Scene;
import javafx.scene.Node;
import javafx.scene.control.*;
import javafx.scene.control.cell.TextFieldTableCell;
import javafx.scene.layout.*;
import javafx.scene.paint.Color;
import javafx.scene.shape.Circle;
import javafx.stage.Stage;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

public class LauncherApp extends Application {

    private final ExecutorService workers = Executors.newVirtualThreadPerTaskExecutor();
    private final List<ManifestVersionEntry> allManifestEntries = FXCollections.observableArrayList();
    
    private boolean syncingVersionUi;
    private boolean syncingIdentityUi;

    private LauncherFacade facade;
    private List<LauncherProfile> profiles;
    private LauncherProfile selected;

    private final Map<String, Process> activeProcesses = new ConcurrentHashMap<>();
    private final Map<String, BooleanProperty> runningState = new HashMap<>();
    private final AtomicBoolean installing = new AtomicBoolean(false);
    
    private StackPane contentStack;
    private final Map<String, Node> views = new HashMap<>();

    // Componentes UI
    private ListView<LauncherProfile> profileList;
    private TextField displayNameField;
    private TextField usernameField;
    private TextField uuidField;
    private ComboBox<ManifestVersionEntry> versionCombo;
    private ComboBox<String> versionFilter;
    private ComboBox<JvmPresetKind> presetCombo;
    private TextArea jvmArea;
    private CheckBox globalMcCheck;
    private TableView<ServerEntry> serverTable;
    private TextArea logArea;
    private Label modHintLabel;
    private Label modLoaderBadgeLabel;
    private Label aternosHint;
    @SuppressWarnings({"FieldCanBeLocal", "unused"})
    private Stage stage;
    private String currentViewTitle = "General";
    private StackPane deleteConfirmOverlay;
    private Label deleteConfirmMsg;
    private StackPane modloaderOverlay;
    private Label modloaderMcLabel;
    private HBox updateBanner;
    private ProgressBar updateProgress;
    private Label updateStatus;
    private Button updateBtn;
    private Label sidebarVersionLabel;
    private HBox sidebarStatusBox;
    private final Map<String, Button> navButtons = new HashMap<>();
    private StackPane javaDownloadOverlay;
    private ProgressBar javaProgress;
    private Label javaStatus;
    private Label javaProgressLabel;
    private Button javaDownloadCloseBtn;
    private int detectedJavaVersion = 8;

    // Modloader overlay — Paso 2 (selector de versión específica)
    private VBox modloaderStep1;
    private VBox modloaderStep2;
    private ComboBox<String> modloaderVersionCombo;
    private Label modloaderStep2Title;
    private javafx.scene.control.ProgressIndicator modloaderVersionSpinner;
    private String currentSelectedLoaderType;

    // Store mod version selector overlay
    private StackPane modVersionOverlay;
    private VBox modVersionStep1;
    private VBox modVersionStep2;
    private ComboBox<com.experimento.launcher.store.ModVersion> modVersionCombo;
    private Label modVersionStep2Title;
    private Label modVersionModLabel;
    private javafx.scene.control.ProgressIndicator modVersionSpinner;
    private StoreItem currentSelectedStoreItem;
    private List<com.experimento.launcher.store.ModVersion> currentModVersions;
    private Button installSpecificVersionBtn;

    // Gestor de mods instalados
    private javafx.scene.control.ListView<com.experimento.launcher.service.InstalledModsService.InstalledMod> storeModListView;

    // Nuevo Header Dinámico
    private Label headerProfileName;
    private Label headerProfileVersion;

    // Botones (para deshabilitar durante procesos)
    private Button installBtn;
    private Button playBtn;
    private Button saveBtn;
    private Button newProfileBtn;
    private Button deleteProfileBtn;

    public static void main(String[] args) {
        launch(args);
    }

    @Override
    public void start(Stage stage) throws Exception {
        Stage splashStage = new Stage(javafx.stage.StageStyle.UNDECORATED);
        StackPane splashRoot = new StackPane();
        splashRoot.setStyle("-fx-background-color: linear-gradient(to right, #1177BB, #0E639C); -fx-border-color: #0c507c; -fx-border-width: 4px;");
        
        Label title = new Label("MEACORE\nTHE LAUNCHER FOR MINECRAFT");
        title.setStyle("-fx-text-fill: white; -fx-font-size: 28px; -fx-font-weight: bold; -fx-alignment: center; -fx-text-alignment: center;");
        
        Label version = new Label(LauncherMetadata.VERSION);
        version.setStyle("-fx-text-fill: white; -fx-background-color: rgba(0,0,0,0.2); -fx-padding: 3 8;");
        StackPane.setAlignment(version, Pos.TOP_RIGHT);
        
        splashRoot.getChildren().addAll(title, version);
        Scene splashScene = new Scene(splashRoot, 400, 250);
        splashStage.setScene(splashScene);
        splashStage.show();

        new Thread(() -> {
            try {
                initData();
                Platform.runLater(() -> {
                    try {
                        initLayout(stage);
                        splashStage.close();
                        stage.show();
                        loadVersionManifestAsync();
                        com.experimento.launcher.service.AutoUpdateService.checkForUpdatesAsync();
                    } catch (Exception ex) {
                        ex.printStackTrace();
                    }
                });
            } catch (Exception e) {
                e.printStackTrace();
            }
        }).start();
    }

    private void initLayout(Stage stage) throws Exception {
        this.stage = stage;
        
        VBox sidebarArea = new VBox();
        sidebarArea.setPrefWidth(260);
        sidebarArea.setStyle("-fx-background-color: #252526;"); // Carbón Premium
        
        Label brandLabel = new Label("MeaCore Launcher");
        brandLabel.setStyle("-fx-text-fill: white; -fx-font-size: 18px; -fx-font-weight: bold; -fx-padding: 20 10;");

        sidebarVersionLabel = new Label("v" + LauncherMetadata.VERSION);
        sidebarVersionLabel.setStyle("-fx-text-fill: #666666; -fx-font-size: 11px;");
        
        sidebarStatusBox = new HBox(5);
        sidebarStatusBox.setAlignment(Pos.CENTER_LEFT);
        sidebarStatusBox.setVisible(false);
        sidebarStatusBox.setManaged(false);
        Label statusDot = new Label("●");
        statusDot.setStyle("-fx-text-fill: #4CAF50; -fx-font-size: 14px;");
        Label statusTxt = new Label("Actualización lista");
        statusTxt.setStyle("-fx-text-fill: #4CAF50; -fx-font-size: 11px; -fx-font-weight: bold;");
        sidebarStatusBox.getChildren().addAll(statusDot, statusTxt);
        sidebarStatusBox.setCursor(Cursor.HAND);
        sidebarStatusBox.setOnMouseClicked(e -> {
            updateBanner.setVisible(true);
            updateBanner.setManaged(true);
        });

        Button checkUpdateBtn = new Button("Buscar Actualizaciones");
        checkUpdateBtn.setStyle("-fx-background-color: transparent; -fx-text-fill: #888888; -fx-font-size: 10px; -fx-padding: 0; -fx-cursor: hand;");
        checkUpdateBtn.setOnAction(e -> {
            checkUpdateBtn.setText("Buscando...");
            checkUpdateBtn.setDisable(true);
            AutoUpdateService.checkForUpdatesAsync();
        });

        VBox sidebarFooter = new VBox(5, sidebarStatusBox, sidebarVersionLabel, checkUpdateBtn);
        sidebarFooter.setPadding(new Insets(10, 20, 15, 20));

        Region sidebarSpacer = new Region();
        VBox.setVgrow(sidebarSpacer, Priority.ALWAYS);

        sidebarArea.getChildren().addAll(brandLabel, createProfileSidebar(), new Separator(), createNavigationMenu(), sidebarSpacer, sidebarFooter);

        contentStack = new StackPane();
        contentStack.setPadding(new Insets(20));
        
        initializeViews();
        setupFieldListeners();
        
        headerProfileName = new Label("Cargando...");
        headerProfileName.setStyle("-fx-text-fill: white; -fx-font-size: 32px; -fx-font-weight: bold; -fx-effect: dropshadow(gaussian, rgba(0,0,0,0.8), 5, 0, 0, 2);");
        headerProfileVersion = new Label("");
        headerProfileVersion.setStyle("-fx-text-fill: #e0e0e0; -fx-font-size: 16px; -fx-effect: dropshadow(gaussian, rgba(0,0,0,0.8), 3, 0, 0, 1);");

        VBox headerTextInfo = new VBox(5, headerProfileName, headerProfileVersion);
        headerTextInfo.setAlignment(Pos.CENTER_LEFT);
        headerTextInfo.setPadding(new Insets(0, 0, 0, 30));

        StackPane topHeader = new StackPane(headerTextInfo);
        topHeader.setPrefHeight(120);
        topHeader.setMinHeight(120);
        topHeader.setMaxHeight(120);
        topHeader.setStyle("-fx-background-color: linear-gradient(to right, #111111, #094771); -fx-background-insets: 0;");

        updateBanner = buildUpdateBanner();
        updateBanner.setVisible(false);
        updateBanner.setManaged(false);

        VBox rightArea = new VBox();
        rightArea.getChildren().addAll(updateBanner, topHeader, contentStack, createPersistentFooter());
        VBox.setVgrow(contentStack, Priority.ALWAYS);

        HBox mainLayout = new HBox(sidebarArea, rightArea);
        HBox.setHgrow(rightArea, Priority.ALWAYS);

        // Overlays internos
        deleteConfirmOverlay = buildDeleteOverlay();
        deleteConfirmOverlay.setVisible(false);
        modloaderOverlay = buildModloaderOverlay();
        modloaderOverlay.setVisible(false);
        javaDownloadOverlay = buildJavaDownloadOverlay();
        javaDownloadOverlay.setVisible(false);
        modVersionOverlay = buildModVersionOverlay();
        modVersionOverlay.setVisible(false);
        StackPane rootPane = new StackPane(mainLayout, deleteConfirmOverlay, modloaderOverlay, javaDownloadOverlay, modVersionOverlay);

        Scene scene = new Scene(rootPane, 1080, 720);
        stage.setMinWidth(1080);
        stage.setMinHeight(720);
        
        // Listener de actualización
        AutoUpdateService.setListener(new AutoUpdateService.UpdateListener() {
            @Override
            public void onUpdateFound(String version, String url) {
                Platform.runLater(() -> {
                    updateStatus.setText("🚀 Descargando v" + version + " en segundo plano...");
                    updateBtn.setText("Descargando...");
                    updateBtn.setDisable(true);
                    updateBanner.setVisible(true);
                    updateBanner.setManaged(true);
                    sidebarStatusBox.setVisible(true);
                    sidebarStatusBox.setManaged(true);
                    // Solo descarga — la instalación la dispara el usuario con "Reiniciar Ahora"
                    AutoUpdateService.downloadAndInstallAsync(url);
                });
            }

            @Override
            public void onDownloadProgress(double fraction) {
                Platform.runLater(() -> {
                    updateProgress.setProgress(fraction);
                    updateStatus.setText("Descargando actualización: " + (int)(fraction * 100) + "%");
                });
            }

            @Override
            public void onDownloadComplete(Path installerPath) {
                Platform.runLater(() -> {
                    updateProgress.setProgress(1.0);
                    updateStatus.setText("✅ Actualización lista. Haz clic en 'Reiniciar Ahora' para instalar.");
                    updateBtn.setText("Reiniciar Ahora");
                    updateBtn.setDisable(false);
                    // CORRECCIÓN: el botón llama installFromPath (que desbloquea SmartScreen,
                    // escribe el .bat y lo lanza de forma desacoplada) en lugar de System.exit(0)
                    updateBtn.setOnAction(e -> AutoUpdateService.installFromPath(installerPath));
                    sidebarVersionLabel.setText("v" + LauncherMetadata.VERSION + " → Nueva lista");
                });
            }

            @Override
            public void onDownloadError(String message) {
                Platform.runLater(() -> {
                    updateBtn.setText("❌ Error");
                    updateBtn.setDisable(true);
                    updateStatus.setText("⚠ " + message);
                    updateBanner.setVisible(true);
                    updateBanner.setManaged(true);
                    updateBanner.setStyle("-fx-background-color: #5a1a1a;");
                    log("⚠ Error de actualización: " + message);
                });
            }
        });

        try {
            java.net.URL cssUrl = LauncherApp.class.getResource("/com/experimento/launcher/ui/meacore.css");
            if (cssUrl != null) scene.getStylesheets().add(cssUrl.toExternalForm());
        } catch (Exception ignored) {}
        
        stage.setTitle(LauncherMetadata.DISPLAY_NAME);
        stage.getProperties().put("glass.gtk.wm_class", "meacorelauncher");
        try {
            for (String s : new String[]{"/com/experimento/launcher/ui/icon-256.png", "/com/experimento/launcher/ui/icon-128.png", "/com/experimento/launcher/ui/icon.png"}) {
                var stream = LauncherApp.class.getResourceAsStream(s);
                if (stream != null) stage.getIcons().add(new javafx.scene.image.Image(stream));
            }
        } catch (Exception ignored) {}
        
        stage.setScene(scene);
        stage.centerOnScreen();
        
        // Inicialización post-UI
        showView("General");
        profileList.getSelectionModel().selectFirst();

        stage.setOnCloseRequest(ev -> {
            workers.shutdownNow();
            Platform.exit();
            System.exit(0);
        });
    }

    private void initData() throws Exception {
        LauncherDirectories dirs = LauncherDirectories.fromDefault();
        dirs.ensureBaseDirs();
        facade = new LauncherFacade(dirs);
        profiles = new ArrayList<>(facade.profiles().loadOrCreateDefault());
        for (LauncherProfile p : profiles) {
            LauncherFacade.maybeImportTlauncherJvm(p);
            runningState.put(p.id, new SimpleBooleanProperty(false));
        }
        facade.profiles().save(profiles);
        
        // Recopilación de datos (Telemetría Inicial)
        SystemInfoService.collectTelemetry(dirs.launcherData().resolve("telemetry.log"));
    }

    private VBox createProfileSidebar() {
        profileList = new ListView<>(FXCollections.observableList(profiles));
        profileList.setPrefWidth(240);
        profileList.setStyle("-fx-background-color: #252526; -fx-background: #252526; -fx-control-inner-background: #252526; -fx-border-color: transparent;");
        profileList.setCellFactory(lv -> new ListCell<>() {
            @Override
            protected void updateItem(LauncherProfile item, boolean empty) {
                super.updateItem(item, empty);
                setStyle(isSelected()
                    ? "-fx-background-color: #37373D;"
                    : "-fx-background-color: #252526;");
                if (empty || item == null) {
                    setGraphic(null); setText(null);
                } else {
                    HBox box = new HBox(12);
                    box.setAlignment(Pos.CENTER_LEFT);
                    box.setPadding(new Insets(5, 5, 5, 5));
                    
                    Circle indicator = new Circle(4);
                    indicator.setFill(Color.TRANSPARENT);
                    BooleanProperty running = runningState.get(item.id);
                    if (running != null) {
                        indicator.fillProperty().bind(javafx.beans.binding.Bindings.when(running)
                            .then(Color.LIMEGREEN)
                            .otherwise(Color.TRANSPARENT));
                    }
                    
                    Label name = new Label(item.displayName + " (" + (item.lastVersionId != null && !item.lastVersionId.isBlank() ? item.lastVersionId : "Sin versión") + ")");
                    name.setStyle("-fx-text-fill: inherit;");
                    
                    box.getChildren().addAll(indicator, name);
                    setGraphic(box);
                }
            }
            @Override
            public void updateSelected(boolean selected) {
                super.updateSelected(selected);
                setStyle(selected ? "-fx-background-color: #37373D;" : "-fx-background-color: #252526;");
            }
        });
        profileList.getSelectionModel().selectedItemProperty().addListener((obs, o, n) -> bindProfile(n));

        Label perfilesLabel = new Label("  Perfiles");
        perfilesLabel.setStyle("-fx-text-fill: #888888; -fx-font-size: 11px; -fx-font-weight: bold; -fx-padding: 10 0 4 0;");
        
        VBox sidebar = new VBox(0, perfilesLabel, profileList);
        VBox.setVgrow(profileList, Priority.ALWAYS);
        return sidebar;
    }
    private VBox createNavigationMenu() {
        VBox menu = new VBox(10);
        menu.setPadding(new Insets(20, 10, 20, 10));
        menu.setAlignment(Pos.TOP_LEFT);

        menu.getChildren().addAll(
            createNavButton("🏠 General", "General"),
            createNavButton("🛠 Modding", "Modding"),
            createNavButton("🏪 MCMOD", "Store"),
            createNavButton("⚙ Config. Java", "Java"),
            createNavButton("🌐 Servidores", "Servers"),
            createNavButton("📜 Consola", "Log")
        );

        return menu;
    }

    private Button createNavButton(String text, String viewId) {
        Button btn = new Button(text);
        btn.setMaxWidth(Double.MAX_VALUE);
        btn.setAlignment(Pos.CENTER_LEFT);
        btn.setId("nav-" + viewId); // Para identificarlo fácilmente
        
        btn.setStyle("-fx-background-color: transparent; -fx-text-fill: #cccccc; -fx-font-size: 14px; -fx-padding: 10 15; -fx-cursor: hand; -fx-background-radius: 5;");
        
        btn.setOnMouseEntered(e -> {
            if (!viewId.equals(currentViewId)) {
                btn.setStyle("-fx-background-color: #333333; -fx-text-fill: white; -fx-font-size: 14px; -fx-padding: 10 15; -fx-cursor: hand; -fx-background-radius: 5;");
            }
        });
        
        btn.setOnMouseExited(e -> updateNavButtonStyle(btn, viewId));

        btn.setOnAction(e -> showView(viewId));
        navButtons.put(viewId, btn);
        return btn;
    }

    private String currentViewId = "General";

    private void updateNavButtonStyle(Button btn, String viewId) {
        if (viewId.equals(currentViewId)) {
            btn.setStyle("-fx-background-color: #3d3d3d; -fx-text-fill: #0E639C; -fx-font-size: 14px; -fx-font-weight: bold; -fx-padding: 10 15; -fx-background-radius: 5;");
        } else {
            btn.setStyle("-fx-background-color: transparent; -fx-text-fill: #cccccc; -fx-font-size: 14px; -fx-padding: 10 15; -fx-background-radius: 5;");
        }
    }

    private void showView(String viewId) {
        Node view = views.get(viewId);
        if (view != null) {
            contentStack.getChildren().setAll(view);
            String oldViewId = currentViewId;
            currentViewId = viewId;
            currentViewTitle = viewId;
            updateHeaderTitle();
            
            // Actualización instantánea de botones sin recorrer el árbol
            Button oldBtn = navButtons.get(oldViewId);
            if (oldBtn != null) updateNavButtonStyle(oldBtn, oldViewId);
            
            Button newBtn = navButtons.get(viewId);
            if (newBtn != null) updateNavButtonStyle(newBtn, viewId);
        }
    }

    private void initializeViews() {
        logArea = new TextArea();
        logArea.setEditable(false);
        logArea.setPrefRowCount(20);

        jvmArea = new TextArea();
        jvmArea.setPrefRowCount(3);
        
        globalMcCheck = new CheckBox("Usar ~/.minecraft global (avanzado)");
        presetCombo = new ComboBox<>(FXCollections.observableArrayList(JvmPresetKind.values()));
        presetCombo.setOnAction(e -> handlePresetChange());

        views.put("General", createGeneralView());
        views.put("Modding", createModdingView());
        views.put("Store", createStoreView());
        views.put("Java", createJavaView());
        views.put("Servers", createServersView());
        views.put("Log", createLogAreaView());
    }

    private Node createGeneralView() {
        VBox identityCard = new VBox(15, new Label("Ajustes de Identidad"), createIdentitySection());
        identityCard.getStyleClass().add("mc-card");

        VBox versionCard = new VBox(15, new Label("Versión del Juego"), createVersionSection());
        versionCard.getStyleClass().add("mc-card");

        VBox content = new VBox(20, identityCard, versionCard);
        content.setPadding(new Insets(10, 15, 10, 0)); // Evitar que el scrollbar tape cards
        
        ScrollPane scroll = new ScrollPane(content);
        scroll.setFitToWidth(true);
        scroll.setStyle("-fx-background-color: transparent; -fx-background: transparent; -fx-border-color: transparent;");
        return scroll;
    }

    private VBox createModdingView() {
        // ── Card: Gestión de Modloaders ────────────────────────────────────────
        Button installModloaderBtn = new Button("✨ Inyectar Modloader (con versión específica)");
        installModloaderBtn.setStyle("-fx-font-size: 14px; -fx-padding: 10 20; -fx-background-color: #0E639C; -fx-text-fill: white; -fx-font-weight: bold;");
        installModloaderBtn.setMaxWidth(Double.MAX_VALUE);
        installModloaderBtn.setOnAction(e -> handleInstallModloader());

        Label modloaderTitle = new Label("🔧 Gestión de Modloaders");
        modloaderTitle.setStyle("-fx-text-fill: white; -fx-font-size: 15px; -fx-font-weight: bold;");
        Label modloaderDesc = new Label("Instala Forge, NeoForge, Fabric o Quilt — ahora con selector de versión exacta.");
        modloaderDesc.setStyle("-fx-text-fill: #aaaaaa; -fx-font-size: 12px;");

        modLoaderBadgeLabel = new Label("Modloader activo: (selecciona un perfil)");
        modLoaderBadgeLabel.setStyle("-fx-text-fill: #cccccc; -fx-font-size: 12px; -fx-background-color: #1e1e1e; -fx-padding: 4 10; -fx-background-radius: 4;");

        VBox loaderCard = new VBox(10, modloaderTitle, modloaderDesc, modLoaderBadgeLabel, installModloaderBtn);
        loaderCard.getStyleClass().add("mc-card");

        // ── Card: Mods de Rendimiento ─────────────────────────────────────────
        Label perfTitle = new Label("⚡ Mods de Rendimiento (GRATIS)");
        perfTitle.setStyle("-fx-text-fill: white; -fx-font-size: 15px; -fx-font-weight: bold;");
        Label perfDesc = new Label(
            "Instala automáticamente Sodium, Lithium, FerriteCore, ImmediatelyFast y más.\n" +
            "Pueden subir los FPS de 30 a 100+ en modpacks pesados. Requiere Fabric, Quilt, Forge o NeoForge.");
        perfDesc.setWrapText(true);
        perfDesc.setStyle("-fx-text-fill: #aaaaaa; -fx-font-size: 12px;");
        Button perfModsBtn = new Button("🚀 Instalar Mods de Rendimiento");
        perfModsBtn.setStyle("-fx-font-size: 14px; -fx-padding: 10 20; -fx-background-color: #2e7d32; -fx-text-fill: white; -fx-font-weight: bold;");
        perfModsBtn.setOnAction(e -> handleInstallPerformanceMods(perfModsBtn));

        VBox perfCard = new VBox(10, perfTitle, perfDesc, perfModsBtn);
        perfCard.getStyleClass().add("mc-card");

        // ── Layout final ──────────────────────────────────────────────────────
        VBox modding = new VBox(20, loaderCard, perfCard);
        modding.setAlignment(Pos.TOP_LEFT);
        modding.setPadding(new Insets(0, 10, 10, 0));

        ScrollPane scroll = new ScrollPane(modding);
        scroll.setFitToWidth(true);
        scroll.setStyle("-fx-background-color: transparent; -fx-background: transparent; -fx-border-color: transparent;");

        VBox wrapper = new VBox(scroll);
        VBox.setVgrow(scroll, Priority.ALWAYS);
        return wrapper;
    }

    /** Refresca la lista de mods del perfil activo. */
    private void refreshModList() {
        if (storeModListView == null) return;
        if (selected == null) {
            storeModListView.getItems().clear();
            return;
        }
        Path modsDir = facade.gameDirFor(selected).resolve("mods");
        var mods = com.experimento.launcher.service.InstalledModsService.scanMods(modsDir);
        storeModListView.getItems().setAll(mods);
    }

    private void configureModListView(javafx.scene.control.ListView<com.experimento.launcher.service.InstalledModsService.InstalledMod> listView) {
        listView.setCellFactory(lv -> new javafx.scene.control.ListCell<>() {
            @Override
            protected void updateItem(
                    com.experimento.launcher.service.InstalledModsService.InstalledMod item,
                    boolean empty) {
                super.updateItem(item, empty);
                if (empty || item == null) {
                    setGraphic(null);
                    setText(null);
                    setStyle("");
                } else {
                    HBox row = new HBox(10);
                    row.setAlignment(Pos.CENTER_LEFT);

                    Button toggle = new Button(item.enabled() ? "🟢" : "🔴");
                    toggle.setStyle("-fx-background-color: transparent; -fx-font-size: 14px; -fx-padding: 0 4; -fx-cursor: hand;");
                    toggle.setOnAction(ev -> {
                        workers.submit(() -> {
                            try {
                                if (item.enabled()) {
                                    com.experimento.launcher.service.InstalledModsService.disableMod(item);
                                } else {
                                    com.experimento.launcher.service.InstalledModsService.enableMod(item);
                                }
                                Platform.runLater(() -> refreshModList());
                            } catch (Exception ex) {
                                Platform.runLater(() -> log("[Mods] Error: " + ex.getMessage()));
                            }
                        });
                    });

                    Label nameLbl = new Label(item.cleanName());
                    nameLbl.setMaxWidth(Double.MAX_VALUE);
                    if (!item.enabled()) {
                        nameLbl.setStyle("-fx-text-fill: #666666; -fx-font-size: 12px;");
                        nameLbl.setOpacity(0.45);
                    } else {
                        nameLbl.setStyle("-fx-text-fill: #e0e0e0; -fx-font-size: 12px;");
                        nameLbl.setOpacity(1.0);
                    }
                    HBox.setHgrow(nameLbl, Priority.ALWAYS);

                    row.getChildren().addAll(toggle, nameLbl);
                    setGraphic(row);
                    setText(null);
                    setStyle("-fx-background-color: transparent;");
                }
            }
        });

        javafx.scene.control.ContextMenu ctxMenu = new javafx.scene.control.ContextMenu();
        javafx.scene.control.MenuItem deleteItem = new javafx.scene.control.MenuItem("🗑  Eliminar mod seleccionado");
        deleteItem.setStyle("-fx-text-fill: #f44336;");
        deleteItem.setOnAction(e -> {
            var sel = listView.getSelectionModel().getSelectedItem();
            if (sel == null) return;
            workers.submit(() -> {
                try {
                    com.experimento.launcher.service.InstalledModsService.deleteMod(sel);
                    Platform.runLater(() -> {
                        log("[Mods] Eliminado: " + sel.cleanName());
                        refreshModList();
                    });
                } catch (Exception ex) {
                    Platform.runLater(() -> log("[Mods] Error al eliminar: " + ex.getMessage()));
                }
            });
        });
        ctxMenu.getItems().add(deleteItem);
        listView.setContextMenu(ctxMenu);
    }

    private void handleInstallPerformanceMods(Button btn) {
        if (selected == null) {
            log("[PERF] Selecciona un perfil primero.");
            btn.setDisable(false); btn.setText("🚀 Instalar Mods de Rendimiento");
            return;
        }
        String version = selected.lastVersionId;
        if (version == null || version.isBlank()) {
            log("[PERF] El perfil no tiene versión configurada.");
            btn.setDisable(false); btn.setText("🚀 Instalar Mods de Rendimiento");
            return;
        }
        String loader = selected.modLoader != null ? selected.modLoader : "vanilla";
        if (!PerformanceModsService.isSupported(loader)) {
            log("[PERF] Este perfil no tiene modloader activo. Instala Fabric, Quilt, Forge o NeoForge primero desde la pestaña Modding.");
            btn.setDisable(false); btn.setText("🚀 Instalar Mods de Rendimiento");
            return;
        }
        btn.setDisable(true);
        btn.setText("Instalando...");
        Path modsDir = facade.gameDirFor(selected).resolve("mods");
        workers.submit(() -> {
            try {
                PerformanceModsService.installPerformanceMods(
                    modsDir, version, loader,
                    msg -> Platform.runLater(() -> log(msg)));
                Platform.runLater(() -> {
                    btn.setDisable(false);
                    btn.setText("✅ Mods instalados");
                    showView("Log");
                });
            } catch (Exception e) {
                Platform.runLater(() -> {
                    btn.setDisable(false);
                    btn.setText("🚀 Instalar Mods de Rendimiento");
                    log("[PERF] Error: " + e.getMessage());
                });
            }
        });
    }


    private VBox createStoreView() {
        HBox topBar = new HBox(10);
        topBar.setAlignment(Pos.CENTER_LEFT);

        ComboBox<StoreCategory> catCombo = new ComboBox<>(FXCollections.observableArrayList(StoreCategory.values()));
        catCombo.setValue(StoreCategory.MODPACK);

        ComboBox<String> loaderCombo = new ComboBox<>(FXCollections.observableArrayList("Todos", "Forge", "Fabric", "Quilt", "NeoForge"));
        loaderCombo.setValue("Todos");
        loaderCombo.setPrefWidth(120);

        TextField searchField = new TextField();
        searchField.setPromptText("Buscar...");
        searchField.setPrefWidth(200);

        Button searchBtn = new Button("🔍 Buscar");

        topBar.getChildren().addAll(catCombo, loaderCombo, searchField, searchBtn);

        ListView<StoreItem> storeList = new ListView<>();
        storeList.setPrefHeight(400);
        storeList.setCellFactory(lv -> new ListCell<>() {
            @Override protected void updateItem(StoreItem item, boolean empty) {
                super.updateItem(item, empty);
                if (empty || item == null) { setGraphic(null); setText(null); }
                else {
                    HBox box = new HBox(15);
                    box.setAlignment(Pos.CENTER_LEFT);
                    box.setPadding(new Insets(10));

                    StackPane iconBox = new StackPane();
                    iconBox.setPrefSize(64, 64);
                    iconBox.setMinSize(64, 64);
                    iconBox.setStyle("-fx-background-color: #2a2a2a; -fx-background-radius: 8px;");

                    Label fallbackIcon = new Label("📦");
                    fallbackIcon.setStyle("-fx-font-size: 32px;");
                    iconBox.getChildren().add(fallbackIcon);

                    if (item.thumbnailUrl() != null && !item.thumbnailUrl().isBlank()) {
                        javafx.scene.image.ImageView imgView = new javafx.scene.image.ImageView();
                        imgView.setFitWidth(64); 
                        imgView.setFitHeight(64);
                        javafx.scene.image.Image img = new javafx.scene.image.Image(item.thumbnailUrl(), true);
                        imgView.setImage(img);
                        iconBox.getChildren().add(imgView);
                        
                        img.errorProperty().addListener((obs, o, isError) -> {
                            if (isError) {
                                Platform.runLater(() -> iconBox.getChildren().remove(imgView));
                            }
                        });
                    }

                    VBox info = new VBox(5);
                    Label title = new Label(item.title());
                    title.setStyle("-fx-font-weight: bold; -fx-font-size: 16px; -fx-text-fill: white;");
                    Label author = new Label("Por " + item.author());
                    author.setStyle("-fx-text-fill: #0E639C;");
                    Label desc = new Label(item.description());
                    desc.setWrapText(true); desc.setMaxWidth(400); desc.setStyle("-fx-text-fill: #cccccc;");
                    Label stats = new Label("Descargas: " + item.downloads() + " | Versión: " + item.latestVersion());
                    stats.setStyle("-fx-font-size: 11px; -fx-text-fill: #888888;");
                    info.getChildren().addAll(title, author, desc, stats);

                    Region spacer = new Region();
                    HBox.setHgrow(spacer, Priority.ALWAYS);

                    Button btnInstall = new Button("✨ Instalar");
                    btnInstall.setStyle("-fx-background-color: #0E639C; -fx-text-fill: white; -fx-font-weight: bold;");
                    btnInstall.setOnAction(ev -> {
                        if (selected == null) {
                            new Alert(Alert.AlertType.WARNING, "Selecciona un perfil en la barra lateral primero.").show();
                            return;
                        }
                        // Abrir selector de versión para mods
                        currentSelectedStoreItem = item;
                        modVersionModLabel.setText(item.title() + " por " + item.author());
                        modVersionOverlay.setVisible(true);
                        resetModVersionOverlayToStep1();
                    });

                    box.getChildren().addAll(iconBox, info, spacer, btnInstall);
                    setGraphic(box);
                }
            }
        });
        
        VBox.setVgrow(storeList, Priority.ALWAYS);

        Button loadMoreBtn = new Button("Cargar Más...");
        loadMoreBtn.setMaxWidth(Double.MAX_VALUE);
        
        final int[] offset = {0};

        Runnable performSearch = () -> {
            storeList.getItems().clear();
            offset[0] = 0;
            workers.submit(() -> {
                String loader = loaderCombo.getValue().equals("Todos") ? null : loaderCombo.getValue().toLowerCase();
                var results = ModrinthStoreClient.search(searchField.getText(), catCombo.getValue(), loader, 0);
                Platform.runLater(() -> storeList.getItems().addAll(results));
            });
        };

        searchBtn.setOnAction(e -> performSearch.run());
        searchField.setOnAction(e -> performSearch.run());
        catCombo.setOnAction(e -> performSearch.run());
        loaderCombo.setOnAction(e -> performSearch.run());

        loadMoreBtn.setOnAction(e -> {
            offset[0] += 20;
            workers.submit(() -> {
                String loader = loaderCombo.getValue().equals("Todos") ? null : loaderCombo.getValue().toLowerCase();
                var res = ModrinthStoreClient.search(searchField.getText(), catCombo.getValue(), loader, offset[0]);
                Platform.runLater(() -> storeList.getItems().addAll(res));
            });
        });

        // --- Sidebar: Mis Mods Instalados ---
        Label myModsTitle = new Label("🧩 Mis Mods");
        myModsTitle.setStyle("-fx-text-fill: white; -fx-font-size: 15px; -fx-font-weight: bold;");
        
        Label myModsDesc = new Label("Instancia activa. Clic derecho para borrar.");
        myModsDesc.setStyle("-fx-text-fill: #aaaaaa; -fx-font-size: 11px;");

        storeModListView = new javafx.scene.control.ListView<>();
        storeModListView.setPrefHeight(300);
        storeModListView.setPlaceholder(new Label("📭 Sin mods instalados"));
        configureModListView(storeModListView);

        Button sideRefreshBtn = new Button("🔄");
        sideRefreshBtn.setStyle("-fx-font-size: 11px; -fx-padding: 4 8;");
        sideRefreshBtn.setOnAction(e -> refreshModList());

        Button sideOpenFolderBtn = new Button("📂 Abrir Carpeta");
        sideOpenFolderBtn.setStyle("-fx-font-size: 11px; -fx-padding: 4 8; -fx-background-color: #3a3a3a; -fx-text-fill: white;");
        sideOpenFolderBtn.setOnAction(e -> {
            if (selected == null) return;
            Path modsDir = facade.gameDirFor(selected).resolve("mods");
            try {
                java.nio.file.Files.createDirectories(modsDir);
                String os = System.getProperty("os.name", "").toLowerCase();
                if (os.contains("win")) {
                    Runtime.getRuntime().exec(new String[]{"explorer", modsDir.toString()});
                } else if (os.contains("mac")) {
                    Runtime.getRuntime().exec(new String[]{"open", modsDir.toString()});
                } else {
                    Runtime.getRuntime().exec(new String[]{"xdg-open", modsDir.toString()});
                }
            } catch (Exception ex) {
                log("[Mods] No se pudo abrir la carpeta: " + ex.getMessage());
            }
        });

        HBox sideToolbar = new HBox(6, sideRefreshBtn, sideOpenFolderBtn);
        sideToolbar.setAlignment(Pos.CENTER_LEFT);

        VBox rightSide = new VBox(10, myModsTitle, myModsDesc, sideToolbar, storeModListView);
        rightSide.getStyleClass().add("mc-card");
        rightSide.setPrefWidth(260);
        rightSide.setMinWidth(260);
        rightSide.setMaxWidth(260);
        VBox.setVgrow(storeModListView, Priority.ALWAYS);

        VBox leftSide = new VBox(15, storeList, loadMoreBtn);
        HBox.setHgrow(leftSide, Priority.ALWAYS);
        VBox.setVgrow(storeList, Priority.ALWAYS);

        HBox body = new HBox(15, leftSide, rightSide);
        VBox.setVgrow(body, Priority.ALWAYS);

        // Trigger initial load
        Platform.runLater(() -> {
            performSearch.run();
            refreshModList();
        });

        return new VBox(15, topBar, body);
    }

    private VBox createJavaView() {
        GridPane grid = new GridPane();
        grid.setHgap(10); grid.setVgap(15);
        int r = 0;
        grid.add(new Label("Optimización RAM:"), 0, r); grid.add(presetCombo, 1, r++);
        grid.add(new Label("Argumentos JVM:"), 0, r); grid.add(jvmArea, 1, r++);
        grid.add(globalMcCheck, 1, r++);
        
        return new VBox(15, new Label("Motor de Ejecución Java"), grid, new Separator(), createHintSection());
    }

    private VBox createServersView() {
        return new VBox(10, new Label("Lista de Servidores Multijugador"), createServerTableSection());
    }

    private VBox createLogAreaView() {
        return new VBox(10, new Label("Consola de Diagnóstico en Vivo"), logArea);
    }

    private HBox createPersistentFooter() {
        saveBtn = new Button("💾 Guardar");
        saveBtn.setStyle("-fx-background-color: transparent; -fx-border-color: white; -fx-border-radius: 4px; -fx-text-fill: white;");
        saveBtn.setOnAction(e -> saveProfiles());

        installBtn = new Button("⬇ Instalar");
        installBtn.setStyle("-fx-background-color: #0E639C; -fx-text-fill: white;");
        installBtn.setOnAction(e -> runTask(createInstallTask()));

        playBtn = new Button("▶ ¡JUGAR!");
        playBtn.setDefaultButton(true);
        playBtn.getStyleClass().add("btn-play");
        playBtn.setOnAction(e -> handlePlayClick());

        newProfileBtn = new Button("➕ Nuevo");
        newProfileBtn.setOnAction(e -> createNewProfile());

        deleteProfileBtn = new Button("🗑 Borrar");
        deleteProfileBtn.setStyle("-fx-text-fill: #d32f2f;");
        deleteProfileBtn.setOnAction(e -> deleteSelectedProfile());

        HBox footer = new HBox(15, newProfileBtn, deleteProfileBtn, new Region(), saveBtn, installBtn, playBtn);
        HBox.setHgrow(footer.getChildren().get(2), Priority.ALWAYS);
        footer.setPadding(new Insets(15, 25, 15, 25));
        footer.setAlignment(Pos.CENTER_LEFT);
        footer.setStyle("-fx-background-color: #2d2d2d; -fx-border-color: #3f3f46; -fx-border-width: 1 0 0 0;");
        return footer;
    }

    private GridPane createIdentitySection() {
        displayNameField = new TextField();
        usernameField = new TextField();
        uuidField = new TextField();
        uuidField.setEditable(false);
        Button syncUuidBtn = new Button("🔄 Sync");
        syncUuidBtn.setOnAction(e -> syncUuidFromUsername());

        GridPane grid = new GridPane();
        grid.setHgap(10); grid.setVgap(10);
        grid.add(new Label("Nombre Perfil:"), 0, 0); grid.add(displayNameField, 1, 0);
        grid.add(new Label("Usuario (Offline):"), 0, 1); grid.add(usernameField, 1, 1);
        grid.add(new Label("UUID:"), 0, 2); grid.add(new HBox(8, uuidField, syncUuidBtn), 1, 2);
        return grid;
    }

    private VBox createVersionSection() {
        versionFilter = new ComboBox<>(FXCollections.observableArrayList("Todas", "Solo releases", "Solo snapshots", "Clásicas (beta/alpha)"));
        versionFilter.setValue("Todas");
        versionFilter.setOnAction(e -> applyVersionFilter());

        versionCombo = new ComboBox<>();
        versionCombo.setMaxWidth(Double.MAX_VALUE);
        setupVersionComboCellFactories();

        Button refreshManifestBtn = new Button("Actualizar");
        refreshManifestBtn.setOnAction(e -> loadVersionManifestAsync());

        return new VBox(10, new HBox(10, versionFilter, versionCombo, refreshManifestBtn));
    }



    private void setupFieldListeners() {
        displayNameField.textProperty().addListener((obs, o, n) -> {
            if (selected != null && !syncingIdentityUi) {
                selected.displayName = n;
                syncIdentityFromDisplayName(n);
                profileList.refresh();
            }
        });

        usernameField.textProperty().addListener((obs, o, n) -> {
            if (selected != null && !syncingIdentityUi) {
                selected.username = n;
                syncUuidFromUsername();
            }
        });

        jvmArea.textProperty().addListener((obs, o, n) -> {
            if (selected != null) selected.customJvmArgs = n;
        });

        versionCombo.valueProperty().addListener((obs, o, n) -> {
            if (!syncingVersionUi && selected != null && n != null) {
                // Validación: Solo cambiar si realmente es una selección manual del usuario
                if (!n.id().equals(selected.lastVersionId)) {
                    selected.lastVersionId = n.id();
                    headerProfileVersion.setText(n.id());
                    profileList.refresh();
                }
            }
        });
    }

    private VBox createServerTableSection() {
        serverTable = new TableView<>();
        serverTable.setPrefHeight(200);
        serverTable.setEditable(true);

        TableColumn<ServerEntry, String> colName = new TableColumn<>("Nombre");
        colName.setCellValueFactory(cd -> new SimpleStringProperty(cd.getValue().name));
        colName.setCellFactory(TextFieldTableCell.forTableColumn());
        colName.setOnEditCommit(ev -> { if(ev.getRowValue() != null) ev.getRowValue().name = ev.getNewValue(); });
        colName.setPrefWidth(180);

        TableColumn<ServerEntry, String> colAddr = new TableColumn<>("IP:Puerto");
        colAddr.setCellValueFactory(cd -> new SimpleStringProperty(cd.getValue().address));
        colAddr.setCellFactory(TextFieldTableCell.forTableColumn());
        colAddr.setOnEditCommit(ev -> { if(ev.getRowValue() != null) ev.getRowValue().address = ev.getNewValue(); });
        colAddr.setPrefWidth(220);

        TableColumn<ServerEntry, Boolean> colCracked = new TableColumn<>("Skins/Aternos");
        colCracked.setCellFactory(cd -> new TableCell<>() {
            @Override protected void updateItem(Boolean item, boolean empty) {
                super.updateItem(item, empty);
                if (empty || getTableRow() == null || getTableRow().getItem() == null) { setGraphic(null); }
                else {
                    ServerEntry se = getTableRow().getItem();
                    CheckBox cb = new CheckBox();
                    cb.setSelected(se.crackedServer);
                    cb.setOnAction(ev -> { se.crackedServer = cb.isSelected(); refreshAternosRowHint(se); });
                    setGraphic(cb);
                }
            }
        });

        serverTable.getColumns().addAll(List.of(colName, colAddr, colCracked));
        serverTable.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_ALL_COLUMNS);
        serverTable.setPlaceholder(new Label("⚠️ Pulsa 'Añadir' para sincronizar servidores con Minecraft"));
        
        Button addSrv = new Button("➕ Añadir");
        addSrv.setOnAction(e -> addServerToSelected());
        Button delSrv = new Button("➖ Quitar");
        delSrv.setOnAction(e -> removeSelectedServer());

        String localIp = LanFixService.getLocalIpAddress();
        Label netInfo = new Label(
            "══════════════ 🌐 INFO DE RED ══════════════\n" +
            "[NETWORK] Tu IP Local: " + localIp + "\n" +
            "[NETWORK] LAN: Para que tus amigos se conecten, abre el mundo → 'Abrir a la LAN'.\n" +
            "[NETWORK]      Ellos deben usar 'Conexión Directa' → " + localIp + ":[PUERTO_QUE_MUESTRA_EL_JUEGO]\n" +
            "[NETWORK] Aternos: Asegúrate de que tu servidor tenga el modo 'Cracked' activado\n" +
            "[NETWORK]         (Panel Aternos → Options → Cracked = ON)\n" +
            "════════════════════════════════════════════"
        );
        netInfo.setStyle("-fx-font-family: 'Consolas', 'monospace'; -fx-text-fill: #00d2ff; -fx-padding: 10 0 0 0; -fx-font-size: 11px;");

        return new VBox(5, serverTable, new HBox(8, addSrv, delSrv), netInfo);
    }

    private VBox createHintSection() {
        aternosHint = new Label();
        aternosHint.setWrapText(true);
        aternosHint.setStyle("-fx-text-fill: #888888;");
        
        modHintLabel = new Label();
        modHintLabel.setWrapText(true);
        modHintLabel.setStyle("-fx-font-weight: bold;");

        return new VBox(8, aternosHint, modHintLabel);
    }



    // --- Lógica de Negocio y Helpers ---

    private void updateHeaderTitle() {
        if (selected == null) {
            headerProfileName.setText("Ningún perfil");
            headerProfileVersion.setText("");
            return;
        }
        String name = selected.displayName != null && !selected.displayName.isBlank() ? selected.displayName : (selected.username != null ? selected.username : "Perfil Nuevo");
        headerProfileName.setText(name);
        headerProfileVersion.setText(selected.lastVersionId != null ? selected.lastVersionId : "");
    }

    private void bindProfile(LauncherProfile p) {
        selected = p;
        deleteProfileBtn.setDisable(p == null);
        if (p == null) {
            updateHeaderTitle();
            clearFields();
            return;
        }

        updateHeaderTitle();

        syncingIdentityUi = true;
        try {
            displayNameField.setText(p.displayName);
            usernameField.setText(p.username);
            uuidField.setText(p.offlineUuid);
        } finally {
            syncingIdentityUi = false;
        }

        presetCombo.setValue(p.jvmPreset);
        jvmArea.setText(p.customJvmArgs != null ? p.customJvmArgs : "");
        globalMcCheck.setSelected(p.useGlobalMinecraftFolder);
        
        if (p.servers == null) p.servers = new ArrayList<>();
        serverTable.setItems(FXCollections.observableList(p.servers));
        
        syncingVersionUi = true;
        try {
            applyVersionFilter();
            // Restaurar selección exacta del perfil
            if (p.lastVersionId != null) {
                for (ManifestVersionEntry mve : versionCombo.getItems()) {
                    if (mve.id().equals(p.lastVersionId)) {
                        versionCombo.getSelectionModel().select(mve);
                        break;
                    }
                }
            }
        } finally {
            syncingVersionUi = false;
        }
        
        if (p.offlineUuid == null || p.offlineUuid.isBlank()) syncUuidFromUsername();
        refreshHints();
        refreshStatusCard();
        refreshModList();
    }

    private void refreshStatusCard() {
        // Redundante, el cuadro fue eliminado pero mantendremos el hook dummy si algun proceso lo llama
    }

    private void clearFields() {
        displayNameField.clear();
        usernameField.clear();
        uuidField.clear();
        versionCombo.setItems(FXCollections.emptyObservableList());
    }

    private void handlePresetChange() {
        if (selected != null && presetCombo.getValue() != null) {
            selected.jvmPreset = presetCombo.getValue();
            refreshHints();
        }
    }

    private void createNewProfile() {
        LauncherProfile p = LauncherProfile.createDefault();
        
        // Generar un nombre de visualización único y no conflictivo
        int counter = 1;
        String baseName = "Nuevo Perfil";
        String candidate = baseName;
        boolean conflict = true;
        while (conflict) {
            conflict = false;
            for (LauncherProfile existing : profiles) {
                if (existing.displayName.equalsIgnoreCase(candidate)) {
                    conflict = true;
                    break;
                }
            }
            if (conflict) {
                candidate = baseName + " " + counter;
                counter++;
            }
        }
        p.displayName = candidate;
        p.instanceId = sanitizeFolderName(candidate);

        // Añadir a la lista observable de la vista (esto modifica automáticamente el 'profiles' subyacente de forma segura)
        profileList.getItems().add(p);
        runningState.put(p.id, new SimpleBooleanProperty(false));
        profileList.getSelectionModel().select(p);
        saveProfiles();
    }

    /** Muestra el overlay de confirmación sin abrir ninguna ventana secundaria. */
    private void deleteSelectedProfile() {
        if (selected == null) return;
        deleteConfirmMsg.setText("¿Estás seguro de eliminar el perfil '" + selected.displayName + "'?");
        deleteConfirmOverlay.setVisible(true);
    }

    /** Construye el overlay interno de confirmación (reemplaza Alert nativo). */
    private StackPane buildDeleteOverlay() {
        StackPane dim = new StackPane();
        dim.setStyle("-fx-background-color: rgba(0,0,0,0.55);");

        VBox card = new VBox(16);
        card.setMaxWidth(460);
        card.setMaxHeight(javafx.scene.layout.Region.USE_PREF_SIZE);
        card.setStyle("-fx-background-color: #252526; -fx-background-radius: 10; "
                + "-fx-border-radius: 10; -fx-border-color: #454545; -fx-border-width: 1; "
                + "-fx-padding: 28; -fx-effect: dropshadow(gaussian, rgba(0,0,0,0.9), 24, 0, 0, 6);");

        deleteConfirmMsg = new Label();
        deleteConfirmMsg.setStyle("-fx-text-fill: white; -fx-font-size: 15px; -fx-font-weight: bold;");
        deleteConfirmMsg.setWrapText(true);

        Label warning = new Label("⚠️ ADVERTENCIA: Los mundos, mods y configuraciones se borrarán PERMANENTEMENTE del disco.");
        warning.setWrapText(true);
        warning.setStyle("-fx-text-fill: #f0a0a0; -fx-font-size: 12px;");

        Button acceptBtn = new Button("🗑 Eliminar Permanentemente");
        acceptBtn.setStyle("-fx-background-color: #d32f2f; -fx-text-fill: white; -fx-font-weight: bold; -fx-padding: 9 20; -fx-background-radius: 5;");
        acceptBtn.setOnAction(e -> {
            deleteConfirmOverlay.setVisible(false);
            executeDeleteCurrentProfile();
        });

        Button cancelBtn = new Button("Cancelar");
        cancelBtn.setStyle("-fx-background-color: transparent; -fx-border-color: #666666; -fx-border-radius: 5; -fx-text-fill: #cccccc; -fx-padding: 9 20;");
        cancelBtn.setOnAction(e -> deleteConfirmOverlay.setVisible(false));

        HBox buttons = new HBox(12, cancelBtn, acceptBtn);
        buttons.setAlignment(Pos.CENTER_RIGHT);

        card.getChildren().addAll(deleteConfirmMsg, warning, new Separator(), buttons);
        dim.getChildren().add(card);
        return dim;
    }

    /** Lógica real de borrado, llamada tras confirmar en el overlay. */
    private void executeDeleteCurrentProfile() {
        if (selected == null) return;
        final LauncherProfile toDelete = selected;

        // Remover de la lista observable (esto modifica de forma segura el 'profiles' subyacente y actualiza la UI)
        profileList.getItems().remove(toDelete);
        selected = null;
        deleteProfileBtn.setDisable(true);
        profileList.getSelectionModel().clearSelection();
        bindProfile(null);
        headerProfileName.setText("Ningún perfil");

        workers.submit(() -> {
            try {
                facade.profiles().save(profiles);
                if (!toDelete.useGlobalMinecraftFolder) {
                    java.nio.file.Path dir = facade.gameDirFor(toDelete);
                    if (java.nio.file.Files.exists(dir)) {
                        try (var stream = java.nio.file.Files.walk(dir)) {
                            stream.sorted(java.util.Comparator.reverseOrder())
                                  .map(java.nio.file.Path::toFile)
                                  .forEach(java.io.File::delete);
                        }
                    }
                }
                Platform.runLater(() -> {
                    log("Perfil '" + toDelete.displayName + "' eliminado permanentemente.");
                    deleteProfileBtn.setDisable(false);
                });
            } catch (Exception ex) {
                Platform.runLater(() -> {
                    log("Error al borrar perfil: " + ex.getMessage());
                    deleteProfileBtn.setDisable(false);
                });
            }
        });
    }

    /** Muestra el overlay de selección de loader sin abrir ventanas secundarias. */
    private void handleInstallModloader() {
        if (selected == null || selected.lastVersionId == null || selected.lastVersionId.isBlank()) {
            log("[ERROR] Selecciona primero una versión Vanilla desde el desplegable 'Versión Juego' antes de instalar Forge/Fabric.");
            return;
        }
        modloaderMcLabel.setText("Inyección de Modloader para Minecraft " + selected.lastVersionId);
        modloaderOverlay.setVisible(true);
    }

    /** Construye el overlay de selección de Forge/Fabric/NeoForge (2 pasos). */
    private StackPane buildModloaderOverlay() {
        StackPane dim = new StackPane();
        dim.setStyle("-fx-background-color: rgba(0,0,0,0.55);");

        VBox card = new VBox(16);
        card.setMaxWidth(540);
        card.setMaxHeight(javafx.scene.layout.Region.USE_PREF_SIZE);
        card.setStyle("-fx-background-color: #252526; -fx-background-radius: 10; "
                + "-fx-border-radius: 10; -fx-border-color: #454545; -fx-border-width: 1; "
                + "-fx-padding: 28; -fx-effect: dropshadow(gaussian, rgba(0,0,0,0.9), 24, 0, 0, 6);");

        // ── PASO 1: Elegir tipo de modloader ──────────────────────────────────
        modloaderStep1 = new VBox(14);

        Label titleLbl = new Label("✨ Instalar Modloader");
        titleLbl.setStyle("-fx-text-fill: white; -fx-font-size: 17px; -fx-font-weight: bold;");

        modloaderMcLabel = new Label();
        modloaderMcLabel.setStyle("-fx-text-fill: #cccccc; -fx-font-size: 13px;");

        Label hint = new Label("Elige el motor de mods — podrás seleccionar la versión exacta:");
        hint.setStyle("-fx-text-fill: #888888; -fx-font-size: 12px;");
        hint.setWrapText(true);

        Button forgeBtn = new Button("⚙  Forge");
        forgeBtn.setPrefWidth(125);
        forgeBtn.setStyle("-fx-background-color: #b07833; -fx-text-fill: white; -fx-font-weight: bold; -fx-padding: 11 12; -fx-background-radius: 6;");
        forgeBtn.setOnAction(e -> showModloaderStep2("Forge"));

        Button neoforgeBtn = new Button("🔥  NeoForge");
        neoforgeBtn.setPrefWidth(125);
        neoforgeBtn.setStyle("-fx-background-color: #c0522a; -fx-text-fill: white; -fx-font-weight: bold; -fx-padding: 11 12; -fx-background-radius: 6;");
        neoforgeBtn.setOnAction(e -> showModloaderStep2("NeoForge"));

        Button fabricBtn = new Button("🪡  Fabric");
        fabricBtn.setPrefWidth(125);
        fabricBtn.setStyle("-fx-background-color: #4a7c40; -fx-text-fill: white; -fx-font-weight: bold; -fx-padding: 11 12; -fx-background-radius: 6;");
        fabricBtn.setOnAction(e -> showModloaderStep2("Fabric"));

        Button quiltBtn = new Button("🧵  Quilt");
        quiltBtn.setPrefWidth(125);
        quiltBtn.setStyle("-fx-background-color: #5c3d75; -fx-text-fill: white; -fx-font-weight: bold; -fx-padding: 11 12; -fx-background-radius: 6;");
        quiltBtn.setOnAction(e -> showModloaderStep2("Quilt"));

        Label forgeNote = new Label("Forge: 1.12.2–1.20.1  ·  NeoForge: 1.20.2+  ·  Fabric / Quilt: 1.14+ (compatible con mods Fabric)");
        forgeNote.setStyle("-fx-text-fill: #555555; -fx-font-size: 11px;");
        forgeNote.setWrapText(true);

        Button cancelBtn1 = new Button("Cancelar");
        cancelBtn1.setStyle("-fx-background-color: transparent; -fx-border-color: #555555; -fx-border-radius: 5; -fx-text-fill: #aaaaaa; -fx-padding: 8 16;");
        cancelBtn1.setOnAction(e -> modloaderOverlay.setVisible(false));

        HBox loaderBtns = new HBox(8, forgeBtn, neoforgeBtn, fabricBtn, quiltBtn);
        loaderBtns.setAlignment(Pos.CENTER);
        HBox cancel1Row = new HBox(cancelBtn1);
        cancel1Row.setAlignment(Pos.CENTER_RIGHT);

        modloaderStep1.getChildren().addAll(titleLbl, modloaderMcLabel, hint, new Separator(), loaderBtns, forgeNote, cancel1Row);

        // ── PASO 2: Elegir versión específica ─────────────────────────────────
        modloaderStep2 = new VBox(14);
        modloaderStep2.setVisible(false);
        modloaderStep2.setManaged(false);

        modloaderStep2Title = new Label();
        modloaderStep2Title.setStyle("-fx-text-fill: white; -fx-font-size: 16px; -fx-font-weight: bold;");

        Label step2Hint = new Label("Selecciona la versión a instalar (más reciente primero):");
        step2Hint.setStyle("-fx-text-fill: #888888; -fx-font-size: 12px;");

        modloaderVersionCombo = new ComboBox<>();
        modloaderVersionCombo.setMaxWidth(Double.MAX_VALUE);
        modloaderVersionCombo.setPromptText("Cargando versiones...");

        modloaderVersionSpinner = new javafx.scene.control.ProgressIndicator(-1);
        modloaderVersionSpinner.setPrefSize(22, 22);
        modloaderVersionSpinner.setVisible(true);

        HBox comboRow = new HBox(10, modloaderVersionCombo, modloaderVersionSpinner);
        comboRow.setAlignment(Pos.CENTER_LEFT);
        HBox.setHgrow(modloaderVersionCombo, Priority.ALWAYS);

        Button installSpecificBtn = new Button("✅ Instalar versión seleccionada");
        installSpecificBtn.setStyle("-fx-background-color: #0E639C; -fx-text-fill: white; -fx-font-weight: bold; -fx-padding: 10 20; -fx-background-radius: 6;");
        installSpecificBtn.setMaxWidth(Double.MAX_VALUE);
        installSpecificBtn.setOnAction(e -> {
            String ver = modloaderVersionCombo.getValue();
            if (ver == null || ver.isBlank()) {
                log("[Modloader] Selecciona una versión primero.");
                return;
            }
            modloaderOverlay.setVisible(false);
            resetModloaderOverlayToStep1();
            executeInstallModloaderSpecific(currentSelectedLoaderType, ver);
        });

        Button backBtn = new Button("← Volver");
        backBtn.setStyle("-fx-background-color: transparent; -fx-border-color: #555555; -fx-border-radius: 5; -fx-text-fill: #aaaaaa; -fx-padding: 8 16;");
        backBtn.setOnAction(e -> resetModloaderOverlayToStep1());

        Button cancelBtn2 = new Button("Cancelar");
        cancelBtn2.setStyle("-fx-background-color: transparent; -fx-text-fill: #666666; -fx-padding: 8 16;");
        cancelBtn2.setOnAction(e -> { modloaderOverlay.setVisible(false); resetModloaderOverlayToStep1(); });

        HBox step2Btns = new HBox(10, backBtn, cancelBtn2);
        step2Btns.setAlignment(Pos.CENTER_RIGHT);

        modloaderStep2.getChildren().addAll(modloaderStep2Title, step2Hint, comboRow, new Separator(), installSpecificBtn, step2Btns);

        card.getChildren().addAll(modloaderStep1, modloaderStep2);
        dim.getChildren().add(card);
        return dim;
    }

    private void showModloaderStep2(String loaderType) {
        currentSelectedLoaderType = loaderType;
        String mcVersion = (selected != null && selected.lastVersionId != null) ? selected.lastVersionId : "?";
        modloaderStep2Title.setText("Instalar " + loaderType + " para Minecraft " + mcVersion);
        modloaderStep1.setVisible(false);
        modloaderStep1.setManaged(false);
        modloaderStep2.setVisible(true);
        modloaderStep2.setManaged(true);
        modloaderVersionCombo.getItems().clear();
        modloaderVersionCombo.setPromptText("Cargando versiones...");
        modloaderVersionSpinner.setVisible(true);
        workers.submit(() -> {
            try {
                List<String> versions = switch (loaderType) {
                    case "Forge" -> com.experimento.launcher.modloaders.ModloaderVersionService.getForgeVersions(mcVersion);
                    case "Fabric" -> {
                        if (!com.experimento.launcher.modloaders.ModloaderVersionService.isFabricSupported(mcVersion)) {
                            throw new Exception("Fabric no soporta Minecraft " + mcVersion + ". Usa Forge para versiones antiguas (1.14-).");
                        }
                        yield com.experimento.launcher.modloaders.ModloaderVersionService.getFabricLoaderVersions(mcVersion);
                    }
                    case "Quilt" -> {
                        if (!com.experimento.launcher.modloaders.ModloaderVersionService.isQuiltSupported(mcVersion)) {
                            throw new Exception("Quilt no soporta Minecraft " + mcVersion + ". Usa Forge para versiones antiguas (1.14-).");
                        }
                        yield com.experimento.launcher.modloaders.ModloaderVersionService.getQuiltLoaderVersions(mcVersion);
                    }
                    case "NeoForge" -> com.experimento.launcher.modloaders.ModloaderVersionService.getNeoForgeVersions(mcVersion);
                    default -> List.of();
                };
                Platform.runLater(() -> {
                    modloaderVersionSpinner.setVisible(false);
                    modloaderVersionCombo.getItems().addAll(versions);
                    if (!versions.isEmpty()) {
                        modloaderVersionCombo.getSelectionModel().selectFirst();
                        modloaderVersionCombo.setPromptText(null);
                    } else {
                        modloaderVersionCombo.setPromptText("Sin versiones disponibles para " + mcVersion);
                    }
                });
            } catch (Exception ex) {
                Platform.runLater(() -> {
                    modloaderVersionSpinner.setVisible(false);
                    modloaderVersionCombo.setPromptText("Error al cargar versiones");
                    log("[Modloader] Error cargando versiones de " + loaderType + ": " + ex.getMessage());
                });
            }
        });
    }

    private void resetModloaderOverlayToStep1() {
        modloaderStep1.setVisible(true);
        modloaderStep1.setManaged(true);
        modloaderStep2.setVisible(false);
        modloaderStep2.setManaged(false);
        modloaderVersionCombo.getItems().clear();
    }

    /** Instala la versión específica elegida en el ComboBox. */
    private void executeInstallModloaderSpecific(String choice, String specificVersion) {
        if (selected == null) return;
        String mcVersion = selected.lastVersionId;
        Path baseDir = facade.directories().launcherData();
        log("Iniciando instalación de " + choice + " " + specificVersion + " para Minecraft " + mcVersion + "...");
        showView("Log");
        workers.submit(() -> {
            try {
                var runtime = facade.runtime();
                switch (choice) {
                    case "Forge" -> ModloaderInstallerService.installForgeSpecific(mcVersion, specificVersion, baseDir,
                            msg -> Platform.runLater(() -> log(msg)), runtime);
                    case "NeoForge" -> ModloaderInstallerService.installNeoForgeSpecific(mcVersion, specificVersion, baseDir,
                            msg -> Platform.runLater(() -> log(msg)), runtime);
                    case "Fabric" -> ModloaderInstallerService.installFabricSpecific(mcVersion, specificVersion, baseDir,
                            msg -> Platform.runLater(() -> log(msg)), runtime);
                    case "Quilt" -> ModloaderInstallerService.installQuiltSpecific(mcVersion, specificVersion, baseDir,
                            msg -> Platform.runLater(() -> log(msg)), runtime);
                }
                Platform.runLater(() -> {
                    log("✅ " + choice + " " + specificVersion + " instalado correctamente.");
                    if (selected != null) {
                        selected.modLoader = choice.toLowerCase();
                        saveProfiles();
                        refreshHints();
                    }
                    loadVersionManifestAsync();
                });
            } catch (Exception ex) {
                Platform.runLater(() -> log("[CRITICAL] Falló la instalación de " + choice + " " + specificVersion + ": " + ex.getMessage()));
            }
        });
    }

    /** Construye el overlay de selección de versión específica para mods de la tienda. */
    private StackPane buildModVersionOverlay() {
        StackPane dim = new StackPane();
        dim.setStyle("-fx-background-color: rgba(0,0,0,0.55);");

        VBox card = new VBox(16);
        card.setMaxWidth(580);
        card.setMaxHeight(javafx.scene.layout.Region.USE_PREF_SIZE);
        card.setStyle("-fx-background-color: #252526; -fx-background-radius: 10; "
                + "-fx-border-radius: 10; -fx-border-color: #454545; -fx-border-width: 1; "
                + "-fx-padding: 28; -fx-effect: dropshadow(gaussian, rgba(0,0,0,0.9), 24, 0, 0, 6);");

        // ── PASO 1: Mostrar información del mod y botón para ver versiones ──────────────────────────────────
        modVersionStep1 = new VBox(14);

        Label titleLbl = new Label("📦 Instalar Mod");
        titleLbl.setStyle("-fx-text-fill: white; -fx-font-size: 17px; -fx-font-weight: bold;");

        modVersionModLabel = new Label();
        modVersionModLabel.setStyle("-fx-text-fill: #cccccc; -fx-font-size: 13px;");

        Label hint = new Label("Selecciona cómo quieres instalar el mod:");
        hint.setStyle("-fx-text-fill: #888888; -fx-font-size: 12px;");
        hint.setWrapText(true);

        Button quickInstallBtn = new Button("⚡ Instalar Última Versión");
        quickInstallBtn.setPrefWidth(220);
        quickInstallBtn.setStyle("-fx-background-color: #2e7d32; -fx-text-fill: white; -fx-font-weight: bold; -fx-padding: 11 14; -fx-background-radius: 6;");
        quickInstallBtn.setOnAction(e -> executeQuickInstallMod());

        Button viewVersionsBtn = new Button("🔽 Ver Todas las Versiones");
        viewVersionsBtn.setPrefWidth(220);
        viewVersionsBtn.setStyle("-fx-background-color: #0E639C; -fx-text-fill: white; -fx-font-weight: bold; -fx-padding: 11 14; -fx-background-radius: 6;");
        viewVersionsBtn.setOnAction(e -> showModVersionStep2());

        Label note = new Label("Elige 'Última Versión' para instalar rápidamente, o 'Ver Todas' para seleccionar una versión específica.");
        note.setStyle("-fx-text-fill: #555555; -fx-font-size: 11px;");
        note.setWrapText(true);

        Button cancelBtn1 = new Button("Cancelar");
        cancelBtn1.setStyle("-fx-background-color: transparent; -fx-border-color: #555555; -fx-border-radius: 5; -fx-text-fill: #aaaaaa; -fx-padding: 8 16;");
        cancelBtn1.setOnAction(e -> modVersionOverlay.setVisible(false));

        VBox btnBox = new VBox(10, quickInstallBtn, viewVersionsBtn);
        btnBox.setAlignment(Pos.CENTER);
        HBox cancel1Row = new HBox(cancelBtn1);
        cancel1Row.setAlignment(Pos.CENTER_RIGHT);

        modVersionStep1.getChildren().addAll(titleLbl, modVersionModLabel, hint, new Separator(), btnBox, note, cancel1Row);

        // ── PASO 2: Elegir versión específica ─────────────────────────────────
        modVersionStep2 = new VBox(14);
        modVersionStep2.setVisible(false);
        modVersionStep2.setManaged(false);

        modVersionStep2Title = new Label();
        modVersionStep2Title.setStyle("-fx-text-fill: white; -fx-font-size: 16px; -fx-font-weight: bold;");

        Label step2Hint = new Label("Selecciona la versión a instalar:");
        step2Hint.setStyle("-fx-text-fill: #888888; -fx-font-size: 12px;");

        modVersionCombo = new ComboBox<>();
        modVersionCombo.setMaxWidth(Double.MAX_VALUE);
        modVersionCombo.setPromptText("Cargando versiones...");
        // Custom cell factory to show version info
        modVersionCombo.setCellFactory(lv -> new ListCell<com.experimento.launcher.store.ModVersion>() {
            @Override
            protected void updateItem(com.experimento.launcher.store.ModVersion item, boolean empty) {
                super.updateItem(item, empty);
                if (empty || item == null) {
                    setText(null);
                } else {
                    String mcVers = String.join(", ", item.gameVersions());
                    String loaders = String.join(", ", item.loaders());
                    setText(item.versionNumber() + " (MC: " + mcVers + ", " + loaders + ")");
                }
            }
        });
        modVersionCombo.setButtonCell(new ListCell<com.experimento.launcher.store.ModVersion>() {
            @Override
            protected void updateItem(com.experimento.launcher.store.ModVersion item, boolean empty) {
                super.updateItem(item, empty);
                if (empty || item == null) {
                    setText(modVersionCombo.getPromptText());
                } else {
                    String mcVers = String.join(", ", item.gameVersions());
                    String loaders = String.join(", ", item.loaders());
                    setText(item.versionNumber() + " (MC: " + mcVers + ", " + loaders + ")");
                }
            }
        });

        modVersionSpinner = new javafx.scene.control.ProgressIndicator(-1);
        modVersionSpinner.setPrefSize(22, 22);
        modVersionSpinner.setVisible(true);

        HBox comboRow = new HBox(10, modVersionCombo, modVersionSpinner);
        comboRow.setAlignment(Pos.CENTER_LEFT);
        HBox.setHgrow(modVersionCombo, Priority.ALWAYS);

        installSpecificVersionBtn = new Button("✅ Instalar versión seleccionada");
        installSpecificVersionBtn.setStyle("-fx-background-color: #0E639C; -fx-text-fill: white; -fx-font-weight: bold; -fx-padding: 10 20; -fx-background-radius: 6;");
        installSpecificVersionBtn.setMaxWidth(Double.MAX_VALUE);
        installSpecificVersionBtn.setOnAction(e -> executeInstallSpecificModVersion());

        Button backBtn = new Button("← Volver");
        backBtn.setStyle("-fx-background-color: transparent; -fx-border-color: #555555; -fx-border-radius: 5; -fx-text-fill: #aaaaaa; -fx-padding: 8 16;");
        backBtn.setOnAction(e -> resetModVersionOverlayToStep1());

        Button cancelBtn2 = new Button("Cancelar");
        cancelBtn2.setStyle("-fx-background-color: transparent; -fx-text-fill: #666666; -fx-padding: 8 16;");
        cancelBtn2.setOnAction(e -> { modVersionOverlay.setVisible(false); resetModVersionOverlayToStep1(); });

        HBox step2Btns = new HBox(10, backBtn, cancelBtn2);
        step2Btns.setAlignment(Pos.CENTER_RIGHT);

        modVersionStep2.getChildren().addAll(modVersionStep2Title, step2Hint, comboRow, new Separator(), installSpecificVersionBtn, step2Btns);

        card.getChildren().addAll(modVersionStep1, modVersionStep2);
        dim.getChildren().add(card);
        return dim;
    }

    private void showModVersionStep2() {
        if (currentSelectedStoreItem == null || selected == null) return;
        
        String mcVersion = (selected.lastVersionId != null) ? selected.lastVersionId : "";
        String loader = (selected.modLoader != null) ? selected.modLoader.toLowerCase() : null;
        
        modVersionStep2Title.setText("Seleccionar versión de " + currentSelectedStoreItem.title());
        modVersionStep1.setVisible(false);
        modVersionStep1.setManaged(false);
        modVersionStep2.setVisible(true);
        modVersionStep2.setManaged(true);
        modVersionCombo.getItems().clear();
        modVersionCombo.setPromptText("Cargando versiones...");
        modVersionSpinner.setVisible(true);
        installSpecificVersionBtn.setDisable(true);
        
        workers.submit(() -> {
            try {
                currentModVersions = com.experimento.launcher.store.ModrinthStoreClient.getModVersions(
                    currentSelectedStoreItem.id(), mcVersion, loader);
                
                Platform.runLater(() -> {
                    modVersionSpinner.setVisible(false);
                    modVersionCombo.getItems().addAll(currentModVersions);
                    if (!currentModVersions.isEmpty()) {
                        modVersionCombo.getSelectionModel().selectFirst();
                        modVersionCombo.setPromptText(null);
                        installSpecificVersionBtn.setDisable(false);
                    } else {
                        modVersionCombo.setPromptText("Sin versiones compatibles encontradas");
                        // Intentar sin filtros
                        workers.submit(() -> {
                            try {
                                var allVersions = com.experimento.launcher.store.ModrinthStoreClient.getModVersions(
                                    currentSelectedStoreItem.id(), null, null);
                                Platform.runLater(() -> {
                                    currentModVersions = allVersions;
                                    modVersionCombo.getItems().addAll(allVersions);
                                    if (!allVersions.isEmpty()) {
                                        modVersionCombo.getSelectionModel().selectFirst();
                                        installSpecificVersionBtn.setDisable(false);
                                        modVersionCombo.setPromptText(null);
                                    } else {
                                        modVersionCombo.setPromptText("No hay versiones disponibles");
                                    }
                                });
                            } catch (Exception ex) {
                                Platform.runLater(() -> modVersionCombo.setPromptText("Error al cargar"));
                            }
                        });
                    }
                });
            } catch (Exception ex) {
                Platform.runLater(() -> {
                    modVersionSpinner.setVisible(false);
                    modVersionCombo.setPromptText("Error al cargar versiones");
                    log("[Store] Error cargando versiones: " + ex.getMessage());
                });
            }
        });
    }

    private void resetModVersionOverlayToStep1() {
        modVersionStep1.setVisible(true);
        modVersionStep1.setManaged(true);
        modVersionStep2.setVisible(false);
        modVersionStep2.setManaged(false);
        modVersionCombo.getItems().clear();
        currentModVersions = null;
    }

    /** Instala la versión específica del mod seleccionada en el ComboBox. */
    private void executeInstallSpecificModVersion() {
        if (selected == null || currentSelectedStoreItem == null) return;
        
        com.experimento.launcher.store.ModVersion version = modVersionCombo.getValue();
        if (version == null) {
            log("[Store] Selecciona una versión primero.");
            return;
        }
        
        modVersionOverlay.setVisible(false);
        resetModVersionOverlayToStep1();
        
        Path gameDir = facade.gameDirFor(selected);
        log("[Store] Instalando " + currentSelectedStoreItem.title() + " " + version.versionNumber() + "...");
        showView("Log");
        
        workers.submit(() -> {
            try {
                com.experimento.launcher.store.StoreDownloader.installSpecificVersion(
                    currentSelectedStoreItem, version, gameDir,
                    msg -> Platform.runLater(() -> log(msg)));
                Platform.runLater(() -> {
                    log("[Store] ✅ " + currentSelectedStoreItem.title() + " " + version.versionNumber() + " instalado correctamente.");
                    refreshModList();
                });
            } catch (Exception ex) {
                Platform.runLater(() -> log("[Store] Error: " + ex.getMessage()));
            }
        });
    }

    /** Instala la última versión compatible del mod (instalación rápida). */
    private void executeQuickInstallMod() {
        if (selected == null || currentSelectedStoreItem == null) return;
        
        modVersionOverlay.setVisible(false);
        resetModVersionOverlayToStep1();
        
        Path gameDir = facade.gameDirFor(selected);
        String mcVersion = selected.lastVersionId;
        String loader = selected.modLoader;
        
        log("[Store] Instalando última versión de " + currentSelectedStoreItem.title() + "...");
        showView("Log");
        
        workers.submit(() -> {
            try {
                com.experimento.launcher.store.ModpackDependencies deps = 
                    com.experimento.launcher.store.StoreDownloader.install(
                        currentSelectedStoreItem, gameDir, mcVersion, loader,
                        msg -> Platform.runLater(() -> log("[STORE] " + msg)));
                
                if (deps != null) {
                    Platform.runLater(() -> {
                        log("[STORE] Modpack requiere " + deps.mcVersion() + " con " + deps.loader() + ". Configurando automáticamente...");
                        autoConfigureModpack(deps);
                    });
                } else {
                    Platform.runLater(() -> {
                        log("[Store] ✅ " + currentSelectedStoreItem.title() + " instalado correctamente.");
                        refreshModList();
                    });
                }
            } catch (Exception ex) {
                Platform.runLater(() -> log("[Store] Error: " + ex.getMessage()));
            }
        });
    }

    /**
     * Configura automáticamente las dependencias de un modpack recién instalado.
     */
    private void autoConfigureModpack(ModpackDependencies deps) {
        if (deps == null || deps.mcVersion() == null) return;
        
        workers.submit(() -> {
            try {
                String mcVer = deps.mcVersion();
                String loader = deps.loader();

                // 1. Instalar base vanilla si no existe
                Platform.runLater(() -> log("[STORE] Instalando Minecraft " + mcVer + "..."));
                facade.installVersion(mcVer, s -> Platform.runLater(() -> log("[AUTO] " + s)));

                // 2. Instalar loader si se requiere
                if (loader != null && !loader.equalsIgnoreCase("vanilla")) {
                    Platform.runLater(() -> log("[STORE] Inyectando " + loader + "..."));
                    Path baseDir = facade.directories().launcherData();
                    
                    var runtime = facade.runtime();
                    if (loader.equalsIgnoreCase("forge")) {
                        ModloaderInstallerService.installForge(mcVer, baseDir,
                            s -> Platform.runLater(() -> log("[AUTO-FORGE] " + s)), runtime);
                    } else if (loader.equalsIgnoreCase("neoforge")) {
                        ModloaderInstallerService.installNeoForge(mcVer, baseDir,
                            s -> Platform.runLater(() -> log("[AUTO-NEOFORGE] " + s)), runtime);
                    } else if (loader.equalsIgnoreCase("fabric")) {
                        ModloaderInstallerService.installFabric(mcVer, baseDir,
                            s -> Platform.runLater(() -> log("[AUTO-FABRIC] " + s)), runtime);
                    } else if (loader.equalsIgnoreCase("quilt")) {
                        ModloaderInstallerService.installQuilt(mcVer, baseDir,
                            s -> Platform.runLater(() -> log("[AUTO-QUILT] " + s)), runtime);
                    }

                    // Recargar manifiesto para encontrar el nuevo ID
                    Platform.runLater(() -> {
                        log("[STORE] Actualizando lista de versiones...");
                        loadVersionManifestAsync(() -> finalizeModpackSetup(mcVer, loader));
                    });
                } else {
                    // Solo vanilla
                    Platform.runLater(() -> finalizeModpackSetup(mcVer, null));
                }

            } catch (Exception ex) {
                Platform.runLater(() -> log("[STORE] Error en auto-config: " + ex.getMessage()));
            }
        });
    }

    private void finalizeModpackSetup(String mcVer, String loader) {
        if (selected == null) return;
        
        // Buscar en la lista de versiones filtradas la que mejor coincida
        String match = mcVer;
        if (loader != null) {
            String target = loader.toLowerCase();
            for (ManifestVersionEntry v : versionCombo.getItems()) {
                if (v.id().toLowerCase().contains(target) && v.id().contains(mcVer)) {
                    match = v.id();
                    break;
                }
            }
        }
        
        selected.lastVersionId = match;
        // Guardar el loader en el perfil para que los mods de rendimiento funcionen
        if (loader != null && !loader.equalsIgnoreCase("vanilla")) {
            selected.modLoader = loader.toLowerCase();
        }
        // Seleccionar en el combo si está presente
        for (ManifestVersionEntry v : versionCombo.getItems()) {
            if (v.id().equals(match)) {
                versionCombo.getSelectionModel().select(v);
                break;
            }
        }
        saveProfiles();
        applyVersionFilter(); // Refresca UI
        log("[STORE] ✅ Perfil auto-configurado para: " + match + " | Loader: " + (loader != null ? loader : "vanilla"));
        
        // Si estamos en la vista General, forzar refresco
        if ("General".equals(currentViewTitle)) showView("General");
    }

    /** Construye el overlay de actualización. */
    private HBox buildUpdateBanner() {
        HBox banner = new HBox(15);
        banner.setAlignment(Pos.CENTER_LEFT);
        banner.setPadding(new Insets(10, 20, 10, 20));
        banner.setStyle("-fx-background-color: #0E639C; -fx-border-color: transparent transparent #1177BB transparent; -fx-border-width: 0 0 1 0;");

        updateStatus = new Label("Actualización disponible");
        updateStatus.setStyle("-fx-text-fill: white; -fx-font-weight: bold;");

        updateProgress = new ProgressBar(0);
        updateProgress.setPrefWidth(200);
        updateProgress.setMaxHeight(10);
        updateProgress.setStyle("-fx-accent: #ffffff;");

        updateBtn = new Button("Actualizar");
        updateBtn.setStyle("-fx-background-color: white; -fx-text-fill: #0E639C; -fx-font-size: 11px; -fx-padding: 4 12;");

        Button closeBtn = new Button("✕");
        closeBtn.setStyle("-fx-background-color: transparent; -fx-text-fill: white; -fx-font-size: 14px; -fx-padding: 0 5;");
        closeBtn.setOnAction(e -> {
            updateBanner.setVisible(false);
            updateBanner.setManaged(false);
        });

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);

        banner.getChildren().addAll(updateStatus, updateProgress, spacer, updateBtn, closeBtn);
        return banner;
    }

    private void handlePlayClick() {
        if (selected == null) return;
        if (installing.get()) {
            log("[LAUNCHER] Espera a que termine la instalación antes de jugar.");
            return;
        }
        
        // Desactivar UI mientras validamos
        playBtn.setDisable(true);
        playBtn.setText("Validando...");
        
        workers.submit(() -> {
            try {
                // 1. Cargar el version.json para ver si requiere Java 8
                Path jsonPath = facade.directories().versionsDir().resolve(selected.lastVersionId).resolve("version.json");
                if (!java.nio.file.Files.exists(jsonPath)) {
                    // Si no existe, tenemos que instalar la versión primero
                    Platform.runLater(() -> {
                        playBtn.setDisable(false);
                        playBtn.setText("▶ ¡JUGAR!");
                        runTask(createLaunchTask());
                    });
                    return;
                }
                
                JsonNode merged = new ObjectMapper().readTree(java.nio.file.Files.readAllBytes(jsonPath));
                int requiredJava = 0;
                if (merged.has("javaVersion")) {
                    requiredJava = merged.get("javaVersion").path("majorVersion").asInt(0);
                } else {
                    String main = merged.path("mainClass").asText("").toLowerCase();
                    if (main.contains("launchwrapper") || main.contains("fml") || main.contains("forge") || selected.lastVersionId.toLowerCase().contains("1.12.2")) {
                        requiredJava = 8;
                    }
                }

                if (requiredJava == 0 || requiredJava > 17) {
                    String vid = selected.lastVersionId;
                    if (VersionComparator.isVersionAtLeast(vid, 1, 20, 5)) {
                        requiredJava = 21;
                    } else if (VersionComparator.isVersionAtLeast(vid, 1, 17, 0)) {
                        requiredJava = 17;
                    }
                }

                if (requiredJava == 8 || requiredJava == 17 || requiredJava == 21) {
                    final int ver = requiredJava;
                    if (facade.runtime().getExecutable(ver) == null) {
                        Platform.runLater(() -> {
                            detectedJavaVersion = ver;
                            javaStatus.setText("Esta versión requiere Java " + ver + " para funcionar correctamente.");
                            javaProgress.setProgress(0);
                            javaDownloadOverlay.setVisible(true);
                            playBtn.setDisable(false);
                            playBtn.setText("▶ ¡JUGAR!");
                        });
                        return;
                    }
                }

                Platform.runLater(() -> {
                    playBtn.setDisable(false);
                    playBtn.setText("▶ ¡JUGAR!");
                    runTask(createLaunchTask());
                });

            } catch (Exception ex) {
                Platform.runLater(() -> {
                    log("Error verificando Java: " + ex.getMessage());
                    playBtn.setDisable(false);
                    playBtn.setText("▶ ¡JUGAR!");
                });
            }
        });
    }

    /** Construye el overlay de descarga de Java. */
    private StackPane buildJavaDownloadOverlay() {
        StackPane dim = new StackPane();
        dim.setStyle("-fx-background-color: rgba(0,0,0,0.65);");

        VBox card = new VBox(20);
        card.setMaxWidth(480);
        card.setMaxHeight(javafx.scene.layout.Region.USE_PREF_SIZE);
        card.getStyleClass().add("mc-card");
        card.setStyle(card.getStyle() + "; -fx-border-color: #0E639C; -fx-border-width: 2;");

        Label title = new Label("☕ Motor Java Requerido");
        title.setStyle("-fx-text-fill: white; -fx-font-size: 20px; -fx-font-weight: bold;");

        javaStatus = new Label("Detectamos que esta versión de Minecraft necesita Java.");
        javaStatus.setWrapText(true);
        javaStatus.setStyle("-fx-text-fill: #cccccc; -fx-font-size: 14px;");

        Label javaInfoLabel = new Label(
            "Java 8: Minecraft 1.8–1.16  |  Java 17: Minecraft 1.17–1.20.4  |  Java 21: Minecraft 1.20.5+");
        javaInfoLabel.setStyle("-fx-text-fill: #888888; -fx-font-size: 11px;");
        javaInfoLabel.setWrapText(true);

        javaProgress = new ProgressBar(0);
        javaProgress.setMaxWidth(Double.MAX_VALUE);
        javaProgress.setStyle("-fx-accent: #0E639C;");

        javaProgressLabel = new Label("0%");
        javaProgressLabel.setStyle("-fx-text-fill: #0E639C; -fx-font-size: 12px;");

        final Button downloadBtn = new Button("✅ Descargar Java " + (detectedJavaVersion > 0 ? detectedJavaVersion : "Recomendado") + " Portátil");
        downloadBtn.getStyleClass().add("button-primary");
        downloadBtn.setPrefWidth(300);

        final Button download21Btn = new Button("⚡ Descargar Java 21 (NeoForge / Modpacks modernos)");
        download21Btn.setStyle("-fx-background-color: #c0522a; -fx-text-fill: white; -fx-font-weight: bold; -fx-padding: 8 12;");
        download21Btn.setPrefWidth(300);

        downloadBtn.setOnAction(e -> {
            downloadBtn.setDisable(true);
            download21Btn.setDisable(true);
            if (javaDownloadCloseBtn != null) javaDownloadCloseBtn.setDisable(true);
            updateJavaDownloadStatus("Descargando Java " + (detectedJavaVersion > 0 ? detectedJavaVersion : 17) + "...", 0);
            runTask(createJavaDownloadTask(detectedJavaVersion > 0 ? detectedJavaVersion : 17, downloadBtn, download21Btn));
        });

        download21Btn.setOnAction(e -> {
            downloadBtn.setDisable(true);
            download21Btn.setDisable(true);
            if (javaDownloadCloseBtn != null) javaDownloadCloseBtn.setDisable(true);
            updateJavaDownloadStatus("Descargando Java 21...", 0);
            runTask(createJavaDownloadTask(21, downloadBtn, download21Btn));
        });

        javaDownloadCloseBtn = new Button("Cancelar");
        javaDownloadCloseBtn.setStyle("-fx-background-color: transparent; -fx-text-fill: #888888;");
        javaDownloadCloseBtn.setOnAction(e -> javaDownloadOverlay.setVisible(false));

        card.getChildren().addAll(title, javaStatus, javaInfoLabel, javaProgress, javaProgressLabel, downloadBtn, download21Btn, javaDownloadCloseBtn);
        dim.getChildren().add(card);
        return dim;
    }

    private javafx.concurrent.Task<Void> createJavaDownloadTask(int version, Button downloadBtn, Button download21Btn) {
        return new javafx.concurrent.Task<>() {
            @Override
            protected Void call() throws Exception {
                Platform.runLater(() -> updateJavaDownloadStatus("Iniciando descarga de Java " + version + "...", 0));

                Path path = facade.runtime().downloadJavaSync(version, p -> {
                    Platform.runLater(() -> updateJavaDownloadStatus(null, p));
                });

                if (path != null) {
                    Platform.runLater(() -> {
                        log("Java " + version + " Portable instalado en: " + path);
                        javaDownloadOverlay.setVisible(false);
                        closeJavaDownloadOverlay(downloadBtn, download21Btn);
                        new Alert(Alert.AlertType.INFORMATION, "Java " + version + " se ha instalado correctamente.").show();
                    });
                } else {
                    throw new Exception("No se pudo encontrar el ejecutable tras la extracción.");
                }
                return null;
            }

            @Override
            protected void failed() {
                Platform.runLater(() -> {
                    closeJavaDownloadOverlay(downloadBtn, download21Btn);
                    log("Error descargando Java " + version + ": " + getException().getMessage());
                    new Alert(Alert.AlertType.ERROR, "Error al descargar Java: " + getException().getMessage()).show();
                });
            }
        };
    }

    private void updateJavaDownloadStatus(String message, double progress) {
        if (message != null && javaStatus != null) {
            javaStatus.setText(message);
        }
        if (javaProgress != null) {
            if (progress < 0) {
                javaProgress.setProgress(-1);
                if (javaProgressLabel != null) javaProgressLabel.setText("Descargando...");
            } else {
                javaProgress.setProgress(progress);
                if (javaProgressLabel != null) javaProgressLabel.setText(String.format("%.0f%%", progress * 100));
            }
        }
    }

    private void closeJavaDownloadOverlay(Button downloadBtn, Button download21Btn) {
        if (javaDownloadOverlay != null) {
            javaDownloadOverlay.setVisible(false);
        }
        if (downloadBtn != null) {
            downloadBtn.setDisable(false);
        }
        if (download21Btn != null) {
            download21Btn.setDisable(false);
        }
        if (javaDownloadCloseBtn != null) {
            javaDownloadCloseBtn.setDisable(false);
        }
    }

    private void saveProfiles() {
        try {
            if (selected != null && (selected.offlineUuid == null || selected.offlineUuid.isBlank())) syncUuidFromUsername();

            // Renombrar carpetas de perfiles según su displayName
            for (LauncherProfile p : profiles) {
                if (p.useGlobalMinecraftFolder) continue;

                // Si el perfil está corriendo, no podemos renombrar su carpeta
                BooleanProperty runningProp = runningState.get(p.id);
                if (runningProp != null && runningProp.get()) {
                    continue;
                }

                String safeName = sanitizeFolderName(p.displayName);
                if (!safeName.equals(p.instanceId)) {
                    java.nio.file.Path oldDir = facade.directories().instanceGameDir(p.instanceId);

                    // Encontrar un nombre de carpeta que no esté en uso por otro perfil
                    int counter = 1;
                    String candidate = safeName;
                    while (java.nio.file.Files.exists(facade.directories().instanceGameDir(candidate)) 
                           && !candidate.equals(p.instanceId)) {
                        candidate = safeName + "_" + counter;
                        counter++;
                    }

                    if (!candidate.equals(p.instanceId)) {
                        java.nio.file.Path newDir = facade.directories().instanceGameDir(candidate);
                        boolean success = true;
                        if (java.nio.file.Files.exists(oldDir)) {
                            try {
                                java.nio.file.Files.move(oldDir, newDir);
                                log("[LAUNCHER] Carpeta de perfil renombrada de '" + p.instanceId + "' a '" + candidate + "'");
                            } catch (Exception moveEx) {
                                success = false;
                                log("No se pudo renombrar la carpeta de '" + p.instanceId + "': " + moveEx.getMessage());
                            }
                        }
                        if (success) {
                            p.instanceId = candidate;
                        }
                    }
                }
            }

            facade.profiles().save(profiles);
            log("Configuración guardada correctamente.");
        } catch (Exception ex) {
            log("Error al guardar: " + ex.getMessage());
        }
    }

    private static String sanitizeFolderName(String name) {
        if (name == null || name.isBlank()) {
            return "perfil";
        }
        String safe = name.trim()
            .replaceAll("[\\\\/:*?\"<>|\\s]+", "_")
            .replaceAll("_+", "_");
        if (safe.isBlank() || safe.equals("_")) {
            return "perfil";
        }
        return safe;
    }

    private javafx.concurrent.Task<Void> createInstallTask() {
        return new javafx.concurrent.Task<>() {
            @Override protected Void call() throws Exception {
                installing.set(true);
                try {
                    if (selected == null) return null;
                    updateMessage("Instalando " + selected.lastVersionId + "...");
                    facade.installVersion(selected.lastVersionId, s -> Platform.runLater(() -> log(s)));
                    return null;
                } finally {
                    installing.set(false);
                }
            }
            @Override protected void succeeded() {
                log("Instalación completada.");
                refreshHints();
            }
            @Override protected void failed() {
                log("FALLO en instalación: " + getException().getMessage());
            }
        };
    }

    private javafx.concurrent.Task<Void> createLaunchTask() {
        return new javafx.concurrent.Task<>() {
            @Override protected Void call() throws Exception {
                if (selected == null) return null;
                if (!OfflineUuid.uuidMatchesUsername(selected.username, selected.offlineUuid)) {
                    throw new RuntimeException("UUID no válido para el usuario actual.");
                }
                
                Path jar = facade.directories().versionsDir().resolve(selected.lastVersionId).resolve(selected.lastVersionId + ".jar");
                if (!java.nio.file.Files.exists(jar)) {
                    Platform.runLater(() -> log("Detectada versión faltando. Iniciando auto-instalador para " + selected.lastVersionId + "..."));
                    facade.installVersion(selected.lastVersionId, s -> Platform.runLater(() -> log(s)));
                }
                
                updateMessage("Iniciando Minecraft...");
                long totalRam = com.experimento.launcher.service.HardwareProbe.totalPhysicalRamMiB();
                // Cap RAM assignment: never allocate more than 75% of physical RAM and never more than 16 GB
                long ram = Math.min(totalRam * 3 / 4, 16 * 1024L);
                
                String pId = selected.id;
                Process proc = facade.startGame(selected, ram, s -> Platform.runLater(() -> log(s)));
                
                Platform.runLater(() -> {
                    activeProcesses.put(pId, proc);
                    runningState.get(pId).set(true);
                    log("Instancia [" + selected.displayName + "] iniciada.");
                });
                
                proc.onExit().thenAccept(p -> {
                    int exitCode = p.exitValue();
                    Platform.runLater(() -> {
                        activeProcesses.remove(pId);
                        if (runningState.containsKey(pId)) runningState.get(pId).set(false);
                        if (exitCode == 0) {
                            log("Instancia [" + pId + "] cerrada correctamente.");
                        } else {
                            log("⚠ Instancia [" + pId + "] cerrada con código " + exitCode + ".");
                        }
                    });
                });

                return null;
            }
            @Override protected void failed() {
                log("Error de lanzamiento: " + getException().getMessage());
            }
        };
    }

    private void runTask(javafx.concurrent.Task<Void> task) {
        setUilock(true);
        task.setOnSucceeded(e -> setUilock(false));
        task.setOnFailed(e -> setUilock(false));
        task.setOnCancelled(e -> setUilock(false));
        workers.execute(task);
    }

    private void setUilock(boolean lock) {
        installBtn.setDisable(lock);
        playBtn.setDisable(lock);
        saveBtn.setDisable(lock);
        newProfileBtn.setDisable(lock);
        deleteProfileBtn.setDisable(lock);
        profileList.setDisable(lock);
    }

    private void setupVersionComboCellFactories() {
        versionCombo.setCellFactory(lv -> new ListCell<>() {
            @Override protected void updateItem(ManifestVersionEntry item, boolean empty) {
                super.updateItem(item, empty);
                if (empty || item == null) {
                    setText(null);
                } else {
                    setText(item.label());
                    setStyle("-fx-text-fill: #333333; -fx-background-color: white;"); // Forzar visibilidad en Linux
                }
            }
        });
        versionCombo.setButtonCell(new ListCell<>() {
             @Override protected void updateItem(ManifestVersionEntry item, boolean empty) {
                super.updateItem(item, empty);
                setText(empty || item == null ? null : item.label());
            }
        });
    }

    // --- Métodos heredados/auxiliares originales (mantenidos o simplificados) ---

    private void loadVersionManifestAsync() {
        loadVersionManifestAsync(null);
    }

    private void loadVersionManifestAsync(Runnable postAction) {
        workers.submit(() -> {
            try {
                List<ManifestVersionEntry> list = facade.fetchManifestVersions();
                Platform.runLater(() -> {
                    allManifestEntries.clear();
                    allManifestEntries.addAll(list);
                    if (selected != null) ensureProfileVersionInBackingList(selected.lastVersionId);
                    applyVersionFilter();
                    if (postAction != null) {
                        try {
                            postAction.run();
                        } catch (Exception e) {
                            log("Error post manifest loading: " + e.getMessage());
                        }
                    }
                });
            } catch (Exception ex) {
                Platform.runLater(() -> log("Error manifest: " + ex.getMessage()));
            }
        });
    }

    private void ensureProfileVersionInBackingList(String id) {
        if (id == null || id.isBlank()) return;
        if (allManifestEntries.stream().noneMatch(e -> id.equals(e.id()))) {
            allManifestEntries.add(new ManifestVersionEntry(id, "perfil"));
        }
    }

    private void applyVersionFilter() {
        if (selected != null) ensureProfileVersionInBackingList(selected.lastVersionId);
        String filter = versionFilter.getValue();
        String profileId = (selected != null && selected.lastVersionId != null) ? selected.lastVersionId : "";

        List<ManifestVersionEntry> filtered = allManifestEntries.stream()
            .filter(e -> matchesFilter(e, filter) || e.id().equals(profileId))
            .toList();

        syncingVersionUi = true;
        try {
            versionCombo.setItems(FXCollections.observableArrayList(filtered));
            versionCombo.getItems().stream()
                .filter(e -> e.id().equals(profileId)).findFirst()
                .ifPresent(versionCombo::setValue);
        } finally {
            syncingVersionUi = false;
        }
    }

    private boolean matchesFilter(ManifestVersionEntry e, String filter) {
        if (filter == null || "Todas".equals(filter)) return true;
        String t = e.type() == null ? "" : e.type();
        return switch (filter) {
            case "Solo releases" -> "release".equalsIgnoreCase(t);
            case "Solo snapshots" -> "snapshot".equalsIgnoreCase(t);
            case "Clásicas (beta/alpha)" -> t.toLowerCase().contains("alpha") || t.toLowerCase().contains("beta");
            default -> true;
        };
    }

    private void addServerToSelected() {
        if (selected == null) return;
        if (selected.servers == null) selected.servers = new ArrayList<>();
        selected.servers.add(new ServerEntry("Nuevo Servidor", "ip:puerto"));
        serverTable.setItems(FXCollections.observableList(selected.servers));
    }

    private void removeSelectedServer() {
        ServerEntry se = serverTable.getSelectionModel().getSelectedItem();
        if (selected != null && se != null) {
            selected.servers.remove(se);
            serverTable.setItems(FXCollections.observableList(selected.servers));
        }
    }

    private void syncIdentityFromDisplayName(String displayName) {
        if (selected == null) return;
        String normalized = displayName == null ? "" : displayName.trim();
        syncingIdentityUi = true;
        try {
            selected.username = normalized;
            usernameField.setText(normalized);
            syncUuidFromUsername();
        } finally {
            syncingIdentityUi = false;
        }
    }

    private void syncUuidFromUsername() {
        if (selected == null) return;
        String name = selected.username == null ? "" : selected.username.trim();
        if (name.isEmpty()) {
            uuidField.clear();
            return;
        }
        try {
            selected.offlineUuid = OfflineUuid.toString(OfflineUuid.forUsername(name));
            uuidField.setText(selected.offlineUuid);
        } catch (Exception ex) {
            // Solo loguear si el nombre tiene contenido pero es inválido tecnicamente
            if (name.length() > 0) {
                log("Aviso: " + name + " no es un nombre técnico estándar.");
            }
        }
    }

    private void refreshHints() {
        if (selected == null) return;
        long ram = HardwareProbe.totalPhysicalRamMiB();
        JvmPresetKind eff = selected.jvmPreset == JvmPresetKind.AUTO ? JvmPresetService.resolveAutoKind(ram) : selected.jvmPreset;
        modHintLabel.setText(AutoOptimizerService.modSuggestionText(eff) + " | RAM: " + ram + " MiB");

        if (modLoaderBadgeLabel != null) {
            String loader = selected.modLoader != null ? selected.modLoader : "vanilla";
            String icon = switch (loader.toLowerCase()) {
                case "fabric"   -> "🪡 Fabric";
                case "quilt"    -> "🧵 Quilt";
                case "forge"    -> "⚙ Forge";
                case "neoforge" -> "🔥 NeoForge";
                default         -> "📦 Vanilla";
            };
            boolean canInstallPerfMods = PerformanceModsService.isSupported(loader);
            String perfNote = canInstallPerfMods ? " ✓ Compatible con mods de rendimiento" : " ✗ Sin soporte de mods (instala Fabric/Quilt/Forge/NeoForge)";
            modLoaderBadgeLabel.setText("Modloader activo: " + icon + perfNote);
            modLoaderBadgeLabel.setStyle(
                "-fx-font-size: 12px; -fx-padding: 4 10; -fx-background-radius: 4; -fx-text-fill: " +
                (canInstallPerfMods ? "#81c784" : "#ef9a9a") +
                "; -fx-background-color: #1e1e1e;");
        }
    }

    private void refreshAternosRowHint(ServerEntry se) {
        log(se.crackedServer ? "Modo Cracked: Acceso offline permitido." : "Modo Premium: Requiere cuenta Microsoft.");
    }

    private void log(String s) {
        if (javafx.application.Platform.isFxApplicationThread()) {
            if (logArea.getLength() > 50000) {
                logArea.clear();
            }
            logArea.appendText(s + "\n");
            logArea.selectPositionCaret(logArea.getLength());
        } else {
            Platform.runLater(() -> {
                if (logArea.getLength() > 50000) {
                    logArea.clear();
                }
                logArea.appendText(s + "\n");
                logArea.selectPositionCaret(logArea.getLength());
            });
        }
    }

    @Override
    public void stop() { workers.shutdownNow(); }
}
