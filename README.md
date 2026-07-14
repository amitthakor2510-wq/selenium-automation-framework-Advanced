# Selenium Automation Framework

A production-ready, scalable Java + Selenium automation framework built for
learning and real-world QA practice. Covers the complete demoqa.com test suite
with Jenkins and GitLab CI/CD integration, custom Extent HTML reporting, and
human-like interaction simulation.

---

## Tech Stack

| Tool | Version | Purpose |
|---|---|---|
| Java | 17 | Programming language |
| Selenium | 4.21.0 | Browser automation |
| TestNG | 7.9.0 | Test runner and reporting |
| Maven | 3.9+ | Build and dependency management |
| ExtentReports | 5.1.2 | Custom HTML test reports |
| WebDriverManager | 6.1.0 | Automatic browser driver management |
| Jenkins | Latest | CI/CD pipeline (local or server) |
| GitLab CI | Latest | CI/CD pipeline |

---

## Project Structure

```
selenium-automation-framework/
│
├── Jenkinsfile                          # Jenkins CI/CD pipeline
├── .gitlab-ci.yml                       # GitLab CI/CD pipeline
├── pom.xml                              # Maven dependencies and build config
├── README.md                            # This file
│
├── testng-suites/                       # TestNG suite files
│   ├── demoqa-smoke.xml                 # Quick sanity checks (smoke group)
│   └── demoqa-regression.xml            # Full test suite (regression group)
│
└── src/test/
    ├── java/com/automation/
    │   │
    │   ├── core/                        # SHARED framework — never site-specific
    │   │   ├── base/
    │   │   │   └── BaseTest.java        # Opens/closes browser per test
    │   │   ├── config/
    │   │   │   └── ConfigReader.java    # Reads layered config files
    │   │   ├── driver/
    │   │   │   └── DriverFactory.java   # Creates Chrome/Firefox/Edge driver
    │   │   ├── listeners/
    │   │   │   └── TestListener.java    # Connects results to Extent report
    │   │   ├── report/
    │   │   │   └── ExtentManager.java   # Creates HTML report (singleton)
    │   │   └── utils/
    │   │       ├── HumanActions.java    # Human-like delays on every action
    │   │       └── ScreenshotUtil.java  # Captures screenshots on failure
    │   │
    │   └── sites/                       # SITE-SPECIFIC code
    │       └── demoqa/
    │           ├── pages/               # Page Objects (locators + actions)
    │           └── tests/               # Test classes
    │
    └── resources/config/
        ├── global.properties            # Shared defaults for all sites
        ├── demoqa.properties            # demoqa-specific config (URL)
        └── _TEMPLATE.properties.example # Copy this when adding a new site
```

---

## Core Files — What Each One Does

### `BaseTest.java`
Parent class that every test extends. Handles browser lifecycle automatically.
```
@BeforeMethod → opens browser, navigates to site URL
@Test         → your test runs here
@AfterMethod  → closes browser, cleans ThreadLocal
```
Uses `ThreadLocal<WebDriver>` so each test thread gets its own browser instance — required for parallel execution.

### `ConfigReader.java`
Reads config in three layers, each overriding the previous:
```
global.properties   → base defaults
demoqa.properties   → site-specific overrides
-Dkey=value         → command line wins over everything
```
Call anywhere: `ConfigReader.get("browser")`, `ConfigReader.getInt("timeout", 10)`

### `DriverFactory.java`
Single place that creates the browser. Reads `browser` and `headless` from config.
Supports Chrome, Firefox, Edge. Sets download folder to `target/downloads` so
downloads work on both local machine and Jenkins.

### `HumanActions.java`
Wraps every Selenium click and type with random delays. Makes automation look
human. All timings come from config so they can be tuned or disabled.
```java
HumanActions.click(driver, locator)        // pause → click
HumanActions.type(driver, locator, text)   // pause → type char by char
HumanActions.pause()                       // random pause min-max ms
HumanActions.postTestPause()              // longer pause after test ends
```

### `TestListener.java`
TestNG calls this at key moments. Connects test results to the Extent report.
```
onTestStart   → creates entry in HTML report
onTestSuccess → marks green
onTestFailure → marks red, captures screenshot, attaches to report
onFinish      → writes HTML file to disk
```
Registered in suite XML:

```xml

<listener class-name="com.automation.sites.listeners.TestListener"/>
```

