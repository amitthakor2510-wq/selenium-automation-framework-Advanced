package com.automation.sites.demoqa.tests;

import com.automation.sites.core.BaseTest;
import com.automation.sites.demoqa.pages.SliderPage;
import org.testng.Assert;
import org.testng.annotations.Test;

public class SliderTest extends BaseTest {

    @Test(priority = 1,
            groups = {"smoke", "regression"},
            description = "Slider - Set Value To 50 And Verify")
    public void verifySliderValue() {
        SliderPage page = new SliderPage(getDriver());

        page.navigateToSlider();
        page.setSliderValue(50);

        Assert.assertEquals(
                page.getSliderValue(), "50",
                "Slider value should be 50"
        );
    }

    @Test(priority = 2,
            groups = {"regression"},
            description = "Slider - Set Value To 75 And Verify")
    public void verifySliderValueAt75() {
        SliderPage page = new SliderPage(getDriver());

        page.navigateToSlider();
        page.setSliderValue(75);

        Assert.assertEquals(
                page.getSliderValue(), "75",
                "Slider value should be 75"
        );
    }
}
