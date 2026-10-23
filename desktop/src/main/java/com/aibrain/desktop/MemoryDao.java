package com.aibrain.desktop;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

/**
 * Read-only access to the AIBRAIN SQLite database (shared with the plugin core).
 * Location: the {@code AIBRAIN_DB} environment variable, or {@code ~/.aibrain/memory.db}.
 */
public final class MemoryDao {

    public record Stats(long sessions, long messages, long facts, double mb) {}

    /** A recall entry: a session topic (segment) or, as fallback, the session itself. */
    public record Topic(String date, String project, String label, String sub, String sessionId) {}

    /** A full-text search hit. */
    public record Hit(String date, String title, String snippet, String sessionId) {}

    public record Message(String role, String text) {}

    static Path dbPath() {
        String env = System.getenv("AIBRAIN_DB");
        if (env != null && !env.isBlank()) return Path.of(env);
        return Path.of(System.getProperty("user.home"), ".aibrain", "memory.db");
    }

    private Connection open() throws SQLException {
        return DriverManager.getConnection("jdbc:sqlite:" + dbPath());
    }

    public Stats stats() {
        try (Connection c = open()) {
            return new Stats(count(c, "sessions"), count(c, "messages"), count(c, "facts"),
                             safeSize() / 1e6);
        } catch (Exception e) {
            return new Stats(0, 0, 0, 0);
        }
    }

    /** Recent topics (segments), most recent first; falls back to the session title. */
    public List<Topic> recentTopics(int limit) {
        List<Topic> out = new ArrayList<>();
        String sql = "SELECT id,project,ended_at,title,summary FROM sessions ORDER BY ended_at DESC LIMIT ?";
        try (Connection c = open();
             PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setInt(1, limit);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next() && out.size() < limit) {
                    String sid = rs.getString("id");
                    String proj = rs.getString("project");
                    String date = shortDate(rs.getString("ended_at"));
                    String title = rs.getString("title");
                    String summ = rs.getString("summary");
                    List<String[]> segs = segments(c, sid);
                    if (!segs.isEmpty()) {
                        for (String[] seg : segs) {
                            out.add(new Topic(date, proj, seg[0], seg[1], sid));
                            if (out.size() >= limit) break;
                        }
                    } else {
                        out.add(new Topic(date, proj, summ != null ? summ : title, null, sid));
                    }
                }
            }
        } catch (Exception e) {
            // best effort: return whatever was collected
        }
        return out;
    }

    public List<Hit> search(String query, int limit) {
        List<Hit> out = new ArrayList<>();
        String sql = "SELECT f.session_id, s.ended_at, s.title, " +
                     "snippet(messages_fts,1,'[',']','…',12) " +
                     "FROM messages_fts f JOIN sessions s ON s.id=f.session_id " +
                     "WHERE messages_fts MATCH ? ORDER BY s.ended_at DESC LIMIT ?";
        try (Connection c = open();
             PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, query);
            ps.setInt(2, limit);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    out.add(new Hit(shortDate(rs.getString(2)), rs.getString(3),
                                    rs.getString(4), rs.getString(1)));
                }
            }
        } catch (Exception e) {
            // invalid FTS syntax -> no results
        }
        return out;
    }

    public List<Message> sessionMessages(String sessionId) {
        List<Message> out = new ArrayList<>();
        String sql = "SELECT role, text FROM messages WHERE session_id=? ORDER BY seq";
        try (Connection c = open();
             PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, sessionId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) out.add(new Message(rs.getString(1), rs.getString(2)));
            }
        } catch (Exception e) {
            // ignore
        }
        return out;
    }

    private List<String[]> segments(Connection c, String sid) throws SQLException {
        List<String[]> out = new ArrayList<>();
        try (PreparedStatement ps = c.prepareStatement(
                "SELECT topic,summary FROM segments WHERE session_id=? ORDER BY idx DESC")) {
            ps.setString(1, sid);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) out.add(new String[]{rs.getString(1), rs.getString(2)});
            }
        }
        return out;
    }

    private long count(Connection c, String table) throws SQLException {
        try (Statement st = c.createStatement();
             ResultSet rs = st.executeQuery("SELECT COUNT(*) FROM " + table)) {
            return rs.next() ? rs.getLong(1) : 0;
        }
    }

    private long safeSize() {
        try {
            return Files.exists(dbPath()) ? Files.size(dbPath()) : 0;
        } catch (Exception e) {
            return 0;
        }
    }

    private static String shortDate(String ts) {
        return ts == null ? "" : ts.substring(0, Math.min(10, ts.length()));
    }
}
