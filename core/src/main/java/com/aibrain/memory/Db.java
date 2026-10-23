package com.aibrain.memory;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;

/**
 * SQLite connection and schema.
 *
 * Database location: the {@code AIBRAIN_DB} environment variable if set,
 * otherwise {@code ~/.aibrain/memory.db}.
 */
final class Db {

    static final String SCHEMA = """
        CREATE TABLE IF NOT EXISTS sessions(
          id TEXT PRIMARY KEY, source TEXT, project TEXT, title TEXT,
          started_at TEXT, ended_at TEXT, msg_count INTEGER, summary TEXT,
          src_path TEXT, src_sig TEXT, indexed_at TEXT);
        CREATE TABLE IF NOT EXISTS messages(
          id INTEGER PRIMARY KEY AUTOINCREMENT, session_id TEXT, seq INTEGER,
          role TEXT, ts TEXT, text TEXT);
        CREATE INDEX IF NOT EXISTS idx_msg_session ON messages(session_id);
        CREATE VIRTUAL TABLE IF NOT EXISTS messages_fts USING fts5(session_id UNINDEXED, text);
        CREATE TABLE IF NOT EXISTS facts(
          id INTEGER PRIMARY KEY AUTOINCREMENT, category TEXT, topic TEXT,
          content TEXT, source_session TEXT, created_at TEXT, updated_at TEXT);
        CREATE TABLE IF NOT EXISTS segments(
          session_id TEXT, idx INTEGER, topic TEXT, summary TEXT, created_at TEXT,
          PRIMARY KEY(session_id, idx));
        CREATE INDEX IF NOT EXISTS idx_seg_session ON segments(session_id);
        CREATE TABLE IF NOT EXISTS meta(key TEXT PRIMARY KEY, value TEXT);
        """;

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
            for (String stmt : SCHEMA.split(";")) {
                if (!stmt.isBlank()) st.execute(stmt);
            }
        }
        return c;
    }

    private Db() {}
}
