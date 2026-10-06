package com.automation.core.utils;

import org.openqa.selenium.JavascriptExecutor;
import org.openqa.selenium.WebDriver;
import org.openqa.selenium.support.ui.WebDriverWait;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Read / write helper for the browser's Web Storage ({@code localStorage} and
 * {@code sessionStorage}) of the page the driver is currently on.
 *
 * <p>Typical uses: asserting an app stored an auth token or preference, seeding a feature flag or
 * "cookie banner dismissed" entry before a test, or resetting state between scenarios.
 *
 * <p>Every method comes in two forms - one for {@code localStorage} (e.g.
 * {@link #getItem(WebDriver, String)}) and one taking an explicit {@link Area} (e.g.
 * {@link #getItem(WebDriver, Area, String)}) for {@code sessionStorage}.
 *
 * <p>Notes:
 * <ul>
 *   <li>Storage is per-ORIGIN, so these calls act on whichever origin the driver is on right
 *       now; on {@code about:blank} the browser throws a security error (a
 *       {@code WebDriverException}) - navigate to the app first.</li>
 *   <li>Keys and values are passed to the script as arguments, never concatenated into it, so a
 *       quote or newline in a value cannot break or inject into the script.</li>
 *   <li>Values are masked in the log when the KEY looks sensitive (token, password, secret ...,
 *       see {@link SensitiveData}).</li>
 * </ul>
 */
public final class LocalStorageUtils {

    private static final Logger logger = LoggerFactory.getLogger(LocalStorageUtils.class);

    /** Which Web Storage area to act on. */
    public enum Area {
        LOCAL("localStorage"),
        SESSION("sessionStorage");

        private final String jsName;

        Area(String jsName) {
            this.jsName = jsName;
        }

        /** The {@code window.<name>} property this area maps to. */
        public String jsName() {
            return jsName;
        }
    }

    private LocalStorageUtils() {
    }

    // ── localStorage shortcuts ───────────────────────────────────────────────

    public static String getItem(WebDriver driver, String key) {
        return getItem(driver, Area.LOCAL, key);
    }

    public static void setItem(WebDriver driver, String key, String value) {
        setItem(driver, Area.LOCAL, key, value);
    }

    public static void removeItem(WebDriver driver, String key) {
        removeItem(driver, Area.LOCAL, key);
    }

    public static boolean containsKey(WebDriver driver, String key) {
        return containsKey(driver, Area.LOCAL, key);
    }

    public static Map<String, String> getAll(WebDriver driver) {
        return getAll(driver, Area.LOCAL);
    }

    public static List<String> keys(WebDriver driver) {
        return keys(driver, Area.LOCAL);
    }

    public static int size(WebDriver driver) {
        return size(driver, Area.LOCAL);
    }

    public static void clear(WebDriver driver) {
        clear(driver, Area.LOCAL);
    }

    public static String waitForItem(WebDriver driver, String key, Duration timeout) {
        return waitForItem(driver, Area.LOCAL, key, timeout);
    }

    /** Empties BOTH localStorage and sessionStorage (what a "fresh session" reset needs). */
    public static void clearAll(WebDriver driver) {
        clear(driver, Area.LOCAL);
        clear(driver, Area.SESSION);
    }

    // ── Area-aware operations ────────────────────────────────────────────────

    /** The stored value, or {@code null} when the key is absent. */
    public static String getItem(WebDriver driver, Area area, String key) {
        requireKey(key);
        Object value = run(driver, "return window." + area.jsName() + ".getItem(arguments[0]);", key);
        return value == null ? null : String.valueOf(value);
    }

    /** Stores {@code value} under {@code key}, replacing any existing entry. */
    public static void setItem(WebDriver driver, Area area, String key, String value) {
        requireKey(key);
        if (value == null) {
            // Web Storage would store the literal string "null" - never what a test means.
            throw new IllegalArgumentException("value must not be null for key '" + key
                + "' (use removeItem to delete an entry)");
        }
        run(driver, "window." + area.jsName() + ".setItem(arguments[0], arguments[1]);", key, value);
        logger.debug("[LocalStorageUtils] {}.setItem('{}') = {}", area.jsName(), key,
            SensitiveData.maskIfSensitive(key, value));
    }

    public static void removeItem(WebDriver driver, Area area, String key) {
        requireKey(key);
        run(driver, "window." + area.jsName() + ".removeItem(arguments[0]);", key);
        logger.debug("[LocalStorageUtils] {}.removeItem('{}')", area.jsName(), key);
    }

    public static boolean containsKey(WebDriver driver, Area area, String key) {
        return getItem(driver, area, key) != null;
    }

    /** Every entry as an insertion-ordered key/value map (empty when the storage is empty). */
    public static Map<String, String> getAll(WebDriver driver, Area area) {
        Object raw = run(driver, "var s = window." + area.jsName() + "; var o = {};"
            + " for (var i = 0; i < s.length; i++) { var k = s.key(i); o[k] = s.getItem(k); }"
            + " return o;");
        Map<String, String> result = new LinkedHashMap<>();
        if (raw instanceof Map<?, ?> map) {
            for (Map.Entry<?, ?> e : map.entrySet()) {
                result.put(String.valueOf(e.getKey()), e.getValue() == null ? null : String.valueOf(e.getValue()));
            }
        }
        return result;
    }

    public static List<String> keys(WebDriver driver, Area area) {
        Object raw = run(driver, "return Object.keys(window." + area.jsName() + ");");
        List<String> result = new ArrayList<>();
        if (raw instanceof List<?> list) {
            for (Object o : list) {
                result.add(String.valueOf(o));
            }
        }
        return result;
    }

    public static int size(WebDriver driver, Area area) {
        Object raw = run(driver, "return window." + area.jsName() + ".length;");
        return raw instanceof Number n ? n.intValue() : 0;
    }

    public static void clear(WebDriver driver, Area area) {
        run(driver, "window." + area.jsName() + ".clear();");
        logger.debug("[LocalStorageUtils] {}.clear()", area.jsName());
    }

    /**
     * Polls until {@code key} exists, then returns its value - for entries an SPA writes
     * asynchronously after login. Throws {@code TimeoutException} if it never appears.
     */
    public static String waitForItem(WebDriver driver, Area area, String key, Duration timeout) {
        requireKey(key);
        if (timeout == null) {
            throw new IllegalArgumentException("timeout must not be null");
        }
        return new WebDriverWait(driver, timeout)
            .withMessage(area.jsName() + " key '" + key + "' did not appear within " + timeout.toSeconds() + "s")
            .until(d -> getItem(d, area, key));
    }

    // ── Internals ────────────────────────────────────────────────────────────

    private static Object run(WebDriver driver, String script, Object... args) {
        if (driver == null) {
            throw new IllegalArgumentException("driver must not be null");
        }
        if (!(driver instanceof JavascriptExecutor js)) {
            throw new IllegalStateException(driver.getClass().getName()
                + " cannot execute JavaScript, so Web Storage is unavailable (native mobile context?)");
        }
        return js.executeScript(script, args);
    }

    private static void requireKey(String key) {
        if (key == null) {
            throw new IllegalArgumentException("storage key must not be null");
        }
    }
}
