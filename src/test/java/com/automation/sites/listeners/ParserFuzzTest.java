package com.automation.sites.listeners;

import com.automation.core.budget.PerfBudget;
import com.automation.core.budget.PerfSamples.Kind;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertTrue;

/** The text formats people edit by hand (quarantine file, budget overrides): no input may crash the run. */
class ParserFuzzTest {

    private static String randomText(Random r, String alphabet, int maxLength) {
        StringBuilder text = new StringBuilder();
        for (int n = r.nextInt(maxLength); n > 0; n--) {
            text.append(alphabet.charAt(r.nextInt(alphabet.length())));
        }
        return text.toString();
    }

    @Test
    void anyQuarantineFileTextIsParsedWithoutThrowingAndLookupsStillWork() {
        Random r = new Random(48);
        String alphabet = "=|#!:@*./\\\n\r \tabcXYZ0123-_\uFEFF'\"scenariofeaturetag;";
        for (int i = 0; i < 3000; i++) {
            String text = randomText(r, alphabet, 120);
            try {
                QuarantineRegistry registry = QuarantineRegistry.parse(text);
                registry.find("com.x.SomeTest", "method");
                registry.findScenario(new QuarantineRegistry.Scenario(randomText(r, alphabet, 20), randomText(r, alphabet, 20),
                    List.of(randomText(r, alphabet, 8), "@quarantine_until_" + randomText(r, "0123456789-", 12))));
                registry.expiredOn(java.time.LocalDate.now());
            } catch (RuntimeException e) {
                throw new AssertionError("QuarantineRegistry threw " + e + " for: " + text.replace("\n", "\\n"), e);
            }
        }
    }

    @Test
    void aByteOrderMarkDoesNotHideTheFirstRule() {
        assertTrue(QuarantineRegistry.parse("\uFEFFLoginTest = 2026-11-15 | QA-1 | x").find("LoginTest", "m").isPresent());
    }

    @Test
    void anyBudgetOverrideTextIsParsedAndAlwaysGivesANonNegativeLimit() {
        Random r = new Random(49);
        String alphabet = "=;*/ GETPOST-0123456789abc{}\n.?%";
        for (int i = 0; i < 3000; i++) {
            String api = randomText(r, alphabet, 80);
            String page = randomText(r, alphabet, 80);
            String screen = randomText(r, alphabet, 80);
            try {
                PerfBudget budget = PerfBudget.of(r.nextInt(9000) - 100, r.nextInt(9000) - 100, r.nextInt(9000) - 100, api, page, screen);
                for (Kind kind : Kind.values()) {
                    long limit = budget.limitFor(kind, kind == Kind.API ? "GET /" + randomText(r, "abc/*0", 10) : "/" + randomText(r, "abc/*0", 10));
                    assertTrue(limit >= 0, "negative limit " + limit);
                }
            } catch (RuntimeException e) {
                throw new AssertionError("PerfBudget threw " + e + " for api=[" + api + "] page=[" + page + "] screen=[" + screen + "]", e);
            }
        }
    }

    @Test
    void scenarioInfoToleratesAnyParameterArray() {
        Object[][] odd = {null, {}, {null}, {"x", 1, null}, {new Object()}, {new int[] {1}}};
        for (Object[] parameters : odd) {
            ScenarioInfo.from(parameters); // must simply return empty, never throw
        }
    }
}
