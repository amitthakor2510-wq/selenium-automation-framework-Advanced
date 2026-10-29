/* Control Center. No inline script/style (CSP) and no innerHTML with data: everything
   server- or user-provided goes through textContent / setAttribute. */
(function () {
  "use strict";
  var S = { data: null, globals: {}, touched: {}, runs: [], active: null, logOffset: 0, logText: "",
            filter: "", group: "", timer: null, logTimer: null };

  function $(id) { return document.getElementById(id); }
  function el(tag, attrs, kids) {
    var n = document.createElement(tag);
    Object.keys(attrs || {}).forEach(function (k) {
      var v = attrs[k];
      if (k === "text") n.textContent = v;
      else if (k === "class") n.className = v;
      else if (k.slice(0, 2) === "on") n.addEventListener(k.slice(2), v);
      else if (v === true) n.setAttribute(k, "");
      else if (v !== false && v != null) n.setAttribute(k, v);
    });
    (kids || []).forEach(function (c) { if (c) n.appendChild(typeof c === "string" ? document.createTextNode(c) : c); });
    return n;
  }
  function api(method, url, body) {
    var o = { method: method, headers: {} };
    if (method !== "GET") { o.headers["Content-Type"] = "application/json"; o.body = JSON.stringify(body || {}); }
    return fetch(url, o).then(function (r) {
      return r.json().then(function (j) { if (!r.ok) throw new Error(j.error || ("HTTP " + r.status)); return j; });
    });
  }
  function banner(msg, isErr) {
    var b = $("banner");
    if (!msg) { b.hidden = true; return; }
    b.textContent = msg; b.className = "cc-banner" + (isErr ? " err" : ""); b.hidden = false;
  }
  function canRun() { return !!(S.data && S.data.runnerEnabled && S.data.permissions["run.start"]); }

  /* ── switch controls ─────────────────────────────────────── */
  function control(o, store, touchedStore, idPrefix) {
    var id = idPrefix + o.key;
    var cur = store[o.key] !== undefined ? store[o.key] : o.default;
    function mark(v) { store[o.key] = v; touchedStore[o.key] = true; }
    if (o.type === "bool") {
      var cb = el("input", { type: "checkbox", id: id });
      cb.checked = cur === true || cur === "true";
      cb.addEventListener("change", function () { mark(cb.checked); });
      return el("label", { class: "cc-switch", for: id }, [cb, el("span", { class: "track" }), el("span", { text: o.label })]);
    }
    var input;
    if (o.type === "enum") {
      input = el("select", { id: id }, o.values.map(function (v) { return el("option", { value: v, text: v }); }));
      input.value = cur != null ? cur : o.values[0];
      input.addEventListener("change", function () { mark(input.value); });
    } else if (o.type === "int") {
      input = el("input", { type: "number", id: id, min: o.min, max: o.max });
      if (cur != null) input.value = cur;
      input.addEventListener("input", function () { if (input.value === "") { delete store[o.key]; delete touchedStore[o.key]; } else mark(input.value); });
    } else {
      input = el("input", { type: "text", id: id, maxlength: "2000", autocomplete: "off", spellcheck: "false" });
      if (cur != null) input.value = cur;
      input.addEventListener("input", function () { mark(input.value); });
    }
    if (o.example && input.tagName === "INPUT") input.setAttribute("placeholder", o.example);
    var kids = [el("label", { for: id, text: o.label + (o.required ? " *" : "") }), input];
    if (o.help) kids.push(el("small", { class: "cc-help", text: o.help }));
    return el("div", { class: "cc-field" }, kids);
  }

  /* ── globals ─────────────────────────────────────────────── */
  function renderGlobals() {
    var box = $("globals"); box.textContent = "";
    try { var saved = JSON.parse(localStorage.getItem("cc.globals") || "{}"); Object.keys(saved).forEach(function (k) { S.globals[k] = saved[k]; S.touched[k] = true; }); } catch (e) { /* storage unavailable */ }
    S.data.globalOptions.forEach(function (o) {
      var c = control(o, S.globals, S.touched, "g-");
      c.addEventListener("change", persistGlobals); c.addEventListener("input", persistGlobals);
      box.appendChild(c);
    });
  }
  function persistGlobals() {
    var out = {}; Object.keys(S.touched).forEach(function (k) { if (S.globals[k] !== undefined) out[k] = S.globals[k]; });
    try { localStorage.setItem("cc.globals", JSON.stringify(out)); } catch (e) { /* ignore */ }
  }
  function globalPayload() {
    var out = {}; Object.keys(S.touched).forEach(function (k) { if (S.globals[k] !== undefined) out[k] = S.globals[k]; });
    return out;
  }

  /* ── cards ───────────────────────────────────────────────── */
  function card(f) {
    var vals = {}, touched = {}, extras = {};
    var blocked = f.blockers.length > 0;
    var badges = el("div", { class: "cc-badges" });
    if (f.risk === "system") badges.appendChild(el("span", { class: "cc-badge warn", text: "system-level" }));
    f.blockers.forEach(function (b) { badges.appendChild(el("span", { class: "cc-badge fail", text: b })); });
    f.warnings.forEach(function (w) { badges.appendChild(el("span", { class: "cc-badge warn", text: w })); });

    var opts = el("div", { class: "cc-opts" });
    var any = false;
    if (f.pickSite) {
      any = true;
      var siteVals = {}; extras.site = siteVals;
      var siteOpt = { key: "site", label: "Site", type: "enum", values: S.data.sites, default: f.site || S.data.sites[0] };
      siteVals.site = siteOpt.default;
      var sc = control(siteOpt, siteVals, {}, "s-" + f.id + "-");
      opts.appendChild(sc);
    }
    if (f.siteArg) {
      any = true;
      var sv = { site: "demoqa" }; extras.siteArg = sv;
      opts.appendChild(control({ key: "site", label: "Site", type: "enum", values: S.data.sites, default: S.data.sites.indexOf("demoqa") >= 0 ? "demoqa" : S.data.sites[0] }, sv, {}, "a-" + f.id + "-"));
      opts.appendChild(control({ key: "suiteFile", label: "Suite file name (optional, e.g. demoqa-smoke)", type: "name" }, sv, {}, "a2-" + f.id + "-"));
    }
    if (f.tags) {
      any = true;
      var tv = {}; extras.tags = tv;
      opts.appendChild(control({ key: "cucumber.filter.tags", label: "Cucumber tags (e.g. @smoke and not @slow)", type: "name" }, tv, {}, "t-" + f.id + "-"));
    }
    var needed = el("div", { class: "cc-needed" });
    var requiredOpts = f.options.filter(function (o) { return o.required; });
    f.options.forEach(function (o) {
      any = true;
      (o.required ? needed : opts).appendChild(control(o, vals, touched, "o-" + f.id + "-"));
    });
    if (f.globals) opts.appendChild(el("p", { class: "cc-mono", text: "Also uses the Run switches at the top." }));
    function missing() {
      return requiredOpts.filter(function (o) { var v = vals[o.key] !== undefined ? vals[o.key] : o.default; return v == null || String(v).trim() === ""; });
    }
    var hint = el("span", { class: "cc-mono" });
    function sync() {
      var m = missing();
      run.disabled = blocked || !canRun() || m.length > 0;
      hint.textContent = m.length ? "Needed first: " + m.map(function (o) { return o.label; }).join(", ") : "max " + f.timeout + " min";
    }
    needed.addEventListener("input", sync); needed.addEventListener("change", sync);

    var run = el("button", { class: "btn btn-primary", type: "button", text: "Run", disabled: true });
    run.addEventListener("click", function () {
      var options = {};
      if (f.globals) { var g = globalPayload(); Object.keys(g).forEach(function (k) { options[k] = g[k]; }); }
      f.options.forEach(function (o) {
        var must = ((o.type === "url" || o.type === "name" || o.type === "relurl" || o.type === "ref") && o.default != null) || o.required;
        if (touched[o.key] || must) { var v = vals[o.key] !== undefined ? vals[o.key] : o.default; if (v !== undefined && v !== "") options[o.key] = v; }
      });
      if (extras.site && extras.site.site) options.site = extras.site.site;
      if (extras.siteArg) { options.site = extras.siteArg.site; if (extras.siteArg.suiteFile) options.suiteFile = extras.siteArg.suiteFile; }
      if (extras.tags && extras.tags["cucumber.filter.tags"]) options["cucumber.filter.tags"] = extras.tags["cucumber.filter.tags"];
      run.disabled = true;
      api("POST", "/api/features/run", { feature: f.id, options: options })
        .then(function (st) { banner(""); S.active = st.id; refreshRuns(true); })
        .catch(function (e) { banner(f.title + ": " + e.message, true); })
        .then(sync);
    });

    var kids = [el("h3", { text: f.title }), el("p", { class: "cc-desc", text: f.desc }), badges];
    if (requiredOpts.length) { kids.push(el("div", { class: "cc-need-title", text: "Needed before it can run" })); kids.push(needed); }
    var optCount = f.options.length - requiredOpts.length;
    if (optCount > 0 || f.globals || f.pickSite || f.siteArg || f.tags) {
      kids.push(el("details", {}, [el("summary", { text: requiredOpts.length ? "Optional — helps the test" : "Switches" }), opts]));
    }
    kids.push(el("div", { class: "cc-card-foot" }, [run, hint]));
    sync();
    return el("div", { class: "cc-card" + (blocked ? " blocked" : ""), id: "card-" + f.id, "data-search": (f.title + " " + f.desc + " " + f.group + " " + f.id).toLowerCase() }, kids);
  }

  function renderGroups() {
    var host = $("groups"); host.textContent = "";
    var q = S.filter.toLowerCase();
    S.data.groups.forEach(function (g) {
      if (S.group && S.group !== g) return;
      var cards = S.data.features.filter(function (f) { return f.group === g; })
        .filter(function (f) { return !q || (f.title + " " + f.desc + " " + f.group + " " + f.id).toLowerCase().indexOf(q) >= 0; });
      if (!cards.length) return;
      host.appendChild(el("section", { class: "cc-group" }, [el("h2", { text: g + " (" + cards.length + ")" }),
        el("div", { class: "cc-cards" }, cards.map(card))]));
    });
    if (!host.firstChild) host.appendChild(el("p", { class: "cc-empty", text: "No feature matches." }));
  }

  function renderChips() {
    var box = $("chips"); box.textContent = "";
    [""].concat(S.data.groups).forEach(function (g) {
      var b = el("button", { class: "cc-chip", type: "button", text: g || "All", "aria-pressed": String(S.group === g) });
      b.addEventListener("click", function () { S.group = g; renderChips(); renderGroups(); });
      box.appendChild(b);
    });
  }

  function renderBundles() {
    var box = $("bundles"); box.textContent = "";
    S.data.bundles.forEach(function (b) {
      var btn = el("button", { class: "btn btn-primary btn-small", type: "button", text: "Run bundle", disabled: !canRun() });
      btn.addEventListener("click", function () {
        btn.disabled = true;
        api("POST", "/api/features/bundle", { bundle: b.id, options: globalPayload() })
          .then(function (r) { banner(r.warning ? "Bundle partly queued: " + r.warning : ""); if (r.runs.length) S.active = r.runs[0].id; refreshRuns(true); })
          .catch(function (e) { banner(b.title + ": " + e.message, true); })
          .then(function () { btn.disabled = !canRun(); });
      });
      box.appendChild(el("div", { class: "cc-bundle" }, [el("h3", { text: b.title }), el("p", { text: b.desc }), btn]));
    });
  }

  /* ── runs ────────────────────────────────────────────────── */
  function when(s) { return s ? s.replace("T", " ").replace("Z", "") : ""; }
  function renderRuns() {
    var box = $("runs"); box.textContent = "";
    var list = S.runs.filter(function (r) { return r.type === "feature" || r.type; }).slice(0, 25);
    if (!list.length) { box.appendChild(el("p", { class: "cc-empty", text: "No runs yet — press Run on any card." })); return; }
    list.forEach(function (r) {
      var b = el("button", { class: "cc-run", type: "button", "aria-current": String(r.id === S.active) }, [
        el("div", { class: "row" }, [el("strong", { text: r.name || r.id }), el("span", { class: "st-" + r.state, text: r.state + (r.queuePosition ? " #" + r.queuePosition : "") })]),
        el("div", { class: "cc-mono", text: when(r.createdAt) + (r.startedBy ? " · " + r.startedBy : "") })]);
      b.addEventListener("click", function () { selectRun(r.id); });
      box.appendChild(b);
    });
  }
  function refreshRuns(selectNew) {
    return api("GET", "/api/runs").then(function (j) {
      S.runs = j.runs || []; renderRuns();
      if (selectNew && S.active) selectRun(S.active);
      else if (S.active) paintActive();
    }).catch(function () { /* transient */ });
  }
  function current() { return S.runs.filter(function (r) { return r.id === S.active; })[0]; }
  function selectRun(id) {
    S.active = id; S.logOffset = 0; S.logText = ""; $("log").textContent = ""; $("logPanel").hidden = false;
    renderRuns(); paintActive(); pollLog();
  }
  function paintActive() {
    var r = current(); if (!r) return;
    $("logTitle").textContent = r.name || r.id;
    $("logMeta").textContent = r.state + (r.exitCode != null ? " · exit " + r.exitCode : "") + (r.message ? " · " + r.message : "") + (r.command ? "  —  " + r.command : "");
    var acts = $("logActions"); acts.textContent = "";
    var live = r.state === "queued" || r.state === "running";
    if (live && S.data.permissions["run.cancel"]) {
      acts.appendChild(el("button", { class: "btn btn-ghost btn-small", type: "button", text: "Cancel", onclick: function () {
        api("POST", "/api/runs/" + r.id + "/cancel").then(function () { refreshRuns(); }).catch(function (e) { banner(e.message, true); }); } }));
    }
    if (!live && canRun()) {
      acts.appendChild(el("button", { class: "btn btn-ghost btn-small", type: "button", text: "Re-run", onclick: function () {
        api("POST", "/api/runs/" + r.id + "/rerun").then(function (st) { S.active = st.id; refreshRuns(true); }).catch(function (e) { banner(e.message, true); }); } }));
    }
    var rb = $("resultBox"), key = r.id + ":" + r.state;
    if (live) { rb.textContent = ""; S.resultKey = null; }
    else if (S.resultKey !== key) { S.resultKey = key; window.RunResults.load(rb, r.id); }
    var links = $("logLinks"); links.textContent = "";
    if (r.reportLink) links.appendChild(el("a", { class: "btn btn-ghost btn-small", href: r.reportLink, target: "_blank", rel: "noopener", text: "Run summary" }));
    links.appendChild(el("a", { class: "btn btn-ghost btn-small", href: "/api/runs/" + r.id + "/files/run.log", target: "_blank", rel: "noopener", text: "Full log" }));
    (r.reports || []).forEach(function (n) {
      links.appendChild(el("a", { class: "btn btn-ghost btn-small", href: "/api/runs/" + r.id + "/files/report/" + encodeURIComponent(n) + ".zip", text: n + ".zip" }));
    });
  }
  function pollLog() {
    clearTimeout(S.logTimer);
    if (!S.active) return;
    var id = S.active;
    api("GET", "/api/runs/" + id + "/log?offset=" + S.logOffset).then(function (j) {
      if (id !== S.active) return;
      if (j.text) {
        S.logText = (S.logText + j.text).slice(-200000);
        var pre = $("log"); var atEnd = pre.scrollTop + pre.clientHeight >= pre.scrollHeight - 30;
        pre.textContent = S.logText; if (atEnd) pre.scrollTop = pre.scrollHeight;
      }
      S.logOffset = j.offset;
    }).catch(function () { /* transient */ }).then(function () {
      var r = current();
      S.logTimer = setTimeout(pollLog, r && (r.state === "queued" || r.state === "running") ? 1500 : 6000);
    });
  }

  /* ── boot ────────────────────────────────────────────────── */
  function boot() {
    api("GET", "/api/features").then(function (d) {
      S.data = d;
      $("whoami").textContent = d.user + " (" + d.role + ")";
      var rp = $("runnerPill"); rp.textContent = d.runnerEnabled ? "runner ON" : "runner OFF";
      rp.className = "pill " + (d.runnerEnabled ? "pill-ok" : "pill-muted");
      if (!d.runnerEnabled) banner("The runner is switched off, so features can be browsed but not started. Start the dashboard with Scripts/start-dashboard.sh (it turns the runner on).", false);
      else if (!d.permissions["run.start"]) banner("Your role (" + d.role + ") can look but not start runs.", false);
      var q = /[?&]group=([^&]+)/.exec(location.search);
      if (q && d.groups.indexOf(decodeURIComponent(q[1])) >= 0) S.group = decodeURIComponent(q[1]);
      renderGlobals(); renderBundles(); renderChips(); renderGroups();
      var fm = /[?&]feature=([A-Za-z0-9:_-]+)/.exec(location.search);
      var target = fm && document.getElementById("card-" + fm[1]);
      if (target) { target.classList.add("cc-spot"); var dt = target.querySelector("details"); if (dt) dt.open = true; target.scrollIntoView({ block: "center" }); }
      $("search").addEventListener("input", function (e) { S.filter = e.target.value; renderGroups(); });
      refreshRuns(false).then(function () { if (S.runs.length && !S.active) selectRun(S.runs[0].id); });
      S.timer = setInterval(function () { refreshRuns(false); }, 3000);
    }).catch(function (e) { banner("Could not load the feature catalog: " + e.message, true); });
  }
  boot();
})();
