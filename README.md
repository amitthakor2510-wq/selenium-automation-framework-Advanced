# 🤖 Selenium Automation Framework — Advanced Edition

> **A production-grade, multi-site Java test automation framework** built on Selenium 4 + TestNG + Maven, with dual reporting (Allure + Extent), data-driven testing across 4 file formats, human-like interaction simulation, and a full Jenkins CI/CD pipeline.

---

## 📋 Table of Contents

- [🧠 What Is This? (From Scratch)](#-what-is-this-from-scratch)
- [🗂️ Project Structure](#️-project-structure)
- [⚙️ Tech Stack & Dependencies](#️-tech-stack--dependencies)
- [🏗️ Architecture — How Everything Connects](#️-architecture--how-everything-connects)
- [🔑 Core Layer — Deep Dive](#-core-layer--deep-dive)
  - [DriverFactory.java](#1-driverfactoryjava---browser-creation-engine)
  - [ConfigReader.java](#2-configreaderjava---3-layer-config-system)
  - [BasePage.java](#3-basepagejava---the-parent-of-all-pages)
  - [BaseTest.java](#4-basetestjava---the-parent-of-all-tests)
  - [HumanActions.java](#5-humanactionsjava---human-like-interaction-engine)
  - [DataProvider.java](#6-dataproviderjava---multi-format-data-engine)
  - [DataRow.java](#7-datarowjava---one-row-of-test-data)
  - [DataProviderFactory.java](#8-dataproviderfactoryjava---convenience-wrapper)
  - [ScreenshotUtil.java](#9-screenshotutiljava---screenshot-capture)
  - [ExtentManager.java](#10-extentmanagerjava---html-report-generator)
- [🎧 Listeners — The Hidden Automation Engine](#-listeners--the-hidden-automation-engine)
  - [TestListener.java](#testlistenerjava)
  - [RetryAnalyzer.java](#retryanalyzerjava)
  - [RetryListener.java](#retrylistenerjava)
- [📄 Page Object Model — Design Pattern Explained](#-page-object-model--design-pattern-explained)
- [🧪 Test Classes — How To Write Tests](#-test-classes--how-to-write-tests)
- [📊 Data-Driven Testing — 4 Formats](#-data-driven-testing--4-formats)
- [🔧 Configuration Files Explained](#-configuration-files-explained)
- [📑 TestNG Suite XMLs Explained](#-testng-suite-xmls-explained)
- [🚀 How To Run Tests](#-how-to-run-tests)
- [🔄 Jenkins CI/CD Pipeline — Full Flow](#-jenkinscicd-pipeline--full-flow)
- [📈 Reports — Allure & Extent](#-reports--allure--extent)
- [🌐 Multi-Site Architecture](#-multi-site-architecture)
- [🧩 Test Coverage — All 25 DemoQA Tests](#-test-coverage--all-25-demoqa-tests)

---

## 🧠 What Is This? (From Scratch)

### 🔰 What is Selenium?
Selenium is a **browser automation library**. It lets your Java code control a real browser — open URLs, click buttons, fill forms, read text — exactly like a human would.

```
Your Java Code  →  Selenium WebDriver  →  ChromeDriver  →  Chrome Browser  →  Website
```

### 🔰 What is TestNG?
TestNG is a **test runner** for Java. It finds your `@Test` methods, runs them in order, tracks pass/fail, and generates reports.

### 🔰 What is Maven?
Maven is a **build tool**. It downloads your dependencies (Selenium, TestNG…) from the internet, compiles your code, and runs your tests — all with one command.

### 🔰 What is the Page Object Model (POM)?
POM is a **design pattern**: every web page gets its own Java class. The class knows how to interact with that page. Tests call the page class — they never interact with the browser directly. This keeps code clean and reusable.

```
                         ┌─────────────────┐
Your Test  ───────────▶  │   LoginPage.java │  ───────▶  Browser
                         │  (Page Object)   │
                         └─────────────────┘
```

---

## 🗂️ Project Structure

```
selenium-automation-framework Advanced/
│
├── 📄 pom.xml                          ← Maven config: dependencies + build settings
├── 📄 Jenkinsfile                      ← CI/CD pipeline definition
├── 📄 selenium-framework.iml           ← IntelliJ IDEA project file
│
├── 📁 testng-suites/                   ← Test suite XML files (what to run)
│   ├── demoqa-regression.xml
│   ├── demoqa-smoke.xml
│   └── saucedemo-regression.xml
│
├── 📁 src/
│   ├── 📁 main/java/com/automation/    ← REUSABLE CORE (not tests)
│   │   ├── core/
│   │   │   ├── base/
│   │   │   │   ├── BasePage.java       ← Parent class for all Page Objects
│   │   │   │   └── DriverProvider.java ← Interface to get WebDriver
│   │   │   ├── config/
│   │   │   │   └── ConfigReader.java   ← Reads .properties config files
│   │   │   ├── data/
│   │   │   │   ├── DataProvider.java   ← Reads Excel/CSV/JSON/ZIP data
│   │   │   │   ├── DataProviderFactory.java ← TestNG-ready data wrapper
│   │   │   │   └── DataRow.java        ← Represents one row of test data
│   │   │   ├── driver/
│   │   │   │   └── DriverFactory.java  ← Creates Chrome/Firefox/Edge/Brave
│   │   │   ├── report/
│   │   │   │   └── ExtentManager.java  ← Creates the HTML report
│   │   │   └── utils/
│   │   │       ├── HumanActions.java   ← Human-like click/type with random delay
│   │   │       └── ScreenshotUtil.java ← Captures screenshots on failure
│   │   └── sites/
│   │       ├── demoqa/pages/           ← 22 Page Objects for demoqa.com
│   │       └── saucedemo/pages/        ← Page Objects for saucedemo.com
│   │           └── LoginPage.java
│   │
│   └── 📁 test/java/com/automation/   ← ACTUAL TESTS
│       └── sites/
│           ├── core/
│           │   └── BaseTest.java       ← Parent class for all Test classes
│           ├── listeners/
│           │   ├── TestListener.java   ← Screenshot + report on pass/fail/skip
│           │   ├── RetryAnalyzer.java  ← Retries failed tests N times
│           │   └── RetryListener.java  ← Auto-attaches RetryAnalyzer to all tests
│           ├── demoqa/tests/           ← 25 DemoQA test classes
│           └── saucedemo/tests/
│               ├── LoginTest.java
│               └── LoginDataDrivenTest.java
│
└── 📁 src/test/resources/
    ├── config/
    │   ├── global.properties           ← Default settings for all sites
    │   ├── demoqa.properties           ← demoqa.com URL + overrides
    │   └── saucedemo.properties        ← saucedemo.com URL + overrides
    ├── testdata/
    │   ├── login.xlsx                  ← Test data in Excel format
    │   ├── login.csv                   ← Test data in CSV format
    │   ├── login.json                  ← Test data in JSON format
    │   └── login.zip                   ← Test data bundled in ZIP
    └── allure.properties               ← Allure output folder config
```

---

## ⚙️ Tech Stack & Dependencies

| 🏷️ Library | 📌 Version | 🎯 Purpose |
|---|---|---|
| `selenium-java` | 4.21.0 | Browser automation — the core engine |
| `testng` | 7.9.0 | Test runner — finds/runs/reports tests |
| `webdrivermanager` | 6.1.0 | Auto-downloads correct browser drivers |
| `extentreports` | 5.1.2 | Beautiful HTML test reports |
| `allure-testng` | 2.27.0 | Interactive Allure reports with steps |
| `poi-ooxml` | 5.2.5 | Reads Excel `.xlsx` / `.xls` files |
| `opencsv` | 5.9 | Reads `.csv` test data files |
| `jackson-databind` | 2.17.1 | Reads `.json` test data files |
| `java-client` (Appium) | 9.2.3 | Mobile testing ready (future use) |
| `slf4j-simple` | 2.0.13 | Logging for WebDriverManager |
| `aspectjweaver` | 1.9.25 | Makes Allure `@Step` annotations work |
| `maven-surefire` | 3.2.5 | Runs tests via Maven |

---

## 🏗️ Architecture — How Everything Connects

```
┌──────────────────────────────────────────────────────────────────────┐
│                         TEST EXECUTION FLOW                          │
├──────────────────────────────────────────────────────────────────────┤
│                                                                      │
│   mvn test -Dsite=demoqa -Dbrowser=chrome                           │
│       │                                                              │
│       ▼                                                              │
│   [Maven Surefire Plugin]                                            │
│       │  reads testng-suites/demoqa-regression.xml                  │
│       │                                                              │
│       ▼                                                              │
│   [TestNG Engine]                                                    │
│       │  finds all @Test methods in demoqa.tests package            │
│       │  attaches TestListener + RetryListener                       │
│       │                                                              │
│       ▼  for each @Test method:                                      │
│   [BaseTest.setUp()]                                                 │
│       │  ConfigReader reads global.properties + demoqa.properties   │
│       │  DriverFactory creates ChromeDriver                         │
│       │  driver.get("https://demoqa.com")                           │
│       │                                                              │
│       ▼                                                              │
│   [Your Test Method e.g. AlertsTest.verifySimpleAlert()]            │
│       │  creates AlertsPage(driver)                                  │
│       │  calls page.navigateToAlerts()                              │
│       │  calls page.clickAlertAndGetText()                          │
│       │       └─ HumanActions.click() ← random pause before click  │
│       │  Assert.assertEquals(text, "You clicked a button")          │
│       │                                                              │
│       ▼  after each test:                                            │
│   [BaseTest.tearDown()]                                              │
│       │  driver.quit()                                               │
│       │                                                              │
│       ▼  simultaneously (via TestListener):                          │
│   [TestListener]                                                     │
│       │  on PASS → log to Extent + attach screenshot to Allure      │
│       │  on FAIL → screenshot → Extent (HTML embed) + Allure attach │
│       │  on SKIP → log skip reason                                   │
│       │  RetryAnalyzer retries up to N times before final fail      │
│       │                                                              │
│       ▼  after all tests:                                            │
│   [Reports Generated]                                                │
│       ├─ target/extent-reports/index.html  (Extent Report)          │
│       ├─ target/allure-results/            (raw JSON for Allure)    │
│       └─ target/screenshots/              (saved PNGs)              │
│                                                                      │
└──────────────────────────────────────────────────────────────────────┘
```

---

## 🔑 Core Layer — Deep Dive

### 1. `DriverFactory.java` — Browser Creation Engine

📁 `src/main/java/com/automation/core/driver/DriverFactory.java`

**What it does:** Creates a `WebDriver` instance for any supported browser. One class, one job.

**Why it exists:** Without this, every test would need to manually set up the driver with all Chrome options. That's repetitive and error-prone. DriverFactory centralizes this.

```java
// 🔑 KEY CONCEPT: final class = cannot be extended (utility class pattern)
public final class DriverFactory {

    // 🔑 private constructor = cannot be instantiated. Only static methods.
    private DriverFactory() { }

    // 🔑 Entry point — reads config, then delegates to the right browser method
    public static WebDriver createDriver() {
        String browser = ConfigReader.get("browser", "chrome").toLowerCase();
        boolean headless = ConfigReader.getBoolean("headless", false);

        switch (browser) {
            case "chrome":  return createChromeDriver(headless);
            case "firefox": return createFirefoxDriver(headless);
            case "edge":    return createEdgeDriver(headless);
            case "brave":   return createBraveDriver(headless);
            default: throw new RuntimeException("Browser not supported: " + browser);
        }
    }
}
```

**🔑 Chrome Options explained:**
```java
options.addArguments("--disable-notifications");   // No popup permission dialogs
options.addArguments("--no-sandbox");              // Required for Docker/Jenkins Linux
options.addArguments("--disable-dev-shm-usage");  // Prevents crashes in containers

// 🔑 Headless mode: runs browser without GUI (no screen needed — perfect for CI)
if (headless) {
    options.addArguments("--headless=new");        // Chrome's new headless engine
    options.addArguments("--window-size=1920,1080"); // Simulates full screen
}
```

**🔑 Download path configuration:**
```java
Map<String, Object> prefs = new HashMap<>();
prefs.put("download.default_directory", downloadPath); // Where files download to
prefs.put("download.prompt_for_download", false);      // No "Save As" dialog popup
```

**🔑 Brave browser support:**
Brave is Chromium-based, so it uses ChromeDriver — just with a different binary path:
```java
private static WebDriver createBraveDriver(boolean headless) {
    WebDriverManager.chromedriver().setup();         // Same driver as Chrome!
    ChromeOptions options = buildChromeOptions(headless);
    options.setBinary(braveBinary);                  // Just point to Brave binary
    return new ChromeDriver(options);
}
```

---

### 2. `ConfigReader.java` — 3-Layer Config System

📁 `src/main/java/com/automation/core/config/ConfigReader.java`

**What it does:** Reads configuration from properties files + system properties, with a clear priority order.

**Why it exists:** Hardcoding URLs and settings in test code is terrible practice. ConfigReader centralizes all config and lets Jenkins override any value at runtime.

**🔑 The 3-layer priority (last wins):**
```
Layer 1: global.properties        → default values for everything
Layer 2: {site}.properties         → site-specific overrides (URL etc.)
Layer 3: -Dkey=value on CLI/Maven  → runtime overrides (Jenkins can set anything)
```

**Example:** If `global.properties` says `browser=chrome` and Jenkins runs with `-Dbrowser=firefox`, Firefox wins.

```java
// 🔑 Singleton pattern with lazy init
private static volatile boolean initialized = false;

public static synchronized void init() {
    if (initialized) return;  // Only load files ONCE, no matter how many tests run

    activeSite = System.getProperty("site", "demoqa"); // -Dsite=demoqa (or default)
    loadFromClasspath("config/global.properties", true);          // REQUIRED
    loadFromClasspath("config/" + activeSite + ".properties", false); // OPTIONAL
    initialized = true;
}

// 🔑 Resolution order for get("browser"):
public static String get(String key) {
    String sys = System.getProperty(key); // Check -D flags first
    if (sys != null && !sys.isEmpty()) return sys; // CLI wins
    String val = properties.getProperty(key); // Then .properties file
    if (val == null) throw new RuntimeException("Missing config key: " + key);
    return val;
}
```

**📌 Why `synchronized`?** Multiple threads (parallel tests) might try to init at the same time. `synchronized` ensures only one thread runs `init()` at once.

---

### 3. `BasePage.java` — The Parent of All Pages

📁 `src/main/java/com/automation/core/base/BasePage.java`

**What it does:** Every Page Object class extends this. It provides the `driver`, `wait`, and `js` objects, plus common helper methods that all pages need.

**Why it exists:** Every page needs the same tools — a driver, a wait mechanism, JavaScript access. Rather than repeating this in 25 page classes, one parent class provides it all.

```java
// 🔑 abstract = cannot be instantiated directly. You MUST extend it.
public abstract class BasePage {

    protected final WebDriver driver;    // The browser
    protected final WebDriverWait wait;  // Smart waiter (waits up to N seconds)
    protected final JavascriptExecutor js; // Run JavaScript in the browser

    // 🔑 Constructor — called by every child page's constructor via super(driver)
    protected BasePage(WebDriver driver) {
        this.driver = driver;
        // 🔑 Timeout from config — not hardcoded. Change in global.properties!
        this.wait = new WebDriverWait(driver, Duration.ofSeconds(ConfigReader.getInt("timeout", 10)));
        this.js   = (JavascriptExecutor) driver;
    }

    // 🔑 Navigate relative to site base URL (avoids hardcoding full URLs in tests)
    protected void navigateTo(String path) {
        String baseUrl = ConfigReader.get("url"); // https://demoqa.com
        driver.get(baseUrl + path);               // → https://demoqa.com/alerts
    }

    // 🔑 JavaScript click — used when normal .click() fails (hidden elements, overlays)
    protected void scrollAndJsClick(By locator) {
        WebElement el = wait.until(ExpectedConditions.presenceOfElementLocated(locator));
        js.executeScript("arguments[0].scrollIntoView({block:'center'});", el); // Scroll to it
        js.executeScript("arguments[0].click();", el);  // Click via JS, bypassing overlays
    }

    // 🔑 Waits up to 'timeout' seconds for element to be visible
    protected WebElement waitVisible(By locator) {
        return wait.until(ExpectedConditions.visibilityOfElementLocated(locator));
    }

    // 🔑 Safe isDisplayed — returns false instead of throwing exception
    protected boolean isDisplayed(By locator) {
        try {
            return !driver.findElements(locator).isEmpty()
                && driver.findElement(locator).isDisplayed();
        } catch (Exception e) {
            return false; // Element not found = not displayed
        }
    }
}
```

---

### 4. `BaseTest.java` — The Parent of All Tests

📁 `src/test/java/com/automation/sites/core/BaseTest.java`

**What it does:** Every test class extends this. It handles browser setup before each test and browser teardown after each test.

**Why it exists:** Without this, every test class would need `@BeforeMethod` and `@AfterMethod` logic to create/destroy the browser. BaseTest does this once for everyone.

```java
public class BaseTest implements DriverProvider {

    // 🔑 ThreadLocal = each thread gets its OWN WebDriver
    // This is CRUCIAL for parallel test execution — without it,
    // Thread A's driver would interfere with Thread B's tests
    protected static final ThreadLocal<WebDriver> driver = new ThreadLocal<>();

    // 🔑 @BeforeMethod: TestNG calls this BEFORE every @Test method
    @BeforeMethod(alwaysRun = true)
    public void setUp() {
        WebDriver webDriver = DriverFactory.createDriver(); // Create fresh browser
        driver.set(webDriver);                             // Store per-thread
        getDriver().get(ConfigReader.get("url"));          // Open site URL
    }

    // 🔑 @AfterMethod: TestNG calls this AFTER every @Test method
    @AfterMethod(alwaysRun = true)
    public void tearDown() {
        if (getDriver() != null) {
            try {
                getDriver().quit();    // Close browser + kill process
            } finally {
                driver.remove();       // 🔑 CRITICAL: free ThreadLocal memory
            }
        }
    }

    // 🔑 alwaysRun=true means setUp/tearDown run even if test is skipped/fails
}
```

---

### 5. `HumanActions.java` — Human-Like Interaction Engine

📁 `src/main/java/com/automation/core/utils/HumanActions.java`

**What it does:** Wraps Selenium clicks and typing with randomized delays to simulate human behavior. Bypasses bot-detection systems.

**Why it exists:** Selenium by default interacts with pages at machine speed — instant clicks, instant typing. Some websites detect this as bot traffic. HumanActions slows down interactions with random delays (just like a real person).

```java
// 🔑 All timing values come from config — easily adjustable!
public static void pause() {
    pauseBetween(
        ConfigReader.getInt("human.pause.min", 400),  // minimum 400ms
        ConfigReader.getInt("human.pause.max", 1200)  // maximum 1200ms
    );
    // Result: a RANDOM pause between 400ms and 1200ms before each action
}

// 🔑 Typing one character at a time with random delay between keystrokes
public static void typeHumanLike(WebElement element, String text) {
    for (char c : text.toCharArray()) {
        element.sendKeys(String.valueOf(c)); // Type one char
        int delay = ThreadLocalRandom.current().nextInt(40, 120); // 40-120ms per key
        Thread.sleep(delay);                 // Random keystroke delay
    }
    // Looks like: h...e...l...l...o (with random gaps = human typing!)
}

// 🔑 Master switch — turn off ALL pauses for fast CI runs
// global.properties: human.pause.enabled=false → skip all delays instantly
private static void pauseBetween(int min, int max) {
    if (!ConfigReader.getBoolean("human.pause.enabled", true)) return; // CI mode
    int delay = ThreadLocalRandom.current().nextInt(min, max + 1);
    Thread.sleep(delay);
}
```

**Usage in Page Objects:**
```java
// Instead of:  driver.findElement(By.id("user-name")).sendKeys(username);
// Use:
HumanActions.type(driver, usernameField, username);   // With human typing delay
HumanActions.click(driver, loginButton);               // With random pre-click pause
```

---

### 6. `DataProvider.java` — Multi-Format Data Engine

📁 `src/main/java/com/automation/core/data/DataProvider.java`

**What it does:** Reads test data from **Excel (.xlsx/.xls)**, **CSV**, **JSON**, or **ZIP** files and returns a `List<DataRow>`. Auto-detects format by file extension.

**Why it exists:** Data-Driven Testing (DDT) means running the same test with many data sets (e.g., login with 10 different username/password combos). Instead of hardcoding data in tests, we read from files — easy to add new cases without touching test code.

```java
// 🔑 Auto-dispatch by file extension
public static List<DataRow> read(String filePath) {
    String name = file.getName().toLowerCase();

    if (name.endsWith(".xlsx") || name.endsWith(".xls")) return readExcel(file, null);
    else if (name.endsWith(".csv"))                       return readCsv(file);
    else if (name.endsWith(".json"))                      return readJson(file);
    else if (name.endsWith(".zip"))                       return readZip(file);
    // The SAME login data works from any format — tests don't care which
}
```

**🔑 Excel reading:**
```java
// First row = headers (username | password | expected | notes)
Row headerRow = sheet.getRow(0);
headers = [username, password, expected, notes]

// All other rows = data
for (int i = 1; i <= sheet.getLastRowNum(); i++) {
    Row row = sheet.getRow(i);
    if (isEmptyRow(row)) continue; // Skip blank rows automatically
    // Build map: {username=standard_user, password=secret_sauce, expected=success}
    rows.add(new DataRow(rowData, i));
}
```

**🔑 ZIP reading:** Reads ALL supported files inside the ZIP, combines all rows into one list. Uses temporary files to extract and read each entry.

**🔑 File resolution order:**
```
1. Try as absolute path → /home/user/data/login.xlsx
2. Try relative to project root → src/test/resources/testdata/login.xlsx
3. Try on classpath → inside compiled JAR/test-classes
```

---

### 7. `DataRow.java` — One Row of Test Data

📁 `src/main/java/com/automation/core/data/DataRow.java`

**What it does:** Represents one row from any data source as a key-value map. Always case-insensitive column lookup.

```java
// 🔑 Stored normalized (lowercase keys) for case-insensitive access
public DataRow(Map<String, String> data, int rowIndex) {
    data.forEach((k, v) -> normalized.put(k.trim().toLowerCase(), v.trim()));
}

// 🔑 Safe get — returns empty string if column not found
row.get("username")   // → "standard_user"
row.get("USERNAME")   // → "standard_user" (same result, case-insensitive!)
row.get("missing")    // → "" (no exception)

// 🔑 Required get — throws a clear error if column is missing
row.getRequired("username") // Must exist or throws: "Required column 'username' missing in row 2"
```

---

### 8. `DataProviderFactory.java` — Convenience Wrapper

📁 `src/main/java/com/automation/core/data/DataProviderFactory.java`

**What it does:** Converts a data file directly to `Object[][]` — the format TestNG's `@DataProvider` needs.

```java
// 🔑 Without factory (verbose):
@DataProvider(name = "loginData")
public Object[][] getData() {
    List<DataRow> rows = DataProvider.read("src/test/resources/testdata/login.xlsx");
    return DataProvider.toTestNGFormat(rows);
}

// 🔑 With factory (clean one-liner):
@DataProvider(name = "loginData")
public Object[][] getData() {
    return DataProviderFactory.fromFile("src/test/resources/testdata/login.xlsx");
}
```

---

### 9. `ScreenshotUtil.java` — Screenshot Capture

📁 `src/main/java/com/automation/core/utils/ScreenshotUtil.java`

**What it does:** Captures browser screenshots in 3 formats for different uses.

```java
// 🔑 Format 1: Save to disk as .png file (for local viewing)
captureScreenshot(driver, "LoginTest_verifyLogin")
// → target/screenshots/LoginTest_verifyLogin_20240715_143022.png

// 🔑 Format 2: Return as Base64 string (for embedding directly in HTML reports)
// Base64 = image data encoded as text — no file paths needed, works everywhere
captureScreenshotAsBase64(driver)
// → "iVBORw0KGgoAAAANSUhEUgAA..." (very long string)

// 🔑 Format 3: Return as byte[] (for Allure to attach to its own report)
captureScreenshotAsBytes(driver)
// → byte[] { 0x89, 0x50, 0x4E... }
```

**Why 3 formats?** Extent Reports embeds Base64 directly in HTML. Allure accepts byte[] via `InputStream`. Local debugging wants a file. All handled!

---

### 10. `ExtentManager.java` — HTML Report Generator

📁 `src/main/java/com/automation/core/report/ExtentManager.java`

**What it does:** Creates and configures the Extent Reports HTML report. Singleton pattern ensures only ONE report is created per test run.

```java
// 🔑 Singleton — one ExtentReports object per JVM run
public static synchronized ExtentReports getInstance() {
    if (extent == null) { // Only create on FIRST call
        String reportPath = "target/extent-reports/index.html";
        ExtentSparkReporter spark = new ExtentSparkReporter(reportPath);
        spark.config().setTheme(Theme.STANDARD);
        spark.config().setDocumentTitle(site + " - Automation Report");

        extent = new ExtentReports();
        extent.attachReporter(spark);

        // 🔑 System info shows at top of report (very useful for debugging)
        extent.setSystemInfo("Site",     site);
        extent.setSystemInfo("Browser",  ConfigReader.get("browser", "chrome"));
        extent.setSystemInfo("Headless", ConfigReader.get("headless", "false"));
        extent.setSystemInfo("OS",       System.getProperty("os.name"));
    }
    return extent;
}
```

---

## 🎧 Listeners — The Hidden Automation Engine

Listeners are classes that TestNG **automatically calls** when test events happen (start, pass, fail, skip). You register them in the TestNG XML and they run without any test code needing to call them.

### `TestListener.java`

📁 `src/test/java/com/automation/sites/listeners/TestListener.java`

```java
public class TestListener implements ITestListener {
    // 🔑 ThreadLocal — each parallel test gets its own ExtentTest log
    private static final ThreadLocal<ExtentTest> test = new ThreadLocal<>();

    @Override
    public void onTestStart(ITestResult result) {
        // 🔑 Create a new entry in the report for this test
        ExtentTest extentTest = extent.createTest(result.getMethod().getMethodName());
        test.set(extentTest); // Store for this thread
    }

    @Override
    public void onTestSuccess(ITestResult result) {
        test.get().pass("Test Passed");        // Green ✅ in Extent report
        // Attach screenshot to Allure report (not Extent — no screenshot on success)
        byte[] bytes = ScreenshotUtil.captureScreenshotAsBytes(driver);
        Allure.addAttachment("Pass Screenshot", new ByteArrayInputStream(bytes));
        HumanActions.postTestPause(); // Human-like delay before browser closes
    }

    @Override
    public void onTestFailure(ITestResult result) {
        byte[] bytes = ScreenshotUtil.captureScreenshotAsBytes(driver);
        test.get().fail("Test Failed");
        test.get().fail(result.getThrowable()); // Log the error/stacktrace
        test.get().addScreenCaptureFromBase64String(
            "data:image/png;base64," + ScreenshotUtil.toBase64(bytes), // Embed in HTML!
            "Failure Screenshot"
        );
        Allure.addAttachment("Failure Screenshot", new ByteArrayInputStream(bytes));
    }

    @Override
    public void onFinish(ITestContext context) {
        extent.flush(); // 🔑 Write report to disk (MUST call this or file is incomplete)
    }
}
```

### `RetryAnalyzer.java`

**What it does:** Automatically retries a failed test up to N times before marking it as permanently failed.

```java
public class RetryAnalyzer implements IRetryAnalyzer {
    private int count = 0;

    @Override
    public boolean retry(ITestResult result) {
        int maxRetry = ConfigReader.getInt("retry.count", 2); // From config!

        if (count < maxRetry) {
            count++; // Increment attempt counter
            System.out.println("Retrying [" + result.getName() + "] attempt " + count);
            return true;  // 🔑 Return true = TestNG WILL retry this test
        }
        return false; // 🔑 Return false = no more retries, mark as FAILED
    }
}
```

**Config:** `retry.count=2` in `global.properties` → 2 retries → 3 total attempts.

### `RetryListener.java`

**What it does:** Automatically attaches `RetryAnalyzer` to **every** `@Test` method without you having to write `retryAnalyzer = RetryAnalyzer.class` on each one.

```java
// 🔑 IAnnotationTransformer = runs before tests start, can modify @Test annotations
public class RetryListener implements IAnnotationTransformer {

    @Override
    public void transform(ITestAnnotation annotation, Class testClass, Constructor c, Method m) {
        // Only set if no retryAnalyzer already explicitly declared
        if (annotation.getRetryAnalyzerClass() == null) {
            annotation.setRetryAnalyzer(RetryAnalyzer.class); // Auto-attach!
        }
    }
}
```

---

## 📄 Page Object Model — Design Pattern Explained

The Page Object Model (POM) is THE most important pattern in Selenium. Here's a complete example:

```java
// 🔑 Page Object: LoginPage.java
// This class KNOWS everything about the Login page
public class LoginPage extends BasePage {  // ← extends BasePage for driver/wait/js

    // 🔑 Locators — HOW to find elements on the page
    // Stored as fields, not hardcoded in methods — easy to update when UI changes
    private final By usernameField = By.id("user-name");   // Find by HTML id
    private final By passwordField = By.id("password");    // Find by HTML id
    private final By loginButton   = By.id("login-button");
    private final By errorMessage  = By.cssSelector("[data-test='error']"); // CSS selector

    public LoginPage(WebDriver driver) {
        super(driver); // 🔑 Pass driver to BasePage constructor
    }

    // 🔑 Page methods — WHAT you can do on this page
    public void navigateToLogin() {
        navigateTo("");                          // Goes to base URL (https://saucedemo.com)
        wait.until(ExpectedConditions.visibilityOfElementLocated(usernameField)); // Wait for load
    }

    public void login(String username, String password) {
        HumanActions.type(driver, usernameField, username); // Type with human delay
        HumanActions.type(driver, passwordField, password);
        HumanActions.click(driver, loginButton);            // Click with human delay
    }

    public boolean isErrorDisplayed() {
        try {
            return wait.until(ExpectedConditions.visibilityOfElementLocated(errorMessage)).isDisplayed();
        } catch (Exception e) {
            return false;
        }
    }
}
```

```java
// 🔑 Test Class: LoginTest.java
// This class USES the page object — it doesn't know about locators!
public class LoginTest extends BaseTest {  // ← extends BaseTest for setUp/tearDown

    @Test(
        priority = 1,                          // Run order
        groups = {"smoke", "regression"},      // Tags for filtering which tests to run
        description = "SauceDemo - Verify Successful Login"  // Shown in report
    )
    public void verifyLogin() {
        LoginPage page = new LoginPage(getDriver()); // Create page with current driver

        page.navigateToLogin();                      // Action (page handles the how)
        page.login("standard_user", "secret_sauce"); // Action

        // 🔑 Assert — verify the outcome
        Assert.assertTrue(
            getDriver().getCurrentUrl().contains("inventory"),
            "Login failed - not redirected to inventory page"  // Error message if fails
        );
    }
}
```

**🔑 Key principle:** Tests describe **WHAT** to do. Pages describe **HOW** to do it.

---

## 🧪 Test Classes — How To Write Tests

### Basic Test Template

```java
@Test(
    priority = 1,                           // Lower number = runs first
    groups = {"smoke", "regression"},       // Groups for suite XML filtering
    description = "What this test verifies" // Shows in report
)
public void yourTestName() {
    // 1. Create Page Object
    SomePage page = new SomePage(getDriver());

    // 2. Navigate
    page.navigateToSomePage();

    // 3. Interact
    page.doSomething();

    // 4. Assert
    Assert.assertTrue(page.isResultCorrect(), "Failure message");
    Assert.assertEquals(actualValue, expectedValue, "Failure message");
}
```

### Available Assert Methods

```java
Assert.assertTrue(condition, "message");           // Is it true?
Assert.assertFalse(condition, "message");          // Is it false?
Assert.assertEquals(actual, expected, "message");  // Are they equal?
Assert.assertNotNull(obj, "message");              // Is it not null?
Assert.assertContains(string, substring);          // Does it contain?
```

---

## 📊 Data-Driven Testing — 4 Formats

The same test can read data from any file format:

### Excel Format (login.xlsx)

| username | password | expected | notes |
|---|---|---|---|
| standard_user | secret_sauce | success | Valid login |
| locked_out_user | secret_sauce | fail | Locked account |
| invalid_user | wrong_pass | fail | Bad credentials |

### CSV Format (login.csv)

```
username,password,expected,notes
standard_user,secret_sauce,success,Valid login
locked_out_user,secret_sauce,fail,Locked account
```

### JSON Format (login.json)

```json
[
  { "username": "standard_user", "password": "secret_sauce", "expected": "success" },
  { "username": "locked_out_user", "password": "secret_sauce", "expected": "fail" }
]
```

### ZIP Format (login.zip)

Contains any of the above files. DataProvider reads all of them, combines rows.

### How to use in a test:

```java
// 🔑 Step 1: Declare a DataProvider method
@DataProvider(name = "loginFromExcel")
public Object[][] loginFromExcel() {
    return DataProviderFactory.fromFile("src/test/resources/testdata/login.xlsx");
}

// 🔑 Step 2: Link @Test to the DataProvider
@Test(dataProvider = "loginFromExcel", groups = {"regression"})
public void verifyLoginFromExcel(DataRow row) { // 🔑 DataRow is the parameter!
    // 🔑 Step 3: Read values from the row
    String username = row.getRequired("username"); // Throws if column missing
    String password = row.getRequired("password");
    String expected = row.getRequired("expected");

    // 🔑 Step 4: Use them
    LoginPage page = new LoginPage(getDriver());
    page.navigateToLogin();
    page.login(username, password);

    if ("success".equalsIgnoreCase(expected)) {
        Assert.assertTrue(getDriver().getCurrentUrl().contains("inventory"));
    } else {
        Assert.assertTrue(page.isErrorDisplayed());
    }
}
// 🔑 TestNG automatically runs this test ONCE PER ROW in the Excel file!
```

---

## 🔧 Configuration Files Explained

### `global.properties` — Master Defaults

```properties
# ─── BROWSER ───────────────────────────────────────
browser=chrome           # chrome | firefox | edge | brave
headless=false           # true for CI (no screen needed)
timeout=10               # Max seconds to wait for elements

# ─── HUMAN PAUSE ENGINE ────────────────────────────
human.pause.enabled=true      # false = skip all pauses (fast CI mode)
human.pause.min=400           # Min ms before each click/type
human.pause.max=1200          # Max ms before each click/type
human.pause.postTest.min=500  # Min ms after test ends
human.pause.postTest.max=1500 # Max ms after test ends
human.pause.typing.min=40     # Min ms between keystrokes
human.pause.typing.max=120    # Max ms between keystrokes

# ─── RETRY ──────────────────────────────────────────
retry.count=2            # Retry failed tests up to 2 times
```

### `demoqa.properties` — Site-Specific Override

```properties
site.name=demoqa
url=https://demoqa.com
# Only define what's DIFFERENT from global.properties
```

### `saucedemo.properties` — Another Site

```properties
site.name=saucedemo
url=https://www.saucedemo.com
```

### `allure.properties` — Allure Output

```properties
allure.results.directory=target/allure-results
# This tells Allure where to save the raw JSON results files
```

---

## 📑 TestNG Suite XMLs Explained

Suite XML files tell TestNG **which tests to run** and **how to run them**.

### `demoqa-regression.xml`

```xml
<!DOCTYPE suite SYSTEM "https://testng.org/testng-1.0.dtd">

<suite name="DemoQA Regression Suite" parallel="none">
<!--   ↑ Name shown in report    ↑ parallel="none" = sequential execution
       Other options: parallel="methods" or parallel="classes" for parallel  -->

    <listeners>
        <!-- 🔑 These run automatically for every test in this suite -->
        <listener class-name="com.automation.sites.listeners.TestListener"/>
        <listener class-name="com.automation.sites.listeners.RetryListener"/>
    </listeners>

    <test name="DemoQA Regression Tests" preserve-order="true">
    <!--                                  ↑ Run tests in the order @priority says -->

        <groups>
            <run>
                <include name="regression"/>  <!-- Only run @Test(groups={"regression"}) -->
            </run>
        </groups>

        <packages>
            <!-- Run ALL test classes in this package -->
            <package name="com.automation.sites.demoqa.tests"/>
        </packages>
    </test>
</suite>
```

### Smoke vs Regression:

| Suite | Groups | Purpose |
|---|---|---|
| `demoqa-smoke.xml` | `smoke` | Quick sanity check — only critical paths (~5 min) |
| `demoqa-regression.xml` | `regression` | Full coverage — all tests (~30-60 min) |
| `saucedemo-regression.xml` | `regression` | SauceDemo full coverage |

---

## 🚀 How To Run Tests

### Prerequisites
- ☑️ Java 17 installed (`java -version`)
- ☑️ Maven installed (`mvn -version`)
- ☑️ Chrome/Firefox/Edge browser installed
- ☑️ Internet access (WebDriverManager downloads drivers automatically)

### Quick Start Commands

```bash
# ─── 1. Run default suite (demoqa regression, Chrome, headed) ──────────────
mvn test

# ─── 2. Run a specific site ────────────────────────────────────────────────
mvn test -Dsite=saucedemo -DsuiteXmlFile=testng-suites/saucedemo-regression.xml

# ─── 3. Run with Firefox ───────────────────────────────────────────────────
mvn test -Dbrowser=firefox

# ─── 4. Run headless (no browser window — great for background runs) ────────
mvn test -Dheadless=true

# ─── 5. Run smoke suite only ───────────────────────────────────────────────
mvn test -DsuiteXmlFile=testng-suites/demoqa-smoke.xml

# ─── 6. Run with retries disabled (fastest) ────────────────────────────────
mvn test -Dretry.count=0 -Dhuman.pause.enabled=false

# ─── 7. Run everything — all options combined ──────────────────────────────
mvn test \
  -Dsite=demoqa \
  -DsuiteXmlFile=testng-suites/demoqa-regression.xml \
  -Dbrowser=chrome \
  -Dheadless=true \
  -Dhuman.pause.enabled=false \
  -Dretry.count=1

# ─── 8. Generate Allure report (after tests run) ────────────────────────────
mvn allure:serve       # Opens Allure report in browser
mvn allure:report      # Just generates the HTML (no browser open)
```

### Where to Find Reports After Running

```
target/
├── extent-reports/index.html     ← Open this for Extent Report
├── allure-results/               ← Raw data (run `mvn allure:serve` to view)
├── allure-report/                ← Generated Allure HTML
├── screenshots/                  ← PNG files of failures
├── downloads/                    ← Files downloaded during upload/download tests
└── surefire-reports/             ← TestNG XML results (used by Jenkins)
```

---

## 🔄 Jenkins CI/CD Pipeline — Full Flow

📁 `Jenkinsfile`

The Jenkinsfile defines an **automated pipeline** that runs every time code is pushed. Here's what each stage does:

```
Git Push / Manual Trigger
        │
        ▼
┌─────────────────────────────────────────────────────────────┐
│  STAGE 1: Checkout                                          │
│  Git pulls the latest code from the repository             │
└────────────────────┬────────────────────────────────────────┘
                     │
                     ▼
┌─────────────────────────────────────────────────────────────┐
│  STAGE 2: Build                                             │
│  mvn -B -ntp clean compile test-compile                    │
│  Compiles main code + test code. Fails fast if syntax error │
└────────────────────┬────────────────────────────────────────┘
                     │
                     ▼
┌─────────────────────────────────────────────────────────────┐
│  STAGE 3: Discover Site Projects                            │
│  Scans testng-suites/ for *-{SUITE_TYPE}.xml files         │
│  Builds a list of sites to test (e.g. demoqa, saucedemo)  │
└────────────────────┬────────────────────────────────────────┘
                     │
                     ▼
┌─────────────────────────────────────────────────────────────┐
│  STAGE 4: Run Tests Per Site                               │
│  For each discovered site:                                  │
│    mvn test -Dsite={site} -Dheadless=true                  │
│             -Dhuman.pause.enabled=false                     │
│             -Dbrowser={BROWSER}                             │
│  testFailureIgnore=true → continue even if tests fail      │
│  Builds a map of {site → exit code} for reporting          │
└────────────────────┬────────────────────────────────────────┘
                     │
                     ▼
┌─────────────────────────────────────────────────────────────┐
│  POST: Always (regardless of pass/fail)                    │
│  • junit: Publishes surefire XML to Jenkins test trends    │
│  • allure: Generates + publishes Allure report             │
│  • publishHTML: Extent report via HTML Publisher plugin     │
│  • archiveArtifacts: Zips and stores screenshots, results  │
└─────────────────────────────────────────────────────────────┘
```

### Jenkins Pipeline Parameters (can be set at runtime in Jenkins UI)

| Parameter | Default | Options |
|---|---|---|
| `SUITE_TYPE` | `regression` | `regression`, `smoke` |
| `SITE` | `ALL` | `ALL`, `demoqa`, `saucedemo` |
| `BROWSER` | `chrome` | `chrome`, `firefox`, `edge` |
| `HEADLESS` | `true` | `true`, `false` |
| `RETRY_COUNT` | `0` | Any number |

### Jenkins Setup Requirements

```
Jenkins Plugins needed:
  ✅ HTML Publisher Plugin     → for Extent Reports
  ✅ Allure Jenkins Plugin     → for Allure Reports
  ✅ Git Plugin                → for code checkout

Jenkins Global Tools needed (Manage Jenkins → Global Tool Configuration):
  ✅ JDK → name: "JDK17"
  ✅ Maven → name: "Maven3"
  ✅ Allure Commandline → name: "allure"
```

---

## 📈 Reports — Allure & Extent

### Extent Reports (HTML)

- 📄 **Location:** `target/extent-reports/index.html`
- 🎨 **Features:** Dashboard with pass/fail counts, per-test logs, embedded failure screenshots, system info (browser, OS, Java version)
- 📌 **When it's generated:** At the end of every test run automatically

### Allure Reports (Interactive)

- 📁 **Raw data:** `target/allure-results/` (JSON files per test)
- 🌐 **To view:** `mvn allure:serve` (starts a local web server + opens browser)
- 🎨 **Features:** Timeline view, test steps, categories, environment info, attached screenshots for both pass AND fail
- 📌 **Allure needs AspectJ** to instrument `@Step` annotations — configured in `pom.xml`'s `argLine`

### Why Two Report Systems?

| Feature | Extent Reports | Allure Reports |
|---|---|---|
| Setup | Zero — auto-generated | Needs `mvn allure:serve` |
| Screenshots | ✅ Embedded in HTML | ✅ Click to expand |
| CI Integration | Jenkins HTML Publisher | Native Allure Jenkins Plugin |
| Trends over time | ❌ | ✅ Historical graphs |
| Step-by-step detail | ❌ | ✅ With `@Step` annotations |
| Shareable | Single HTML file | Needs web server |

---

## 🌐 Multi-Site Architecture

The framework is designed to test **multiple websites** without changing any code — just config files.

```
One framework, many sites:

testng-suites/
├── demoqa-regression.xml      → runs all demoqa tests
├── demoqa-smoke.xml           → runs demoqa smoke tests
└── saucedemo-regression.xml   → runs saucedemo tests

src/test/resources/config/
├── global.properties          → browser, timeout, human pauses (shared)
├── demoqa.properties          → url=https://demoqa.com
└── saucedemo.properties       → url=https://www.saucedemo.com

src/main/java/.../sites/
├── demoqa/pages/              → 22 Page Objects for demoqa.com
└── saucedemo/pages/           → Page Objects for saucedemo.com
```

**To add a new site:**
1. Create `config/mynewsite.properties` with `url=https://mynewsite.com`
2. Create `src/main/java/.../sites/mynewsite/pages/` with page objects
3. Create `src/test/java/.../sites/mynewsite/tests/` with test classes
4. Create `testng-suites/mynewsite-regression.xml`
5. Run: `mvn test -Dsite=mynewsite -DsuiteXmlFile=testng-suites/mynewsite-regression.xml`

---

## 🧩 Test Coverage — All 25 DemoQA Tests

| 🏷️ Test Class | 📝 What It Tests | 🔖 Groups |
|---|---|---|
| `AccordianTest` | Expand/collapse accordion sections | regression |
| `AlertsTest` | JS alert, timer alert, confirm, prompt dialogs | smoke, regression |
| `AutoCompleteTest` | Auto-complete input with multi/single value | regression |
| `BookStoreApplicationTest` | Book store search, add, profile management | regression |
| `BrokenLinksImagesTest` | Detect broken links and images | regression |
| `BrowserWindowsTest` | New tab, new window, new window message | regression |
| `ButtonsTest` | Double-click, right-click, regular click | smoke, regression |
| `CheckBoxTest` | Home tree checkbox expand/select | regression |
| `DatePickerTest` | Date + date-and-time pickers | regression |
| `DraggableTest` | Simple drag, restricted drag, cursor variations | regression |
| `DroppableTest` | Simple/accept/prevent/revert droppable areas | regression |
| `DynamicPropertiesTest` | Wait for dynamic element, color change, visible | regression |
| `FramesTest` | Interact with elements inside iframes | regression |
| `LinksTest` | Home/dynamic links, API response links | regression |
| `MenuTest` | Hover navigation menu items | regression |
| `ModalDialogsTest` | Small and large modal dialogs | regression |
| `NestedFramesTest` | Frame inside a frame interaction | regression |
| `PracticeFormTest` | Full student registration form submission | regression |
| `ProgressBarTest` | Start/stop progress bar, verify value | regression |
| `RadioButtonTest` | Radio button selection and verification | regression |
| `ResizableTest` | Resize box and restricted resize box | regression |
| `SelectMenuTest` | Select value, title, multi-select | regression |
| `SelectableTest` | Grid/list item selection | regression |
| `SliderTest` | Drag slider to target value | regression |
| `SortableTest` | Drag to sort list/grid items | regression |
| `TabsTest` | Switch between What/Origin/Use tabs | regression |
| `TextBoxTest` | Form fill and output verification | smoke, regression |
| `ToolTipsTest` | Hover tooltip on button/text/field | regression |
| `UploadDownloadTest` | File upload and download verification | regression |
| `WebTablesCRUDTest` | Add, edit, delete, search web table rows | regression |
| `LoginTest` (SauceDemo) | Successful login verification | smoke, regression |
| `LoginDataDrivenTest` (SauceDemo) | Multi-format DDT login (Excel/CSV/JSON/ZIP) | regression |

---

## 🔰 Key Concepts Quick Reference

| Term | What It Means |
|---|---|
| `WebDriver` | The object that controls the browser |
| `By` | A locator strategy — tells Selenium HOW to find an element |
| `WebElement` | Represents a single HTML element on the page |
| `WebDriverWait` | Waits up to N seconds for a condition before throwing |
| `ExpectedConditions` | Pre-built conditions like `visibilityOfElementLocated` |
| `@Test` | Marks a method as a test case for TestNG |
| `@BeforeMethod` | Runs before EVERY `@Test` method |
| `@AfterMethod` | Runs after EVERY `@Test` method |
| `@DataProvider` | Supplies data sets to a data-driven test |
| `ThreadLocal` | Gives each thread its own private variable copy |
| `synchronized` | Allows only one thread at a time inside a method |
| `Properties` | Java's key-value config file reader |
| `ITestListener` | TestNG interface for hooking into test events |
| `IRetryAnalyzer` | TestNG interface for retry logic |
| `Base64` | Encoding binary (image) data as plain text |

---

## 🛡️ Best Practices This Framework Follows

- ✅ **Page Object Model** — No locators in test classes
- ✅ **Thread-safe** — `ThreadLocal<WebDriver>` for parallel execution
- ✅ **Config-driven** — Nothing hardcoded; all settings in `.properties` files
- ✅ **Auto-driver management** — `WebDriverManager` downloads drivers automatically
- ✅ **Dual reporting** — Allure for CI trends + Extent for quick HTML sharing
- ✅ **Multi-format DDT** — Same test reads Excel, CSV, JSON, or ZIP
- ✅ **Human simulation** — Randomized delays to avoid bot detection
- ✅ **Auto-retry** — Failed tests retry N times before final failure
- ✅ **Screenshot on failure** — Auto-captured and embedded in both reports
- ✅ **Multi-site** — Add a new site with just a `.properties` file and page objects
- ✅ **CI-ready** — Full Jenkins pipeline with headless mode + report publishing

---

*📌 Framework built with Java 17 · Selenium 4.21 · TestNG 7.9 · Maven · Allure 2.27 · ExtentReports 5*
