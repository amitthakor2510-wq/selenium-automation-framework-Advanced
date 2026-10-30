package com.automation.core.telegram;

import com.automation.core.mail.RunSummary;

import java.util.List;

/**
 * Builds the Telegram message (Bot API "HTML" parse mode). Every dynamic value is escaped, and the
 * result is trimmed to Telegram's 4096-character limit - the failed-test list gives way first, and the
 * number of tests that did not fit is still shown.
 */
public final class TelegramMessageBuilder {

    /** Telegram's hard limit for one text message. */
    public static final int MAX_MESSAGE_CHARS = 4096;

    private static final int RESERVE = 120;

    private TelegramMessageBuilder() {
    }

    public static String message(RunSummary s, int maxFailuresListed, List<String> notes) {
        StringBuilder head = new StringBuilder();
        head.append(icon(s)).append(" <b>").append(escape(s.status())).append("</b>");
        String where = join(" / ", s.site(), s.browser());
        if (!where.isEmpty()) {
            head.append(" - ").append(escape(where));
        }
        head.append('\n');
        if (notBlank(s.suite())) {
            head.append("Suite: ").append(escape(s.suite())).append('\n');
        }
        head.append("Total ").append(s.total()).append("  |  Passed ").append(s.passed())
            .append("  |  Failed ").append(s.failed()).append("  |  Skipped ").append(s.skipped()).append('\n');
        if (s.durationMs() > 0) {
            head.append("Duration: ").append(duration(s.durationMs())).append('\n');
        }
        if (isHttp(s.ciLink())) {
            head.append("<a href=\"").append(escapeAttribute(s.ciLink())).append("\">Open CI run</a>\n");
        }

        StringBuilder tail = new StringBuilder();
        for (String note : notes) {
            tail.append("\nNote: ").append(escape(note)).append('\n');
        }

        StringBuilder out = new StringBuilder(head);
        List<RunSummary.FailedTest> failures = s.failures();
        if (!failures.isEmpty() && maxFailuresListed > 0) {
            out.append("\n<b>Failed tests</b>\n");
            int budget = MAX_MESSAGE_CHARS - head.length() - tail.length() - RESERVE;
            int used = 0;
            int shown = 0;
            for (RunSummary.FailedTest f : failures) {
                if (shown >= maxFailuresListed) {
                    break;
                }
                String line = "- " + escape(f.className() + "." + f.name())
                    + (notBlank(f.message()) ? ": " + escape(f.message()) : "") + "\n";
                if (used + line.length() > budget) {
                    break;
                }
                out.append(line);
                used += line.length();
                shown++;
            }
            if (shown < failures.size()) {
                out.append("... and ").append(failures.size() - shown).append(" more\n");
            }
        }
        out.append(tail);
        String text = out.toString().stripTrailing();
        return text.length() <= MAX_MESSAGE_CHARS ? text : hardCut(text);
    }

    /** Last resort - cut at a line break so no HTML tag or entity is left half-open. */
    private static String hardCut(String text) {
        int cut = text.lastIndexOf('\n', MAX_MESSAGE_CHARS - 4);
        return (cut > 0 ? text.substring(0, cut) : text.substring(0, MAX_MESSAGE_CHARS - 4)) + "\n...";
    }

    private static String icon(RunSummary s) {
        return switch (s.status()) {
            case "PASSED" -> "\u2705";
            case "FAILED" -> "\u274C";
            default -> "\u26A0\uFE0F";
        };
    }

    /** Escapes the three characters Telegram's HTML mode treats as markup. */
    public static String escape(String s) {
        if (s == null) {
            return "";
        }
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

    private static String escapeAttribute(String s) {
        return escape(s).replace("\"", "&quot;");
    }

    static String duration(long ms) {
        long totalSeconds = Math.max(0, ms / 1000);
        long h = totalSeconds / 3600;
        long m = (totalSeconds % 3600) / 60;
        long sec = totalSeconds % 60;
        if (h > 0) {
            return h + "h " + m + "m " + sec + "s";
        }
        return m > 0 ? m + "m " + sec + "s" : sec + "s";
    }

    private static boolean isHttp(String link) {
        return link != null && (link.startsWith("https://") || link.startsWith("http://"));
    }

    private static boolean notBlank(String s) {
        return s != null && !s.isBlank();
    }

    private static String join(String sep, String a, String b) {
        if (notBlank(a) && notBlank(b)) {
            return a + sep + b;
        }
        return notBlank(a) ? a : notBlank(b) ? b : "";
    }
}
