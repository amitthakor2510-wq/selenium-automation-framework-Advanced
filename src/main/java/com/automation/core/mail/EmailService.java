package com.automation.core.mail;

import com.automation.core.license.LicenseReportWriter;

import java.io.IOException;
import java.nio.file.Files;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;

/** Decides whether to send, gathers attachments and the license summary, and sends. */
public final class EmailService {

    /** What {@link #deliver} did. {@code sent} is also true for a dry run that wrote the .eml. */
    public record Outcome(boolean sent, String message) {
    }

    private EmailService() {
    }

    /**
     * @param attachSince only files modified at/after this instant are attached/summarised (null = no limit)
     * @throws EmailException when sending was attempted and failed, or the settings are unusable
     */
    public static Outcome deliver(EmailSettings settings, RunSummary summary, Instant attachSince,
                                  Map<String, String> env) throws EmailException {
        if (!settings.enabled()) {
            return new Outcome(false, "E-mail report disabled (email.enabled=false)");
        }
        if (settings.sendOn() == EmailSettings.SendOn.NEVER) {
            return new Outcome(false, "E-mail report skipped (email.sendOn=never)");
        }
        RunSummary withExtras = withExtras(settings, summary, attachSince, env);
        if (settings.sendOn() == EmailSettings.SendOn.FAILURE && !withExtras.hasProblems()) {
            return new Outcome(false, "E-mail report skipped (email.sendOn=failure and the run had no problems)");
        }
        EmailAttachments attachments = EmailAttachments.collect(settings, attachSince);
        String subject = EmailReportBuilder.subject(settings.subjectTemplate(), withExtras);
        String text = EmailReportBuilder.text(withExtras, attachments.notes());
        String html = EmailReportBuilder.html(withExtras, attachments.notes());
        String result = EmailSender.send(settings, subject, text, html, attachments.items());
        return new Outcome(true, result);
    }

    /** Adds the CI link and the (fresh) license summary when the caller did not supply them. */
    private static RunSummary withExtras(EmailSettings settings, RunSummary s, Instant since,
                                         Map<String, String> env) {
        String link = s.ciLink() == null || s.ciLink().isBlank() ? CiLinks.fromEnv(env) : s.ciLink();
        LicenseReportWriter.Summary license = s.license() != null ? s.license() : freshLicenseSummary(settings, since);
        return new RunSummary(s.site(), s.browser(), s.suite(), s.total(), s.passed(), s.failed(), s.skipped(),
            s.durationMs(), s.startedAt(), s.failures(), link, license);
    }

    private static LicenseReportWriter.Summary freshLicenseSummary(EmailSettings settings, Instant since) {
        Optional<LicenseReportWriter.Summary> summary = LicenseReportWriter.readSummary(settings.licenseDir());
        if (summary.isEmpty()) {
            return null;
        }
        if (since != null) {
            try {
                Instant written = Files.getLastModifiedTime(
                    settings.licenseDir().resolve(LicenseReportWriter.SUMMARY)).toInstant();
                if (written.isBefore(since.minusSeconds(2))) {
                    return null;
                }
            } catch (IOException e) {
                return null;
            }
        }
        return summary.get();
    }
}
