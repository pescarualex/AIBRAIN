package com.aibrain.desktop;

import javafx.application.Application;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.SplitPane;
import javafx.scene.control.TextField;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.stage.Stage;

import java.util.List;

/**
 * AIBRAIN desktop — browse and search your Claude Code memory.
 * Reads the same local database the plugin writes ({@code ~/.aibrain/memory.db}).
 */
public class App extends Application {

    private static final int DETAIL_CAP = 300;    // messages rendered per session
    private static final double BUBBLE_FRACTION = 0.74;

    private final MemoryDao dao = new MemoryDao();

    private final ListView<MemoryDao.Topic> topicList = new ListView<>();
    private final ListView<MemoryDao.Hit> hitList = new ListView<>();
    private final StackPane listArea = new StackPane();
    private final VBox detailBox = new VBox(4);
    private final ScrollPane detailScroll = new ScrollPane(detailBox);
    private final TextField searchField = new TextField();
    private final Label statsLabel = new Label();

    @Override
    public void start(Stage stage) {
        // ---- top bar ----
        Label wordmark = new Label("AIBRAIN");
        wordmark.getStyleClass().add("wordmark");
        statsLabel.getStyleClass().add("stats");
        searchField.setPromptText("Search your memory…   (Enter)");
        searchField.setOnAction(e -> onSearch());
        HBox.setHgrow(searchField, Priority.ALWAYS);
        HBox top = new HBox(16, wordmark, statsLabel, searchField);
        top.getStyleClass().add("topbar");
        top.setPadding(new Insets(11, 14, 11, 16));

        // ---- recent-topics list ----
        topicList.setCellFactory(lv -> new TopicCell(lv));
        topicList.setPlaceholder(placeholder("No topics yet. Run /memory-index in Claude Code."));
        topicList.getSelectionModel().selectedItemProperty().addListener((o, a, t) -> {
            if (t != null) showSession(t.sessionId());
        });

        // ---- search-results list ----
        hitList.setCellFactory(lv -> new HitCell(lv));
        hitList.setPlaceholder(placeholder("No matches."));
        hitList.getSelectionModel().selectedItemProperty().addListener((o, a, h) -> {
            if (h != null) showSession(h.sessionId());
        });

        listArea.getChildren().add(topicList);

        // ---- detail pane: chat-style bubbles ----
        detailBox.getStyleClass().add("detail");
        detailBox.setFillWidth(true);
        detailBox.setPadding(new Insets(14, 16, 20, 16));
        detailScroll.getStyleClass().add("detail-scroll");
        detailScroll.setFitToWidth(true);

        SplitPane split = new SplitPane(listArea, detailScroll);
        split.setDividerPositions(0.40);

        BorderPane root = new BorderPane();
        root.setTop(top);
        root.setCenter(split);

        Scene scene = new Scene(root, 1080, 680);
        var css = getClass().getResource("style.css");
        if (css != null) scene.getStylesheets().add(css.toExternalForm());

        stage.setTitle("AIBRAIN — Memory");
        stage.setMinWidth(780);
        stage.setMinHeight(500);
        stage.setScene(scene);
        stage.show();

        refresh();
    }

    private void refresh() {
        MemoryDao.Stats s = dao.stats();
        statsLabel.setText(s.sessions() + " sessions  ·  " + s.messages() + " messages  ·  "
                           + s.facts() + " facts  ·  " + String.format("%.1f MB", s.mb()));
        topicList.getItems().setAll(dao.recentTopics(60));
    }

    private void onSearch() {
        String q = searchField.getText().trim();
        if (q.isEmpty()) {
            listArea.getChildren().setAll(topicList);
            return;
        }
        hitList.getItems().setAll(dao.search(q, 60));
        listArea.getChildren().setAll(hitList);
    }

