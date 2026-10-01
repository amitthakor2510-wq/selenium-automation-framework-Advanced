package com.automation.sites.listeners;

import org.junit.jupiter.api.Test;

import java.io.StringReader;
import java.io.IOException;
import java.util.Properties;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Pure-logic tests for TestSelection — no TestNG, no browser, no files. */
class TestSelectionTest {

    private static final String LOGIN = "com.automation.sites.saucedemo.tests.LoginTest";
    private static final String BOOKS = "com.automation.sites.demoqa.tests.BookStoreApplicationTest";
    private static final String[] NO_GROUPS = new String[0];

    private static TestSelection of(String fileContent) {
        return of(fileContent, null, null);
    }

    private static TestSelection of(String fileContent, String sysDisabled, String sysRunOnly) {
        Properties props = new Properties();
        try {
            props.load(new StringReader(fileContent));
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
        return TestSelection.from(props, sysDisabled, sysRunOnly);
    }

    @Test
    void emptyConfigDisablesNothing() {
        TestSelection s = of("");
        assertTrue(s.isEmpty());
        assertFalse(s.disabledReason(LOGIN, "verifyLogin", new String[]{"smoke"}).isPresent());
    }

    @Test
    void explicitTrueChangesNothing() {
        TestSelection s = of("test.LoginTest.enabled=true\ngroup.perf.enabled=true\n");
        assertTrue(s.isEmpty());
        assertFalse(s.disabledReason(LOGIN, "x", NO_GROUPS).isPresent());
    }

    @Test
    void classCanBeDisabledBySimpleName() {
        TestSelection s = of("test.LoginTest.enabled=false");
        assertTrue(s.disabledReason(LOGIN, "anyMethod", NO_GROUPS).isPresent());
        assertFalse(s.disabledReason(BOOKS, "anyMethod", NO_GROUPS).isPresent());
    }

    @Test
    void classCanBeDisabledByFullyQualifiedName() {
        TestSelection s = of("test." + LOGIN + ".enabled=false");
        assertTrue(s.disabledReason(LOGIN, "m", NO_GROUPS).isPresent());
    }

    @Test
    void simpleNameDoesNotMatchAClassThatMerelyEndsWithIt() {
        // "LoginTest" must not switch off e.g. KeywordDrivenLoginTest.
        TestSelection s = of("test.LoginTest.enabled=false");
        assertFalse(s.disabledReason("com.x.KeywordDrivenLoginTest", "m", NO_GROUPS).isPresent());
    }

    @Test
    void onlyTheNamedMethodIsDisabled() {
        TestSelection s = of("test.BookStoreApplicationTest#verifyLogin.enabled=false");
        assertTrue(s.disabledReason(BOOKS, "verifyLogin", NO_GROUPS).isPresent());
        assertFalse(s.disabledReason(BOOKS, "verifyLogout", NO_GROUPS).isPresent());
        // A class-level annotation (methodName == null) is not affected by a method rule.
        assertFalse(s.disabledReason(BOOKS, null, NO_GROUPS).isPresent());
    }

    @Test
    void packageWildcardDisablesEverythingBelowIt() {
        TestSelection s = of("test.com.automation.sites.demoqa.tests.*.enabled=false");
        assertTrue(s.disabledReason(BOOKS, "m", NO_GROUPS).isPresent());
        assertFalse(s.disabledReason(LOGIN, "m", NO_GROUPS).isPresent());
    }

    @Test
    void groupSwitchDisablesAnyTestCarryingIt() {
        TestSelection s = of("group.perf.enabled=false");
        assertTrue(s.disabledReason(LOGIN, "m", new String[]{"regression", "perf"}).isPresent());
        assertFalse(s.disabledReason(LOGIN, "m", new String[]{"regression"}).isPresent());
        assertFalse(s.disabledReason(LOGIN, "m", null).isPresent());
    }

    @Test
    void runOnlyRestrictsToTheListedClasses() {
        TestSelection s = of("run.only=LoginTest, SampleTest");
        assertFalse(s.disabledReason(LOGIN, "m", NO_GROUPS).isPresent());
        assertTrue(s.disabledReason(BOOKS, "m", NO_GROUPS).isPresent());
    }

    @Test
    void runOnlyNeverReEnablesADisabledTest() {
        TestSelection s = of("run.only=LoginTest\ntest.LoginTest#flaky.enabled=false");
        assertTrue(s.disabledReason(LOGIN, "flaky", NO_GROUPS).isPresent());
        assertFalse(s.disabledReason(LOGIN, "stable", NO_GROUPS).isPresent());
    }

    @Test
    void systemPropertyRunOnlyOverridesTheFile() {
        TestSelection s = of("run.only=LoginTest", null, "BookStoreApplicationTest");
        assertFalse(s.disabledReason(BOOKS, "m", NO_GROUPS).isPresent());
        assertTrue(s.disabledReason(LOGIN, "m", NO_GROUPS).isPresent());
    }

    @Test
    void systemPropertyDisabledAddsToTheFile() {
        TestSelection s = of("test.LoginTest.enabled=false", "BookStoreApplicationTest#verifyLogin, Other", null);
        assertTrue(s.disabledReason(LOGIN, "m", NO_GROUPS).isPresent());
        assertTrue(s.disabledReason(BOOKS, "verifyLogin", NO_GROUPS).isPresent());
        assertFalse(s.disabledReason(BOOKS, "other", NO_GROUPS).isPresent());
    }

    @Test
    void valuesOtherThanTrueFalseAreTreatedAsEnabled() {
        // Fails open: a typo must never silently drop a test.
        TestSelection s = of("test.LoginTest.enabled=fasle\ntest.SampleTest.enabled=no");
        assertFalse(s.disabledReason(LOGIN, "m", NO_GROUPS).isPresent());
        assertTrue(s.isEmpty());
    }

    @Test
    void falseIsCaseInsensitiveAndWhitespaceTolerant() {
        TestSelection s = of("test.LoginTest.enabled=  FALSE  ");
        assertTrue(s.disabledReason(LOGIN, "m", NO_GROUPS).isPresent());
    }

    @Test
    void reasonNamesWhatSwitchedTheTestOff() {
        TestSelection s = of("test.LoginTest.enabled=false");
        assertEquals("class 'LoginTest' is disabled", s.disabledReason(LOGIN, "m", NO_GROUPS).orElse(""));
    }

    @Test
    void unrelatedKeysAreIgnored() {
        TestSelection s = of("something.else=1\ntest.enabled=false\ngroup.enabled=false\ntest..enabled=false\nrun.only=");
        assertFalse(s.disabledReason(LOGIN, "m", NO_GROUPS).isPresent());
    }
}
