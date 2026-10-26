package com.automation.core.report;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Guards src/main/resources/categories.json — the rules behind Allure's Categories tab.
 * A typo in a regex or a broken JSON file would silently turn the tab empty ("Can't find
 * information about categories") or mis-sort failures, and nothing else would notice.
 *
 * Matching below mirrors Allure 2: a rule applies when the result's status is listed, and each
 * given regex matches the WHOLE message/trace (dot also matches newlines).
 */
class AllureCategoriesTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static JsonNode load() throws Exception {
        try (InputStream in = AllureEnvironmentWriter.class.getResourceAsStream(
                AllureEnvironmentWriter.CATEGORIES_RESOURCE)) {
            assertNotNull(in, "categories.json must be on the classpath at "
                    + AllureEnvironmentWriter.CATEGORIES_RESOURCE);
            return MAPPER.readTree(in);
        }
    }

    private static boolean regexMatches(JsonNode rule, String field, String value) {
        if (!rule.has(field)) {
            return true;
        }
        return Pattern.compile(rule.get(field).asText(), Pattern.DOTALL)
                .matcher(value == null ? "" : value).matches();
    }

    private static List<String> categoriesFor(String status, String message, String trace) throws Exception {
        List<String> hits = new ArrayList<>();
        for (JsonNode rule : load()) {
            boolean statusOk = false;
            for (JsonNode s : rule.get("matchedStatuses")) {
                statusOk |= s.asText().equals(status);
            }
            if (statusOk && regexMatches(rule, "messageRegex", message)
                    && regexMatches(rule, "traceRegex", trace)) {
                hits.add(rule.get("name").asText());
            }
        }
        return hits;
    }

    @Test
    void fileIsAValidNonEmptyArrayOfUniquelyNamedRules() throws Exception {
        JsonNode root = load();
        assertTrue(root.isArray() && root.size() > 0);
        Set<String> names = new HashSet<>();
        for (JsonNode rule : root) {
            assertTrue(rule.hasNonNull("name") && !rule.get("name").asText().isBlank(), "rule needs a name");
            assertTrue(names.add(rule.get("name").asText()), "duplicate category: " + rule.get("name"));
            assertTrue(rule.get("matchedStatuses").isArray() && rule.get("matchedStatuses").size() > 0,
                    "rule needs matchedStatuses: " + rule.get("name"));
        }
    }

    @Test
    void everyRegexCompiles() throws Exception {
        for (JsonNode rule : load()) {
            for (String field : new String[] {"messageRegex", "traceRegex"}) {
                if (rule.has(field)) {
                    Pattern.compile(rule.get(field).asText(), Pattern.DOTALL);
                }
            }
        }
    }

    @Test
    void typicalFailuresLandInExactlyTheRightCategory() throws Exception {
        assertEquals(List.of("Product defects (assertion failures)"),
                categoriesFor("failed", "java.lang.AssertionError: expected [true] but found [false]",
                        "java.lang.AssertionError: expected [true]\n\tat A.b(A.java:1)"));
        assertEquals(List.of("Element not found / stale"),
                categoriesFor("broken", "no such element",
                        "org.openqa.selenium.NoSuchElementException: no such element\n\tat A.b(A.java:1)"));
        assertEquals(List.of("Timeouts"),
                categoriesFor("broken", "Expected condition failed",
                        "org.openqa.selenium.TimeoutException: Expected condition failed\n\tat A.b(A.java:1)"));
        assertEquals(List.of("Driver / infrastructure issues"),
                categoriesFor("broken", "session not created",
                        "org.openqa.selenium.SessionNotCreatedException: x\n\tat A.b(A.java:1)"));
        assertEquals(List.of("Slow response (performance budget exceeded)"),
                categoriesFor("failed", "Performance budget exceeded: 5000ms",
                        "java.lang.AssertionError: Performance budget exceeded: 5000ms\n\tat P.q(P.java:3)"));
        assertEquals(List.of("Quarantined (known flaky - build not affected)"),
                categoriesFor("skipped", "Quarantined - known flaky", "org.testng.SkipException"));
        assertEquals(List.of("Ignored / skipped"),
                categoriesFor("skipped", "Skipped because a dependency failed", "org.testng.SkipException"));
    }

    @Test
    void passedTestsAreNeverCategorised() throws Exception {
        assertTrue(categoriesFor("passed", null, null).isEmpty());
    }

    @Test
    void writerCopiesTheCanonicalFileIntoTheResultsDirectory(@TempDir Path dir) throws Exception {
        AllureEnvironmentWriter.writeCategories(dir);
        Path written = dir.resolve("categories.json");
        assertTrue(Files.isRegularFile(written));
        assertEquals(load(), MAPPER.readTree(written.toFile()));
        assertFalse(Files.readString(written).contains("\"Failures\""), "must not be the minimal fallback");
    }
}