    /** Render a session as a chat: user bubbles on the right, assistant on the left. */
    private void showSession(String sessionId) {
        detailBox.getChildren().clear();
        List<MemoryDao.Message> msgs = dao.sessionMessages(sessionId);
        int shown = Math.min(DETAIL_CAP, msgs.size());
        for (int i = 0; i < shown; i++) {
            MemoryDao.Message m = msgs.get(i);
            boolean user = "user".equalsIgnoreCase(m.role());

            Label bubble = new Label(m.text());
            bubble.setWrapText(true);
            bubble.getStyleClass().addAll("bubble", user ? "bubble-user" : "bubble-assistant");
            bubble.maxWidthProperty().bind(detailScroll.widthProperty().multiply(BUBBLE_FRACTION));

            HBox row = new HBox(bubble);
            row.setAlignment(user ? Pos.CENTER_RIGHT : Pos.CENTER_LEFT);
            detailBox.getChildren().add(row);
        }
        if (msgs.size() > shown) {
            Label more = new Label("…  " + (msgs.size() - shown) + " more messages in this session");
            more.getStyleClass().add("bubble-more");
            HBox row = new HBox(more);
            row.setAlignment(Pos.CENTER);
            detailBox.getChildren().add(row);
        }
        detailScroll.setVvalue(0);
    }

    private Label placeholder(String text) {
        Label l = new Label(text);
        l.getStyleClass().add("placeholder");
        return l;
    }

    /** Shorten a project key like "-home-alex-Documents-dev-InkVault" to "InkVault". */
    static String shortProject(String project) {
        if (project == null || project.isBlank()) return "";
        if (!project.contains("-")) return project;           // e.g. "claude.ai"
        String[] parts = project.split("-");
        for (int i = parts.length - 1; i >= 0; i--) {
            if (!parts[i].isBlank()) return parts[i];
        }
        return project;
    }

    // ---- custom list cells ----

    /** Two-line topic row: eyebrow (date · project), title, summary. */
    private static final class TopicCell extends ListCell<MemoryDao.Topic> {
        private final Label meta = new Label();
        private final Label title = new Label();
        private final Label sub = new Label();
        private final VBox box = new VBox(2, meta, title, sub);

        TopicCell(ListView<MemoryDao.Topic> lv) {
            meta.getStyleClass().add("topic-meta");
            title.getStyleClass().add("topic-title");
            sub.getStyleClass().add("topic-sub");
            box.getStyleClass().add("row-box");
            title.setMaxWidth(Double.MAX_VALUE);
            sub.setMaxWidth(Double.MAX_VALUE);
            meta.setMaxWidth(Double.MAX_VALUE);
            setText(null);
            prefWidthProperty().bind(lv.widthProperty().subtract(20));
        }

        @Override protected void updateItem(MemoryDao.Topic t, boolean empty) {
            super.updateItem(t, empty);
            if (empty || t == null) { setGraphic(null); return; }
            String proj = shortProject(t.project());
            meta.setText(t.date() + (proj.isBlank() ? "" : "   ·   " + proj));
            title.setText(t.label() == null ? "" : t.label());
            boolean hasSub = t.sub() != null && !t.sub().isBlank();
            sub.setText(hasSub ? t.sub() : "");
            sub.setManaged(hasSub);
            sub.setVisible(hasSub);
            setGraphic(box);
        }
    }

    /** Two-line search hit: eyebrow (date), title, snippet. */
    private static final class HitCell extends ListCell<MemoryDao.Hit> {
        private final Label meta = new Label();
        private final Label title = new Label();
        private final Label snip = new Label();
        private final VBox box = new VBox(2, meta, title, snip);

        HitCell(ListView<MemoryDao.Hit> lv) {
            meta.getStyleClass().add("topic-meta");
            title.getStyleClass().add("topic-title");
            snip.getStyleClass().add("topic-sub");
            box.getStyleClass().add("row-box");
            title.setMaxWidth(Double.MAX_VALUE);
            snip.setMaxWidth(Double.MAX_VALUE);
            setText(null);
            prefWidthProperty().bind(lv.widthProperty().subtract(20));
        }

        @Override protected void updateItem(MemoryDao.Hit h, boolean empty) {
            super.updateItem(h, empty);
            if (empty || h == null) { setGraphic(null); return; }
            meta.setText(h.date());
            title.setText(h.title() == null ? "" : h.title());
            snip.setText(h.snippet() == null ? "" : h.snippet());
            setGraphic(box);
        }
    }

    public static void main(String[] args) {
        launch(args);
    }
}
