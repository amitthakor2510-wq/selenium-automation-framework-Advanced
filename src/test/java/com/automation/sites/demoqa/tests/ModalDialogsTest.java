package com.automation.sites.demoqa.tests;

import com.automation.core.base.BaseTest;
import com.automation.sites.demoqa.pages.ModalDialogsPage;
import org.testng.Assert;
import org.testng.annotations.Test;

public class ModalDialogsTest extends BaseTest {

    @Test(priority = 1,
            groups = {"smoke", "regression"},
            description = "Modal Dialogs - Small Modal Opens With Correct Title")
    public void verifySmallModalTitle() {
        ModalDialogsPage page = new ModalDialogsPage(getDriver());

        page.navigateToModalDialogs();
        page.openSmallModal();

        Assert.assertEquals(
                page.getSmallModalTitle(),
                "Small Modal"
        );

        page.closeSmallModal();
    }

    @Test(priority = 2,
            groups = {"regression"},
            description = "Modal Dialogs - Small Modal Body Has Content")
    public void verifySmallModalBody() {
        ModalDialogsPage page = new ModalDialogsPage(getDriver());

        page.navigateToModalDialogs();
        page.openSmallModal();

        Assert.assertFalse(
                page.getSmallModalBody().isEmpty(),
                "Small modal body should not be empty"
        );

        page.closeSmallModal();
    }

    @Test(priority = 3,
            groups = {"regression"},
            description = "Modal Dialogs - Large Modal Opens With Correct Title")
    public void verifyLargeModalTitle() {
        ModalDialogsPage page = new ModalDialogsPage(getDriver());

        page.navigateToModalDialogs();
        page.openLargeModal();

        Assert.assertEquals(
                page.getLargeModalTitle(),
                "Large Modal"
        );

        page.closeLargeModal();
    }

    @Test(priority = 4,
            groups = {"regression"},
            description = "Modal Dialogs - Large Modal Body Has Content")
    public void verifyLargeModalBody() {
        ModalDialogsPage page = new ModalDialogsPage(getDriver());

        page.navigateToModalDialogs();
        page.openLargeModal();

        Assert.assertFalse(
                page.getLargeModalBody().isEmpty(),
                "Large modal body should not be empty"
        );

        page.closeLargeModal();
    }
}   