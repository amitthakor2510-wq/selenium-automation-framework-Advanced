#!/usr/bin/env bash
# Usage: ./scripts/new-site.sh <sitename> <base-url>
# Example: ./scripts/new-site.sh indianrail https://nationalrail.co.uk

set -e

SITE="$1"
URL="$2"

if [[ -z "$SITE" || -z "$URL" ]]; then
  echo "Usage: $0 <sitename> <base-url>"
  exit 1
fi

# 1. Config
cat > "src/test/resources/config/${SITE}.properties" <<EOF
site.name=${SITE}
url=${URL}
EOF
echo "[✓] Created config/${SITE}.properties"

# 2. Regression suite
cat > "testng-suites/${SITE}-regression.xml" <<EOF
<!DOCTYPE suite SYSTEM "https://testng.org/testng-1.0.dtd">
<suite name="${SITE^} Regression Suite" parallel="none">
    <listeners>
        <listener class-name="com.automation.sites.listeners.TestListener"/>
        <listener class-name="com.automation.sites.listeners.RetryListener"/>
    </listeners>
    <test name="${SITE^} Regression Tests" preserve-order="true">
        <groups>
            <run><include name="regression"/></run>
        </groups>
        <packages>
            <package name="com.automation.sites.${SITE}.tests"/>
        </packages>
    </test>
</suite>
EOF
echo "[✓] Created testng-suites/${SITE}-regression.xml"

# 3. Smoke suite
cat > "testng-suites/${SITE}-smoke.xml" <<EOF
<!DOCTYPE suite SYSTEM "https://testng.org/testng-1.0.dtd">
<suite name="${SITE^} Smoke Suite" parallel="none">
    <listeners>
        <listener class-name="com.automation.sites.listeners.TestListener"/>
        <listener class-name="com.automation.sites.listeners.RetryListener"/>
    </listeners>
    <test name="${SITE^} Smoke Tests" preserve-order="true">
        <groups>
            <run><include name="smoke"/></run>
        </groups>
        <packages>
            <package name="com.automation.sites.${SITE}.tests"/>
        </packages>
    </test>
</suite>
EOF
echo "[✓] Created testng-suites/${SITE}-smoke.xml"

# 4. Page and Test stubs
mkdir -p "src/main/java/com/automation/sites/${SITE}/pages"
mkdir -p "src/test/java/com/automation/sites/${SITE}/tests"

CLASS="${SITE^}HomePage"
cat > "src/main/java/com/automation/sites/${SITE}/pages/${CLASS}.java" <<EOF
package com.automation.sites.${SITE}.pages;

import com.automation.core.base.BasePage;
import org.openqa.selenium.WebDriver;

public class ${CLASS} extends BasePage {

    public ${CLASS}(WebDriver driver) {
        super(driver);
    }

    public void navigateToHome() {
        navigateTo("");
    }
}
EOF
echo "[✓] Created pages/${CLASS}.java stub"

TEST="${SITE^}HomeTest"
cat > "src/test/java/com/automation/sites/${SITE}/tests/${TEST}.java" <<EOF
package com.automation.sites.${SITE}.tests;

import com.automation.sites.core.BaseTest;
import com.automation.sites.${SITE}.pages.${CLASS};
import io.qameta.allure.*;
import org.testng.Assert;
import org.testng.annotations.Test;

@Feature("${SITE^}")
public class ${TEST} extends BaseTest {

    @Test(priority = 1, groups = {"smoke", "regression"},
            description = "${SITE^} - Verify Home Page Loads")
    @Story("Home Page")
    @Severity(SeverityLevel.CRITICAL)
    public void verifyHomePageLoads() {
        ${CLASS} page = new ${CLASS}(getDriver());
        page.navigateToHome();
        Assert.assertFalse(getDriver().getTitle().isEmpty(),
                "Page title should not be empty");
    }
}
EOF
echo "[✓] Created tests/${TEST}.java stub"

echo ""
echo "✅ New site '${SITE}' scaffolded. Next steps:"
echo "   1. Add your page objects under src/main/java/com/automation/sites/${SITE}/pages/"
echo "   2. Add your tests under src/test/java/com/automation/sites/${SITE}/tests/"
echo "   3. Run: mvn test -Dsite=${SITE} -DsuiteXmlFile=testng-suites/${SITE}-regression.xml"