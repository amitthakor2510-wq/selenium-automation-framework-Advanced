package com.automation.sites.demoqa.tests;

import com.automation.sites.core.BaseTest;
import com.automation.sites.demoqa.pages.ModalDialogsPage;
import org.testng.Assert;
import org.testng.annotations.Test;

public class ModalDialogsTest extends BaseTest {

    private ModalDialogsPage openPage() {
        ModalDialogsPage page = new ModalDialogsPage(getDriver());
        page.navigateToModalDialogs();
        return page;
    }

    @Test(priority = 1,
            groups = {"smoke", "regression"},
            description = "Modal Dialogs - Small Modal Title Is Correct")
    public void verifySmallModalTitle() {
        ModalDialogsPage page = openPage();

        page.openSmallModal();
        String title = page.getSmallModalTitle();
        page.closeSmallModal();

        Assert.assertEquals(title, "Small Modal");
    }

    @Test(priority = 2,
            groups = {"regression"},
            description = "Modal Dialogs - Small Modal Body Has Content")
    public void verifySmallModalBody() {
        ModalDialogsPage page = openPage();

        page.openSmallModal();
        String body = page.getSmallModalBody();
        page.closeSmallModal();

        Assert.assertFalse(
                body.isEmpty(),
                "Small modal body should not be empty. Got: '" + body + "'"
        );
    }

    @Test(priority = 3,
            groups = {"regression"},
            description = "Modal Dialogs - Large Modal Title Is Correct")
    public void verifyLargeModalTitle() {
        ModalDialogsPage page = openPage();

        page.openLargeModal();
        String title = page.getLargeModalTitle();
        page.closeLargeModal();

        Assert.assertEquals(title, "Large Modal");
    }

    @Test(priority = 4,
            groups = {"regression"},
            description = "Modal Dialogs - Large Modal Body Has Content")
    public void verifyLargeModalBody() {
        ModalDialogsPage page = openPage();

        page.openLargeModal();
        String body = page.getLargeModalBody();
        page.closeLargeModal();

        Assert.assertFalse(
                body.isEmpty(),
                "Large modal body should not be empty. Got: '" + body + "'"
        );
    }   
}
