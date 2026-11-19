/*
 * Shared appearance for every Console page (Overview, Test Hub, Control Center, Test Wizard):
 * light/dark theme and comfortable/compact density.
 *
 * Loaded from <head> WITHOUT defer so both are on <html> before the first paint (no flash). The Overview
 * page additionally reads the signed-in user's saved preferences from /api/prefs and applies them through
 * the functions below, so the same person gets the same Console in every browser; the values kept here in
 * localStorage are just the fast, flash-free cache of that choice.
 *
 * A theme toggle on any page ([data-theme-toggle]) also saves the choice to the server, best effort and
 * silent: the pages never disagree, and a failed save only means "not remembered on other browsers".
 * No inline script/style anywhere (CSP: default-src 'self').
 */
(function () {
  "use strict";
  var THEME_KEY = "dashboard-theme";
  var DENSITY_KEY = "dashboard-density";
  var root = document.documentElement;
  var mq = window.matchMedia ? window.matchMedia("(prefers-color-scheme: light)") : null;

  function read(key, allowed) {
    try { var v = localStorage.getItem(key); return allowed.indexOf(v) >= 0 ? v : null; } catch (e) { return null; }
  }
  function write(key, value) {
    try { if (value === null) localStorage.removeItem(key); else localStorage.setItem(key, value); } catch (e) { /* private mode */ }
  }
  function system() { return mq && mq.matches ? "light" : "dark"; }
  function current() { return root.getAttribute("data-theme") === "light" ? "light" : "dark"; }
  function mode() { return read(THEME_KEY, ["light", "dark"]) || "system"; }
  function density() { return root.getAttribute("data-density") === "compact" ? "compact" : "comfortable"; }

  function savePrefs(patch) {
    if (!window.fetch) return;
    try {
      fetch("/api/prefs", {
        method: "POST", credentials: "same-origin", keepalive: true,
        headers: { "Content-Type": "application/json" }, body: JSON.stringify(patch)
      }).catch(function () { /* not remembered elsewhere; the local choice still applies */ });
    } catch (e) { /* ignore */ }
  }

  function labelToggle(btn, t) {
    btn.setAttribute("aria-label", t === "light" ? "Switch to dark theme" : "Switch to light theme");
    btn.setAttribute("aria-pressed", String(t === "light"));
  }
  function apply(t) {
    root.setAttribute("data-theme", t);
    document.querySelectorAll("[data-theme-toggle]").forEach(function (b) { labelToggle(b, t); });
  }
  // m: "system" | "light" | "dark". "system" forgets the stored choice and follows the OS again.
  function setMode(m, opts) {
    if (m === "light" || m === "dark") { write(THEME_KEY, m); apply(m); }
    else { m = "system"; write(THEME_KEY, null); apply(system()); }
    if (!opts || opts.save !== false) savePrefs({ theme: m });
    window.dispatchEvent(new CustomEvent("console-theme-change", { detail: { mode: m } }));
    return m;
  }
  function toggle() { return setMode(current() === "light" ? "dark" : "light"); }

  function setDensity(d, opts) {
    d = d === "compact" ? "compact" : "comfortable";
    root.setAttribute("data-density", d);
    write(DENSITY_KEY, d);
    if (!opts || opts.save !== false) savePrefs({ density: d });
    return d;
  }

  apply(read(THEME_KEY, ["light", "dark"]) || system());
  root.setAttribute("data-density", read(DENSITY_KEY, ["compact", "comfortable"]) || "comfortable");

  if (mq && mq.addEventListener) {
    mq.addEventListener("change", function () { if (mode() === "system") apply(system()); });
  }
  // Keep several open tabs/pages in step when the choice is changed in another one.
  window.addEventListener("storage", function (e) {
    if (e.key === THEME_KEY) apply(read(THEME_KEY, ["light", "dark"]) || system());
    if (e.key === DENSITY_KEY) root.setAttribute("data-density", read(DENSITY_KEY, ["compact", "comfortable"]) || "comfortable");
  });
  document.addEventListener("DOMContentLoaded", function () {
    document.querySelectorAll("[data-theme-toggle]").forEach(function (b) {
      labelToggle(b, current());
      b.addEventListener("click", toggle);
    });
  });

  window.ConsoleTheme = { toggle: toggle, apply: apply, current: current, mode: mode, setMode: setMode, density: density, setDensity: setDensity };
})();
