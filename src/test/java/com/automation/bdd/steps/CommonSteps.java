package com.automation.bdd.steps;

import com.automation.bdd.support.ElementLocator;
import com.automation.core.api.ApiClient;
import com.automation.core.config.ConfigReader;
import com.automation.core.keyword.PlaceholderResolver;
import com.automation.core.utils.HumanActions;
import com.automation.core.verify.FailureHandling;
import com.automation.core.verify.Verify;
import io.appium.java_client.AppiumDriver;
import io.appium.java_client.android.AndroidDriver;
import io.appium.java_client.android.nativekey.AndroidKey;
import io.appium.java_client.android.nativekey.KeyEvent;
import io.cucumber.java.en.Given;
import io.cucumber.java.en.Then;
import io.cucumber.java.en.When;
import io.restassured.response.Response;
import org.openqa.selenium.By;
import org.openqa.selenium.Dimension;
import org.openqa.selenium.Keys;
import org.openqa.selenium.NoSuchElementException;
import org.openqa.selenium.TimeoutException;
import org.openqa.selenium.WebDriver;
import org.openqa.selenium.WebElement;
import org.openqa.selenium.interactions.Actions;
import org.openqa.selenium.interactions.PointerInput;
import org.openqa.selenium.interactions.Sequence;
import org.openqa.selenium.remote.RemoteWebDriver;
import org.openqa.selenium.support.ui.ExpectedConditions;
import org.openqa.selenium.support.ui.Select;
import org.openqa.selenium.support.ui.WebDriverWait;

