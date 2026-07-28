package com.automation.sites.demoqa.tests;

import java.util.logging.Logger;

import com.automation.sites.core.BaseTest;
import com.automation.sites.demoqa.pages.AccordianPage;
import org.testng.Assert;
import org.testng.annotations.Test;

public class AccordianTest extends BaseTest {

    private static final Logger logger = Logger.getLogger(AccordianTest.class.getName());

    @Test(priority = 1,
        groups = {"smoke", "regression"},
        description = "Accordian - Section 1 Is Open By Default")
    public void verifySection1OpenByDefault() {
        AccordianPage page = new AccordianPage(getDriver());

        page.navigateToAccordian();

        String header = page.getSection1HeaderText();
        logger.info("Section 1 header: " + header);

        Assert.assertFalse(
            header.isEmpty(),
            "Section 1 header should have text"
        );

        Assert.assertTrue(
            page.isSection1ContentVisible(),
            "Section 1 content should be visible by default"
        );
    }

    @Test(priority = 2,
        groups = {"regression"},
        description = "Accordian - Section 2 Opens On Click")
    public void verifySection2OpensOnClick() {
        AccordianPage page = new AccordianPage(getDriver());

        page.navigateToAccordian();
        page.openSection2();

        Assert.assertTrue(
            page.isSection2ContentVisible(),
            "Section 2 content should be visible after click"
        );
    }

    @Test(priority = 3,
        groups = {"regression"},
        description = "Accordian - Section 3 Opens On Click")
    public void verifySection3OpensOnClick() {
        AccordianPage page = new AccordianPage(getDriver());

        page.navigateToAccordian();
        page.openSection3();

        Assert.assertTrue(
            page.isSection3ContentVisible(),
            "Section 3 content should be visible after click"
        );
    }

    @Test(priority = 4,
        groups = {"regression"},
        description = "Accordian - Only One Section Open At A Time")
    public void verifyOnlyOneSectionOpenAtTime() {
        AccordianPage page = new AccordianPage(getDriver());

        page.navigateToAccordian();

        // Section 1 is open by default
        Assert.assertTrue(page.isSection1ContentVisible(),
            "Section 1 should be visible by default");

        // Open Section 2
        page.openSection2();
        Assert.assertTrue(page.isSection2ContentVisible(),
            "Section 2 should be visible after click");
        Assert.assertFalse(page.isSection1ContentVisible(),
            "Section 1 should be collapsed when Section 2 opens");

        // Open Section 3
        page.openSection3();
        Assert.assertTrue(page.isSection3ContentVisible(),
            "Section 3 should be visible after click");
        Assert.assertFalse(page.isSection2ContentVisible(),
            "Section 2 should be collapsed when Section 3 opens");
    }

    @Test(priority = 5,
        groups = {"regression"},
        description = "Accordian - Section Closes When Clicked Again")
    public void verifySectionToggles() {
        AccordianPage page = new AccordianPage(getDriver());

        page.navigateToAccordian();
        page.openSection2();

        Assert.assertTrue(page.isSection2ContentVisible(),
            "Section 2 should be visible after first click");

        page.clickSection2Header();
        Assert.assertFalse(page.isSection2ContentVisible(),
            "Section 2 should be hidden after second click");
    }
}
