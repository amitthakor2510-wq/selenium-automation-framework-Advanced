package com.automation.core.mail;

import com.automation.core.license.LicenseReportWriter;

import java.util.List;
import java.util.regex.Pattern;

/** Builds the subject and the HTML / plain-text bodies. Every dynamic value is escaped. */
public final class EmailReportBuilder {

    private static final int MAX_FAILURES_LISTED = 20;
    private static final int MAX_MESSAGE_CHARS = 300;
    private static final Pattern SECRET = Pattern.compile(
        "(?i)\\b(password|passwd|pwd|secret|token|api[_-]?key|authorization)(\\s*[:=]\\s*)\\S+");
    private static final Pattern BEARER = Pattern.compile("(?i)\\b(bearer|basic)(\\s+)[A-Za-z0-9._~+/=-]{8,}");

    private EmailReportBuilder() {
    }

    /** Fills {@code {status} {site} {browser} {suite} {total} {passed} {failed} {skipped}}; strips line breaks. */
    public static String subject(String template, RunSummary s) {
        String subject = template
            .replace("{status}", s.status())
            .replace("{site}", nullToEmpty(s.site()))
            .replace("{browser}", nullToEmpty(s.browser()))
            .replace("{suite}", nullToEmpty(s.suite()))
            .replace("{total}", String.valueOf(s.total()))
            .replace("{passed}", String.valueOf(s.passed()))
            .replace("{failed}", String.valueOf(s.failed()))
            .replace("{skipped}", String.valueOf(s.skipped()));
        // A CR/LF in a subject would let a test name inject mail headers.
        return subject.replaceAll("[\\r\\n]+", " ").replaceAll("\\s{2,}", " ").trim();
    }

    /** Masks "password=..." style secrets and shortens to a readable length. */
    public static String sanitizeMessage(String message) {
        if (message == null) {
            return "";
        }
        String oneLine = message.replaceAll("\\s+", " ").trim();
        String masked = SECRET.matcher(BEARER.matcher(oneLine).replaceAll("$1$2****")).replaceAll("$1$2****");
        return masked.length() <= MAX_MESSAGE_CHARS ? masked : masked.substring(0, MAX_MESSAGE_CHARS) + "...";
    }

    public static String text(RunSummary s, List<String> notes) {
        StringBuilder sb = new StringBuilder();
        sb.append("Test run ").append(s.status()).append('\n');
        appendLine(sb, "Site", s.site());
        appendLine(sb, "Browser", s.browser());
        appendLine(sb, "Suite", s.suite());
        sb.append("Total: ").append(s.total()).append("  Passed: ").append(s.passed())
            .append("  Failed: ").append(s.failed()).append("  Skipped: ").append(s.skipped()).append('\n');
        if (s.durationMs() > 0) {
            sb.append("Duration: ").append(duration(s.durationMs())).append('\n');
        }
        if (isHttp(s.ciLink())) {
            sb.append("CI run: ").append(s.ciLink()).append('\n');
        }
        if (!s.failures().isEmpty()) {
            sb.append("\nFailed tests:\n");
            int shown = 0;
            for (RunSummary.FailedTest f : s.failures()) {
                if (shown++ == MAX_FAILURES_LISTED) {
                    sb.append("  ... and ").append(s.failures().size() - MAX_FAILURES_LISTED).append(" more\n");
                    break;
                }
                sb.append("  - ").append(f.className()).append('.').append(f.name()).append(": ")
                    .append(f.message()).append('\n');
            }
        }
        if (s.license() != null) {
            sb.append('\n').append(licenseLine(s.license())).append('\n');
        }
        for (String note : notes) {
            sb.append("\nNote: ").append(note).append('\n');
        }
        return sb.toString();
    }

