package com.automation.core.utils;

import java.util.regex.Pattern;

/**
 * Keeps secrets typed into a form out of reports and logs.
 *
 * <p>Allure's {@code @Step("Type \"{text}\" ...")} interpolation and the
 * KeywordEngine's per-step log line both print whatever text was typed, so a
 * password entered through {@code HumanActions.type()} or a keyword-CSV
 * {@code TYPE} row used to land verbatim in Allure step names, the console
 * log, and the "Step failed: ..." exception message shown in every report.
 *
 * <p>The decision is made from the <b>name</b> of the target field (its
 * locator or its object-repository key), because that is all the framework
 * knows at the call site. It is a heuristic on purpose — a field called
 * {@code pw} slips through — so {@code HumanActions.typeSecret()} exists for
 * callers who want masking regardless of what the locator looks like.
 */
public final class SensitiveData {

    /** What a masked value is replaced with in logs and reports. */
    public static final String MASK = "********";

    // password / passwd / passcode / pwd / secret / token / otp / cvv.
    // Deliberately NOT a bare "pass" or "pin": "passenger", "passport",
    // "shipping" and "spinner" are ordinary field/class names.
    private static final Pattern SENSITIVE_NAME = Pattern.compile(
        "(?i)(passw(or)?d|passcode|pwd|secret|token|api[-_ ]?key|cvv|\\botp\\b)");

    private SensitiveData() {
    }

    /** True when a locator string / object-repository key names a secret-bearing field. */
    public static boolean isSensitiveName(String fieldName) {
        return fieldName != null && SENSITIVE_NAME.matcher(fieldName).find();
    }

    /** {@code value} unchanged, or {@link #MASK} if {@code fieldName} looks sensitive. */
    public static String maskIfSensitive(String fieldName, String value) {
        return isSensitiveName(fieldName) ? MASK : value;
    }
}
