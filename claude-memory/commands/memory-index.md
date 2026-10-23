---
description: Index new Claude Code sessions into memory and segment them into topics
allowed-tools: Bash(java:*)
---
Step 1 — incremental indexing (runs automatically):

!`java -jar "$CLAUDE_PLUGIN_ROOT/bin/memory.jar" ingest`

Step 2 — segment the new/changed sessions into topics:
1. Run `pending` to list sessions without segments (most recent first). This includes **the
   current session** — that is the point of running the command manually: a checkpoint of the
   conversation so far. It is a snapshot (more messages may follow), and a later run refreshes it.
2. For each remaining session: run `spine <sid>`, read the user-message column and identify the
   topics in chronological order — a short `topic` plus a one-sentence `summary`.
3. Write them via stdin (heredoc), in chronological order:
   ```
   java -jar "$CLAUDE_PLUGIN_ROOT/bin/memory.jar" set-segments <sid> <<'EOF'
   [{"topic":"…","summary":"…"}, {"topic":"…","summary":"…"}]
   EOF
   ```
   A single-topic session has a single element.

At the end, report briefly: how many new sessions/messages, and how many sessions you segmented.
