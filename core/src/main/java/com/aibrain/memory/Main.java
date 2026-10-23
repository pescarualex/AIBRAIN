package com.aibrain.memory;

/**
 * Command dispatcher. Ported from the original ingest.py.
 *
 * Usage: java -jar memory.jar <subcommand> [args]
 */
public final class Main {

    public static void main(String[] args) throws Exception {
        String cmd = args.length > 0 ? args[0] : "ingest";
        switch (cmd) {
            case "ingest"       -> Ingest.run(args);
            case "recall"       -> Recall.recall(args);
            case "hook"         -> Recall.hook();
            case "search"       -> Search.search(args);
            case "stats"        -> Search.stats();
            case "pending"      -> Search.pending(args);
            case "spine"        -> Search.spine(args);
            case "set-segments" -> Search.setSegments(args);
            default -> System.err.println("Unknown subcommand: " + cmd);
        }
    }

    private Main() {}
}
