---
title: "DemoQA"
type: site
layer: site
tags: [fw/site, kind/site]
parent: "[[Sites Under Test]]"
---

# DemoQA

> [!abstract] Deepest suite: 32 Page Objects and 42 test files (~147 `@Test`s) covering Elements, Forms, Alerts/Frames/Windows, Widgets, Interactions and the Book Store (UI + REST).

**Part of:** [[Sites Under Test]]  ·  **Layer:** Sites under test

| Section | Pages / tests |
|---|---|
| Elements | TextBox · CheckBox · RadioButton · WebTables (full CRUD) · Buttons · Links · BrokenLinksImages · UploadDownload · DynamicProperties |
| Forms | PracticeForm |
| Alerts/Frames/Windows | BrowserWindows · Alerts · Frames · NestedFrames · ModalDialogs |
| Widgets | Accordian · AutoComplete · DatePicker · Slider · ProgressBar · Tabs · ToolTips · Menu · SelectMenu |
| Interactions | Draggable · Droppable · Resizable · Selectable · Sortable |
| Book Store | `BookStoreApplicationTest` (**16 tests, one shared session**: register → login → browse → profile → add/delete → logout), Login/Profile/Registration pages |
| API | `BookStoreApiTest`, `BookStoreApiNegativeTest`, `ApiResilienceMockedTest`, `ProfileApiSeededTest`, `DemoQaAccountApi` |
| Specialised | `AccessibilityTest`, `VisualRegressionTest`, `RegistrationSyntheticDataTest`, `KeywordDrivenTextBoxTest`, `DemoQaHomePagePerfTest` |

**Recurring issues & fixes:** React controlled inputs dropping keystrokes (native-value-setter JS) · sticky ad banner intercepting clicks (navigate by URL) · Bootstrap tab timing · markup drift (e.g. checkbox tree `react-checkbox-tree`→`rc-tree`) · ReCaptcha rate-limit on `/register` · DELETE user returns **204** not 200.

Components used: [[BootstrapModalComponent]], [[ReactDatePickerComponent]], [[SmartLocator]]. Config: `url=https://demoqa.com`, `timeout=20`.

## Connections

- **Depends on →** [[BasePage]] · [[BaseTest]] · [[HumanActions]] · [[BootstrapModalComponent]] · [[ReactDatePickerComponent]] · [[SmartLocator]] · [[CleanupRegistry]]
- **Related ↔** [[API Testing Layer]] · [[AccessibilityUtils]] · [[VisualRegressionUtils]] · [[SyntheticDataGenerator]] · [[WireMockManager]]
