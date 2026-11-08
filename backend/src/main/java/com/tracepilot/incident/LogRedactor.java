package com.tracepilot.incident;

import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.List;
import java.util.regex.Pattern;

@Component
public class LogRedactor {
    private static final String MASK = "[REDACTED]";
    private static final Pattern ANSI = Pattern.compile(
        "\u001B\\[[0-?]*[ -/]*[@-~]|\u001B\\][^\u0007\u001B]*(?:\u0007|\u001B\\\\)");
    private static final Pattern PRIVATE_KEY = Pattern.compile(
        "-----BEGIN (?:[A-Z0-9]+ )?PRIVATE KEY-----[\\s\\S]*?(?:-----END (?:[A-Z0-9]+ )?PRIVATE KEY-----|\\z)");
    private static final Pattern HEADER = Pattern.compile(
        "(?im)^([^\\n]*?(?:authorization|proxy-authorization|set-cookie|cookie)\\s*:\\s*)[^\\n]*");
    private static final String SECRET_ASSIGNMENT =
        "(?i)(?<![\\w.-])([\"']?[\\w.-]{0,64}(?:password|passwd|pwd|secret|token|credential"
            + "|api[_-]?key|private[_-]?key|access[_-]?key|authorization|cookie)"
            + "[\\w.-]{0,64}[\"']?\\s*[:=]\\s*)";
    private static final Pattern ASSIGNMENT = Pattern.compile(SECRET_ASSIGNMENT);
    private static final Pattern BEARER = Pattern.compile("(?i)\\bBearer[ \\t]+[^\\s\"',;]+");
    private static final Pattern URL_CREDENTIALS = Pattern.compile(
        "(?i)\\b([a-z][a-z0-9+.-]{0,20}://)[^\\s/]*@");
    private static final Pattern TOKENS = Pattern.compile(
        "\\b(?:gh[pousr]_[A-Za-z0-9_]{10,}|github_pat_[A-Za-z0-9_]{10,}"
            + "|(?:AKIA|ASIA)[A-Z0-9]{16}|sk-(?:proj-)?[A-Za-z0-9_-]{16,}"
            + "|(?:sk|rk)_(?:live|test)_[A-Za-z0-9]{10,}|xox[baprs]-[A-Za-z0-9-]{10,}"
            + "|AIza[A-Za-z0-9_-]{20,}|eyJ[A-Za-z0-9_-]+\\.[A-Za-z0-9_-]+\\.[A-Za-z0-9_-]+)\\b");
    private final List<String> configuredSecrets;

    public LogRedactor(Environment environment) {
        var secrets = new ArrayList<String>();
        for (var key : List.of("tracepilot.auth.signing-secret", "tracepilot.github.webhook-secret",
            "spring.datasource.password", "spring.rabbitmq.password")) {
            var value = environment.getProperty(key);
            if (value != null && !value.isBlank()) {
                secrets.add(value);
            }
        }
        configuredSecrets = List.copyOf(secrets);
    }

    public String redact(String raw, List<String> temporarySecrets) {
        var result = normalize(raw);
        var literals = new ArrayList<String>();
        for (var secret : configuredSecrets) {
            addVariants(literals, secret);
        }
        for (var secret : temporarySecrets) {
            addVariants(literals, secret);
        }
        literals.sort(Comparator.comparingInt(String::length).reversed());
        for (var secret : literals) {
            result = result.replace(secret, MASK);
        }
        result = PRIVATE_KEY.matcher(result).replaceAll(MASK);
        result = HEADER.matcher(result).replaceAll("$1" + MASK);
        result = maskAssignments(result);
        result = BEARER.matcher(result).replaceAll("Bearer " + MASK);
        result = URL_CREDENTIALS.matcher(result).replaceAll("$1" + MASK + "@");
        return TOKENS.matcher(result).replaceAll(MASK);
    }

    private void addVariants(List<String> literals, String value) {
        if (value == null || value.isBlank()) {
            return;
        }
        var normalized = normalize(value);
        if (!normalized.isEmpty()) {
            literals.add(normalized);
        }
        literals.add(URLEncoder.encode(value, StandardCharsets.UTF_8));
        literals.add(Base64.getEncoder().encodeToString(value.getBytes(StandardCharsets.UTF_8)));
        literals.add(Base64.getUrlEncoder().withoutPadding().encodeToString(value.getBytes(StandardCharsets.UTF_8)));
    }

    private String maskAssignments(String text) {
        var matcher = ASSIGNMENT.matcher(text);
        var result = new StringBuilder(text.length());
        int cursor = 0;
        while (matcher.find(cursor)) {
            result.append(text, cursor, matcher.end()).append(MASK);
            cursor = valueEnd(text, matcher.end());
            if (cursor == text.length()) {
                break;
            }
        }
        return result.append(text, cursor, text.length()).toString();
    }

    private int valueEnd(String text, int start) {
        if (start == text.length()) {
            return start;
        }
        char quote = text.charAt(start);
        if (quote != '"' && quote != '\'') {
            int newline = text.indexOf('\n', start);
            return newline < 0 ? text.length() : newline;
        }
        // Scan quoted values once, including escaped quotes and multiline/unterminated values.
        for (int index = start + 1; index < text.length(); index++) {
            char current = text.charAt(index);
            if (current == '\\') {
                index++;
            } else if (current == quote) {
                return index + 1;
            }
        }
        return text.length();
    }

    private String normalize(String text) {
        var result = Normalizer.normalize(text, Normalizer.Form.NFKC).replace("\r\n", "\n").replace('\r', '\n');
        result = ANSI.matcher(result).replaceAll("");
        return result.replaceAll("[\\p{Cc}\\p{Cf}&&[^\\n\\t]]", "");
    }
}
