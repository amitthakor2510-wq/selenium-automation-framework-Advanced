package com.automation.sites.listeners;

import com.automation.sites.listeners.QuarantineRegistry.Entry;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Pure-logic tests for QuarantineRegistry - no TestNG, no browser, no files. */
class QuarantineRegistryTest {

    private static final String LOGIN = "com.automation.sites.saucedemo.tests.LoginTest";
    private static final String BOOKS = "com.automation.sites.demoqa.tests.BookStoreApplicationTest";

    private static QuarantineRegistry of(String fileContent) {
        return QuarantineRegistry.parse(fileContent);
    }

    private static QuarantineRegistry.Scenario scenario(String name, String feature, String... tags) {
        return new QuarantineRegistry.Scenario(name, feature, List.of(tags));
    }

    @Test
    void emptyFileQuarantinesNothing() {
        QuarantineRegistry registry = of("# nothing here\n");
        assertTrue(registry.isEmpty());
        assertFalse(registry.find(LOGIN, "verifyLogin").isPresent());
    }

    @Test
    void methodRuleParsesDateTicketAndReason() {
        QuarantineRegistry registry = of("LoginTest#verifyLogin = 2026-11-15 | QA-412 | CAPTCHA misread");
        Entry entry = registry.find(LOGIN, "verifyLogin").orElseThrow();
        assertEquals(LocalDate.of(2026, 11, 15), entry.until());
        assertEquals("QA-412", entry.ticket());
        assertEquals("CAPTCHA misread", entry.reason());
        assertEquals("QA-412 (until 2026-11-15): CAPTCHA misread", entry.describe());
        assertFalse(registry.find(LOGIN, "otherMethod").isPresent());
    }

    @Test
    void classRuleCoversEveryMethodOfThatClassOnly() {
        QuarantineRegistry registry = of("BookStoreApplicationTest = 2026-11-01 | QA-1 | data reset");
        assertTrue(registry.find(BOOKS, "anything").isPresent());
        assertFalse(registry.find(LOGIN, "anything").isPresent());
    }

    @Test
    void packageWildcardCoversEveryClassBelowIt() {
        QuarantineRegistry registry = of("com.automation.sites.demoqa.tests.* = 2026-11-01 | | staging");
        assertTrue(registry.find(BOOKS, "m").isPresent());
        assertFalse(registry.find(LOGIN, "m").isPresent());
    }

    @Test
    void methodRuleBeatsClassRule() {
        QuarantineRegistry registry = of(
            "LoginTest = 2026-12-01 | CLASS | whole class\n"
                + "LoginTest#verifyLogin = 2026-11-01 | METHOD | one method");
        assertEquals("METHOD", registry.find(LOGIN, "verifyLogin").orElseThrow().ticket());
        assertEquals("CLASS", registry.find(LOGIN, "other").orElseThrow().ticket());
    }

    @Test
    void entryIsActiveThroughItsLastDayAndExpiredAfter() {
        Entry entry = of("LoginTest = 2026-11-15 | QA-1 | x").find(LOGIN, "m").orElseThrow();
        assertTrue(entry.activeOn(LocalDate.of(2026, 11, 14)));
        assertTrue(entry.activeOn(LocalDate.of(2026, 11, 15)), "until is inclusive");
        assertFalse(entry.activeOn(LocalDate.of(2026, 11, 16)));
    }

    @Test
    void expiredEntriesAreListed() {
        QuarantineRegistry registry = of("A = 2026-01-01 | | old\nB = 2099-01-01 | | future");
        assertEquals(1, registry.expiredOn(LocalDate.of(2026, 6, 1)).size());
        assertEquals("A", registry.expiredOn(LocalDate.of(2026, 6, 1)).get(0).target());
    }

    @Test
    void missingOrBadDateIsIgnoredSoTheTestFailsNormally() {
        QuarantineRegistry registry = of("A = QA-1 | no date\nB = 2026-13-45 | QA-2 | bad date\nC =");
        assertTrue(registry.isEmpty());
        assertFalse(registry.find(LOGIN, "m").isPresent());
    }

    @Test
    void reasonMayContainPipesAndTicketAndReasonAreOptional() {
        Entry entry = of("LoginTest = 2026-11-15 | QA-1 | a | b").find(LOGIN, "m").orElseThrow();
        assertEquals("a | b", entry.reason());
        Entry bare = of("LoginTest = 2026-11-15").find(LOGIN, "m").orElseThrow();
        assertEquals("(until 2026-11-15)", bare.describe());
    }

