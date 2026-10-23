package com.aibrain.desktop;

import javafx.application.Application;
import javafx.geometry.Insets;
import javafx.scene.Scene;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.control.SplitPane;
import javafx.scene.control.TextArea;
import javafx.scene.control.TextField;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.StackPane;
import javafx.stage.Stage;

import java.util.List;

/**
 * AIBRAIN desktop — browse and search your Claude Code memory.
 * Reads the same local database the plugin writes ({@code ~/.aibrain/memory.db}).
 */
public class App extends Application {

    private final MemoryDao dao = new MemoryDao();

    private final ListView<MemoryDao.Topic> topicList = new ListView<>();
    private final ListView<MemoryDao.Hit> hitList = new ListView<>();
    private final StackPane listArea = new StackPane();
    private final TextArea detail = new TextArea();
    private final TextField searchField = new TextField();
    private final Label statsLabel = new Label();

    @Override
    public void start(Stage stage) {
        // --- top bar: title, stats, search ---
        Label title = new Label("AIBRAIN");
        title.getStyleClass().add("title");
        statsLabel.getStyleClass().add("stats");
        searchField.setPromptText("Search your memory…  (Enter)");
        searchField.setOnAction(e -> onSearch());
        HBox.setHgrow(searchField, Priority.ALWAYS);
        HBox top = new HBox(14, title, statsLabel, searchField);
        top.getStyleClass().add("topbar");
        top.setPadding(new Insets(12, 14, 12, 14));

        // --- recent-topics list ---
        topicList.setCellFactory(lv -> new ListCell<>() {
            @Override protected void updateItem(MemoryDao.Topic t, boolean empty) {
                super.updateItem(t, empty);
                if (empty || t == null) { setText(null); return; }
                String sub = t.sub() == null || t.sub().isBlank() ? "" : "  —  " + t.sub();
                setText("[" + t.date() + "]  " + t.label() + sub);
            }
        });
        topicList.getSelectionModel().selectedItemProperty().addListener((o, a, t) -> {
            if (t != null) showSession(t.sessionId());
        });

        // --- search-results list ---
        hitList.setCellFactory(lv -> new ListCell<>() {
            @Override protected void updateItem(MemoryDao.Hit h, boolean empty) {
                super.updateItem(h, empty);
                if (empty || h == null) { setText(null); return; }
                setText("[" + h.date() + "]  " + h.title() + "\n    " + h.snippet());
            }
        });
        hitList.getSelectionModel().selectedItemProperty().addListener((o, a, h) -> {
            if (h != null) showSession(h.sessionId());
        });

        listArea.getChildren().add(topicList);

        // --- detail pane ---
        detail.setEditable(false);
        detail.setWrapText(true);

        SplitPane split = new SplitPane(listArea, detail);
        split.setDividerPositions(0.42);

        BorderPane root = new BorderPane();
        root.setTop(top);
        root.setCenter(split);

        Scene scene = new Scene(root, 1040, 660);
        var cssUrl = getClass().getResource("style.css");
        if (cssUrl != null) scene.getStylesheets().add(cssUrl.toExternalForm());

        stage.setTitle("AIBRAIN — Memory");
        stage.setScene(scene);
        stage.show();

        refresh();
    }

    private void refresh() {
        MemoryDao.Stats s = dao.stats();
        statsLabel.setText(s.sessions() + " sessions · " + s.messages() + " messages · "
                           + s.facts() + " facts · " + String.format("%.1f MB", s.mb()));
        topicList.getItems().setAll(dao.recentTopics(50));
    }

    private void onSearch() {
        String q = searchField.getText().trim();
        if (q.isEmpty()) {
            listArea.getChildren().setAll(topicList);
            return;
        }
        List<MemoryDao.Hit> hits = dao.search(q, 60);
        hitList.getItems().setAll(hits);
        listArea.getChildren().setAll(hitList);
    }

    private void showSession(String sessionId) {
        StringBuilder sb = new StringBuilder();
        for (MemoryDao.Message m : dao.sessionMessages(sessionId)) {
            sb.append(m.role().toUpperCase()).append(":\n").append(m.text()).append("\n\n");
        }
        detail.setText(sb.toString());
        detail.positionCaret(0);
    }

    public static void main(String[] args) {
        launch(args);
    }
}
