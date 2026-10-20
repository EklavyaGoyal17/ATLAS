package dev.atlas.intelligence;

import java.util.Locale;
import java.util.regex.Pattern;

public final class Redaction {

    private Redaction() {}

    private static final Pattern CREDENTIAL_NAME = Pattern.compile(
        "(?i)(?:password|passwd|secret|token|api[_-]?key|authorization|credential)"
    );
    private static final Pattern KNOWN = Pattern.compile(
        "(?:AKIA[0-9A-Z]{16}|gh[pousr]_[A-Za-z0-9]{20,}|sk-[A-Za-z0-9_-]{16,}|Bearer\\s+[A-Za-z0-9._-]{12,})"
    );

    public static String mask(String input) {
        // Scan each maximal identifier once. An unanchored greedy identifier
        // regex repeatedly rescans long source tokens and can block an import.
        for (String line : input.split("\\R", -1)) {
            int begin = line.indexOf("-----BEGIN");
            if (
                begin >= 0 && line.indexOf("PRIVATE KEY-----", begin) >= 0
            ) return "[File withheld: private key material detected]";
        }
        var result = new StringBuilder(input.length());
        var names = CREDENTIAL_NAME.matcher(input);
        int copied = 0;
        for (int position = 0; position < input.length(); ) {
            if (!identifier(input.charAt(position))) {
                position++;
                continue;
            }
            int start = position;
            while (
                    position < input.length() &&
                    identifier(input.charAt(position))
                )
                position++;
            if (!names.region(start, position).find()) continue;
            int separator = position;
            if (
                separator < input.length() &&
                (input.charAt(separator) == '\'' ||
                    input.charAt(separator) == '"')
            ) separator++;
            while (
                    separator < input.length() &&
                    whitespace(input.charAt(separator))
                )
                separator++;
            if (
                separator >= input.length() ||
                (input.charAt(separator) != ':' &&
                    input.charAt(separator) != '=')
            ) continue;
            int value = separator + 1;
            while (value < input.length() && whitespace(input.charAt(value)))
                value++;
            int end = value;
            while (end < input.length() && !lineBreak(input.charAt(end))) end++;
            if (end == value) continue;
            result.append(input, copied, value).append("[REDACTED]");
            copied = end;
            position = end;
        }
        result.append(input, copied, input.length());
        return KNOWN.matcher(result).replaceAll(match -> {
            var replacement = new StringBuilder("[REDACTED]");
            // A Bearer separator can span lines. Keep those separators so
            // declarations below it retain their original citation numbers.
            String token = match.group();
            for (int i = 0; i < token.length(); i++) {
                char separator = token.charAt(i);
                if (!lineBreak(separator)) continue;
                replacement.append(separator);
                if (
                    separator == '\r' &&
                    i + 1 < token.length() &&
                    token.charAt(i + 1) == '\n'
                ) replacement.append(token.charAt(++i));
                // Preserve separate CR and LF lines when token text between
                // them disappears instead of accidentally forming one CRLF.
                replacement.append("[REDACTED]");
            }
            return replacement.toString();
        });
    }

    public static boolean injection(String text) {
        String lower = text.toLowerCase(Locale.ROOT);
        if (
            lower.contains("system prompt") ||
            lower.contains("execute this command") ||
            lower.contains("execute the command") ||
            lower.contains("you are now") ||
            ignoreInstructions(lower)
        ) return true;
        for (String line : lower.split("\\R", -1)) {
            int send = line.indexOf("send "),
                override = line.indexOf("override ");
            if (
                (send >= 0 && line.lastIndexOf("secret") >= send + 5) ||
                (override >= 0 &&
                    line.lastIndexOf("instructions") >= override + 9)
            ) return true;
        }
        return false;
    }

    private static boolean ignoreInstructions(String text) {
        int start = text.indexOf("ignore ");
        while (start >= 0) {
            int next = start + 7;
            while (true) {
                if (text.startsWith("all ", next)) next += 4;
                else if (text.startsWith("previous ", next)) next += 9;
                else if (text.startsWith("prior ", next)) next += 6;
                else break;
            }
            if (text.startsWith("instructions", next)) return true;
            start = text.indexOf("ignore ", next);
        }
        return false;
    }

    private static boolean identifier(char c) {
        return (
            (c >= 'a' && c <= 'z') ||
            (c >= 'A' && c <= 'Z') ||
            (c >= '0' && c <= '9') ||
            c == '_' ||
            c == '.' ||
            c == '-'
        );
    }

    private static boolean whitespace(char c) {
        return (
            c == ' ' ||
            c == '\t' ||
            c == '\n' ||
            c == '\r' ||
            c == '\f' ||
            c == '\u000B'
        );
    }

    private static boolean lineBreak(char c) {
        return (
            c == '\n' ||
            c == '\r' ||
            c == '\f' ||
            c == '\u000B' ||
            c == '\u0085' ||
            c == '\u2028' ||
            c == '\u2029'
        );
    }
}
