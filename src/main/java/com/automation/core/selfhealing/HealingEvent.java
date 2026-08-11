package com.automation.core.selfhealing;

/**
 * One occurrence of a locator breaking and the engine successfully
 * re-finding the element by similarity. Collected in-memory during the run
 * and flushed to target/self-healing/healing-report.json at JVM shutdown
 * (see {@link SelfHealingReportWriter}) so a broken locator shows up as a
 * warning to fix, not just a passing test that got lucky.
 */
public class HealingEvent {

    public String elementKey;
    public String originalLocator;
    public String healedDescription;
    public double score;
    /** "dom" (attribute/text similarity alone) or "visual" (dom score fell short; a screenshot match tipped it over threshold). */
    public String matchMethod;
    public long timestamp;

    /** No-arg constructor required by Jackson. */
    public HealingEvent() {
    }

    public HealingEvent(String elementKey, String originalLocator, String healedDescription, double score) {
        this(elementKey, originalLocator, healedDescription, score, "dom");
    }

    public HealingEvent(String elementKey, String originalLocator, String healedDescription,
                        double score, String matchMethod) {
        this.elementKey = elementKey;
        this.originalLocator = originalLocator;
        this.healedDescription = healedDescription;
        this.score = score;
        this.matchMethod = matchMethod;
        this.timestamp = System.currentTimeMillis();
    }
}
