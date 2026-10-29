/* Shared "what happened" card used by the Hub, the Test Wizard and the Control Center.
   RunResults.load(container, runId, opts) fetches /api/runs/<id>/results and draws it.
   Everything goes through textContent: no innerHTML, no inline script/style (the CSP forbids them). */
(function () {
  "use strict";
  var LABEL = { passed: "Passed", failed: "Failed", "no-tests": "No results", cancelled: "Cancelled", timeout: "Timed out",
                error: "Error", running: "Running", queued: "Waiting" };

  function el(tag, cls, text) {
    var n = document.createElement(tag);
    if (cls) n.className = cls;
    if (text != null) n.textContent = text;
    return n;
  }
  function badge(verdict) { return el("span", "rr-badge rr-" + verdict, LABEL[verdict] || verdict); }

  function render(box, r, opts) {
    opts = opts || {};
    box.textContent = "";
    var card = el("div", "rr-card rr-card-" + r.verdict);
    var head = el("div", "rr-head");
    head.appendChild(badge(r.verdict));
    head.appendChild(el("strong", "rr-name", r.name || r.runId));
    card.appendChild(head);
    card.appendChild(el("p", "rr-headline", r.headline || ""));

    if (r.counts) {
      var c = r.counts, row = el("div", "rr-counts");
      [["passed", c.passed], ["failed", c.failed], ["skipped", c.skipped], ["total", c.total]].forEach(function (p) {
        var cell = el("div", "rr-count rr-count-" + p[0]);
        cell.appendChild(el("span", "rr-num", String(p[1])));
        cell.appendChild(el("span", "rr-lbl", p[0]));
        row.appendChild(cell);
      });
      card.appendChild(row);
      if (c.total) {
        var bar = el("div", "rr-bar");
        [["passed", c.passed], ["skipped", c.skipped], ["failed", c.failed]].forEach(function (p) {
          if (!p[1]) return;
          var seg = el("span", "rr-seg rr-seg-" + p[0]);
          seg.style.width = (100 * p[1] / c.total) + "%";   // CSSOM property write: allowed under CSP (unlike a style attribute)
          bar.appendChild(seg);
        });
        card.appendChild(bar);
      }
    }

    if (r.hints && r.hints.length) {
      card.appendChild(el("h4", "rr-h", "What to try next"));
      var ul = el("ul", "rr-hints");
      r.hints.forEach(function (h) { ul.appendChild(el("li", null, h)); });
      card.appendChild(ul);
    }

    if (r.failures && r.failures.length) {
      card.appendChild(el("h4", "rr-h", "Checks that failed (" + r.failures.length + ")"));
      var wrap = el("div", "rr-scroll"), t = el("table", "rr-table"), hr = el("tr");
      ["Where", "Check", "What went wrong"].forEach(function (h) { hr.appendChild(el("th", null, h)); });
      t.appendChild(hr);
      r.failures.forEach(function (f) {
        var tr = el("tr");
        [f["class"], f.test, f.message].forEach(function (v) { tr.appendChild(el("td", null, v || "")); });
        t.appendChild(tr);
      });
      wrap.appendChild(t);
      card.appendChild(wrap);
    }

    var links = el("div", "rr-links");
    var base = "/api/runs/" + r.runId + "/files/";
    function link(href, text, newTab) {
      var a = el("a", "btn btn-ghost btn-small", text);
      a.href = href;
      if (newTab) { a.target = "_blank"; a.rel = "noopener noreferrer"; }
      links.appendChild(a);
    }
    link(base + "summary.html", "Printable summary", true);
    (r.reports || []).forEach(function (rep) { link(rep.link, rep.label + " (zip)", false); });
    link(base + "run.log", "Full log", true);
    card.appendChild(links);
    if (opts.actions) card.appendChild(opts.actions);
    if (r.durationSeconds != null) card.appendChild(el("p", "rr-meta", "Took " + Math.round(r.durationSeconds) + " s" + (r.exitCode != null ? " · exit code " + r.exitCode : "")));
    box.appendChild(card);
  }

  function load(box, runId, opts) {
    return fetch("/api/runs/" + encodeURIComponent(runId) + "/results").then(function (resp) {
      return resp.json().then(function (j) { if (!resp.ok) throw new Error(j.error || ("HTTP " + resp.status)); return j; });
    }).then(function (r) { render(box, r, opts); return r; })
      .catch(function (e) { box.textContent = "Could not load the results: " + e.message; });
  }

  window.RunResults = { render: render, load: load, badge: badge, LABEL: LABEL };
})();
