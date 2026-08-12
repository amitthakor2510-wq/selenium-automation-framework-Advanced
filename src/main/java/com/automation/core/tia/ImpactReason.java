package com.automation.core.tia;

/** Why a given test class ended up in the impacted set — surfaced in the report for auditability. */
public enum ImpactReason {
    /** The test class's own source file changed. */
    SELF_CHANGED,
    /** Brand-new, not-yet-committed test file (git diff can't see it; caught via untracked scan). */
    NEW_TEST_FILE,
    /**
     * Transitively depends (per the compiled-class dependency graph) on a changed class — either
     * a main-source class, or a shared test-source class such as a {@code BaseTest} superclass,
     * a {@code @DataProvider} holder, or any other test-side class other tests reference.
     */
    DEPENDS_ON_CHANGED_CLASS,
    /** A changed resource file (test data, keyword script) is literally referenced by this test's source. */
    RESOURCE_LITERAL_REFERENCE,
    /** A changed per-site resource (config/objectrepository) has no literal reference; every test for that site is included. */
    RESOURCE_SITE_FALLBACK
}
