package com.aibrain.memory;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.BufferedReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

/**
 * Incremental indexing of Claude Code transcripts
 * ({@code ~/.claude/projects/**}{@code /*.jsonl}).
 */
final class Ingest {

    private static final ObjectMapper M = new ObjectMapper();
    private static final Path PROJECTS =
        Path.of(System.getProperty("user.home"), ".claude", "projects");

    record Msg(String role, String ts, String text) {}

    static void run(String[] args) throws Exception {
        try (Connection c = Db.open()) {
            c.setAutoCommit(false);
            int[] r = ingestClaudeCode(c);
            c.commit();
            System.out.printf("claudecode: +%d sessions, +%d messages%n", r[0], r[1]);
            Search.statsOn(c);
        }
    }

    static int[] ingestClaudeCode(Connection c) throws Exception {
        int newS = 0, newM = 0;
        if (!Files.isDirectory(PROJECTS)) return new int[]{0, 0};

        List<Path> files = new ArrayList<>();
        try (Stream<Path> s = Files.walk(PROJECTS)) {
            s.filter(p -> p.toString().endsWith(".jsonl")).sorted().forEach(files::add);
        }

        for (Path f : files) {
            String sid = f.getFileName().toString().replaceFirst("\\.jsonl$", "");
            String sig = String.valueOf(Files.getLastModifiedTime(f).toMillis() / 1000L);
            if (alreadyCurrent(c, sid, sig)) continue;

            List<Msg> msgs = new ArrayList<>();
            String firstUser = null, started = null, ended = null;

            try (BufferedReader br = Files.newBufferedReader(f)) {
                String line;
                while ((line = br.readLine()) != null) {
                    line = line.strip();
                    if (line.isEmpty()) continue;
                    JsonNode o;
                    try { o = M.readTree(line); } catch (Exception e) { continue; }

                    String ts = o.path("timestamp").isMissingNode() ? null : o.path("timestamp").asText(null);
                    if (started == null) started = ts;
                    if (ts != null) ended = ts;

                    String typ = o.path("type").asText("");
                    if (!typ.equals("user") && !typ.equals("assistant")) continue;

                    JsonNode msg = o.path("message");
                    if (!msg.isObject()) continue;

                    String txt = Content.clip(Content.blocksText(msg.path("content")));
                    if (typ.equals("user") && Content.isNoise(txt)) continue;
                    if (txt.isEmpty()) continue;

                    if (typ.equals("user") && firstUser == null && !txt.startsWith("[")) {
                        firstUser = txt.substring(0, Math.min(160, txt.length())).replace("\n", " ");
                    }
                    msgs.add(new Msg(typ, ts, txt));
                }
            }
            if (msgs.isEmpty()) continue;

            String project = f.getParent() != null ? f.getParent().getFileName().toString() : "";
            String title = firstUser != null ? firstUser : "(Claude Code session)";
            replaceSession(c, sid, "claude_code", project, title, started, ended,
                           f.toString(), sig, msgs);
            newS++;
            newM += msgs.size();
        }
        return new int[]{newS, newM};
    }

    private static boolean alreadyCurrent(Connection c, String sid, String sig) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("SELECT src_sig FROM sessions WHERE id=?")) {
            ps.setString(1, sid);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() && sig.equals(rs.getString(1));
            }
        }
    }

    /** Replace a session and all its messages/segments (content changed -> re-index). */
    static void replaceSession(Connection c, String id, String source, String project,
                               String title, String started, String ended,
                               String srcPath, String sig, List<Msg> msgs) throws SQLException {
        deleteBySession(c, "DELETE FROM messages WHERE session_id=?", id);
        deleteBySession(c, "DELETE FROM messages_fts WHERE session_id=?", id);
        deleteBySession(c, "DELETE FROM segments WHERE session_id=?", id);
        deleteBySession(c, "DELETE FROM sessions WHERE id=?", id);

        try (PreparedStatement ps = c.prepareStatement(
                "INSERT INTO sessions(id,source,project,title,started_at,ended_at," +
                "msg_count,summary,src_path,src_sig,indexed_at) VALUES(?,?,?,?,?,?,?,?,?,?,?)")) {
            ps.setString(1, id);
            ps.setString(2, source);
            ps.setString(3, project);
            ps.setString(4, title);
            ps.setString(5, started);
            ps.setString(6, ended);
            ps.setInt(7, msgs.size());
            ps.setString(8, null);
            ps.setString(9, srcPath);
            ps.setString(10, sig);
            ps.setString(11, Content.now());
            ps.executeUpdate();
        }

        try (PreparedStatement pm = c.prepareStatement(
                "INSERT INTO messages(session_id,seq,role,ts,text) VALUES(?,?,?,?,?)");
             PreparedStatement pf = c.prepareStatement(
                "INSERT INTO messages_fts(session_id,text) VALUES(?,?)")) {
            int seq = 0;
            for (Msg m : msgs) {
                pm.setString(1, id);
                pm.setInt(2, seq++);
                pm.setString(3, m.role());
                pm.setString(4, m.ts());
                pm.setString(5, m.text());
                pm.executeUpdate();

                pf.setString(1, id);
                pf.setString(2, m.text());
                pf.executeUpdate();
            }
        }
    }

    private static void deleteBySession(Connection c, String sql, String id) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, id);
            ps.executeUpdate();
        }
    }

    private Ingest() {}
}
