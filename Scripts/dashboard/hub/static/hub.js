/* Test Hub. No inline script/style (CSP); all data goes through textContent. */
(function () {
  "use strict";
  var S = { data: null, active: null, timer: null };
  function $(id) { return document.getElementById(id); }
  function el(tag, cls, text) { var n = document.createElement(tag); if (cls) n.className = cls; if (text != null) n.textContent = text; return n; }
  function api(method, url, body) {
    var o = { method: method, headers: {} };
    if (method !== "GET") { o.headers["Content-Type"] = "application/json"; o.body = JSON.stringify(body || {}); }
    return fetch(url, o).then(function (r) { return r.json().then(function (j) { if (!r.ok) throw new Error(j.error || ("HTTP " + r.status)); return j; }); });
  }
  function banner(msg) { var b = $("banner"); b.hidden = !msg; b.textContent = msg || ""; }

  function list(cls, items) { var ul = el("ul", cls); items.forEach(function (t) { ul.appendChild(el("li", null, t)); }); return ul; }

  function renderCards() {
    var box = $("cards"); box.textContent = "";
    S.data.intents.forEach(function (i) {
      var c = el("div", "hub-card");
      var h = el("h3"); h.appendChild(el("span", "ico", i.icon)); h.appendChild(document.createTextNode(i.title));
      c.appendChild(h); c.appendChild(el("p", null, i.blurb));
      if (i.needs.length) { c.appendChild(el("div", "lbl", "You'll need")); c.appendChild(list("", i.needs)); }
      if (i.optional.length) { c.appendChild(el("div", "lbl", "Optional")); c.appendChild(list("opt", i.optional)); }
      var go = el("a", "btn btn-primary go", "Start"); go.href = i.open; c.appendChild(go);
      box.appendChild(c);
    });
  }

  function renderReady() {
    var ul = $("ready"); ul.textContent = "";
    S.data.readiness.forEach(function (r) {
      var li = el("li", r.status); li.appendChild(el("span", "dot"));
      var d = el("div"); d.appendChild(el("strong", null, r.label)); d.appendChild(el("div", "detail", r.detail));
      if (r.fix) d.appendChild(el("div", "fix", r.fix));
      li.appendChild(d); ul.appendChild(li);
    });
  }

  function verdictOf(r) { return r.verdict || (r.state === "queued" || r.state === "running" ? r.state : r.state) || "unknown"; }
  function renderRuns() {
    var box = $("runs"); box.textContent = "";
    if (!S.data.runs.length) { box.appendChild(el("p", "hub-empty", "Nothing has run yet. Pick a test on the left to get started.")); return; }
    S.data.runs.forEach(function (r) {
      var b = el("button", "hub-run"); b.type = "button"; b.setAttribute("aria-current", String(r.id === S.active));
      var row = el("div", "row"); row.appendChild(el("strong", null, r.name || r.id)); row.appendChild(window.RunResults.badge(verdictOf(r)));
      b.appendChild(row);
      b.appendChild(el("div", "sub", r.headline || ((r.createdAt || "").replace("T", " ").replace("Z", ""))));
      b.addEventListener("click", function () { open(r.id); });
      box.appendChild(b);
    });
  }

  function open(id) {
    S.active = id; renderRuns();
    $("detailPanel").hidden = false;
    var acts = el("div", "hub-actions");
    if (S.data.runnerEnabled && S.data.permissions["run.start"]) {
      var again = el("button", "btn btn-small", "Run again"); again.type = "button";
      again.addEventListener("click", function () {
        again.disabled = true;
        api("POST", "/api/runs/" + id + "/rerun").then(function (st) { banner(""); refresh().then(function () { open(st.id); }); })
          .catch(function (e) { banner(e.message); again.disabled = false; });
      });
      acts.appendChild(again);
    }
    window.RunResults.load($("detail"), id, { actions: acts });
  }

  function live() { return S.data.runs.some(function (r) { return r.state === "queued" || r.state === "running"; }); }
  function refresh() {
    return api("GET", "/api/hub").then(function (d) {
      S.data = d; $("whoami").textContent = d.user + " (" + d.role + ")";
      if (!d.runnerEnabled) banner("Running tests from the dashboard is switched off. Start it with Scripts/start-dashboard.sh to enable the Start buttons.");
      renderCards(); renderReady(); renderRuns();
      if (S.active && live()) open(S.active);
      clearTimeout(S.timer); S.timer = setTimeout(refresh, live() ? 3000 : 15000);
    }).catch(function (e) { banner("Could not load the hub: " + e.message); });
  }
  refresh().then(function () {
    var m = /[#&]run=([0-9a-f-]+)/.exec(location.hash);
    if (m) open(m[1]); else if (S.data && S.data.runs.length) open(S.data.runs[0].id);
  });
})();
