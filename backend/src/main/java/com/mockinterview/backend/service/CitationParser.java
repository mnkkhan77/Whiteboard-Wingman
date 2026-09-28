package com.mockinterview.backend.service;

import java.util.List;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Extracts the source numbers an answer actually cites. Accepts the instructed "[n]" plus the
 * "[1, 3]" grouping models often produce anyway; numbers outside 1..sourceCount (hallucinated
 * sources, or a literal "[2024]" in the text) are dropped rather than handed to the UI.
 */
final class CitationParser {

    private static final Pattern MARKER = Pattern.compile("\\[(\\d{1,3}(?:\\s*,\\s*\\d{1,3})*)]");

    private CitationParser() {
    }

    /** Distinct valid source numbers, ascending. */
    static List<Integer> cited(String answer, int sourceCount) {
        TreeSet<Integer> cited = new TreeSet<>();
        if (answer == null) {
            return List.of();
        }
        Matcher matcher = MARKER.matcher(answer);
        while (matcher.find()) {
            for (String part : matcher.group(1).split(",")) {
                int n = Integer.parseInt(part.trim());
                if (n >= 1 && n <= sourceCount) {
                    cited.add(n);
                }
            }
        }
        return List.copyOf(cited);
    }

    /** The answer without its citation markers — used for history turns sent back to the model,
     *  whose numbers referred to that turn's sources, not the current ones. */
    static String stripMarkers(String answer) {
        return MARKER.matcher(answer).replaceAll("").replaceAll("[ \\t]+([.,;:!?])", "$1");
    }
}
