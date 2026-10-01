package com.automation.core.ai;

import com.automation.core.config.ConfigReader;
import com.fasterxml.jackson.databind.JsonNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.PrintWriter;
import java.io.StringWriter;

/**
 * AI-assisted root-cause analysis for a failed/skipped test — report-only,
 * never edits source, never retries, never influences the test's actual
 * pass/fail status. Wired into TestListener.attachFailureDiagnostics(),
 * attaching the model's answer as one more Allure/ReportPortal text
 * attachment alongside the existing screenshot/page-source/console-log
 * evidence {@code FailureDiagnostics} already captures.
 *
 * Off by default ({@code ai.exceptionAnalysis.enabled=false}) — every
 * enabled failure/skip costs one network round-trip to the AI provider
 * configured for {@link OllamaClient}, and a fresh checkout with no AI
 * server reachable should not have its failure path slowed down (or
 * log-spammed with "AI unavailable" noise) by a feature it never opted
 * into.
 */
public final class AiExceptionAnalyzer {

    private static final Logger log = LoggerFactory.getLogger(AiExceptionAnalyzer.class);

    // Keep prompts small — local models have limited context, and this
    // runs synchronously on the failure path, so trimming matters more
    // here than the accuracy a much longer page source would buy.
    private static final int MAX_PAGE_SOURCE_CHARS = 4000;
    private static final int MAX_CONSOLE_LOG_CHARS = 1500;
    private static final int MAX_STACK_TRACE_CHARS = 2000;

    private static final String SYSTEM_PROMPT =
        "You are a test-automation triage assistant reviewing a single failed Selenium/TestNG test. "
            + "You are given the exception, its stack trace, the page URL, a trimmed page source, and browser "
            + "console logs at the moment of failure. Reply with ONLY a single JSON object, no prose outside it, "
            + "no markdown fence, shaped exactly as: "
            + "{\"rootCause\": \"one short phrase classifying the failure "
            + "(e.g. locator-drift, timing/flakiness, application-bug, network, data-mismatch, unknown)\", "
            + "\"explanation\": \"2-4 sentences on what actually went wrong and why, grounded in the evidence given\", "
            + "\"suggestedFix\": \"a concrete next step or code-level fix - e.g. an updated locator, a wait "
            + "condition to add, or what to check in the application\"}. "
            + "If the evidence doesn't support a confident answer, say so honestly in explanation rather than "
            + "guessing - do not invent selectors, URLs, or facts not present in what you were given.";

    private AiExceptionAnalyzer() {
    }

    public static boolean isEnabled() {
        return ConfigReader.getBoolean("ai.exceptionAnalysis.enabled", false) && OllamaClient.isConfigured();
    }

    /**
     * @return the analysis, or null if AI analysis is disabled/unconfigured
     *         or the call itself fails for any reason — callers should
     *         treat null exactly like "no AI attachment this time", never
     *         as a reason to fail the test further.
     */
    public static AiFailureAnalysis analyze(String testName, Throwable throwable, String pageUrl,
                                            String pageSource, String consoleLogs) {
        if (!isEnabled()) {
            return null;
        }
        try {
            String prompt = buildPrompt(testName, throwable, pageUrl, pageSource, consoleLogs);
            String reply = OllamaClient.chat(SYSTEM_PROMPT, prompt);
            JsonNode json = OllamaClient.extractJsonObject(reply);
            if (json == null) {
                log.warn("[AI] Exception analysis reply had no parseable JSON — skipping attachment. Raw reply "
                    + "(first 200 chars): {}", truncate(reply, 200));
                return null;
            }
            return new AiFailureAnalysis(
                textOrNull(json, "rootCause"),
                textOrNull(json, "explanation"),
                textOrNull(json, "suggestedFix"));
        } catch (Exception e) {
            log.warn("[AI] Exception analysis call failed ({}) — continuing without it.", e.getMessage());
            return null;
        }
    }

    private static String buildPrompt(String testName, Throwable throwable, String pageUrl,
                                      String pageSource, String consoleLogs) {
        StringBuilder sb = new StringBuilder();
        sb.append("Test: ").append(testName == null ? "(unknown)" : testName).append('\n');
        sb.append("Page URL: ").append(pageUrl == null ? "(unknown)" : pageUrl).append('\n');
        sb.append("Exception: ").append(throwable == null ? "(none)" : throwable.toString()).append('\n');
        sb.append("Stack trace (trimmed):\n").append(truncate(stackTraceOf(throwable), MAX_STACK_TRACE_CHARS))
            .append('\n');
        sb.append("Page source (trimmed):\n").append(truncate(pageSource, MAX_PAGE_SOURCE_CHARS)).append('\n');
        sb.append("Browser console logs (trimmed):\n").append(truncate(consoleLogs, MAX_CONSOLE_LOG_CHARS));
        return sb.toString();
    }

    private static String stackTraceOf(Throwable t) {
        if (t == null) {
            return "";
        }
        StringWriter sw = new StringWriter();
        t.printStackTrace(new PrintWriter(sw));
        return sw.toString();
    }

    private static String truncate(String s, int max) {
        if (s == null) {
            return "";
        }
        return s.length() <= max ? s : s.substring(0, max) + "... [truncated]";
    }

    private static String textOrNull(JsonNode node, String field) {
        JsonNode value = node.get(field);
        return value == null || value.isNull() ? null : value.asText();
    }
}
