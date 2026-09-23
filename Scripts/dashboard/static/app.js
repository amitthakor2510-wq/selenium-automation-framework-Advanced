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

  // ── switch widget ──────────────────────────────────────────

  function makeSwitch(checked, onToggle) {
    const btn = document.createElement("button");
    btn.type = "button";
    btn.className = "switch";
    btn.setAttribute("role", "switch");
    btn.setAttribute("aria-checked", String(checked));
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

  async function loadSites() {
    const data = await api("/api/pipeline-config");
    renderSites(data.sites);
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
      }));

      list.appendChild(row);
    });
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
        <span class="test-section-title" style="flex:1;margin-left:6px;">${escapeHtml(section.title)}</span>
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
        }));
        rows.appendChild(row);
      });
      wrap.appendChild(rows);
      container.appendChild(wrap);
    });
  }

  async function bulkSection(section, enabled) {
    const names = section.tests.map((t) => t.name);
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
      }));
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

    lastByClass = sf.by_class || [];
    renderResultsTable(lastByClass);
    renderHealing(healing);
    renderFlaky(data.flaky);
  }

  function renderResultsTable(byClass) {
    const container = $("#resultsTable");
    if (!byClass.length) {
      container.innerHTML = `<div class="results-empty">Nothing here yet — run tests locally, then Refresh.</div>`;
      return;
    }
    const rows = byClass.map((c) => `
      <tr>
        <td><span class="dot ${c.failed > 0 ? "fail" : "pass"}"></span><span class="mono">${escapeHtml(c.class)}</span></td>
        <td class="num">${c.total}</td>
        <td class="num">${c.passed}</td>
        <td class="num">${c.failed}</td>
        <td class="num">${c.skipped}</td>
      </tr>
    `).join("");
    container.innerHTML = `
      <table class="results">
        <thead><tr><th>Class</th><th>Total</th><th>Passed</th><th>Failed</th><th>Skipped</th></tr></thead>
        <tbody>${rows}</tbody>
      </table>
    `;
  }

  function exportResultsCsv() {
    if (!lastByClass.length) {
      toast("Nothing to export yet.", false);
      return;
    }
    const header = "class,total,passed,failed,skipped";
    const lines = lastByClass.map((c) => [c.class, c.total, c.passed, c.failed, c.skipped].join(","));
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
    if (isNaN(d.getTime())) return iso;
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
  $("#refreshBtn").addEventListener("click", loadAll);
  $("#runOnlySave").addEventListener("click", saveRunOnly);
  $("#newGroupSave").addEventListener("click", addGroupOff);
  $("#newGroupInput").addEventListener("keydown", (e) => { if (e.key === "Enter") addGroupOff(); });
  $("#exportCsvBtn").addEventListener("click", exportResultsCsv);
  $("#undoBtn").addEventListener("click", undoLast);
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
    loadResults().catch(() => {});
    loadAudit().catch(() => {});
  }, 15000);
})();
