package com.tracepilot.incident;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.regex.Pattern;

import org.springframework.stereotype.Component;

/** Selects bounded original log ranges. Callers must redact before selection. */
@Component
public class DiagnosticLogSelector {
    private static final String GAP = "\n[... omitted ...]\n";
    private static final Pattern CAUSE = Pattern.compile("(?i)caused by:|root cause|fatal error");
    private static final Pattern FAILURE = Pattern.compile(
        "(?i)\\berror\\b|\\bexception\\b|\\bfailed\\b|\\bfailure\\b|\\bfatal\\b|"
            + "assertionerror|connection refused|cannot find|not found|permission denied|\\bTS\\d{4}\\b");
    private static final Pattern SUMMARY = Pattern.compile(
        "(?i)process (?:completed|exited)|exit code|build failure|tests run:");

    public String select(String redacted, int maximum) {
        if (maximum < 100) {
            throw new IllegalArgumentException("Diagnostic log budget must be at least 100 characters.");
        }
        if (redacted.length() <= maximum) {
            return redacted;
        }
        var lines = lines(redacted);
        var candidates = new ArrayList<Candidate>();
        for (int index = 0; index < lines.size(); index++) {
            var line = lines.get(index);
            var value = redacted.substring(line.start(), line.end());
            int priority = CAUSE.matcher(value).find() ? 3 : FAILURE.matcher(value).find() ? 2 : 0;
            if (SUMMARY.matcher(value).find()) {
                priority = 1;
            }
            if (priority > 0) {
                candidates.add(new Candidate(index, priority));
            }
        }
        candidates.sort(Comparator.comparingInt(Candidate::priority).reversed()
            .thenComparingInt(Candidate::line));
        if (candidates.isEmpty()) {
            return redacted.substring(safeStart(redacted, redacted.length() - maximum));
        }
        var selected = new ArrayList<Range>();
        int footer = Math.min(800, maximum / 5);
        selected.add(new Range(safeStart(redacted, redacted.length() - footer), redacted.length()));
        for (var candidate : candidates) {
            var focus = lines.get(candidate.line());
            if (selected.stream().anyMatch(range -> range.start() <= focus.start() && range.end() >= focus.end())) {
                continue;
            }
            int window = Math.min(1600, (maximum - footer - 2 * GAP.length()) / 2);
            int before = Math.min(focus.start() - lines.get(Math.max(0, candidate.line() - 2)).start(), window / 4);
            int start = safeStart(redacted, focus.start() - before);
            int end = safeEnd(redacted,
                Math.min(lines.get(Math.min(lines.size() - 1, candidate.line() + 8)).end(), start + window));
            var proposed = new ArrayList<>(selected);
            proposed.add(new Range(start, end));
            proposed = merge(proposed);
            if (length(proposed) <= maximum) {
                selected = proposed;
            }
            if (selected.size() >= 8) {
                break;
            }
        }
        var result = new StringBuilder();
        for (var range : merge(selected)) {
            if (!result.isEmpty()) {
                result.append(GAP);
            }
            result.append(redacted, range.start(), range.end());
        }
        return result.toString();
    }

    private List<Range> lines(String text) {
        var result = new ArrayList<Range>();
        int start = 0;
        while (start < text.length()) {
            int newline = text.indexOf('\n', start);
            int end = newline < 0 ? text.length() : newline + 1;
            result.add(new Range(start, end));
            start = end;
        }
        return result;
    }

    private ArrayList<Range> merge(List<Range> ranges) {
        var sorted = ranges.stream().sorted(Comparator.comparingInt(Range::start)).toList();
        var result = new ArrayList<Range>();
        for (var range : sorted) {
            if (!result.isEmpty() && result.getLast().end() >= range.start()) {
                var previous = result.removeLast();
                result.add(new Range(previous.start(), Math.max(previous.end(), range.end())));
            } else {
                result.add(range);
            }
        }
        return result;
    }

    private int length(List<Range> ranges) {
        return ranges.stream().mapToInt(range -> range.end() - range.start()).sum() + (ranges.size() - 1) * GAP.length();
    }

    private int safeStart(String text, int start) {
        return start > 0 && start < text.length() && Character.isLowSurrogate(text.charAt(start)) ? start + 1 : start;
    }

    private int safeEnd(String text, int end) {
        return end > 0 && end < text.length() && Character.isHighSurrogate(text.charAt(end - 1)) ? end - 1 : end;
    }

    private record Range(int start, int end) {
    }

    private record Candidate(int line, int priority) {
    }
}
