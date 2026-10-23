package com.aibrain.memory;

import com.fasterxml.jackson.databind.JsonNode;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;

/**
 * Text helpers: timestamps, truncation, noise detection and readable-text
 * extraction from message content blocks.
 */
final class Content {

    static final int MAX_MSG = 8000;          // per-message truncation
    static final int MAX_TOOL_RESULT = 400;   // tool output: only a fragment

    static String now() {
        return OffsetDateTime.now(ZoneOffset.UTC).truncatedTo(ChronoUnit.SECONDS).toString();
    }

    static String clip(String s) {
        return clip(s, MAX_MSG);
    }

    static String clip(String s, int n) {
        s = s == null ? "" : s.strip();
        return s.length() <= n ? s : s.substring(0, n) + " …[truncated]";
    }

    static boolean isNoise(String txt) {
        String t = txt.strip();
        if (t.isEmpty()) return true;
        if (t.startsWith("<")) {
            String head = t.substring(0, Math.min(200, t.length()));
            return head.contains("system-reminder")
                || head.contains("command-")
                || head.contains("local-command");
        }
        return false;
    }

    /** Extract readable text from a content node (a string or a list of blocks). */
    static String blocksText(JsonNode content) {
        if (content == null || content.isNull()) return "";
        if (content.isTextual()) return content.asText();
        if (!content.isArray()) return "";

        StringBuilder out = new StringBuilder();
        for (JsonNode b : content) {
            if (!b.isObject()) continue;
            String piece = switch (b.path("type").asText("")) {
                case "text" -> b.path("text").asText("");
                case "tool_use" -> {
                    String name = b.path("name").asText("tool");
                    JsonNode inp = b.path("input");
                    String desc = firstNonEmpty(
                        inp.path("description").asText(""),
                        inp.path("command").asText(""),
                        inp.path("file_path").asText(""));
                    yield "[tool:" + name + "] " + cut(desc, 200);
                }
                case "tool_result" -> {
                    JsonNode c = b.path("content");
                    String txt = c.isArray() ? blocksText(c) : c.asText("");
                    yield "[tool-result] " + cut(txt, MAX_TOOL_RESULT);
                }
                default -> "";
            };
            if (!piece.isEmpty()) {
                if (out.length() > 0) out.append("\n");
                out.append(piece);
            }
        }
        return out.toString();
    }

    private static String firstNonEmpty(String... xs) {
        for (String x : xs) if (x != null && !x.isEmpty()) return x;
        return "";
    }

    private static String cut(String s, int n) {
        if (s == null) return "";
        return s.length() <= n ? s : s.substring(0, n);
    }

    private Content() {}
}
