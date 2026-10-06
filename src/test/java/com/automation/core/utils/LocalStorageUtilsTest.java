package com.automation.core.utils;

import com.automation.core.utils.LocalStorageUtils.Area;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.openqa.selenium.JavascriptExecutor;
import org.openqa.selenium.TimeoutException;
import org.openqa.selenium.WebDriver;

import java.lang.reflect.Proxy;
import java.time.Duration;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * WebDriver-free unit tests for {@link LocalStorageUtils}. A JDK dynamic proxy stands in for the
 * browser: it implements {@link WebDriver} + {@link JavascriptExecutor} and emulates Web Storage
 * by recognising the helper's scripts, so the tests check what the helper SENDS (script shape,
 * area, arguments) and how it interprets the replies - not a real browser's storage engine.
 */
class LocalStorageUtilsTest {

    private FakeBrowser browser;
    private WebDriver driver;

    @BeforeEach
    void setUp() {
        browser = new FakeBrowser();
        driver = browser.driver();
    }

    @Test
    void setThenGetRoundTrips() {
        LocalStorageUtils.setItem(driver, "theme", "dark");
        assertEquals("dark", LocalStorageUtils.getItem(driver, "theme"));
        assertTrue(LocalStorageUtils.containsKey(driver, "theme"));
    }

    @Test
    void missingKeyReturnsNullAndEmptyStringIsKept() {
        assertNull(LocalStorageUtils.getItem(driver, "absent"));
        assertFalse(LocalStorageUtils.containsKey(driver, "absent"));
        LocalStorageUtils.setItem(driver, "blank", "");
        assertEquals("", LocalStorageUtils.getItem(driver, "blank"));
        assertTrue(LocalStorageUtils.containsKey(driver, "blank"));
    }

    @Test
    void localAndSessionStorageAreSeparate() {
        LocalStorageUtils.setItem(driver, "k", "local-value");
        LocalStorageUtils.setItem(driver, Area.SESSION, "k", "session-value");
        assertEquals("local-value", LocalStorageUtils.getItem(driver, "k"));
        assertEquals("session-value", LocalStorageUtils.getItem(driver, Area.SESSION, "k"));
        LocalStorageUtils.clear(driver);
        assertNull(LocalStorageUtils.getItem(driver, "k"));
        assertEquals("session-value", LocalStorageUtils.getItem(driver, Area.SESSION, "k"));
    }

    @Test
    void keysSizeAndGetAllReflectTheStoredEntries() {
        assertEquals(0, LocalStorageUtils.size(driver));
        assertTrue(LocalStorageUtils.getAll(driver).isEmpty());
        LocalStorageUtils.setItem(driver, "a", "1");
        LocalStorageUtils.setItem(driver, "b", "2");
        assertEquals(2, LocalStorageUtils.size(driver));
        assertEquals(List.of("a", "b"), LocalStorageUtils.keys(driver));
        Map<String, String> all = LocalStorageUtils.getAll(driver);
        assertEquals("1", all.get("a"));
        assertEquals("2", all.get("b"));
    }

    @Test
    void removeItemDeletesOnlyThatKey() {
        LocalStorageUtils.setItem(driver, "a", "1");
        LocalStorageUtils.setItem(driver, "b", "2");
        LocalStorageUtils.removeItem(driver, "a");
        assertNull(LocalStorageUtils.getItem(driver, "a"));
        assertEquals("2", LocalStorageUtils.getItem(driver, "b"));
    }

    @Test
    void clearAllEmptiesBothAreas() {
        LocalStorageUtils.setItem(driver, "a", "1");
        LocalStorageUtils.setItem(driver, Area.SESSION, "b", "2");
        LocalStorageUtils.clearAll(driver);
        assertEquals(0, LocalStorageUtils.size(driver));
        assertEquals(0, LocalStorageUtils.size(driver, Area.SESSION));
    }

