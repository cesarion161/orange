package com.ai.orange.session;

import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Rule-based stand-in for the judge (Phase B): does a session's last message ask the user
 * something? Only the end of the message counts, since that is where a session that wants an
 * answer puts its question.
 */
public final class QuestionDetector {

    /** How many trailing non-blank lines are looked at. */
    static final int TAIL_LINES = 4;

    private static final List<String> PHRASES = List.of("let me know", "should i ", "shall i ", "would you like",
            "do you want", "which option", "which approach", "please confirm", "please advise", "waiting for your",
            "how would you like", "want me to", "your call", "awaiting your");
    /** What may follow the question mark: markdown, closing punctuation, a short aside in brackets. */
    private static final Pattern TRAILER = Pattern.compile("(\\s*\\([^()?]{0,60}\\))?[\\s*_`)\\]\"'>]*$");

    private QuestionDetector() {}

    public static boolean asks(String lastMessage) {
        if (lastMessage == null || lastMessage.isBlank()) return false;
        List<String> lines = lastMessage.lines().map(String::strip).filter(l -> !l.isEmpty()).toList();
        for (String line : lines.subList(Math.max(0, lines.size() - TAIL_LINES), lines.size())) {
            if (TRAILER.matcher(line).replaceAll("").endsWith("?")) return true;
            String lower = line.toLowerCase(Locale.ROOT);
            if (PHRASES.stream().anyMatch(lower::contains)) return true;
        }
        return false;
    }

    /** The line that asks, for an item's title; the last line when none stands out. */
    public static String questionLine(String lastMessage) {
        if (lastMessage == null || lastMessage.isBlank()) return "";
        List<String> lines = lastMessage.lines().map(String::strip).filter(l -> !l.isEmpty()).toList();
        for (int i = lines.size() - 1; i >= Math.max(0, lines.size() - TAIL_LINES); i--) {
            if (TRAILER.matcher(lines.get(i)).replaceAll("").endsWith("?")) return lines.get(i);
        }
        return lines.getLast();
    }
}
