package com.automation.core.telegram;

import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TelegramSettingsTest {

    @Test
    void defaultsAreOffAndSafe() {
        TelegramSettings s = TelegramSettings.of(k -> null, Map.of());
        assertFalse(s.enabled());
        assertFalse(s.dryRun());
        assertEquals(TelegramSettings.SendOn.ALWAYS, s.sendOn());
        assertEquals("https://api.telegram.org", s.apiBase());
        assertEquals(45L * 1024 * 1024, s.maxFileBytes());
    }

    @Test
    void tokenAndChatComeFromEnvironmentVariables() {
        Map<String, String> env = Map.of("TELEGRAM_BOT_TOKEN", "123456:ABC-def_ghi", "TELEGRAM_CHAT_ID", "-1001234567890",
            "TELEGRAM_THREAD_ID", "7");
        TelegramSettings s = TelegramSettings.of(k -> null, env);
        assertEquals("123456:ABC-def_ghi", s.token());
        assertEquals("-1001234567890", s.chatId());
        assertEquals("7", s.threadId());
        assertTrue(s.problems().isEmpty());
    }

    @Test
    void propertyWinsOverEnvironment() {
        Map<String, String> props = new HashMap<>();
        props.put("telegram.chat.id", "42");
        TelegramSettings s = TelegramSettings.of(props::get, Map.of("TELEGRAM_CHAT_ID", "99"));
        assertEquals("42", s.chatId());
    }

    @Test
    void missingTokenAndChatAreReported() {
        TelegramSettings s = TelegramSettings.of(k -> null, Map.of());
        assertEquals(2, s.problems().size());
    }

    @Test
    void dryRunNeedsNoCredentials() {
        Map<String, String> props = Map.of("telegram.dryRun", "true");
        assertTrue(TelegramSettings.of(props::get, Map.of()).problems().isEmpty());
    }

    @Test
    void malformedChatIdIsRejected() {
        Map<String, String> props = Map.of("telegram.chat.id", "not a chat", "telegram.bot.token", "1:a",
            "telegram.dryRun", "true");
        assertEquals(1, TelegramSettings.of(props::get, Map.of()).problems().size());
    }

    @Test
    void channelUsernameIsAccepted() {
        Map<String, String> props = Map.of("telegram.chat.id", "@my_channel", "telegram.bot.token", "1:a");
        assertTrue(TelegramSettings.of(props::get, Map.of()).problems().isEmpty());
    }

    @Test
    void plainHttpToARemoteHostIsRefusedBecauseTheTokenIsInTheUrl() {
        Map<String, String> props = Map.of("telegram.chat.id", "1", "telegram.bot.token", "1:a",
            "telegram.apiBase", "http://example.com");
        assertEquals(1, TelegramSettings.of(props::get, Map.of()).problems().size());
    }

    @Test
    void plainHttpToLoopbackIsAllowedForLocalTests() {
        Map<String, String> props = Map.of("telegram.chat.id", "1", "telegram.bot.token", "1:a",
            "telegram.apiBase", "http://127.0.0.1:8080/");
        TelegramSettings s = TelegramSettings.of(props::get, Map.of());
        assertTrue(s.problems().isEmpty());
        assertEquals("http://127.0.0.1:8080", s.apiBase());
    }

    @Test
    void toStringMasksTheToken() {
        Map<String, String> props = Map.of("telegram.bot.token", "123456:SECRET-VALUE");
        assertFalse(TelegramSettings.of(props::get, Map.of()).toString().contains("SECRET"));
    }
}
