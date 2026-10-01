package com.automation.sites.demoqa.pages;

import com.automation.core.base.BasePage;
import com.automation.core.utils.HumanActions;
import com.automation.core.utils.ScreenshotUtil;
import lombok.Getter;
import org.openqa.selenium.By;
import org.openqa.selenium.Keys;
import org.openqa.selenium.NoSuchElementException;
import org.openqa.selenium.StaleElementReferenceException;
import org.openqa.selenium.TimeoutException;
import org.openqa.selenium.WebDriver;
import org.openqa.selenium.WebElement;
import org.openqa.selenium.support.ui.ExpectedConditions;
import org.openqa.selenium.support.ui.WebDriverWait;

import java.time.Duration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class RegistrationPage extends BasePage {

    private static final Logger logger = LoggerFactory.getLogger(RegistrationPage.class);

    // IDs verified from compiled RegistrationPage.class:
    private final By firstNameInput  = By.id("firstname");
    private final By lastNameInput   = By.id("lastname");
    private final By userNameInput   = By.id("userName");
    private final By emailInput      = By.id("email");
    private final By passwordInput   = By.id("password");
    private final By registerButton  = By.id("register");
    private final By backToLoginLink = By.id("gotologin");

    // DemoQA renders server-side errors (e.g. "User already exists!",
    // "Please verify ReCaptcha!") inside this <p id="name"> element — NOT as
    // a JS alert and NOT with any class/text containing the words our old
    // diagnostics scanned for ("error"/"invalid"/"required"/"exist"), which
    // is why "Please verify ReCaptcha!" was silently missed before. When this
    // fires, React also resets the form fields back to '' — which is exactly
    // the "fields empty, no alert, no visible error" symptom seen in the logs.
    private final By serverErrorText = By.id("name");

    // Captured from the native JS alert shown after clicking Register.
    // This is the ONLY reliable success/failure signal DemoQA gives us here —
    // the page does not redirect and does not render a success/error element.
    private String lastAlertText = "";

    /**
     * -- GETTER --
     * Raw text captured from DemoQA's #name error element (may be empty).
     */
    // Captured from the #name error element when no alert appears.
    @Getter
    private String lastServerErrorText = "";

    /**
     * -- GETTER --
     * Actual captured API response (status + body) when no alert/#name text was found.
     */
    // Captured true HTTP response (status + body) via installNetworkCapture(),
    // used only when neither the alert nor #name gave us anything.
    @Getter
    private String lastNetworkErrorInfo = "";

    public RegistrationPage(WebDriver driver) {
        super(driver);
    }

    public void navigateToRegistration() {
        navigateTo("/register");
        wait.until(ExpectedConditions.visibilityOfElementLocated(firstNameInput));
        installNetworkCapture();
        logger.info("  Navigated to registration page");
    }

    /**
     * Installs a page-level fetch/XHR interceptor so we can see the ACTUAL
     * HTTP response of the registration API call (status + body), instead of
     * guessing from the DOM. This is independent of Chrome DevTools Protocol
     * version support — the logs show "Unable to find CDP implementation
     * matching 150", so a CDP-based Network.* approach would be unreliable
     * here anyway. Must run before Register is clicked.
     */
    private void installNetworkCapture() {
        String script =
            "window.__lastRegisterResponse = null;" +
                "if (!window.__registerCaptureInstalled) {" +
                "  window.__registerCaptureInstalled = true;" +
                "  var origFetch = window.fetch;" +
                "  window.fetch = function() {" +
                "    var args = arguments;" +
                "    return origFetch.apply(this, args).then(function(res) {" +
                "      try {" +
                "        var url = (typeof args[0] === 'string') ? args[0] : (args[0] && args[0].url) || '';" +
                "        if (url.toLowerCase().indexOf('account') !== -1) {" +
                "          res.clone().text().then(function(body) {" +
                "            window.__lastRegisterResponse = {url:url, status:res.status, body:body};" +
                "          });" +
                "        }" +
                "      } catch(e) {}" +
                "      return res;" +
                "    });" +
                "  };" +
                "  var origOpen = XMLHttpRequest.prototype.open;" +
                "  var origSend = XMLHttpRequest.prototype.send;" +
                "  XMLHttpRequest.prototype.open = function(method, url) {" +
                "    this.__capturedUrl = url;" +
                "    return origOpen.apply(this, arguments);" +
                "  };" +
                "  XMLHttpRequest.prototype.send = function(body) {" +
                "    var xhr = this;" +
                "    xhr.addEventListener('load', function() {" +
                "      try {" +
                "        if (xhr.__capturedUrl && xhr.__capturedUrl.toLowerCase().indexOf('account') !== -1) {" +
                "          window.__lastRegisterResponse = {url:xhr.__capturedUrl, status:xhr.status, body:xhr.responseText};" +
                "        }" +
                "      } catch(e) {}" +
                "    });" +
                "    return origSend.apply(this, arguments);" +
                "  };" +
                "}";
        try {
            js.executeScript(script);
        } catch (Exception e) {
            logger.warn("  Network capture install failed (non-fatal): " + e.getMessage());
        }
    }

    /**
     * Polls window.__lastRegisterResponse (populated by installNetworkCapture)
     * for a few seconds and returns a formatted "status: body" string, or ""
     * if no matching network response was captured in time.
     */
    @SuppressWarnings("unchecked")
    private String captureNetworkErrorInfo() {
        try {
            WebDriverWait shortWait = new WebDriverWait(driver, Duration.ofSeconds(5));
            Object result = shortWait.until(d -> {
                Object val = js.executeScript("return window.__lastRegisterResponse;");
                return val;
            });
            if (result instanceof java.util.Map) {
                java.util.Map<String, Object> map = (java.util.Map<String, Object>) result;
                return map.get("status") + ": " + map.get("body") + " (url: " + map.get("url") + ")";
            }
            return "";
        } catch (TimeoutException e) {
            return "";
        }
    }

    private void fillField(By locator, String value, String fieldName) {
        final int maxAttempts = 3;

        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            WebElement el = wait.until(
                ExpectedConditions.visibilityOfElementLocated(locator));

            js.executeScript("arguments[0].scrollIntoView({block:'center'});", el);
            js.executeScript("arguments[0].click();", el);

            el.sendKeys(Keys.chord(Keys.CONTROL, "a"));
            el.sendKeys(Keys.DELETE);

            HumanActions.typeHumanLike(el, value);

            String actual;
            try {
                actual = el.getAttribute("value");
            } catch (StaleElementReferenceException e) {
                actual = null; // force retry below
            }

            logger.info("  " + fieldName + ": typed='" + value
                + "' actual='" + actual + "' (attempt " + attempt + "/" + maxAttempts + ")");

            if (value.equals(actual)) {
                return;
            }
            if (attempt < maxAttempts) {
                logger.info("  " + fieldName + ": mismatch — retrying");
            }
        }

        throw new IllegalStateException(
            fieldName + " field still didn't contain '" + value
                + "' after " + maxAttempts + " attempts — page may be unstable");
    }

    /**
     * Checks a field's CURRENT value (without retyping) and re-fills it only
     * if it no longer matches what we already typed earlier. Cheap safety
     * net against the form clearing already-filled fields as a side effect
     * of interacting with later ones.
     */
    private void verifyAndReassertField(By locator, String expectedValue, String fieldName) {
        try {
            WebElement el = driver.findElement(locator);
            String actual = el.getAttribute("value");
            if (expectedValue.equals(actual)) {
                return;
            }
            logger.info("  " + fieldName + ": drifted to '" + actual
                + "' before submit — re-filling");
        } catch (Exception e) {
            logger.info("  " + fieldName + ": couldn't read current value (" + e.getMessage() + ") — re-filling");
        }
        fillField(locator, expectedValue, fieldName + " (reassert)");
    }

    /**
     * Same as fillField, but tolerant of the field not existing at all: waits
     * a short time and, if it never appears, logs and moves on instead of
     * failing the whole registration. Use for fields whose presence on the
     * live DemoQA Book Store register form is uncertain (it's a separate,
     * simpler form than the old demoqa.com/registration/ page and may not
     * include every field you'd expect e.g. Email).
     */
    private void fillFieldIfPresent(By locator, String value, String fieldName) {
        try {
            new WebDriverWait(driver, Duration.ofSeconds(3))
                .until(ExpectedConditions.visibilityOfElementLocated(locator));
        } catch (TimeoutException e) {
            logger.info("  " + fieldName + ": field not present on this form — skipping");
            return;
        }
        fillField(locator, value, fieldName);
    }

    public void registerUser(String firstName, String lastName,
                             String userName, String email, String password) {
        logger.info("  Filling registration form...");

        fillField(firstNameInput, firstName,  "First name");
        fillField(lastNameInput,  lastName,   "Last name");
        fillField(userNameInput,  userName,   "Username");
        fillFieldIfPresent(emailInput, email, "Email");
        fillField(passwordInput,  password,   "Password");

        // DemoQA's register form has been observed clearing earlier fields
        // (e.g. First/Last name) back to empty after later fields are filled —
        // re-verify everything is still correct immediately before submitting,
        // and re-fill anything that drifted.
        verifyAndReassertField(firstNameInput, firstName, "First name");
        verifyAndReassertField(lastNameInput,  lastName,  "Last name");
        verifyAndReassertField(userNameInput,  userName,  "Username");
        verifyAndReassertField(passwordInput,  password,  "Password");

        wait.until(ExpectedConditions.elementToBeClickable(registerButton));

        WebElement btn = wait.until(
            ExpectedConditions.presenceOfElementLocated(registerButton));
        js.executeScript("arguments[0].scrollIntoView({block:'center'});", btn);
        js.executeScript("arguments[0].click();", btn);
        logger.info("  Register clicked");

        lastAlertText = "";
        lastServerErrorText = "";
        lastNetworkErrorInfo = "";
        try {
            new WebDriverWait(driver, Duration.ofSeconds(8))
                .until(ExpectedConditions.alertIsPresent());
            lastAlertText = driver.switchTo().alert().getText();
            logger.info("  Alert: " + lastAlertText);
            driver.switchTo().alert().accept();
        } catch (TimeoutException e) {
            logger.info("  No alert appeared");
            lastServerErrorText = captureServerErrorText();
            if (!lastServerErrorText.isEmpty()) {
                logger.warn("  Server error text: '" + lastServerErrorText + "'");
            } else {
                lastNetworkErrorInfo = captureNetworkErrorInfo();
                if (!lastNetworkErrorInfo.isEmpty()) {
                    logger.warn("  Actual API response: " + lastNetworkErrorInfo);
                } else {
                    logger.info("  No API response captured either — request may never have fired "
                        + "(button/JS issue) or fired to an unexpected URL.");
                }
                dumpDiagnosticsIfStillOnRegisterPage();
            }
        }

        logger.info("  URL after register: " + driver.getCurrentUrl());
    }

    /**
     * Polls the #name element (DemoQA's real error surface on this form) for
     * a short window after Register is clicked. Fields typically reset to ''
     * in the same React update that populates this text, so we can't just
     * check it once immediately — give it a moment to render.
     */
    private String captureServerErrorText() {
        try {
            WebDriverWait shortWait = new WebDriverWait(driver, Duration.ofSeconds(4));
            WebElement el = shortWait.until(d -> {
                java.util.List<WebElement> els = d.findElements(serverErrorText);
                if (els.isEmpty()) {
                    return null;
                }
                String text = els.get(0).getText();
                return (text != null && !text.trim().isEmpty()) ? els.get(0) : null;
            });
            return el.getText().trim();
        } catch (TimeoutException e) {
            return "";
        }
    }

    /**
     * Diagnostic-only — runs when Register was clicked but nothing observable
     * happened (no alert, no navigation). Surfaces whatever validation/error
     * text is actually on the page and the register button's state, and
     * saves a screenshot, so the NEXT run's log tells us why instead of
     * requiring another blind guess at the DOM we can't see from here.
     */
    private void dumpDiagnosticsIfStillOnRegisterPage() {
        if (!driver.getCurrentUrl().contains("/register")) {
            return;
        }

        try {
            logger.info("  --- Diagnostics: registration did not visibly proceed ---");

            WebElement btn = driver.findElement(registerButton);
            logger.info("  Register button: enabled=" + btn.isEnabled()
                + " disabled-attr=" + btn.getAttribute("disabled")
                + " class=" + btn.getAttribute("class"));

            for (By field : new By[]{firstNameInput, lastNameInput, userNameInput, passwordInput}) {
                try {
                    WebElement el = driver.findElement(field);
                    logger.info("  " + field + ": value='" + el.getAttribute("value")
                        + "' aria-invalid=" + el.getAttribute("aria-invalid")
                        + " class=" + el.getAttribute("class"));
                } catch (NoSuchElementException ignored) {
                    // field not present on this form — skip
                }
            }

            By likelyErrorText = By.xpath(
                "//*[contains(translate(text(),'ABCDEFGHIJKLMNOPQRSTUVWXYZ','abcdefghijklmnopqrstuvwxyz')," +
                    "'error') or contains(translate(text(),'ABCDEFGHIJKLMNOPQRSTUVWXYZ','abcdefghijklmnopqrstuvwxyz')," +
                    "'invalid') or contains(translate(text(),'ABCDEFGHIJKLMNOPQRSTUVWXYZ','abcdefghijklmnopqrstuvwxyz')," +
                    "'required') or contains(translate(text(),'ABCDEFGHIJKLMNOPQRSTUVWXYZ','abcdefghijklmnopqrstuvwxyz')," +
                    "'exist') or contains(translate(text(),'ABCDEFGHIJKLMNOPQRSTUVWXYZ','abcdefghijklmnopqrstuvwxyz')," +
                    "'captcha')]");
            java.util.List<WebElement> errorEls = driver.findElements(likelyErrorText);
            if (errorEls.isEmpty()) {
                logger.warn("  No error/validation text found anywhere on the page");
            } else {
                for (WebElement el : errorEls) {
                    try {
                        String text = el.getText().trim();
                        if (!text.isEmpty()) {
                            logger.warn("  Possible error text: '" + text + "'");
                        }
                    } catch (Exception ignored) {
                        // element went stale between findElements() and getText() — skip it
                    }
                }
            }

            String screenshotPath = ScreenshotUtil.captureScreenshot(driver, "register_no_alert");
            logger.info("  Screenshot saved: " + screenshotPath);
            logger.info("  --- End diagnostics ---");
        } catch (Exception e) {
            logger.warn("  Diagnostics capture failed: " + e.getMessage());
        }
    }

    /**
     * The alert text is the primary success signal on DemoQA's Book Store
     * register page — it does NOT redirect to /login and does NOT render any
     * success/error text in the DOM on success. URL/text checks are kept only
     * as a fallback in case DemoQA's behavior changes.
     */
    public boolean isRegistrationSuccessful() {
        String alert = lastAlertText.toLowerCase();

        if (alert.contains("success")) {
            logger.info("  Alert confirmed success: " + lastAlertText);
            return true;
        }
        if (alert.contains("already exist") || alert.contains("user exists")) {
            logger.warn("  Registration failed — user already exists: " + lastAlertText);
            return false;
        }

        // #name error text (no alert case) — this is where DemoQA actually
        // surfaces "Please verify ReCaptcha!" and "User already exists!".
        String serverError = lastServerErrorText.toLowerCase();
        if (serverError.contains("captcha")) {
            logger.warn("  Registration blocked by DemoQA's ReCaptcha check: '"
                + lastServerErrorText + "'. This is a server-side rate-limit/bot-detection "
                + "response, not a locator or timing bug — it cannot be satisfied by "
                + "Selenium. Reduce registration frequency, reuse an existing test "
                + "account instead of registering fresh every run, or seed the account "
                + "via a direct API call instead of the UI.");
            return false;
        }
        if (serverError.contains("already exist") || serverError.contains("user exists")) {
            logger.warn("  Registration failed — user already exists: " + lastServerErrorText);
            return false;
        }
        if (!serverError.isEmpty()) {
            logger.warn("  Registration failed — server error: " + lastServerErrorText);
            return false;
        }
        if (!lastNetworkErrorInfo.isEmpty()) {
            logger.warn("  Registration failed — actual API response: " + lastNetworkErrorInfo);
            return false;
        }

        // Fallback heuristics (kept in case DemoQA ever changes to redirect-based flow)
        String url = driver.getCurrentUrl();
        if (url.contains("/login") || url.contains("/profile")) {
            logger.info("  Redirected to " + url + " ✓");
            return true;
        }
        By success = By.xpath(
            "//*[contains(text(),'registered') or contains(text(),'success')]");
        if (!driver.findElements(success).isEmpty()) {
            logger.info("  Success element visible ✓");
            return true;
        }

        logger.info("  Registration uncertain. Alert='" + lastAlertText + "' URL=" + url);
        return false;
    }

    /** True only when the last registration attempt was blocked by DemoQA's ReCaptcha check. */
    public boolean isBlockedByRecaptcha() {
        return lastServerErrorText.toLowerCase().contains("captcha");
    }

    public void clickBackToLogin() {
        try {
            WebElement link = wait.until(
                ExpectedConditions.elementToBeClickable(backToLoginLink));
            js.executeScript("arguments[0].scrollIntoView({block:'center'});", link);
            js.executeScript("arguments[0].click();", link);
            wait.until(ExpectedConditions.urlContains("/login"));
            logger.info("  On /login ✓");
        } catch (Exception e) {
            logger.info("  Navigating to /login directly");
            navigateTo("/login");
            wait.until(ExpectedConditions.visibilityOfElementLocated(By.id("userName")));
        }
    }
}