### `ExtentManager.java`
Singleton that creates one shared HTML report per test run.
Saves to `target/extent-reports/<site>-report.html`.

### `ScreenshotUtil.java`
Takes a PNG screenshot on test failure.
Saves to `target/screenshots/TestName_timestamp.png`.
Called automatically by `TestListener` — never call manually.

---

## Page Objects — Pattern Explained

Every webpage has its own Java class. Locators and actions live in the page
class. Tests only call page methods — no locators ever appear in test files.

```java
// Page Object — knows HOW to interact with the page
public class TextBoxPage {
    private final By userName = By.id("userName");   // locator

    public void fillForm(String name) {              // action
        HumanActions.type(driver, userName, name);
    }

    public String getOutputName() {                  // getter
        return driver.findElement(outputName).getText();
    }
}

// Test — describes WHAT to verify
public class TextBoxTest extends BaseTest {
    @Test
    public void fillTextBoxForm() {
        TextBoxPage page = new TextBoxPage(getDriver());
        page.fillForm("Amit");                        // no locators here
        Assert.assertTrue(page.getOutputName().contains("Amit"));
    }
}
```

### Helpers in Page Objects
Private methods that do repeated work so public methods stay clean:
```java
// Helper — private, called only inside this class
private void scrollAndClick(By locator) {
    js.executeScript("arguments[0].scrollIntoView({block:'center'});", el);
    js.executeScript("arguments[0].click();", el);
}

// Public methods use helper — no duplication
public void selectSports()  { scrollAndClick(sportsLabel);  }
public void selectReading() { scrollAndClick(readingLabel); }
```

### Helpers in Test Classes
Private methods that group related steps — makes tests shorter and readable:
```java
private PracticeFormPage openForm() {
    PracticeFormPage page = new PracticeFormPage(getDriver());
    page.navigateToPracticeForm();
    return page;
}

private void fillPersonalDetails(PracticeFormPage page) {
    page.enterFirstName("Amit");
    page.enterLastName("Thakor");
    page.selectGender("male");
    page.enterMobile("9876543210");
}

@Test
public void verifyFormSubmission() {
    PracticeFormPage page = openForm();   // helper
    fillPersonalDetails(page);            // helper
    page.submitForm();
}
```

---

## Test Coverage — demoqa.com

### Elements Section
| Page | Description | Groups |
|---|---|---|
| Text Box | Fill and submit form, verify output | smoke, regression |
| Check Box | Expand tree, select Desktop checkbox | regression |
| Radio Button | Select Yes option, verify result | regression |
| Web Tables | Full CRUD — add, search, edit, delete | regression |
| Buttons | Double click, right click, dynamic click | regression |
| Links | Home link opens tab, API links return correct status codes | smoke, regression |
| Broken Links - Images | Valid image loads, broken image fails, link navigation | smoke, regression |
| Upload and Download | Upload file, download file to target folder | smoke, regression |
| Dynamic Properties | Enable after delay, color change, appear after delay | smoke, regression |

### Forms Section
| Page | Description | Groups |
|---|---|---|
| Practice Form | Full form with all fields, mandatory fields only | smoke, regression |

### Alerts, Frame and Windows Section
| Page | Description | Groups |
|---|---|---|
| Browser Windows | New tab, new window, message window | smoke, regression |
| Alerts | Simple alert, timer alert, confirm accept/dismiss, prompt | smoke, regression |
| Frames | Read text from frame 1 and frame 2 | smoke, regression |
| Nested Frames | Read parent frame text, child frame text | smoke, regression |
| Modal Dialogs | Small modal title/body, large modal title/body | smoke, regression |

### Widgets Section
| Page | Description | Groups |
|---|---|---|
| Accordian | Section 1 default open, open section 2, open section 3 | smoke, regression |
| Auto Complete | Multi color select, single color select | smoke, regression |
| Date Picker | Select specific date from calendar | smoke, regression |
| Slider | Set value to 50, set value to 75 | smoke, regression |
| Progress Bar | Starts at 0, reaches 100, resets to 0 | smoke, regression |
| Tabs | What tab content, origin tab, use tab | smoke, regression |
| Tool Tips | Button tooltip on hover, text field tooltip on hover | smoke, regression |
| Menu | Main item visible, sub item on hover, nested sub sub item | smoke, regression |
| Select Menu | Old style select, standard multi select | smoke, regression |

---

## Running Tests Locally

