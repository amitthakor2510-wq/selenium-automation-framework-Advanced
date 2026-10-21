/*
 * In-page recorder for the keyword-driven framework (see RecorderSession).
 *
 * Injected into the TOP window only; it attaches capture-phase listeners to the top document and to every
 * same-origin iframe below it. Captured user actions are appended to a queue that is mirrored into
 * sessionStorage, so events survive the page navigations they themselves cause. The Java side drains the
 * queue by calling window.__kdtRec.drain(), which returns JSON: {"stop":bool,"events":[...]}.
 *
 * Nothing here ever throws into the page: every handler is wrapped in guard().
 */
(function () {
  'use strict';
  var VERSION = 4;
  if (window.__kdtRec && window.__kdtRec.version === VERSION) { return; }
  if (window !== window.top) { return; }

  function ssGet(k) { try { return window.sessionStorage.getItem(k); } catch (e) { return null; } }
  function ssSet(k, v) { try { window.sessionStorage.setItem(k, v); } catch (e) { /* storage unavailable */ } }

  var queue = [];
  try { queue = JSON.parse(ssGet('__kdt_q') || '[]'); } catch (e) { queue = []; }
  var state = { assertMode: ssGet('__kdt_assert') || '', hover: ssGet('__kdt_hover') === '1', stop: ssGet('__kdt_stop') === '1' };
  var pending = null;          // the text field currently being typed into
  var initialValue = new WeakMap();
  var suppress = { el: null, until: 0 };
  var hoverTimer = null;

  // ---------------------------------------------------------------- helpers
  function guard(fn) {
    return function () { try { return fn.apply(this, arguments); } catch (e) { if (window.console) { console.debug('kdt-recorder', e); } } };
  }
  function norm(s) { return (s || '').replace(/\s+/g, ' ').trim(); }
  function attr(el, n) { var v = el.getAttribute(n); return v === null ? '' : v; }
  function tagOf(el) { return el.tagName.toLowerCase(); }
  function cssEsc(s) { return (window.CSS && CSS.escape) ? CSS.escape(s) : String(s).replace(/([^\w-])/g, '\\$1'); }
  function attrQ(v) { return "'" + String(v).replace(/\\/g, '\\\\').replace(/'/g, "\\'") + "'"; }
  function xpLit(s) {
    if (s.indexOf("'") < 0) { return "'" + s + "'"; }
    if (s.indexOf('"') < 0) { return '"' + s + '"'; }
    return "concat('" + s.split("'").join("',\"'\",'") + "')";
  }
  function looksDynamic(s) {
    if (!s) { return true; }
    return /\d{4,}/.test(s) || /[0-9a-f]{8}-[0-9a-f]{4}/i.test(s) || /^:r[0-9a-z]+:$/.test(s)
      || /^(ember|ext-gen|gwt-uid|rc-|mui-|__|react-select|radix-)/i.test(s) || /[_-][0-9a-f]{6,}$/i.test(s);
  }
  function stableClasses(el) {
    var out = [];
    var raw = attr(el, 'class').split(/\s+/);
    for (var i = 0; i < raw.length; i++) {
      var c = raw[i];
      if (!c || c.length > 40 || looksDynamic(c)) { continue; }
      if (/^(css|sc|jsx|styled|emotion|_|js)[-_]?[a-z0-9]{4,}$/i.test(c)) { continue; }
      if (/^(active|selected|open|opened|hover|focus|focused|disabled|checked|show|hide|hidden|visible|loading|ng-|is-|has-)/i.test(c)) { continue; }
      out.push(c);
      if (out.length >= 3) { break; }
    }
    return out;
  }
  function topUrl() { return window.location.href; }

  // ------------------------------------------------------------- uniqueness
  function isUnique(doc, el, type, value) {
    try {
      if (type === 'id') { var byId = doc.querySelectorAll('[id=' + attrQ(value) + ']'); return byId.length === 1 && byId[0] === el; }
      if (type === 'name') { var byName = doc.getElementsByName(value); return byName.length === 1 && byName[0] === el; }
      if (type === 'css') { var l = doc.querySelectorAll(value); return l.length === 1 && l[0] === el; }
      if (type === 'xpath') {
        var r = doc.evaluate(value, doc, null, XPathResult.ORDERED_NODE_SNAPSHOT_TYPE, null);
        return r.snapshotLength === 1 && r.snapshotItem(0) === el;
      }
      if (type === 'linktext') {
        var as = doc.getElementsByTagName('a'), n = 0, hit = null;
        for (var i = 0; i < as.length; i++) { if (norm(as[i].textContent) === value) { n++; hit = as[i]; } }
        return n === 1 && hit === el;
      }
    } catch (e) { return false; }
    return false;
  }

  function positionalCss(el) {
    var doc = el.ownerDocument, parts = [], cur = el;
    while (cur && cur.nodeType === 1 && cur !== doc.documentElement) {
      var id = attr(cur, 'id');
      if (id && !looksDynamic(id) && cur !== el) { parts.unshift('#' + cssEsc(id)); return parts.join(' > '); }
      var tag = tagOf(cur), idx = 1, sib = cur;
      while ((sib = sib.previousElementSibling)) { if (tagOf(sib) === tag) { idx++; } }
      parts.unshift(tag + ':nth-of-type(' + idx + ')');
      cur = cur.parentElement;
    }
    return 'html > ' + parts.join(' > ');
  }

  // ------------------------------------------------------ locator candidates
  function candidates(el) {
    var doc = el.ownerDocument, out = [], tag = tagOf(el);
    function add(type, value, stable) {
      if (!value) { return; }
      for (var i = 0; i < out.length; i++) { if (out[i].type === type && out[i].value === value) { return; } }
      if (isUnique(doc, el, type, value)) { out.push({ type: type, value: value, stable: stable !== false }); }
    }
    var id = attr(el, 'id');
    if (id && !looksDynamic(id)) { add('id', id, true); }
    var testAttrs = ['data-testid', 'data-test', 'data-qa', 'data-cy', 'data-test-id', 'data-automation-id'];
    for (var t = 0; t < testAttrs.length; t++) {
      var tv = attr(el, testAttrs[t]);
      if (tv && !looksDynamic(tv)) { add('css', '[' + testAttrs[t] + '=' + attrQ(tv) + ']', true); }
    }
    var name = attr(el, 'name');
    if (name && !looksDynamic(name) && /^(input|select|textarea|button|form|iframe|frame|a|img)$/.test(tag)) { add('name', name, true); }
    var aria = attr(el, 'aria-label');
    if (aria) { add('css', tag + '[aria-label=' + attrQ(aria) + ']', true); }
    var ph = attr(el, 'placeholder');
    if (ph) { add('css', tag + '[placeholder=' + attrQ(ph) + ']', true); }
    var title = attr(el, 'title');
    if (title) { add('css', tag + '[title=' + attrQ(title) + ']', true); }
    if (tag === 'img' && attr(el, 'alt')) { add('css', 'img[alt=' + attrQ(attr(el, 'alt')) + ']', true); }
    if (tag === 'input' && /^(submit|button|reset)$/.test(attr(el, 'type')) && attr(el, 'value')) {
      add('css', 'input[type=' + attrQ(attr(el, 'type')) + '][value=' + attrQ(attr(el, 'value')) + ']', true);
    }
    var text = norm(el.textContent);
    if (tag === 'a' && text && text.length <= 60) { add('linktext', text, true); }
    // only for elements whose text is a stable label; the text of span/div/td/p is usually data that changes
    if (text && text.length <= 60 && /^(a|button|label|option|summary|h1|h2|h3|h4|h5|h6|li)$/.test(tag)) {
      add('xpath', '//' + tag + '[normalize-space()=' + xpLit(text) + ']', true);
    }
    var cls = stableClasses(el);
    if (cls.length) {
      add('css', tag + '.' + cls.map(cssEsc).join('.'), true);
      add('css', tag + '.' + cssEsc(cls[0]), true);
    }
    var type = attr(el, 'type');
    if (tag === 'input' && type) { add('css', 'input[type=' + attrQ(type) + ']', true); }
    var stableCount = 0;
    for (var s = 0; s < out.length; s++) { if (out[s].stable) { stableCount++; } }
    if (stableCount < 2) {
      add('css', positionalCss(el), false);
    }
    return out.slice(0, 4);
  }

  function labelText(el) {
    try {
      if (el.labels && el.labels.length) { return norm(el.labels[0].textContent); }
      var by = attr(el, 'aria-labelledby');
      if (by) { var ref = el.ownerDocument.getElementById(by.split(/\s+/)[0]); if (ref) { return norm(ref.textContent); } }
    } catch (e) { /* ignore */ }
    return '';
  }

  var TRACKED = ['type', 'placeholder', 'aria-label', 'role', 'href', 'title', 'data-testid'];
  function meta(el) {
    var attrs = {};
    for (var i = 0; i < TRACKED.length; i++) { var v = attr(el, TRACKED[i]); if (v) { attrs[TRACKED[i]] = v; } }
    var tag = tagOf(el);
    var inputType = tag === 'input' ? (el.type || 'text') : '';
    var text = norm(el.innerText !== undefined ? el.innerText : el.textContent);
    return {
      tag: tag, inputType: inputType, id: attr(el, 'id'), name: attr(el, 'name'),
      classes: attr(el, 'class').split(/\s+/).filter(Boolean),
      text: text.slice(0, 100), parentTag: el.parentElement ? tagOf(el.parentElement) : '', attrs: attrs,
      hints: {
        label: labelText(el), placeholder: attr(el, 'placeholder'), ariaLabel: attr(el, 'aria-label'),
        testId: attr(el, 'data-testid') || attr(el, 'data-test') || attr(el, 'data-qa'), title: attr(el, 'title'),
        value: (tag === 'input' && /^(submit|button|reset)$/.test(inputType)) ? attr(el, 'value') : ''
      },
      secret: tag === 'input' && inputType === 'password',
      cands: candidates(el)
    };
  }

  // ----------------------------------------------------------------- queue
  function persist() { ssSet('__kdt_q', JSON.stringify(queue)); }
  function emit(type, el, extra, chain) {
    var ev = { type: type, ts: Date.now(), url: topUrl(), frame: chain || [] };
    if (el) { ev.target = meta(el); }
    if (extra) { for (var k in extra) { if (Object.prototype.hasOwnProperty.call(extra, k)) { ev[k] = extra[k]; } } }
    queue.push(ev);
    persist();
  }

  // ------------------------------------------------------------- classifiers
  var TEXT_TYPES = /^(text|password|email|search|tel|url|number|date|time|datetime-local|month|week)$/;
  function isTextual(el) {
    if (!el || el.nodeType !== 1) { return false; }
    var tag = tagOf(el);
    if (tag === 'textarea') { return true; }
    if (tag === 'input') { return TEXT_TYPES.test(el.type || 'text'); }
    return !!el.isContentEditable && tag !== 'html' && tag !== 'body';
  }
  var INTERACTIVE = 'a,button,input,select,textarea,label,summary,[role=button],[role=link],[role=tab],[role=menuitem],'
    + '[role=option],[role=checkbox],[role=radio],[role=switch],[onclick],[tabindex]:not([tabindex="-1"])';
  function climb(el) {
    var cur = el, hops = 0;
    while (cur && cur.nodeType === 1 && hops < 6) {
      if (cur.matches && cur.matches(INTERACTIVE)) { return cur; }
      if (tagOf(cur) === 'body' || tagOf(cur) === 'html') { break; }
      cur = cur.parentElement; hops++;
    }
    return el;
  }
  function inToolbar(el) { return !!(el && el.closest && el.closest('#__kdt_toolbar')); }
  function valueOf(el) { return el.isContentEditable && tagOf(el) !== 'input' && tagOf(el) !== 'textarea' ? (el.innerText || '') : (el.value || ''); }

  // --------------------------------------------------------------- handlers
  function flush() {
    if (!pending) { return; }
    var p = pending; pending = null;
    var el = p.el;
    var before = initialValue.get(el);
    emit('type', el, { value: valueOf(el), hadValue: !!before }, p.chain);
    initialValue.set(el, valueOf(el));
  }

  function onClick(e, chain) {
    if (!e.isTrusted) { return; }
    var t = e.target;
    if (!t || t.nodeType !== 1 || inToolbar(t)) { return; }
    var el = climb(t), tag = tagOf(el), now = Date.now();
    if (state.assertMode) {
      e.preventDefault(); e.stopImmediatePropagation();
      var mode = state.assertMode;
      state.assertMode = ''; ssSet('__kdt_assert', ''); refreshToolbar();
      if (mode === 'visible') { emit('assertVisible', el, null, chain); }
      else if (isTextual(el) || tag === 'select') { emit('assertValue', el, { value: valueOf(el) }, chain); }
      else { emit('assertText', el, { text: norm(el.innerText !== undefined ? el.innerText : el.textContent).slice(0, 200) }, chain); }
      return;
    }
    flush();
    if (tag === 'select' || tag === 'option') { return; }
    if (suppress.el === el && now < suppress.until) { return; }
    var type = tag === 'input' ? (el.type || 'text') : '';
    if (type === 'file') { return; }
    if (tag === 'label' && el.control) { suppress = { el: el.control, until: now + 250 }; }
    if (tag === 'input' && (type === 'checkbox' || type === 'radio')) {
      emit('check', el, { checked: !!el.checked }, chain);
      return;
    }
    emit('click', el, { soft: isTextual(el) }, chain);
  }

  function onDblClick(e, chain) {
    if (!e.isTrusted || state.assertMode || inToolbar(e.target)) { return; }
    emit('dblclick', climb(e.target), null, chain);
  }
  function onContextMenu(e, chain) {
    if (!e.isTrusted || state.assertMode || inToolbar(e.target)) { return; }
    flush();
    emit('rightclick', climb(e.target), null, chain);
  }
  function onInput(e, chain) {
    if (!e.isTrusted) { return; }
    var el = e.target;
    if (!isTextual(el)) { return; }
    pending = { el: el, chain: chain };
  }
  function onFocusIn(e) {
    var el = e.target;
    if (isTextual(el) && !initialValue.has(el)) { initialValue.set(el, valueOf(el)); }
  }
  function onChange(e, chain) {
    if (!e.isTrusted) { return; }
    var el = e.target;
    if (isTextual(el)) { flush(); return; }
    var tag = tagOf(el);
    if (tag === 'select') {
      var opt = el.options[el.selectedIndex];
      emit('select', el, { text: opt ? norm(opt.text) : '', value: el.value, index: el.selectedIndex }, chain);
    } else if (tag === 'input' && el.type === 'file') {
      var names = [];
      for (var i = 0; el.files && i < el.files.length; i++) { names.push(el.files[i].name); }
      emit('upload', el, { text: names.join(',') }, chain);
    }
  }
  function onKeyDown(e, chain) {
    if (!e.isTrusted || e.ctrlKey || e.metaKey || e.altKey) { return; }
    var el = e.target;
    if (!el || el.nodeType !== 1 || inToolbar(el)) { return; }
    var key = null;
    if (e.key === 'Enter' && isTextual(el) && tagOf(el) !== 'textarea') { key = 'ENTER'; }
    else if (e.key === 'Escape') { key = 'ESCAPE'; }
    if (key) { flush(); emit('key', el, { key: key }, chain); }
  }
  function onMouseOver(e, chain) {
    if (!state.hover || !e.isTrusted) { return; }
    var el = climb(e.target);
    if (!el || inToolbar(el) || tagOf(el) === 'body' || tagOf(el) === 'html') { return; }
    if (hoverTimer) { clearTimeout(hoverTimer); }
    hoverTimer = setTimeout(guard(function () { emit('hover', el, null, chain); hoverTimer = null; }), 700);
  }
  function onMouseOut() { if (hoverTimer) { clearTimeout(hoverTimer); hoverTimer = null; } }

  function attach(doc, chain) {
    if (doc.__kdtAttached) { return; }
    doc.__kdtAttached = true;
    doc.addEventListener('click', guard(function (e) { onClick(e, chain); }), true);
    doc.addEventListener('dblclick', guard(function (e) { onDblClick(e, chain); }), true);
    doc.addEventListener('contextmenu', guard(function (e) { onContextMenu(e, chain); }), true);
    doc.addEventListener('input', guard(function (e) { onInput(e, chain); }), true);
    doc.addEventListener('focusin', guard(onFocusIn), true);
    doc.addEventListener('focusout', guard(flush), true);
    doc.addEventListener('change', guard(function (e) { onChange(e, chain); }), true);
    doc.addEventListener('keydown', guard(function (e) { onKeyDown(e, chain); }), true);
    doc.addEventListener('mouseover', guard(function (e) { onMouseOver(e, chain); }), true);
    doc.addEventListener('mouseout', guard(onMouseOut), true);
  }

  var frameChains = new WeakMap();

  // ---------------------------------------------------------------- toolbar
  var toolbar = null;
  function btn(label, title, onClick2) {
    var b = document.createElement('button');
    b.type = 'button'; b.textContent = label; b.title = title;
    b.style.cssText = 'margin:0 3px;padding:3px 8px;font:12px sans-serif;cursor:pointer;border:1px solid #888;border-radius:3px;background:#fff;color:#000;';
    b.addEventListener('click', guard(function (e) { e.preventDefault(); e.stopPropagation(); onClick2(); }), true);
    return b;
  }
  function refreshToolbar() {
    if (!toolbar) { return; }
    var bs = toolbar.__buttons;
    bs.text.style.background = state.assertMode === 'text' ? '#ffd54f' : '#fff';
    bs.visible.style.background = state.assertMode === 'visible' ? '#ffd54f' : '#fff';
    bs.hover.style.background = state.hover ? '#80cbc4' : '#fff';
    bs.hover.textContent = state.hover ? 'Hover: ON' : 'Hover: off';
    toolbar.__label.textContent = state.assertMode ? 'Click the element to verify' : 'REC';
  }
  function ensureToolbar() {
    if (!document.body) { return; }
    if (toolbar && document.body.contains(toolbar)) { return; }
    toolbar = document.createElement('div');
    toolbar.id = '__kdt_toolbar';
    toolbar.style.cssText = 'position:fixed;top:6px;right:6px;z-index:2147483647;background:#222;color:#fff;padding:4px 6px;'
      + 'border-radius:4px;font:12px sans-serif;box-shadow:0 1px 4px rgba(0,0,0,.5);opacity:.92;';
    var label = document.createElement('span');
    label.style.cssText = 'color:#ff5252;font-weight:bold;margin-right:4px;';
    var bs = {};
    bs.text = btn('Verify text', 'Next click records a text/value check', function () {
      state.assertMode = state.assertMode === 'text' ? '' : 'text'; ssSet('__kdt_assert', state.assertMode); refreshToolbar(); });
    bs.visible = btn('Verify visible', 'Next click records a "is displayed" check', function () {
      state.assertMode = state.assertMode === 'visible' ? '' : 'visible'; ssSet('__kdt_assert', state.assertMode); refreshToolbar(); });
    bs.hover = btn('Hover: off', 'When ON, resting the mouse on an element records a HOVER step', function () {
      state.hover = !state.hover; ssSet('__kdt_hover', state.hover ? '1' : '0'); refreshToolbar(); });
    bs.stop = btn('Stop', 'Finish recording', function () { flush(); state.stop = true; ssSet('__kdt_stop', '1'); });
    toolbar.appendChild(label);
    toolbar.appendChild(bs.text); toolbar.appendChild(bs.visible); toolbar.appendChild(bs.hover); toolbar.appendChild(bs.stop);
    toolbar.__buttons = bs; toolbar.__label = label;
    document.body.appendChild(toolbar);
    refreshToolbar();
  }

  // -------------------------------------------------------------------- API
  window.__kdtRec = {
    version: VERSION,
    drain: function () {
      var out = queue; queue = []; persist();
      return JSON.stringify({ stop: state.stop, events: out });
    },
    flush: flush
  };

  attach(document, []);
  window.addEventListener('pagehide', guard(flush), true);
  window.addEventListener('beforeunload', guard(flush), true);
  setInterval(guard(function () { ensureToolbar(); scanFramesTop(); }), 400);
  ensureToolbar();
  scanFramesTop();

  function scanFramesTop() { scanFramesIn(document, []); }
  function scanFramesIn(doc, chain) {
    var frames = doc.querySelectorAll('iframe,frame');
    for (var i = 0; i < frames.length; i++) {
      var cd = null;
      try { cd = frames[i].contentDocument; } catch (e) { cd = null; }
      if (!cd || !cd.documentElement) { continue; }
      var myChain = frameChains.get(cd);
      if (!myChain) {
        myChain = chain.concat([{ cands: candidates(frames[i]), tag: tagOf(frames[i]), name: attr(frames[i], 'name'),
          id: attr(frames[i], 'id'), title: attr(frames[i], 'title') }]);
        frameChains.set(cd, myChain);
        attach(cd, myChain);
      }
      scanFramesIn(cd, myChain);
    }
  }
})();
