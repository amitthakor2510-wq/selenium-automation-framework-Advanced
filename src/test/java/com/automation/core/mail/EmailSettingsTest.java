package com.automation.core.mail;

import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EmailSettingsTest {

    private static EmailSettings settings(Map<String, String> props, Map<String, String> env) {
        return EmailSettings.of(props::get, env);
    }

    private static Map<String, String> valid() {
        Map<String, String> m = new HashMap<>();
        m.put("email.enabled", "true");
        m.put("email.smtp.host", "smtp.example.com");
        m.put("email.from", "qa@example.com");
        m.put("email.to", "team@example.com");
        return m;
    }

    @Test
    void defaultsAreSafe() {
        EmailSettings s = settings(Map.of(), Map.of());
        assertFalse(s.enabled(), "e-mail is off unless switched on");
        assertEquals(587, s.port());
        assertEquals(EmailSettings.Security.STARTTLS, s.security());
        assertEquals(EmailSettings.SendOn.ALWAYS, s.sendOn());
        assertFalse(s.failOnError());
        assertEquals(10L * 1024 * 1024, s.maxAttachmentBytes());
    }

    @Test
    void environmentVariableFillsAGapButPropertyWins() {
        Map<String, String> props = valid();
        props.put("email.smtp.host", "from-property");
        Map<String, String> env = Map.of("EMAIL_SMTP_HOST", "from-env", "EMAIL_SMTP_PASSWORD", "s3cret",
            "EMAIL_SMTP_USERNAME", "robot");
        EmailSettings s = settings(props, env);
        assertEquals("from-property", s.host());
        assertEquals("s3cret", s.password());
        assertEquals("robot", s.username());
    }

    @Test
    void blankPropertyFallsThroughToEnvironment() {
        Map<String, String> props = new HashMap<>();
        props.put("email.to", "   ");
        EmailSettings s = settings(props, Map.of("EMAIL_TO", "ci@example.com"));
        assertEquals("ci@example.com", s.to());
    }

    @Test
    void validConfigurationHasNoProblems() {
        assertEquals(List.of(), settings(valid(), Map.of()).problems());
    }

    @Test
    void reportsEveryMissingPiece() {
        List<String> problems = settings(Map.of("email.enabled", "true"), Map.of()).problems();
        assertTrue(problems.stream().anyMatch(p -> p.contains("email.from")), problems.toString());
        assertTrue(problems.stream().anyMatch(p -> p.contains("email.to")), problems.toString());
        assertTrue(problems.stream().anyMatch(p -> p.contains("email.smtp.host")), problems.toString());
    }

    @Test
    void dryRunNeedsNoServer() {
        Map<String, String> m = valid();
        m.remove("email.smtp.host");
        m.put("email.dryRun", "true");
        assertEquals(List.of(), settings(m, Map.of()).problems());
    }

    @Test
    void usernameWithoutPasswordIsRejected() {
        Map<String, String> m = valid();
        m.put("email.smtp.username", "robot");
        List<String> problems = settings(m, Map.of()).problems();
        assertTrue(problems.stream().anyMatch(p -> p.contains("EMAIL_SMTP_PASSWORD")), problems.toString());
    }

    @Test
    void credentialsOverPlainTextAreRefusedExceptOnLoopback() {
        Map<String, String> m = valid();
        m.put("email.smtp.username", "robot");
        m.put("email.smtp.password", "pw");
        m.put("email.smtp.security", "none");
        assertTrue(settings(m, Map.of()).problems().stream().anyMatch(p -> p.contains("unencrypted")));
        m.put("email.smtp.host", "localhost");
        assertEquals(List.of(), settings(m, Map.of()).problems());
    }

    @Test
    void invalidAndInjectedAddressesAreRejected() {
        Map<String, String> m = valid();
        m.put("email.to", "ok@example.com, not-an-address");
        assertTrue(settings(m, Map.of()).problems().stream().anyMatch(p -> p.contains("email.to")));

        m.put("email.to", "ok@example.com\r\nBcc: attacker@evil.test");
        assertTrue(settings(m, Map.of()).problems().stream().anyMatch(p -> p.contains("email.to")),
            "CR/LF in an address is a header-injection attempt");

        m = valid();
        m.put("email.from", "a@example.com, b@example.com");
        assertTrue(settings(m, Map.of()).problems().stream().anyMatch(p -> p.contains("email.from")));
    }

    @Test
    void semicolonSeparatedRecipientsAreAccepted() {
        Map<String, String> m = valid();
        m.put("email.to", "a@example.com; b@example.com");
        assertEquals(List.of(), settings(m, Map.of()).problems());
    }

    @Test
    void unknownEnumValuesFallBackSafely() {
        Map<String, String> m = valid();
        m.put("email.smtp.security", "bogus");
        m.put("email.sendOn", "bogus");
        m.put("email.smtp.port", "abc");
        EmailSettings s = settings(m, Map.of());
        assertEquals(EmailSettings.Security.STARTTLS, s.security(), "never silently downgrade to plain text");
        assertEquals(EmailSettings.SendOn.ALWAYS, s.sendOn());
        assertEquals(587, s.port());
    }

    @Test
    void toStringNeverShowsThePassword() {
        Map<String, String> m = valid();
        m.put("email.smtp.password", "hunter2-super-secret");
        String text = settings(m, Map.of()).toString();
        assertFalse(text.contains("hunter2"), text);
        assertTrue(text.contains("****"));
    }
}