    @Test
    void annotationEntryNeedsAValidDate() {
        Optional<Entry> good = QuarantineRegistry.entryOf("T#m", "2026-11-15", "QA-9", "flaky");
        assertTrue(good.isPresent());
        assertEquals("@Quarantine", good.get().origin());
        assertFalse(QuarantineRegistry.entryOf("T#m", "", "QA-9", "flaky").isPresent());
        assertFalse(QuarantineRegistry.entryOf("T#m", "next week", "QA-9", "flaky").isPresent());
    }

    // ── Cucumber scenarios ───────────────────────────────────────────────────

    @Test
    void scenarioRuleMatchesNameWithSpacesAndWildcards() {
        QuarantineRegistry registry = of("scenario: Login with a locked-out user = 2026-11-15 | QA-1 | captcha\n"
            + "scenario: Checkout * = 2026-11-15 | QA-2 | all checkout");
        assertEquals("QA-1", registry.findScenario(scenario("Login with a locked-out user", "a.feature")).orElseThrow().ticket());
        assertEquals("QA-2", registry.findScenario(scenario("Checkout with coupon", "a.feature")).orElseThrow().ticket());
        assertFalse(registry.findScenario(scenario("Login with a valid user", "a.feature")).isPresent());
    }

    @Test
    void featureRuleMatchesFileNameOrPath() {
        QuarantineRegistry registry = of("feature: checkout.feature = 2026-11-15 | QA-3 | whole file");
        assertTrue(registry.findScenario(scenario("x", "classpath:features/checkout.feature")).isPresent());
        assertTrue(registry.findScenario(scenario("x", "file:///C:/proj/features/checkout.feature")).isPresent());
        assertFalse(registry.findScenario(scenario("x", "classpath:features/login.feature")).isPresent());
    }

    @Test
    void tagRuleMatchesWithOrWithoutTheAtSign() {
        QuarantineRegistry registry = of("tag: @flaky = 2026-11-15 | QA-4 | flaky tag");
        assertTrue(registry.findScenario(scenario("x", "a.feature", "@smoke", "@flaky")).isPresent());
        assertFalse(registry.findScenario(scenario("x", "a.feature", "@smoke")).isPresent());
        assertTrue(of("tag:flaky = 2026-11-15 | | t").findScenario(scenario("x", "a.feature", "@flaky")).isPresent());
    }

    @Test
    void scenarioCanQuarantineItselfWithTags() {
        Entry entry = of("").findScenario(scenario("x", "a.feature", "@smoke",
            "@quarantine_until_2026-11-15", "@ticket_QA-412")).orElseThrow();
        assertEquals(LocalDate.of(2026, 11, 15), entry.until());
        assertEquals("QA-412", entry.ticket());
        assertEquals("@tag", entry.origin());
        assertFalse(of("").findScenario(scenario("x", "a.feature", "@quarantine_until_next-week")).isPresent(),
            "a malformed date quarantines nothing");
        assertFalse(of("").findScenario(scenario("x", "a.feature", "@quarantine")).isPresent(),
            "no expiry date, no quarantine");
    }

    @Test
    void scenarioRulesTakePrecedenceOverTagsOverFeatureRules() {
        QuarantineRegistry registry = of("feature: a.feature = 2026-11-15 | FEATURE | f\n"
            + "tag: @flaky = 2026-11-15 | TAG | t\n"
            + "scenario: Exact name = 2026-11-15 | SCENARIO | s");
        assertEquals("SCENARIO", registry.findScenario(scenario("Exact name", "a.feature", "@flaky")).orElseThrow().ticket());
        assertEquals("TAG", registry.findScenario(scenario("Other", "a.feature", "@flaky")).orElseThrow().ticket());
        assertEquals("FEATURE", registry.findScenario(scenario("Other", "a.feature")).orElseThrow().ticket());
    }

    @Test
    void scenarioRulesDoNotLeakIntoClassLookups() {
        QuarantineRegistry registry = of("scenario: * = 2026-11-15 | QA-9 | everything\n"
            + "tag: @flaky = 2026-11-15 | QA-9 | x");
        assertFalse(registry.find(LOGIN, "verifyLogin").isPresent(), "scenario targets never match a Java class");
    }

    @Test
    void parserKeepsFileOrderAndWarnsAboutLinesItCannotUse() {
        QuarantineRegistry registry = of("# comment\n\nNotAnEntry\nA = 2026-11-15 | QA-1 | ok\nB = soon | QA-2 | bad date");
        assertTrue(registry.find("A", "m").isPresent());
        assertFalse(registry.find("B", "m").isPresent());
    }
}
