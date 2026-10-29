/* Test wizard — six steps, one page. No inline script/style (the console's CSP forbids it) and no
   innerHTML with data: everything user- or server-provided goes through textContent. */
(function () {
  "use strict";
  var STEPS = [
    ["Test type", "What do you want to test?"],
    ["Target", "Where does it run?"],
    ["Details", "What should it do?"],
    ["Reports & evidence", "What should you get back?"],
    ["Review", "Check it, save it as a template if you want to re-run it."],
    ["Run", "Run it here or hand it to your CI."]
  ];
  var S = { step: 0, opts: null, form: null, spec: blankSpec(), result: { errors: [], warnings: [] },
            templates: [], runId: null, poller: null, logOffset: 0, ci: null, optOpen: {}, watch: {} };
  var timer = null;

  function blankSpec() {
    return { version: 1, name: "", type: "", target: {}, details: {}, reports: {}, evidence: {}, run: { timeoutMinutes: 30 } };
  }
  function $(id) { return document.getElementById(id); }
  function el(tag, attrs, kids) {
    var n = document.createElement(tag);
    Object.keys(attrs || {}).forEach(function (k) {
      if (k === "text") n.textContent = attrs[k];
      else if (k === "class") n.className = attrs[k];
      else if (k.slice(0, 2) === "on") n.addEventListener(k.slice(2), attrs[k]);
      else if (attrs[k] === true) n.setAttribute(k, "");
      else if (attrs[k] !== false && attrs[k] != null) n.setAttribute(k, attrs[k]);
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
  function can(p) { return !!(S.opts && S.opts.permissions && S.opts.permissions[p]); }

  /* ── validation (server is authoritative; this just shows its answer live) ── */
  function revalidate() {
    clearTimeout(timer);
    if (!S.spec.type) return;
    S.pending = true;
    paintErrors();
    timer = setTimeout(function () {
      api("POST", "/api/wizard/validate", { spec: S.spec }).then(function (r) { S.result = r; S.pending = false; paintErrors(); })
        .catch(function () { S.pending = false; paintErrors(); });
    }, 350);
  }
  // cumulative: moving forward needs everything before it to be valid too
  var SECTIONS = [[], ["name", "target"], ["name", "target", "details"], ["name", "target", "details", "reports", "evidence", "run"],
                  ["name", "target", "details", "reports", "evidence", "run"], []];
  function stepErrors() {
    var secs = SECTIONS[S.step] || [];
    return S.result.errors.filter(function (e) { return secs.some(function (s) { return e.path === s || e.path.indexOf(s + ".") === 0 || e.path.indexOf(s + "[") === 0; }); });
  }
  function paintErrors() {
    var errs = stepErrors();
    var box = $("errorSummary");
    box.hidden = !errs.length || S.step === 0;
    box.textContent = "";
    if (!box.hidden) {
      box.appendChild(el("div", { text: "Fix these to continue:" }));
      var ul = el("ul");
      errs.slice(0, 8).forEach(function (e) { ul.appendChild(el("li", { text: e.message })); });
      box.appendChild(ul);
    }
    document.querySelectorAll("[data-path]").forEach(function (n) {
      var p = n.getAttribute("data-path");
      var hit = S.result.errors.filter(function (e) { return e.path === p; })[0];
      if (n.classList.contains("wz-err-slot")) n.textContent = hit ? hit.message : "";
      else n.classList.toggle("invalid", !!hit);
    });
    $("nextBtn").disabled = (S.step >= 1 && S.step <= 4 && (errs.length > 0 || !!S.pending)) || S.step === 5;
  }

  /* ── field renderers ── */
  function fieldWrap(f, path, input) {
    var req = f.required || f.requiredGroup || (f.showIf && (f.type === "secret_ref" || f.requiredWhenShown));
    return el("div", { class: "wz-field" }, [
      el("label", { class: "wz-label", text: f.label + (req ? " *" : "") }), input,
      f.help ? el("div", { class: "wz-help", text: f.help }) : null,
      el("div", { class: "wz-err wz-err-slot", "data-path": path })
    ]);
  }
  function setVal(obj, key, v) {
    obj[key] = v; revalidate();
    if (S.watch[key]) render();   // another field is shown/hidden by this one (e.g. login type -> token field)
  }
  function renderField(f, obj, section) {
    var path = section + "." + f.id, cur = obj[f.id], input;
    if (f.showIf && !Object.keys(f.showIf).every(function (k) { return obj[k] === f.showIf[k]; })) return null;
    if (f.default !== undefined && cur === undefined) { obj[f.id] = f.default; cur = f.default; }
    if (f.type === "steps") return renderSteps(f, obj);
    if (f.type === "requests") return renderRequests(f, obj);
    if (f.type === "checkbox") {
      input = el("input", { type: "checkbox", "data-path": path, onchange: function (e) { setVal(obj, f.id, e.target.checked); } });
      input.checked = !!cur;
    } else if (f.type === "select" || f.type === "site") {
      var options = f.options || (f.siteSuites ? (S.opts.sitesFor[f.siteSuites] || []) : S.opts.sites);
      input = el("select", { "data-path": path, onchange: function (e) { setVal(obj, f.id, e.target.value); } },
        [el("option", { value: "", text: "— choose —" })].concat(options.map(function (o) { return el("option", { value: o, text: o }); })));
      input.value = cur || "";
    } else if (f.type === "multiselect") {
      input = el("div", { class: "wz-checks", "data-path": path }, f.options.map(function (o) {
        var cb = el("input", { type: "checkbox", onchange: function () {
          var set = (obj[f.id] || []).filter(function (x) { return x !== o; });
          if (cb.checked) set.push(o);
          setVal(obj, f.id, f.options.filter(function (x) { return set.indexOf(x) >= 0; }));
        } });
        cb.checked = (cur || []).indexOf(o) >= 0;
        return el("label", {}, [cb, " " + o]);
      }));
    } else if (f.type === "integer" || f.type === "number") {
      input = el("input", { type: "number", step: f.type === "integer" ? "1" : "any", "data-path": path, oninput: function (e) {
        var t = e.target.value; setVal(obj, f.id, t === "" ? undefined : Number(t)); } });
      if (cur !== undefined) input.value = cur;
    } else if (f.type === "headers") {
      input = el("textarea", { rows: "3", placeholder: "X-Api-Version: 2", spellcheck: "false", "data-path": path, oninput: function (e) {
        setVal(obj, f.id, e.target.value); } });
      input.value = cur || "";
    } else {
      input = el("input", { type: "text", autocomplete: "off", spellcheck: "false", placeholder: f.example || "", "data-path": path, oninput: function (e) {
        setVal(obj, f.id, e.target.value); } });
      if (f.type === "secret_ref" && S.opts.secretNames.length) {
        input.setAttribute("list", "secretList");
      }
      input.value = cur || "";
    }
    var wrap = fieldWrap(f, path, input);
    if (f.type === "url" && can("run.start")) wrap.insertBefore(connectionCheck(input), wrap.querySelector(".wz-err"));
    return wrap;
  }
  function connectionCheck(input) {
    var msg = el("span", { class: "msg" }), btn = el("button", { class: "btn btn-ghost btn-small", type: "button", text: "Check connection" });
    btn.addEventListener("click", function () {
      var url = input.value.trim();
      if (!url) { msg.className = "msg in-warn"; msg.textContent = "Type the address first."; return; }
      btn.disabled = true; msg.className = "msg"; msg.textContent = "Checking…";
      api("POST", "/api/wizard/check-url", { url: url }).then(function (r) {
        msg.className = "msg in-" + r.level; msg.textContent = r.message;
      }).catch(function (e) { msg.className = "msg in-bad"; msg.textContent = e.message; })
        .then(function () { btn.disabled = false; });
    });
    return el("div", { class: "in-check" }, [btn, msg]);
  }
  function hasValue(f, obj) {
    var v = obj[f.id];
    return v !== undefined && v !== "" && v !== false && !(Array.isArray(v) && !v.length) && v !== f.default;
  }
  function renderForm(fields, obj, section) {
    fields.forEach(function (f) { if (f.showIf) Object.keys(f.showIf).forEach(function (k) { S.watch[k] = true; }); });
    var box = el("div"), needed = [], optional = [];
    fields.forEach(function (f) { (f.required || f.requiredGroup ? needed : optional).push(f); });
    function fill(into, list) { list.forEach(function (f) { var n = renderField(f, obj, section); if (n) into.appendChild(n); }); }
    if (needed.length && optional.length) {
      var req = el("div", { class: "in-req" }, [el("h3", { text: "Needed" })]);
      fill(req, needed); box.appendChild(req);
    } else fill(box, needed);
    if (optional.length) {
      var dep = optional.some(function (f) { return hasValue(f, obj) || S.result.errors.some(function (e) { return e.path === section + "." + f.id; }); });
      var det = el("details", { class: "in-opt" }, [el("summary", { text: "Optional — helps the test (you can skip all of this)" })]);
      if (S.optOpen[section] || dep) det.open = true;
      det.addEventListener("toggle", function () { S.optOpen[section] = det.open; });
      fill(det, optional); box.appendChild(det);
    }
    return box;
  }
  function cellInput(row, key, path, disabled, ph) {
    var i = el("input", { type: "text", placeholder: ph || "", "data-path": path, disabled: disabled, autocomplete: "off", spellcheck: "false",
      oninput: function (e) { row[key] = e.target.value; revalidate(); } });
    i.value = row[key] || "";
    return i;
  }
  function renderSteps(f, obj) {
    var rules = S.form.stepKeywords;
    obj.steps = obj.steps || [{ keyword: "NAVIGATE", locator: "", data: "", expected: "", description: "" }];
    var wrap = el("div", { class: "wz-field" }), body = el("tbody");
    function draw() {
      body.textContent = "";
      obj.steps.forEach(function (s, i) {
        var rule = rules[s.keyword] || { locator: "no", data: "no", expected: "no" }, p = "details.steps[" + i + "]";
        var sel = el("select", { "data-path": p + ".keyword", onchange: function (e) { s.keyword = e.target.value; draw(); revalidate(); } },
          [el("option", { value: "", text: "— action —" })].concat(Object.keys(rules).map(function (k) { return el("option", { value: k, text: k }); })));
        sel.value = s.keyword || "";
        body.appendChild(el("tr", {}, [
          el("td", { class: "num", text: String(i + 1) }), el("td", {}, [sel]),
          el("td", {}, [cellInput(s, "locator", p + ".locator", rule.locator === "no", "id:user-name")]),
          el("td", {}, [cellInput(s, "data", p + ".data", rule.data === "no", "value or ${secret:NAME}")]),
          el("td", {}, [cellInput(s, "expected", p + ".expected", rule.expected === "no", "expected")]),
          el("td", {}, [cellInput(s, "description", p + ".description", false, "note (optional)")]),
          el("td", {}, [el("button", { class: "btn btn-ghost", type: "button", title: "Remove step", text: "✕", onclick: function () { obj.steps.splice(i, 1); draw(); revalidate(); } })])
        ]));
      });
      paintErrors();
    }
    wrap.appendChild(el("label", { class: "wz-label", text: f.label + " *" }));
    wrap.appendChild(el("table", { class: "wz-grid" }, [el("thead", {}, [el("tr", {}, ["#", "Action", "Locator", "Data", "Expected", "Note", ""].map(function (h) { return el("th", { text: h }); }))]), body]));
    wrap.appendChild(el("button", { class: "btn", type: "button", text: "+ Add step", onclick: function () {
      obj.steps.push({ keyword: "", locator: "", data: "", expected: "", description: "" }); draw(); revalidate(); } }));
    if (f.help) wrap.appendChild(el("div", { class: "wz-help", text: f.help }));
    draw();
    return wrap;
  }
  function renderRequests(f, obj) {
    obj.requests = obj.requests || [{ method: "GET", path: "/", expectedStatus: 200, name: "", body: "", bodyContains: "" }];
    var wrap = el("div", { class: "wz-field" }), body = el("tbody");
    function draw() {
      body.textContent = "";
      obj.requests.forEach(function (r, i) {
        var p = "details.requests[" + i + "]";
        var m = el("select", { "data-path": p + ".method", onchange: function (e) { r.method = e.target.value; revalidate(); } },
          S.form.methods.map(function (x) { return el("option", { value: x, text: x }); }));
        m.value = r.method || "GET";
        var st = el("input", { type: "number", "data-path": p + ".expectedStatus", oninput: function (e) {
          r.expectedStatus = e.target.value === "" ? undefined : Number(e.target.value); revalidate(); } });
        if (r.expectedStatus !== undefined) st.value = r.expectedStatus;
        var bd = el("textarea", { rows: "2", placeholder: '{"title": "x"}', "data-path": p + ".body", oninput: function (e) { r.body = e.target.value; revalidate(); } });
        bd.value = r.body || "";
        body.appendChild(el("tr", {}, [
          el("td", { class: "num", text: String(i + 1) }), el("td", {}, [m]), el("td", {}, [cellInput(r, "path", p + ".path", false, "/posts/1")]),
          el("td", {}, [st]), el("td", {}, [cellInput(r, "name", p + ".name", false, "name (optional)")]),
          el("td", {}, [bd]), el("td", {}, [cellInput(r, "bodyContains", p + ".bodyContains", false, "must contain")]),
          el("td", {}, [el("button", { class: "btn btn-ghost", type: "button", text: "✕", onclick: function () { obj.requests.splice(i, 1); draw(); revalidate(); } })])
        ]));
      });
      paintErrors();
    }
    wrap.appendChild(el("label", { class: "wz-label", text: f.label + " *" }));
    wrap.appendChild(el("table", { class: "wz-grid" }, [el("thead", {}, [el("tr", {}, ["#", "Method", "Path", "Status", "Name", "JSON body", "Response contains", ""].map(function (h) { return el("th", { text: h }); }))]), body]));
    wrap.appendChild(el("button", { class: "btn", type: "button", text: "+ Add request", onclick: function () {
      obj.requests.push({ method: "GET", path: "/", expectedStatus: 200, name: "", body: "", bodyContains: "" }); draw(); revalidate(); } }));
    if (f.help) wrap.appendChild(el("div", { class: "wz-help", text: f.help }));
    draw();
    return wrap;
  }

  /* ── steps ── */
  function stepType(body) {
    var cards = el("div", { class: "wz-cards" });
    S.opts.types.forEach(function (t) {
      cards.appendChild(el("button", { type: "button", class: "wz-card" + (S.spec.type === t.type ? " selected" : ""), onclick: function () { chooseType(t.type); } },
        [el("strong", { text: t.title }), el("span", { text: t.description }),
         el("span", { class: "in-tag", text: t.needs && t.needs.length ? "You'll need: " + t.needs[0] : "" })]));
    });
    body.appendChild(cards);
    var tpl = el("div", { class: "wz-templates" }, [el("h3", { text: "Saved templates" })]);
    if (!S.templates.length) tpl.appendChild(el("div", { class: "wz-help", text: "None yet — save one on the Review step." }));
    S.templates.forEach(function (t) {
      var row = el("div", { class: "wz-tpl" }, [el("span", { class: "grow", text: t.name + "  (" + t.type + ", v" + t.version + ", by " + t.savedBy + ")" }),
        el("button", { class: "btn", type: "button", text: "Load", onclick: function () { loadTemplate(t.slug); } })]);
      if (can("template.delete")) row.appendChild(el("button", { class: "btn btn-ghost", type: "button", text: "Delete", onclick: function () {
        if (confirm("Delete template '" + t.name + "' and all its versions?")) api("POST", "/api/templates/" + t.slug + "/delete").then(refreshTemplates).catch(showError); } }));
      tpl.appendChild(row);
    });
    body.appendChild(tpl);
  }
  function chooseType(type) {
    if (S.spec.type === type) { go(1); return; }
    S.spec = blankSpec(); S.spec.type = type; S.result = { errors: [], warnings: [] };
    api("GET", "/api/wizard/forms/" + type).then(function (f) { S.form = f; S.spec.reports = {}; go(1); }).catch(showError);
  }
  function loadTemplate(slug) {
    api("GET", "/api/templates/" + slug).then(function (spec) {
      S.spec = spec; delete S.spec.meta;
      return api("GET", "/api/wizard/forms/" + spec.type);
    }).then(function (f) { S.form = f; go(4); revalidate(); }).catch(showError);
  }
  function needsPanel() {
    var t = S.opts.types.filter(function (x) { return x.type === S.spec.type; })[0];
    if (!t || !t.needs || !t.needs.length) return null;
    var ul = el("ul"); t.needs.forEach(function (n) { ul.appendChild(el("li", { text: n })); });
    return el("div", { class: "in-needs" }, [el("strong", { text: "Before you start, have ready:" }), ul]);
  }
  function stepTarget(body) {
    S.watch = {};
    var np = needsPanel(); if (np) body.appendChild(np);
    body.appendChild(fieldWrap({ label: "Test name", required: true, help: "Used for the template and the run list (3-80 characters)." }, "name",
      (function () { var i = el("input", { type: "text", "data-path": "name", autocomplete: "off", oninput: function (e) { S.spec.name = e.target.value; revalidate(); } }); i.value = S.spec.name; return i; })()));
    body.appendChild(renderForm(S.form.target, S.spec.target, "target"));
  }
  function stepDetails(body) {
    S.watch = {};
    if (S.form.notes) body.appendChild(el("div", { class: "wz-warn", text: S.form.notes }));
    body.appendChild(renderForm(S.form.details, S.spec.details, "details"));
  }
  function stepReports(body) {
    var t = S.opts.types.filter(function (x) { return x.type === S.spec.type; })[0];
    var rbox = el("div", { class: "wz-field wz-checks" }, [el("label", { class: "wz-label", text: "Reports" })]);
    t.reports.forEach(function (k) {
      if (S.spec.reports[k] === undefined) S.spec.reports[k] = !!S.opts.reportDefaults[k];
      var cb = el("input", { type: "checkbox", onchange: function (e) { S.spec.reports[k] = e.target.checked; revalidate(); } });
      cb.checked = S.spec.reports[k];
      rbox.appendChild(el("div", {}, [el("label", {}, [cb, " " + S.opts.reportLabels[k]])]));
    });
    body.appendChild(rbox);
    if (t.evidence) {
      var ev = S.spec.evidence; if (!ev.screenshots) { ev.screenshots = "on-failure"; ev.video = false; ev.aiNote = false; }
      var sel = el("select", { onchange: function (e) { ev.screenshots = e.target.value; revalidate(); } },
        S.opts.screenshotChoices.map(function (c) { return el("option", { value: c, text: c }); }));
      sel.value = ev.screenshots;
      body.appendChild(el("div", { class: "wz-field" }, [el("label", { class: "wz-label", text: "Screenshots" }), sel]));
      [["video", "Record video"], ["aiNote", "AI note on failures (needs an AI provider configured)"]].forEach(function (p) {
        var cb = el("input", { type: "checkbox", onchange: function (e) { ev[p[0]] = e.target.checked; revalidate(); } });
        cb.checked = !!ev[p[0]];
        body.appendChild(el("div", { class: "wz-field wz-checks" }, [el("label", {}, [cb, " " + p[1]])]));
      });
    } else {
      body.appendChild(el("div", { class: "wz-help", text: "Screenshots, video and AI notes only apply to browser-driven tests." }));
    }
    body.appendChild(renderForm([{ id: "timeoutMinutes", label: "Stop the run after (minutes)", type: "integer", min: 1, max: 240, default: 30 }], S.spec.run, "run"));
  }
  function row(k, v) { return el("tr", {}, [el("th", { text: k }), el("td", { text: v })]); }
  function stepReview(body) {
    var sp = S.spec, tbl = el("table", { class: "wz-review" });
    tbl.appendChild(row("Name", sp.name)); tbl.appendChild(row("Type", sp.type));
    Object.keys(sp.target).forEach(function (k) { tbl.appendChild(row(k, String(sp.target[k]))); });
    Object.keys(sp.details).forEach(function (k) {
      var v = sp.details[k];
      tbl.appendChild(row(k, Array.isArray(v) ? (v.length + " item(s)" + (v.length && typeof v[0] === "object" ? "" : ": " + v.join(", "))) : String(v)));
    });
    tbl.appendChild(row("Reports", Object.keys(sp.reports).filter(function (k) { return sp.reports[k]; }).join(", ") || "none"));
    if (sp.evidence && sp.evidence.screenshots) tbl.appendChild(row("Evidence", "screenshots: " + sp.evidence.screenshots + ", video: " + !!sp.evidence.video + ", AI note: " + !!sp.evidence.aiNote));
    tbl.appendChild(row("Timeout", sp.run.timeoutMinutes + " min"));
    body.appendChild(tbl);
    S.result.warnings.forEach(function (w) { body.appendChild(el("div", { class: "wz-warn", text: "⚠ " + w.message })); });
    var actions = el("div", { class: "wz-actions" });
    if (can("spec.save")) actions.appendChild(el("button", { class: "btn", type: "button", text: "Save as template", onclick: function () {
      api("POST", "/api/templates", { spec: S.spec }).then(function (r) { flash("Saved '" + sp.name + "' as version " + r.version + "."); refreshTemplates(); }).catch(showError); } }));
    body.appendChild(actions);
  }
  function stepRun(body) {
    var actions = el("div", { class: "wz-actions" });
    if (!S.opts.runnerEnabled) {
      body.appendChild(el("div", { class: "wz-banner", text: "Running tests from the dashboard is switched off on this server (set DASHBOARD_RUNNER_ENABLED=1 and restart). You can still save this as a template." }));
    } else if (can("run.start")) {
      actions.appendChild(el("button", { class: "btn btn-primary", type: "button", text: "Run now", onclick: function () {
        api("POST", "/api/runs", { spec: S.spec }).then(function (st) { watch(st.id); }).catch(showError); } }));
    }
    if (can("ci.trigger") && S.opts.ciProviders.length) {
      var prov = el("select", {}, S.opts.ciProviders.map(function (p) { return el("option", { value: p, text: p }); }));
      var suite = el("select", {}, ["smoke", "regression"].map(function (p) { return el("option", { value: p, text: p }); }));
      actions.appendChild(el("span", { class: "wz-help", text: "Or trigger the repo's own CI pipeline:" }));
      actions.appendChild(prov); actions.appendChild(suite);
      actions.appendChild(el("button", { class: "btn", type: "button", text: "Trigger CI", onclick: function () {
        var p = { suite_type: suite.value, browser: S.spec.target.browser || "chrome", headless: S.spec.target.headless !== false };
        api("POST", "/api/ci/trigger", { provider: prov.value, params: p }).then(function (r) { S.ci = r.token; pollCi(); }).catch(showError); } }));
      body.appendChild(el("div", { class: "wz-help", text: "CI runs the repository's checked-in suites with these settings; a scenario built here exists only on this machine and is not sent to CI." }));
    }
    body.appendChild(actions);
    body.appendChild(el("div", { id: "runPanel" }));
    body.appendChild(el("h3", { text: "Recent runs" }));
    body.appendChild(el("div", { id: "runList" }));
    refreshRuns();
    if (S.runId) watch(S.runId);
  }
  function pollCi() {
    if (!S.ci) return;
    api("GET", "/api/ci/" + S.ci).then(function (r) {
      var p = $("runPanel"); if (!p) return;
      p.textContent = "";
      p.appendChild(el("div", { class: "state-" + r.state, text: "CI: " + r.state }));
      if (r.url) p.appendChild(el("a", { href: r.url, target: "_blank", rel: "noopener noreferrer", text: "Open the CI run" }));
      if (r.state === "queued" || r.state === "running") setTimeout(pollCi, 4000);
    }).catch(showError);
  }
  function refreshRuns() {
    api("GET", "/api/runs").then(function (r) {
      var box = $("runList"); if (!box) return;
      box.textContent = "";
      if (!r.runs.length) box.appendChild(el("div", { class: "wz-help", text: "No runs yet." }));
      r.runs.slice(0, 10).forEach(function (st) {
        box.appendChild(el("div", { class: "wz-tpl" }, [
          el("span", { class: "state-" + st.state, text: st.state }), el("span", { class: "grow", text: st.name + " · " + st.type + " · " + st.startedBy + " · " + st.createdAt }),
          el("button", { class: "btn btn-ghost", type: "button", text: "Open", onclick: function () { watch(st.id); } }),
          can("run.start") && S.opts.runnerEnabled ? el("button", { class: "btn btn-ghost", type: "button", text: "Re-run", onclick: function () {
            api("POST", "/api/runs/" + st.id + "/rerun").then(function (n) { watch(n.id); }).catch(showError); } }) : null]));
      });
    }).catch(function () {});
  }
  function watch(id) {
    S.runId = id; S.logOffset = 0; clearInterval(S.poller);
    var p = $("runPanel"); if (!p) return;
    p.textContent = "";
    var head = el("div", { id: "runHead" }), links = el("div", { id: "runLinks" }), log = el("pre", { class: "wz-log", id: "runLog" });
    var cancel = el("button", { class: "btn btn-ghost", type: "button", text: "Cancel run", onclick: function () {
      api("POST", "/api/runs/" + id + "/cancel").catch(showError); } });
    p.appendChild(head); p.appendChild(cancel); p.appendChild(links); p.appendChild(log);
    function tick() {
      api("GET", "/api/runs/" + id).then(function (st) {
        head.textContent = ""; head.appendChild(el("strong", { class: "state-" + st.state, text: st.name + " — " + st.state + (st.queuePosition ? " (position " + st.queuePosition + ")" : "") }));
        if (st.message) head.appendChild(el("div", { class: "wz-help", text: st.message }));
        var done = ["passed", "failed", "error", "cancelled", "timeout"].indexOf(st.state) >= 0;
        cancel.hidden = done;
        if (done) {
          links.textContent = "";
          clearInterval(S.poller); refreshRuns();
          window.RunResults.load(links, id);
        }
        return api("GET", "/api/runs/" + id + "/log?offset=" + S.logOffset);
      }).then(function (l) {
        if (l && l.text) { log.textContent += l.text; log.scrollTop = log.scrollHeight; }
        if (l) S.logOffset = l.offset;
      }).catch(function () {});
    }
    tick(); S.poller = setInterval(tick, 2000);
  }

  /* ── plumbing ── */
  function showError(e) { var b = $("banner"); b.hidden = false; b.textContent = e.message || String(e); setTimeout(function () { b.hidden = true; }, 8000); }
  function flash(msg) { var b = $("banner"); b.hidden = false; b.textContent = msg; setTimeout(function () { b.hidden = true; }, 4000); }
  function refreshTemplates() { return api("GET", "/api/templates").then(function (r) { S.templates = r.templates; if (S.step === 0) render(); }).catch(function () {}); }
  function go(n) { S.step = Math.max(0, Math.min(5, n)); render(); revalidate(); }
  function render() {
    clearInterval(S.poller);
    var st = $("stepper"); st.textContent = "";
    STEPS.forEach(function (s, i) { st.appendChild(el("li", { class: i === S.step ? "current" : (i < S.step ? "done" : ""), text: (i + 1) + ". " + s[0] })); });
    $("stepTitle").textContent = STEPS[S.step][0]; $("stepSub").textContent = STEPS[S.step][1];
    var body = $("stepBody"); body.textContent = "";
    if (S.opts.secretNames.length) {
      var dl = el("datalist", { id: "secretList" }, S.opts.secretNames.map(function (n) { return el("option", { value: "${secret:" + n + "}" }); }));
      body.appendChild(dl);
    }
    [stepType, stepTarget, stepDetails, stepReports, stepReview, stepRun][S.step](body);
    $("backBtn").hidden = S.step === 0; $("nextBtn").hidden = S.step === 0 || S.step === 5;
    $("nextBtn").textContent = S.step === 4 ? "Continue to run" : "Next";
    paintErrors();
  }
  $("backBtn").addEventListener("click", function () { go(S.step - 1); });
  $("nextBtn").addEventListener("click", function () { go(S.step + 1); });

  api("GET", "/api/wizard/options").then(function (o) {
    S.opts = o;
    $("whoami").textContent = o.user + " (" + o.role + ")";
    return refreshTemplates();
  }).then(function () {
    var m = /[?&]type=([a-z]+)/.exec(location.search);
    if (m && S.opts.types.some(function (t) { return t.type === m[1]; })) chooseType(m[1]); else render();
  }).catch(showError);
})();
