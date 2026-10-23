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

    /**
     * A recall entry: a session topic (segment) or, as fallback, the session itself.
     * {@code startSeq}/{@code endSeq} bound the topic's messages; -1 means
     * "from the start" / "to the end" (and startSeq -1 means the whole session).
     */
    public record Topic(String date, String project, String label, String sub,
                        String sessionId, int startSeq, int endSeq) {}

    /** A full-text search hit. */
    public record Hit(String date, String title, String snippet, String sessionId) {}

    public record Message(String role, String text) {}

    static Path dbPath() {
        String env = System.getenv("AIBRAIN_DB");
        if (env != null && !env.isBlank()) return Path.of(env);
        return Path.of(System.getProperty("user.home"), ".aibrain", "memory.db");
    }

    private Connection open() throws SQLException {
        Connection c = DriverManager.getConnection("jdbc:sqlite:" + dbPath());
        // best-effort migration so this reader works even before the plugin core runs
        try (Statement st = c.createStatement()) {
            st.execute("ALTER TABLE segments ADD COLUMN start_seq INTEGER");
        } catch (SQLException ignore) {
            // table missing or column already present
        }
        return c;
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
                    List<Seg> segs = segments(c, sid);   // chronological (idx ASC)
                    if (!segs.isEmpty()) {
                        int n = segs.size();
                        // add most-recent topic first; each topic spans [start, nextStart)
                        for (int i = n - 1; i >= 0 && out.size() < limit; i--) {
                            int start = segs.get(i).startSeq() != null ? segs.get(i).startSeq() : -1;
                            int end = -1;
                            if (i + 1 < n && segs.get(i + 1).startSeq() != null) {
                                end = segs.get(i + 1).startSeq();
                            }
                            out.add(new Topic(date, proj, segs.get(i).topic(), segs.get(i).summary(),
                                              sid, start, end));
                        }
                    } else {
                        out.add(new Topic(date, proj, summ != null ? summ : title, null, sid, -1, -1));
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

    private record Seg(int idx, String topic, String summary, Integer startSeq) {}

    private List<Seg> segments(Connection c, String sid) throws SQLException {
        List<Seg> out = new ArrayList<>();
        try (PreparedStatement ps = c.prepareStatement(
                "SELECT idx, topic, summary, start_seq FROM segments WHERE session_id=? ORDER BY idx ASC")) {
            ps.setString(1, sid);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    int ss = rs.getInt("start_seq");
                    Integer startSeq = rs.wasNull() ? null : ss;
                    out.add(new Seg(rs.getInt("idx"), rs.getString("topic"),
                                    rs.getString("summary"), startSeq));
                }
            }
        }
        return out;
    }

    /** Messages of a session within [startSeq, endSeq); startSeq < 0 means the whole session. */
    public List<Message> messagesInRange(String sessionId, int startSeq, int endSeq) {
        if (startSeq < 0) return sessionMessages(sessionId);
        List<Message> out = new ArrayList<>();
        String sql = "SELECT role, text FROM messages WHERE session_id=? AND seq>=?"
                   + (endSeq >= 0 ? " AND seq<?" : "") + " ORDER BY seq";
        try (Connection c = open();
             PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, sessionId);
            ps.setInt(2, startSeq);
            if (endSeq >= 0) ps.setInt(3, endSeq);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) out.add(new Message(rs.getString(1), rs.getString(2)));
            }
        } catch (Exception e) {
            // ignore
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
