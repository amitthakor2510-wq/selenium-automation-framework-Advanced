package com.automation.sites.demoqa.tests;

import com.automation.core.utils.AccessibilityUtils;
import com.automation.sites.core.BaseTest;
import com.automation.sites.demoqa.pages.CheckBoxPage;
import com.automation.sites.demoqa.pages.TextBoxPage;
import io.qameta.allure.Feature;
import io.qameta.allure.Story;
import org.testng.annotations.Test;

/**
 * Automated accessibility (WCAG / GIGW-adjacent) checks via axe-core.
 *
 * Deliberately tagged with group "accessibility" only — NOT "regression" or
 * "smoke" — so these do not run as part of the existing regression suites
 * and cannot suddenly start failing CI on a page they weren't yet tuned for.
 * Run them explicitly with:
 *
 *   mvn test -DsuiteXmlFile=testng-suites/demoqa-accessibility.xml -Dsite=demoqa
 *
 * See AccessibilityUtils for the a11y.enabled / a11y.failOn config knobs —
 * start with a11y.failOn=none (log-only) while triaging a new page's first
 * results, then tighten to critical,serious once the known issues are
 * either fixed or consciously accepted.
 */
@Feature("Accessibility")
public class AccessibilityTest extends BaseTest {

    // demoqa.com's own header/footer markup — present on every page, shared
    // across the whole site, and entirely outside this framework's or the
    // page-under-test's control — has two longstanding axe-core violations
    // confirmed present identically on both pages tested here:
    //   image-alt  [critical] the site logo image has no alt attribute
    //   link-name  [serious]  a header/footer icon link has no discernible text
    // Accepted as known upstream issues rather than fixed here (there is
    // nothing on our side to fix) so the build doesn't fail on someone
    // else's markup. Both are still logged and still attached in full to
    // the Allure report on every run — this only stops them from failing
    // the build. If demoqa fixes their own site chrome, these two rule IDs
    // will simply stop appearing in the violation list; no action needed.
    // Re-verify occasionally (e.g. if a totally different rule ID starts
    // getting suppressed unexpectedly, that would be a sign the site's
    // markup changed in a way worth re-reviewing).
    private static final String[] DEMOQA_SITE_WIDE_KNOWN_A11Y_ISSUES = {"image-alt", "link-name"};

    @Test(groups = {"accessibility"},
        description = "Text Box page has no critical/serious axe-core violations "
            + "beyond demoqa's own known site-wide markup issues")
    @Story("Text Box")
    public void textBoxPageIsAccessible() {
        TextBoxPage page = new TextBoxPage(getDriver());
        page.navigateToTextBox();
        // "label" [critical]: the Text Box page's own form has at least one
        // input without an associated <label> — specific to this page's
        // markup (not the shared site chrome above), but still demoqa's
        // own HTML, not this framework's — accepted for the same reason.
        AccessibilityUtils.assertNoViolations(getDriver(), "Text Box page",
            "image-alt", "link-name", "label");
    }

    @Test(groups = {"accessibility"},
        description = "Check Box page has no critical/serious axe-core violations "
            + "beyond demoqa's own known site-wide markup issues")
    @Story("Check Box")
    public void checkBoxPageIsAccessible() {
        CheckBoxPage page = new CheckBoxPage(getDriver());
        page.navigateToCheckBox();
        AccessibilityUtils.assertNoViolations(getDriver(), "Check Box page",
            DEMOQA_SITE_WIDE_KNOWN_A11Y_ISSUES);
    }
}
