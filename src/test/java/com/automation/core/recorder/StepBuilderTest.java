package com.automation.core.recorder;

import com.automation.core.recorder.RecordedEvent.FrameStep;
import com.automation.core.recorder.RecordedEvent.Target;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Browser-free tests for {@link StepBuilder}: how raw recorder events become keyword steps and repository keys. */
class StepBuilderTest {

    private static final String BASE = "http://site.test";
    private static final String LOGIN = BASE + "/login.html";

    // ----------------------------------------------------------------- helpers

    private static Target target(String tag, String id, String text, Candidate... cands) {
        Target t = new Target();
        t.tag = tag;
        t.id = id;
        t.text = text;
        t.parentTag = "div";
        t.cands = new ArrayList<>(List.of(cands));
        return t;
    }

    private static Target idTarget(String id) {
        return target("input", id, "", new Candidate("id", id, true));
    }

    private static RecordedEvent ev(String type, Target t, long ts) {
        RecordedEvent e = new RecordedEvent();
        e.type = type;
        e.target = t;
        e.ts = ts;
        e.rcv = ts;
        e.url = LOGIN;
        return e;
    }

    private static RecordedEvent url(String address, long rcv) {
        return RecordedEvent.synthetic("url", address, null, rcv);
    }

    private static RecordedEvent typed(Target t, String value, long ts) {
        RecordedEvent e = ev("type", t, ts);
        e.value = value;
        return e;
    }

    private static RecordedFlow build(List<RecordedEvent> events) {
        return new StepBuilder("lab", BASE, true, Map.of()).build(events);
    }

    private static List<String> keywords(RecordedFlow flow) {
        return flow.steps.stream().map(s -> s.keyword).collect(Collectors.toList());
    }

    // ------------------------------------------------------------------- tests

    @Test
    void firstPageIsNavigateRelativeToTheBaseUrl() {
        RecordedFlow flow = build(List.of(url(BASE + "/login.html", 1000)));
        assertEquals(List.of("NAVIGATE"), keywords(flow));
        assertEquals("/login.html", flow.steps.get(0).testData);
    }

    @Test
    void focusClickBeforeTypingIsDroppedButALoneFocusClickIsKept() {
        RecordedEvent click = ev("click", idTarget("user"), 1100);
        click.soft = true;
        RecordedFlow typing = build(List.of(url(LOGIN, 1000), click, typed(idTarget("user"), "amit", 1200)));
        assertEquals(List.of("NAVIGATE", "TYPE"), keywords(typing));

        RecordedEvent lone = ev("click", idTarget("date"), 1100);
        lone.soft = true;
        RecordedFlow noTyping = build(List.of(url(LOGIN, 1000), lone, ev("click", idTarget("ok"), 1300)));
        assertEquals(List.of("NAVIGATE", "CLICK", "CLICK"), keywords(noTyping));
    }

    @Test
    void secretsAreReplacedByEnvPlaceholdersAndKeptOutOfTheScript() {
        Target pin = target("input", "", "", new Candidate("css", "input[placeholder='Enter code']", true));
        pin.hints.placeholder = "Enter code";
        pin.secret = true;
        RecordedFlow flow = build(List.of(url(LOGIN, 1000), typed(pin, "1234-secret", 1100)));

        RecordedStep type = flow.steps.get(1);
        assertEquals("TYPE", type.keyword);
        assertTrue(type.locatorKey.endsWith("enterCodePassword"), "key must look sensitive so masking applies: " + type.locatorKey);
        assertEquals("${env:LAB_LOGIN_ENTER_CODE_PASSWORD}", type.testData);
        assertEquals("1234-secret", flow.secrets.get("LAB_LOGIN_ENTER_CODE_PASSWORD"));
        assertFalse(flow.steps.stream().anyMatch(s -> s.testData.contains("1234-secret") || s.description.contains("1234-secret")));
    }

    @Test
    void typingIntoAPrefilledFieldClearsFirstAndAnEmptyValueIsJustAClear() {
        RecordedEvent retype = typed(idTarget("user"), "new", 1100);
        retype.hadValue = true;
        RecordedFlow flow = build(List.of(url(LOGIN, 1000), retype, typed(idTarget("user"), "", 1200)));
        assertEquals(List.of("NAVIGATE", "CLEAR", "TYPE", "CLEAR"), keywords(flow));
    }

