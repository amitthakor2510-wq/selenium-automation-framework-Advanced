package com.automation.sites.demoqa.pages;

import com.automation.core.base.BasePage;
import com.automation.core.config.ConfigReader;
import com.automation.core.utils.HumanActions;
import org.openqa.selenium.By;
import org.openqa.selenium.TimeoutException;
import org.openqa.selenium.WebDriver;
import org.openqa.selenium.WebElement;
import org.openqa.selenium.support.ui.ExpectedConditions;
import org.openqa.selenium.support.ui.WebDriverWait;

import java.time.Duration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class CheckBoxPage extends BasePage {

    private static final Logger logger = LoggerFactory.getLogger(CheckBoxPage.class);

    // CONFIRMED via full page-source dump (target/debug-dumps) after two
    // rct-* guesses both failed to find anything: demoqa has switched the
    // Check Box widget from react-checkbox-tree to rc-tree (antd's tree
    // component). Real markup:
    //   <div role="treeitem" class="rc-tree-treenode ...">
    //     <span class="rc-tree-switcher rc-tree-switcher_close"></span>
    //     <span class="rc-tree-checkbox" role="checkbox" aria-label="Select Home"></span>
    //     <span title="Home" class="rc-tree-node-content-wrapper ...">
    //       <span class="rc-tree-title">Home</span>
    //     </span>
    //   </div>
    // Note: rc-tree also renders one decoy node for virtualization
    // (aria-hidden="true", visibility:hidden, height:0px) — it only
    // contains an "rc-tree-indent" span, no switcher/checkbox/title, so it
    // can't accidentally match any of the locators below.
    private final By expandToggle    = By.cssSelector(".rc-tree-switcher");
    // Desktop node label (text-based, stable)
    private final By desktopLabel    = By.xpath("//span[@class='rc-tree-title' and text()='Desktop']");
    // Result section
    private final By resultSection   = By.id("result");

    // First-load React app boot can plausibly need more than the default
    // 10s timeout, same reasoning already applied to the book store page
    // ("this page got heavier" / needs a dedicated longer budget).
    private final WebDriverWait longWait;

    public CheckBoxPage(WebDriver driver) {
        super(driver);
        this.longWait = new WebDriverWait(driver,
            Duration.ofSeconds(ConfigReader.getInt("timeout.long", 15)));
    }

    public void navigateToCheckBox() {
        navigateTo("/checkbox");
        try {
            longWait.until(ExpectedConditions.visibilityOfElementLocated(expandToggle));
        } catch (TimeoutException e) {
            // Unverified locator guess failing on its very first real run —
            // rather than guess a replacement blind, dump the full page
            // source to disk so the next fix is based on the real markup.
            dumpPageForDebugging("checkbox-page-load");
            throw e;
        }
        HumanActions.pause();
    }

    public void expandTree() {
        WebElement toggle = wait.until(
            ExpectedConditions.elementToBeClickable(expandToggle));
        js.executeScript("arguments[0].scrollIntoView({block:'center'});", toggle);
        HumanActions.pause();
        js.executeScript("arguments[0].click();", toggle);
        // Wait for Desktop node to appear after expanding
        wait.until(ExpectedConditions.visibilityOfElementLocated(desktopLabel));
        HumanActions.pause();
    }

    public void selectDesktop() {
        // Click the checkbox for the Desktop node. CONFIRMED via page-source
        // dump: rc-tree wraps each node in <div role="treeitem">, not <li>
        // (that was the react-checkbox-tree shape this used to assume) —
        // walk up to the nearest treeitem div, then find its checkbox span.
        By desktopCheckbox = By.xpath(
            "//span[@class='rc-tree-title' and text()='Desktop']" +
                "/ancestor::div[@role='treeitem'][1]//span[contains(@class,'rc-tree-checkbox')]"
        );
        WebElement cb = wait.until(
            ExpectedConditions.elementToBeClickable(desktopCheckbox));
        js.executeScript("arguments[0].scrollIntoView({block:'center'});", cb);
        HumanActions.pause();
        js.executeScript("arguments[0].click();", cb);
        HumanActions.pause();
    }

    public boolean isResultDisplayed() {
        return wait.until(ExpectedConditions.visibilityOfElementLocated(resultSection))
            .isDisplayed();
    }

    // dumpPageForDebugging(label) is inherited from BasePage, which itself
    // just delegates to core/utils/DebugDumpUtils — see there for the
    // shared implementation (was a duplicate of this class's own copy
    // until consolidated, then promoted out of BasePage into core/utils).
}
