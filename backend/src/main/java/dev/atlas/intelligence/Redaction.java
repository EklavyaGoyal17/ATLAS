package dev.atlas.intelligence;

import java.util.regex.Pattern;

public final class Redaction {

    private Redaction() {}

    private static final Pattern ASSIGNMENT = Pattern.compile(
        "(?i)([\\w.-]*(?:password|passwd|secret|token|api[_-]?key|authorization|credential)[\\w.-]*[\\\"']?\\s*[:=]\\s*)([^\\r\\n]+)"
    );
    private static final Pattern KNOWN = Pattern.compile(
        "(?:AKIA[0-9A-Z]{16}|gh[pousr]_[A-Za-z0-9]{20,}|sk-[A-Za-z0-9_-]{16,}|Bearer\\s+[A-Za-z0-9._-]{12,})"
    );

    public static String mask(String input) {
        // Preserve newline counts so source citations remain valid. Remove private-key bodies entirely.
        String value = input.replaceAll(
            "(?s)-----BEGIN[^\\n]*PRIVATE KEY-----.*?-----END[^\\n]*PRIVATE KEY-----",
            "[REDACTED PRIVATE KEY]"
        );
        if (
            !value.equals(input)
        ) return "[File withheld: private key material detected]";
        return KNOWN.matcher(
            ASSIGNMENT.matcher(value).replaceAll("$1[REDACTED]")
        ).replaceAll("[REDACTED]");
    }

    public static boolean injection(String text) {
        return Pattern.compile(
            "(?i)(ignore (all |previous |prior )*instructions|system prompt|send .*secret|execute (this|the) command|you are now|override .*instructions)"
        )
            .matcher(text)
            .find();
    }
}
