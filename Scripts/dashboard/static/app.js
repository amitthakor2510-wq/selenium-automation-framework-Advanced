/*
 * Automation Console — frontend. No build step, no framework, no CDN
 * dependency (this has to work offline: Docker, LAN, no-internet dev
 * boxes). Talks to dashboard_server.py's JSON API; the browser's native
 * Basic-Auth prompt (triggered by the server's 401) handles the login,
 * so there is no custom auth form here.
 *
 * localStorage holds only per-browser UI preferences (theme, auto-refresh
 * switch, and a fallback pass-rate history for very old servers). Nothing
 * else persists client-side; every config change goes straight to the
 * server, which is the actual source of truth.
 */
(() => {
  "use strict";

  const $ = (sel, root = document) => root.querySelector(sel);

  const state = { testFilter: "" };
  const openSections = new Set(); // titles the user has expanded

  // ── theme ──────────────────────────────────────────────────
  // Applied, persisted and toggled by the shared /theme.js (loaded in <head>, so there is no flash and the
  // Hub / Control Center / Wizard pages follow the same choice). Only the palette command needs a handle.

  function toggleTheme() {
    if (window.ConsoleTheme) window.ConsoleTheme.toggle();
  }

  // ── fetch helpers ──────────────────────────────────────────

  async function api(path, options) {
    let res;
    try {
      res = await fetch(path, options);
    } catch (e) {
      // fetch only rejects when the server cannot be reached at all (stopped, network down).
      const err = new Error("Can't reach the dashboard server.");
      err.network = true;
      throw err;
    }
    if (res.status === 401) {
      setConn("auth required", "pill-bad");
      throw new Error("Authentication required — reload and sign in.");
    }
    const data = await res.json().catch(() => ({}));
    if (!res.ok) {
      throw new Error(data.error || `Request failed (${res.status})`);
    }
    return data;
  }

  function postJson(path, body) {
    return api(path, {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify(body),
    });
  }

  function setConn(text, cls) {
    const el = $("#connStatus");
    el.textContent = text;
    el.className = `pill ${cls}`;
  }

  // Errors stay up longer (they are the thing a person must actually read); a click dismisses either kind.
  function toast(message, ok) {
    const el = $("#toast");
    el.textContent = message;
    el.className = `toast show ${ok ? "is-ok" : "is-error"}`;
    clearTimeout(toast._t);
    toast._t = setTimeout(() => { el.className = "toast"; }, ok ? 2600 : 6500);
  }

  function escapeHtml(s) {
    const d = document.createElement("div");
    d.textContent = s;
    return d.innerHTML;
  }

  // navigator.clipboard.writeText needs a secure context (https, or
  // localhost) — fine when this dashboard is opened at localhost, but a
  // plain http:// LAN/ngrok URL on another host blocks it silently. The
  // legacy execCommand path works on any origin, so it's the fallback
  // rather than the primary (execCommand is deprecated but still broadly
  // supported, and there is no dependency-free modern equivalent for a
  // non-secure context).
  async function copyToClipboard(text) {
    try {
      if (navigator.clipboard && window.isSecureContext) {
        await navigator.clipboard.writeText(text);
        return true;
      }
    } catch (e) { /* fall through */ }
    try {
      const ta = document.createElement("textarea");
      ta.value = text;
      ta.style.position = "fixed";
      ta.style.opacity = "0";
      document.body.appendChild(ta);
      ta.focus();
      ta.select();
      const ok = document.execCommand("copy");
      ta.remove();
      return ok;
    } catch (e) {
      return false;
    }
  }

  // ── switch widget ──────────────────────────────────────────

  function makeSwitch(checked, onToggle, label) {
    const btn = document.createElement("button");
    btn.type = "button";
    btn.className = "switch";
    btn.setAttribute("role", "switch");
    btn.setAttribute("aria-checked", String(checked));
    if (label) btn.setAttribute("aria-label", label);
    const thumb = document.createElement("span");
    thumb.className = "switch-thumb";
    btn.appendChild(thumb);
    btn.addEventListener("click", async () => {
      if (btn.disabled) return;
      const next = btn.getAttribute("aria-checked") !== "true";
      // A successful toggle re-renders the whole list, which replaces this very button; disabling it also
      // drops focus in some browsers. Remember who had focus and give it back, or keyboard users land on <body>
      // after every single switch.
      const hadFocus = document.activeElement === btn;
      btn.disabled = true;
      try {
        await onToggle(next);
        btn.setAttribute("aria-checked", String(next));
      } catch (e) {
        toast(e.message, false);
      } finally {
        btn.disabled = false;
        if (hadFocus) refocusSwitch(btn, label);
      }
    });
    return btn;
  }

  function refocusSwitch(btn, label) {
    const target = document.contains(btn)
      ? btn
      : Array.from(document.querySelectorAll("button.switch")).find((x) => x.getAttribute("aria-label") === label);
    if (target) target.focus({ preventScroll: true });
  }

  // ── modals (raw viewer, command palette) ───────────────────
  // One implementation for every dialog: focus moves in on open, Tab stays inside, Esc closes, and focus is
  // handed back to whatever opened it — so keyboard and screen-reader users are never dropped on <body>.

  let modalReturnFocus = null;
  const FOCUSABLE = 'button:not([disabled]), input:not([disabled]), a[href], [tabindex]:not([tabindex="-1"])';

  function openModal(modal, focusEl) {
    modalReturnFocus = document.activeElement;
    modal.hidden = false;
    (focusEl || modal.querySelector(FOCUSABLE) || modal).focus();
  }

  function closeModal(modal) {
    if (modal.hidden) return;
    modal.hidden = true;
    const back = modalReturnFocus;
    modalReturnFocus = null;
    if (back && back.focus && document.contains(back)) back.focus();
  }

  function openModalEl() { return document.querySelector(".modal:not([hidden])"); }

  async function openRawModal(path, apiPath) {
    try {
      const data = await api(apiPath);
      $("#rawModalTitle").textContent = data.path || path;
      $("#rawModalBody").textContent = data.text || "(empty)";
      openModal($("#rawModal"), $("#rawModal [data-close]"));
    } catch (e) {
      toast(e.message, false);
    }
  }

  function wireModal() {
    document.querySelectorAll(".modal").forEach((modal) => {
      modal.querySelectorAll("[data-close]").forEach((el) =>
        el.addEventListener("click", () => closeModal(modal)));
    });
    document.addEventListener("keydown", (e) => {
      const modal = openModalEl();
      if (!modal) return;
      if (e.key === "Escape") { e.preventDefault(); closeModal(modal); return; }
      if (e.key !== "Tab") return;
      const items = Array.from(modal.querySelectorAll(FOCUSABLE)).filter((x) => x.offsetParent !== null);
      if (!items.length) return;
      const first = items[0], last = items[items.length - 1];
      if (e.shiftKey && document.activeElement === first) { e.preventDefault(); last.focus(); }
      else if (!e.shiftKey && document.activeElement === last) { e.preventDefault(); first.focus(); }
    });
    $("#sitesRawBtn").addEventListener("click", () => openRawModal("pipeline-config.properties", "/api/pipeline-config/raw"));
    $("#testsRawBtn").addEventListener("click", () => openRawModal("test-config.properties", "/api/test-config/raw"));
    $("#rawCopyBtn").addEventListener("click", async () => {
      const ok = await copyToClipboard($("#rawModalBody").textContent);
      toast(ok ? "Copied file contents" : "Couldn't copy automatically — select the text and copy it.", ok);
    });
    $("#toast").addEventListener("click", () => { $("#toast").className = "toast"; });
  }

  // ── sites panel ────────────────────────────────────────────

  let suitesBySite = {};
  let sitesCache = [];

  async function loadSites() {
    const [sitesData, suitesData] = await Promise.all([
      api("/api/pipeline-config"),
      api("/api/suites").catch(() => ({ bySite: {} })), // suite listing is a convenience — don't block Sites on it
    ]);
    suitesBySite = suitesData.bySite || {};
    renderSites(sitesData.sites);
  }

  async function setSite(name, enabled) {
    const data = await postJson("/api/pipeline-config/site", { name, enabled });
    toast(`${name} ${enabled ? "enabled" : "disabled"}`, true);
    renderSites(data.sites);
    loadAudit().catch(() => {});
    return data;
  }

  function renderSites(sites) {
    sitesCache = sites;
    renderPlan();
    const list = $("#sitesList");
    list.innerHTML = "";
    sites.forEach((site) => {
      const row = document.createElement("div");
      row.className = "row";

      const label = document.createElement("div");
      label.className = "row-label";
      const name = document.createElement("span");
      name.className = "row-name";
      name.textContent = site.name;
      label.appendChild(name);
      if (site.type && site.type !== "browser") {
        const tag = document.createElement("span");
        tag.className = "row-tag";
        tag.textContent = site.type;
        label.appendChild(tag);
      }
      row.appendChild(label);

      row.appendChild(makeSwitch(site.enabled, async (next) => {
        await setSite(site.name, next);
      }, `${site.name} site`));

      list.appendChild(row);

      const suiteFiles = suitesBySite[site.name] || [];
      if (suiteFiles.length) {
        list.appendChild(makeSuiteCmdRow(site.name, suiteFiles));
      }
    });
  }

  function makeSuiteCmdRow(siteName, suiteFiles) {
    const row = document.createElement("div");
    row.className = "row row-sub";

    const select = document.createElement("select");
    select.className = "suite-select";
    select.setAttribute("aria-label", `Suite file for ${siteName}`);
    suiteFiles.forEach((file) => {
      const opt = document.createElement("option");
      opt.value = file;
      opt.textContent = file;
      select.appendChild(opt);
    });
    const smokeIndex = suiteFiles.findIndex((f) => f.includes("smoke") && !f.includes("safari"));
    select.selectedIndex = smokeIndex >= 0 ? smokeIndex : 0;

    const btn = document.createElement("button");
    btn.type = "button";
    btn.className = "btn btn-ghost btn-small";
    btn.textContent = "Copy run command";
    btn.addEventListener("click", async () => {
      const cmd = `mvn test -Dsite=${siteName} -DsuiteXmlFile=testng-suites/${select.value}`;
      const ok = await copyToClipboard(cmd);
      toast(ok ? `Copied: ${cmd}` : `Couldn't copy automatically — select it here: ${cmd}`, ok);
    });

    row.appendChild(select);
    row.appendChild(btn);
    return row;
  }

  // ── presets panel ─────────────────────────────────────────

  async function loadPresets() {
    const data = await api("/api/pipeline-config/presets");
    const list = $("#presetsList");
    if (!data.presets || !data.presets.length) {
      list.innerHTML = `<div class="dd-empty">No presets defined (Scripts/dashboard/presets.json is missing or empty).</div>`;
      return;
    }
    const grid = document.createElement("div");
    grid.className = "presets-grid";
    data.presets.forEach((preset) => {
      const btn = document.createElement("button");
      btn.type = "button";
      btn.className = "preset-btn";
      btn.innerHTML = `
        <span class="preset-label">${escapeHtml(preset.label || preset.id)}</span>
        <span class="preset-desc">${escapeHtml(preset.description || "")}</span>
      `;
      btn.addEventListener("click", async () => {
        btn.disabled = true;
        try {
          const data = await postJson("/api/pipeline-config/preset", { id: preset.id });
          renderSites(data.sites);
          const skippedNote = data.skipped && data.skipped.length
            ? ` (skipped unknown: ${data.skipped.join(", ")})` : "";
          toast(`Applied "${preset.label}"${skippedNote}`, true);
          loadAudit().catch(() => {});
        } catch (e) {
          toast(e.message, false);
        } finally {
          btn.disabled = false;
        }
      });
      grid.appendChild(btn);
    });
    list.innerHTML = "";
    list.appendChild(grid);
  }

  // ── tests panel ────────────────────────────────────────────

  let testConfigCache = null;

  async function loadTestConfig() {
    testConfigCache = await api("/api/test-config");
    $("#runOnlyInput").value = testConfigCache.run_only || "";
    renderTestSections();
    renderGroups();
  }

  function renderTestSections() {
    const container = $("#testsSections");
    container.innerHTML = "";
    renderPlan();
    if (!testConfigCache) return;

    const filter = state.testFilter.trim().toLowerCase();

    testConfigCache.sections.forEach((section) => {
      const tests = filter
        ? section.tests.filter((t) => t.name.toLowerCase().includes(filter))
        : section.tests;
      if (filter && tests.length === 0) return;

      const wrap = document.createElement("div");
      wrap.className = "test-section";
      const isOpen = filter ? true : openSections.has(section.title);
      if (isOpen) wrap.classList.add("is-open");

      const head = document.createElement("div");
      head.className = "test-section-head";
      const disabledCount = section.tests.filter((t) => !t.enabled).length;
      head.innerHTML = `
        <button type="button" class="test-section-toggle" aria-expanded="${isOpen}">
          <span class="test-section-caret" aria-hidden="true"></span>
          <span class="test-section-title">${escapeHtml(section.title)}</span>
          <span class="test-section-count">${section.tests.length - disabledCount}/${section.tests.length} on</span>
        </button>
        <span class="test-section-actions">
          <button type="button" class="section-bulk-btn" data-bulk="on">Enable all</button>
          <button type="button" class="section-bulk-btn" data-bulk="off">Disable all</button>
        </span>
      `;
      // With a filter active the person sees only the matching tests, so "all" means those — not the hidden rest.
      head.querySelector('[data-bulk="on"]').addEventListener("click", () => bulkSection(section, true, tests, Boolean(filter)));
      head.querySelector('[data-bulk="off"]').addEventListener("click", () => bulkSection(section, false, tests, Boolean(filter)));
      const toggle = head.querySelector(".test-section-toggle");
      toggle.addEventListener("click", () => {
        if (openSections.has(section.title)) openSections.delete(section.title);
        else openSections.add(section.title);
        const open = wrap.classList.toggle("is-open");
        toggle.setAttribute("aria-expanded", String(open));
      });
      wrap.appendChild(head);

      const rows = document.createElement("div");
      rows.className = "test-section-rows";
      tests.forEach((test) => {
        const row = document.createElement("div");
        row.className = "row";
        const label = document.createElement("div");
        label.className = "row-label";
        const name = document.createElement("span");
        name.className = "row-name";
        name.textContent = test.name;
        label.appendChild(name);
        row.appendChild(label);
        row.appendChild(makeSwitch(test.enabled, async (next) => {
          await postJson("/api/test-config/test", { name: test.name, enabled: next });
          test.enabled = next;
          toast(`${test.name} ${next ? "enabled" : "disabled"}`, true);
          renderTestSections();
          loadAudit().catch(() => {});
        }, `${test.name} test`));
        rows.appendChild(row);
      });
      wrap.appendChild(rows);
      container.appendChild(wrap);
    });
  }

  async function bulkSection(section, enabled, scope, filtered) {
    const names = (scope || section.tests).map((t) => t.name);
    if (!names.length) { toast("No tests to change here.", false); return; }
    const what = filtered ? `${names.length} matching test${names.length === 1 ? "" : "s"}` : `all ${names.length} tests`;
    if (!enabled && !confirm(`Disable ${what} in "${section.title}"?`)) {
      return;
    }
    try {
      testConfigCache = await postJson("/api/test-config/bulk-section", { names, enabled });
      toast(`${section.title}: ${enabled ? "enabled" : "disabled"} ${what}`, true);
      openSections.add(section.title);
      renderTestSections();
      loadAudit().catch(() => {});
    } catch (e) {
      toast(e.message, false);
    }
  }

  async function saveRunOnly() {
    const value = $("#runOnlyInput").value;
    const status = $("#runOnlyStatus");
    try {
      const data = await postJson("/api/test-config/run-only", { value });
      testConfigCache = data;
      status.textContent = data.run_only
        ? `Restricted to: ${data.run_only}`
        : "No restriction — every enabled test runs.";
      toast("run.only saved", true);
      loadAudit().catch(() => {});
    } catch (e) {
      status.textContent = e.message;
      toast(e.message, false);
    }
  }

  // ── groups panel ───────────────────────────────────────────

  function renderGroups() {
    const list = $("#groupsList");
    list.innerHTML = "";
    renderPlan();
    const groups = (testConfigCache && testConfigCache.groups) || [];
    if (!groups.length) {
      const empty = document.createElement("div");
      empty.className = "dd-empty";
      empty.textContent = "No groups switched off. Add one below to disable every test carrying that group.";
      list.appendChild(empty);
      return;
    }
    groups.forEach((group) => {
      const row = document.createElement("div");
      row.className = "row";
      row.innerHTML = `<div class="row-label"><span class="row-name">${escapeHtml(group.name)}</span></div>`;
      row.appendChild(makeSwitch(group.enabled, async (next) => {
        const data = await postJson("/api/test-config/group", { name: group.name, enabled: next });
        testConfigCache = data;
        toast(`group '${group.name}' ${next ? "enabled" : "disabled"}`, true);
        renderGroups();
        loadAudit().catch(() => {});
      }, `${group.name} group`));
      list.appendChild(row);
    });
  }

  async function addGroupOff() {
    const input = $("#newGroupInput");
    const name = input.value.trim();
    if (!name) return;
    try {
      testConfigCache = await postJson("/api/test-config/group", { name, enabled: false });
      input.value = "";
      renderGroups();
      toast(`group '${name}' disabled`, true);
      loadAudit().catch(() => {});
    } catch (e) {
      toast(e.message, false);
    }
  }

  // ── results panel ──────────────────────────────────────────

  let lastByClass = [];

  let lastDetails = { duration_s: 0, class_time: {}, slowest: [], failure_details: [] };
  let lastData = null;
  let lastSf = null;

  function statTile(value, label, cls, sub) {
    const div = document.createElement("div");
    div.className = `stat ${cls || ""}`;
    const v = document.createElement("span");
    v.className = "stat-value";
    v.textContent = String(value);
    const l = document.createElement("span");
    l.className = "stat-label";
    l.textContent = label;
    div.append(v, l);
    if (sub) {
      const d = document.createElement("span");
      d.className = `stat-delta ${sub.cls || ""}`;
      d.textContent = sub.text;
      div.appendChild(d);
    }
    return div;
  }

  function fmtDuration(sec) {
    if (typeof sec !== "number" || !isFinite(sec)) return "–";
    if (sec <= 0) return "0s";
    if (sec < 1) return "<1s";
    if (sec < 60) return `${sec.toFixed(sec < 10 ? 1 : 0)}s`;
    const m = Math.floor(sec / 60), r = Math.round(sec % 60);
    return m >= 60 ? `${Math.floor(m / 60)}h ${String(m % 60).padStart(2, "0")}m` : `${m}m ${String(r).padStart(2, "0")}s`;
  }

  function timeAgo(epochSec) {
    if (!epochSec) return "never";
    const d = Math.max(0, Math.floor(Date.now() / 1000) - epochSec);
    if (d < 45) return "just now";
    if (d < 3600) return `${Math.round(d / 60)} min ago`;
    if (d < 86400) return `${Math.round(d / 3600)} h ago`;
    return `${Math.round(d / 86400)} d ago`;
  }

  // surefire's class column can be a simple or a fully-qualified name depending on the runner; match both.
  function timeFor(cls) {
    const t = lastDetails.class_time || {};
    if (cls in t) return t[cls];
    const hit = Object.keys(t).find((k) => k.endsWith("." + cls) || cls.endsWith("." + k));
    return hit === undefined ? null : t[hit];
  }

  async function loadResults() {
    const data = await api("/api/results");
    lastData = data;
    lastDetails = Object.assign({ duration_s: 0, class_time: {}, slowest: [], failure_details: [] }, data.details || {});
    const hero = $("#heroRow");
    hero.innerHTML = "";
    const sf = data.surefire || { total: 0, passed: 0, failed: 0, skipped: 0 };
    lastSf = sf;
    const hist = trendHistory(data, sf);

    const rate = sf.total ? Math.round((1000 * sf.passed) / sf.total) / 10 : null;
    let delta = null;
    if (hist.length >= 2) {
      const d = Math.round((hist[hist.length - 1].rate - hist[hist.length - 2].rate) * 10) / 10;
      delta = { text: d === 0 ? "no change vs previous" : `${d > 0 ? "▲" : "▼"} ${Math.abs(d)} pts vs previous`, cls: d > 0 ? "is-pass" : d < 0 ? "is-fail" : "" };
    }

    hero.appendChild(statTile(sf.total, "tests run", ""));
    if (rate !== null) hero.appendChild(statTile(`${rate}%`, "pass rate", rate >= 95 ? "is-pass" : rate >= 80 ? "is-warn" : "is-fail", delta));
    hero.appendChild(statTile(sf.passed, "passed", "is-pass"));
    hero.appendChild(statTile(sf.failed, sf.failed === 1 ? "failure" : "failures", sf.failed > 0 ? "is-fail" : ""));
    hero.appendChild(statTile(sf.skipped, "skipped", sf.skipped > 0 ? "is-warn" : ""));
    if (lastDetails.duration_s > 0) hero.appendChild(statTile(fmtDuration(lastDetails.duration_s), "run time", ""));

    if (data.coverage && typeof data.coverage.line_pct === "number") {
      hero.appendChild(statTile(`${data.coverage.line_pct}%`, "core coverage",
        data.coverage.passed === false ? "is-warn" : ""));
    }
    const healing = data.self_healing;
    if (healing && typeof healing.total === "number") {
      hero.appendChild(statTile(healing.total, "self-healed", healing.total > 0 ? "is-warn" : ""));
    }
    const flakyCount = data.flaky && typeof data.flaky.flaky_count === "number" ? data.flaky.flaky_count : null;
    if (flakyCount !== null) {
      hero.appendChild(statTile(flakyCount, "flaky", flakyCount > 0 ? "is-warn" : ""));
    }
    if (data.tia && typeof data.tia.impacted_count === "number") {
      hero.appendChild(statTile(
        `${data.tia.impacted_count}/${data.tia.total_test_classes}`, "TIA selected", ""
      ));
    }

    const note = $("#heroNote");
    note.textContent = sf.total === 0
      ? "No test results yet — run mvn test, then hit Refresh."
      : `${sf.total} test${sf.total === 1 ? "" : "s"} from the last local run.`;

    if (sf.note) note.textContent = `${note.textContent} ${sf.note}`.trim();
    lastByClass = (sf.by_class || []).map((c) => Object.assign({}, c, { time: timeFor(c.class) }));
    renderSummary(sf, hist);
    renderResultsTable(lastByClass);
    $("#resultsUpdated").textContent = `Updated ${new Date().toLocaleTimeString()}`;
    renderHealing(healing);
    renderFlaky(data.flaky, data.stability);
    renderFailures(sf);
    renderClusters(sf);
    renderAttention(sf);
    renderSlowest();
    renderTrend(hist);
    lastHist = Array.isArray(data.history) ? data.history : [];
    renderDuration(lastHist);
    renderCompareChoices(lastHist);
    renderAlerts();
    const badge = $("#failBadge");
    badge.hidden = !(sf.failed > 0);
    badge.textContent = String(sf.failed);
  }

  // ── enhanced results view ──────────────────────────────────
  // Charts are SVG built with setAttribute (presentation attributes are CSP-safe; inline style="" is not).

  const view = { status: "all", query: "", sortKey: "failed", sortDir: -1 };
  const HISTORY_KEY = "dashboard.passHistory.v1";
  const SVG_NS = "http://www.w3.org/2000/svg";

  function svgEl(name, attrs) {
    const el = document.createElementNS(SVG_NS, name);
    Object.entries(attrs || {}).forEach(([k, v]) => el.setAttribute(k, String(v)));
    return el;
  }

  function readHistory() {
    try {
      const h = JSON.parse(localStorage.getItem(HISTORY_KEY) || "[]");
      // Older builds stored milliseconds; the server (and this UI) use epoch SECONDS. Normalise so the date
      // shown for a run is never in the year 50000.
      return Array.isArray(h)
        ? h.filter((x) => x && typeof x.rate === "number").map((x) => (x.t > 1e11 ? Object.assign({}, x, { t: Math.floor(x.t / 1000) }) : x))
        : [];
    } catch (e) { return []; }
  }

  // One entry per distinct result set, so polling an unchanged run never inflates the trend.
  function recordHistory(sf) {
    if (!sf || !sf.total) return readHistory();
    const hist = readHistory();
    const sig = `${sf.total}/${sf.passed}/${sf.failed}/${sf.skipped}`;
    if (!hist.length || hist[hist.length - 1].sig !== sig) {
      hist.push({ t: Math.floor(Date.now() / 1000), sig, rate: Math.round((1000 * sf.passed) / sf.total) / 10, failed: sf.failed });
      try { localStorage.setItem(HISTORY_KEY, JSON.stringify(hist.slice(-30))); } catch (e) { /* private mode */ }
    }
    return hist.slice(-30);
  }

  // The server keeps the history (shared by every browser, survives clearing site data). The browser-local
  // copy is only a fallback for a dashboard server that predates /api/results "history".
  function trendHistory(data, sf) {
    if (Array.isArray(data.history)) {
      return data.history.filter((h) => h && typeof h.rate === "number").slice(-30);
    }
    return recordHistory(sf);
  }

  function donut(sf) {
    const svg = svgEl("svg", { viewBox: "0 0 100 100", class: "donut", role: "img",
      "aria-label": `${sf.passed} passed, ${sf.failed} failed, ${sf.skipped} skipped` });
    svg.appendChild(svgEl("circle", { cx: 50, cy: 50, r: 38, class: "donut-track", fill: "none", "stroke-width": 12 }));
    let offset = 0;
    [["pass", sf.passed], ["fail", sf.failed], ["skip", sf.skipped]].forEach(([cls, n]) => {
      if (!n || !sf.total) return;
      const len = (100 * n) / sf.total;
      svg.appendChild(svgEl("circle", { cx: 50, cy: 50, r: 38, fill: "none", "stroke-width": 12, class: `seg-${cls}`,
        pathLength: 100, "stroke-dasharray": `${len} ${100 - len}`, "stroke-dashoffset": -offset, transform: "rotate(-90 50 50)" }));
      offset += len;
    });
    const rate = sf.total ? Math.round((1000 * sf.passed) / sf.total) / 10 : 0;
    const t = svgEl("text", { x: 50, y: 55, "text-anchor": "middle", class: "donut-text" });
    t.textContent = `${rate}%`;
    svg.appendChild(t);
    return svg;
  }

  function sparkline(hist) {
    const wrap = document.createElement("div");
    wrap.className = "trend";
    if (hist.length < 2) {
      wrap.innerHTML = '<span class="field-note">Pass-rate trend appears after two runs.</span>';
      return wrap;
    }
    const W = 160, H = 40, pad = 4;
    const pts = hist.map((h, i) => [pad + (i * (W - 2 * pad)) / (hist.length - 1), pad + ((100 - h.rate) * (H - 2 * pad)) / 100]);
    const svg = svgEl("svg", { viewBox: `0 0 ${W} ${H}`, class: "spark", role: "img",
      "aria-label": `Pass rate over the last ${hist.length} runs, now ${hist[hist.length - 1].rate}%` });
    svg.appendChild(svgEl("polyline", { points: pts.map((p) => p.join(",")).join(" "), fill: "none", class: "spark-line", "stroke-width": 2 }));
    const last = pts[pts.length - 1];
    svg.appendChild(svgEl("circle", { cx: last[0], cy: last[1], r: 3, class: "spark-dot" }));
    wrap.appendChild(svg);
    const delta = Math.round((hist[hist.length - 1].rate - hist[hist.length - 2].rate) * 10) / 10;
    const first = hist[0].t;
    if (typeof first === "number") {
      wrap.title = `Last ${hist.length} runs, since ${new Date(first * 1000).toLocaleDateString()}`;
    }
    const lab = document.createElement("span");
    lab.className = `trend-delta ${delta < 0 ? "is-fail" : delta > 0 ? "is-pass" : ""}`;
    lab.textContent = delta === 0 ? "no change" : `${delta > 0 ? "▲" : "▼"} ${Math.abs(delta)} pts vs previous run`;
    wrap.appendChild(lab);
    return wrap;
  }

  function renderSummary(sf, hist) {
    const box = $("#resultsSummary");
    box.innerHTML = "";
    if (!sf.total) return;
    box.appendChild(donut(sf));
    const meta = document.createElement("div");
    meta.className = "summary-meta";
    meta.innerHTML = `<div><strong>${sf.passed}</strong> passed · <strong class="${sf.failed ? "txt-fail" : ""}">${sf.failed}</strong> failed · <strong>${sf.skipped}</strong> skipped</div>`;
    meta.appendChild(sparkline(hist));
    box.appendChild(meta);
  }

  function visibleRows(byClass) {
    const q = view.query.trim().toLowerCase();
    const rows = byClass.filter((c) =>
      (view.status === "all" || (view.status === "failing" ? c.failed > 0 : c.failed === 0)) &&
      (!q || c.class.toLowerCase().includes(q)));
    const k = view.sortKey;
    return rows.sort((x, y) => {
      const a = k === "class" ? x.class.toLowerCase() : (x[k] == null ? -1 : x[k]);
      const b = k === "class" ? y.class.toLowerCase() : (y[k] == null ? -1 : y[k]);
      return (a < b ? -1 : a > b ? 1 : x.class.localeCompare(y.class)) * view.sortDir;
    });
  }

  function barSvg(c) {
    const W = 120;
    const svg = svgEl("svg", { viewBox: `0 0 ${W} 8`, class: "rowbar", width: W, height: 8, "aria-hidden": "true" });
    if (!c.total) return svg.outerHTML;
    let x = 0;
    [["pass", c.passed], ["fail", c.failed], ["skip", c.skipped]].forEach(([cls, n]) => {
      if (!n) return;
      const w = (W * n) / c.total;
      svg.appendChild(svgEl("rect", { x, y: 0, width: w, height: 8, class: `bar-${cls}` }));
      x += w;
    });
    return svg.outerHTML;
  }

  function renderResultsTable(byClass) {
    syncViewUrl();
    const container = $("#resultsTable");
    if (!byClass.length) {
      container.innerHTML = `<div class="results-empty">Nothing here yet — run tests locally, then Refresh.</div>`;
      return;
    }
    const rows = visibleRows(byClass);
    if (!rows.length) {
      container.innerHTML = `<div class="results-empty">No classes match this filter.</div>`;
      return;
    }
    const th = (key, label, num) => {
      const on = view.sortKey === key;
      const arrow = on ? (view.sortDir < 0 ? " ▼" : " ▲") : "";
      return `<th class="${num ? "num " : ""}sortable" data-sort="${key}" tabindex="0" role="columnheader" aria-sort="${on ? (view.sortDir < 0 ? "descending" : "ascending") : "none"}">${label}${arrow}</th>`;
    };
    const body = rows.map((c) => `
      <tr>
        <td><span class="dot ${c.failed > 0 ? "fail" : "pass"}"></span><span class="mono">${escapeHtml(c.class)}</span></td>
        <td class="num">${c.total}</td><td class="num">${c.passed}</td><td class="num">${c.failed}</td><td class="num">${c.skipped}</td>
        <td class="num">${c.time == null ? "–" : c.time.toFixed(1)}</td>
        <td>${barSvg(c)}</td>
      </tr>`).join("");
    container.innerHTML = `
      <div class="table-scroll"><table class="results"><caption class="sr-only">Test results by class</caption>
        <thead><tr>${th("class", "Class")}${th("total", "Total", 1)}${th("passed", "Passed", 1)}${th("failed", "Failed", 1)}${th("skipped", "Skipped", 1)}${th("time", "Time (s)", 1)}<th>Mix</th></tr></thead>
        <tbody>${body}</tbody>
      </table></div>
      <p class="field-note table-count">Showing ${rows.length} of ${byClass.length} classes</p>`;
  }

  function setSort(key) {
    if (view.sortKey === key) view.sortDir *= -1;
    else { view.sortKey = key; view.sortDir = key === "class" ? 1 : -1; }
    renderResultsTable(lastByClass);
  }

  function wireResultsView() {
    $("#resultsTable").addEventListener("click", (e) => {
      const h = e.target.closest("th[data-sort]");
      if (h) setSort(h.dataset.sort);
    });
    $("#resultsTable").addEventListener("keydown", (e) => {
      const h = e.target.closest("th[data-sort]");
      if (h && (e.key === "Enter" || e.key === " ")) { e.preventDefault(); setSort(h.dataset.sort); }
    });
    document.querySelectorAll(".seg-btn").forEach((b) => b.addEventListener("click", () => {
      view.status = b.dataset.status;
      document.querySelectorAll(".seg-btn").forEach((x) => {
        x.classList.toggle("is-on", x === b);
        x.setAttribute("aria-pressed", x === b ? "true" : "false");
      });
      renderResultsTable(lastByClass);
    }));
    $("#resultsSearch").addEventListener("input", (e) => { view.query = e.target.value; renderResultsTable(lastByClass); });
    const auto = $("#autoRefresh");
    try { auto.checked = localStorage.getItem("dashboard.autoRefresh") !== "off"; } catch (e) { /* ignore */ }
    auto.addEventListener("change", () => {
      try { localStorage.setItem("dashboard.autoRefresh", auto.checked ? "on" : "off"); } catch (e) { /* ignore */ }
    });
    document.addEventListener("keydown", (e) => {
      const tag = (e.target.tagName || "").toLowerCase();
      if (openModalEl() || e.ctrlKey || e.metaKey || e.altKey || tag === "input" || tag === "textarea" || tag === "select" || e.target.isContentEditable) return;
      if (e.key === "/") { e.preventDefault(); go("results"); $("#resultsSearch").focus(); }
      else if (e.key === "r") { loadAll(); }
      else if (e.key === "?") { e.preventDefault(); showShortcuts(); }
    });
    document.addEventListener("visibilitychange", () => {
      if (!document.hidden && poll.every > 0 && auto.checked) { poll.now(); }
    });
  }

  function exportResultsCsv() {
    if (!lastByClass.length) {
      toast("Nothing to export yet.", false);
      return;
    }
    const header = "class,total,passed,failed,skipped,time_s";
    // RFC 4180 quoting, plus a leading ' on cells a spreadsheet would run as a formula (= + - @).
    const cell = (v) => {
      let t = String(v);
      if (/^[=+\-@\t\r]/.test(t)) t = "'" + t;
      return /[",\n\r]/.test(t) ? '"' + t.replace(/"/g, '""') + '"' : t;
    };
    const lines = visibleRows(lastByClass).map((c) => [c.class, c.total, c.passed, c.failed, c.skipped, c.time == null ? "" : c.time].map(cell).join(","));
    const csv = [header, ...lines].join("\n");
    const blob = new Blob([csv], { type: "text/csv;charset=utf-8" });
    const url = URL.createObjectURL(blob);
    const a = document.createElement("a");
    a.href = url;
    a.download = `test-results-${new Date().toISOString().slice(0, 19).replace(/[:T]/g, "-")}.csv`;
    document.body.appendChild(a);
    a.click();
    a.remove();
    URL.revokeObjectURL(url);
  }

  // ── drilldown: self-healing / flaky ──────────────────────────

  function renderHealing(healing) {
    const container = $("#healingList");
    const events = healing && Array.isArray(healing.events) ? healing.events : [];
    if (!events.length) {
      container.innerHTML = `<div class="dd-empty">No self-healed locators in the last run.</div>`;
      return;
    }
    container.innerHTML = events.map((e) => `
      <div class="dd-row">
        <div class="dd-row-top">
          <span class="dd-title">${escapeHtml(e.original || "?")}<span class="dd-arrow">→</span>${escapeHtml(e.healedTo || "?")}</span>
        </div>
        <div class="dd-meta">${escapeHtml(e.site || "")}${e.stage ? " · " + escapeHtml(e.stage) : ""}</div>
      </div>
    `).join("");
  }

  const KIND_LABEL = { flaky: "flaky", new: "new failure", consistent: "always failing", recovered: "recovered" };

  function renderFlaky(flaky, stability) {
    const container = $("#flakyList");
    container.innerHTML = "";
    let items = Array.isArray(stability) ? stability : [];
    if (!items.length && flaky && Array.isArray(flaky.flaky)) {
      // Older server without /api/results "stability": show the CI list as it always was.
      items = flaky.flaky.map((t) => ({ test: t.test || "", kind: "flaky", fails: t.fail_count, runs: (t.pass_count || 0) + (t.fail_count || 0), flips: 0, recent: t.recent_statuses || [], source: "ci" }));
    }
    if (!items.length) {
      container.appendChild(emptyState("No unstable tests yet. Once a test has failed in a recorded run it shows up here with its history."));
      return;
    }
    items.forEach((t, i) => {
      const row = document.createElement("div");
      row.className = "dd-row stab-row";
      const top = document.createElement("div");
      top.className = "dd-row-top";
      const title = document.createElement("div");
      title.className = "dd-title";
      const rank = document.createElement("span");
      rank.className = "stab-rank";
      rank.textContent = String(i + 1);
      const tag = document.createElement("span");
      tag.className = `stab-kind is-${KIND_LABEL[t.kind] ? t.kind : "new"}`;
      tag.textContent = KIND_LABEL[t.kind] || t.kind;
      const name = document.createElement("span");
      name.className = "stab-name";
      name.textContent = t.test || "";
      title.append(rank, tag, name);
      const ticks = document.createElement("span");
      ticks.className = "dd-sparkline";
      (t.recent || []).forEach((st) => {
        const k = document.createElement("span");
        k.className = `dd-tick ${st === "pass" ? "pass" : st === "fail" ? "fail" : "skip"}`;
        k.title = st;
        ticks.appendChild(k);
      });
      const more = document.createElement("button");
      more.type = "button";
      more.className = "btn btn-ghost btn-small";
      more.textContent = "History";
      more.setAttribute("aria-label", `Open the history of ${t.test || "this test"}`);
      more.addEventListener("click", () => openTestDrawer(t.test));
      top.append(title, ticks, more);
      const meta = document.createElement("div");
      meta.className = "dd-stat-row";
      const runs = t.runs ? `failed ${t.fails} of ${t.runs} run${t.runs === 1 ? "" : "s"}` : `${t.fails} failure${t.fails === 1 ? "" : "s"}`;
      meta.textContent = `${runs}${t.flips ? ` · ${t.flips} flip${t.flips === 1 ? "" : "s"}` : ""} · ${t.source === "ci" ? "CI history" : "this Console's history"}`;
      row.append(top, meta);
      container.appendChild(row);
    });
  }

  // ── reports ─────────────────────────────────────────────────

  function renderReports(sys) {
    const box = $("#reportsList");
    if (!box) return;
    box.innerHTML = "";
    const reports = (sys && sys.reports) || [];
    if (!reports.length) { box.appendChild(emptyState("No report information from this server.")); return; }
    reports.forEach((r) => {
      const row = document.createElement("div");
      row.className = "report-row";
      const dot = document.createElement("span");
      dot.className = `report-dot${r.present ? " is-on" : ""}`;
      dot.setAttribute("aria-hidden", "true");
      const info = document.createElement("div");
      info.className = "report-info";
      const n = document.createElement("div");
      n.className = "report-name";
      n.textContent = r.name;
      const p = document.createElement("div");
      p.className = "report-path mono";
      p.textContent = r.present ? `${r.path} · updated ${timeAgo(r.modified)}` : `${r.path} · not generated yet`;
      info.append(n, p);
      row.append(dot, info);
      if (r.present && r.open_hint) {
        const b = document.createElement("button");
        b.type = "button";
        b.className = "btn btn-ghost btn-small";
        b.textContent = "Copy how to open";
        b.addEventListener("click", async () => {
          const ok = await copyToClipboard(r.open_hint);
          toast(ok ? `Copied: ${r.open_hint}` : `Couldn't copy automatically — ${r.open_hint}`, ok);
        });
        row.appendChild(b);
      }
      box.appendChild(row);
    });
    const foot = $("#reportsFoot");
    const pub = sys && sys.reports_url;
    foot.hidden = !pub;
    if (pub) {
      $("#reportsPublished").href = pub.url;
      $("#reportsPublishedNote").textContent = pub.source === "env"
        ? "Address set by DASHBOARD_REPORTS_URL."
        : "Guessed from your GitHub remote — works if GitHub Pages is enabled for the repo.";
    }
  }

  // ── audit log ─────────────────────────────────────────────

  function fmtWhen(iso) {
    if (!iso) return "";
    const d = new Date(iso);
    if (isNaN(d.getTime())) return escapeHtml(String(iso));
    return d.toLocaleString(undefined, {
      month: "short", day: "numeric", hour: "2-digit", minute: "2-digit",
    });
  }

  function describeAuditEntry(e) {
    const from = e.old_value === null || e.old_value === undefined ? "(none)" : e.old_value;
    switch (e.action) {
      case "site": return `site <b>${escapeHtml(e.target)}</b>: ${escapeHtml(from)} → ${escapeHtml(e.new_value)}`;
      case "test": return `test <b>${escapeHtml(e.target)}</b>: ${escapeHtml(from)} → ${escapeHtml(e.new_value)}`;
      case "test-bulk": return `bulk (${escapeHtml(e.target)}) → ${escapeHtml(e.new_value)}`;
      case "group": return `group <b>${escapeHtml(e.target)}</b>: ${escapeHtml(from)} → ${escapeHtml(e.new_value)}`;
      case "run-only": return `run.only: ${escapeHtml(from || "(blank)")} → ${escapeHtml(e.new_value || "(blank)")}`;
      case "preset": return `preset <b>${escapeHtml(e.target)}</b> applied`;
      default: return `${escapeHtml(e.action)} ${escapeHtml(e.target)}`;
    }
  }

  async function loadAudit() {
    const data = await api("/api/audit");
    const list = $("#auditList");
    if (!data.entries || !data.entries.length) {
      list.innerHTML = `<div class="dd-empty">No changes made through this dashboard yet.</div>`;
      return;
    }
    list.innerHTML = data.entries.map((e) => `
      <div class="audit-row">
        <span class="audit-when">${fmtWhen(e.at)}</span>
        <span class="audit-what">${describeAuditEntry(e)}</span>
        <span class="audit-actor">${escapeHtml(e.actor || "")}</span>
      </div>
    `).join("");
  }

  async function undoLast() {
    if (!confirm("Undo the most recent dashboard change? This reverts one step back.")) {
      return;
    }
    try {
      const data = await postJson("/api/audit/undo", {});
      renderSites(data.sites);
      testConfigCache = data.test_config;
      renderTestSections();
      renderGroups();
      $("#runOnlyInput").value = testConfigCache.run_only || "";
      toast("Reverted last change.", true);
      loadAudit().catch(() => {});
    } catch (e) {
      toast(e.message, false);
    }
  }

  // ── snapshot export / import ─────────────────────────────

  function exportSnapshot() {
    // A plain link download (not fetch+blob) so the browser's native
    // Basic-Auth credentials are reused automatically — a fetch() would
    // need to re-send the Authorization header by hand for no benefit.
    const a = document.createElement("a");
    a.href = "/api/snapshot";
    a.download = "dashboard-snapshot.json";
    document.body.appendChild(a);
    a.click();
    a.remove();
  }

  function importSnapshot(file) {
    const reader = new FileReader();
    reader.onload = async () => {
      let parsed;
      try {
        parsed = JSON.parse(reader.result);
      } catch (e) {
        toast("That file isn't valid JSON.", false);
        return;
      }
      try {
        const data = await postJson("/api/snapshot/import", parsed);
        toast(`Imported: ${data.applied.sites} site(s), ${data.applied.tests} test(s), ${data.applied.groups} group(s)`, true);
        if (data.problems && data.problems.length) {
          toast(`${data.problems.length} entr${data.problems.length === 1 ? "y" : "ies"} skipped — see console`, false);
          console.warn("Snapshot import — skipped entries:", data.problems);
        }
        renderSites(data.sites);
        testConfigCache = data.test_config;
        renderTestSections();
        renderGroups();
        $("#runOnlyInput").value = testConfigCache.run_only || "";
        loadAudit().catch(() => {});
      } catch (e) {
        toast(e.message, false);
      }
    };
    reader.readAsText(file);
  }

  // ── navigation (hash-routed tabs) ───────────────────────────

  const TABS = { overview: "Overview", config: "Configuration", results: "Results", activity: "Activity" };

  function currentTab() {
    const h = (location.hash || "").slice(1);
    return Object.prototype.hasOwnProperty.call(TABS, h) ? h : "overview";
  }

  function showTab() {
    const t = currentTab();
    document.querySelectorAll(".view").forEach((v) => { v.hidden = v.dataset.view !== t; });
    document.querySelectorAll(".tab").forEach((a) => {
      if (a.dataset.tab === t) a.setAttribute("aria-current", "page"); else a.removeAttribute("aria-current");
    });
    document.title = `${TABS[t]} · Automation Console`;
  }

  function go(tab) {
    if (location.hash === `#${tab}`) showTab(); else location.hash = tab;
  }

  // ── overview + results cards ───────────────────────────────

  function fmtDate(sec) {
    return typeof sec === "number" ? new Date(sec * 1000).toLocaleString(undefined, { month: "short", day: "numeric", hour: "2-digit", minute: "2-digit" }) : "";
  }

  function failureItems(sf) {
    const d = lastDetails.failure_details || [];
    if (d.length) return d;
    // Older reports without per-case XML: fall back to the "class.method" names the summary already has.
    return (sf.failures || []).map((k) => {
      const i = k.lastIndexOf(".");
      return { class: i > 0 ? k.slice(0, i) : "", name: i > 0 ? k.slice(i + 1) : k, kind: "failure", type: "", message: "" };
    });
  }

  function okState(title, sub) {
    const div = document.createElement("div");
    div.className = "ok-state";
    div.innerHTML = `<span class="ok-mark" aria-hidden="true">✓</span><span>${escapeHtml(title)}<small>${escapeHtml(sub)}</small></span>`;
    return div;
  }

  function emptyState(text) {
    const div = document.createElement("div");
    div.className = "dd-empty";
    div.textContent = text;
    return div;
  }

  function renderFailures(sf) {
    const box = $("#failuresList");
    box.innerHTML = "";
    const items = failureItems(sf);
    $("#failuresSub").textContent = items.length
      ? `${items.length}${sf.failed > items.length ? ` of ${sf.failed}` : ""} failing test case${items.length === 1 ? "" : "s"} from the last run. Expand one for its error.`
      : "Failing test cases from the last run.";
    $("#failuresFoot").hidden = !items.length;
    if (!sf.total) { box.appendChild(emptyState("No test results yet — run mvn test, then Refresh.")); return; }
    if (!items.length) { box.appendChild(okState("No failures", "Every test in the last run passed or was skipped.")); return; }
    items.forEach((f, i) => {
      const d = document.createElement("details");
      d.className = "fail-detail";
      if (items.length === 1 || i === 0) d.open = items.length <= 3;
      const sum = document.createElement("summary");
      const tag = document.createElement("span");
      tag.className = `kind-tag${f.kind === "error" ? " is-error" : ""}`;
      tag.textContent = f.kind || "failure";
      const name = document.createElement("span");
      name.className = "fail-name";
      name.textContent = f.name;
      const cls = document.createElement("span");
      cls.className = "fail-class";
      cls.textContent = f.class;
      sum.append(tag, name, cls);
      const body = document.createElement("div");
      body.className = "fail-body";
      if (f.type) {
        const t = document.createElement("div");
        t.className = "fail-class";
        t.textContent = f.type;
        body.appendChild(t);
      }
      const pre = document.createElement("pre");
      pre.textContent = f.message || "(no message recorded in the report)";
      body.appendChild(pre);
      d.append(sum, body);
      box.appendChild(d);
    });
  }

  function renderAttention(sf) {
    const box = $("#attentionList");
    box.innerHTML = "";
    if (!sf.total) { box.appendChild(emptyState("No test results yet — run mvn test, then Refresh.")); return; }
    const items = failureItems(sf);
    if (!sf.failed && !items.length) { box.appendChild(okState("All clear", "No failing tests in the last run.")); return; }
    items.slice(0, 5).forEach((f) => {
      const row = document.createElement("div");
      row.className = "fail-item";
      const n = document.createElement("div");
      n.className = "fail-name";
      n.textContent = f.name;
      const c = document.createElement("div");
      c.className = "fail-class";
      c.textContent = f.class;
      row.append(n, c);
      if (f.message) {
        const m = document.createElement("p");
        m.className = "fail-msg";
        m.textContent = f.message;
        row.appendChild(m);
      }
      box.appendChild(row);
    });
    const more = Math.max(sf.failed, items.length) - Math.min(5, items.length);
    if (more > 0) box.appendChild(emptyState(`+ ${more} more — see Results`));
  }

  function renderSlowest() {
    const box = $("#slowList");
    box.innerHTML = "";
    const rows = (lastDetails.slowest || []).slice(0, 6);
    if (!rows.length) { box.appendChild(emptyState("No timing data yet — it comes from target/surefire-reports.")); return; }
    const max = Math.max(...rows.map((r) => r.seconds)) || 1;
    rows.forEach((r) => {
      const row = document.createElement("div");
      row.className = "slow-row";
      const top = document.createElement("div");
      top.className = "slow-top";
      const name = document.createElement("span");
      name.className = "slow-name";
      name.textContent = r.name;
      name.title = r.class;
      const time = document.createElement("span");
      time.className = "slow-time";
      time.textContent = fmtDuration(r.seconds);
      top.append(name, time);
      const svg = svgEl("svg", { viewBox: "0 0 100 4", preserveAspectRatio: "none", class: "slow-bar", "aria-hidden": "true" });
      svg.appendChild(svgEl("rect", { x: 0, y: 0, width: 100, height: 4, class: "bar-bg" }));
      svg.appendChild(svgEl("rect", { x: 0, y: 0, width: Math.max(1, (100 * r.seconds) / max), height: 4, class: "bar-fg" }));
      row.append(top, svg);
      box.appendChild(row);
    });
  }

  // ── failure clusters ────────────────────────────────────────
  // Twenty failures are usually two or three broken things. Group by the exception type plus the first line of
  // the message with the volatile bits (numbers, ids, quoted values, hex) masked, so the same root cause lands in
  // one row. Pure grouping of data the server already sends — nothing is hidden, every test is still listed.

  function clusterKey(f) {
    const first = String(f.message || "").split("\n").map((l) => l.trim()).find(Boolean) || "";
    const masked = first
      .replace(/\b[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}\b/gi, "<id>")
      .replace(/0x[0-9a-f]+/gi, "<hex>")
      .replace(/"[^"]*"|'[^']*'/g, "<str>")
      .replace(/\d+(?:\.\d+)?/g, "<n>")
      .replace(/\s+/g, " ")
      .slice(0, 160);
    return `${f.type || ""}|${masked}`;
  }

  function clusterFailures(items) {
    const groups = new Map();
    items.forEach((f) => {
      const key = clusterKey(f);
      if (!groups.has(key)) {
        const first = String(f.message || "").split("\n").map((l) => l.trim()).find(Boolean);
        groups.set(key, { key, type: f.type || "", label: (first || f.type || "(no message recorded)").slice(0, 220), items: [] });
      }
      groups.get(key).items.push(f);
    });
    return Array.from(groups.values()).sort((a, b) => b.items.length - a.items.length || a.label.localeCompare(b.label));
  }

  function renderClusters(sf) {
    const panel = $("#clustersPanel");
    const box = $("#clustersList");
    box.innerHTML = "";
    const items = failureItems(sf);
    // One failure (or none) has nothing to group; the Failures panel below already says it all.
    if (items.length < 2) { panel.hidden = true; return; }
    const clusters = clusterFailures(items);
    panel.hidden = false;
    $("#clustersSub").textContent = clusters.length === items.length
      ? `${items.length} failing tests, each with a different error.`
      : `${items.length} failing tests share ${clusters.length} distinct root cause${clusters.length === 1 ? "" : "s"}.`;
    clusters.forEach((c) => {
      const d = document.createElement("details");
      d.className = "fail-detail cluster";
      const sum = document.createElement("summary");
      const count = document.createElement("span");
      count.className = "cluster-count";
      count.textContent = `×${c.items.length}`;
      const label = document.createElement("span");
      label.className = "fail-name";
      label.textContent = c.label;
      sum.append(count, label);
      const body = document.createElement("div");
      body.className = "fail-body";
      if (c.type) {
        const t = document.createElement("div");
        t.className = "fail-class";
        t.textContent = c.type;
        body.appendChild(t);
      }
      const ul = document.createElement("ul");
      ul.className = "cluster-tests";
      c.items.forEach((f) => {
        const li = document.createElement("li");
        li.className = "mono";
        li.textContent = f.class ? `${f.class}.${f.name}` : f.name;
        ul.appendChild(li);
      });
      body.appendChild(ul);
      d.append(sum, body);
      box.appendChild(d);
    });
  }

  // ── re-run only the failures ────────────────────────────────
  // Surefire's -Dtest takes "Class#method+method,Other". Names carrying data-provider/parameter suffixes ("login[2]")
  // or anything unusual are widened to the whole class rather than emitting a selector that matches nothing.

  function buildRerunCommand(items) {
    const byClass = new Map();
    items.forEach((f) => {
      const cls = String(f.class || "").trim();
      if (!/^[A-Za-z0-9_.$]+$/.test(cls)) return;
      const entry = byClass.get(cls) || { whole: false, methods: new Set() };
      const base = String(f.name || "").replace(/[\[(].*$/, "").trim();
      if (/^[A-Za-z0-9_$]+$/.test(base)) entry.methods.add(base); else entry.whole = true;
      byClass.set(cls, entry);
    });
    if (!byClass.size) return null;
    const sel = Array.from(byClass, ([cls, e]) => (e.whole || !e.methods.size ? cls : `${cls}#${Array.from(e.methods).join("+")}`));
    return `mvn test -Dtest='${sel.join(",")}' -Dsurefire.failIfNoSpecifiedTests=false`;
  }

  async function copyRerun() {
    const cmd = lastSf ? buildRerunCommand(failureItems(lastSf)) : null;
    if (!cmd) { toast("No failed tests to re-run.", false); return; }
    const ok = await copyToClipboard(cmd);
    toast(ok ? "Copied re-run command for the failed tests" : "Couldn't copy automatically — try again from localhost or HTTPS.", ok);
  }

  function setAllFailuresOpen(open) {
    document.querySelectorAll("#failuresList details.fail-detail").forEach((d) => { d.open = open; });
  }

  // ── keyboard shortcuts help ─────────────────────────────────

  const SHORTCUTS = [
    ["Ctrl/⌘ + K", "Open the command palette"],
    ["/", "Search the results"],
    ["r", "Refresh everything"],
    ["?", "Show this list"],
    ["Esc", "Close a dialog or the palette"],
    ["↑ ↓ Enter", "Move through and pick palette commands"],
  ];

  function showShortcuts() {
    $("#rawModalTitle").textContent = "Keyboard shortcuts";
    $("#rawModalBody").textContent = SHORTCUTS.map(([k, d]) => `${k.padEnd(12)} ${d}`).join("\n");
    openModal($("#rawModal"), $("#rawModal [data-close]"));
  }

  // ── preferences (saved per login) ───────────────────────────
  // The server keeps theme / density / landing tab / refresh interval per signed-in user (/api/prefs). /theme.js
  // applies theme and density before first paint from a local cache; this reads the saved values once on load,
  // makes them win, and writes changes back. A server without /api/prefs just leaves the local defaults.

  let prefsCache = { theme: "system", density: "comfortable", default_tab: "overview", refresh_s: 15 };

  function syncPrefsForm() {
    document.querySelectorAll("#prefsModal [data-pref]").forEach((sel) => {
      const v = String(prefsCache[sel.dataset.pref]);
      if (sel.value !== v) sel.value = v;
    });
  }

  function applyPrefs(p) {
    prefsCache = Object.assign({}, prefsCache, p);
    if (window.ConsoleTheme) {
      window.ConsoleTheme.setMode(prefsCache.theme, { save: false });
      window.ConsoleTheme.setDensity(prefsCache.density, { save: false });
    }
    poll.every = prefsCache.refresh_s * 1000;
    syncPrefsForm();
    renderAlerts();
  }

  async function initPrefs() {
    try {
      const p = await api("/api/prefs");
      const saved = Array.isArray(p.saved) ? p.saved : [];
      delete p.saved;
      // Nothing saved on the server yet for a key -> keep what this browser already had (migration), don't reset it.
      if (!saved.includes("theme") && window.ConsoleTheme) p.theme = window.ConsoleTheme.mode();
      if (!saved.includes("density") && window.ConsoleTheme) p.density = window.ConsoleTheme.density();
      applyPrefs(p);
      if (!location.hash && prefsCache.default_tab !== "overview") location.replace(`#${prefsCache.default_tab}`);
    } catch (e) { /* older server or offline: keep the local defaults */ }
  }

  async function savePref(patch) {
    const before = prefsCache;
    applyPrefs(patch);                                   // optimistic: the change is visible at once
    try {
      applyPrefs(await postJson("/api/prefs", patch));
      toast("Preference saved", true);
    } catch (e) {
      applyPrefs(before);
      toast(`Couldn't save the preference: ${e.message}`, false);
    }
  }

  function openPrefs() {
    syncPrefsForm();
    openModal($("#prefsModal"), $("#prefTheme"));
  }

  function toggleDensity() {
    savePref({ density: prefsCache.density === "compact" ? "comfortable" : "compact" });
  }

  // ── standalone HTML run report ───────────────────────────────
  // One self-contained file (its own inline CSS; it is a download, not something the Console serves) that can be
  // attached to a ticket or e-mailed, and printed to PDF from any browser. Everything that came from test output
  // goes through escapeHtml as TEXT — never into markup or attributes.

  const REPORT_CSS = `
    :root { color-scheme: light; }
    * { box-sizing: border-box; }
    body { margin: 0; padding: 32px 40px; font: 14px/1.5 -apple-system, "Segoe UI", Roboto, Helvetica, Arial, sans-serif; color: #1c2430; background: #fff; }
    h1 { margin: 0 0 4px; font-size: 24px; }
    h2 { margin: 28px 0 10px; font-size: 16px; padding-bottom: 6px; border-bottom: 2px solid #e3e8ef; }
    h3 { margin: 14px 0 4px; font-size: 14px; }
    .sub { color: #5b6675; margin: 0; }
    .verdict { display: inline-block; margin: 14px 0 0; padding: 4px 12px; border-radius: 999px; font-weight: 700; font-size: 13px; }
    .verdict.ok { background: #dcf5ea; color: #0b6b4c; } .verdict.bad { background: #fde4e2; color: #a52a22; }
    .kpis { display: flex; flex-wrap: wrap; gap: 10px; margin: 16px 0 0; }
    .kpi { border: 1px solid #e3e8ef; border-radius: 8px; padding: 10px 16px; min-width: 110px; }
    .kpi b { display: block; font: 600 22px ui-monospace, Menlo, Consolas, monospace; }
    .kpi span { color: #5b6675; font-size: 12px; }
    .kpi.pass b { color: #0b7a57; } .kpi.fail b { color: #c0392b; } .kpi.warn b { color: #a86a00; }
    table { width: 100%; border-collapse: collapse; font-size: 13px; }
    th, td { text-align: left; padding: 6px 10px; border-bottom: 1px solid #e3e8ef; vertical-align: top; }
    th { color: #5b6675; font-weight: 600; font-size: 12px; }
    td.n, th.n { text-align: right; font-family: ui-monospace, Menlo, Consolas, monospace; white-space: nowrap; }
    .mono { font-family: ui-monospace, Menlo, Consolas, monospace; font-size: 12.5px; }
    .cluster { border: 1px solid #e3e8ef; border-left: 4px solid #c0392b; border-radius: 6px; padding: 10px 14px; margin: 10px 0; break-inside: avoid; }
    .cluster .count { font: 700 12px ui-monospace, Menlo, Consolas, monospace; color: #c0392b; margin-right: 8px; }
    .cluster ul { margin: 6px 0 0; padding-left: 20px; }
    pre { margin: 8px 0 0; padding: 8px 10px; background: #f4f6f9; border-radius: 6px; white-space: pre-wrap; overflow-wrap: anywhere; font-size: 12px; }
    .tag { display: inline-block; padding: 0 8px; border-radius: 999px; border: 1px solid #c9d1dc; font-size: 11px; font-weight: 600; text-transform: uppercase; margin-right: 6px; }
    .muted { color: #5b6675; } tr.bad td { background: #fff5f4; }
    footer { margin-top: 36px; padding-top: 10px; border-top: 1px solid #e3e8ef; color: #5b6675; font-size: 12px; }
    @media print { body { padding: 0; } h2 { break-after: avoid; } tr { break-inside: avoid; } }
    @media (max-width: 640px) { body { padding: 18px 14px; } .kpi { flex: 1 1 40%; } }`;

  function buildReportHtml() {
    const sf = lastSf;
    if (!sf || !sf.total) return null;
    const e = escapeHtml;
    const sys = systemCache || {};
    const repo = sys.repo || {};
    const d = lastData || {};
    const rate = Math.round((1000 * sf.passed) / sf.total) / 10;
    const where = [repo.name || "", repo.branch ? ` @ ${repo.branch}` : "", repo.commit ? ` (${repo.commit})` : ""].join("").trim();
    const finished = sys.last_run ? new Date(sys.last_run * 1000).toLocaleString() : "";
    const items = failureItems(sf);
    const clusters = items.length ? clusterFailures(items) : [];
    const kpi = (cls, value, label) => `<div class="kpi ${cls}"><b>${e(String(value))}</b><span>${e(label)}</span></div>`;

    const out = [];
    out.push(`<!DOCTYPE html><html lang="en"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width, initial-scale=1">`);
    out.push(`<title>Test run report${where ? " — " + e(where) : ""}</title><style>${REPORT_CSS}</style></head><body>`);
    out.push(`<h1>Test run report</h1>`);
    out.push(`<p class="sub">${[where && e(where), finished && "finished " + e(finished), "generated " + e(new Date().toLocaleString())].filter(Boolean).join(" · ")}</p>`);
    out.push(`<span class="verdict ${sf.failed ? "bad" : "ok"}">${sf.failed ? `${sf.failed} failing test${sf.failed === 1 ? "" : "s"}` : "All tests passing"}</span>`);
    out.push(`<div class="kpis">${kpi("", sf.total, "tests")}${kpi("pass", sf.passed, "passed")}${kpi(sf.failed ? "fail" : "", sf.failed, "failed")}${kpi(sf.skipped ? "warn" : "", sf.skipped, "skipped")}${kpi(sf.failed ? "warn" : "pass", rate + "%", "pass rate")}`
      + (lastDetails.duration_s > 0 ? kpi("", fmtDuration(lastDetails.duration_s), "run time") : "")
      + (d.coverage && typeof d.coverage.line_pct === "number" ? kpi("", d.coverage.line_pct + "%", "core coverage") : "")
      + (d.self_healing && d.self_healing.total > 0 ? kpi("warn", d.self_healing.total, "self-healed locators") : "") + `</div>`);

    if (clusters.length) {
      out.push(`<h2>Failures by root cause</h2><p class="muted">${items.length} failing test${items.length === 1 ? "" : "s"}, ${clusters.length} distinct root cause${clusters.length === 1 ? "" : "s"}.</p>`);
      clusters.slice(0, 25).forEach((c) => {
        const first = c.items[0] || {};
        out.push(`<div class="cluster"><div><span class="count">×${c.items.length}</span><b>${e(c.label)}</b></div>`
          + (c.type ? `<div class="muted mono">${e(c.type)}</div>` : "")
          + `<ul>${c.items.slice(0, 15).map((f) => `<li class="mono">${e(f.class ? f.class + "." + f.name : f.name)}</li>`).join("")}${c.items.length > 15 ? `<li class="muted">+${c.items.length - 15} more</li>` : ""}</ul>`
          + (first.message ? `<pre>${e(first.message)}</pre>` : "") + `</div>`);
      });
      if (clusters.length > 25) out.push(`<p class="muted">+${clusters.length - 25} more root causes not shown.</p>`);
    }

    const stab = Array.isArray(d.stability) ? d.stability.slice(0, 10) : [];
    if (stab.length) {
      out.push(`<h2>Test stability</h2><table><thead><tr><th>Test</th><th>Status</th><th class="n">Failed</th><th class="n">Flips</th></tr></thead><tbody>`);
      stab.forEach((t) => out.push(`<tr><td class="mono">${e(t.test || "")}</td><td><span class="tag">${e(KIND_LABEL[t.kind] || t.kind || "")}</span></td><td class="n">${e(`${t.fails}/${t.runs || "?"}`)}</td><td class="n">${e(String(t.flips || 0))}</td></tr>`));
      out.push(`</tbody></table>`);
    }

    const slow = (lastDetails.slowest || []).slice(0, 10);
    if (slow.length) {
      out.push(`<h2>Slowest tests</h2><table><thead><tr><th>Test</th><th class="n">Time</th></tr></thead><tbody>`);
      slow.forEach((r) => out.push(`<tr><td class="mono">${e(r.class ? r.class + "." + r.name : r.name)}</td><td class="n">${e(fmtDuration(r.seconds))}</td></tr>`));
      out.push(`</tbody></table>`);
    }

    const classes = lastByClass.slice().sort((a, b) => (b.failed - a.failed) || String(a.class).localeCompare(String(b.class))).slice(0, 300);
    if (classes.length) {
      out.push(`<h2>Results by class</h2><table><thead><tr><th>Class</th><th class="n">Total</th><th class="n">Passed</th><th class="n">Failed</th><th class="n">Skipped</th><th class="n">Time</th></tr></thead><tbody>`);
      classes.forEach((c) => out.push(`<tr${c.failed ? ' class="bad"' : ""}><td class="mono">${e(c.class)}</td><td class="n">${c.total}</td><td class="n">${c.passed}</td><td class="n">${c.failed}</td><td class="n">${c.skipped}</td><td class="n">${e(c.time == null ? "–" : fmtDuration(c.time))}</td></tr>`));
      out.push(`</tbody></table>`);
      if (lastByClass.length > 300) out.push(`<p class="muted">Showing 300 of ${lastByClass.length} classes (failing first).</p>`);
    }
    out.push(`<footer>Generated by the Automation Console. For a PDF: open this file in a browser and use Print → Save as PDF.</footer></body></html>`);
    return out.join("");
  }

  function downloadText(filename, text, type) {
    const url = URL.createObjectURL(new Blob([text], { type }));
    const a = document.createElement("a");
    a.href = url;
    a.download = filename;
    document.body.appendChild(a);
    a.click();
    a.remove();
    URL.revokeObjectURL(url);
  }

  function exportReportHtml() {
    const html = buildReportHtml();
    if (!html) { toast("No test results to report yet.", false); return; }
    const stamp = new Date().toISOString().slice(0, 16).replace(/[:T]/g, "-");
    downloadText(`run-report-${stamp}.html`, html, "text/html;charset=utf-8");
    toast("Report downloaded — open it and use Print → Save as PDF for a PDF.", true);
  }

  // ── run-time trend ───────────────────────────────────────────

  function median(nums) {
    const a = nums.slice().sort((x, y) => x - y);
    if (!a.length) return null;
    const m = Math.floor(a.length / 2);
    return a.length % 2 ? a[m] : (a[m - 1] + a[m]) / 2;
  }

  function renderDuration(hist) {
    const box = $("#durChart");
    if (!box) return;
    box.innerHTML = "";
    const runs = hist.filter((r) => typeof r.dur === "number" && r.dur > 0).slice(-30);
    if (runs.length < 2) {
      const d = document.createElement("div");
      d.className = "chart-empty";
      d.textContent = runs.length
        ? "Run the tests once more and the run-time trend appears (a single run has nothing to compare with)."
        : "Run-time data is recorded from now on; it appears after the next run is loaded here.";
      box.appendChild(d);
      return;
    }
    const W = Math.max(300, Math.min(600, Math.round(box.clientWidth - 36) || 600)), H = 130, L = 40, T = 8, B = 6, innerH = H - T - B;
    const max = Math.max(...runs.map((r) => r.dur));
    const med = median(runs.map((r) => r.dur));
    const svg = svgEl("svg", { viewBox: `0 0 ${W} ${H}`, class: "chart", role: "img",
      "aria-label": `Run time of the last ${runs.length} runs; latest ${fmtDuration(runs[runs.length - 1].dur)}, median ${fmtDuration(med)}` });
    svg.appendChild(svgEl("line", { x1: L, x2: W, y1: T + innerH, y2: T + innerH, class: "chart-grid" }));
    const top = svgEl("text", { x: L - 6, y: T + 8, "text-anchor": "end", class: "chart-axis" });
    top.textContent = fmtDuration(max);
    svg.appendChild(top);
    const slot = (W - L) / runs.length;
    const bw = Math.max(3, Math.min(26, slot * 0.68));
    runs.forEach((r, i) => {
      const h = Math.max(2, (r.dur / max) * innerH);
      const slow = med && r.dur > med * 1.25;
      const rect = svgEl("rect", { x: L + i * slot + (slot - bw) / 2, y: T + innerH - h, width: bw, height: h, class: `chart-bar ${slow ? "is-warn" : "is-dur"}` });
      const title = svgEl("title", {});
      title.textContent = `${fmtDate(r.t)} — ${fmtDuration(r.dur)}`;
      rect.appendChild(title);
      svg.appendChild(rect);
    });
    const my = T + innerH - (med / max) * innerH;
    svg.appendChild(svgEl("line", { x1: L, x2: W, y1: my, y2: my, class: "chart-median" }));
    box.appendChild(svg);
    const latest = runs[runs.length - 1].dur;
    const pct = med ? Math.round((100 * (latest - med)) / med) : 0;
    const meta = document.createElement("div");
    meta.className = "chart-meta";
    meta.textContent = `${runs.length} runs · latest ${fmtDuration(latest)} (${pct >= 0 ? "+" : ""}${pct}% vs median ${fmtDuration(med)}) · longest ${fmtDuration(max)} · amber = more than 25% over the median`;
    box.appendChild(meta);
  }

  // ── threshold alerts ─────────────────────────────────────────
  // Evaluated in the browser against the latest run and the saved per-login thresholds (Preferences). Off by
  // default. A dismissed alert stays dismissed until the run or the wording changes.

  const ALERT_DISMISS_KEY = "dashboard.alertsDismissed";
  let lastHist = [];

  function computeAlerts() {
    const p = prefsCache;
    const sf = lastSf;
    const msgs = [];
    if (!sf || !sf.total) return msgs;
    const rate = Math.round((1000 * sf.passed) / sf.total) / 10;
    if (p.alert_pass_pct > 0 && rate < p.alert_pass_pct) {
      msgs.push(`Pass rate is ${rate}% — below your ${p.alert_pass_pct}% line (${sf.failed} failing).`);
    }
    const cov = lastData && lastData.coverage && lastData.coverage.line_pct;
    if (p.alert_coverage_pct > 0 && typeof cov === "number" && cov < p.alert_coverage_pct) {
      msgs.push(`Core coverage is ${cov}% — below your ${p.alert_coverage_pct}% line.`);
    }
    if (p.alert_slowdown_pct > 0) {
      const timed = lastHist.filter((r) => typeof r.dur === "number" && r.dur > 0);
      if (timed.length >= 4) {
        const latest = timed[timed.length - 1].dur;
        const med = median(timed.slice(-11, -1).map((r) => r.dur));
        const pct = Math.round((100 * (latest - med)) / med);
        if (pct >= p.alert_slowdown_pct) msgs.push(`The last run took ${fmtDuration(latest)} — ${pct}% longer than the recent median (${fmtDuration(med)}).`);
      }
    }
    return msgs;
  }

  function renderAlerts() {
    const banner = $("#alertsBanner");
    if (!banner) return;
    const msgs = computeAlerts();
    const sig = `${msgs.join("|")}@${lastHist.length ? lastHist[lastHist.length - 1].t : 0}`;
    let dismissed = null;
    try { dismissed = localStorage.getItem(ALERT_DISMISS_KEY); } catch (e) { /* ignore */ }
    banner.hidden = !msgs.length || dismissed === sig;
    banner.dataset.sig = sig;
    $("#alertsText").textContent = msgs.join("  ·  ");
  }

  // ── compare two runs ─────────────────────────────────────────

  function renderCompareChoices(hist) {
    const a = $("#cmpA"), b = $("#cmpB");
    if (!a || !b) return;
    const runs = hist.filter((r) => typeof r.t === "number").slice(-30).reverse();   // newest first
    const keepA = a.value, keepB = b.value;
    [a, b].forEach((sel) => { sel.innerHTML = ""; });
    runs.forEach((r) => {
      const label = `${fmtDate(r.t)} · ${r.rate}%${typeof r.failed === "number" ? ` · ${r.failed} failed` : ""}`;
      [a, b].forEach((sel) => {
        const o = document.createElement("option");
        o.value = String(r.t);
        o.textContent = label;
        sel.appendChild(o);
      });
    });
    const has = (sel, v) => Array.from(sel.options).some((o) => o.value === v);
    b.value = has(b, keepB) ? keepB : (runs[0] ? String(runs[0].t) : "");
    a.value = has(a, keepA) ? keepA : (runs[1] ? String(runs[1].t) : "");
    $("#cmpBtn").disabled = runs.length < 2;
    if (runs.length < 2) {
      const r = $("#cmpResult");
      r.innerHTML = "";
      r.appendChild(emptyState("Comparison needs at least two recorded runs."));
    }
  }

  function cmpSection(title, items, build, open, good) {
    if (!items.length) return null;
    const d = document.createElement("details");
    d.className = "fail-detail cmp-section";
    d.open = open;
    const s = document.createElement("summary");
    const n = document.createElement("span");
    n.className = `cluster-count${good ? " is-good" : ""}`;
    n.textContent = String(items.length);
    const t = document.createElement("span");
    t.className = "fail-name";
    t.textContent = title;
    s.append(n, t);
    const ul = document.createElement("ul");
    ul.className = "cluster-tests cmp-list";
    items.forEach((it) => { const li = document.createElement("li"); build(li, it); ul.appendChild(li); });
    const body = document.createElement("div");
    body.className = "fail-body";
    body.appendChild(ul);
    d.append(s, body);
    return d;
  }

  async function runCompare() {
    const a = Number($("#cmpA").value), b = Number($("#cmpB").value);
    const box = $("#cmpResult");
    if (!a || !b || a === b) { toast("Pick two different runs.", false); return; }
    box.innerHTML = "";
    box.appendChild(emptyState("Comparing…"));
    let r;
    try { r = await api(`/api/results/compare?a=${a}&b=${b}`); } catch (e) { box.innerHTML = ""; box.appendChild(emptyState(e.message)); return; }
    box.innerHTML = "";
    const wrap = document.createElement("div");
    wrap.className = "cmp-wrap";
    const head = document.createElement("div");
    head.className = "cmp-head";
    const side = (label, x) => `${label}: ${fmtDate(x.t)} — ${x.rate}%${typeof x.failed === "number" ? `, ${x.failed} failed` : ""}${x.dur ? `, ${fmtDuration(x.dur)}` : ""}`;
    head.textContent = `${side("Baseline", r.a)}   →   ${side("Compared", r.b)}`
      + (r.duration_pct !== null ? `   (run time ${r.duration_pct >= 0 ? "+" : ""}${r.duration_pct}%)` : "");
    wrap.appendChild(head);
    if (!r.per_test) {
      const n = document.createElement("p");
      n.className = "field-note";
      n.textContent = "One of these runs was recorded before per-test results were kept (they cannot be recovered), so only the totals can be compared.";
      wrap.appendChild(n);
    } else {
      const c = r.counts || {};
      const sum = document.createElement("p");
      sum.className = "cmp-summary";
      sum.textContent = `${c.new_failures || 0} newly failing · ${c.fixed || 0} fixed · ${c.still_failing || 0} still failing${r.exact ? ` · ${c.no_longer_run || 0} no longer run` : ""}`;
      wrap.appendChild(sum);
      if (!c.new_failures && !c.fixed && !c.still_failing) wrap.appendChild(okState("No failures in either run", "Nothing to compare."));
    }
    const parts = [
      cmpSection("Newly failing", r.new_failures, (li, it) => {
        li.textContent = it.test;
        const extra = [it.was ? `was ${it.was}` : "", it.message || ""].filter(Boolean).join(" — ");
        if (extra) { const m = document.createElement("div"); m.className = "field-note"; m.textContent = extra; li.appendChild(m); }
      }, true),
      cmpSection(r.exact ? "Fixed (now passing)" : "Fixed (no longer failing)", r.fixed, (li, it) => { li.textContent = it; }, true, true),
      cmpSection("Was failing, now skipped or not run", r.no_longer_run || [], (li, it) => { li.textContent = it; }, true),
      cmpSection("Got slower", r.slower, (li, it) => { li.textContent = `${it.test}  ${fmtDuration(it.a)} → ${fmtDuration(it.b)}  (+${fmtDuration(it.delta)})`; }, true),
      cmpSection("Got faster", r.faster, (li, it) => { li.textContent = `${it.test}  ${fmtDuration(it.a)} → ${fmtDuration(it.b)}`; }, false, true),
      cmpSection("Still failing", r.still_failing, (li, it) => { li.textContent = it; }, false),
    ].filter(Boolean);
    parts.forEach((p) => wrap.appendChild(p));
    if (r.per_test) {
      const note = document.createElement("p");
      note.className = "field-note";
      note.textContent = r.exact
        ? "Exact: every test's result and time was recorded for both runs, so this is compared test by test."
        : "Approximate: \"fixed\" means absent from the second run's failures (it may also mean the test did not run), and times are compared only for tests among the slowest of both runs. Runs recorded from now on are compared exactly.";
      wrap.appendChild(note);
    }
    box.appendChild(wrap);
  }

  // ── per-test history drawer + quarantine ─────────────────────

  function configTestFor(testName) {
    const cls = testName.includes(".") ? testName.slice(0, testName.lastIndexOf(".")) : testName;
    const simple = cls.slice(cls.lastIndexOf(".") + 1);
    if (!testConfigCache) return null;
    for (const sec of testConfigCache.sections) {
      const t = sec.tests.find((x) => x.name === simple);
      if (t) return t;
    }
    return null;
  }

  async function quarantineTest(testName) {
    const t = configTestFor(testName);
    if (!t) { toast("This test's class isn't in the test configuration, so it can't be switched off here.", false); return false; }
    if (!t.enabled) { toast(`${t.name} is already switched off.`, true); return false; }
    if (!confirm(`Quarantine ${t.name}?\n\nThis switches off the whole test CLASS ${t.name} (every test in it) in test-config.properties. It is recorded in Activity, where it can be undone.`)) return false;
    try {
      testConfigCache = await postJson("/api/test-config/test", { name: t.name, enabled: false });
      renderTestSections();
      loadAudit();
      toast(`${t.name} is quarantined (switched off)`, true);
      return true;
    } catch (e) { toast(e.message, false); return false; }
  }

  async function openTestDrawer(testName) {
    $("#testModalTitle").textContent = testName;
    const body = $("#testModalBody");
    body.innerHTML = "";
    body.appendChild(emptyState("Loading…"));
    const q = $("#testQuarantine");
    q.hidden = true;
    openModal($("#testModal"), $("#testModal [data-close]"));
    let h;
    try { h = await api(`/api/results/test?name=${encodeURIComponent(testName)}`); } catch (e) { body.innerHTML = ""; body.appendChild(emptyState(e.message)); return; }
    body.innerHTML = "";
    const stats = document.createElement("div");
    stats.className = "td-stats";
    const stat = (label, value) => { const s = document.createElement("div"); s.className = "td-stat"; const v = document.createElement("b"); v.textContent = value; const l = document.createElement("span"); l.textContent = label; s.append(v, l); stats.appendChild(s); };
    stat("status", h.kind ? (KIND_LABEL[h.kind] || h.kind) : "no failures recorded");
    stat("failed", h.total_runs ? `${h.fails} of ${h.total_runs} runs` : "–");
    stat("flips", String(h.flips));
    stat("average time", h.avg_seconds !== null ? fmtDuration(h.avg_seconds) : "–");
    stat("slowest", h.max_seconds !== null ? fmtDuration(h.max_seconds) : "–");
    body.appendChild(stats);
    if (h.timed_runs) {
      const n = document.createElement("p");
      n.className = "field-note";
      const approx = h.total_runs - (h.exact_runs || 0);
      n.textContent = h.exact_runs
        ? `Exact results for ${h.exact_runs} run${h.exact_runs === 1 ? "" : "s"}${approx > 0 ? `; ${approx} older run${approx === 1 ? "" : "s"} only know failures and the slowest tests` : ""}.`
        : `Times come from the ${h.timed_runs} recorded run${h.timed_runs === 1 ? "" : "s"} where this test was among the slowest.`;
      body.appendChild(n);
    }
    if (h.last_error) {
      const lbl = document.createElement("div");
      lbl.className = "td-label";
      lbl.textContent = "Last error";
      const pre = document.createElement("pre");
      pre.className = "raw-view mono td-error";
      pre.textContent = h.last_error;
      body.append(lbl, pre);
    }
    const lbl = document.createElement("div");
    lbl.className = "td-label";
    lbl.textContent = h.runs.length ? "Recorded runs (newest first)" : "No per-test history recorded yet";
    body.appendChild(lbl);
    const ul = document.createElement("div");
    ul.className = "td-runs";
    h.runs.slice().reverse().forEach((r) => {
      const row = document.createElement("div");
      row.className = `td-run is-${r.status}`;
      const when = document.createElement("span");
      when.className = "mono";
      when.textContent = fmtDate(r.t);
      const st = document.createElement("span");
      st.className = "td-status";
      st.textContent = { fail: "failed", pass: r.exact ? "passed" : "not failing", skip: "skipped", absent: "not in this run" }[r.status] || r.status;
      const tm = document.createElement("span");
      tm.className = "mono";
      tm.textContent = typeof r.seconds === "number" ? fmtDuration(r.seconds) : "";
      row.append(when, st, tm);
      if (r.message) { const m = document.createElement("span"); m.className = "td-msg"; m.textContent = r.message; row.appendChild(m); }
      ul.appendChild(row);
    });
    body.appendChild(ul);
    const cfg = configTestFor(testName);
    q.hidden = !(cfg && cfg.enabled);
    q.textContent = cfg ? `Quarantine ${cfg.name}…` : "Quarantine…";
    q.onclick = async () => { if (await quarantineTest(testName)) q.hidden = true; };
  }

  // ── saved views (Results tab) ────────────────────────────────
  // The filter/sort state lives in the URL (?rq=…&rs=…&rk=…&rd=…) so a view can be bookmarked or shared, and
  // named views are kept in this browser. Everything read back is validated against fixed choices.

  const VIEWS_KEY = "dashboard.savedViews";
  const VIEW_SORTS = ["class", "total", "passed", "failed", "skipped", "time"];
  const VIEW_STATUS = ["all", "failing", "passing"];
  const VIEW_DEFAULT = { query: "", status: "all", sortKey: "failed", sortDir: -1 };

  function sanitizeView(v) {
    const o = Object.assign({}, VIEW_DEFAULT);
    if (!v || typeof v !== "object") return o;
    if (typeof v.query === "string") o.query = v.query.slice(0, 100);
    if (VIEW_STATUS.includes(v.status)) o.status = v.status;
    if (VIEW_SORTS.includes(v.sortKey)) o.sortKey = v.sortKey;
    if (v.sortDir === 1 || v.sortDir === -1) o.sortDir = v.sortDir;
    return o;
  }

  function viewQuery() {
    const p = new URLSearchParams();
    if (view.query) p.set("rq", view.query.slice(0, 100));
    if (view.status !== "all") p.set("rs", view.status);
    if (view.sortKey !== VIEW_DEFAULT.sortKey || view.sortDir !== VIEW_DEFAULT.sortDir) { p.set("rk", view.sortKey); p.set("rd", String(view.sortDir)); }
    return p.toString();
  }

  function setView(v) {
    Object.assign(view, sanitizeView(v));
    $("#resultsSearch").value = view.query;
    document.querySelectorAll(".seg-btn").forEach((x) => {
      const on = x.dataset.status === view.status;
      x.classList.toggle("is-on", on);
      x.setAttribute("aria-pressed", on ? "true" : "false");
    });
    renderResultsTable(lastByClass);
  }

  function syncViewUrl() {
    try {
      const qs = viewQuery();
      history.replaceState(null, "", `${location.pathname}${qs ? `?${qs}` : ""}${location.hash}`);
    } catch (e) { /* sandboxed or file:// — the view just isn't mirrored into the address bar */ }
  }

  function viewFromUrl() {
    const p = new URLSearchParams(location.search);
    if (!["rq", "rs", "rk", "rd"].some((k) => p.has(k))) return null;
    return { query: p.get("rq") || "", status: p.get("rs"), sortKey: p.get("rk"), sortDir: Number(p.get("rd")) };
  }

  function loadViews() {
    try {
      const raw = JSON.parse(localStorage.getItem(VIEWS_KEY) || "[]");
      return Array.isArray(raw) ? raw.filter((x) => x && typeof x.name === "string" && x.name.trim()).slice(0, 12).map((x) => ({ name: x.name.slice(0, 40), view: sanitizeView(x.view) })) : [];
    } catch (e) { return []; }
  }

  function storeViews(list) {
    try { localStorage.setItem(VIEWS_KEY, JSON.stringify(list.slice(0, 12))); } catch (e) { toast("Couldn't save: browser storage is unavailable.", false); }
  }

  function renderViewsSelect(selectedName) {
    const sel = $("#viewsSelect");
    sel.innerHTML = "";
    const first = document.createElement("option");
    first.value = "";
    first.textContent = "Saved views…";
    sel.appendChild(first);
    loadViews().forEach((v) => {
      const o = document.createElement("option");
      o.value = v.name;
      o.textContent = v.name;
      sel.appendChild(o);
    });
    sel.value = selectedName || "";
    $("#viewDelete").disabled = !sel.value;
  }

  function wireViews() {
    renderViewsSelect();
    $("#viewsSelect").addEventListener("change", (e) => {
      const hit = loadViews().find((v) => v.name === e.target.value);
      $("#viewDelete").disabled = !hit;
      if (hit) setView(hit.view);
    });
    $("#viewSave").addEventListener("click", () => {
      const name = (window.prompt("Name this view (it keeps the current search, status filter and sort):") || "").trim().slice(0, 40);
      if (!name) return;
      const list = loadViews().filter((v) => v.name !== name);
      list.push({ name, view: sanitizeView(view) });
      storeViews(list);
      renderViewsSelect(name);
      toast(`Saved view "${name}"`, true);
    });
    $("#viewDelete").addEventListener("click", () => {
      const name = $("#viewsSelect").value;
      if (!name || !confirm(`Delete the saved view "${name}"?`)) return;
      storeViews(loadViews().filter((v) => v.name !== name));
      renderViewsSelect();
    });
    $("#viewLink").addEventListener("click", async () => {
      const qs = viewQuery();
      const ok = await copyToClipboard(`${location.origin}${location.pathname}${qs ? `?${qs}` : ""}#results`);
      toast(ok ? "Copied a link to this view" : "Couldn't copy automatically — copy the address bar instead.", ok);
    });
  }

  // ── environment health ───────────────────────────────────────

  function renderHealth(h) {
    const box = $("#healthList");
    box.innerHTML = "";
    (h.targets || []).forEach((t) => {
      const row = document.createElement("div");
      row.className = "report-row";
      const dot = document.createElement("span");
      const state = t.reachable === true ? "is-on" : t.reachable === false ? (t.enabled === false ? "is-na" : "is-bad") : "is-na";
      dot.className = `report-dot ${state}`;
      dot.setAttribute("aria-hidden", "true");
      const info = document.createElement("div");
      info.className = "report-info";
      const n = document.createElement("div");
      n.className = "report-name";
      n.textContent = t.name;
      const p = document.createElement("div");
      p.className = "report-path mono";
      const bits = [];
      if (t.target) bits.push(t.target);
      if (t.reachable === true) bits.push(`${t.note === "ok" ? "reachable" : t.note}${typeof t.ms === "number" ? ` · ${t.ms} ms` : ""}`);
      else if (t.reachable === false) bits.push(t.enabled === false ? "not reachable — fine, it is switched off in the config (grid.enabled=false)" : t.note);
      else bits.push(t.note);
      p.textContent = bits.join(" · ");
      info.append(n, p);
      row.append(dot, info);
      box.appendChild(row);
    });
    $("#healthNote").textContent = `Checked ${new Date((h.checked_at || Date.now() / 1000) * 1000).toLocaleTimeString()}`;
  }

  async function checkHealth() {
    const btn = $("#healthBtn");
    btn.disabled = true;
    $("#healthNote").textContent = "Checking…";
    try { renderHealth(await api("/api/health/deps")); }
    catch (e) { $("#healthNote").textContent = ""; toast(`Health check failed: ${e.message}`, false); }
    finally { btn.disabled = false; }
  }

  // ── live run view ────────────────────────────────────────────
  // Only when the test runner is enabled. Polls the existing /api/runs (and the run's log tail) every few seconds
  // while a run is queued or running; otherwise it checks rarely. Progress is read from Maven's own
  // "Tests run: … in <Class>" lines. Stop uses the same cancel endpoint (and permission rule) as the Test Hub.

  const live = { timer: null, runId: null, offset: -1, text: "", wasLive: false, hideAt: 0 };
  const ANSI = /\x1b\[[0-9;]*[A-Za-z]/g;
  const TESTS_RUN = /Tests run: (\d+), Failures: (\d+), Errors: (\d+), Skipped: (\d+), Time elapsed: [\d.,]+ \S+(?: <<< \w+!)? +-{1,2} in (\S+)/;

  function parseProgress(text) {
    const out = { classes: 0, tests: 0, failing: 0, bad: [] };
    text.split("\n").forEach((line) => {
      const m = TESTS_RUN.exec(line);
      if (!m) return;
      out.classes += 1;
      out.tests += Number(m[1]);
      const f = Number(m[2]) + Number(m[3]);
      out.failing += f;
      if (f > 0) out.bad.push(m[5]);
    });
    return out;
  }

  function showLive(title, sub, progressText, logText, canStop) {
    $("#livePanel").hidden = false;
    $("#liveTitle").textContent = title;
    $("#liveSub").textContent = sub;
    $("#liveProgress").textContent = progressText;
    const log = $("#liveLog");
    log.textContent = logText;
    log.scrollTop = log.scrollHeight;
    $("#liveStop").hidden = !canStop;
  }

  async function renderLive(run, waiting) {
    if (live.runId !== run.id) { live.runId = run.id; live.offset = -1; live.text = ""; }
    if (run.state === "running") {
      try {
        const d = await api(`/api/runs/${run.id}/log?offset=${live.offset}`);
        live.offset = d.offset;
        live.text = (live.text + d.text.replace(ANSI, "")).slice(-80000);
      } catch (e) { /* keep what we have */ }
    }
    const p = parseProgress(live.text);
    const lines = live.text.split("\n").filter((l) => l.trim()).slice(-14).join("\n");
    const started = run.startedAt ? Date.parse(run.startedAt) : NaN;
    const secs = Number.isFinite(started) ? Math.max(0, (Date.now() - started) / 1000) : null;
    const sub = [run.name || "", run.startedBy ? `started by ${run.startedBy}` : "", secs !== null ? `running for ${fmtDuration(secs)}` : "", waiting > 1 ? `${waiting - 1} more waiting` : ""].filter(Boolean).join(" · ");
    const prog = run.state === "queued"
      ? `Waiting in the queue${run.queuePosition ? ` (position ${run.queuePosition})` : ""}…`
      : `${p.classes} class${p.classes === 1 ? "" : "es"} finished · ${p.tests} tests · ${p.failing} failing${p.bad.length ? ` — ${p.bad.slice(0, 4).join(", ")}${p.bad.length > 4 ? "…" : ""}` : ""}`;
    showLive(run.state === "queued" ? "Run queued" : "Run in progress", sub, prog, lines, true);
  }

  async function liveTick() {
    let delay = 15000;
    try {
      if (!systemCache || !systemCache.runner || !systemCache.runner.enabled) {
        delay = 30000;
      } else if (poll.offline) {
        delay = 10000;
      } else {
        const r = await api("/api/runs");
        const runs = r.runs || [];
        const active = runs.filter((x) => x.state === "queued" || x.state === "running");
        if (active.length) {
          live.wasLive = true;
          live.hideAt = 0;
          await renderLive(active[active.length - 1], active.length);
          delay = 3000;
        } else {
          if (live.wasLive) {
            live.wasLive = false;
            const last = runs.find((x) => x.id === live.runId) || runs[0] || {};
            showLive(`Run finished — ${last.state || "done"}`, last.name || "", parseProgress(live.text).classes ? `${parseProgress(live.text).classes} classes · ${parseProgress(live.text).tests} tests · ${parseProgress(live.text).failing} failing` : "", live.text.split("\n").filter((l) => l.trim()).slice(-14).join("\n"), false);
            live.hideAt = Date.now() + 25000;
            loadAll();
          }
          if (live.hideAt && Date.now() > live.hideAt) { $("#livePanel").hidden = true; live.hideAt = 0; live.runId = null; }
        }
      }
    } catch (e) {
      delay = 60000;   // runner not available to this login, or the server is away: check rarely
    }
    live.timer = setTimeout(liveTick, document.hidden ? Math.max(delay, 20000) : delay);
  }

  async function stopLiveRun() {
    if (!live.runId || !confirm("Stop the run that is in progress?")) return;
    try { await postJson(`/api/runs/${live.runId}/cancel`, {}); toast("Stop requested", true); clearTimeout(live.timer); liveTick(); }
    catch (e) { toast(e.message, false); }
  }

  // ── next-run plan ───────────────────────────────────────────

  function planRow(title, build) {
    const row = document.createElement("div");
    row.className = "plan-row";
    const h = document.createElement("div");
    h.className = "plan-title";
    h.textContent = title;
    const v = document.createElement("div");
    v.className = "plan-value";
    build(v);
    row.append(h, v);
    return row;
  }

  function chipList(parent, names, on, limit) {
    const chips = document.createElement("div");
    chips.className = "chips";
    names.slice(0, limit).forEach((n) => {
      const c = document.createElement("span");
      c.className = `chip${on ? " is-on" : ""}`;
      c.textContent = n;
      chips.appendChild(c);
    });
    if (names.length > limit) {
      const more = document.createElement("span");
      more.className = "chip";
      more.textContent = `+${names.length - limit} more`;
      chips.appendChild(more);
    }
    parent.appendChild(chips);
  }

  function renderPlan() {
    const box = $("#planBody");
    if (!box) return;
    box.innerHTML = "";
    if (!sitesCache.length && !testConfigCache) { box.appendChild(emptyState("Loading configuration…")); return; }

    const onSites = sitesCache.filter((x) => x.enabled).map((x) => x.name);
    const offSites = sitesCache.filter((x) => !x.enabled).map((x) => x.name);
    const grid = document.createElement("div");
    grid.className = "plan-grid";

    grid.appendChild(planRow(`Sites · ${onSites.length} of ${sitesCache.length} on`, (v) => {
      if (!onSites.length) {
        const w = document.createElement("p");
        w.className = "plan-warn";
        w.textContent = "No site is enabled — there is nothing for the next run to execute.";
        v.appendChild(w);
      } else chipList(v, onSites, true, 12);
      if (offSites.length) {
        const off = document.createElement("p");
        off.className = "field-note";
        off.textContent = `Off: ${offSites.slice(0, 8).join(", ")}${offSites.length > 8 ? ` +${offSites.length - 8} more` : ""}`;
        v.appendChild(off);
      }
    }));

    if (testConfigCache) {
      const all = testConfigCache.sections.flatMap((sec) => sec.tests);
      const off = all.filter((t) => !t.enabled).map((t) => t.name);
      grid.appendChild(planRow(`Test classes · ${all.length - off.length} of ${all.length} on`, (v) => {
        if (!off.length) {
          const p = document.createElement("p");
          p.className = "field-note";
          p.textContent = "Every test class is switched on.";
          v.appendChild(p);
        } else {
          const p = document.createElement("p");
          p.className = "field-note";
          p.textContent = "Switched off:";
          v.appendChild(p);
          chipList(v, off, false, 10);
        }
      }));

      const groupsOff = (testConfigCache.groups || []).filter((g) => !g.enabled).map((g) => g.name);
      grid.appendChild(planRow(`Groups switched off · ${groupsOff.length}`, (v) => {
        if (groupsOff.length) chipList(v, groupsOff, false, 12);
        else {
          const p = document.createElement("p");
          p.className = "field-note";
          p.textContent = "No group is switched off.";
          v.appendChild(p);
        }
      }));

      grid.appendChild(planRow("Run only", (v) => {
        const p = document.createElement("p");
        p.className = testConfigCache.run_only ? "mono" : "field-note";
        p.textContent = testConfigCache.run_only || "No restriction — every enabled test runs.";
        v.appendChild(p);
      }));
    }
    box.appendChild(grid);
  }

  // ── run summary (markdown) ──────────────────────────────────

  function buildSummaryMarkdown() {
    const sf = lastSf;
    if (!sf || !sf.total) return null;
    const rate = Math.round((1000 * sf.passed) / sf.total) / 10;
    const lines = [];
    const sys = systemCache || {};
    const repo = sys.repo || {};
    const where = [repo.name, repo.branch ? `@${repo.branch}` : "", repo.commit ? ` (${repo.commit})` : ""].join("");
    lines.push(`### ${sf.failed ? "❌" : "✅"} Test run summary${where ? ` — ${where}` : ""}`);
    lines.push("");
    lines.push(`- **Tests:** ${sf.total} · **passed** ${sf.passed} · **failed** ${sf.failed} · **skipped** ${sf.skipped} · **pass rate** ${rate}%`);
    if (lastDetails.duration_s > 0) lines.push(`- **Run time:** ${fmtDuration(lastDetails.duration_s)}`);
    const when = sys.last_run ? new Date(sys.last_run * 1000).toLocaleString() : null;
    if (when) lines.push(`- **Finished:** ${when}`);
    const d = lastData || {};
    if (d.coverage && typeof d.coverage.line_pct === "number") lines.push(`- **Core coverage:** ${d.coverage.line_pct}%`);
    if (d.self_healing && typeof d.self_healing.total === "number" && d.self_healing.total > 0) lines.push(`- **Self-healed locators:** ${d.self_healing.total}`);
    if (d.flaky && typeof d.flaky.flaky_count === "number" && d.flaky.flaky_count > 0) lines.push(`- **Flaky tests:** ${d.flaky.flaky_count}`);
    const items = failureItems(sf);
    if (items.length) {
      lines.push("");
      lines.push("#### Failures by root cause");
      clusterFailures(items).slice(0, 10).forEach((c) => {
        const names = c.items.slice(0, 3).map((f) => `\`${f.name}\``).join(", ");
        const rest = c.items.length > 3 ? ` +${c.items.length - 3} more` : "";
        lines.push(`- ×${c.items.length} ${c.label.replace(/[\r\n]+/g, " ")} — ${names}${rest}`);
      });
    }
    return lines.join("\n");
  }

  async function copySummary() {
    const md = buildSummaryMarkdown();
    if (!md) { toast("No test results to summarise yet.", false); return; }
    const ok = await copyToClipboard(md);
    toast(ok ? "Copied run summary (Markdown)" : "Couldn't copy automatically — try again from localhost or HTTPS.", ok);
  }

  // Bar-per-run pass-rate chart. Native <title> children give an accessible tooltip with no JS or inline style.
  function renderTrend(hist) {
    const box = $("#trendChart");
    box.innerHTML = "";
    if (!hist.length) {
      const d = document.createElement("div");
      d.className = "chart-empty";
      d.textContent = "The trend appears once a test run has been recorded.";
      box.appendChild(d);
      return;
    }
    const runs = hist.slice(-30);
    const W = Math.max(300, Math.min(600, Math.round(box.clientWidth - 36) || 600)), H = 150, L = 30, T = 8, B = 6;
    const innerH = H - T - B;
    const svg = svgEl("svg", { viewBox: `0 0 ${W} ${H}`, class: "chart", role: "img",
      "aria-label": `Pass rate for the last ${runs.length} runs; latest ${runs[runs.length - 1].rate} percent` });
    [0, 50, 100].forEach((v) => {
      const y = T + innerH - (v / 100) * innerH;
      svg.appendChild(svgEl("line", { x1: L, x2: W, y1: y, y2: y, class: "chart-grid" }));
      const t = svgEl("text", { x: L - 6, y: y + 3, "text-anchor": "end", class: "chart-axis" });
      t.textContent = `${v}`;
      svg.appendChild(t);
    });
    const slot = (W - L) / runs.length;
    const bw = Math.max(3, Math.min(26, slot * 0.68));
    runs.forEach((r, i) => {
      const h = Math.max(2, (r.rate / 100) * innerH);
      const cls = r.rate >= 95 ? "is-pass" : r.rate >= 80 ? "is-warn" : "is-fail";
      const rect = svgEl("rect", { x: L + i * slot + (slot - bw) / 2, y: T + innerH - h, width: bw, height: h, class: `chart-bar ${cls}` });
      const detail = typeof r.total === "number" ? ` (${r.passed} of ${r.total} passed, ${r.failed} failed)` : "";
      const title = svgEl("title", {});
      title.textContent = `${fmtDate(r.t)} — ${r.rate}%${detail}`;
      rect.appendChild(title);
      svg.appendChild(rect);
    });
    box.appendChild(svg);
    const rates = runs.map((r) => r.rate);
    const meta = document.createElement("div");
    meta.className = "chart-meta";
    meta.innerHTML = `<span>${runs.length} run${runs.length === 1 ? "" : "s"} · latest <strong>${rates[rates.length - 1]}%</strong> · best ${Math.max(...rates)}% · worst ${Math.min(...rates)}%</span>
      <span class="legend"><span><i class="l-pass"></i>≥ 95%</span><span><i class="l-warn"></i>≥ 80%</span><span><i class="l-fail"></i>&lt; 80%</span></span>`;
    box.appendChild(meta);
  }

  // ── environment card ───────────────────────────────────────

  let systemCache = null;

  async function loadSystem() {
    systemCache = await api("/api/system");
    renderEnv(systemCache);
  }

  function renderEnv(sys) {
    const box = $("#envList");
    box.innerHTML = "";
    const repo = sys.repo || {};
    const rows = [
      ["Repository", repo.name || "unknown"],
      ["Branch", repo.branch ? repo.branch : repo.detached ? "(detached HEAD)" : "unknown"],
      ["Commit", repo.commit || "unknown"],
      ["Last run", sys.last_run ? `${timeAgo(sys.last_run)}` : "no run yet"],
      ["Signed in as", sys.auth && sys.auth.user ? `${sys.auth.user}${sys.auth.role ? ` · ${sys.auth.role}` : ""}` : "unknown"],
      ["Test runner", sys.runner && sys.runner.enabled ? "enabled" : "off (read-only)"],
      ["Console uptime", fmtDuration(sys.server ? sys.server.uptime_s : NaN)],
    ];
    const dl = document.createElement("dl");
    dl.className = "kv";
    rows.forEach(([k, v]) => {
      const dt = document.createElement("dt");
      dt.textContent = k;
      const dd = document.createElement("dd");
      dd.textContent = v;
      if (k === "Last run" && sys.last_run) dd.title = new Date(sys.last_run * 1000).toLocaleString();
      dl.append(dt, dd);
    });
    box.appendChild(dl);
    renderReports(sys);
  }

  async function copyRunCommand() {
    const ok = await copyToClipboard("mvn test");
    toast(ok ? "Copied: mvn test" : "Couldn't copy automatically — run: mvn test", ok);
  }

  // ── command palette ──────────────────────────────────────────

  const palette = { items: [], shown: [], sel: 0 };

  function paletteCommands() {
    const cmds = [
      { g: "Go to", t: "Overview", run: () => go("overview") },
      { g: "Go to", t: "Configuration", run: () => go("config") },
      { g: "Go to", t: "Results", run: () => go("results") },
      { g: "Go to", t: "Activity", run: () => go("activity") },
      { g: "Open", t: "Test Hub", run: () => { location.href = "/hub"; } },
      { g: "Open", t: "Control Center", run: () => { location.href = "/control"; } },
      { g: "Open", t: "Test Wizard", run: () => { location.href = "/wizard"; } },
      { g: "Action", t: "Refresh data", hint: "R", run: () => loadAll() },
      { g: "Action", t: "Toggle light / dark theme", run: toggleTheme },
      { g: "Action", t: "Copy run command (mvn test)", run: copyRunCommand },
      { g: "Action", t: "Copy run summary (Markdown)", run: copySummary },
      { g: "Action", t: "Export HTML run report", run: exportReportHtml },
      { g: "Help", t: "Preferences…", run: openPrefs },
      { g: "Action", t: "Check environment health", run: () => { location.hash = "#overview"; checkHealth(); } },
      { g: "Action", t: "Compare two runs", run: () => { location.hash = "#results"; $("#comparePanel").scrollIntoView(); } },
      { g: "Action", t: "Switch density (compact / comfortable)", run: toggleDensity },
      { g: "Action", t: "Copy re-run command for failed tests", run: copyRerun },
      { g: "Help", t: "Keyboard shortcuts", run: showShortcuts },
      { g: "Action", t: "Export results as CSV", run: exportResultsCsv },
      { g: "Action", t: "Export config snapshot", run: exportSnapshot },
      { g: "Action", t: "Undo last change", run: undoLast },
      { g: "Action", t: "View pipeline-config.properties", run: () => openRawModal("pipeline-config.properties", "/api/pipeline-config/raw") },
      { g: "Action", t: "View test-config.properties", run: () => openRawModal("test-config.properties", "/api/test-config/raw") },
    ];
    sitesCache.forEach((site) => cmds.push({
      g: "Site", t: `${site.enabled ? "Disable" : "Enable"} ${site.name}`, hint: site.enabled ? "on" : "off",
      run: () => setSite(site.name, !site.enabled).catch((e) => toast(e.message, false)),
    }));
    return cmds;
  }

  function renderPalette() {
    const list = $("#paletteList");
    const q = $("#paletteInput").value.trim().toLowerCase().split(/\s+/).filter(Boolean);
    palette.shown = palette.items.filter((c) => {
      const hay = `${c.g} ${c.t}`.toLowerCase();
      return q.every((tok) => hay.includes(tok));
    });
    palette.sel = Math.min(palette.sel, Math.max(0, palette.shown.length - 1));
    list.innerHTML = "";
    if (!palette.shown.length) {
      const li = document.createElement("li");
      li.className = "palette-empty";
      li.textContent = "No matching command.";
      list.appendChild(li);
      $("#paletteInput").removeAttribute("aria-activedescendant");
      return;
    }
    palette.shown.forEach((c, i) => {
      const li = document.createElement("li");
      li.className = "palette-item";
      li.id = `pal-${i}`;
      li.setAttribute("role", "option");
      li.setAttribute("aria-selected", String(i === palette.sel));
      const g = document.createElement("span");
      g.className = "p-group";
      g.textContent = c.g;
      const t = document.createElement("span");
      t.textContent = c.t;
      li.append(g, t);
      if (c.hint) {
        const h = document.createElement("span");
        h.className = "p-hint";
        h.textContent = c.hint;
        li.appendChild(h);
      }
      li.addEventListener("mousemove", () => { if (palette.sel !== i) { palette.sel = i; markPalette(); } });
      li.addEventListener("click", () => runPalette(i));
      list.appendChild(li);
    });
    $("#paletteInput").setAttribute("aria-activedescendant", `pal-${palette.sel}`);
  }

  function markPalette() {
    $("#paletteList").querySelectorAll(".palette-item").forEach((li, i) => {
      li.setAttribute("aria-selected", String(i === palette.sel));
      if (i === palette.sel) { li.scrollIntoView({ block: "nearest" }); $("#paletteInput").setAttribute("aria-activedescendant", li.id); }
    });
  }

  function openPalette() {
    if (!$("#palette").hidden) return;
    const other = openModalEl();
    if (other) closeModal(other);
    palette.items = paletteCommands();
    palette.sel = 0;
    $("#paletteInput").value = "";
    renderPalette();
    openModal($("#palette"), $("#paletteInput"));
  }

  function runPalette(i) {
    const cmd = palette.shown[i];
    if (!cmd) return;
    closeModal($("#palette"));
    // After the dialog has handed focus back, so a command that opens another dialog keeps its own focus.
    setTimeout(() => { try { cmd.run(); } catch (e) { toast(e.message, false); } }, 0);
  }

  function wirePalette() {
    const isMac = /Mac|iPhone|iPad/.test(navigator.platform || navigator.userAgent || "");
    $("#paletteKbd").textContent = isMac ? "⌘ K" : "Ctrl K";
    $("#paletteBtn").addEventListener("click", openPalette);
    document.addEventListener("keydown", (e) => {
      if ((e.ctrlKey || e.metaKey) && !e.altKey && e.key.toLowerCase() === "k") { e.preventDefault(); openPalette(); }
    });
    const input = $("#paletteInput");
    input.addEventListener("input", () => { palette.sel = 0; renderPalette(); });
    input.addEventListener("keydown", (e) => {
      if (!palette.shown.length) return;
      if (e.key === "ArrowDown") { e.preventDefault(); palette.sel = (palette.sel + 1) % palette.shown.length; markPalette(); }
      else if (e.key === "ArrowUp") { e.preventDefault(); palette.sel = (palette.sel - 1 + palette.shown.length) % palette.shown.length; markPalette(); }
      else if (e.key === "Enter") { e.preventDefault(); runPalette(palette.sel); }
    });
  }

  // ── wiring ─────────────────────────────────────────────────

  async function loadAll() {
    setConn("loading…", "pill-muted");
    try {
      await Promise.all([loadSites(), loadPresets(), loadTestConfig(), loadResults(), loadAudit(),
        loadSystem().catch(() => {})]); // the environment card is a nicety: never fail the page over it
      setConn("connected", "pill-ok");
      setOffline(false);
    } catch (e) {
      if (e.network) setOffline(true);
      setConn(e.network ? "offline" : "error", "pill-bad");
      toast(e.message, false);
    }
  }

  // ── connection monitor + polling ─────────────────────────────
  // Results/audit can change between manual refreshes (someone runs `mvn test` in another terminal), so poll
  // gently. Config panels are never refreshed on this timer, so an in-progress edit is not clobbered. If the
  // server stops answering, say so (banner + pill) and poll faster until it is back, instead of silently
  // showing stale numbers under a green "connected" pill.

  const poll = { fails: 0, offline: false, timer: null, every: 15000 };   // every = 0 -> automatic refresh off (preference)

  function setOffline(v) {
    if (poll.offline === v) return;
    poll.offline = v;
    $("#offlineBanner").hidden = !v;
    if (v) setConn("offline", "pill-bad");
  }

  poll.now = async () => {
    const out = await Promise.allSettled([loadResults(), loadAudit(), loadSystem()]);
    const unreachable = out.every((o) => o.status === "rejected" && o.reason && o.reason.network);
    if (unreachable) {
      poll.fails += 1;
      if (poll.fails >= 2) setOffline(true);
    } else {
      const wasOffline = poll.offline;
      poll.fails = 0;
      if (wasOffline) { setOffline(false); loadAll(); }
    }
  };

  function schedulePoll() {
    clearTimeout(poll.timer);
    poll.timer = setTimeout(async () => {
      if (poll.offline || (!document.hidden && poll.every > 0 && $("#autoRefresh").checked)) await poll.now();
      schedulePoll();
    }, poll.offline ? 5000 : (poll.every > 0 ? poll.every : 15000));
  }

  showTab();
  const urlView = viewFromUrl();
  if (urlView) { setView(urlView); if (!location.hash) location.replace("#results"); }
  initPrefs();
  setTimeout(liveTick, 2500);
  window.addEventListener("hashchange", () => { showTab(); window.scrollTo(0, 0); });
  wireModal();
  wirePalette();
  wireResultsView();
  $("#refreshBtn").addEventListener("click", loadAll);
  $("#copyMvnBtn").addEventListener("click", copyRunCommand);
  $("#rerunBtn").addEventListener("click", copyRerun);
  $("#failExpandBtn").addEventListener("click", () => setAllFailuresOpen(true));
  $("#failCollapseBtn").addEventListener("click", () => setAllFailuresOpen(false));
  document.querySelectorAll("[data-copy-summary]").forEach((b) => b.addEventListener("click", copySummary));
  document.querySelectorAll("[data-export-report]").forEach((b) => b.addEventListener("click", exportReportHtml));
  $("#prefsBtn").addEventListener("click", openPrefs);
  $("#cmpBtn").addEventListener("click", runCompare);
  $("#healthBtn").addEventListener("click", checkHealth);
  $("#liveStop").addEventListener("click", stopLiveRun);
  $("#alertsDismiss").addEventListener("click", () => {
    try { localStorage.setItem(ALERT_DISMISS_KEY, $("#alertsBanner").dataset.sig || ""); } catch (e) { /* ignore */ }
    $("#alertsBanner").hidden = true;
  });
  wireViews();
  document.querySelectorAll("#prefsModal [data-pref]").forEach((sel) => {
    sel.addEventListener("change", () => savePref({ [sel.dataset.pref]: sel.dataset.pref === "refresh_s" ? Number(sel.value) : sel.value }));
  });
  // The header toggle (and the Hub / Control Center / Wizard toggles) save the theme themselves; keep the form in step.
  window.addEventListener("console-theme-change", (ev) => { prefsCache.theme = ev.detail.mode; syncPrefsForm(); });
  $("#runOnlySave").addEventListener("click", saveRunOnly);
  $("#runOnlyInput").addEventListener("keydown", (e) => { if (e.key === "Enter") saveRunOnly(); });
  $("#newGroupSave").addEventListener("click", addGroupOff);
  $("#newGroupInput").addEventListener("keydown", (e) => { if (e.key === "Enter") addGroupOff(); });
  $("#exportCsvBtn").addEventListener("click", exportResultsCsv);
  $("#undoBtn").addEventListener("click", undoLast);
  $("#exportSnapshotBtn").addEventListener("click", exportSnapshot);
  $("#importSnapshotInput").addEventListener("change", (e) => {
    const file = e.target.files[0];
    if (file) importSnapshot(file);
    e.target.value = ""; // allow re-selecting the same file next time
  });
  $("#testFilter").addEventListener("input", (e) => {
    state.testFilter = e.target.value;
    renderTestSections();
  });

  loadAll();
  schedulePoll();
})();