    @Test
    void twoClicksAndADoubleClickBecomeOneDoubleClick() {
        Target tile = target("span", "tile", "tile", new Candidate("id", "tile", true));
        RecordedFlow flow = build(List.of(url(LOGIN, 1000), ev("click", tile, 1100), ev("click", tile, 1200), ev("dblclick", tile, 1210)));
        assertEquals(List.of("NAVIGATE", "DOUBLE_CLICK"), keywords(flow));
    }

    @Test
    void aPageChangeAfterAClickIsWaitedForAndAssertedWhileAnUnrelatedOneIsANavigate() {
        RecordedFlow flow = build(List.of(url(LOGIN, 1000), ev("click", idTarget("go"), 5000),
            url(BASE + "/inventory.html?sort=asc", 5400), url(BASE + "/other.html", 60_000)));
        assertEquals(List.of("NAVIGATE", "CLICK", "WAIT_FOR_PAGE_LOAD", "VERIFY_URL_CONTAINS", "NAVIGATE"), keywords(flow));
        assertEquals("/inventory.html", flow.steps.get(3).expected);
        assertEquals("/other.html", flow.steps.get(4).testData);

        RecordedFlow quiet = new StepBuilder("lab", BASE, false, Map.of())
            .build(List.of(url(LOGIN, 1000), ev("click", idTarget("go"), 5000), url(BASE + "/inventory.html", 5400)));
        assertEquals(List.of("NAVIGATE", "CLICK", "WAIT_FOR_PAGE_LOAD"), keywords(quiet));
    }

    @Test
    void iframesAreEnteredAndLeftAndSameLocatorsInDifferentFramesStayDistinct() {
        FrameStep one = frame("frameOne");
        FrameStep two = frame("frameTwo");
        RecordedEvent inOne = ev("click", idTarget("btn"), 1100);
        inOne.frame = List.of(one);
        RecordedEvent inTwo = ev("click", idTarget("btn"), 1200);
        inTwo.frame = List.of(two);
        RecordedEvent top = ev("click", idTarget("btn"), 1300);

        RecordedFlow flow = build(List.of(url(LOGIN, 1000), inOne, inTwo, top));
        assertEquals(List.of("NAVIGATE", "SWITCH_TO_FRAME", "CLICK", "SWITCH_TO_DEFAULT_CONTENT", "SWITCH_TO_FRAME", "CLICK",
            "SWITCH_TO_DEFAULT_CONTENT", "CLICK"), keywords(flow));
        List<String> clickKeys = flow.steps.stream().filter(s -> s.keyword.equals("CLICK")).map(s -> s.locatorKey).collect(Collectors.toList());
        assertEquals(3, clickKeys.stream().distinct().count(), "equal locators in three contexts need three keys: " + clickKeys);
    }

    private static FrameStep frame(String id) {
        FrameStep f = new FrameStep();
        f.tag = "iframe";
        f.id = id;
        f.cands = new ArrayList<>(List.of(new Candidate("id", id, true)));
        return f;
    }

    @Test
    void anExistingKeyForTheSameLocatorIsReusedAndANameCollisionGetsASuffix() {
        Map<String, String> existing = Map.of("lab.login.userField", "id:user", "lab.login.username", "id:somethingElse");
        StepBuilder builder = new StepBuilder("lab", BASE, true, existing);

        RecordedFlow reuse = builder.build(List.of(url(LOGIN, 1000), ev("click", idTarget("user"), 1100)));
        assertEquals("lab.login.userField", reuse.steps.get(1).locatorKey);
        assertTrue(reuse.locators.get("lab.login.userField").alreadyInRepository);

        RecordedFlow collide = new StepBuilder("lab", BASE, true, existing)
            .build(List.of(url(LOGIN, 1000), ev("click", idTarget("username"), 1100)));
        assertEquals("lab.login.username2", collide.steps.get(1).locatorKey);
        assertFalse(collide.locators.get("lab.login.username2").alreadyInRepository);
    }