    public static String html(RunSummary s, List<String> notes) {
        String banner = "FAILED".equals(s.status()) ? "#ff5454" : "NO TESTS".equals(s.status()) ? "#fabb3d" : "#79c447";
        StringBuilder sb = new StringBuilder(4096);
        sb.append("<html><body style=\"font-family:Arial,Helvetica,sans-serif;color:#222\">")
            .append("<table align=\"center\" cellpadding=\"6\" style=\"border-collapse:collapse\">")
            .append("<tr><td colspan=\"4\" align=\"center\" style=\"background:").append(banner)
            .append(";color:#fff;font-size:20px;font-weight:bold\">Test run ").append(esc(s.status()))
            .append("</td></tr><tr>")
            .append(box("#67c2ef", s.total(), "Total")).append(box("#79c447", s.passed(), "Passed"))
            .append(box("#ff5454", s.failed(), "Failed")).append(box("#fabb3d", s.skipped(), "Skipped"))
            .append("</tr></table>");

        sb.append("<table cellpadding=\"4\" style=\"margin-top:12px;font-size:14px\">");
        row(sb, "Site", s.site());
        row(sb, "Browser", s.browser());
        row(sb, "Suite", s.suite());
        if (s.durationMs() > 0) {
            row(sb, "Duration", duration(s.durationMs()));
        }
        if (isHttp(s.ciLink())) {
            sb.append("<tr><td><b>CI run</b></td><td><a href=\"").append(esc(s.ciLink())).append("\">")
                .append(esc(s.ciLink())).append("</a></td></tr>");
        }
        sb.append("</table>");

        if (!s.failures().isEmpty()) {
            sb.append("<h3>Failed tests</h3><table cellpadding=\"4\" border=\"1\" ")
                .append("style=\"border-collapse:collapse;font-size:13px\"><tr><th>Test</th><th>Message</th></tr>");
            int shown = 0;
            for (RunSummary.FailedTest f : s.failures()) {
                if (shown++ == MAX_FAILURES_LISTED) {
                    sb.append("<tr><td colspan=\"2\">... and ").append(s.failures().size() - MAX_FAILURES_LISTED)
                        .append(" more</td></tr>");
                    break;
                }
                sb.append("<tr><td>").append(esc(f.className())).append('.').append(esc(f.name()))
                    .append("</td><td>").append(esc(f.message())).append("</td></tr>");
            }
            sb.append("</table>");
        }
        if (s.license() != null) {
            String color = s.license().failing() > 0 ? "#b00020" : "#222";
            sb.append("<h3>Dependency licenses</h3><p style=\"color:").append(color).append("\">")
                .append(esc(licenseLine(s.license()))).append("</p>");
        }
        for (String note : notes) {
            sb.append("<p><i>").append(esc(note)).append("</i></p>");
        }
        sb.append("</body></html>");
        return sb.toString();
    }

    private static String licenseLine(LicenseReportWriter.Summary l) {
        return "License scan: " + l.total() + " dependencies - " + l.allowed() + " allowed, " + l.review()
            + " need review, " + l.denied() + " denied, " + l.missing() + " with no license found";
    }

    private static String box(String color, int value, String label) {
        return "<td><table style=\"background:" + color + ";width:120px\"><tr><td align=\"center\" "
            + "style=\"font-size:32px\">" + value + "</td></tr><tr><td align=\"center\">" + label
            + "</td></tr></table></td>";
    }

    private static void row(StringBuilder sb, String label, String value) {
        if (value != null && !value.isBlank()) {
            sb.append("<tr><td><b>").append(label).append("</b></td><td>").append(esc(value)).append("</td></tr>");
        }
    }

    private static void appendLine(StringBuilder sb, String label, String value) {
        if (value != null && !value.isBlank()) {
            sb.append(label).append(": ").append(value).append('\n');
        }
    }

    static String duration(long ms) {
        long totalSeconds = ms / 1000;
        long h = totalSeconds / 3600;
        long m = (totalSeconds % 3600) / 60;
        long sec = totalSeconds % 60;
        return h > 0 ? h + "h " + m + "m " + sec + "s" : m > 0 ? m + "m " + sec + "s" : sec + "s";
    }

    /** Only http(s) links are rendered as links - never javascript: or data: URLs from an env var. */
    private static boolean isHttp(String link) {
        if (link == null) {
            return false;
        }
        String l = link.toLowerCase(java.util.Locale.ROOT);
        return l.startsWith("https://") || l.startsWith("http://");
    }

    private static String nullToEmpty(String s) {
        return s == null ? "" : s;
    }

    private static String esc(String s) {
        if (s == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder(s.length() + 16);
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '&' -> sb.append("&amp;");
                case '<' -> sb.append("&lt;");
                case '>' -> sb.append("&gt;");
                case '"' -> sb.append("&quot;");
                case '\'' -> sb.append("&#39;");
                default -> sb.append(c);
            }
        }
        return sb.toString();
    }
}
