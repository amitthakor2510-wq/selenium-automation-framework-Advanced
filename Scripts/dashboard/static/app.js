/*
 * Automation Console — frontend. No build step, no framework, no CDN
 * dependency (this has to work offline: Docker, LAN, no-internet dev
 * boxes). Talks to dashboard_server.py's JSON API; the browser's native
 * Basic-Auth prompt (triggered by the server's 401) handles the login,
 * so there is no custom auth form here.
 *
 * localStorage is used ONLY for the light/dark theme preference — a
 * per-browser UI setting, not data the server needs to know about.
 * Nothing else here persists client-side; every config change goes
 * straight to the server, which is the actual source of truth.
 */
(() => {
  "use strict";

  const $ = (sel, root = document) => root.querySelector(sel);

  const state = { testFilter: "" };
  const openSections = new Set(); // titles the user has expanded

  // ── theme ──────────────────────────────────────────────────

  function initTheme() {
    const saved = localStorage.getItem("dashboard-theme");
    if (saved === "light" || saved === "dark") {
      document.documentElement.setAttribute("data-theme", saved);
    }
    $("#themeToggle").addEventListener("click", () => {
      const current = document.documentElement.getAttribute("data-theme") === "light" ? "light" : "dark";
      const next = current === "light" ? "dark" : "light";
      document.documentElement.setAttribute("data-theme", next);
      localStorage.setItem("dashboard-theme", next);
    });
  }

  // ── fetch helpers ──────────────────────────────────────────

  async function api(path, options) {
    const res = await fetch(path, options);
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

  function toast(message, ok) {
    const el = $("#toast");
    el.textContent = message;
    el.className = `toast show ${ok ? "is-ok" : "is-error"}`;
    clearTimeout(toast._t);
    toast._t = setTimeout(() => { el.className = "toast"; }, 2600);
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
      btn.disabled = true;
      try {
        await onToggle(next);
        btn.setAttribute("aria-checked", String(next));
      } catch (e) {
        toast(e.message, false);
      } finally {
        btn.disabled = false;
      }
    });
    return btn;
  }

  // ── raw file modal ───────────────────────────────────────────

  async function openRawModal(path, apiPath) {
    try {
      const data = await api(apiPath);
      $("#rawModalTitle").textContent = data.path || path;
      $("#rawModalBody").textContent = data.text || "(empty)";
      $("#rawModal").hidden = false;
    } catch (e) {
      toast(e.message, false);
    }
  }

  function wireModal() {
    const modal = $("#rawModal");
    modal.querySelectorAll("[data-close]").forEach((el) =>
      el.addEventListener("click", () => { modal.hidden = true; }));
    document.addEventListener("keydown", (e) => {
      if (e.key === "Escape" && !modal.hidden) modal.hidden = true;
    });
    $("#sitesRawBtn").addEventListener("click", () => openRawModal("pipeline-config.properties", "/api/pipeline-config/raw"));
    $("#testsRawBtn").addEventListener("click", () => openRawModal("test-config.properties", "/api/test-config/raw"));
  }

  // ── sites panel ────────────────────────────────────────────

  let suitesBySite = {};

  async function loadSites() {
    const [sitesData, suitesData] = await Promise.all([
      api("/api/pipeline-config"),
      api("/api/suites").catch(() => ({ bySite: {} })), // suite listing is a convenience — don't block Sites on it
    ]);
    suitesBySite = suitesData.bySite || {};
    renderSites(sitesData.sites);
  }

  function renderSites(sites) {
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
        const data = await postJson("/api/pipeline-config/site", { name: site.name, enabled: next });
        toast(`${site.name} ${next ? "enabled" : "disabled"}`, true);
        renderSites(data.sites);
        loadAudit().catch(() => {});
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
      toast(ok ? `Copied: ${cmd}` : cmd, ok);
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
        <span class="test-section-caret"></span>
        <span class="test-section-title">${escapeHtml(section.title)}</span>
        <span class="test-section-count">${section.tests.length - disabledCount}/${section.tests.length} on</span>
        <span class="test-section-actions">
          <button type="button" class="section-bulk-btn" data-bulk="on">Enable all</button>
          <button type="button" class="section-bulk-btn" data-bulk="off">Disable all</button>
        </span>
      `;
      head.querySelector('[data-bulk="on"]').addEventListener("click", (e) => {
        e.stopPropagation();
        bulkSection(section, true);
      });
      head.querySelector('[data-bulk="off"]').addEventListener("click", (e) => {
        e.stopPropagation();
        bulkSection(section, false);
      });
      head.addEventListener("click", () => {
        if (openSections.has(section.title)) openSections.delete(section.title);
        else openSections.add(section.title);
        wrap.classList.toggle("is-open");
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

  async function bulkSection(section, enabled) {
    const names = section.tests.map((t) => t.name);
    if (!enabled && !confirm(`Disable all ${names.length} tests in "${section.title}"?`)) {
      return;
    }
    try {
      testConfigCache = await postJson("/api/test-config/bulk-section", { names, enabled });
      toast(`${section.title}: ${enabled ? "all enabled" : "all disabled"} (${names.length} tests)`, true);
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

  function statTile(value, label, cls) {
    const div = document.createElement("div");
    div.className = `stat ${cls || ""}`;
    div.innerHTML = `<span class="stat-value">${value}</span><span class="stat-label">${label}</span>`;
    return div;
  }

  async function loadResults() {
    const data = await api("/api/results");
    const hero = $("#heroRow");
    hero.innerHTML = "";
    const sf = data.surefire || { total: 0, passed: 0, failed: 0, skipped: 0 };

    hero.appendChild(statTile(sf.total, "tests run", ""));
    hero.appendChild(statTile(sf.passed, "passed", "is-pass"));
    hero.appendChild(statTile(sf.failed, sf.failed === 1 ? "failure" : "failures", sf.failed > 0 ? "is-fail" : ""));
    hero.appendChild(statTile(sf.skipped, "skipped", sf.skipped > 0 ? "is-warn" : ""));

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
    lastByClass = sf.by_class || [];
    renderSummary(sf, recordHistory(sf));
    renderResultsTable(lastByClass);
    $("#resultsUpdated").textContent = `Updated ${new Date().toLocaleTimeString()}`;
    renderHealing(healing);
    renderFlaky(data.flaky);
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
      return Array.isArray(h) ? h.filter((x) => x && typeof x.rate === "number") : [];
    } catch (e) { return []; }
  }

  // One entry per distinct result set, so polling an unchanged run never inflates the trend.
  function recordHistory(sf) {
    if (!sf || !sf.total) return readHistory();
    const hist = readHistory();
    const sig = `${sf.total}/${sf.passed}/${sf.failed}/${sf.skipped}`;
    if (!hist.length || hist[hist.length - 1].sig !== sig) {
      hist.push({ t: Date.now(), sig, rate: Math.round((1000 * sf.passed) / sf.total) / 10, failed: sf.failed });
      try { localStorage.setItem(HISTORY_KEY, JSON.stringify(hist.slice(-30))); } catch (e) { /* private mode */ }
    }
    return hist.slice(-30);
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
      wrap.innerHTML = '<span class="field-note">Pass-rate trend appears after two different runs.</span>';
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
      const a = k === "class" ? x.class.toLowerCase() : x[k];
      const b = k === "class" ? y.class.toLowerCase() : y[k];
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
        <td>${barSvg(c)}</td>
      </tr>`).join("");
    container.innerHTML = `
      <div class="table-scroll"><table class="results">
        <thead><tr>${th("class", "Class")}${th("total", "Total", 1)}${th("passed", "Passed", 1)}${th("failed", "Failed", 1)}${th("skipped", "Skipped", 1)}<th>Mix</th></tr></thead>
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
      if (e.ctrlKey || e.metaKey || e.altKey || tag === "input" || tag === "textarea" || tag === "select" || e.target.isContentEditable) return;
      if (e.key === "/") { e.preventDefault(); $("#resultsSearch").focus(); }
      else if (e.key === "r") { loadAll(); }
    });
    document.addEventListener("visibilitychange", () => {
      if (!document.hidden && auto.checked) { loadResults().catch(() => {}); loadAudit().catch(() => {}); }
    });
  }

  function exportResultsCsv() {
    if (!lastByClass.length) {
      toast("Nothing to export yet.", false);
      return;
    }
    const header = "class,total,passed,failed,skipped";
    // RFC 4180 quoting, plus a leading ' on cells a spreadsheet would run as a formula (= + - @).
    const cell = (v) => {
      let t = String(v);
      if (/^[=+\-@\t\r]/.test(t)) t = "'" + t;
      return /[",\n\r]/.test(t) ? '"' + t.replace(/"/g, '""') + '"' : t;
    };
    const lines = visibleRows(lastByClass).map((c) => [c.class, c.total, c.passed, c.failed, c.skipped].map(cell).join(","));
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

  function renderFlaky(flaky) {
    const container = $("#flakyList");
    const items = flaky && Array.isArray(flaky.flaky) ? flaky.flaky : [];
    if (!items.length) {
      container.innerHTML = `<div class="dd-empty">No flaky tests detected (or no gh-pages run history available locally).</div>`;
      return;
    }
    container.innerHTML = items.map((t) => {
      const ticks = (t.recent_statuses || []).map((s) =>
        `<span class="dd-tick ${s === "pass" ? "pass" : s === "fail" ? "fail" : "skip"}" title="${escapeHtml(s)}"></span>`
      ).join("");
      return `
        <div class="dd-row">
          <div class="dd-title">${escapeHtml(t.test || "")}</div>
          <div class="dd-stat-row">
            <span class="dd-stat-pass">${t.pass_count} pass</span>
            <span class="dd-stat-fail">${t.fail_count} fail</span>
            <span class="dd-sparkline">${ticks}</span>
          </div>
        </div>
      `;
    }).join("");
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

  // ── wiring ─────────────────────────────────────────────────

  async function loadAll() {
    setConn("loading…", "pill-muted");
    try {
      await Promise.all([loadSites(), loadPresets(), loadTestConfig(), loadResults(), loadAudit()]);
      setConn("connected", "pill-ok");
    } catch (e) {
      setConn("error", "pill-bad");
      toast(e.message, false);
    }
  }

  initTheme();
  wireModal();
  wireResultsView();
  $("#refreshBtn").addEventListener("click", loadAll);
  $("#runOnlySave").addEventListener("click", saveRunOnly);
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
  // Results/audit can change between manual refreshes (someone runs
  // `mvn test` or edits a file directly in another terminal) — poll
  // gently. Config panels don't self-refresh on this timer so an
  // in-progress edit is never clobbered mid-type.
  setInterval(() => {
    if (document.hidden || !$("#autoRefresh").checked) return;
    loadResults().catch(() => {});
    loadAudit().catch(() => {});
  }, 15000);
})();
