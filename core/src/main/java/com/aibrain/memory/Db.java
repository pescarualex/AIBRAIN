package com.aibrain.memory;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

/**
 * SQLite connection and schema.
 *
 * Database location: the {@code AIBRAIN_DB} environment variable if set,
 * otherwise {@code ~/.aibrain/memory.db}.
 */
final class Db {

    /**
     * One element per statement. Never split a single SQL text on ';' — trigger
     * bodies ({@code BEGIN ...; END}) contain semicolons of their own.
     */
    static final String[] SCHEMA = {
        """
        CREATE TABLE IF NOT EXISTS sessions(
          id TEXT PRIMARY KEY, source TEXT, project TEXT, title TEXT,
          started_at TEXT, ended_at TEXT, msg_count INTEGER, summary TEXT,
          src_path TEXT, src_sig TEXT, indexed_at TEXT)""",
        """
        CREATE TABLE IF NOT EXISTS messages(
          id INTEGER PRIMARY KEY AUTOINCREMENT, session_id TEXT, seq INTEGER,
          role TEXT, ts TEXT, text TEXT)""",
        "CREATE INDEX IF NOT EXISTS idx_msg_session ON messages(session_id)",
        """
        CREATE TABLE IF NOT EXISTS facts(
          id INTEGER PRIMARY KEY AUTOINCREMENT, category TEXT, topic TEXT,
          content TEXT, source_session TEXT, created_at TEXT, updated_at TEXT)""",
        """
        CREATE TABLE IF NOT EXISTS segments(
          session_id TEXT, idx INTEGER, topic TEXT, summary TEXT, start_seq INTEGER, created_at TEXT,
          PRIMARY KEY(session_id, idx))""",
        "CREATE INDEX IF NOT EXISTS idx_seg_session ON segments(session_id)",
        "CREATE TABLE IF NOT EXISTS meta(key TEXT PRIMARY KEY, value TEXT)",
    };

    /**
     * Full-text index as an FTS5 <em>external content</em> table: it reads the text
     * from {@code messages} by rowid instead of storing a second copy, and a hit's
     * rowid is the {@code messages.id}, so search can report the message position.
     * The triggers keep it in sync with {@code messages}.
     */
    static final String[] FTS_SCHEMA = {
        """
        CREATE VIRTUAL TABLE IF NOT EXISTS messages_fts
          USING fts5(text, content='messages', content_rowid='id')""",
        """
        CREATE TRIGGER IF NOT EXISTS messages_ai AFTER INSERT ON messages BEGIN
          INSERT INTO messages_fts(rowid, text) VALUES (new.id, new.text);
        END""",
        """
        CREATE TRIGGER IF NOT EXISTS messages_ad AFTER DELETE ON messages BEGIN
          INSERT INTO messages_fts(messages_fts, rowid, text) VALUES ('delete', old.id, old.text);
        END""",
        """
        CREATE TRIGGER IF NOT EXISTS messages_au AFTER UPDATE ON messages BEGIN
          INSERT INTO messages_fts(messages_fts, rowid, text) VALUES ('delete', old.id, old.text);
          INSERT INTO messages_fts(rowid, text) VALUES (new.id, new.text);
        END""",
    };

    static Path dbPath() {
        String env = System.getenv("AIBRAIN_DB");
        if (env != null && !env.isBlank()) return Path.of(env);
        return Path.of(System.getProperty("user.home"), ".aibrain", "memory.db");
    }

    /** Open the database, creating the parent directory and schema if needed. */
    static Connection open() throws Exception {
        Path p = dbPath();
        if (p.getParent() != null) Files.createDirectories(p.getParent());
        Connection c = DriverManager.getConnection("jdbc:sqlite:" + p);
        try (Statement st = c.createStatement()) {
            for (String stmt : SCHEMA) st.execute(stmt);
            // migration: add segments.start_seq to databases created before it existed
            try {
                st.execute("ALTER TABLE segments ADD COLUMN start_seq INTEGER");
            } catch (SQLException ignore) {
                // column already present
            }
        }
        migrateFts(c);
        return c;
    }

    /**
     * Databases created before the external-content index hold a standalone
     * {@code messages_fts(session_id UNINDEXED, text)} with its own copy of every
     * message. Replace it, rebuild the index from {@code messages}, and VACUUM once
     * so the freed space goes back to the file system.
     *
     * The swap runs in one transaction, so a crash cannot leave an empty index
     * behind. VACUUM needs autocommit and no open statement, so it runs last on
     * its own statement; if it fails the data is still correct, just not shrunk.
     */
    private static void migrateFts(Connection c) throws SQLException {
        String sql = null;
        try (Statement st = c.createStatement();
             ResultSet rs = st.executeQuery(
                "SELECT sql FROM sqlite_master WHERE type='table' AND name='messages_fts'")) {
            if (rs.next()) sql = rs.getString(1);
        }
        boolean legacy = sql != null && !sql.contains("content=");
        if (!legacy) {
            try (Statement st = c.createStatement()) {
                for (String stmt : FTS_SCHEMA) st.execute(stmt);
            }
            return;
        }
        c.setAutoCommit(false);
        try (Statement st = c.createStatement()) {
            st.execute("DROP TABLE messages_fts");
            for (String stmt : FTS_SCHEMA) st.execute(stmt);
            st.execute("INSERT INTO messages_fts(messages_fts) VALUES('rebuild')");
            c.commit();
        } catch (SQLException e) {
            c.rollback();
            throw e;
        } finally {
            c.setAutoCommit(true);
        }
        try (Statement st = c.createStatement()) {
            st.execute("VACUUM");
        } catch (SQLException e) {
            System.err.println("aibrain: VACUUM after FTS migration failed: " + e.getMessage());
        }
    }

    private Db() {}
}
