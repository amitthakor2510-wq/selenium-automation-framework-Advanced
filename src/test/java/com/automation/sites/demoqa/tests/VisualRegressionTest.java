package com.automation.sites.demoqa.tests;

import com.automation.core.utils.VisualRegressionUtils;
import com.automation.sites.core.BaseTest;
import com.automation.sites.demoqa.pages.TextBoxPage;
import io.qameta.allure.Feature;
import io.qameta.allure.Story;
import org.testng.annotations.Test;

/**
 * Pixel-level visual regression checks via VisualRegressionUtils (AShot).
 *
 * Tagged "visual" only — not "regression"/"smoke" — so it stays out of the
 * existing CI suites until a baseline has been deliberately captured and
 * reviewed. First run for each snapshot name just saves the baseline image
 * under src/test/resources/visual-baselines/demoqa/ and passes; commit that
 * file, then this becomes a real regression check on every later run.
 *
 * Run explicitly with:
 *   mvn test -DsuiteXmlFile=testng-suites/demoqa-visual.xml -Dsite=demoqa
 */
@Feature("Visual Regression")
public class VisualRegressionTest extends BaseTest {

    @Test(groups = {"visual"},
        description = "Text Box page layout matches its stored visual baseline")
    @Story("Text Box")
    public void textBoxPageMatchesBaseline() {
        TextBoxPage page = new TextBoxPage(getDriver());
        page.navigateToTextBox();
        VisualRegressionUtils.compareOrCaptureBaseline(getDriver(), "demoqa", "text-box-page");
    }
}
