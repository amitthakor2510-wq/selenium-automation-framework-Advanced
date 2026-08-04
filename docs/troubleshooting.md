# Troubleshooting & Glossary

## 🩹 Common Errors and Fixes

| Error | Cause | Fix |
|---|---|---|
| `ElementClickInterceptedException` | Ad banner covering element | Use `js.executeScript("arguments[0].click()", el)` |
| `TimeoutException` | Element not found in time, OR the site's markup changed | Check locator in DevTools; check `target/debug-dumps/` if the page object dumps on failure |
| `UnhandledAlertException` on any subsequent driver call | A native alert opened and was never accepted/dismissed | Call `wait.until(alertIsPresent())` then `.accept()`/`.dismiss()` immediately after the action that triggers it, before any other driver call |
| `NoSuchWindowException` | Window already closed | Wrap `driver.close()` in try-catch |
| `StaleElementReferenceException` | Page reloaded, element gone | Re-find element after page action |
| `InvalidSelectorException: Compound class names` | Space in `By.className()` | Use `By.cssSelector(".class1.class2")` instead |
| `Keys to send should be not null` | Variable is null | Check `@BeforeMethod` runs before `@Test` |
| `NoSuchElementException` | Wrong locator, inside an iframe, OR read happened before an async re-render finished | Check locator in DevTools, switch frame first, or poll instead of a one-shot read |
| `NoSuchWindowException on close()` | Message window closed itself | Wrap in try-catch, window already gone |
| `ElementNotInteractableException` | Element hidden or disabled | Use `presenceOfElementLocated`, then JS click |
| `Cannot resolve symbol 'Step'` (IDE) in `src/main/java` code | `allure-testng` (which transitively brings `@Step`) is declared `scope=test`, invisible to main code | Add `io.qameta.allure:allure-java-commons` as its own dependency with the default (compile) scope — already done in this pom |
| `Expected status code <200> but was <204>` on `DELETE /Account/v1/User/{UUID}` | DemoQA's Swagger docs say `200`; the live endpoint actually returns `204 No Content` | Assert `204`, not the documented `200` — already fixed in `BookStoreApiTest` |
| `TimeoutException` even though the element clearly exists in the browser | `SelfHealingEngine` tried to heal and the best DOM candidate scored below `self-healing.threshold` (or no fingerprint existed yet to heal against) | Check the test log for a `[SelfHealing] ... broke and no candidate matched closely enough` warning (it logs the best score it found); if that score looks right, lower `self-healing.threshold` slightly — otherwise fix the locator directly, since healing is a safety net, not a substitute for an accurate locator |

---

## 📖 Glossary

| Term | Meaning |
|---|---|
| Page Object Model | Design pattern — one Java class per webpage |
| Helper method | Private method inside a class that does repeated work |
| Locator | How Selenium finds an element — `By.id`, `By.xpath`, etc. |
| WebDriverWait | Tells Selenium to keep retrying until condition is true or timeout |
| ThreadLocal | Variable where each thread gets its own independent copy |
| TestNG groups | Tags on tests — `smoke` or `regression` — for selective running |
| Extent Report | Custom HTML test report with charts and embedded screenshots |
| Allure Report | Interactive test report with trend history across runs, used via CI |
| HumanActions | Framework utility adding random delays to simulate human typing/clicking |
| Actions class | Selenium class for hover, drag-drop, double-click, keyboard shortcuts |
| Alert | Native JavaScript browser popup (`alert`/`confirm`/`prompt`) — not part of the page's own HTML/DOM |
| In-page modal | A dialog rendered by the site's own HTML/CSS/JS — looks like a popup but is a normal DOM element, so `alertIsPresent()` never catches it |
| Frame / iframe | HTML element that embeds one webpage inside another |
| Window handle | Unique ID assigned to each open browser tab or window |
| ARIA attribute | Accessibility HTML attribute readable by automation tools |
| JS click | Clicking via JavaScript — bypasses overlay/banner interception |
| Singleton | Design pattern — only one instance of a class ever created |
| CSP | Content Security Policy — Jenkins setting that blocks external CSS in reports |
| Smoke test | Quick sanity check — is it working at all? |
| Regression test | Full suite — did anything break? |
| dispatchEvent | JavaScript command to fire browser events that React/Vue listens to |
| Debug dump | A full page-source snapshot written to `target/debug-dumps/` when a locator fails, for diagnosing site changes from real markup instead of guessing |
| Keyword-driven testing | Writing test steps as rows in a data file (CSV) instead of Java methods — a new scenario is a new row, no code compile needed |
| Data-driven testing | Running one test method many times with different input values pulled from an external file (CSV/JSON/Excel/YAML) |
| Object Repository | A properties file mapping short names to locators (`login.username = id:user-name`), kept separate from both scripts and Java code |
| axe-core | An accessibility-testing engine (by Deque) that scans a page's rendered DOM for WCAG violations — missing alt text, low contrast, bad ARIA, etc. |
| Accessibility scan / WCAG | Automated check for whether a page is usable by people relying on assistive tech (screen readers, keyboard-only navigation); WCAG is the standard those checks are measured against |
| Visual regression | Comparing a pixel screenshot of a page against a saved baseline image to catch unintended layout/styling changes a functional test wouldn't notice |
| Baseline image | The "known good" reference screenshot a visual regression test diffs every future run against |
| AShot | The Java library this framework uses to capture and pixel-diff screenshots for visual regression |
| Appium | A mobile-app automation tool — same idea as Selenium, but drives native Android/iOS apps instead of a browser |
| JMeter | A load/performance testing tool; used here only for a lightweight response-time smoke check, not real load testing |
| Self-healing locator (manual) | A locator strategy that tries a primary element selector and automatically falls back to alternates a developer explicitly wrote (see `SmartLocator.java`) |
| Self-healing locator (automatic) | Framework-wide auto-recovery: when any locator breaks, `SelfHealingEngine` re-finds the element by scoring live DOM elements against a fingerprint saved the last time that locator succeeded — no explicit fallback required (see [🩹 Self-Healing Locators](architecture.md#-self-healing-locators)) |
| Element fingerprint | The identifying snapshot (`tag`, `id`, `name`, classes, text, key attributes) `SelfHealingEngine` captures for every successfully-found element, used as the baseline to heal against if that locator later breaks |
| Opt-in test type | A test suite that exists in the repo but isn't run by the default CI pipeline — must be triggered explicitly with its own suite file or Maven profile |

---

