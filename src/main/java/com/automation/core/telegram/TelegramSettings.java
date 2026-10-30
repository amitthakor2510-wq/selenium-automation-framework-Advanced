package com.automation.core.telegram;

import java.net.URI;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.UnaryOperator;
import java.util.regex.Pattern;

/**
 * Telegram report configuration. Every key can come from (in order) a {@code -D} system property,
 * the properties files ({@code global.properties} / the site file), or an environment variable named
 * after the key ({@code telegram.bot.token} -> {@code TELEGRAM_BOT_TOKEN}) - so the bot token never has
 * to be written into a file. The token has no default, is never logged and is masked in
 * {@link #toString()}. The environment variable names are the same ones the CI notify job
 * ({@code notify_chat.py}) uses, so one set of CI secrets serves both.
 */
public final class TelegramSettings {

    /** When a report is sent. */
    public enum SendOn { ALWAYS, FAILURE, NEVER }

    /** {@code 123456789} / {@code -1001234567890} (groups, channels) or {@code @publicchannel}. */
    private static final Pattern CHAT_ID = Pattern.compile("-?\\d{1,20}|@[A-Za-z][A-Za-z0-9_]{3,31}");
    private static final Pattern THREAD_ID = Pattern.compile("\\d{1,12}");

    private final boolean enabled;
    private final boolean dryRun;
    private final String token;
    private final String chatId;
    private final String threadId;
    private final String apiBase;
    private final SendOn sendOn;
    private final boolean silent;
    private final boolean attachPdf;
    private final boolean attachReports;
    private final long maxFileBytes;
    private final int maxFailuresListed;
    private final int timeoutMillis;
    private final boolean failOnError;
    private final Path dryRunDir;
    private final Path pdfDir;
    private final Path extentDir;
    private final Path zipFile;

    private TelegramSettings(UnaryOperator<String> lookup, Map<String, String> env) {
        this.enabled = bool(lookup, env, "telegram.enabled", false);
        this.dryRun = bool(lookup, env, "telegram.dryRun", false);
        this.token = str(lookup, env, "telegram.bot.token", "");
        this.chatId = str(lookup, env, "telegram.chat.id", "");
        this.threadId = str(lookup, env, "telegram.thread.id", "");
        this.apiBase = stripTrailingSlash(str(lookup, env, "telegram.apiBase", "https://api.telegram.org"));
        this.sendOn = sendOn(str(lookup, env, "telegram.sendOn", "always"));
        this.silent = bool(lookup, env, "telegram.silent", false);
        this.attachPdf = bool(lookup, env, "telegram.attach.pdf", true);
        this.attachReports = bool(lookup, env, "telegram.attach.reports", true);
        // The Bot API accepts files up to 50 MB; stay below it.
        this.maxFileBytes = Math.max(0, integer(lookup, env, "telegram.attach.maxFileMb", 45)) * 1024L * 1024L;
        this.maxFailuresListed = Math.max(0, integer(lookup, env, "telegram.maxFailuresListed", 10));
        this.timeoutMillis = Math.max(1000, integer(lookup, env, "telegram.timeoutMs", 20000));
        this.failOnError = bool(lookup, env, "telegram.failOnError", false);
        this.dryRunDir = Path.of(str(lookup, env, "telegram.dryRun.dir", "target/telegram"));
        this.pdfDir = Path.of(str(lookup, env, "report.pdf.output.dir", "target/pdf-reports"));
        this.extentDir = Path.of(str(lookup, env, "telegram.reports.dir", "target/extent-reports"));
        this.zipFile = Path.of(str(lookup, env, "report.zip.output", "target/report-archives/extent-reports.zip"));
    }

    /** {@code lookup} returns a configured value or null/blank; {@code env} is the environment. */
    public static TelegramSettings of(UnaryOperator<String> lookup, Map<String, String> env) {
        return new TelegramSettings(lookup, env);
    }

    private static String str(UnaryOperator<String> lookup, Map<String, String> env, String key, String dflt) {
        String v = lookup.apply(key);
        if (v == null || v.isBlank()) {
            v = env.get(key.toUpperCase(Locale.ROOT).replace('.', '_'));
        }
        return v == null || v.isBlank() ? dflt : v.trim();
    }

