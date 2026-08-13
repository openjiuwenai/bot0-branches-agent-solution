(function () {
  "use strict";
  var overview = null;
  var selectedTanks = [];
  var selectedMats = [];
  var currentTimePoint = "前尺";

  var COLORS = ["#2563eb","#dc2626","#16a34a","#d97706","#9333ea","#0891b2","#db2777","#65a30d","#ea580c","#7c3aed","#0d9488","#be185d","#4f46e5","#0369a1","#c2410c"];

  function $(id) { return document.getElementById(id); }
  function fmt(n) { if (n == null || isNaN(n)) return "-"; return Number(n).toLocaleString("zh-CN", {maximumFractionDigits:2}); }
  function esc(s) { return String(s == null ? "" : s).replace(/[&<>"]/g, function(c){ return {"&":"&amp;","<":"&lt;",">":"&gt;",'"':"&quot;"}[c]; }); }

  function loadSources() {
    fetch("/api/tank-analysis/sources").then(function(r){ return r.json(); }).then(function(res){
      var sel = $("tankSource");
      sel.innerHTML = "";
      (res.sources || []).forEach(function(s){
        var opt = document.createElement("option");
        opt.value = s.source_id;
        opt.textContent = s.name + " (" + s.row_count + " 行)";
        sel.appendChild(opt);
      });
      if (sel.options.length === 0) {
        sel.innerHTML = '<option value="">无罐表数据</option>';
      }
    });
  }

  function loadOverview() {
    var sid = $("tankSource").value;
    if (!sid) { alert("请选择数据源"); return; }
    fetch("/api/tank-analysis/overview?source_id=" + encodeURIComponent(sid))
      .then(function(r){ return r.json(); })
      .then(function(res){
        if (!res.has_data) {
          showErrors(res.errors || ["无数据"]);
          $("tankMain").classList.add("hidden");
          return;
        }
        overview = res;
        if (res.dates && res.dates.length > 0) {
          $("tankRangeStart").value = res.dates[0];
          $("tankRangeEnd").value = res.dates[res.dates.length - 1];
        }
        $("tankMain").classList.remove("hidden");
        $("tankErrors").classList.add("hidden");
        selectedTanks = [];
        selectedMats = [];
        updateTankDropdown();
        updateMatDropdown();
        updateDropdownBtns();
        queryTrend();
      });
  }

  function updateTankDropdown() {
    var list = $("tankDropdownList");
    list.innerHTML = "";
    var search = ($("tankSearch").value || "").toLowerCase();
    (overview.tanks || []).forEach(function(t){
      // If materials selected, only show tanks related to those materials
      if (selectedMats.length > 0) {
        var prods = t.products || [t.product];
        var hasMatch = prods.some(function(p){ return selectedMats.indexOf(p) >= 0; });
        if (!hasMatch) return;
      }
      if (search && t.tank_id.toLowerCase().indexOf(search) < 0 && (t.product || "").toLowerCase().indexOf(search) < 0) return;
      var label = document.createElement("label");
      label.className = "mat-check-item";
      var cb = document.createElement("input");
      cb.type = "checkbox";
      cb.value = t.tank_id;
      cb.checked = selectedTanks.indexOf(t.tank_id) >= 0;
      cb.addEventListener("change", function(){
        if (cb.checked) { if (selectedTanks.indexOf(t.tank_id) < 0) selectedTanks.push(t.tank_id); }
        else { selectedTanks = selectedTanks.filter(function(x){ return x !== t.tank_id; }); }
        updateDropdownBtns();
        updateMatDropdown();
      });
      label.appendChild(cb);
      var prodText = (t.products && t.products.length > 1) ? t.products.join(", ") : (t.product || "");
      label.appendChild(document.createTextNode(" " + t.tank_id + " (" + prodText + ")"));
      list.appendChild(label);
    });
  }

  function updateMatDropdown() {
    var list = $("matDropdownList");
    list.innerHTML = "";
    var search = ($("matSearch").value || "").toLowerCase();
    (overview.materials || []).forEach(function(m){
      // If tanks selected, only show materials related to those tanks
      if (selectedTanks.length > 0) {
        var tanks = m.tanks || [];
        var hasMatch = tanks.some(function(t){ return selectedTanks.indexOf(t) >= 0; });
        if (!hasMatch) return;
      }
      if (search && m.name.toLowerCase().indexOf(search) < 0) return;
      var label = document.createElement("label");
      label.className = "mat-check-item";
      var cb = document.createElement("input");
      cb.type = "checkbox";
      cb.value = m.name;
      cb.checked = selectedMats.indexOf(m.name) >= 0;
      cb.addEventListener("change", function(){
        if (cb.checked) { if (selectedMats.indexOf(m.name) < 0) selectedMats.push(m.name); }
        else { selectedMats = selectedMats.filter(function(x){ return x !== m.name; }); }
        updateDropdownBtns();
        updateTankDropdown();
      });
      label.appendChild(cb);
      label.appendChild(document.createTextNode(" " + m.name + " (" + m.tank_count + "罐)"));
      list.appendChild(label);
    });
  }

  function updateDropdownBtns() {
    var tbtn = $("tankDropdownBtn");
    if (selectedTanks.length === 0) tbtn.textContent = "请选择罐号 ▾";
    else if (selectedTanks.length <= 3) tbtn.textContent = selectedTanks.join(", ") + " ▾";
    else tbtn.textContent = "已选 " + selectedTanks.length + " 罐 ▾";

    var mbtn = $("matDropdownBtn");
    if (selectedMats.length === 0) mbtn.textContent = "请选择物料 ▾";
    else if (selectedMats.length <= 3) mbtn.textContent = selectedMats.join(", ") + " ▾";
    else mbtn.textContent = "已选 " + selectedMats.length + " 种 ▾";
  }

  function queryTrend() {
    if (!overview) return;
    var sid = $("tankSource").value;
    var tanksParam = selectedTanks.join(",");
    var matsParam = selectedMats.join(",");
    var url = "/api/tank-analysis/trend?source_id=" + encodeURIComponent(sid) +
      "&tanks=" + encodeURIComponent(tanksParam) +
      "&materials=" + encodeURIComponent(matsParam) +
      "&time_point=" + encodeURIComponent(currentTimePoint);
    $("tankStatus").textContent = "查询中...";
    fetch(url).then(function(r){ return r.json(); }).then(function(res){
      $("tankStatus").textContent = "";
      if (!res.has_data) {
        showErrors(res.errors || ["无数据"]);
        clearAll();
        return;
      }
      $("tankErrors").classList.add("hidden");
      var startDate = $("tankRangeStart").value;
      var endDate = $("tankRangeEnd").value;
      var filteredDates = res.dates.filter(function(d){
        if (startDate && d < startDate) return false;
        if (endDate && d > endDate) return false;
        return true;
      });
      var flLevel = filterSeries(res.level_series, filteredDates);
      var flVolume = filterSeries(res.volume_series, filteredDates);
      renderStats(res.stats, res.items);
      drawChart("levelChart", "levelChartWrap", "levelTooltip", filteredDates, flLevel, res.items, "液位");
      drawChart("volumeChart", "volumeChartWrap", "volumeTooltip", filteredDates, flVolume, res.items, "罐量");
      drawMatTotalChart(filteredDates, flVolume, res.items);
    }).catch(function(){ $("tankStatus").textContent = "查询失败"; });
  }

  function filterSeries(series, dates) {
    var out = {};
    for (var key in series) {
      out[key] = {};
      dates.forEach(function(d){
        if (series[key][d] !== undefined) out[key][d] = series[key][d];
      });
    }
    return out;
  }

  function renderStats(stats, items) {
    var tbody = $("statsTable").querySelector("tbody");
    tbody.innerHTML = "";
    items.sort(function(a,b){
      var sa = (stats[a.key]||{}).volume||{}, sb = (stats[b.key]||{}).volume||{};
      return (sb.mean||0) - (sa.mean||0);
    });
    items.forEach(function(item){
      var s = stats[item.key] || {};
      var lv = s.level || {}, vv = s.volume || {};
      var typeLabel = s.type === "tank" ? "罐号" : "物料";
      var tr = document.createElement("tr");
      tr.innerHTML = "<td>" + esc(s.label || item.label) + "</td>" +
        "<td style='text-align:center'>" + typeLabel + "</td>" +
        "<td>" + fmt(lv.max) + "</td><td>" + fmt(lv.min) + "</td><td>" + fmt(lv.mean) + "</td><td>" + fmt(lv.total) + "</td>" +
        "<td>" + fmt(vv.max) + "</td><td>" + fmt(vv.min) + "</td><td>" + fmt(vv.mean) + "</td><td>" + fmt(vv.total) + "</td>" +
        "<td>" + (vv.count || 0) + "</td>";
      tbody.appendChild(tr);
    });
  }

  function drawChart(svgId, wrapId, tooltipId, dates, series, items, metric) {
    var svg = $(svgId);
    var wrap = $(wrapId);
    var W = Math.max(wrap.clientWidth, 600);
    var H = 380;
    var M = {l:64, r:24, t:16, b:56};
    var iw = W - M.l - M.r, ih = H - M.t - M.b;
    svg.setAttribute("viewBox", "0 0 " + W + " " + H);
    svg.removeAttribute("width");
    svg.removeAttribute("height");
    svg.style.width = "";
    svg.style.height = "";
    svg.innerHTML = "";
    var n = dates.length;
    var nItems = items.length;
    var NS = "http://www.w3.org/2000/svg";
    function el(tag, attrs) {
      var e = document.createElementNS(NS, tag);
      for (var k in attrs) e.setAttribute(k, attrs[k]);
      return e;
    }
    if (n === 0 || nItems === 0) {
      var t = el("text", {x:W/2, y:H/2, "text-anchor":"middle", fill:"#999", "font-size":"14"});
      t.textContent = "无数据，请选择罐号或物料";
      svg.appendChild(t);
      return;
    }
    var yMin = Infinity, yMax = -Infinity;
    items.forEach(function(item){
      var data = series[item.key] || {};
      for (var d in data) {
        var v = data[d];
        if (v != null && !isNaN(v) && v !== 0) { if (v < yMin) yMin = v; if (v > yMax) yMax = v; }
      }
    });
    if (yMin === Infinity) { yMin = 0; yMax = 1; }
    var yPad = (yMax - yMin) * 0.1 || 1;
    yMin = Math.max(0, yMin - yPad);
    yMax = yMax + yPad;
    function x(i) { return M.l + (n === 1 ? iw/2 : i/(n-1) * iw); }
    function y(v) { return M.t + (1 - (v - yMin)/(yMax - yMin)) * ih; }

    var yTicks = 6;
    for (var i = 0; i <= yTicks; i++) {
      var yv = yMin + (yMax - yMin) * i / yTicks;
      var yp = y(yv);
      svg.appendChild(el("line", {x1:M.l, y1:yp, x2:W-M.r, y2:yp, stroke:"#e2e8f0", "stroke-width":1}));
      var lbl = el("text", {x:M.l-8, y:yp+4, "text-anchor":"end", fill:"#94a3b8", "font-size":"11"});
      lbl.textContent = fmt(yv);
      svg.appendChild(lbl);
    }
    var xStep = Math.max(1, Math.ceil(n / 10));
    for (var i = 0; i < n; i += xStep) {
      var xp = x(i);
      var xlbl = el("text", {x:xp, y:H-M.b+16, "text-anchor":"middle", fill:"#94a3b8", "font-size":"11"});
      xlbl.textContent = dates[i].substring(5);
      svg.appendChild(xlbl);
    }

    items.forEach(function(item, idx){
      var color = COLORS[idx % COLORS.length];
      var data = series[item.key] || {};
      var path = "";
      var pts = [];
      var prevHadData = false;
      for (var i = 0; i < n; i++) {
        var v = data[dates[i]];
        if (v != null && !isNaN(v) && v !== 0) {
          var px = x(i), py = y(v);
          if (!prevHadData) path += "M" + px + " " + py;
          else path += " L" + px + " " + py;
          pts.push({x:px, y:py, v:v, d:dates[i]});
          prevHadData = true;
        } else {
          prevHadData = false;
        }
      }
      if (path) {
        svg.appendChild(el("path", {d:path, fill:"none", stroke:color, "stroke-width":2, "stroke-linejoin":"round", "stroke-linecap":"round"}));
      }
      pts.forEach(function(pt){
        var c = el("circle", {cx:pt.x, cy:pt.y, r:3, fill:color, stroke:"#fff", "stroke-width":1, "data-item":item.label, "data-date":pt.d, "data-val":pt.v, class:"tp-pt"});
        svg.appendChild(c);
      });
    });

    var legendY = H - M.b + 36;
    var lx = M.l;
    items.forEach(function(item, idx){
      var color = COLORS[idx % COLORS.length];
      svg.appendChild(el("rect", {x:lx, y:legendY-9, width:12, height:12, fill:color, rx:2}));
      var tlbl = el("text", {x:lx+16, y:legendY, fill:"#475569", "font-size":"12"});
      tlbl.textContent = item.label;
      svg.appendChild(tlbl);
      lx += item.label.length * 8 + 32;
    });

    // 仅选罐号模式：标注各物料连续出现的天数段
    if (items.length > 0 && items[0].key && items[0].key.indexOf("tank:") === 0 && items[0].key.indexOf("|mat:") > 0) {
      drawSegments(svg, dates, series, items, x, y, M, ih);
    }

    setupHover(svg, $(tooltipId), items);
  }

  function drawSegments(svg, dates, series, items, xf, yf, M, ih) {
    var NS = "http://www.w3.org/2000/svg";
    function el(tag, attrs) {
      var e = document.createElementNS(NS, tag);
      for (var k in attrs) e.setAttribute(k, attrs[k]);
      return e;
    }
    items.forEach(function(item, idx) {
      var color = COLORS[idx % COLORS.length];
      var data = series[item.key] || {};
      // Find dates with data
      var dataDates = [];
      for (var i = 0; i < dates.length; i++) {
        if (data[dates[i]] != null && !isNaN(data[dates[i]]) && data[dates[i]] !== 0) dataDates.push(i);
      }
      if (dataDates.length === 0) return;
      // Find continuous segments (consecutive dates with data)
      var segments = [];
      var segStart = dataDates[0];
      var segEnd = dataDates[0];
      for (var j = 1; j < dataDates.length; j++) {
        if (dataDates[j] === segEnd + 1) {
          segEnd = dataDates[j];
        } else {
          segments.push([segStart, segEnd]);
          segStart = dataDates[j];
          segEnd = dataDates[j];
        }
      }
      segments.push([segStart, segEnd]);
      // Draw each segment annotation above the curve
      segments.forEach(function(seg, sIdx) {
        var startIdx = seg[0], endIdx = seg[1];
        var segLen = endIdx - startIdx + 1;
        var midIdx = Math.floor((startIdx + endIdx) / 2);
        var mx = xf(midIdx);
        // Find the max y value in this segment to place label above
        var minY = Infinity;
        for (var k = startIdx; k <= endIdx; k++) {
          var v = data[dates[k]];
          if (v != null && !isNaN(v) && v !== 0) {
            var py = yf(v);
            if (py < minY) minY = py;
          }
        }
        if (minY === Infinity) minY = M.t;
        var labelY = Math.max(M.t + 8, minY - 22 - idx * 16);
        // Draw bracket line above segment
        var x1 = xf(startIdx), x2 = xf(endIdx);
        svg.appendChild(el("line", {
          x1: x1, y1: labelY, x2: x2, y2: labelY,
          stroke: color, "stroke-width": 1.5, "stroke-dasharray": "4,2", opacity: 0.7
        }));
        svg.appendChild(el("line", {x1: x1, y1: labelY, x2: x1, y2: labelY + 4, stroke: color, "stroke-width": 1.5, opacity: 0.7}));
        svg.appendChild(el("line", {x2: x2, y2: labelY, x1: x2, y1: labelY + 4, stroke: color, "stroke-width": 1.5, opacity: 0.7}));
        // Label text
        var lbl = el("text", {
          x: mx, y: labelY - 3,
          "text-anchor": "middle", fill: color, "font-size": "10", "font-weight": "600"
        });
        lbl.textContent = item.label.split(" / ").pop() + " " + segLen + "天";
        svg.appendChild(lbl);
      });
    });
  }

  function setupHover(svg, tooltip, items) {
    var pts = svg.querySelectorAll(".tp-pt");
    pts.forEach(function(pt){
      pt.addEventListener("mouseenter", function(e){
        var item = pt.getAttribute("data-item");
        var date = pt.getAttribute("data-date");
        var val = parseFloat(pt.getAttribute("data-val"));
        pt.setAttribute("r", 5);
        tooltip.classList.remove("hidden");
        tooltip.innerHTML = "<b>" + esc(item) + "</b><br>" + date + "<br>值: " + fmt(val);
        moveTooltip(e, svg, tooltip);
      });
      pt.addEventListener("mouseleave", function(){ pt.setAttribute("r", 3); tooltip.classList.add("hidden"); });
      pt.addEventListener("mousemove", function(e){ moveTooltip(e, svg, tooltip); });
    });
  }

  function moveTooltip(e, svg, tooltip) {
    var rect = svg.getBoundingClientRect();
    tooltip.style.left = (e.clientX - rect.left + 12) + "px";
    tooltip.style.top = (e.clientY - rect.top - 10) + "px";
  }

  function clearAll() {
    $("levelChart").innerHTML = "";
    $("volumeChart").innerHTML = "";
    $("statsTable").querySelector("tbody").innerHTML = "";
    var mts = $("matTotalSection");
    if (mts) mts.style.display = "none";
  }

  function showErrors(errors) {
    var box = $("tankErrors");
    var list = $("tankErrorList");
    list.innerHTML = "";
    (errors || []).forEach(function(e){ var li = document.createElement("li"); li.textContent = e; list.appendChild(li); });
    box.classList.remove("hidden");
  }

  function drawMatTotalChart(dates, series, items) {
    var section = $("matTotalSection");
    if (!selectedMats.length || selectedTanks.length) {
      if (section) section.style.display = "none";
      return;
    }
    if (section) section.style.display = "";
    var svg = $("matTotalChart");
    var wrap = $("matTotalChartWrap");
    var W = Math.max(wrap.clientWidth, 600);
    var H = 380;
    var M = {l:64, r:64, t:30, b:56};
    var iw = W - M.l - M.r, ih = H - M.t - M.b;
    svg.setAttribute("viewBox", "0 0 " + W + " " + H);
    svg.innerHTML = "";
    var n = dates.length;
    var NS = "http://www.w3.org/2000/svg";
    function el(tag, attrs) {
      var e = document.createElementNS(NS, tag);
      for (var k in attrs) e.setAttribute(k, attrs[k]);
      return e;
    }
    if (n === 0) { svg.innerHTML = "<text x=\""+W/2+"\" y=\""+H/2+"\" text-anchor=\"middle\" fill=\"#999\" font-size=\"13\">无数据</text>"; return; }
    var matNames = selectedMats.slice();
    var matDailyTotals = {};
    matNames.forEach(function(mn) {
      matDailyTotals[mn] = {};
      for (var key in series) {
        if (key.indexOf("mat:" + mn + "|") === 0 || key === "mat:" + mn) {
          for (var d in series[key]) {
            matDailyTotals[mn][d] = (matDailyTotals[mn][d] || 0) + series[key][d];
          }
        }
      }
    });
    var dailyTotals = [];
    var dailyHasData = [];
    var allVals = [];
    dates.forEach(function(d) {
      var sum = 0;
      var hasData = false;
      matNames.forEach(function(mn) {
        if (matDailyTotals[mn][d] != null && matDailyTotals[mn][d] !== 0) { sum += matDailyTotals[mn][d]; hasData = true; }
      });
      dailyTotals.push(sum);
      dailyHasData.push(hasData);
      if (hasData) allVals.push(sum);
    });
    // 累计均值：仅在有数据的日期计算，无数据日期不画均线
    var cumAvg = [];
    var cumSum = 0, cumCnt = 0;
    dailyTotals.forEach(function(v, i) {
      if (dailyHasData[i]) {
        cumSum += v; cumCnt++;
        cumAvg.push(cumCnt > 0 ? cumSum / cumCnt : 0);
      } else {
        cumAvg.push(null);
      }
    });
    var yMax = allVals.length ? Math.max.apply(null, allVals) : 100;
    if (yMax <= 0) yMax = 100;
    yMax = yMax * 1.15;
    var yMin = 0;
    function xv(i) { return M.l + (n === 1 ? iw / 2 : i / (n - 1) * iw); }
    function yv(v) { return M.t + (1 - (v - yMin) / (yMax - yMin)) * ih; }
    var ticks = [];
    for (var ti = 0; ti <= 6; ti++) { ticks.push(yMin + (yMax - yMin) * ti / 6); }
    ticks.forEach(function(t) {
      svg.appendChild(el("line", {x1:M.l, y1:yv(t), x2:W-M.r, y2:yv(t), stroke:"#e8eaed", "stroke-width":1}));
      var lbl = el("text", {x:M.l-8, y:yv(t)+4, "text-anchor":"end", "font-size":11, fill:"#666"});
      lbl.textContent = fmt(t);
      svg.appendChild(lbl);
    });
    var barW = Math.max(2, iw / n * 0.6);
    dailyTotals.forEach(function(v, i) {
      var bx = xv(i) - barW / 2;
      var by = yv(v);
      var bh = M.t + ih - by;
      svg.appendChild(el("rect", {x:bx, y:by, width:barW, height:Math.max(0, bh), fill:"#93c5fd", opacity:0.75, rx:1}));
    });
    var dPath = "";
    var needMove = true;
    cumAvg.forEach(function(v, i) {
      if (v == null) { needMove = true; return; }
      dPath += (needMove ? "M" : "L") + xv(i).toFixed(1) + " " + yv(v).toFixed(1) + " ";
      needMove = false;
    });
    if (dPath) svg.appendChild(el("path", {d:dPath.trim(), fill:"none", stroke:"#dc2626", "stroke-width":2, "stroke-linejoin":"round"}));
    var step = Math.max(1, Math.ceil(n / 12));
    for (var i = 0; i < n; i += step) {
      var lbl = el("text", {x:xv(i), y:H-M.b+18, "text-anchor":"middle", "font-size":10, fill:"#666"});
      lbl.textContent = dates[i].slice(5);
      svg.appendChild(el("line", {x1:xv(i), y1:H-M.b, x2:xv(i), y2:H-M.b+4, stroke:"#ccc"}));
      svg.appendChild(lbl);
    }
    svg.appendChild(el("line", {x1:M.l, y1:M.t, x2:M.l, y2:H-M.b, stroke:"#999"}));
    svg.appendChild(el("line", {x1:M.l, y1:H-M.b, x2:W-M.r, y2:H-M.b, stroke:"#999"}));
    svg.appendChild(el("rect", {x:M.l+8, y:M.t+4, width:14, height:10, fill:"#93c5fd", opacity:0.75, rx:1}));
    var lg1 = el("text", {x:M.l+26, y:M.t+13, "font-size":11, fill:"#444"});
    lg1.textContent = "日总量";
    svg.appendChild(lg1);
    svg.appendChild(el("line", {x1:M.l+80, y1:M.t+9, x2:M.l+96, y2:M.t+9, stroke:"#dc2626", "stroke-width":2}));
    var lg2 = el("text", {x:M.l+100, y:M.t+13, "font-size":11, fill:"#444"});
    lg2.textContent = "累计均值";
    svg.appendChild(lg2);
    var yt = el("text", {x:16, y:M.t+ih/2, "text-anchor":"middle", "font-size":12, fill:"#444", transform:"rotate(-90 16 "+(M.t+ih/2)+")"});
    yt.textContent = "罐量";
    svg.appendChild(yt);
    var tooltip = $("matTotalTooltip");
    svg.onmousemove = function(evt) {
      var rect = svg.getBoundingClientRect();
      var mx = (evt.clientX - rect.left) * (W / rect.width);
      var i = Math.round((mx - M.l) / (W - M.l - M.r) * (n - 1));
      if (i < 0) i = 0; if (i > n-1) i = n-1;
      tooltip.classList.remove("hidden");
      var html = "<div><b>" + dates[i] + "</b></div>";
      html += "<div>日总量: <b>" + fmt(dailyTotals[i]) + "</b></div>";
      html += "<div>累计均值: <b>" + (cumAvg[i] != null ? fmt(cumAvg[i]) : "—") + "</b></div>";
      matNames.forEach(function(mn) {
        var mv = matDailyTotals[mn][dates[i]];
        if (mv != null && mv !== 0) html += "<div style='color:#666'>" + esc(mn) + ": " + fmt(mv) + "</div>";
      });
      tooltip.innerHTML = html;
      var px = (xv(i) / W) * rect.width;
      tooltip.style.left = Math.min(rect.width - 200, px + 12) + "px";
      tooltip.style.top = "10px";
    };
    svg.onmouseleave = function() { tooltip.classList.add("hidden"); };
  }

  function setupControls() {
    $("loadOverviewBtn").addEventListener("click", loadOverview);

    // 时间点单选
    document.querySelectorAll(".tp-radio").forEach(function(btn){
      btn.addEventListener("click", function(){
        document.querySelectorAll(".tp-radio").forEach(function(b){ b.classList.remove("active"); });
        btn.classList.add("active");
        currentTimePoint = btn.getAttribute("data-tp");
        queryTrend();
      });
    });

    // 罐号下拉
    $("tankDropdownBtn").addEventListener("click", function(e){ e.stopPropagation(); $("tankDropdownPanel").classList.toggle("hidden"); updateTankDropdown(); });
    $("tankSearch").addEventListener("input", updateTankDropdown);
    // 物料下拉
    $("matDropdownBtn").addEventListener("click", function(e){ e.stopPropagation(); $("matDropdownPanel").classList.toggle("hidden"); updateMatDropdown(); });
    $("matSearch").addEventListener("input", updateMatDropdown);
    document.addEventListener("click", function(e){
      if (!e.target.closest("#tankDropdownBtn") && !e.target.closest("#tankDropdownPanel")) $("tankDropdownPanel").classList.add("hidden");
      if (!e.target.closest("#matDropdownBtn") && !e.target.closest("#matDropdownPanel")) $("matDropdownPanel").classList.add("hidden");
    });

    $("tankApplyBtn").addEventListener("click", queryTrend);
    $("tankResetBtn").addEventListener("click", function(){
      if (overview && overview.dates && overview.dates.length > 0) {
        $("tankRangeStart").value = overview.dates[0];
        $("tankRangeEnd").value = overview.dates[overview.dates.length - 1];
      }
      selectedTanks = []; selectedMats = [];
      updateTankDropdown(); updateMatDropdown(); updateDropdownBtns();
      queryTrend();
    });
  }

  loadSources();
  setupControls();
})();