    @Test
    void valuesTravelAsScriptArgumentsNeverInsideTheScriptText() {
        String nasty = "x'); alert(1); //\n\"quoted\"";
        LocalStorageUtils.setItem(driver, "k'ey", nasty);
        assertEquals(nasty, LocalStorageUtils.getItem(driver, "k'ey"));
        for (String script : browser.scripts) {
            assertFalse(script.contains("alert"), "value leaked into script text: " + script);
            assertFalse(script.contains("k'ey"), "key leaked into script text: " + script);
        }
    }

    @Test
    void invalidArgumentsAreRejectedBeforeAnythingRuns() {
        assertThrows(IllegalArgumentException.class, () -> LocalStorageUtils.setItem(driver, "k", null));
        assertThrows(IllegalArgumentException.class, () -> LocalStorageUtils.setItem(driver, null, "v"));
        assertThrows(IllegalArgumentException.class, () -> LocalStorageUtils.getItem(driver, null));
        assertThrows(IllegalArgumentException.class, () -> LocalStorageUtils.getItem(null, "k"));
        assertTrue(browser.scripts.isEmpty(), "no script should have been sent: " + browser.scripts);
    }

    @Test
    void aDriverThatCannotRunJavaScriptIsReportedClearly() {
        WebDriver plain = (WebDriver) Proxy.newProxyInstance(getClass().getClassLoader(),
            new Class<?>[] {WebDriver.class}, (proxy, method, args) -> null);
        IllegalStateException e = assertThrows(IllegalStateException.class,
            () -> LocalStorageUtils.getItem(plain, "k"));
        assertTrue(e.getMessage().contains("JavaScript"), e.getMessage());
    }

    @Test
    void waitForItemReturnsTheValueOnceItExists() {
        LocalStorageUtils.setItem(driver, "token", "abc");
        assertEquals("abc", LocalStorageUtils.waitForItem(driver, "token", Duration.ofSeconds(2)));
    }

    @Test
    void waitForItemTimesOutWhenTheKeyNeverAppears() {
        assertThrows(TimeoutException.class,
            () -> LocalStorageUtils.waitForItem(driver, "never", Duration.ofMillis(300)));
    }

    // ── Fake browser ─────────────────────────────────────────────────────────

    /** Emulates window.localStorage / window.sessionStorage behind executeScript(). */
    private static final class FakeBrowser {
        final Map<Area, Map<String, String>> storage = new EnumMap<>(Area.class);
        final List<String> scripts = new ArrayList<>();

        FakeBrowser() {
            for (Area a : Area.values()) {
                storage.put(a, new LinkedHashMap<>());
            }
        }

        WebDriver driver() {
            return (WebDriver) Proxy.newProxyInstance(getClass().getClassLoader(),
                new Class<?>[] {WebDriver.class, JavascriptExecutor.class}, (proxy, method, args) -> {
                    switch (method.getName()) {
                        case "executeScript":
                            return execute((String) args[0], (Object[]) args[1]);
                        case "toString":
                            return "FakeBrowserDriver";
                        case "hashCode":
                            return System.identityHashCode(proxy);
                        case "equals":
                            return proxy == args[0];
                        default:
                            return null;
                    }
                });
        }

        private Object execute(String script, Object[] a) {
            scripts.add(script);
            Area area = script.contains(Area.SESSION.jsName()) ? Area.SESSION : Area.LOCAL;
            Map<String, String> s = storage.get(area);
            if (script.contains("s.key(i)")) {
                return new LinkedHashMap<String, Object>(s);
            }
            if (script.contains("Object.keys")) {
                return new ArrayList<Object>(s.keySet());
            }
            if (script.contains(".getItem(arguments[0])")) {
                return s.get((String) a[0]);
            }
            if (script.contains(".setItem(")) {
                s.put((String) a[0], (String) a[1]);
                return null;
            }
            if (script.contains(".removeItem(")) {
                s.remove((String) a[0]);
                return null;
            }
            if (script.contains(".clear()")) {
                s.clear();
                return null;
            }
            if (script.contains(".length")) {
                return (long) s.size();
            }
            throw new IllegalStateException("Unrecognised script: " + script);
        }
    }
}
