---
title: "Tech Stack"
type: concept
layer: core
tags: [fw/core, kind/concept]
parent: "[[Selenium Framework Hub]]"
---

# Tech Stack

> [!abstract] Every dependency, plugin and tool version the framework is pinned to.

**Part of:** [[Selenium Framework Hub]]  ·  **Layer:** Core framework

| Tool | Version | Purpose |
|---|---|---|
| Java | 17 | language |
| Selenium | 4.21.0 | browser automation (+ `selenium` 4.9.1 devtools artifact) |
| TestNG | 7.9.0 | runner, groups, retry |
| Maven | 3.9+ (wrapper) | build |
| Allure | 2.27.0 (`allure-testng`, `allure-rest-assured`) | interactive report + history |
| ExtentReports | 5.1.2 | self-contained HTML report |
| ReportPortal agent | 5.6.5 (`agent-java-testng`) | optional live results |
| Rest-Assured | 5.4.0 (+ `json-schema-validator`) | API tests + contract validation |
| WireMock standalone | 3.13.2 | API mocking |
| JMeter Java DSL | 2.2.1 | load tests; `jmeter-maven-plugin` for the `.jmx` smoke |
| Apache POI | 5.2.5 | Excel data |
| OpenCSV | 5.9 | CSV data |
| Jackson | 2.22.1 | JSON data + AI client JSON |
| SnakeYAML | 2.2 | YAML data |
| DataFaker | 2.4.2 | synthetic data |
| axe-core (Deque Selenium) | 4.9.1 | accessibility |
| AShot | 1.5.4 | visual regression |
| Appium java-client | 9.3.0 | mobile |
| Tess4J | 5.12.0 | OCR for CAPTCHA (needs native Tesseract) |
| Monte Screen Recorder | 0.7.7.0 | video |
| WebDriverManager | 6.3.4 | driver download |
| JaCoCo | 0.8.12 (+ `org.jacoco.core`) | coverage + per-test coverage map |
| Checkstyle plugin | 3.5.0 | style gate |
| OWASP dependency-check | plugin | CVE scan |
| PIT + `pitest-junit5-plugin` 1.2.1 | | mutation testing |
| Lombok | 1.18.46 | boilerplate (`@Getter`) |
| SLF4J 2.0.13 + Log4j2 2.23.1 | | logging |
| AspectJ weaver | 1.9.25 | Allure `@Step` weaving |
| JUnit Jupiter | 5.10.2 | pure unit tests (`-Punit-tests`) |
| Docker / Selenium Grid images | 4.21.0 | hub + chrome/firefox/edge nodes |
| Allure CLI | 2.27.0 (bundled in `.allure/`) | report generation |

## Connections

- **Related ↔** [[Maven Profiles]] · [[Project Structure]]
