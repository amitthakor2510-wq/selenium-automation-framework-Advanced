package com.automation.core.mail;

import jakarta.activation.DataHandler;
import jakarta.activation.FileDataSource;
import jakarta.mail.Message;
import jakarta.mail.MessagingException;
import jakarta.mail.Session;
import jakarta.mail.Transport;
import jakarta.mail.internet.InternetAddress;
import jakarta.mail.internet.MimeBodyPart;
import jakarta.mail.internet.MimeMessage;
import jakarta.mail.internet.MimeMultipart;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Date;
import java.util.List;
import java.util.Properties;

/** Builds the MIME message and either sends it over SMTP or (dry run) writes it as an {@code .eml} file. */
public final class EmailSender {

    private EmailSender() {
    }

    /** Builds a multipart/mixed message: an alternative (plain + HTML) body followed by the attachments. */
    static MimeMessage build(Session session, EmailSettings settings, String subject, String text, String html,
                             List<EmailAttachments.Item> attachments) throws EmailException {
        try {
            MimeMessage message = new MimeMessage(session);
            message.setFrom(new InternetAddress(settings.from(), false));
            message.setRecipients(Message.RecipientType.TO, EmailAddresses.parseList(settings.to()));
            if (!settings.cc().isBlank()) {
                message.setRecipients(Message.RecipientType.CC, EmailAddresses.parseList(settings.cc()));
            }
            if (!settings.bcc().isBlank()) {
                message.setRecipients(Message.RecipientType.BCC, EmailAddresses.parseList(settings.bcc()));
            }
            message.setSubject(subject, "UTF-8");
            message.setSentDate(new Date());

            MimeMultipart alternative = new MimeMultipart("alternative");
            MimeBodyPart plain = new MimeBodyPart();
            plain.setText(text, "UTF-8", "plain");
            MimeBodyPart rich = new MimeBodyPart();
            rich.setText(html, "UTF-8", "html");
            alternative.addBodyPart(plain);
            alternative.addBodyPart(rich);

            MimeMultipart mixed = new MimeMultipart("mixed");
            MimeBodyPart body = new MimeBodyPart();
            body.setContent(alternative);
            mixed.addBodyPart(body);
            for (EmailAttachments.Item item : attachments) {
                MimeBodyPart part = new MimeBodyPart();
                part.setDataHandler(new DataHandler(new FileDataSource(item.file().toFile())));
                // Strip any path/CR/LF from the visible name so it cannot break the Content-Disposition header.
                part.setFileName(item.name().replaceAll("[\\r\\n/\\\\]", "_"));
                mixed.addBodyPart(part);
            }
            message.setContent(mixed);
            message.saveChanges();
            return message;
        } catch (MessagingException e) {
            throw new EmailException("Could not build the e-mail: " + e.getMessage(), e);
        }
    }

    /**
     * Sends (or, in dry-run mode, saves) the report.
     *
     * @return a one-line description of what happened, for the log
     */
    public static String send(EmailSettings settings, String subject, String text, String html,
                              List<EmailAttachments.Item> attachments) throws EmailException {
        List<String> problems = settings.problems();
        if (!problems.isEmpty()) {
            throw new EmailException("E-mail settings are incomplete: " + String.join("; ", problems));
        }
        Session session = Session.getInstance(sessionProperties(settings));
        MimeMessage message = build(session, settings, subject, text, html, attachments);

        if (settings.dryRun()) {
            return saveEml(settings, message);
        }
        try {
            if (settings.username().isBlank()) {
                Transport.send(message);
            } else {
                Transport.send(message, settings.username(), settings.password());
            }
        } catch (MessagingException e) {
            // The exception text can echo the server's reply; it never contains our password.
            throw new EmailException("Sending the e-mail via " + settings.host() + ":" + settings.port()
                + " failed: " + e.getMessage(), e);
        }
        return "Report e-mail sent to " + settings.to() + " via " + settings.host() + ":" + settings.port();
    }

    static Properties sessionProperties(EmailSettings s) {
        Properties p = new Properties();
        p.put("mail.smtp.host", s.host());
        p.put("mail.smtp.port", String.valueOf(s.port()));
        p.put("mail.smtp.auth", String.valueOf(!s.username().isBlank()));
        String timeout = String.valueOf(s.timeoutMillis());
        p.put("mail.smtp.connectiontimeout", timeout);
        p.put("mail.smtp.timeout", timeout);
        p.put("mail.smtp.writetimeout", timeout);
        // Verify the server's certificate matches its host name on every encrypted connection.
        p.put("mail.smtp.ssl.checkserveridentity", "true");
        switch (s.security()) {
            case STARTTLS -> {
                p.put("mail.smtp.starttls.enable", "true");
                p.put("mail.smtp.starttls.required", "true");
            }
            case SSL -> p.put("mail.smtp.ssl.enable", "true");
            case NONE -> {
                // Plain SMTP: only reachable for loopback/unauthenticated relays (see EmailSettings.problems()).
            }
            default -> throw new IllegalStateException("Unhandled security mode " + s.security());
        }
        return p;
    }

    private static String saveEml(EmailSettings settings, MimeMessage message) throws EmailException {
        try {
            Files.createDirectories(settings.dryRunDir());
            Path file = settings.dryRunDir().resolve("report-" + System.currentTimeMillis() + ".eml");
            try (OutputStream out = Files.newOutputStream(file)) {
                // Like Transport.send, never write the Bcc header: it is an envelope-only recipient list.
                message.writeTo(out, new String[] {"Bcc"});
            }
            return "email.dryRun=true - report e-mail written to " + file + " (not sent)";
        } catch (IOException | MessagingException e) {
            throw new EmailException("Could not write the dry-run e-mail: " + e.getMessage(), e);
        }
    }
}
