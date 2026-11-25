(function () {
  "use strict";
  var ORDER = { "in-progress": 0, blocked: 1, planned: 2, done: 3 };
  var steps = [];
  var filter = "all";
  var lastFocus = null;
  var $ = function (id) { return document.getElementById(id); };
  var root = document.documentElement;

  // theme (dark default)
  try { var t = localStorage.getItem("vp-theme"); if (t) root.dataset.theme = t; } catch (e) {}
  $("theme").addEventListener("click", function () {
    var n = root.dataset.theme === "dark" ? "light" : "dark";
    root.dataset.theme = n;
    try { localStorage.setItem("vp-theme", n); } catch (e) {}
  });

  function el(tag, cls, text) {
    var e = document.createElement(tag);
    if (cls) e.className = cls;
    if (text != null) e.textContent = text;
    return e;
  }

  function load() {
    // Prefer fetch when served over http; fall back to window.STEPS_DATA (file://).
    if (location.protocol.indexOf("http") === 0 && window.fetch) {
      return fetch("data/steps.json", { cache: "no-store" })
        .then(function (r) { if (!r.ok) throw new Error(r.status); return r.json(); })
        .catch(function () { return window.STEPS_DATA; });
    }
    return Promise.resolve(window.STEPS_DATA);
  }

  function sorted(list) {
    var open = list.filter(function (s) { return s.status !== "done"; })
      .sort(function (a, b) { return ORDER[a.status] - ORDER[b.status]; });
    var done = list.filter(function (s) { return s.status === "done"; }).reverse(); // newest first (ids ascend)
    return open.concat(done);
  }

  function renderFilters() {
    var box = $("filters");
    box.textContent = "";
    var counts = { all: steps.length };
    steps.forEach(function (s) { counts[s.status] = (counts[s.status] || 0) + 1; });
    ["all", "in-progress", "planned", "blocked", "done"].forEach(function (k) {
      if (!counts[k] && k !== "all") return;
      var b = el("button", "chip", k + " " + counts[k]);
      b.type = "button";
      b.setAttribute("aria-pressed", String(filter === k));
      b.addEventListener("click", function () { filter = k; renderFilters(); renderList(); });
      box.appendChild(b);
    });
  }

  var io = "IntersectionObserver" in window ? new IntersectionObserver(function (es) {
    es.forEach(function (e) { if (e.isIntersecting) { e.target.classList.add("in"); io.unobserve(e.target); } });
  }, { rootMargin: "0px 0px -5% 0px" }) : null;

  function renderList() {
    var ol = $("timeline");
    ol.textContent = "";
    var list = sorted(steps).filter(function (s) { return filter === "all" || s.status === filter; });
    $("empty").hidden = list.length > 0;
    list.forEach(function (s) {
      var li = el("li", "item");
      li.dataset.status = s.status;
      li.appendChild(el("span", "dot"));
      var b = el("button", "card");
      b.type = "button";
      b.appendChild(el("span", "card-title", s.title));
      var m = el("span", "meta");
      m.appendChild(el("span", "badge b-" + s.status, s.status));
      m.appendChild(el("span", null, s.date));
      if (s.commit) m.appendChild(el("code", null, s.commit.hash));
      b.appendChild(m);
      b.addEventListener("click", function () { openPanel(s); });
      li.appendChild(b);
      ol.appendChild(li);
      if (io) io.observe(li); else li.classList.add("in");
    });
  }

  function section(body, title, items, mono) {
    if (!items || !items.length) return;
    body.appendChild(el("h3", null, title));
    var ul = el("ul");
    items.forEach(function (x) {
      var li = el("li");
      if (typeof x === "string") { li.textContent = x; }
      else if (x.path) {
        li.appendChild(el("span", "chg chg-" + x.change, x.change || ""));
        li.appendChild(el("span", "mono", x.path));
      } else {
        if (x.url) {
          var a = el("a", null, x.name); a.href = x.url; a.target = "_blank"; a.rel = "noopener";
          li.appendChild(a);
        } else li.appendChild(el("strong", null, x.name));
        if (x.note) li.appendChild(document.createTextNode(" - " + x.note));
      }
      ul.appendChild(li);
    });
    if (mono && items.length > 25) {
      var d = el("details");
      d.appendChild(el("summary", null, items.length + " files"));
      d.appendChild(ul);
      body.appendChild(d);
    } else body.appendChild(ul);
  }

  function openPanel(s) {
    lastFocus = document.activeElement;
    $("p-title").textContent = s.title;
    var body = $("p-body");
    body.textContent = "";
    var kv = el("dl", "kv");
    function row(k, v) { kv.appendChild(el("dt", null, k)); var d = el("dd"); if (v instanceof Node) d.appendChild(v); else d.textContent = v; kv.appendChild(d); }
    row("status", s.status);
    row("date", s.date);
    if (s.commit) { row("commit", s.commit.hash + " (" + s.commit.date + ")"); if (s.commit.merge_subject) row("merge", s.commit.merge_subject); }
    row("tests", s.tests || "not recorded");
    body.appendChild(kv);
    if (s.description) { body.appendChild(el("h3", null, "Description")); body.appendChild(el("p", null, s.description)); }
    section(body, "Added", s.added);
    section(body, "Removed", s.removed);
    section(body, "Changed", s.changed);
    section(body, "Files", s.files, true);
    section(body, "Inspiration / sources", s.sources);
    if (s.screenshot) {
      body.appendChild(el("h3", null, "Screenshot"));
      var img = el("img"); img.src = s.screenshot; img.alt = "Screenshot for " + s.title; img.loading = "lazy";
      body.appendChild(img);
    }
    var p = $("panel"), sc = $("scrim");
    sc.hidden = false;
    p.setAttribute("aria-hidden", "false");
    requestAnimationFrame(function () { sc.classList.add("show"); p.classList.add("open"); $("p-close").focus(); });
    body.scrollTop = 0;
  }

  function closePanel() {
    var p = $("panel"), sc = $("scrim");
    if (!p.classList.contains("open")) return;
    p.classList.remove("open"); sc.classList.remove("show");
    p.setAttribute("aria-hidden", "true");
    setTimeout(function () { if (!p.classList.contains("open")) sc.hidden = true; }, 350);
    if (lastFocus && lastFocus.focus) lastFocus.focus();
  }

  $("p-close").addEventListener("click", closePanel);
  $("scrim").addEventListener("click", closePanel);
  document.addEventListener("keydown", function (e) {
    var p = $("panel");
    if (!p.classList.contains("open")) return;
    if (e.key === "Escape") { closePanel(); return; }
    if (e.key === "Tab") { // keep focus inside the dialog
      var f = p.querySelectorAll("button,a[href],summary");
      if (!f.length) return;
      var first = f[0], last = f[f.length - 1];
      if (e.shiftKey && document.activeElement === first) { e.preventDefault(); last.focus(); }
      else if (!e.shiftKey && document.activeElement === last) { e.preventDefault(); first.focus(); }
    }
  });

  load().then(function (d) {
    steps = (d && d.steps) || [];
    var done = steps.filter(function (s) { return s.status === "done"; }).length;
    $("summary").textContent = done + " done, " + (steps.length - done) + " open";
    renderFilters();
    renderList();
  });
})();
