package com.aibrain.memory;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.nio.file.Files;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Arrays;

/**
 * Read-side subcommands: search (FTS5), stats, pending, spine and set-segments.
 */
final class Search {

    static void search(String[] args) throws Exception {
        if (args.length < 2) {
            System.out.println("Usage: search <words>");
            return;
        }
        String q = String.join(" ", Arrays.copyOfRange(args, 1, args.length));
        int limit = 8;
        try (Connection c = Db.open();
             PreparedStatement ps = c.prepareStatement(
                "SELECT f.session_id, s.ended_at, s.title, " +
                "snippet(messages_fts,1,'»','«','…',14) " +
                "FROM messages_fts f JOIN sessions s ON s.id=f.session_id " +
                "WHERE messages_fts MATCH ? ORDER BY s.ended_at DESC LIMIT ?")) {
            ps.setString(1, q);
            ps.setInt(2, limit);
            try (ResultSet rs = ps.executeQuery()) {
                boolean any = false;
                while (rs.next()) {
                    any = true;
                    String end = shorten(rs.getString(2), 10);
                    String title = shorten(rs.getString(3), 50);
                    System.out.println("  [" + end + "] " + title + "\n     …" + rs.getString(4) + "…");
                }
                if (!any) System.out.println("No results.");
            }
        }
    }

    static void stats() throws Exception {
        try (Connection c = Db.open()) {
            statsOn(c);
        }
    }

    static void statsOn(Connection c) throws Exception {
        long s = count(c, "sessions");
        long m = count(c, "messages");
        long f = count(c, "facts");
        double mb = Files.exists(Db.dbPath()) ? Files.size(Db.dbPath()) / 1e6 : 0;
        System.out.printf("TOTAL: %d sessions · %d messages · %d facts · %.1f MB%n", s, m, f, mb);
    }

    static void pending(String[] args) throws Exception {
        int n = args.length > 1 ? Integer.parseInt(args[1]) : 20;
        try (Connection c = Db.open();
             PreparedStatement ps = c.prepareStatement(
                "SELECT s.id, s.ended_at, s.msg_count, s.project, COALESCE(s.summary, s.title) " +
                "FROM sessions s WHERE NOT EXISTS " +
                "(SELECT 1 FROM segments g WHERE g.session_id=s.id) " +
                "ORDER BY s.ended_at DESC LIMIT ?")) {
            ps.setInt(1, n);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    String end = shorten(rs.getString(2), 10);
                    String lbl = shorten(rs.getString(5), 80);
                    System.out.println(rs.getString(1) + "\t" + end + "\t" + rs.getInt(3) +
                                       "\t" + rs.getString(4) + "\t" + lbl);
                }
            }
        }
    }

    static void spine(String[] args) throws Exception {
        if (args.length < 2) {
            System.out.println("Usage: spine <sid>");
            return;
        }
        String sid = args[1];
        try (Connection c = Db.open();
             PreparedStatement ps = c.prepareStatement(
                "SELECT seq, text FROM messages WHERE session_id=? " +
                "AND role='user' AND text NOT LIKE '[%' ORDER BY seq")) {
            ps.setString(1, sid);
            try (ResultSet rs = ps.executeQuery()) {
                boolean any = false;
                while (rs.next()) {
                    any = true;
                    String txt = rs.getString(2).replaceAll("\\s+", " ").strip();
                    txt = shorten(txt, 300);
                    System.out.println("[" + rs.getInt(1) + "] " + txt);
                }
                if (!any) System.out.println("(no user messages)");
            }
        }
    }

    /** Write a session's segments. Reads a JSON array of {topic, summary} from stdin. */
    static void setSegments(String[] args) throws Exception {
        if (args.length < 2) {
            System.out.println("Usage: set-segments <sid>");
            return;
        }
        String sid = args[1];
        JsonNode data = new ObjectMapper().readTree(new String(System.in.readAllBytes()));
        if (!data.isArray()) {
            System.err.println("Expected a JSON list of {topic, summary}.");
            System.exit(1);
        }
        try (Connection c = Db.open()) {
            c.setAutoCommit(false);
            try (PreparedStatement del = c.prepareStatement("DELETE FROM segments WHERE session_id=?")) {
                del.setString(1, sid);
                del.executeUpdate();
            }
            try (PreparedStatement ins = c.prepareStatement(
                    "INSERT INTO segments(session_id,idx,topic,summary,start_seq,created_at) " +
                    "VALUES(?,?,?,?,?,?)")) {
                int i = 0;
                for (JsonNode seg : data) {
                    ins.setString(1, sid);
                    ins.setInt(2, i++);
                    ins.setString(3, shorten(seg.path("topic").asText(""), 120));
                    ins.setString(4, shorten(seg.path("summary").asText(""), 300));
                    JsonNode ss = seg.path("start_seq");
                    if (ss.isIntegralNumber()) ins.setInt(5, ss.asInt());
                    else ins.setNull(5, java.sql.Types.INTEGER);
                    ins.setString(6, Content.now());
                    ins.executeUpdate();
                }
            }
            c.commit();
            System.out.println("segments: " + data.size() + " written for " + sid);
        }
    }

    private static long count(Connection c, String table) throws SQLException {
        try (Statement st = c.createStatement();
             ResultSet rs = st.executeQuery("SELECT COUNT(*) FROM " + table)) {
            return rs.next() ? rs.getLong(1) : 0;
        }
    }

    private static String shorten(String s, int n) {
        if (s == null) return "";
        return s.length() <= n ? s : s.substring(0, n);
    }

    private Search() {}
}
