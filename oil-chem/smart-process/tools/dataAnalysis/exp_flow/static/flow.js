// 物料流向分析前端：力导向图 + 匹配明细表
(function() {
  "use strict";
  var graphData = null;
  var nodeMap = {};
  function $(id) { return document.getElementById(id); }

  function loadGraph() {
    fetch("/api/flow-graph")
      .then(function(r) { return r.json(); })
      .then(function(d) {
        graphData = d;
        renderSummary(d);
        if (d.has_data) {
          renderMatchTable(d.matches || []);
          drawForceGraph(d);
        }
      });
  }

  function renderSummary(d) {
    var html = "";
    html += summaryCard("装置数", (d.mb_sources || []).length);
    html += summaryCard("储罐物料数", d.tank_material_count || 0);
    html += summaryCard("已匹配物料", (d.matched_count || 0) + " / " + (d.total_tank_materials || 0));
    html += summaryCard("流向边数", (d.edges || []).length);
    html += summaryCard("节点总数", (d.nodes || []).length);
    $("summaryRow").innerHTML = html;
  }

  function summaryCard(label, value) {
    return '<div class="summary-card"><div class="label">' + label + '</div><div class="value">' + value + "</div></div>";
  }

  function esc(s) {
    return String(s).replace(/&/g,"&amp;").replace(/</g,"&lt;").replace(/>/g,"&gt;");
  }

  function renderMatchTable(matches) {
    var tbody = $("matchTable").querySelector("tbody");
    var html = "";
    matches.forEach(function(m) {
      html += "<tr><td>" + esc(m.tank_material) + "</td>" +
        "<td>" + (m.matched_mb_material ? esc(m.matched_mb_material) : "—") + "</td>" +
        "<td>" + (m.matched ? m.score : "—") + "</td>" +
        '<td class="' + (m.matched ? "yes" : "no") + '">' + (m.matched ? "已匹配" : "未匹配") + "</td></tr>";
    });
    tbody.innerHTML = html;
  }

  function drawForceGraph(d) {
    var svg = $("flowGraph");
    var W = svg.clientWidth || 1360;
    var H = 600;
    svg.setAttribute("viewBox", "0 0 " + W + " " + H);
    svg.innerHTML = "";
    var NS = "http://www.w3.org/2000/svg";
    function el(tag, attrs) {
      var e = document.createElementNS(NS, tag);
      for (var k in attrs) e.setAttribute(k, attrs[k]);
      return e;
    }

    var nodes = d.nodes.map(function(n) {
      var color = n.type === "unit" ? "#2563eb" : n.type === "tank" ? "#16a34a" : n.type === "external_in" ? "#d97706" : "#dc2626";
      var r = n.type === "unit" ? 28 : n.type === "tank" ? 18 : 12;
      return { id: n.id, label: n.label, type: n.type, color: color, r: r,
        x: W/2 + (Math.random() - 0.5) * 200, y: H/2 + (Math.random() - 0.5) * 200, vx: 0, vy: 0 };
    });
    nodeMap = {};
    nodes.forEach(function(n) { nodeMap[n.id] = n; });
    var edges = d.edges || [];

    nodes.forEach(function(n) {
      if (n.type === "unit") { n.x = W / 2; n.y = H / 2; n.fixed = true; }
    });

    var iterations = 300, k = 80, repulsion = 6000;
    for (var iter = 0; iter < iterations; iter++) {
      for (var i = 0; i < nodes.length; i++) {
        for (var j = i + 1; j < nodes.length; j++) {
          var dx = nodes[i].x - nodes[j].x, dy = nodes[i].y - nodes[j].y;
          var dist = Math.sqrt(dx*dx + dy*dy) || 1;
          var force = repulsion / (dist * dist);
          var fx = (dx / dist) * force, fy = (dy / dist) * force;
          nodes[i].vx += fx; nodes[i].vy += fy;
          nodes[j].vx -= fx; nodes[j].vy -= fy;
        }
      }
      edges.forEach(function(e) {
        var s = nodeMap[e.source], t = nodeMap[e.target];
        if (!s || !t) return;
        var dx = t.x - s.x, dy = t.y - s.y;
        var dist = Math.sqrt(dx*dx + dy*dy) || 1;
        var force = (dist - k) * 0.05;
        s.vx += (dx / dist) * force; s.vy += (dy / dist) * force;
        t.vx -= (dx / dist) * force; t.vy -= (dy / dist) * force;
      });
      nodes.forEach(function(n) {
        if (n.type === "unit") return;
        n.vx += (W/2 - n.x) * 0.002; n.vy += (H/2 - n.y) * 0.002;
      });
      nodes.forEach(function(n) {
        if (n.fixed) return;
        n.x += n.vx * 0.1; n.y += n.vy * 0.1;
        n.vx *= 0.85; n.vy *= 0.85;
        n.x = Math.max(n.r + 5, Math.min(W - n.r - 5, n.x));
        n.y = Math.max(n.r + 5, Math.min(H - n.r - 5, n.y));
      });
    }

    var defs = el("defs", {});
    ["feed", "product"].forEach(function(t) {
      var marker = el("marker", { id: "arrow-" + t, viewBox: "0 0 10 10", refX: 8, refY: 5, markerWidth: 6, markerHeight: 6, orient: "auto" });
      marker.appendChild(el("path", { d: "M0,0 L10,5 L0,10 Z", fill: t === "feed" ? "#cbd5e0" : "#86efac" }));
      defs.appendChild(marker);
    });
    svg.appendChild(defs);

    var edgeGroup = el("g", {});
    svg.appendChild(edgeGroup);
    var edgeLines = [];
    edges.forEach(function(e) {
      var s = nodeMap[e.source], t = nodeMap[e.target];
      if (!s || !t) return;
      var color = e.type === "feed" ? "#cbd5e0" : "#86efac";
      var line = el("line", { x1: s.x, y1: s.y, x2: t.x, y2: t.y, stroke: color, "stroke-width": 1.5, opacity: 0.6, "marker-end": "url(#arrow-" + e.type + ")" });
      edgeGroup.appendChild(line);
      edgeLines.push({ line: line, e: e });
      var mx = (s.x + t.x) / 2, my = (s.y + t.y) / 2;
      var lbl = el("text", { x: mx, y: my - 3, "text-anchor": "middle", "font-size": 8, fill: "#666" });
      lbl.textContent = e.label + (e.value ? " (" + e.value.toFixed(0) + ")" : "");
      edgeGroup.appendChild(lbl);
    });

    var tooltip = $("tooltip");
    nodes.forEach(function(n) {
      var g = el("g", { transform: "translate(" + n.x + "," + n.y + ")", cursor: "pointer" });
      if (n.type === "unit") {
        g.appendChild(el("rect", { x: -n.r, y: -n.r, width: n.r*2, height: n.r*2, rx: 6, fill: n.color, opacity: 0.9, stroke: "#fff", "stroke-width": 2 }));
      } else {
        g.appendChild(el("circle", { cx: 0, cy: 0, r: n.r, fill: n.color, opacity: 0.85, stroke: "#fff", "stroke-width": 1.5 }));
      }
      var lbl = el("text", { x: 0, y: n.r + 12, "text-anchor": "middle", "font-size": n.type === "unit" ? 11 : 9, fill: "#333", "font-weight": n.type === "unit" ? "600" : "400" });
      lbl.textContent = n.label.length > 8 ? n.label.substring(0, 7) + "…" : n.label;
      g.appendChild(lbl);

      var dragging = false, offsetX, offsetY;
      g.addEventListener("mousedown", function(e) {
        dragging = true;
        var rect = svg.getBoundingClientRect();
        offsetX = (e.clientX - rect.left) * (W / rect.width) - n.x;
        offsetY = (e.clientY - rect.top) * (H / rect.height) - n.y;
        e.preventDefault();
      });
      document.addEventListener("mousemove", function(e) {
        if (!dragging) return;
        var rect = svg.getBoundingClientRect();
        n.x = (e.clientX - rect.left) * (W / rect.width) - offsetX;
        n.y = (e.clientY - rect.top) * (H / rect.height) - offsetY;
        n.x = Math.max(n.r + 5, Math.min(W - n.r - 5, n.x));
        n.y = Math.max(n.r + 5, Math.min(H - n.r - 5, n.y));
        g.setAttribute("transform", "translate(" + n.x + "," + n.y + ")");
        edgeLines.forEach(function(el2) {
          if (el2.e.source === n.id || el2.e.target === n.id) {
            var s = nodeMap[el2.e.source], t = nodeMap[el2.e.target];
            el2.line.setAttribute("x1", s.x); el2.line.setAttribute("y1", s.y);
            el2.line.setAttribute("x2", t.x); el2.line.setAttribute("y2", t.y);
          }
        });
      });
      document.addEventListener("mouseup", function() { dragging = false; });

      g.addEventListener("mouseenter", function() {
        var connected = edges.filter(function(ed) { return ed.source === n.id || ed.target === n.id; });
        var html = "<b>" + n.label + "</b><br>关联 " + connected.length + " 条流向";
        if (connected.length <= 8) {
          connected.forEach(function(c) {
            html += "<br>" + (c.type === "feed" ? "←" : "→") + " " + c.label + " (" + (c.value || 0).toFixed(0) + ")";
          });
        }
        tooltip.innerHTML = html;
        tooltip.style.display = "block";
      });
      g.addEventListener("mousemove", function(e) {
        tooltip.style.left = (e.pageX + 12) + "px";
        tooltip.style.top = (e.pageY + 12) + "px";
      });
      g.addEventListener("mouseleave", function() { tooltip.style.display = "none"; });
      svg.appendChild(g);
    });
  }

  loadGraph();
})();
