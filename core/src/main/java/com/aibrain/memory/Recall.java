package com.aibrain.memory;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

/**
 * recall and the SessionStart hook. Shows topics (segments), most recent first;
 * a session with no segments falls back to its summary/title.
 */
final class Recall {

    /** One recall entry: date, project, message count, label, sub-text, is-segment? */
    record Entry(String end, String project, int n, String label, String sub, boolean seg) {}

    static List<Entry> recentEntries(Connection c, int limit, String project) throws SQLException {
        String sql = project != null
            ? "SELECT id,project,ended_at,msg_count,title,summary FROM sessions " +
              "WHERE project=? ORDER BY ended_at DESC LIMIT ?"
            : "SELECT id,project,ended_at,msg_count,title,summary FROM sessions " +
              "ORDER BY ended_at DESC LIMIT ?";

        List<Entry> entries = new ArrayList<>();
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            int i = 1;
            if (project != null) ps.setString(i++, project);
            ps.setInt(i, limit);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    String sid = rs.getString("id");
                    String proj = rs.getString("project");
                    String end = rs.getString("ended_at");
                    int n = rs.getInt("msg_count");
                    String title = rs.getString("title");
                    String summ = rs.getString("summary");

                    List<String[]> segs = segmentsOf(c, sid);
                    if (!segs.isEmpty()) {
                        for (String[] s : segs) entries.add(new Entry(end, proj, n, s[0], s[1], true));
                    } else {
                        entries.add(new Entry(end, proj, n, summ != null ? summ : title, null, false));
                    }
                    if (entries.size() >= limit) break;
                }
            }
        }
        return entries.size() > limit ? new ArrayList<>(entries.subList(0, limit)) : entries;
    }

    private static List<String[]> segmentsOf(Connection c, String sid) throws SQLException {
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

    static String fmtEntry(Entry e) {
        String d = e.end() == null ? "" : e.end().substring(0, Math.min(10, e.end().length()));
        if (e.seg()) {
            return "  [" + d + "] " + e.label() + (e.sub() != null ? " — " + e.sub() : "");
        }
        return "  [" + d + "] " + e.project() + " (" + e.n() + " msg) — " + e.label();
    }

    static void recall(String[] args) throws Exception {
        int n = Content.posIntArg(args, 1, 5);
        try (Connection c = Db.open()) {
            List<Entry> entries = recentEntries(c, n, null);
            System.out.println("Last " + entries.size() + " topics:");
            for (Entry e : entries) System.out.println(fmtEntry(e));
        }
    }

    /** cwd -> project key, matching how Claude Code names dirs under ~/.claude/projects/. */
    static String projectKey(String cwd) {
        if (cwd == null || cwd.isEmpty()) return null;
        return cwd.replace("/", "-").replace(".", "-");
    }

    /**
     * Claude Code sends a JSON object with {@code cwd} on stdin at SessionStart.
     * Read it only when stdin is redirected (not a console), so manual runs do not block.
     */
    static String hookCwd() {
        try {
            if (System.console() != null) return null;
            String raw = new String(System.in.readAllBytes());
            if (raw.isBlank()) return null;
            return new ObjectMapper().readTree(raw).path("cwd").asText(null);
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * SessionStart: deliver recall as additionalContext, scoped to the current project
     * if it has history, otherwise global. Never throws.
     */
    static void hook() {
        String ctx;
        try (Connection c = Db.open()) {
            String proj = projectKey(hookCwd());
            List<Entry> entries = proj != null ? recentEntries(c, 5, proj) : new ArrayList<>();
            boolean scoped = !entries.isEmpty();
            if (entries.isEmpty()) entries = recentEntries(c, 5, null);

            long s = count(c, "sessions");
            long m = count(c, "messages");
            long f = count(c, "facts");
            String subtitle = scoped
                ? "Most recent topics on this project (newest first):"
                : "Most recent topics (newest first):";

            StringBuilder sb = new StringBuilder();
            sb.append("[AIBRAIN memory: ").append(s).append(" sessions · ").append(m)
              .append(" messages · ").append(f).append(" facts indexed.]\n");
            sb.append(subtitle);
            for (Entry e : entries) sb.append("\n  •").append(fmtEntry(e).substring(1));
            ctx = sb.toString();
        } catch (Exception e) {
            ctx = "";
        }

        try {
            ObjectMapper mapper = new ObjectMapper();
            ObjectNode inner = mapper.createObjectNode();
            inner.put("hookEventName", "SessionStart");
            inner.put("additionalContext", ctx);
            ObjectNode root = mapper.createObjectNode();
            root.set("hookSpecificOutput", inner);
            System.out.println(mapper.writeValueAsString(root));
        } catch (Exception e) {
            System.out.println("{}");
        }
    }

    private static long count(Connection c, String table) throws SQLException {
        try (Statement st = c.createStatement();
             ResultSet rs = st.executeQuery("SELECT COUNT(*) FROM " + table)) {
            return rs.next() ? rs.getLong(1) : 0;
        }
    }

    private Recall() {}
}
