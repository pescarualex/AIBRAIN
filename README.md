# AIBRAIN

Local-first persistent memory for [Claude Code](https://claude.com/claude-code).

AIBRAIN remembers your previous Claude Code sessions and brings the most recent topics
back into context automatically when a new session starts — so the assistant knows what
you were working on, without you re-explaining. Everything stays on your machine.

## How it works

The `claude-memory` plugin bundles:

- a **SessionStart hook** that recalls your recent topics into context;
- a **`memory` skill** that lets the assistant search and index your history;
- a **`/memory-index`** command to index new sessions and segment them into topics.

Under the hood a small Java core (`memory.jar`) indexes the Claude Code transcripts in
`~/.claude/projects/` into a local SQLite database with full-text search (FTS5).

## Install

```
/plugin marketplace add <this-repo-url>
/plugin install claude-memory@aibrain
```

The plugin requires a Java 17+ runtime on your `PATH`.

## Layout

```
AIBRAIN/
├── .claude-plugin/marketplace.json   # marketplace that lists the plugin
├── claude-memory/                    # the plugin
│   ├── .claude-plugin/plugin.json
│   ├── hooks/hooks.json              # SessionStart -> recall
│   ├── skills/memory/SKILL.md
│   ├── commands/memory-index.md
│   └── bin/memory.jar                # built core (produced by the Maven build)
└── core/                             # Java source for memory.jar
    ├── pom.xml
    └── src/main/java/com/aibrain/memory/
```

## Build the core

```
cd core && mvn -q package
```

This produces `claude-memory/bin/memory.jar`.

## Data location

The database lives at `~/.aibrain/memory.db` (override with the `AIBRAIN_DB`
environment variable). It is never uploaded anywhere.

## Core subcommands

```
java -jar claude-memory/bin/memory.jar <subcommand>

recall [N]          last N topics (default 5), most recent first
search <words>      full-text search across the transcript (FTS5)
stats               session / message / fact counts
ingest              incrementally index new/changed Claude Code sessions
pending [N]         sessions not yet segmented
spine <sid>         user messages of a session
set-segments <sid>  write a session's topics (JSON on stdin)
hook                emit SessionStart additionalContext (used by the hook)
```

## License

MIT