import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * The one set of steps that works for EVERY site and app - web, mobile and API - so a tester
 * writes Gherkin only. Nothing here is specific to a site.
 *
 * <ul>
 *   <li>Web ({@code @ui}) and mobile ({@code @mobile}) share the same sentences; the running
 *       driver decides how an action is performed.</li>
 *   <li>API ({@code @api}) has its own request/response sentences.</li>
 *   <li>"element" is an inline locator ({@code css:#id}, {@code xpath://..}, {@code text:Sign in},
 *       {@code accessibility:Login}...) or a key from {@code objectrepository/<site>.properties}.
 *       See {@link ElementLocator}.</li>
 *   <li>Values may use the framework's {@code ${...}} placeholders so secrets stay out of
 *       feature files.</li>
 * </ul>
 * The site is the one chosen with {@code -Dsite}.
 */
public class CommonSteps extends StepBase {

    // API state: Cucumber creates a fresh instance of this class for every scenario.
    private Response response;
    private final Map<String, String> headers = new HashMap<>();

    // ═════════════════ Web and mobile: navigation ═════════════════

    @Given("the user opens the home page")
    public void opensHome() {
        driver().get(ConfigReader.get("url"));
    }

    @Given("the user opens the page {string}")
    public void opensPage(String path) {
        if (path.startsWith("http://") || path.startsWith("https://")) {
            driver().get(path);
            return;
        }
        String base = ConfigReader.get("url");
        if (base.endsWith("/")) {
            base = base.substring(0, base.length() - 1);
        }
        driver().get(base + (path.startsWith("/") ? path : "/" + path));
    }

    @When("the user navigates back")
    public void navigatesBack() {
        driver().navigate().back();
    }

    @When("the user waits for the page to load")
    public void waitsForPageLoad() {
        if (isMobile()) {
            // Native screens have no document.readyState; UiAutomator2 already waits for the UI to
            // go idle before each command, so there is nothing extra to wait for.
            return;
        }
        waitFor().until(d -> "complete".equals(((RemoteWebDriver) d).executeScript("return document.readyState")));
    }

    // There is deliberately no "the user waits N seconds" step: a fixed pause is either too short
    // (flaky) or too long (slow). Wait for a condition instead - "the user should see ..." and
    // "the user should not see ..." both poll until the element appears or disappears, every
    // click/type step waits for its element, and "the user waits for the page to load" is above.

    // ═════════════════ Web and mobile: actions ═════════════════

    @When("the user clicks {string}")
    public void clicks(String element) {
        By by = by(element);
        if (isMobile()) {
            waitFor().until(ExpectedConditions.elementToBeClickable(by)).click();
        } else {
            HumanActions.click(driver(), by);
        }
    }

    @When("the user taps {string}")
    public void taps(String element) {
        clicks(element);
    }

    @When("the user types {string} into {string}")
    public void typesInto(String text, String element) {
        String value = PlaceholderResolver.resolve(text);
        By by = by(element);
        if (isMobile()) {
            WebElement el = waitFor().until(ExpectedConditions.visibilityOfElementLocated(by));
            el.clear();
            el.sendKeys(value);
        } else {
            HumanActions.type(driver(), by, value);
        }
    }

    @When("the user clears {string}")
    public void clears(String element) {
        waitFor().until(ExpectedConditions.visibilityOfElementLocated(by(element))).clear();
    }

    @When("the user selects {string} from {string}")
    public void selectsFrom(String visibleText, String element) {
        requireWeb("select-from-dropdown (tap the spinner, then tap the option)");
        WebElement el = waitFor().until(ExpectedConditions.visibilityOfElementLocated(by(element)));
        new Select(el).selectByVisibleText(PlaceholderResolver.resolve(visibleText));
    }

    @When("the user hovers over {string}")
    public void hovers(String element) {
        requireWeb("hover");
        WebElement el = waitFor().until(ExpectedConditions.visibilityOfElementLocated(by(element)));
        new Actions(driver()).moveToElement(el).perform();
    }

    @When("the user scrolls to {string}")
    public void scrollsTo(String element) {
        By by = by(element);
        if (isMobile()) {
            // No JavaScript on native screens: swipe up until the element is on screen.
            for (int attempt = 0; attempt < 8; attempt++) {
                List<WebElement> found = driver().findElements(by);
                if (!found.isEmpty() && found.get(0).isDisplayed()) {
                    return;
                }
                swipe(0.7, 0.3);
            }
            throw new NoSuchElementException("'" + element + "' not found after scrolling 8 screens");
        }
        WebElement el = waitFor().until(ExpectedConditions.presenceOfElementLocated(by));
        ((RemoteWebDriver) driver()).executeScript("arguments[0].scrollIntoView({block:'center'});", el);
    }

    @When("the user presses the {string} key")
    public void pressesKey(String key) {
        String name = key.trim().toUpperCase(Locale.ROOT);
        if (isMobile()) {
            if (!(driver() instanceof AndroidDriver android)) {
                throw new UnsupportedOperationException("Pressing keys is only supported on Android in these steps");
            }
            // Web-style names map to Android keys; any AndroidKey name (BACK, HOME, ENTER...) also works.
            String androidName = switch (name) {
                case "BACK_SPACE", "BACKSPACE" -> "DEL";
                case "ESCAPE", "ESC" -> "BACK";
                case "RETURN" -> "ENTER";
                default -> name;
            };
            android.pressKey(new KeyEvent(AndroidKey.valueOf(androidName)));
            return;
        }
        new Actions(driver()).sendKeys(Keys.valueOf(name)).perform();
    }

    @When("the user swipes up")
    public void swipesUp() {
        swipe(0.7, 0.3);
    }

    @When("the user swipes down")
    public void swipesDown() {
        swipe(0.3, 0.7);
    }

    @When("the user switches to the frame {string}")
    public void switchesToFrame(String element) {
        WebElement frame = waitFor().until(ExpectedConditions.visibilityOfElementLocated(by(element)));
        driver().switchTo().frame(frame);
    }

    @When("the user switches back to the main page")
    public void switchesToMain() {
        driver().switchTo().defaultContent();
    }

    @When("the user accepts the alert")
    public void acceptsAlert() {
        driver().switchTo().alert().accept();
    }

    @When("the user dismisses the alert")
    public void dismissesAlert() {
        driver().switchTo().alert().dismiss();
    }

    // ═════════════════ Web and mobile: checks ═════════════════

    @Then("the user should see {string}")
    public void shouldSee(String element) {
        boolean shown;
        try {
            waitFor().until(ExpectedConditions.visibilityOfElementLocated(by(element)));
            shown = true;
        } catch (TimeoutException e) {
            shown = false;
        }
        check(shown, "'" + element + "' is displayed");
    }

    @Then("the user should not see {string}")
    public void shouldNotSee(String element) {
        boolean gone;
        try {
            gone = Boolean.TRUE.equals(waitFor().until(ExpectedConditions.invisibilityOfElementLocated(by(element))));
        } catch (TimeoutException e) {
            gone = false;
        }
        check(gone, "'" + element + "' is not displayed");
    }

    @Then("{string} should have text {string}")
    public void shouldHaveText(String element, String expected) {
        String want = PlaceholderResolver.resolve(expected);
        String actual = waitFor().until(ExpectedConditions.visibilityOfElementLocated(by(element)))
            .getText().trim();
        check(actual.contains(want), "'" + element + "' contains text '" + want + "' (actual: '" + actual + "')");
    }

    @Then("the url should contain {string}")
    public void urlContains(String expected) {
        boolean ok;
        try {
            ok = waitFor().until(ExpectedConditions.urlContains(expected));
        } catch (TimeoutException e) {
            ok = false;
        }
        check(ok, "url contains '" + expected + "' (actual: " + driver().getCurrentUrl() + ")");
    }

    @Then("the page title should contain {string}")
    public void titleContains(String expected) {
        boolean ok;
        try {
            ok = waitFor().until(ExpectedConditions.titleContains(expected));
        } catch (TimeoutException e) {
            ok = false;
        }
        check(ok, "title contains '" + expected + "' (actual: " + driver().getTitle() + ")");
    }

    // ═════════════════ API (@api) ═════════════════

    @Given("the user sets the header {string} to {string}")
    public void setsHeader(String name, String value) {
        headers.put(name, PlaceholderResolver.resolve(value));
    }

    @When("the user sends a GET request to {string}")
    public void sendsGet(String path) {
        response = ApiClient.request().headers(headers).when().get(path);
    }

    @When("the user sends a DELETE request to {string}")
    public void sendsDelete(String path) {
        response = ApiClient.request().headers(headers).when().delete(path);
    }

    @When("the user sends a POST request to {string} with body {string}")
    public void sendsPost(String path, String body) {
        response = ApiClient.jsonRequest().headers(headers).body(PlaceholderResolver.resolve(body)).when().post(path);
    }

    @When("the user sends a PUT request to {string} with body {string}")
    public void sendsPut(String path, String body) {
        response = ApiClient.jsonRequest().headers(headers).body(PlaceholderResolver.resolve(body)).when().put(path);
    }

    @When("the user sends a PATCH request to {string} with body {string}")
    public void sendsPatch(String path, String body) {
        response = ApiClient.jsonRequest().headers(headers).body(PlaceholderResolver.resolve(body)).when().patch(path);
    }

    @When("the user sends a POST request to {string} with the body:")
    public void sendsPostDoc(String path, String body) {
        sendsPost(path, body);
    }

    @When("the user sends a PUT request to {string} with the body:")
    public void sendsPutDoc(String path, String body) {
        sendsPut(path, body);
    }

    @When("the user sends a PATCH request to {string} with the body:")
    public void sendsPatchDoc(String path, String body) {
        sendsPatch(path, body);
    }

    @Then("the response status should be {int}")
    public void statusIs(int expected) {
        check(response().statusCode() == expected,
            "response status is " + expected + " (actual: " + response().statusCode() + ")");
    }

    @Then("the response body should contain {string}")
    public void bodyContains(String expected) {
        check(response().asString().contains(expected), "response body contains '" + expected + "'");
    }

    @Then("the response field {string} should be {string}")
    public void fieldIs(String jsonPath, String expected) {
        String actual = response().jsonPath().getString(jsonPath);
        check(expected.equals(actual), "response field '" + jsonPath + "' is '" + expected + "' (actual: '" + actual + "')");
    }

    @Then("the response field {string} should not be empty")
    public void fieldNotEmpty(String jsonPath) {
        String actual = response().jsonPath().getString(jsonPath);
        check(actual != null && !actual.isEmpty(), "response field '" + jsonPath + "' is not empty");
    }

    @Then("the response time should be under {int} ms")
    public void timeUnder(int millis) {
        long actual = response().time();
        check(actual < millis, "response time under " + millis + " ms (actual: " + actual + " ms)");
    }

    // ═════════════════ Plumbing ═════════════════

    private By by(String element) {
        return ElementLocator.resolve(element, ConfigReader.getActiveSite());
    }

    private WebDriverWait waitFor() {
        return new WebDriverWait(driver(), Duration.ofSeconds(ConfigReader.getInt("timeout", 10)));
    }

    private boolean isMobile() {
        WebDriver d = driver();
        return d instanceof AppiumDriver;
    }

    private void requireWeb(String action) {
        if (isMobile()) {
            throw new UnsupportedOperationException("'" + action + "' is a web-only step; it does not apply to a native mobile screen");
        }
    }

    private Response response() {
        if (response == null) {
            throw new IllegalStateException("No API response yet - send a request first (tag the scenario @api).");
        }
        return response;
    }

    private static void check(boolean condition, String what) {
        Verify.verifyTrue(condition, what, FailureHandling.STOP_ON_FAILURE);
    }

    private void swipe(double startFraction, double endFraction) {
        Dimension size = driver().manage().window().getSize();
        int x = size.width / 2;
        int startY = (int) (size.height * startFraction);
        int endY = (int) (size.height * endFraction);
        PointerInput finger = new PointerInput(PointerInput.Kind.TOUCH, "finger");
        Sequence seq = new Sequence(finger, 1)
            .addAction(finger.createPointerMove(Duration.ZERO, PointerInput.Origin.viewport(), x, startY))
            .addAction(finger.createPointerDown(PointerInput.MouseButton.LEFT.asArg()))
            .addAction(finger.createPointerMove(Duration.ofMillis(600), PointerInput.Origin.viewport(), x, endY))
            .addAction(finger.createPointerUp(PointerInput.MouseButton.LEFT.asArg()));
        ((RemoteWebDriver) driver()).perform(List.of(seq));
    }
}
