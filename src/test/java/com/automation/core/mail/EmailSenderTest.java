package com.automation.core.mail;

import jakarta.mail.Address;
import jakarta.mail.BodyPart;
import jakarta.mail.Message;
import jakarta.mail.Session;
import jakarta.mail.internet.MimeMessage;
import jakarta.mail.internet.MimeMultipart;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EmailSenderTest {

    private static Map<String, String> base(Path dir) {
        Map<String, String> m = new HashMap<>();
        m.put("email.enabled", "true");
        m.put("email.from", "QA Bot <qa@example.com>");
        m.put("email.to", "team@example.com");
        m.put("email.cc", "lead@example.com");
        m.put("email.bcc", "audit@example.com");
        m.put("email.dryRun.dir", dir.resolve("eml").toString());
        return m;
    }

    private static EmailSettings settings(Map<String, String> m) {
        return EmailSettings.of(m::get, Map.of());
    }

    // ---- dry run -------------------------------------------------------------------------------

    @Test
    void dryRunWritesAParseableMessageWithAttachments(@TempDir Path dir) throws Exception {
        Map<String, String> m = base(dir);
        m.put("email.dryRun", "true");
        Path report = dir.resolve("index.html");
        Files.writeString(report, "<html>report</html>");

        String result = EmailSender.send(settings(m), "Hello subject", "plain body", "<b>html body</b>",
            List.of(new EmailAttachments.Item(report, "demoqa-chrome/../index.html")));
        assertTrue(result.contains("dryRun"), result);

        Path eml;
        try (Stream<Path> files = Files.list(dir.resolve("eml"))) {
            eml = files.findFirst().orElseThrow();
        }
        MimeMessage parsed;
        try (InputStream in = Files.newInputStream(eml)) {
            parsed = new MimeMessage(Session.getInstance(new Properties()), in);
        }
        assertEquals("Hello subject", parsed.getSubject());
        assertEquals("qa@example.com", ((jakarta.mail.internet.InternetAddress) parsed.getFrom()[0]).getAddress());
        assertEquals(1, parsed.getRecipients(Message.RecipientType.TO).length);
        assertEquals(1, parsed.getRecipients(Message.RecipientType.CC).length);
        assertEquals(null, parsed.getRecipients(Message.RecipientType.BCC), "Bcc must not appear in the headers");

        MimeMultipart mixed = (MimeMultipart) parsed.getContent();
        assertEquals(2, mixed.getCount(), "body + one attachment");
        MimeMultipart alternative = (MimeMultipart) mixed.getBodyPart(0).getContent();
        assertEquals(2, alternative.getCount());
        assertTrue(alternative.getBodyPart(0).isMimeType("text/plain"));
        assertTrue(alternative.getBodyPart(1).isMimeType("text/html"));
        assertEquals("<b>html body</b>", alternative.getBodyPart(1).getContent().toString());
        BodyPart attachment = mixed.getBodyPart(1);
        assertFalse(attachment.getFileName().contains("/"), "path separators are stripped from attachment names");
        assertEquals("<html>report</html>", new String(attachment.getInputStream().readAllBytes(),
            StandardCharsets.UTF_8));
    }

    @Test
    void headerInjectionInTheSubjectIsNeutralised(@TempDir Path dir) throws Exception {
        Map<String, String> m = base(dir);
        m.put("email.dryRun", "true");
        // The builder strips CR/LF; the sender must also be safe if a caller forgets.
        Session session = Session.getInstance(new Properties());
        MimeMessage msg = EmailSender.build(session, settings(m), "ok\r\nBcc: evil@evil.test", "t", "<p>h</p>",
            List.of());
        for (Address a : msg.getAllRecipients()) {
            assertFalse(a.toString().contains("evil"), a.toString());
        }
        java.io.ByteArrayOutputStream raw = new java.io.ByteArrayOutputStream();
        msg.writeTo(raw);
        String headers = raw.toString(StandardCharsets.ISO_8859_1).split("\r?\n\r?\n", 2)[0];
        assertFalse(headers.lines().anyMatch(l -> l.startsWith("Bcc: evil")), headers);
    }

    @Test
    void incompleteSettingsFailBeforeAnythingIsSent(@TempDir Path dir) {
        Map<String, String> m = new HashMap<>();
        m.put("email.enabled", "true");
        EmailException e = assertThrows(EmailException.class,
            () -> EmailSender.send(settings(m), "s", "t", "h", List.of()));
        assertTrue(e.getMessage().contains("email.smtp.host"), e.getMessage());
    }

    // ---- session properties --------------------------------------------------------------------

    @Test
    void starttlsIsRequiredNotJustOffered(@TempDir Path dir) {
        Map<String, String> m = base(dir);
        m.put("email.smtp.host", "smtp.example.com");
        Properties p = EmailSender.sessionProperties(settings(m));
        assertEquals("true", p.get("mail.smtp.starttls.enable"));
        assertEquals("true", p.get("mail.smtp.starttls.required"));
        assertEquals("true", p.get("mail.smtp.ssl.checkserveridentity"));
        assertEquals("false", p.get("mail.smtp.auth"));
        assertEquals("20000", p.get("mail.smtp.connectiontimeout"));
    }

    @Test
    void sslModeAndAuthFlag(@TempDir Path dir) {
        Map<String, String> m = base(dir);
        m.put("email.smtp.host", "smtp.example.com");
        m.put("email.smtp.security", "ssl");
        m.put("email.smtp.username", "robot");
        m.put("email.smtp.password", "pw");
        Properties p = EmailSender.sessionProperties(settings(m));
        assertEquals("true", p.get("mail.smtp.ssl.enable"));
        assertEquals(null, p.get("mail.smtp.starttls.enable"));
        assertEquals("true", p.get("mail.smtp.auth"));
    }

    // ---- real SMTP conversation against an in-process fake server -------------------------------

    /** Minimal SMTP server: accepts one message and records the envelope + data. */
    private static final class FakeSmtp implements AutoCloseable {
        final ServerSocket server;
        final List<String> recipients = new ArrayList<>();
        final StringBuilder data = new StringBuilder();
        final CompletableFuture<Void> done = new CompletableFuture<>();

        FakeSmtp() throws IOException {
            server = new ServerSocket(0, 1, InetAddress.getLoopbackAddress());
            Thread t = new Thread(this::serve, "fake-smtp");
            t.setDaemon(true);
            t.start();
        }

        int port() {
            return server.getLocalPort();
        }

        private void serve() {
            try (Socket s = server.accept();
                 BufferedReader in = new BufferedReader(new InputStreamReader(s.getInputStream(),
                     StandardCharsets.ISO_8859_1));
                 PrintWriter out = new PrintWriter(new OutputStreamWriter(s.getOutputStream(),
                     StandardCharsets.ISO_8859_1), true)) {
                out.print("220 fake ESMTP\r\n");
                out.flush();
                String line;
                while ((line = in.readLine()) != null) {
                    String up = line.toUpperCase(java.util.Locale.ROOT);
                    if (up.startsWith("EHLO") || up.startsWith("HELO")) {
                        out.print("250-fake\r\n250 AUTH PLAIN\r\n");
                        out.flush();
                    } else if (up.startsWith("MAIL FROM")) {
                        reply(out, "250 ok");
                    } else if (up.startsWith("RCPT TO")) {
                        recipients.add(line.substring(line.indexOf('<') + 1, line.indexOf('>')));
                        reply(out, "250 ok");
                    } else if (up.equals("DATA")) {
                        reply(out, "354 go");
                        while ((line = in.readLine()) != null && !line.equals(".")) {
                            data.append(line).append('\n');
                        }
                        reply(out, "250 queued");
                    } else if (up.equals("QUIT")) {
                        reply(out, "221 bye");
                        break;
                    } else if (up.startsWith("AUTH")) {
                        reply(out, "504 no auth here");
                    } else {
                        reply(out, "250 ok");
                    }
                }
                done.complete(null);
            } catch (IOException e) {
                done.completeExceptionally(e);
            }
        }

        private static void reply(PrintWriter out, String text) {
            out.print(text + "\r\n");
            out.flush();
        }

        @Override
        public void close() throws IOException {
            server.close();
        }
    }

    @Test
    void sendsOverRealSmtpToAllRecipients(@TempDir Path dir) throws Exception {
        try (FakeSmtp smtp = new FakeSmtp()) {
            Map<String, String> m = base(dir);
            m.put("email.smtp.host", "127.0.0.1");
            m.put("email.smtp.port", String.valueOf(smtp.port()));
            m.put("email.smtp.security", "none");
            Path report = dir.resolve("index.html");
            Files.writeString(report, "<html>r</html>");

            String result = EmailSender.send(settings(m), "Run PASSED", "plain", "<p>html</p>",
                List.of(new EmailAttachments.Item(report, "report.html")));
            smtp.done.get(10, TimeUnit.SECONDS);

            assertTrue(result.contains("sent"), result);
            assertEquals(List.of("team@example.com", "lead@example.com", "audit@example.com"), smtp.recipients,
                "To, Cc and Bcc all get an envelope recipient");
            String data = smtp.data.toString();
            assertTrue(data.contains("Subject: Run PASSED"), data);
            assertTrue(data.contains("filename=report.html"), data);
            assertFalse(data.contains("audit@example.com"), "Bcc address must not leak into the headers");
        }
    }

    @Test
    void failedSendIsAnEmailExceptionWithoutThePassword(@TempDir Path dir) throws Exception {
        try (FakeSmtp smtp = new FakeSmtp()) {
            Map<String, String> m = base(dir);
            m.put("email.smtp.host", "127.0.0.1");
            m.put("email.smtp.port", String.valueOf(smtp.port()));
            m.put("email.smtp.security", "none");
            m.put("email.smtp.username", "robot");
            m.put("email.smtp.password", "TOPSECRETPASSWORD");

            EmailException e = assertThrows(EmailException.class,
                () -> EmailSender.send(settings(m), "s", "t", "h", List.of()));
            assertFalse(e.getMessage().contains("TOPSECRETPASSWORD"), e.getMessage());
            assertTrue(e.getMessage().contains("127.0.0.1"), e.getMessage());
        }
    }

    @Test
    void unreachableServerFailsFastWithAClearMessage(@TempDir Path dir) throws Exception {
        int closedPort;
        try (ServerSocket probe = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
            closedPort = probe.getLocalPort();
        }
        Map<String, String> m = base(dir);
        m.put("email.smtp.host", "127.0.0.1");
        m.put("email.smtp.port", String.valueOf(closedPort));
        m.put("email.smtp.security", "none");
        m.put("email.timeoutMs", "3000");
        long start = System.nanoTime();
        EmailException e = assertThrows(EmailException.class,
            () -> EmailSender.send(settings(m), "s", "t", "h", List.of()));
        assertTrue(TimeUnit.NANOSECONDS.toSeconds(System.nanoTime() - start) < 10, "must not hang");
        assertTrue(e.getMessage().contains("failed"), e.getMessage());
    }
}
