package com.automation.core.telegram;

import com.automation.core.mail.CiLinks;
import com.automation.core.mail.RunSummary;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Decides whether to send, builds the message, gathers the report files and sends them to the chat.
 * The Telegram counterpart of {@code EmailService}.
 */
public final class TelegramService {

    /** What {@link #deliver} did. {@code sent} is also true for a dry run that wrote the preview. */
    public record Outcome(boolean sent, String message) {
    }

    private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss-SSS")
        .withZone(ZoneOffset.UTC);

    private TelegramService() {
    }

    /**
     * @param attachSince only files modified at/after this instant are uploaded (null = no limit)
     * @throws TelegramException when sending the summary failed, or the settings are unusable. A report
     *                           file that fails to upload does not throw: it is named in the outcome.
     */
    public static Outcome deliver(TelegramSettings settings, RunSummary summary, Instant attachSince,
                                  Map<String, String> env) throws TelegramException {
        if (!settings.enabled()) {
            return new Outcome(false, "Telegram report disabled (telegram.enabled=false)");
        }
        if (settings.sendOn() == TelegramSettings.SendOn.NEVER) {
            return new Outcome(false, "Telegram report skipped (telegram.sendOn=never)");
        }
        if (settings.sendOn() == TelegramSettings.SendOn.FAILURE && !summary.hasProblems()) {
            return new Outcome(false, "Telegram report skipped (telegram.sendOn=failure and the run had no problems)");
        }
        List<String> problems = settings.problems();
        if (!problems.isEmpty()) {
            throw new TelegramException("Telegram report not sent: " + String.join("; ", problems));
        }

        RunSummary withLink = withCiLink(summary, env);
        TelegramAttachments attachments = TelegramAttachments.collect(settings, attachSince);
        String text = TelegramMessageBuilder.message(withLink, settings.maxFailuresListed(), attachments.notes());

        if (settings.dryRun()) {
            return writePreview(settings, text, attachments);
        }

        TelegramSender sender = new TelegramSender(settings);
        sender.sendMessage(text);
        List<String> failedUploads = new ArrayList<>();
        int uploaded = 0;
        for (TelegramAttachments.Item item : attachments.items()) {
            try {
                sender.sendDocument(item.file(), item.name());
                uploaded++;
            } catch (TelegramException e) {
                failedUploads.add(item.name() + " (" + e.getMessage() + ")");
            }
        }
        String result = "Telegram report sent (" + withLink.status() + ", " + uploaded + " file(s) uploaded)";
        if (!failedUploads.isEmpty()) {
            result += "; NOT uploaded: " + String.join("; ", failedUploads);
        }
        return new Outcome(true, result);
    }

    private static RunSummary withCiLink(RunSummary s, Map<String, String> env) {
        String link = s.ciLink() == null || s.ciLink().isBlank() ? CiLinks.fromEnv(env) : s.ciLink();
        return new RunSummary(s.site(), s.browser(), s.suite(), s.total(), s.passed(), s.failed(), s.skipped(),
            s.durationMs(), s.startedAt(), s.failures(), link, s.license());
    }

    /** Writes what would have been sent to {@code telegram.dryRun.dir}; nothing leaves the machine. */
    private static Outcome writePreview(TelegramSettings settings, String text, TelegramAttachments attachments)
        throws TelegramException {
        StringBuilder preview = new StringBuilder(text).append("\n\n--- files that would be uploaded ---\n");
        if (attachments.items().isEmpty()) {
            preview.append("(none)\n");
        }
        for (TelegramAttachments.Item item : attachments.items()) {
            preview.append(item.name()).append("  <-  ").append(item.file()).append('\n');
        }
        try {
            Files.createDirectories(settings.dryRunDir());
            Path file = settings.dryRunDir().resolve("telegram-" + STAMP.format(Instant.now()) + ".txt");
            Files.writeString(file, preview.toString(), StandardCharsets.UTF_8);
            return new Outcome(true, "Telegram dry run: wrote " + file + " (nothing was sent)");
        } catch (IOException e) {
            throw new TelegramException("Telegram dry run could not write to " + settings.dryRunDir() + ": "
                + e.getMessage());
        }
    }
}
