package com.automation.core.keyword;

/**
 * Every action the KeywordEngine knows how to execute. A keyword-driven
 * test script (Excel/CSV/JSON/YAML, read via DataProvider) is just a list
 * of rows naming one of these per step — no Java per test case required.
 *
 * Includes both UI keywords (CLICK, TYPE, SELECT ...) and keyboard-specific
 * ones (PRESS_KEY, TAB_TO) for keyboard-driven / accessibility-style checks
 * that a screen-and-mouse click alone doesn't cover — e.g. verifying a form
 * can be completed and submitted using only Tab/Enter/Space/Arrow keys.
 */
public enum Keyword {
    NAVIGATE,             // testData = path relative to site base URL (or absolute http(s) URL)
    CLICK,                // locator required
    TYPE,                 // locator + testData required (human-paced typing)
    SET_TEXT,              // locator + testData required (instant — clear() + sendKeys(), no pacing)
    CLEAR,                 // locator required
    SELECT_BY_TEXT,        // locator + testData required (dropdown <select>)
    SELECT_BY_VALUE,        // locator + testData required
    HOVER,                 // locator required
    SCROLL_TO,              // locator required
    WAIT_SECONDS,           // testData = seconds
    PRESS_KEY,              // testData = key name (ENTER, TAB, ESCAPE, ARROW_DOWN, ...);
    // locator optional — sends to that element, else to the active element
    VERIFY_TEXT,            // locator + expected required
    VERIFY_DISPLAYED,       // locator required
    VERIFY_NOT_DISPLAYED,   // locator required
    VERIFY_URL_CONTAINS,    // expected required
    VERIFY_TITLE_CONTAINS,  // expected required
    SWITCH_TO_FRAME,        // locator required
    SWITCH_TO_DEFAULT_CONTENT,
    ACCEPT_ALERT,
    DISMISS_ALERT,
    SCREENSHOT;             // testData = label used in the saved filename (optional)

    public static Keyword from(String raw) {
        if (raw == null) {
            throw new IllegalArgumentException("[KeywordEngine] Keyword cell is empty");
        }
        try {
            return Keyword.valueOf(raw.trim().toUpperCase().replace(' ', '_'));
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException(
                "[KeywordEngine] Unknown keyword: '" + raw + "'. Supported: "
                    + java.util.Arrays.toString(Keyword.values()));
        }
    }
}
