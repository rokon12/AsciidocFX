package com.kodcu.boot;

import com.install4j.api.launcher.StartupNotification;
import com.kodcu.config.EditorConfigBean;
import com.kodcu.controller.ApplicationController;
import com.kodcu.service.ThreadService;
import com.kodcu.service.ui.TabService;
import de.tototec.cmdoption.CmdlineParser;
import de.tototec.cmdoption.CmdlineParserException;
import javafx.application.Application;
import javafx.application.Platform;
import javafx.fxml.FXMLLoader;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.image.Image;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyCodeCombination;
import javafx.scene.layout.AnchorPane;
import javafx.stage.Modality;
import javafx.stage.Stage;
import org.apache.commons.io.IOUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.SpringApplication;
import org.springframework.context.ConfigurableApplicationContext;

import java.io.File;
import java.io.InputStream;
import java.nio.file.Paths;
import java.util.Objects;

import static javafx.scene.input.KeyCombination.SHORTCUT_DOWN;

public class AppStarter extends Application {

    private static Logger logger = LoggerFactory.getLogger(AppStarter.class);

    private static ApplicationController controller;
    private static ConfigurableApplicationContext context;
    private EditorConfigBean editorConfigBean;
    private Stage stage;

    @Override
    public void start(final Stage stage) {

//        System.setProperty("nashorn.typeInfo.maxFiles", "5");

        // http://bit.ly/1Euk8hh
        System.setProperty("jsse.enableSNIExtension", "false");

        final CmdlineConfig config = new CmdlineConfig();
        final CmdlineParser cp = new CmdlineParser(config);

        try {
            cp.parse(getParameters().getRaw().toArray(new String[0]));
        } catch (final CmdlineParserException e) {
            System.err.println("Invalid commandline given: " + e.getMessage());
            System.exit(1);
        }

        if (config.help) {
            cp.usage();
            System.exit(0);
        }

        try {
            startApp(stage, config);
        } catch (final Throwable e) {
            logger.error("Problem occured while starting AsciidocFX", e);
        }

    }

    private void startApp(final Stage stage, final CmdlineConfig config) throws Throwable {

        this.stage = stage;
        context = SpringApplication.run(SpringAppConfig.class);
        editorConfigBean = context.getBean(EditorConfigBean.class);

        final FXMLLoader parentLoader = new FXMLLoader();
        parentLoader.setControllerFactory(context::getBean);

        InputStream sceneStream = getClass().getResourceAsStream("/fxml/Scene.fxml");
        Parent root = parentLoader.load(sceneStream);

        Scene scene = new Scene(root);

        scene.getStylesheets().add("/styles/Styles.css");
        stage.setMaximized(true);
        stage.setTitle("AsciidocFX");
        InputStream logoStream = getClass().getResourceAsStream("/logo.png");
        stage.getIcons().add(new Image(logoStream));

        rememberStageScreenState();

        stage.setScene(scene);
        stage.show();

        IOUtils.closeQuietly(sceneStream);
        IOUtils.closeQuietly(logoStream);

        final FXMLLoader asciidocTableLoader = new FXMLLoader();
        final FXMLLoader markdownTableLoader = new FXMLLoader();

        asciidocTableLoader.setControllerFactory(context::getBean);
        markdownTableLoader.setControllerFactory(context::getBean);

        InputStream asciidocTableStream = getClass().getResourceAsStream("/fxml/AsciidocTablePopup.fxml");
        AnchorPane asciidocTableAnchor = asciidocTableLoader.load(asciidocTableStream);

        InputStream markdownTableStream = getClass().getResourceAsStream("/fxml/MarkdownTablePopup.fxml");
        AnchorPane markdownTableAnchor = markdownTableLoader.load(markdownTableStream);

        Stage asciidocTableStage = new Stage();
        asciidocTableStage.setScene(new Scene(asciidocTableAnchor));
        asciidocTableStage.setTitle("Table Generator");
        asciidocTableStage.initModality(Modality.WINDOW_MODAL);
        asciidocTableStage.initOwner(scene.getWindow());
        asciidocTableStage.getIcons().add(new Image(logoStream));

        Stage markdownTableStage = new Stage();
        markdownTableStage.setScene(new Scene(markdownTableAnchor));
        markdownTableStage.setTitle("Table Generator");
        markdownTableStage.initModality(Modality.WINDOW_MODAL);
        markdownTableStage.initOwner(scene.getWindow());
        markdownTableStage.getIcons().add(new Image(logoStream));

        IOUtils.closeQuietly(asciidocTableStream);
        IOUtils.closeQuietly(markdownTableStream);

        controller = parentLoader.getController();

        controller.setStage(stage);
        controller.setScene(scene);
        controller.setAsciidocTableAnchor(asciidocTableAnchor);
        controller.setMarkdownTableAnchor(markdownTableAnchor);
        controller.setAsciidocTableStage(asciidocTableStage);
        controller.setMarkdownTableStage(markdownTableStage);

//        controller.initializeAutoSaver();
        controller.initializeSaveOnBlur();

        scene.getAccelerators().put(new KeyCodeCombination(KeyCode.S, SHORTCUT_DOWN), controller::saveDoc);
        scene.getAccelerators().put(new KeyCodeCombination(KeyCode.M, SHORTCUT_DOWN), controller::adjustSplitPane);
        scene.getAccelerators().put(new KeyCodeCombination(KeyCode.N, SHORTCUT_DOWN), controller::newDoc);
        scene.getAccelerators().put(new KeyCodeCombination(KeyCode.O, SHORTCUT_DOWN), controller::openDoc);
        scene.getAccelerators().put(new KeyCodeCombination(KeyCode.W, SHORTCUT_DOWN), controller::saveAndCloseCurrentTab);
//        scene.getAccelerators().put(new KeyCodeCombination(KeyCode.F4, SHORTCUT_DOWN), controller::showSettings);

        final ThreadService threadService = context.getBean(ThreadService.class);
//        final TabService tabService = context.getBean(TabService.class);

        controller.initializeTabWatchListener();

        threadService.start(() -> {
            try {
                registerStartupListener(config);
            } catch (Exception e) {
                logger.error("Problem occured in startup listener", e);
            }
        });

        scene.getWindow().setOnCloseRequest(controller::closeAllTabs);

        if (controller.getTabPane().getTabs().isEmpty())
            controller.newDoc();

        controller.setHostServices(getHostServices());

        stage.widthProperty().addListener(controller::stageWidthChanged);
        stage.heightProperty().addListener(controller::stageWidthChanged);
    }

