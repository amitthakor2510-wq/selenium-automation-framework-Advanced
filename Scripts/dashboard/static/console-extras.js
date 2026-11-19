/* Console extras: the Quality gate panel (Overview) and the live status footer.
 *
 * Separate from app.js on purpose: it only reads two read-only endpoints (/api/quality-gate, /api/system),
 * never touches configuration, and a failure here can never break the rest of the page. Everything from the
 * server is written with textContent / DOM nodes, never innerHTML.
 */
(function () {
  "use strict";

  const $ = (sel) => document.querySelector(sel);
  const GATE_EVERY_MS = 30000;
  const FOOT_EVERY_MS = 60000;
  const LABEL = { pass: "PASS", fail: "FAIL", unknown: "NO DATA", skip: "OFF" };
  const ICON = { pass: "✓", fail: "✕", unknown: "?", skip: "–" };
  let lastGate = null;

  function el(tag, cls, text) {
    const n = document.createElement(tag);
    if (cls) n.className = cls;
    if (text !== undefined) n.textContent = text;
    return n;
  }

  async function getJson(path) {
    const res = await fetch(path, { headers: { Accept: "application/json" }, cache: "no-store" });
    if (!res.ok) throw new Error(`${path} → HTTP ${res.status}`);
    return res.json();
  }

  function flash(btn, text) {
    const old = btn.dataset.label || btn.textContent;
    btn.dataset.label = old;
    btn.textContent = text;
    clearTimeout(btn._t);
    btn._t = setTimeout(() => { btn.textContent = old; }, 1800);
  }

  async function copy(text, btn) {
    try {
      await navigator.clipboard.writeText(text);
      flash(btn, "Copied ✓");
    } catch (e) {
      // Clipboard needs a secure context; fall back to a selectable prompt rather than failing silently.
      window.prompt("Copy this:", text);
    }
  }

  // ── Quality gate ─────────────────────────────────────────────
  function fmtValue(check) {
    if (check.actual === null || check.actual === undefined) return "—";
    const unit = check.id === "pass_rate" || check.id === "coverage" ? "%" : check.id === "slowdown" ? "%" : "";
    return `${check.actual}${unit}`;
  }

  function renderGate(g) {
    lastGate = g;
    const verdict = $("#gateVerdict");
    verdict.className = `gate-verdict gate-${g.status}`;
    verdict.textContent = g.status === "unknown" ? "NO DATA" : g.status === "pass" ? "READY" : "BLOCKED";
    $("#gateSummary").textContent = g.summary || "";

    const body = $("#gateChecks");
    body.textContent = "";
    if (!g.checks || !g.checks.length) {
      body.appendChild(el("p", "empty gate-empty", "Run the tests (for example mvn test) and this panel will judge the result."));
      return;
    }
    const list = el("ul", "gate-list");
    g.checks.forEach((c) => {
      const li = el("li", `gate-row gate-row-${c.status}`);
      const badge = el("span", "gate-badge", ICON[c.status] || "?");
      badge.setAttribute("aria-hidden", "true");
      const main = el("div", "gate-main");
      const title = el("div", "gate-title");
      title.appendChild(el("strong", null, c.label));
      title.appendChild(el("span", "gate-state", LABEL[c.status] || c.status));
      main.appendChild(title);
      main.appendChild(el("div", "gate-detail", c.detail || ""));
      const val = el("div", "gate-value", fmtValue(c));
      li.append(badge, main, val);
      list.appendChild(li);
    });
    body.appendChild(list);
  }

  function gateUnavailable() {
    const verdict = $("#gateVerdict");
    verdict.className = "gate-verdict gate-unknown";
    verdict.textContent = "UNAVAILABLE";
  }

  async function loadGate() {
    if (document.hidden || !$("#gatePanel")) return;
    try { renderGate(await getJson("/api/quality-gate")); } catch (e) { gateUnavailable(); }
  }

  // ── Status footer ────────────────────────────────────────────
  function fmtUptime(s) {
    if (typeof s !== "number" || s < 0) return "";
    const d = Math.floor(s / 86400), h = Math.floor((s % 86400) / 3600), m = Math.floor((s % 3600) / 60);
    return d ? `${d}d ${h}h` : h ? `${h}h ${m}m` : `${m}m`;
  }

  async function loadFooter() {
    const slot = $("#footStatus");
    if (!slot || document.hidden) return;
    try {
      const sys = await getJson("/api/system");
      const parts = [];
      const a = sys.auth || {};
      if (a.user) parts.push(`Signed in as ${a.user}${a.role ? ` (${a.role})` : ""}`);
      if (sys.server && typeof sys.server.uptime_s === "number") parts.push(`Console up ${fmtUptime(sys.server.uptime_s)}`);
      parts.push(sys.runner && sys.runner.enabled ? "Runner on" : "Runner off");
      slot.textContent = parts.join("  ·  ");
    } catch (e) {
      slot.textContent = "";
    }
  }

  function wire() {
    const ci = $("#gateCopyCi");
    if (ci) ci.addEventListener("click", () => copy(
      `curl -fsS -u dashboard:"$DASHBOARD_PASSWORD" "${location.origin}/api/quality-gate?strict=1"`, ci));
    const js = $("#gateCopyJson");
    if (js) js.addEventListener("click", () => {
      if (lastGate) copy(JSON.stringify(lastGate, null, 2), js);
    });
    // The Console's own Refresh button / "r" shortcut reload its data; refresh the gate alongside them.
    const refresh = $("#refreshBtn");
    if (refresh) refresh.addEventListener("click", loadGate);
    document.addEventListener("keydown", (e) => {
      const t = e.target;
      const typing = t && (t.tagName === "INPUT" || t.tagName === "TEXTAREA" || t.tagName === "SELECT" || t.isContentEditable);
      if (!typing && !e.ctrlKey && !e.metaKey && !e.altKey && e.key === "r") loadGate();
    });
    document.addEventListener("visibilitychange", () => { if (!document.hidden) { loadGate(); loadFooter(); } });
  }

  wire();
  loadGate();
  loadFooter();
  setInterval(loadGate, GATE_EVERY_MS);
  setInterval(loadFooter, FOOT_EVERY_MS);
})();
