package com.automation.core.keyword;

import com.automation.core.config.ConfigReader;
import com.automation.core.exceptions.KeywordExecutionException;
import com.automation.core.utils.LanguageUtils;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Expands {@code ${source:NAME}} placeholders in keyword-script cells
 * ({@code testData} / {@code expected}) so credentials and other
 * environment-specific values never have to be committed inside a CSV /
 * Excel / JSON / YAML script.
 *
 * <p>Supported forms:
 * <ul>
 *   <li>{@code ${env:NAME}} - OS environment variable {@code NAME}, falling back to the JVM
 *       system property of the same name ({@code mvn test -DNAME=value}).</li>
 *   <li>{@code ${sys:NAME}} - JVM system property only.</li>
 *   <li>{@code ${config:key}} - a key from the layered ConfigReader (system property, then
 *       site.properties, then global.properties).</li>
 *   <li>{@code ${i18n:key}} - translated text for {@code key} in the active language (see
 *       {@link LanguageUtils}), so one keyword script can run against the English, Hindi,
 *       Gujarati ... build of an app.</li>
 *   <li>Any form accepts an inline default: {@code ${env:NAME:-fallback}} (the default may be
 *       empty: {@code ${env:NAME:-}}).</li>
 * </ul>
 *
 * <p>Only the four prefixes above are recognised, so ordinary test data that happens to contain
 * {@code ${...}} (e.g. a template-injection payload such as {@code ${jndi:ldap://x}}) is passed
 * through untouched.
 *
 * <p>A placeholder with no value and no default fails the step with a message naming the missing
 * variable - it is never silently replaced by an empty string, which would turn a missing secret
 * into a confusing "wrong password" application failure.
 */
public final class PlaceholderResolver {

    private static final Pattern PLACEHOLDER =
        Pattern.compile("\\$\\{(env|sys|config|i18n):([^}:]+?)(?::-([^}]*))?}");

    private PlaceholderResolver() {
    }

    /** True if {@code raw} contains at least one recognised placeholder. */
    public static boolean containsPlaceholder(String raw) {
        return raw != null && PLACEHOLDER.matcher(raw).find();
    }

    /** Returns {@code raw} with every recognised placeholder expanded; {@code null} becomes "". */
    public static String resolve(String raw) {
        if (raw == null) {
            return "";
        }
        Matcher m = PLACEHOLDER.matcher(raw);
        if (!m.find()) {
            return raw;
        }
        StringBuilder out = new StringBuilder();
        int last = 0;
        do {
            out.append(raw, last, m.start());
            out.append(lookup(m.group(1), m.group(2).trim(), m.group(3), m.group(0)));
            last = m.end();
        } while (m.find());
        out.append(raw, last, raw.length());
        return out.toString();
    }

    private static String lookup(String source, String name, String inlineDefault, String original) {
        String value = switch (source) {
            case "env" -> firstNonBlank(System.getenv(name), System.getProperty(name));
            case "sys" -> firstNonBlank(System.getProperty(name), null);
            case "i18n" -> firstNonBlank(LanguageUtils.getOrDefault(name, null), null);
            default -> firstNonBlank(ConfigReader.get(name, null), null);
        };
        if (value != null) {
            return value;
        }
        if (inlineDefault != null) {
            return inlineDefault;
        }
        throw new KeywordExecutionException("[PlaceholderResolver] No value for " + original
            + " - set the " + describe(source, name) + ", or give the placeholder a default like "
            + "${" + source + ":" + name + ":-value}.");
    }

    private static String describe(String source, String name) {
        return switch (source) {
            case "env" -> "environment variable (or -D system property) '" + name + "'";
            case "sys" -> "JVM system property '" + name + "' (-D" + name + "=...)";
            case "i18n" -> "i18n key '" + name + "' in the active language's bundle (or the base messages file)";
            default -> "config key '" + name + "'";
        };
    }

    private static String firstNonBlank(String a, String b) {
        if (a != null && !a.isBlank()) {
            return a;
        }
        return b != null && !b.isBlank() ? b : null;
    }
}