    private void rememberStageScreenState() {

        stage.xProperty().addListener((observable, oldValue, newValue) -> {
            editorConfigBean.setScreenX(newValue.doubleValue());
        });

        stage.yProperty().addListener((observable, oldValue, newValue) -> {
            editorConfigBean.setScreenY(newValue.doubleValue());
        });

        stage.widthProperty().addListener((observable, oldValue, newValue) -> {
            editorConfigBean.setScreenWidth(newValue.doubleValue());
        });

        stage.heightProperty().addListener((observable, oldValue, newValue) -> {
            editorConfigBean.setScreenHeight(newValue.doubleValue());
        });

        editorConfigBean.screenXProperty().addListener((observable, oldValue, newValue) -> {
            if (Objects.nonNull(newValue)) {
                stage.setX(newValue.doubleValue());
            }
        });

        editorConfigBean.screenYProperty().addListener((observable, oldValue, newValue) -> {
            if (Objects.nonNull(newValue)) {
                stage.setY(newValue.doubleValue());
            }
        });

        editorConfigBean.screenWidthProperty().addListener((observable, oldValue, newValue) -> {
            if (Objects.nonNull(newValue)) {
                stage.setWidth(newValue.doubleValue());
            }
        });

        editorConfigBean.screenHeightProperty().addListener((observable, oldValue, newValue) -> {
            if (Objects.nonNull(newValue)) {
                stage.setHeight(newValue.doubleValue());
            }
        });
    }

    private void registerStartupListener(CmdlineConfig config) {

        final ThreadService threadService = context.getBean(ThreadService.class);
        final TabService tabService = context.getBean(TabService.class);

        StartupNotification.registerStartupListener(parameters -> {

            threadService.runActionLater(() -> {

                String[] files = parameters.split(" ");

                for (String file : files) {
                    file = file.replace("\"", "");
                    tabService.addTab(Paths.get(file).toAbsolutePath());
                }
            });
        });

        if (!config.files.isEmpty()) {
            threadService.runActionLater(() -> {
                config.files.stream().forEach(f -> {
                    File file = new File(f).getAbsoluteFile();
                    if (file.exists()) {
                        logger.info("Opening file as requsted from cmdline: {}", file);
                        tabService.addTab(file.toPath());
                    } else {
                        // TODO: do we want to create such a file on demand?
                        logger.error("Cannot open non-existent file: {}", file);
                    }
                });
            });
        }
    }

    @Override
    public void stop() throws Exception {
        controller.closeApp(null);
        context.registerShutdownHook();
        Platform.exit();
        System.exit(0);
    }

    /**
     * The main() method is ignored in correctly deployed JavaFX application.
     * main() serves only as fallback in case the application can not be
     * launched through deployment artifacts, e.g., in IDEs with limited FX
     * support. NetBeans ignores main().
     *
     * @param args the command line arguments
     */
    public static void main(String[] args) {
        launch(args);
    }

}