    @Test
    void positionBasedLocatorsAreFlaggedAndNeverUsedAsFallbacks() {
        Target onlyPositional = target("div", "", "", new Candidate("css", "html > body > div:nth-of-type(3)", false));
        RecordedFlow flow = build(List.of(url(LOGIN, 1000), ev("click", onlyPositional, 1100)));
        assertTrue(flow.notes.stream().anyMatch(n -> n.contains("position-based")), flow.notes.toString());

        Target mixed = target("input", "user", "", new Candidate("id", "user", true), new Candidate("name", "u", true),
            new Candidate("css", "html > body > input:nth-of-type(1)", false));
        RecordedFlow mixedFlow = build(List.of(url(LOGIN, 1000), ev("click", mixed, 1100)));
        LocatorSpec spec = mixedFlow.locators.values().iterator().next();
        assertEquals(List.of("id:user", "name:u"), spec.locators.stream().map(Candidate::toLocatorString).collect(Collectors.toList()));
    }

    @Test
    void newAndClosedWindowsBecomeSwitchAndCloseSteps() {
        RecordedFlow flow = build(List.of(url(LOGIN, 1000), ev("click", idTarget("open"), 1100),
            RecordedEvent.synthetic("window", "about:blank", "new", 1200), ev("click", idTarget("popupBtn"), 1300),
            RecordedEvent.synthetic("window", LOGIN, "closed", 1400)));
        assertEquals(List.of("NAVIGATE", "CLICK", "SWITCH_TO_WINDOW", "CLICK", "CLOSE_WINDOW"), keywords(flow));
        assertEquals("NEW", flow.steps.get(2).testData);
    }

    @Test
    void checksSelectsUploadsKeysAssertionsAndAlertsMapToTheirKeywords() {
        RecordedEvent on = ev("check", idTarget("agree"), 1100);
        on.checked = true;
        RecordedEvent off = ev("check", idTarget("agree"), 1200);
        off.checked = false;
        RecordedEvent select = ev("select", idTarget("color"), 1300);
        select.text = "Green";
        RecordedEvent upload = ev("upload", idTarget("file"), 1400);
        upload.text = "cv.pdf";
        RecordedEvent key = ev("key", idTarget("q"), 1500);
        key.key = "ENTER";
        RecordedEvent text = ev("assertText", target("span", "msg", "Saved", new Candidate("id", "msg", true)), 1600);
        text.text = "Saved";
        RecordedEvent value = ev("assertValue", idTarget("user"), 1700);
        value.value = "amit";
        RecordedEvent visible = ev("assertVisible", idTarget("banner"), 1800);
        RecordedEvent alert = RecordedEvent.synthetic("alert", LOGIN, "Are you sure?", 1900);

        RecordedFlow flow = build(List.of(url(LOGIN, 1000), on, off, select, upload, key, text, value, visible, alert));
        assertEquals(List.of("NAVIGATE", "CHECK", "UNCHECK", "SELECT_BY_TEXT", "UPLOAD_FILE", "PRESS_KEY", "VERIFY_TEXT",
            "VERIFY_ATTRIBUTE", "VERIFY_DISPLAYED", "ACCEPT_ALERT"), keywords(flow));
        assertEquals("Green", flow.steps.get(3).testData);
        assertEquals("src/test/resources/testdata/uploads/cv.pdf", flow.steps.get(4).testData);
        assertEquals("ENTER", flow.steps.get(5).testData);
        assertEquals("Saved", flow.steps.get(6).expected);
        assertEquals("value", flow.steps.get(7).testData);
        assertEquals("amit", flow.steps.get(7).expected);
        assertTrue(flow.notes.stream().anyMatch(n -> n.contains("UPLOAD_FILE")));
    }

    @Test
    void anElementWithNoUsableLocatorIsSkippedWithANote() {
        RecordedFlow flow = build(List.of(url(LOGIN, 1000), ev("click", target("div", "", "x"), 1100)));
        assertEquals(List.of("NAVIGATE"), keywords(flow));
        assertTrue(flow.notes.stream().anyMatch(n -> n.contains("No unique locator")), flow.notes.toString());
    }
}
