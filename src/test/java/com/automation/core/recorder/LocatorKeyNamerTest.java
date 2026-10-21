package com.automation.core.recorder;

import com.automation.core.recorder.RecordedEvent.FrameStep;
import com.automation.core.recorder.RecordedEvent.Target;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** Naming rules for object-repository keys and the environment variables behind secrets. */
class LocatorKeyNamerTest {

    @Test
    void pageSlugComesFromTheLastMeaningfulPathSegment() {
        assertEquals("checkoutStepOne", LocatorKeyNamer.pageSlug("https://x.test/checkout-step-one.html"));
        assertEquals("home", LocatorKeyNamer.pageSlug("https://x.test/"));
        assertEquals("products", LocatorKeyNamer.pageSlug("https://x.test/products/12345"));
        assertEquals("login", LocatorKeyNamer.pageSlug("https://x.test/#/login"));
        assertEquals("page", LocatorKeyNamer.pageSlug(null));
    }

    @Test
    void elementNamePrefersTestIdThenIdAndSkipsGeneratedValues() {
        Target t = new Target();
        t.tag = "input";
        t.id = "input-83921";
        t.name = "email";
        assertEquals("email", LocatorKeyNamer.elementName(t));

        t.hints.testId = "login-user";
        assertEquals("loginUser", LocatorKeyNamer.elementName(t));

        Target bare = new Target();
        bare.tag = "button";
        assertEquals("button", LocatorKeyNamer.elementName(bare));
    }

    @Test
    void namesAreCamelCasedAsciiAndNeverStartWithADigit() {
        assertEquals("signInNow", LocatorKeyNamer.camel("Sign in now!", "x"));
        assertEquals("element2fa", LocatorKeyNamer.camel("2fa", "element"));
        assertEquals("fallback", LocatorKeyNamer.camel("!!!", "fallback"));
    }

    @Test
    void frameNamesEndInFrameAndEnvNamesAreUpperSnake() {
        FrameStep f = new FrameStep();
        f.name = "payment";
        assertEquals("paymentFrame", LocatorKeyNamer.frameName(f, 1));
        assertEquals("frame2", LocatorKeyNamer.frameName(new FrameStep(), 2));
        assertEquals("SAUCEDEMO_LOGIN_PASSWORD", LocatorKeyNamer.envName("saucedemo.login.password"));
        assertEquals("LAB_LOGIN_ENTER_CODE_PASSWORD", LocatorKeyNamer.envName("lab.login.enterCodePassword"));
    }
}
