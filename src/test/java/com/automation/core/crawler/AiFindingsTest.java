package com.automation.core.crawler;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pure unit tests for {@link AiFindings#askForJson} — the shared "ask the model,
 * get JSON or null" helper used by all four AI reviewers. No AI server needed:
 * the model call is a lambda.
 */
class AiFindingsTest {

    private static final Logger LOG = LoggerFactory.getLogger(AiFindingsTest.class);

    @Test
    void parsesJsonObjectSurroundedByProse() {
        JsonNode json = AiFindings.askForJson(
            () -> "Sure! Here is the review: {\"findings\":[{\"issue\":\"missing alt text\"}]} Hope that helps.",
            LOG, "[Test]", "Review call", "https://example.test/page");

        assertNotNull(json);
        assertTrue(json.has("findings"));
        assertEquals(1, json.get("findings").size());
        assertEquals("missing alt text", json.get("findings").get(0).get("issue").asText());
    }

    @Test
    void returnsNullWhenTheCallThrowsAnException() {
        JsonNode json = AiFindings.askForJson(
            () -> {
                throw new IOException("connection refused");
            },
            LOG, "[Test]", "Review call", "page-1");

        assertNull(json);
    }

    @Test
    void returnsNullWhenTheCallThrowsARuntimeException() {
        JsonNode json = AiFindings.askForJson(
            () -> {
                throw new IllegalStateException("ai.model is not configured");
            },
            LOG, "[Test]", "Review call", "page-2");

        assertNull(json);
    }

    @Test
    void returnsNullWhenTheReplyContainsNoJson() {
        assertNull(AiFindings.askForJson(
            () -> "I could not find any issues on this page.",
            LOG, "[Test]", "Review call", "page-3"));
    }

    @Test
    void returnsNullWhenTheJsonIsMalformed() {
        assertNull(AiFindings.askForJson(
            () -> "{this is not valid json}",
            LOG, "[Test]", "Review call", "page-4"));
    }

    @Test
    void returnsNullWhenTheReplyIsNull() {
        assertNull(AiFindings.askForJson(
            () -> null,
            LOG, "[Test]", "Review call", "page-5"));
    }

    @Test
    void callsTheSupplierExactlyOnce() {
        int[] calls = {0};
        AiFindings.askForJson(() -> {
            calls[0]++;
            return "{}";
        }, LOG, "[Test]", "Review call", "page-6");

        assertEquals(1, calls[0]);
    }
}