### Prerequisites
- Java 17 — `java -version`
- Maven 3.9+ — `mvn -version`
- Chrome/Firefox/Edge browser installed

### Commands

```bash
# Smoke suite — quick sanity check
mvn test -Dsite=demoqa -DsuiteXmlFile=testng-suites/demoqa-smoke.xml

# Full regression suite
mvn test -Dsite=demoqa -DsuiteXmlFile=testng-suites/demoqa-regression.xml

# Single test class only
mvn test -Dsite=demoqa -DsuiteXmlFile=testng-suites/demoqa-regression.xml -Dtest=ButtonsTest

# Different browser
mvn test -Dsite=demoqa -DsuiteXmlFile=testng-suites/demoqa-regression.xml -Dbrowser=firefox

# Headless — no browser window
mvn test -Dsite=demoqa -DsuiteXmlFile=testng-suites/demoqa-regression.xml -Dheadless=true

# Slow mode — watch every action clearly
mvn test -Dhuman.pause.min=2000 -Dhuman.pause.max=3000 -Dtest=ButtonsTest

# Fast mode — disable all pauses
mvn test -Dhuman.pause.enabled=false -DsuiteXmlFile=testng-suites/demoqa-smoke.xml
```

---

## Configuration — global.properties

```properties
# ── Browser ────────────────────────────────────────────────────────
browser=chrome          # chrome | firefox | edge
headless=false          # true = no visible window (use true on CI/CD)

# ── Timeouts ───────────────────────────────────────────────────────
timeout=10              # seconds to wait for elements before failing

# ── Human Pause ────────────────────────────────────────────────────
human.pause.enabled=true       # false = skip all pauses for fast runs
human.pause.min=400            # min ms before each click/type action
human.pause.max=1200           # max ms before each click/type action
human.pause.postTest.min=500   # min ms after each test finishes
human.pause.postTest.max=1500  # max ms after each test finishes
human.pause.typing.min=40      # min ms between keystrokes when typing
human.pause.typing.max=120     # max ms between keystrokes when typing
```

Any key can be overridden at runtime:
```bash
mvn test -Dbrowser=edge -Dheadless=true -Dhuman.pause.enabled=false
```

---

## Test Reports

```
target/
├── extent-reports/
│   └── demoqa-report.html     # Custom HTML report — open in Chrome
├── screenshots/
│   └── TestName_20260704.png  # Auto-captured on failure
└── surefire-reports/
    └── *.xml                  # Raw XML consumed by Jenkins/GitLab
```

Open `target/extent-reports/demoqa-report.html` in Chrome to see:
- Pass/fail per test with timestamps and duration
- Failure screenshots embedded inline
- System info: browser, site, headless mode
- Summary charts showing overall pass/fail ratio

---

## Smoke vs Regression

### Smoke — run first, fast
Quick check that critical paths work. Run after every deployment.
```bash
mvn test -DsuiteXmlFile=testng-suites/demoqa-smoke.xml
```

### Regression — run for full coverage
Complete suite. Run nightly or before submitting a report.
```bash
mvn test -DsuiteXmlFile=testng-suites/demoqa-regression.xml
```

### How groups are assigned in code
```java
@Test(groups = {"smoke"})               // smoke only
@Test(groups = {"regression"})          // regression only
@Test(groups = {"smoke", "regression"}) // both suites
```

---

## Jenkins CI/CD Setup

### One-time setup
1. `Manage Jenkins → Tools` → Add JDK named exactly `JDK17`
2. `Manage Jenkins → Tools` → Add Maven named exactly `Maven3`
3. Install **HTML Publisher** plugin
4. Create Pipeline job → SCM: Git → Script Path: `Jenkinsfile`

### Build parameters
| Parameter | Options | Default | Purpose |
|---|---|---|---|
| `SUITE_TYPE` | regression / smoke | regression | Which suite type to run |
| `SITE` | ALL / site name | ALL | Run all sites or one specific |
| `BROWSER` | chrome / firefox / edge | chrome | Browser to use |
| `HEADLESS` | true / false | true | Show browser or not |

### After build — where to look
```
Job → Build #N
├── Console Output      → full Maven logs, errors, test output
├── Test Results        → pass/fail count, failed test names
├── Extent Test Report  → custom HTML report tab
└── Artifacts           → download screenshots and HTML report
```

### Fix report styling in Jenkins
Jenkins blocks external CSS by default — report appears unstyled.

