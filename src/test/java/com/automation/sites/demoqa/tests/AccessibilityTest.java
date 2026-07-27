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

    @Test(groups = {"accessibility"},
        description = "Text Box page has no critical/serious axe-core violations")
    @Story("Text Box")
    public void textBoxPageIsAccessible() {
        TextBoxPage page = new TextBoxPage(getDriver());
        page.navigateToTextBox();
        AccessibilityUtils.assertNoViolations(getDriver(), "Text Box page");
    }

    @Test(groups = {"accessibility"},
        description = "Check Box page has no critical/serious axe-core violations")
    @Story("Check Box")
    public void checkBoxPageIsAccessible() {
        CheckBoxPage page = new CheckBoxPage(getDriver());
        page.navigateToCheckBox();
        AccessibilityUtils.assertNoViolations(getDriver(), "Check Box page");
    }
}
