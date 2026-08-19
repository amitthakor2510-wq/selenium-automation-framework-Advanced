package com.automation.core.keyword;

/**
 * Every action the KeywordEngine knows how to execute. A keyword-driven
 * test script (Excel/CSV/JSON/YAML, read via DataProvider) is just a list
 * of rows naming one of these per step — no Java per test case required.
 *
 * Includes both UI keywords (CLICK, TYPE, SELECT ...) and a keyboard-specific
 * one (PRESS_KEY) for keyboard-driven / accessibility-style checks that a
 * screen-and-mouse click alone doesn't cover — e.g. verifying a form can be
 * completed and submitted using only Tab/Enter/Space/Arrow keys.
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
    // Waits for document.readyState == 'complete' (a real page-load signal,
    // not a fixed sleep). testData = optional override timeout in seconds,
    // defaults to config key pageLoad.timeout (falls back to timeout.long).
    // Never fails the test on its own — logs a warning and continues if the
    // page still hasn't settled after the wait, since a slow/SPA page is a
    // reason to proceed carefully, not a reason to abort the whole test.
    // Use after NAVIGATE and after any CLICK that opens a dynamically
    // rendered modal/sub-module (e.g. IndiaAI's login popup) before
    // interacting with anything inside it.
    WAIT_FOR_PAGE_LOAD,
    // locator = captcha image, testData = ObjectRepository key of the input
    // field to type the answer into (see KeywordEngine.captchaInputField)
    SOLVE_TEXT_CAPTCHA,        // OCR-based text CAPTCHA — HARD FAILS if the image locator never resolves (deterministic mode, see docs/CAPTCHA_SOLVER.md Mode 2)
    SOLVE_MATH_CAPTCHA,        // Mathematical equation CAPTCHA
    SOLVE_CAPTCHA_WITH_AI,    // AI Vision fallback (GPT-4o/Claude) — currently falls back to OCR, see CaptchaSolver.solveWithAI
    // Same locator/testData convention as SOLVE_TEXT_CAPTCHA, but forgiving:
    // waits up to captcha.wait.seconds (default timeout.long) for the image
    // to appear, and if it never does — CAPTCHA didn't render this run, a
    // slow/flaky page, etc. — logs and moves on to the next step instead of
    // failing the test. Use this instead of SOLVE_TEXT_CAPTCHA whenever a
    // CAPTCHA is only sometimes present/slow to render (e.g. IndiaAI's login
    // sub-module) and the script shouldn't hard-fail because of that.
    SOLVE_TEXT_CAPTCHA_IF_PRESENT,
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
