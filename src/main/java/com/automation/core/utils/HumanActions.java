package com.automation.core.utils;

import com.automation.core.config.ConfigReader;
import com.automation.core.selfhealing.SelfHealingEngine;
import io.qameta.allure.Step;
import org.openqa.selenium.By;
import org.openqa.selenium.WebDriver;
import org.openqa.selenium.WebElement;
import org.openqa.selenium.support.ui.WebDriverWait;

import java.time.Duration;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Centralised "human pause" engine.
 * Previously, human-like delay was only applied AFTER a whole test
 * finished (in TestListener), and individual test/page classes used
 * hardcoded pause(1)/Thread.sleep(1500) calls scattered everywhere.
 * That meant the delay was not actually human-like between individual
 * actions (click, type, navigate) - the exact steps a human eye/bot
 * detector would look at.
 * This class fixes that by giving Page Objects a single, config-driven
 * way to click/type with a randomized pause BEFORE each interaction,
 * and (for typing) randomized inter-keystroke delay. All timings come
 * from config (human.pause.*) so they can be tuned per site or turned
 * off entirely for fast CI smoke runs via human.pause.enabled=false.
 */
public final class HumanActions {

    private HumanActions() {
    }

    // ---------------------------------------------------------------
    // Core pause primitives
    // ---------------------------------------------------------------

    /** Sleeps a random duration between human.pause.min and human.pause.max (ms). */
    public static void pause() {
        pauseBetween(
            ConfigReader.getInt("human.pause.min", 400),
            ConfigReader.getInt("human.pause.max", 1200)
        );
    }

    /** Sleeps a random duration between human.pause.postTest.min/max (ms). Used after a test finishes. */
    public static void postTestPause() {
        pauseBetween(
            ConfigReader.getInt("human.pause.postTest.min", 500),
            ConfigReader.getInt("human.pause.postTest.max", 1500)
        );
    }

    private static void pauseBetween(int min, int max) {
        if (!ConfigReader.getBoolean("human.pause.enabled", true)) {
            return;
        }
        if (max < min) {
            max = min;
        }
        int delay = (max == min) ? min : ThreadLocalRandom.current().nextInt(min, max + 1);
        sleep(delay);
    }

    private static void sleep(int millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    // ---------------------------------------------------------------
    // Wrapped interactions - use these from Page Objects instead of
    // calling driver.findElement(...).click() / sendKeys(...) directly
    // ---------------------------------------------------------------

    @Step("Click element: {locator}")
    public static void click(WebDriver driver, By locator) {
        WebElement element = waitFor(driver, locator);
        pause();
        element.click();
    }

    @Step("Click element")
    public static void click(WebDriver driver, WebElement element) {
        pause();
        element.click();
    }

    @Step("Type \"{text}\" into element: {locator}")
    public static void type(WebDriver driver, By locator, String text) {
        WebElement element = waitFor(driver, locator);
        pause();
        typeHumanLike(element, text);
    }

    /** Types text one chunk at a time with a small randomized delay, mimicking human typing speed. */
    public static void typeHumanLike(WebElement element, String text) {
        boolean enabled = ConfigReader.getBoolean("human.pause.enabled", true);
        if (!enabled || text == null || text.isEmpty()) {
            element.sendKeys(text);
            return;
        }

        int min = ConfigReader.getInt("human.pause.typing.min", 40);
        int max = ConfigReader.getInt("human.pause.typing.max", 120);

        for (char c : text.toCharArray()) {
            element.sendKeys(String.valueOf(c));
            int delay = (max <= min) ? min : ThreadLocalRandom.current().nextInt(min, max + 1);
            sleep(delay);
        }
    }

    /**
     * Routed through SelfHealingEngine (instead of a bare
     * wait.until(ExpectedConditions.elementToBeClickable(...))) so that the
     * two most-used interaction methods in the whole framework — click()
     * and type() — automatically recover when a page's markup drifts,
     * rather than failing every test that touches the moved element.
     */
    private static WebElement waitFor(WebDriver driver, By locator) {
        WebDriverWait wait = new WebDriverWait(driver,
            Duration.ofSeconds(ConfigReader.getInt("timeout", 10)));
        return SelfHealingEngine.findClickable(driver, wait, locator);
    }
}
