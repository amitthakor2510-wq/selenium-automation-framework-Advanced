package com.automation.core.utils;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.URI;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Unit tests for {@link CredentialTransportGuard} (no network, no browser). */
class CredentialTransportGuardTest {

    private static void check(String url, boolean credential, boolean allowInsecure) throws IOException {
        CredentialTransportGuard.requireSecure(URI.create(url), credential, allowInsecure, "test");
    }

    @Test
    void httpsWithCredentialIsAllowed() {
        assertDoesNotThrow(() -> check("https://api.anthropic.com/v1/messages", true, false));
    }

    @Test
    void plainHttpToRemoteHostWithCredentialIsRefused() {
        IOException e = assertThrows(IOException.class, () -> check("http://llm.example.com/v1", true, false));
        assertTrue(e.getMessage().contains("refusing"));
    }

    @Test
    void plainHttpWithoutCredentialIsAllowed() {
        assertDoesNotThrow(() -> check("http://192.168.1.50:11434/api/chat", false, false));
    }

    @Test
    void loopbackHostsAreExempt() {
        assertDoesNotThrow(() -> check("http://localhost:11434/api/chat", true, false));
        assertDoesNotThrow(() -> check("http://127.0.0.1:11434/api/chat", true, false));
        assertDoesNotThrow(() -> check("http://[::1]:11434/api/chat", true, false));
    }

    @Test
    void lookalikeHostsAreNotTreatedAsLocal() {
        assertFalse(CredentialTransportGuard.isLocal("localhost.evil.com"));
        assertFalse(CredentialTransportGuard.isLocal("127.0.0.1.evil.com"));
        assertFalse(CredentialTransportGuard.isLocal(null));
    }

    @Test
    void explicitOptOutAllowsPlainHttp() {
        assertDoesNotThrow(() -> check("http://llm.example.com/v1", true, true));
    }

    @Test
    void abbreviateCapsLongBodies() {
        String body = "x".repeat(CredentialTransportGuard.MAX_ERROR_BODY_CHARS + 50);
        String out = CredentialTransportGuard.abbreviate(body);
        assertTrue(out.startsWith("x".repeat(CredentialTransportGuard.MAX_ERROR_BODY_CHARS)));
        assertTrue(out.contains("truncated 50 chars"));
        assertEquals("short", CredentialTransportGuard.abbreviate("short"));
        assertEquals("", CredentialTransportGuard.abbreviate(null));
    }
}
