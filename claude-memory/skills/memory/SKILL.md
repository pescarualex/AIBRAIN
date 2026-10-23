---
name: memory
description: >
  Persistent long-term memory of your past Claude Code sessions, stored in a local SQLite
  database. Use whenever you need to recall what was discussed before, answer "what do you
  know/remember about me?", look up a past decision/project/preference, or search prior
  sessions — and to index new sessions into the memory. Works from any directory.
---

# Persistent memory (claude-memory plugin)

A local SQLite database indexes your Claude Code conversation history and keeps curated
topic summaries, so past context can be recalled across sessions.

- **Engine:** a single fat JAR shipped with the plugin at `${CLAUDE_PLUGIN_ROOT}/bin/memory.jar`.
- **Database:** `$AIBRAIN_DB`, or `~/.aibrain/memory.db` by default.
- Run from any directory: `java -jar "$CLAUDE_PLUGIN_ROOT/bin/memory.jar" <subcommand>`.

## When to use it

- At the start of a session, or when the user asks "what do you remember / know about me?",
  run `recall` to bring back recent context. (The SessionStart hook already does this
  automatically.)
- When the user asks about something discussed earlier (a decision, a project, a bug, a
  preference), use `search` with keywords.
- Never invent memories: if `search`/`recall` return nothing, say plainly it is not in memory.

## Subcommands

```
recall [N]          # last N topics (default 5), most recent first
search <words>      # full-text search across the whole transcript (FTS5)
stats               # how many sessions / messages / facts the database holds
ingest              # incrementally index new/changed Claude Code sessions
pending [N]         # sessions not yet segmented (recent first) — TSV
spine <sid>         # only the user messages of a session (cheap to read)
set-segments <sid>  # write the session's topics; reads JSON from stdin
```

**Note:** search only through the JAR, never via the `sqlite3` CLI — the CLI binary often
ships without the `fts5` module compiled in (`no such module: fts5`), while the bundled
driver has it.

## Topic segmentation (how `recall` looks)

A session can touch several topics. `recall`/the hook show one entry **per topic**, not per
session, and within a session the most recent topic appears first. Topics live in the
`segments` table (`session_id, idx, topic, summary`), where `idx` is chronological order.

Segmentation is done by **you (the model)** at `/memory-index`, not by a deterministic script:
1. `pending` → sessions without segments (includes the current session — running
   `/memory-index` manually is a checkpoint of the conversation so far; a later run refreshes it).
2. for each: `spine <sid>`, read the user-message column, identify topics in chronological
   order (short `topic` + one-sentence `summary` + `start_seq`, the `[seq]` where the topic begins).
3. write them: `set-segments <sid>` with `[{"topic":"…","summary":"…","start_seq":0}, …]` on stdin.
   `start_seq` lets the desktop app show just that topic's slice; it is optional (a topic without
   it falls back to the whole session).

A re-indexed session (changed content) loses its segments and reappears in `pending`, so
re-segmentation is automatic. A session with no segments falls back gracefully to its title.