Quick fix (resets on restart) — run in Script Console:
```groovy
System.setProperty("hudson.model.DirectoryBrowserSupport.CSP", "")
```

Permanent fix — systemd override:
```bash
sudo systemctl edit jenkins
# Add these lines:
[Service]
Environment="JAVA_OPTS=-Dhudson.model.DirectoryBrowserSupport.CSP="
# Save, then:
sudo systemctl daemon-reload
sudo systemctl restart jenkins
```

---

## GitLab CI/CD Pipeline

### Pipeline stages
```
build  → mvn compile — catches syntax errors before wasting time on tests
test   → mvn test with all -D flags — installs Chrome if missing on runner
report → publishes JUnit XML, archives HTML report and screenshots
```

### Variables you can override per run
```
SITE        = demoqa     (or ALL)
SUITE_TYPE  = regression (or smoke)
BROWSER     = chrome     (or firefox, edge)
HEADLESS    = true       (always true on CI)
```

### View report after pipeline
```
GitLab Job → Browse Artifacts → target/extent-reports/demoqa-report.html
```
Download and open in Chrome — full styling works when opened locally.

---

## Adding a New Site — 4 Steps

Zero changes to core framework files. Jenkins and GitLab auto-discover the
new suite files on next run.

### Step 1 — Config
```
Copy:  src/test/resources/config/_TEMPLATE.properties.example
To:    src/test/resources/config/mysite.properties
Set:   url=https://mysite.com
```

### Step 2 — Page Objects
```
Create: src/test/java/com/automation/sites/mysite/pages/MyPage.java
```

### Step 3 — Test Classes
```
Create: src/test/java/com/automation/sites/mysite/tests/MyTest.java
```
```java
public class MyTest extends BaseTest {
    @Test(priority = 1, groups = {"regression"}, description = "My test")
    public void verifyMyFeature() {
        MyPage page = new MyPage(getDriver());
        page.navigate();
        Assert.assertTrue(page.isLoaded());
    }
}
```

### Step 4 — Suite Files
```
Copy:   testng-suites/demoqa-regression.xml → testng-suites/mysite-regression.xml
Copy:   testng-suites/demoqa-smoke.xml      → testng-suites/mysite-smoke.xml
Change: <package name="com.automation.sites.mysite.tests"/>
```

### Run
```bash
mvn test -Dsite=mysite -DsuiteXmlFile=testng-suites/mysite-regression.xml
```

---

## Key Selenium Concepts Used

### Locator types
```java
By.id("userName")                          // fastest, most reliable
By.xpath("//h5[text()='Elements']")        // flexible, finds by text
By.cssSelector(".modal-body")              // CSS class/attribute
By.className("text-success")              // single class only
By.linkText("Click Here")                 // exact link text
By.xpath("//a[normalize-space()='Menu']") // trims whitespace before matching
```

### Wait strategies
```java
// Wait for element to be visible (exists AND displayed)
wait.until(ExpectedConditions.visibilityOfElementLocated(locator));

// Wait for element to be clickable (visible AND enabled)
wait.until(ExpectedConditions.elementToBeClickable(locator));

// Wait for element to disappear
wait.until(ExpectedConditions.invisibilityOfElementLocated(locator));

// Wait for custom condition using lambda
wait.until(d -> d.findElement(locator).getAttribute("aria-valuenow").equals("100"));

// Wait for number of windows/tabs
wait.until(ExpectedConditions.numberOfWindowsToBe(2));

// Wait for JS alert to appear
wait.until(ExpectedConditions.alertIsPresent());
```

### JavaScript execution
```java
JavascriptExecutor js = (JavascriptExecutor) driver;

js.executeScript("arguments[0].click();", element);                        // JS click — bypasses ad banner
js.executeScript("arguments[0].scrollIntoView({block:'center'});", el);   // scroll to center
js.executeScript("window.scrollTo(0, 0)");                                 // scroll to top
js.executeScript("arguments[0].value=arguments[1];", el, 50);             // set value directly
js.executeScript("arguments[0].dispatchEvent(new Event('input'));", el);   // fire React event
```

### Actions class — advanced mouse/keyboard
```java
// Hover
new Actions(driver).moveToElement(element).perform();

// Hover with pause (required for CSS menus)
new Actions(driver).moveToElement(element).pause(Duration.ofMillis(1000)).perform();

// Double click
new Actions(driver).doubleClick(element).perform();

// Right click
new Actions(driver).contextClick(element).perform();
```