    private static boolean bool(UnaryOperator<String> lookup, Map<String, String> env, String key, boolean dflt) {
        return Boolean.parseBoolean(str(lookup, env, key, String.valueOf(dflt)));
    }

    private static int integer(UnaryOperator<String> lookup, Map<String, String> env, String key, int dflt) {
        try {
            return Integer.parseInt(str(lookup, env, key, String.valueOf(dflt)));
        } catch (NumberFormatException e) {
            return dflt;
        }
    }

    private static SendOn sendOn(String raw) {
        try {
            return SendOn.valueOf(raw.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return SendOn.ALWAYS;
        }
    }

    private static String stripTrailingSlash(String s) {
        String out = s;
        while (out.endsWith("/")) {
            out = out.substring(0, out.length() - 1);
        }
        return out;
    }

    /**
     * Problems that would make sending impossible or unsafe; empty = ready. Dry-run needs nothing
     * (nothing is sent), a real send needs the token and chat id.
     */
    public List<String> problems() {
        List<String> out = new ArrayList<>();
        if (!chatId.isBlank() && !CHAT_ID.matcher(chatId).matches()) {
            out.add("telegram.chat.id must be a number (groups are negative, e.g. -1001234567890) or @channelname");
        }
        if (!threadId.isBlank() && !THREAD_ID.matcher(threadId).matches()) {
            out.add("telegram.thread.id must be a number");
        }
        if (dryRun) {
            return out;
        }
        if (token.isBlank()) {
            out.add("telegram.bot.token is not set (use the TELEGRAM_BOT_TOKEN environment variable)");
        }
        if (chatId.isBlank()) {
            out.add("telegram.chat.id is not set (use the TELEGRAM_CHAT_ID environment variable)");
        }
        if (!isSafeApiBase(apiBase)) {
            out.add("telegram.apiBase must be an https URL (plain http is accepted only for localhost) - "
                + "the bot token is part of every request URL");
        }
        return out;
    }

    /** https always; http only to the loopback interface (the local test server). */
    static boolean isSafeApiBase(String base) {
        try {
            URI uri = URI.create(base);
            String scheme = uri.getScheme();
            String host = uri.getHost();
            if (scheme == null || host == null) {
                return false;
            }
            if ("https".equalsIgnoreCase(scheme)) {
                return true;
            }
            return "http".equalsIgnoreCase(scheme)
                && ("localhost".equalsIgnoreCase(host) || "127.0.0.1".equals(host) || "[::1]".equals(host));
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    public boolean enabled() {
        return enabled;
    }

    public boolean dryRun() {
        return dryRun;
    }

    /** The bot token - callers must never log it. */
    public String token() {
        return token;
    }

    public String chatId() {
        return chatId;
    }

    /** Forum topic id, or blank. */
    public String threadId() {
        return threadId;
    }

    public String apiBase() {
        return apiBase;
    }

    public SendOn sendOn() {
        return sendOn;
    }

    public boolean silent() {
        return silent;
    }

    public boolean attachPdf() {
        return attachPdf;
    }

    public boolean attachReports() {
        return attachReports;
    }

    public long maxFileBytes() {
        return maxFileBytes;
    }

    public int maxFailuresListed() {
        return maxFailuresListed;
    }

    public int timeoutMillis() {
        return timeoutMillis;
    }

    public boolean failOnError() {
        return failOnError;
    }

    public Path dryRunDir() {
        return dryRunDir;
    }

    public Path pdfDir() {
        return pdfDir;
    }

    public Path extentDir() {
        return extentDir;
    }

    public Path zipFile() {
        return zipFile;
    }

    @Override
    public String toString() {
        return "TelegramSettings[enabled=" + enabled + ", dryRun=" + dryRun
            + ", token=" + (token.isBlank() ? "(none)" : "****") + ", chatId=" + chatId
            + ", threadId=" + threadId + ", sendOn=" + sendOn + "]";
    }
}