### Alert handling
```java
Alert alert = wait.until(ExpectedConditions.alertIsPresent());
String message = alert.getText();   // read alert text
alert.accept();                     // click OK
alert.dismiss();                    // click Cancel
alert.sendKeys("Amit");             // type into prompt input
```

### Frame switching
```java
driver.switchTo().frame(frameElement);  // enter iframe — now find elements inside
driver.switchTo().defaultContent();     // back to main page from any depth

// Nested frames — must go through parent to reach child
driver.switchTo().frame(parentFrame);
driver.switchTo().frame(childFrame);
driver.switchTo().defaultContent();     // one call gets all the way back
```

### Window/tab switching
```java
Set<String> before = driver.getWindowHandles();   // handles before click
// click something that opens new window
for (String handle : driver.getWindowHandles()) {
    if (!before.contains(handle)) {
        driver.switchTo().window(handle);          // switch to new window
        break;
    }
}
// do work in new window
driver.close();                                    // close new window
String remaining = driver.getWindowHandles().iterator().next();
driver.switchTo().window(remaining);               // switch back
```

### Select class — plain HTML dropdowns only
```java
Select dropdown = new Select(driver.findElement(By.id("oldSelectMenu")));
dropdown.selectByVisibleText("Blue");         // by visible text
dropdown.selectByValue("blue");               // by value attribute
dropdown.selectByIndex(2);                    // by position (0-based)
dropdown.getFirstSelectedOption().getText();  // read selected value
```

### ARIA attributes — used by progress bars, sliders
```java
element.getAttribute("aria-valuenow")   // current value
element.getAttribute("aria-valuemin")   // minimum value
element.getAttribute("aria-valuemax")   // maximum value
element.getAttribute("aria-expanded")   // true/false — is expanded
element.getAttribute("aria-selected")   // true/false — is selected
```

---

## Common Errors and Fixes

| Error | Cause | Fix |
|---|---|---|
| `ElementClickInterceptedException` | Ad banner covering element | Use `js.executeScript("arguments[0].click()", el)` |
| `TimeoutException` | Element not found in time | Check locator in DevTools, verify page loaded |
| `NoSuchWindowException` | Window already closed | Wrap `driver.close()` in try-catch |
| `StaleElementReferenceException` | Page reloaded, element gone | Re-find element after page action |
| `InvalidSelectorException: Compound class names` | Space in `By.className()` | Use `By.cssSelector(".class1.class2")` instead |
| `Keys to send should be not null` | Variable is null | Check `@BeforeMethod` runs before `@Test` |
| `NoSuchElementException` | Wrong locator or inside iframe | Check locator in DevTools, switch frame first |
| `NoSuchWindowException on close()` | Message window closed itself | Wrap in try-catch, window already gone |
| `ElementNotInteractableException` | Element hidden or disabled | Use `presenceOfElementLocated`, then JS click |

---

## Glossary

| Term | Meaning |
|---|---|
| Page Object Model | Design pattern — one Java class per webpage |
| Helper method | Private method inside a class that does repeated work |
| Locator | How Selenium finds an element — `By.id`, `By.xpath`, etc. |
| WebDriverWait | Tells Selenium to keep retrying until condition is true or timeout |
| ThreadLocal | Variable where each thread gets its own independent copy |
| TestNG groups | Tags on tests — `smoke` or `regression` — for selective running |
| Extent Report | Custom HTML test report with charts and embedded screenshots |
| HumanActions | Framework utility adding random delays to simulate human typing/clicking |
| Actions class | Selenium class for hover, drag-drop, double-click, keyboard shortcuts |
| Alert | JavaScript browser popup — not part of webpage HTML |
| Frame / iframe | HTML element that embeds one webpage inside another |
| Window handle | Unique ID assigned to each open browser tab or window |
| ARIA attribute | Accessibility HTML attribute readable by automation tools |
| JS click | Clicking via JavaScript — bypasses overlay/banner interception |
| Singleton | Design pattern — only one instance of a class ever created |
| CSP | Content Security Policy — Jenkins setting that blocks external CSS in reports |
| Smoke test | Quick sanity check — is it working at all? |
| Regression test | Full suite — did anything break? |
| ThreadLocal | Each thread gets its own driver — needed for parallel test runs |
| dispatchEvent | JavaScript command to fire browser events that React/Vue listens to |
