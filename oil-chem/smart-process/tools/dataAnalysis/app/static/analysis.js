(function () {
  "use strict";
  // 合格范围（固定 98-102%）：用于合格率统计、绿色区间带、点的颜色
  var YIELD_MIN = 98, YIELD_MAX = 102;
  var currentData = null;
  var lastSourceId = "";
  // 显示过滤范围（用户设置）：默认 null = 不过滤，显示全部数据
  var filterMin = null, filterMax = null;

  // ---- 显示过滤持久化 ----
  function loadSavedFilter() {
    try {
      var s = localStorage.getItem("yieldFilter");
      if (s) {
        var r = JSON.parse(s);
        if (r.min !== undefined) filterMin = (typeof r.min === "number") ? r.min : null;
        if (r.max !== undefined) filterMax = (typeof r.max === "number") ? r.max : null;
      }
    } catch (e) {}
  }
  function saveFilter(lo, hi) {
    try { localStorage.setItem("yieldFilter", JSON.stringify({ min: lo, max: hi })); } catch (e) {}
  }
  function clearSavedFilter() {
    try { localStorage.removeItem("yieldFilter"); } catch (e) {}
  }

  // 合格范围始终是 98-102%
  function qualifiedRange() { return { lo: YIELD_MIN, hi: YIELD_MAX }; }

  // 按显示过滤范围过滤点（不影响合格判定）
  function filterPoints(points) {
    var out = [];
    var removed = 0;
    var sDate = $("ioFlowStart") ? $("ioFlowStart").value : "";
    var eDate = $("ioFlowEnd") ? $("ioFlowEnd").value : "";
    var excSet = {};
    ioExcludedDates.forEach(function(d) { excSet[d] = true; });
    for (var i = 0; i < points.length; i++) {
      var yv = points[i].yield;
      var dp = (points[i].date || "").slice(0, 10);
      var keep = true;
      if (sDate && dp < sDate) keep = false;
      if (eDate && dp > eDate) keep = false;
      if (excSet[dp]) keep = false;
      if (filterMin != null && yv < filterMin) keep = false;
      if (filterMax != null && yv > filterMax) keep = false;
      if (keep) { out.push(points[i]); } else { removed++; }
    }
    return { points: out, removed: removed };
  }

  // 获取因收率超出显示范围而被排除的日期
  function getYieldExcludedDates() {
    if (!currentData || !currentData.points) return [];
    var excluded = [];
    currentData.points.forEach(function(p) {
      var yv = p.yield;
      if ((filterMin != null && yv < filterMin) || (filterMax != null && yv > filterMax)) {
        excluded.push((p.date || "").slice(0, 10));
      }
    });
    return excluded;
  }

  function $(id) { return document.getElementById(id); }

  function currentNegFilter() { return $("negFilterMode") ? $("negFilterMode").value : "filter"; }



  function fmt(n, d) { d = d == null ? 2 : d; if (n == null || isNaN(n)) return "-"; return Number(n).toFixed(d); }

  // ---- 数据源列表 ----
  function loadSources() {
    fetch("/api/analysis/sources").then(function (r) { return r.json(); }).then(function (res) {
      var sel = $("analysisSource");
      sel.innerHTML = "";
      (res.sources || []).forEach(function (s) {
        var opt = document.createElement("option");
        opt.value = s.source_id;
        opt.textContent = s.name + (s.has_data ? "  (有数据)" : "");
        sel.appendChild(opt);
      });
      loadMeta();
    });
  }

  function currentSourceId() { return $("analysisSource").value; }

  // 统一入口：获取最新数据 + 应用所有筛选条件 + 刷新全部图表
  function runAnalysis() {
    var sid = currentSourceId();
    var unit = $("ioFlowSelect") ? $("ioFlowSelect").value : "total";
    var unitParam = (unit && unit !== "total") ? "&unit=" + encodeURIComponent(unit) : "";
    // 检测数据源是否变更：变更时重置日期范围为全集
    var sourceChanged = (lastSourceId && lastSourceId !== sid);
    lastSourceId = sid;
    // 读取显示范围输入框的值
    var loStr = $("rangeMin") ? $("rangeMin").value.trim() : "";
    var hiStr = $("rangeMax") ? $("rangeMax").value.trim() : "";
    filterMin = loStr === "" ? null : parseFloat(loStr);
    filterMax = hiStr === "" ? null : parseFloat(hiStr);
    if (filterMin != null && isNaN(filterMin)) filterMin = null;
    if (filterMax != null && isNaN(filterMax)) filterMax = null;
    saveFilter(filterMin, filterMax);
    var savedStart = "", savedEnd = "", savedExc = null;
    if (!sourceChanged) {
      savedStart = $("ioFlowStart") ? $("ioFlowStart").value : "";
      savedEnd = $("ioFlowEnd") ? $("ioFlowEnd").value : "";
      savedExc = ioExcludedDates.slice();
    }
    fetch("/api/analysis/yield?source_id=" + encodeURIComponent(sid) + "&neg_filter=" + currentNegFilter() + unitParam)
      .then(function (r) { return r.json(); })
      .then(function (res) {
        var statusEl = $("analysisStatus");
        if (!res.has_data) {
          statusEl.textContent = "该数据源尚未导入数据，请先在导入页面导入数据";
          statusEl.classList.remove("hidden");
          $("materialSection").classList.add("hidden");
          hideResults();
          showErrors(res.errors && res.errors.length ? res.errors : null);
          return;
        }
        statusEl.classList.add("hidden");
        showErrors(null);
        currentData = res;
        syncRangeInputs();
        // 显示统计和图表区域
        $("statsSection").classList.remove("hidden");
        $("chartSection").classList.remove("hidden");
        // 显示调整说明
        var adj = $("analysisAdjust");
        var adjList = $("analysisAdjustList");
        if (res.adjustments && res.adjustments.length) {
          adj.classList.remove("hidden");
          adjList.innerHTML = res.adjustments.map(function (a) { return "<li>" + a + "</li>"; }).join("");
        } else { adj.classList.add("hidden"); }
        // 加载进出物料数据（不重置筛选条件），内部会重绘收率图表
        loadIoFlowSilent(sid, savedStart, savedEnd, savedExc);
        loadMaterialTrend(sid);
      });
  }

  function loadMeta() {
    // 首次加载：重置所有筛选条件
    runAnalysis();
  }

  function loadStored() { loadMeta(); }

  function hideResults() {
    $("statsSection").classList.add("hidden");
    $("chartSection").classList.add("hidden");
    $("ioFlowSection").classList.add("hidden");
    $("analysisAdjust").classList.add("hidden");
  }

  function syncRangeInputs() {
    $("rangeMin").value = filterMin != null ? filterMin : "";
    $("rangeMax").value = filterMax != null ? filterMax : "";
  }
  function updateRangeStatus(removed, total) {
    var el2 = $("rangeStatus");
    var hasFilter = (filterMin != null || filterMax != null);
    var lo = filterMin != null ? filterMin + "%" : "不限";
    var hi = filterMax != null ? filterMax + "%" : "不限";
    var txt = "合格范围 98%–102%（固定）";
    if (hasFilter) {
      txt += "，显示范围 " + lo + "–" + hi;
      if (removed > 0) txt += "，已过滤 " + removed + "/" + total + " 个异常值";
    } else {
      txt += "，显示全部数据";
    }
    el2.textContent = txt;
  }
  function applyAllFilters() {
    var loStr = $("rangeMin").value.trim();
    var hiStr = $("rangeMax").value.trim();
    var lo = loStr === "" ? null : parseFloat(loStr);
    var hi = hiStr === "" ? null : parseFloat(hiStr);
    if (lo != null && isNaN(lo)) { alert("请输入有效的最小值"); return; }
    if (hi != null && isNaN(hi)) { alert("请输入有效的最大值"); return; }
    if (lo != null && hi != null && lo >= hi) { alert("最小值必须小于最大值"); return; }
    filterMin = lo; filterMax = hi;
    saveFilter(lo, hi);
    // 重绘总收率图表
    if (currentData) {
      var allPoints = currentData.points || [];
      var filtered = filterPoints(allPoints);
      var st = recomputeStats(filtered.points);
      $("statCount").textContent = st.count || 0;
      $("statRange").textContent = (st.date_min || "-") + " ~ " + (st.date_max || "-");
      $("statMean").textContent = fmt(st.mean) + "%";
      $("statMin").textContent = fmt(st.min) + "%";
      $("statMax").textContent = fmt(st.max) + "%";
      var _qualified = st.in_range_count;
      var _displayed = filtered.points.length;
      var _rate = _displayed ? round2(_qualified / _displayed * 100) : null;
      $("statRate").textContent = fmt(_rate) + "% (" + _qualified + "/" + _displayed + ")";
      updateRangeStatus(filtered.removed, allPoints.length);
      drawChart(filtered.points);
    }
    // 重绘进出物料图表
    if (ioFlowData) drawIoFlowChart();
    // 重新加载占比/收率
    loadDistribution();
    // 更新状态
    var s = $("ioFlowStart").value, e = $("ioFlowEnd").value;
    var parts = [];
    if (s && e) parts.push(s + " 至 " + e);
    else parts.push("全部时间");
    if (ioExcludedDates.length) parts.push("剔除 " + ioExcludedDates.length + " 天");
    if (filterMin != null || filterMax != null) {
      var lo2 = filterMin != null ? filterMin + "%" : "不限";
      var hi2 = filterMax != null ? filterMax + "%" : "不限";
      parts.push("显示范围 " + lo2 + "-" + hi2);
    }
    $("ioFlowStatus").textContent = "显示范围: " + parts.join("，");
  }
  function resetAllFilters() {
    filterMin = null; filterMax = null;
    clearSavedFilter();
    syncRangeInputs();
    if (ioFlowData && ioFlowData.dates && ioFlowData.dates.length) {
      var dMin = ioFlowData.dates[0].slice(0, 10), dMax = ioFlowData.dates[ioFlowData.dates.length - 1].slice(0, 10);
      $("ioFlowStart").value = dMin; $("ioFlowEnd").value = dMax;
    }
    ioExcludedDates = [];
    renderIoExcludedTags();
    $("ioFlowStatus").textContent = "";
    if (currentData) {
      var allPoints = currentData.points || [];
      var filtered = filterPoints(allPoints);
      var st = recomputeStats(filtered.points);
      $("statCount").textContent = st.count || 0;
      $("statRange").textContent = (st.date_min || "-") + " ~ " + (st.date_max || "-");
      $("statMean").textContent = fmt(st.mean) + "%";
      $("statMin").textContent = fmt(st.min) + "%";
      $("statMax").textContent = fmt(st.max) + "%";
      var _qualified = st.in_range_count;
      var _displayed = filtered.points.length;
      var _rate = _displayed ? round2(_qualified / _displayed * 100) : null;
      $("statRate").textContent = fmt(_rate) + "% (" + _qualified + "/" + _displayed + ")";
      updateRangeStatus(filtered.removed, allPoints.length);
      drawChart(filtered.points);
    }
    if (ioFlowData) drawIoFlowChart();
    loadDistribution();
  }

  // ---- 控件绑定 ----
  function setupControls() {
    $("genAnalysisBtn").addEventListener("click", runAnalysis);
    $("resetFiltersBtn").addEventListener("click", resetAllFilters);
    $("rangeMin").addEventListener("keydown", function (e) { if (e.key === "Enter") runAnalysis(); });
    $("rangeMax").addEventListener("keydown", function (e) { if (e.key === "Enter") runAnalysis(); });
  }

  function showErrors(errs) {
    var box = $("analysisErrors");
    var list = $("analysisErrorList");
    if (!errs || !errs.length) { box.classList.add("hidden"); list.innerHTML = ""; return; }
    box.classList.remove("hidden");
    list.innerHTML = errs.map(function (e) { return "<li>" + e + "</li>"; }).join("");
  }

  function recomputeStats(points) {
    if (!points.length) {
      return { count: 0, date_min: null, date_max: null, mean: null,
               min: null, max: null, in_range_count: 0, above_count: 0,
               below_count: 0, in_range_rate: null };
    }
    var r = qualifiedRange();
    var yields = points.map(function (p) { return p.yield; });
    var inRange = points.filter(function (p) { return p.yield >= r.lo && p.yield <= r.hi; });
    return {
      count: points.length,
      date_min: points[0].date,
      date_max: points[points.length - 1].date,
      mean: round2(sum(yields) / yields.length),
      min: round2(Math.min.apply(null, yields)),
      max: round2(Math.max.apply(null, yields)),
      in_range_count: inRange.length,
      above_count: yields.filter(function (y) { return y > r.hi; }).length,
      below_count: yields.filter(function (y) { return y < r.lo; }).length,
      in_range_rate: round2(inRange.length / points.length * 100),
    };
  }
  function sum(arr) { var s = 0; for (var i = 0; i < arr.length; i++) s += arr[i]; return s; }
  function round2(n) { return Math.round(n * 100) / 100; }

  function renderResult(res) {
    currentData = res;
    syncRangeInputs();
    // 调整说明
    var adj = $("analysisAdjust");
    var adjList = $("analysisAdjustList");
    if (res.adjustments && res.adjustments.length) {
      adj.classList.remove("hidden");
      adjList.innerHTML = res.adjustments.map(function (a) { return "<li>" + a + "</li>"; }).join("");
    } else { adj.classList.add("hidden"); }

    // 统计基于过滤后的数据（已排除异常值）
    var allPoints = res.points || [];
    var filtered = filterPoints(allPoints);
    var st = recomputeStats(filtered.points);
    var _displayed = filtered.points.length;
    $("statCount").textContent = st.count || 0;
    $("statRange").textContent = (st.date_min || "-") + " ~ " + (st.date_max || "-");
    $("statMean").textContent = fmt(st.mean) + "%";
    $("statMin").textContent = fmt(st.min) + "%";
    $("statMax").textContent = fmt(st.max) + "%";
    // 合格率基于合格范围(98-102%)，在过滤后的数据中计算
    var _qualified = st.in_range_count;
    var _rate = _displayed ? round2(_qualified / _displayed * 100) : null;
    $("statRate").textContent = fmt(_rate) + "% (" + _qualified + "/" + _displayed + ")";
    $("statsSection").classList.remove("hidden");
    $("chartSection").classList.remove("hidden");

    updateRangeStatus(filtered.removed, allPoints.length);
    drawChart(filtered.points);
  }

  // ---- 物料占比饼图 ----
  var _pieCache = {};  // cache: svgId -> grouped main array
  var DIST_COLORS = ["#2563eb","#dc2626","#16a34a","#d97706","#9333ea","#0891b2","#db2777","#65a30d","#ea580c","#7c3aed","#0d9488","#be185d","#4f46e5","#0369a1","#c2410c","#ca8a04","#64748b","#78716c"];

  function loadDistribution() {
    var sid = currentSourceId();
    var unit = $("ioFlowSelect") ? $("ioFlowSelect").value : "total";
    var unitParam = (unit && unit !== "total") ? "&unit=" + encodeURIComponent(unit) : "";
    var allExc = ioExcludedDates.concat(getYieldExcludedDates());
    fetch("/api/analysis/distribution?source_id=" + encodeURIComponent(sid) + "&neg_filter=" + currentNegFilter() +
        "&start_date=" + ($("ioFlowStart").value || "") + "&end_date=" + ($("ioFlowEnd").value || "") +
        "&exclude_dates=" + encodeURIComponent(allExc.join(",")) + unitParam)
      .then(function(r) { return r.json(); })
      .then(function(res) {
        var sec = $("distSection");
        if (!res.has_data || (!res.in_materials.length && !res.out_materials.length)) {
          sec.classList.add("hidden");
          return;
        }
        sec.classList.remove("hidden");
        drawPie("distInChart", res.in_materials || []);
        drawPie("distOutChart", res.out_materials || []);
        renderDistLegend("distInLegend", res.in_materials || [], "distInChart");
        renderDistLegend("distOutLegend", res.out_materials || [], "distOutChart");
        // 出方向物料收率 = 各出物料 / 进方向总物料
        var inTotal = (res.in_materials || []).reduce(function(s, m) { return s + (m.value || 0); }, 0);
        var yieldMats = (res.out_materials || []).map(function(m) {
          return { name: m.name, value: m.value, percent: inTotal > 0 ? round2(m.value / inTotal * 100) : 0 };
        });
        drawPie("distYieldChart", yieldMats);
        renderDistLegend("distYieldLegend", yieldMats, "distYieldChart");
      })
      .catch(function() { $("distSection").classList.add("hidden"); });
  }

  function drawPie(svgId, materials) {
    var svg = $(svgId);
    var W = 300, H = 300, cx = W/2, cy = H/2, r = 110;
    svg.setAttribute("viewBox", "0 0 " + W + " " + H);
    svg.innerHTML = "";
    var NS = "http://www.w3.org/2000/svg";
    function el(tag, attrs) {
      var e = document.createElementNS(NS, tag);
      for (var k in attrs) e.setAttribute(k, attrs[k]);
      return e;
    }
    if (!materials.length) {
      var t = el("text", {x:cx, y:cy, "text-anchor":"middle", fill:"#999", "font-size":"14"});
      t.textContent = "无数据";
      svg.appendChild(t);
      return;
    }
    // Combine small slices (<2%) into "其他"
    var main = [], otherVal = 0;
    materials.forEach(function(m) {
      if (m.percent < 2) otherVal += m.value;
      else main.push(m);
    });
    if (otherVal > 0) {
      var total = materials.reduce(function(s,m){ return s+m.value; }, 0);
      main.push({name:"其他", value:otherVal, percent: total>0 ? round2(otherVal/total*100) : 0});
    }
    // Sort by percent descending (including "其他")
    main.sort(function(a, b) { return b.percent - a.percent; });
    var total = main.reduce(function(s,m){ return s+m.value; }, 0);
    if (total <= 0) return;

    // Store grouping result for legend sync
    _pieCache[svgId] = main;

    var angle = -Math.PI / 2;
    main.forEach(function(m, idx) {
      var frac = m.value / total;
      var a2 = angle + frac * Math.PI * 2;
      var x1 = cx + r * Math.cos(angle), y1 = cy + r * Math.sin(angle);
      var x2 = cx + r * Math.cos(a2), y2 = cy + r * Math.sin(a2);
      var large = frac > 0.5 ? 1 : 0;
      var d = "M" + cx + " " + cy + " L" + x1 + " " + y1 + " A" + r + " " + r + " 0 " + large + " 1 " + x2 + " " + y2 + " Z";
      var color = DIST_COLORS[idx % DIST_COLORS.length];
      var path = el("path", {d:d, fill:color, stroke:"#fff", "stroke-width":1.5});
      svg.appendChild(path);
      // Label on slice if > 5%
      if (m.percent > 5) {
        var midAngle = (angle + a2) / 2;
        var lx = cx + (r * 0.65) * Math.cos(midAngle);
        var ly = cy + (r * 0.65) * Math.sin(midAngle);
        var lbl = el("text", {x:lx, y:ly, "text-anchor":"middle", "dominant-baseline":"central", fill:"#fff", "font-size":"11", "font-weight":"600"});
        lbl.textContent = m.percent + "%";
        svg.appendChild(lbl);
      }
      angle = a2;
    });
  }

  function renderDistLegend(divId, materials, svgId) {
    var div = $(divId);
    div.innerHTML = "";
    // 使用与饼图相同的分组逻辑
    var main = (svgId && _pieCache[svgId]) ? _pieCache[svgId] : null;
    if (!main) {
      // fallback: 自行分组
      main = [];
      var otherVal = 0;
      materials.forEach(function(m) {
        if (m.percent < 2) otherVal += m.value;
        else main.push(m);
      });
      if (otherVal > 0) {
        var total = materials.reduce(function(s,m){ return s+m.value; }, 0);
        main.push({name:"其他", value:otherVal, percent: total>0 ? round2(otherVal/total*100) : 0});
      }
    }
    main.forEach(function(m, idx) {
      var color = DIST_COLORS[idx % DIST_COLORS.length];
      var item = document.createElement("div");
      item.className = "dist-legend-item";
      item.innerHTML = '<span class="dist-legend-swatch" style="background:' + color + '"></span>' +
        '<span class="dist-legend-name" title="' + escapeHtml(m.name) + '">' + escapeHtml(m.name) + '</span>' +
        '<span class="dist-legend-pct">' + m.percent + '%</span>';
      div.appendChild(item);
    });
  }

  // ---- SVG 趋势图 ----
  function drawChart(points) {
    var svg = $("yieldChart");
    var wrap = $("chartWrap");
    var W = Math.max(wrap.clientWidth, 600);
    var H = 440;
    var M = { l: 56, r: 24, t: 20, b: 40 };
    var iw = W - M.l - M.r, ih = H - M.t - M.b;
    svg.setAttribute("viewBox", "0 0 " + W + " " + H);
    svg.innerHTML = "";
    var n = points.length;
    if (n === 0) {
      var t = document.createElementNS("http://www.w3.org/2000/svg", "text");
      t.setAttribute("x", W / 2); t.setAttribute("y", H / 2);
      t.setAttribute("text-anchor", "middle"); t.setAttribute("fill", "#999");
      t.textContent = "无数据";
      svg.appendChild(t);
      return;
    }
    var yields = points.map(function (p) { return p.yield; });
    var yMin = Math.min.apply(null, yields);
    var yMax = Math.max.apply(null, yields);
    var qr = qualifiedRange();
    // Y 轴范围至少包含合格区间
    var yLo = Math.min(yMin, qr.lo) - 2;
    var yHi = Math.max(yMax, qr.hi) + 2;
    if (yHi - yLo < 10) { var mid = (yLo + yHi) / 2; yLo = mid - 5; yHi = mid + 5; }

    function x(i) { return M.l + (n === 1 ? iw / 2 : i / (n - 1) * iw); }
    function y(v) { return M.t + (1 - (v - yLo) / (yHi - yLo)) * ih; }

    var NS = "http://www.w3.org/2000/svg";
    function el(tag, attrs) {
      var e = document.createElementNS(NS, tag);
      for (var k in attrs) e.setAttribute(k, attrs[k]);
      return e;
    }

    // Y 轴网格 + 刻度
    var ticks = niceTicks(yLo, yHi, 6);
    ticks.forEach(function (t) {
      svg.appendChild(el("line", { x1: M.l, y1: y(t), x2: W - M.r, y2: y(t),
        stroke: "#e8eaed", "stroke-width": 1 }));
      var lbl = el("text", { x: M.l - 8, y: y(t) + 4, "text-anchor": "end", "font-size": 11, fill: "#666" });
      lbl.textContent = t.toFixed(1) + "%";
      svg.appendChild(lbl);
    });

    // 合理区间带（固定 98-102%）
    svg.appendChild(el("rect", {
      x: M.l, y: y(qr.hi), width: iw, height: Math.max(2, y(qr.lo) - y(qr.hi)),
      fill: "#d4edda", "fill-opacity": 0.55, stroke: "#7bc47f", "stroke-width": 1, "stroke-dasharray": "4 3"
    }));
    var bandLbl = el("text", { x: W - M.r, y: y(qr.hi) - 6, "text-anchor": "end", "font-size": 11, fill: "#3c8c46" });
    bandLbl.textContent = "合格区间 " + qr.lo + "%–" + qr.hi + "%";
    svg.appendChild(bandLbl);

    // 100% 参考线
    if (yLo < 100 && yHi > 100) {
      svg.appendChild(el("line", { x1: M.l, y1: y(100), x2: W - M.r, y2: y(100),
        stroke: "#bbb", "stroke-width": 1, "stroke-dasharray": "2 4" }));
    }

    // 折线
    var d = points.map(function (p, i) { return (i ? "L" : "M") + x(i).toFixed(1) + " " + y(p.yield).toFixed(1); }).join(" ");
    svg.appendChild(el("path", { d: d, fill: "none", stroke: "#2b6cb0", "stroke-width": 1.6, "stroke-linejoin": "round" }));

    // 数据点（颜色由合格范围 98-102% 决定，不受显示过滤影响）
    points.forEach(function (p, i) {
      var ok = p.yield >= qr.lo && p.yield <= qr.hi;
      var c = ok ? "#2e8b57" : "#e0322d";
      svg.appendChild(el("circle", { cx: x(i), cy: y(p.yield), r: 3, fill: c, stroke: "#fff", "stroke-width": 1 }));
    });

    // X 轴日期标签（间隔显示）
    var step = Math.max(1, Math.ceil(n / 10));
    for (var i = 0; i < n; i += step) {
      var lbl = el("text", { x: x(i), y: H - M.b + 18, "text-anchor": "middle", "font-size": 10, fill: "#666" });
      lbl.textContent = points[i].date.slice(5);
      svg.appendChild(el("line", { x1: x(i), y1: H - M.b, x2: x(i), y2: H - M.b + 4, stroke: "#ccc" }));
      svg.appendChild(lbl);
    }
    // 轴线
    svg.appendChild(el("line", { x1: M.l, y1: M.t, x2: M.l, y2: H - M.b, stroke: "#999" }));
    svg.appendChild(el("line", { x1: M.l, y1: H - M.b, x2: W - M.r, y2: H - M.b, stroke: "#999" }));

    // 坐标轴标题
    var yTitle = el("text", { x: 16, y: M.t + ih / 2, "text-anchor": "middle", "font-size": 12, fill: "#444", transform: "rotate(-90 16 " + (M.t + ih / 2) + ")" });
    yTitle.textContent = "总收率 (%)";
    svg.appendChild(yTitle);
    var xTitle = el("text", { x: M.l + iw / 2, y: H - 8, "text-anchor": "middle", "font-size": 12, fill: "#444" });
    xTitle.textContent = "日期";
    svg.appendChild(xTitle);

    // 悬停交互
    setupHover(svg, points, x, y, W, H, M);
  }

  function setupHover(svg, points, x, y, W, H, M) {
    var tooltip = $("chartTooltip");
    function onMove(evt) {
      var rect = svg.getBoundingClientRect();
      var mx = (evt.clientX - rect.left) * (W / rect.width);
      var n = points.length;
      var i = Math.round((mx - M.l) / (W - M.l - M.r) * (n - 1));
      if (i < 0) i = 0; if (i > n - 1) i = n - 1;
      var p = points[i];
      tooltip.classList.remove("hidden");
      var qr = qualifiedRange();
      var ok = p.yield >= qr.lo && p.yield <= qr.hi;
      tooltip.innerHTML =
        "<div><b>" + p.date + "</b></div>" +
        "<div>总收率: <b style='color:" + (ok ? "#2e8b57" : "#e0322d") + "'>" + fmt(p.yield) + "%</b></div>" +
        "<div>进总: " + fmt(p.jin_total, 1) + " t</div>" +
        "<div>出总: " + fmt(p.chu_total, 1) + " t</div>" +
        "<div>" + (ok ? "✓ 合格" : "✗ 超出合格范围") + "</div>";
      var px = (x(i) / W) * rect.width;
      var py = (y(p.yield) / H) * rect.height;
      tooltip.style.left = Math.min(rect.width - 150, px + 12) + "px";
      tooltip.style.top = Math.max(0, py - 10) + "px";
    }
    function onLeave() { tooltip.classList.add("hidden"); }
    svg.onmousemove = onMove;
    svg.onmouseleave = onLeave;
  }

  function niceTicks(lo, hi, count) {
    var range = hi - lo;
    var step = range / count;
    var mag = Math.pow(10, Math.floor(Math.log10(step)));
    var norm = step / mag;
    var nice;
    if (norm < 1.5) nice = 1; else if (norm < 3) nice = 2; else if (norm < 7) nice = 5; else nice = 10;
    var tickStep = nice * mag;
    var start = Math.ceil(lo / tickStep) * tickStep;
    var out = [];
    for (var v = start; v <= hi + 1e-9; v += tickStep) out.push(Math.round(v * 1000) / 1000);
    return out;
  }

  window.addEventListener("resize", function () {
    if (currentData) { var f = filterPoints(currentData.points || []); drawChart(f.points); }
    if (materialData) { drawMaterialChart(); drawStabilityChart(); }
    if (ioFlowData) { drawIoFlowChart(); }
  });
  document.addEventListener("change", function(e) {
    if (e.target && e.target.id === "detrendToggle") { drawStabilityChart(); }
  });
  loadSavedFilter();
  loadSources();
  setupControls();

  // ---- 物料变化趋势 ----
  var materialData = null;
  var selectedMaterials = {};
  var excludedDates = []; // 物料分析剔除日期数组
  var MAT_COLORS = ["#2b6cb0","#e0322d","#2e8b57","#b8860b","#8e44ad","#1abc9c","#e67e22","#3498db","#e84393","#00b894","#6c5ce7","#fd79a8","#a29bfe","#fab1a0","#74b9ff","#a3cb38","#d35400","#7f8c8d","#c0392b","#16a085"];

  function matKey(m) { return m.name + (m.direction ? "·" + m.direction : ""); }

  function getFilteredDates() {
    if (!materialData || !materialData.dates) return [];
    var dates = materialData.dates;
    var s = $("matRangeStart").value;
    var e = $("matRangeEnd").value;
    var excSet = {};
    excludedDates.forEach(function(d) { excSet[d] = true; });
    return dates.filter(function(d) {
      var dp = (d || "").slice(0, 10);
      if (s && dp < s) return false;
      if (e && dp > e) return false;
      if (excSet[dp]) return false;
      return true;
    });
  }

  function renderExcludedTags() {
    var container = $("matExcludeTags");
    if (!container) return;
    container.innerHTML = "";
    excludedDates.forEach(function(d, idx) {
      var tag = document.createElement("span");
      tag.className = "mat-exclude-tag";
      tag.innerHTML = escapeHtml(d) + ' <span class="mat-exclude-x" data-date="' + d + '">×</span>';
      container.appendChild(tag);
    });
    if (excludedDates.length) {
      var btn = $("matExcludeBtn");
      if (btn) btn.textContent = "已剔除 " + excludedDates.length + " 天 ▾";
    } else {
      var btn2 = $("matExcludeBtn");
      if (btn2) btn2.textContent = "点击选择剔除日期 ▾";
    }
  }

  function fillExcludeList() {
    var listDiv = $("matExcludeList");
    if (!listDiv || !materialData) return;
    var dates = (materialData.dates || []).map(function(d) { return (d || "").slice(0, 10); });
    // 去重并排序
    var seen = {};
    dates = dates.filter(function(d) { if (seen[d]) return false; seen[d] = true; return true; }).sort();
    var s = $("matRangeStart").value, e = $("matRangeEnd").value;
    var filtered = dates.filter(function(d) {
      if (s && d < s) return false;
      if (e && d > e) return false;
      return true;
    });
    var kw = ($("matExcludeSearch").value || "").trim();
    if (kw) filtered = filtered.filter(function(d) { return d.indexOf(kw) >= 0; });
    listDiv.innerHTML = "";
    var excSet = {};
    excludedDates.forEach(function(d) { excSet[d] = true; });
    filtered.forEach(function(d) {
      var lbl = document.createElement("label");
      lbl.className = "mat-exclude-item";
      var cb = document.createElement("input");
      cb.type = "checkbox";
      cb.value = d;
      cb.checked = !!excSet[d];
      cb.addEventListener("change", function() {
        if (this.checked) {
          if (excludedDates.indexOf(d) < 0) { excludedDates.push(d); excludedDates.sort(); }
        } else {
          var i = excludedDates.indexOf(d);
          if (i >= 0) excludedDates.splice(i, 1);
        }
        renderExcludedTags();
      });
      lbl.appendChild(cb);
      lbl.appendChild(document.createTextNode(" " + d));
      listDiv.appendChild(lbl);
    });
    if (!filtered.length) {
      listDiv.innerHTML = '<div style="padding:8px;color:#999;font-size:12px">无可选日期</div>';
    }
  }

  function setupExcludeControls() {
    var btn = $("matExcludeBtn");
    var panel = $("matExcludePanel");
    var search = $("matExcludeSearch");
    var clearBtn = $("matExcludeClear");
    if (!btn || !panel) return;
    btn.addEventListener("click", function(e) {
      e.stopPropagation();
      panel.classList.toggle("hidden");
      if (!panel.classList.contains("hidden")) fillExcludeList();
    });
    document.addEventListener("click", function(e) {
      if (!panel.contains(e.target) && e.target !== btn) panel.classList.add("hidden");
    });
    if (search) {
      search.addEventListener("input", fillExcludeList);
      search.addEventListener("click", function(e) { e.stopPropagation(); });
    }
    if (clearBtn) {
      clearBtn.addEventListener("click", function(e) {
        e.stopPropagation();
        excludedDates = [];
        renderExcludedTags();
        fillExcludeList();
      });
    }
    var tagsDiv = $("matExcludeTags");
    if (tagsDiv) {
      tagsDiv.addEventListener("click", function(e) {
        if (e.target.classList.contains("mat-exclude-x")) {
          var d = e.target.getAttribute("data-date");
          var i = excludedDates.indexOf(d);
          if (i >= 0) excludedDates.splice(i, 1);
          renderExcludedTags();
          fillExcludeList();
        }
      });
    }
  }

    function loadMaterialTrend(sourceId) {
    fetch("/api/analysis/material-trend?source_id=" + encodeURIComponent(sourceId) + "&neg_filter=" + currentNegFilter())
      .then(function(r){ return r.json(); })
      .then(function(res) {
        if (!res.has_data) { $("materialSection").classList.add("hidden"); $("diagSection").classList.add("hidden"); materialData = null; return; }
        materialData = res;
        selectedMaterials = {};
        var _d0 = (res.dates || []);
        if (_d0.length) {
          var _dMin = _d0[0].slice(0, 10), _dMax = _d0[_d0.length - 1].slice(0, 10);
          $("matRangeStart").min = _dMin; $("matRangeStart").max = _dMax; $("matRangeStart").value = _dMin;
          $("matRangeEnd").min = _dMin; $("matRangeEnd").max = _dMax; $("matRangeEnd").value = _dMax;
          $("matRangeStatus").textContent = "";
          excludedDates = [];
          renderExcludedTags();
        }
        renderMaterialDropdown();
        $("materialSection").classList.remove("hidden");
        var _iv = diagIntervalHours(res.dates || []);
        $("diagPersistUnit").textContent = _iv < 24 ? "小时" : "天";
        $("diagPersist").value = _iv < 24 ? 4 : 1;
        drawMaterialChart();
        renderMaterialStats(); drawStabilityChart();
        drawDiagnostics();
      });
  }

  function renderMaterialDropdown() {
    var panel = $("materialDropdownList");
    panel.innerHTML = "";
    if (!materialData) return;
    (materialData.materials || []).forEach(function(m, idx) {
      var key = matKey(m);
      var color = MAT_COLORS[idx % MAT_COLORS.length];
      var label = m.name + (m.direction ? " (" + m.direction + ")" : "");
      var item = document.createElement("label");
      item.className = "material-item" + (selectedMaterials[key] ? " checked" : "");
      item.innerHTML = '<i class="mat-swatch" style="background:' + color + '"></i>' +
        '<input type="checkbox" data-key="' + key + '" ' + (selectedMaterials[key] ? "checked" : "") + '>' +
        '<span>' + label + '</span>';
      item.querySelector("input").addEventListener("change", function(e) {
        if (e.target.checked) { selectedMaterials[key] = true; item.classList.add("checked"); }
        else { delete selectedMaterials[key]; item.classList.remove("checked"); }
        updateDropdownBtn();
        drawMaterialChart();
        renderMaterialStats(); drawStabilityChart();
      });
      panel.appendChild(item);
    });
    updateDropdownBtn();
  }

  function updateDropdownBtn() {
    var n = Object.keys(selectedMaterials).length;
    var btn = $("materialDropdownBtn");
    btn.textContent = n ? "已选 " + n + " 个物料 ▾" : "请选择物料 ▾";
  }

  function escapeHtml(s) { return String(s).replace(/&/g, "&amp;").replace(/</g, "&lt;").replace(/>/g, "&gt;"); }

  // 分段评分：CV<0.1 映射 [90,100]、0.1-0.3 映射 [70,90)、>0.3 映射 [0,70)，CV>=1 归 0
  function cvScore(cv) {
    if (cv >= 1) return 0;
    if (cv < 0.1) return Math.round(90 + (0.1 - cv) / 0.1 * 10);
    if (cv <= 0.3) return Math.round(70 + (0.3 - cv) / 0.2 * 20);
    return Math.round(Math.max(0, 70 - (cv - 0.3) / 0.7 * 70));
  }
  function levelClass(lv) { return lv === "高稳定" ? "lv-high" : (lv === "中等" ? "lv-mid" : "lv-low"); }
  function levelOf(score) { return score >= 90 ? "高稳定" : (score >= 70 ? "中等" : "低稳定"); }

  function renderMaterialStats() {
    var el2 = $("materialStats");
    var keys = Object.keys(selectedMaterials);
    if (!keys.length || !materialData) { el2.classList.add("hidden"); el2.innerHTML = ""; return; }
    el2.classList.remove("hidden");
    var dates = getFilteredDates();
    var dirTotals = { "进": 0, "出": 0, "": 0 };
    (materialData.materials || []).forEach(function(m) {
      var mk = matKey(m);
      var ser = materialData.series[mk] || {};
      var tot = 0;
      dates.forEach(function(d) { var v = ser[d]; if (v != null && !isNaN(v)) tot += v; });
      var dir = m.direction || "";
      if (!(dir in dirTotals)) dirTotals[dir] = 0;
      dirTotals[dir] += tot;
    });
    var html = '<table class="mat-stats-table"><thead><tr>' +
      '<th>物料</th><th>最大值</th><th>最小值</th><th>均值</th><th>总计</th><th>占比/收率</th><th>极差比</th><th>稳定性评分</th><th>MAD评分</th><th>稳定性等级</th><th>零值占比</th>' +
      '</tr></thead><tbody>';
    keys.forEach(function(key) {
      var s = materialData.series[key] || {};
      var vals = dates.map(function(d) { var v = s[d]; return (v != null && !isNaN(v)) ? v : null; }).filter(function(v) { return v != null; });
      var mx = vals.length ? Math.max.apply(null, vals) : null;
      var mn = vals.length ? Math.min.apply(null, vals) : null;
      var total = vals.length ? vals.reduce(function(a, b) { return a + b; }, 0) : null;
      var mean = vals.length ? total / vals.length : null;
      // 极差比 R/mu
      var rangeRatio = (mx != null && mean != null && Math.abs(mean) > 1e-9) ? (mx - mn) / Math.abs(mean) : null;
      // CV 评分（均值-标准差），样本>=10 才算
      var score = "-";
      if (vals.length >= 10 && mean != null && Math.abs(mean) > 1e-9) {
        var variance = vals.reduce(function(a, b) { return a + (b - mean) * (b - mean); }, 0) / vals.length;
        var cv = Math.sqrt(variance) / Math.abs(mean);
        score = cvScore(cv);
      }
      // MAD 评分（中位数-绝对中位差），抗偏态，样本>=10 才算
      var madScore = "-";
      if (vals.length >= 10) {
        var sorted = vals.slice().sort(function(a, b) { return a - b; });
        var median = sorted.length % 2 ? sorted[(sorted.length - 1) / 2] : (sorted[sorted.length / 2 - 1] + sorted[sorted.length / 2]) / 2;
        if (Math.abs(median) > 1e-9) {
          var absDevs = vals.map(function(v) { return Math.abs(v - median); }).sort(function(a, b) { return a - b; });
          var mad = absDevs.length % 2 ? absDevs[(absDevs.length - 1) / 2] : (absDevs[absDevs.length / 2 - 1] + absDevs[absDevs.length / 2]) / 2;
          var cvRobust = mad / Math.abs(median);
          madScore = cvScore(cvRobust);
        }
      }
      // 综合等级：任一为低则低；否则取较差档
      var lv = "-";
      if (score !== "-" || madScore !== "-") {
        var worst = Math.min(score !== "-" ? score : 100, madScore !== "-" ? madScore : 100);
        lv = levelOf(worst);
      }
      // 占比
      var dir = "";
      var mi = null;
      for (var i = 0; i < (materialData.materials || []).length; i++) { if (matKey(materialData.materials[i]) === key) { mi = materialData.materials[i]; break; } }
      if (mi) dir = mi.direction || "";
      var inDenom = dirTotals["进"] || 0;
      var ratio = (total != null && inDenom > 0) ? (total / inDenom * 100) : null;
      // 零值占比
      var zeroCnt = vals.filter(function(v) { return Math.abs(v) < 1e-9; }).length;
      var zeroPct = vals.length ? (zeroCnt / vals.length * 100) : null;
      html += '<tr><td>' + escapeHtml(key) + '</td>' +
        '<td>' + (mx != null ? mx.toFixed(2) : "-") + '</td>' +
        '<td>' + (mn != null ? mn.toFixed(2) : "-") + '</td>' +
        '<td>' + (mean != null ? mean.toFixed(2) : "-") + '</td>' +
        '<td>' + (total != null ? total.toFixed(2) : "-") + '</td>' +
        '<td>' + (ratio != null ? ratio.toFixed(2) + "%" : "-") + '</td>' +
        '<td>' + (rangeRatio != null ? rangeRatio.toFixed(2) : "-") + '</td>' +
        '<td>' + (score !== "-" ? score : "-") + '</td>' +
        '<td>' + (madScore !== "-" ? madScore : "-") + '</td>' +
        '<td>' + (lv !== "-" ? '<span class="stbl ' + levelClass(lv) + '">' + lv + '</span>' : "-") + '</td>' +
        '<td>' + (zeroPct != null ? zeroPct.toFixed(1) + "%" : "-") + '</td></tr>';
    });
    html += '</tbody></table>';
    el2.innerHTML = html;
  }

  function drawMaterialChart() {
    var svg = $("materialChart");
    var wrap = $("materialChartWrap");
    if (!materialData) { svg.innerHTML = ""; return; }
    var dates = getFilteredDates();
    var keys = Object.keys(selectedMaterials);
    if (!keys.length) {
      svg.setAttribute("viewBox", "0 0 600 200");
      svg.innerHTML = '<text x="300" y="100" text-anchor="middle" fill="#999" font-size="13">请在上方选择物料</text>';
      return;
    }
    var W = Math.max(wrap.clientWidth, 600);
    var H = 400;
    var M = { l: 64, r: 60, t: 20, b: 40 };
    var iw = W - M.l - M.r, ih = H - M.t - M.b;
    svg.setAttribute("viewBox", "0 0 " + W + " " + H);
    svg.innerHTML = "";
    var n = dates.length;
    if (n === 0) return;

    var totalInPerDate = {};
    (materialData.materials || []).forEach(function(m) {
      if ((m.direction || "") !== "进") return;
      var mk = matKey(m);
      var ser = materialData.series[mk] || {};
      dates.forEach(function(d) {
        var v = ser[d];
        if (v != null && !isNaN(v)) totalInPerDate[d] = (totalInPerDate[d] || 0) + v;
      });
    });

    var series = [];
    var allVals = [];
    var allPcts = [];
    keys.forEach(function(key) {
      var ser = materialData.series[key] || {};
      // 流量为0的时间点不参与稳定性分析（无物料时分析稳定性无意义）
      var vals = dates.map(function(d) { var v = ser[d]; return (v != null && !isNaN(v) && Math.abs(v) > 1e-9) ? v : null; });
      vals.forEach(function(v) { if (v != null) allVals.push(v); });
      var dir = "";
      var mIdx = 0;
      for (var i = 0; i < (materialData.materials||[]).length; i++) {
        if (matKey(materialData.materials[i]) === key) { mIdx = i; dir = materialData.materials[i].direction || ""; break; }
      }
      var pcts = dates.map(function(d, i) {
        var v = vals[i];
        var tin = totalInPerDate[d];
        if (v != null && tin && tin > 0) return v / tin * 100;
        return null;
      });
      pcts.forEach(function(pp) { if (pp != null) allPcts.push(pp); });
      series.push({ key: key, vals: vals, pcts: pcts, dir: dir, color: MAT_COLORS[mIdx % MAT_COLORS.length] });
    });

    var yMin = 0;
    var yMax = allVals.length ? Math.max.apply(null, allVals) : 100;
    if (yMax <= 0) yMax = 100;
    yMax = yMax * 1.1;

    var pMin = 0;
    var pMax = allPcts.length ? Math.max.apply(null, allPcts) : 100;
    if (pMax <= 0) pMax = 100;
    pMax = Math.ceil(pMax * 1.1 / 5) * 5;
    if (pMax < 10) pMax = 10;

    function x(i) { return M.l + (n === 1 ? iw / 2 : i / (n - 1) * iw); }
    function y(v) { return M.t + (1 - (v - yMin) / (yMax - yMin)) * ih; }
    function yR(v) { return M.t + (1 - (v - pMin) / (pMax - pMin)) * ih; }

    var NS2 = "http://www.w3.org/2000/svg";
    function el2(tag, attrs) { var e = document.createElementNS(NS2, tag); for (var k in attrs) e.setAttribute(k, attrs[k]); return e; }

    var ticks = niceTicks(yMin, yMax, 6);
    ticks.forEach(function(t) {
      svg.appendChild(el2("line", { x1: M.l, y1: y(t), x2: W - M.r, y2: y(t), stroke: "#e8eaed", "stroke-width": 1 }));
      var lbl = el2("text", { x: M.l - 8, y: y(t) + 4, "text-anchor": "end", "font-size": 11, fill: "#666" });
      lbl.textContent = t.toFixed(1);
      svg.appendChild(lbl);
    });

    var pticks = niceTicks(pMin, pMax, 6);
    pticks.forEach(function(t) {
      var lbl = el2("text", { x: W - M.r + 8, y: yR(t) + 4, "text-anchor": "start", "font-size": 11, fill: "#2e8b57" });
      lbl.textContent = t.toFixed(0) + "%";
      svg.appendChild(lbl);
    });

    series.forEach(function(s) {
      var d = "";
      for (var i = 0; i < n; i++) { if (s.vals[i] == null) continue; d += (d ? "L" : "M") + x(i).toFixed(1) + " " + y(s.vals[i]).toFixed(1) + " "; }
      if (d) svg.appendChild(el2("path", { d: d.trim(), fill: "none", stroke: s.color, "stroke-width": 1.5, "stroke-linejoin": "round" }));
    });

    // 各物料流量均线（截至当天的累计均值），与流量共用左轴
    series.forEach(function(s) {
      var cumSum = 0, cumCnt = 0;
      var meanVals = [];
      for (var i = 0; i < n; i++) {
        if (s.vals[i] != null) { cumSum += s.vals[i]; cumCnt++; }
        meanVals.push(cumCnt > 0 ? cumSum / cumCnt : null);
      }
      var validMeans = meanVals.filter(function(v) { return v != null; });
      if (validMeans.length === 0) return;
      s.meanVal = validMeans[validMeans.length - 1];
      var d = "";
      for (var i = 0; i < n; i++) {
        if (meanVals[i] == null) continue;
        d += (d ? "L" : "M") + x(i).toFixed(1) + " " + y(meanVals[i]).toFixed(1) + " ";
      }
      if (d) svg.appendChild(el2("path", { d: d.trim(), fill: "none", stroke: "#6b7280", "stroke-width": 1.3, "stroke-dasharray": "8 4", "stroke-linejoin": "round", opacity: 0.7 }));
      var lastMean = validMeans[validMeans.length - 1];
      var mlbl = el2("text", { x: W - M.r - 4, y: y(lastMean) - 5, "text-anchor": "end", "font-size": 10, fill: "#6b7280", "font-weight": "600" });
      mlbl.textContent = "均 " + lastMean.toFixed(1);
      svg.appendChild(mlbl);
    });

    series.forEach(function(s) {
      var d = "";
      for (var i = 0; i < n; i++) { if (s.pcts[i] == null) continue; d += (d ? "L" : "M") + x(i).toFixed(1) + " " + yR(s.pcts[i]).toFixed(1) + " "; }
      if (d) svg.appendChild(el2("path", { d: d.trim(), fill: "none", stroke: s.color, "stroke-width": 1.2, "stroke-dasharray": "5 3", "stroke-linejoin": "round", opacity: 0.7 }));
    });

    var step = Math.max(1, Math.ceil(n / 10));
    for (var i = 0; i < n; i += step) {
      var lbl = el2("text", { x: x(i), y: H - M.b + 18, "text-anchor": "middle", "font-size": 10, fill: "#666" });
      lbl.textContent = dates[i].slice(5);
      svg.appendChild(el2("line", { x1: x(i), y1: H - M.b, x2: x(i), y2: H - M.b + 4, stroke: "#ccc" }));
      svg.appendChild(lbl);
    }

    svg.appendChild(el2("line", { x1: M.l, y1: M.t, x2: M.l, y2: H - M.b, stroke: "#999" }));
    svg.appendChild(el2("line", { x1: M.l, y1: H - M.b, x2: W - M.r, y2: H - M.b, stroke: "#999" }));
    svg.appendChild(el2("line", { x1: W - M.r, y1: M.t, x2: W - M.r, y2: H - M.b, stroke: "#999" }));

    var legendX = M.l + 8;
    series.forEach(function(s, idx) {
      var ly = M.t + 6 + idx * 36;
      // 实线：流量（物料色）
      svg.appendChild(el2("line", { x1: legendX, y1: ly - 6, x2: legendX + 16, y2: ly - 6, stroke: s.color, "stroke-width": 2 }));
      var tl = el2("text", { x: legendX + 22, y: ly - 2, "font-size": 11, fill: "#444" });
      tl.textContent = s.key + " 流量";
      svg.appendChild(tl);
      // 灰色虚线：均线
      svg.appendChild(el2("line", { x1: legendX, y1: ly + 10, x2: legendX + 16, y2: ly + 10, stroke: "#6b7280", "stroke-width": 1.3, "stroke-dasharray": "5 3", opacity: 0.7 }));
      var ml = el2("text", { x: legendX + 22, y: ly + 14, "font-size": 11, fill: "#6b7280" });
      ml.textContent = s.key + " 均线" + (s.meanVal != null ? " (终值 " + s.meanVal.toFixed(1) + ")" : "");
      svg.appendChild(ml);
    });
    // 占比/收率线图例（绿色点划线）
    var pctLy = M.t + 6 + series.length * 36 + 6;
    svg.appendChild(el2("line", { x1: legendX, y1: pctLy, x2: legendX + 16, y2: pctLy, stroke: "#2e8b57", "stroke-width": 1.2, "stroke-dasharray": "5 3", opacity: 0.7 }));
    var plbl = el2("text", { x: legendX + 22, y: pctLy + 4, "font-size": 11, fill: "#2e8b57" });
    plbl.textContent = "占比/收率 (%) 右轴";
    svg.appendChild(plbl);

    var yt2 = el2("text", { x: 16, y: M.t + ih / 2, "text-anchor": "middle", "font-size": 12, fill: "#444", transform: "rotate(-90 16 " + (M.t + ih / 2) + ")" });
    yt2.textContent = "流量 (t)";
    svg.appendChild(yt2);

    var yt3 = el2("text", { x: W - 14, y: M.t + ih / 2, "text-anchor": "middle", "font-size": 12, fill: "#2e8b57", transform: "rotate(90 " + (W - 14) + " " + (M.t + ih / 2) + ")" });
    yt3.textContent = "占比/收率 (%)";
    svg.appendChild(yt3);

    setupMaterialHover(svg, series, dates, x, y, yR, W, H, M);
  }

  function setupMaterialHover(svg, series, dates, x, y, yR, W, H, M) {
    var tooltip = $("materialTooltip");
    svg.onmousemove = function(evt) {
      var rect = svg.getBoundingClientRect();
      var mx = (evt.clientX - rect.left) * (W / rect.width);
      var n = dates.length;
      var i = Math.round((mx - M.l) / (W - M.l - M.r) * (n - 1));
      if (i < 0) i = 0; if (i > n - 1) i = n - 1;
      tooltip.classList.remove("hidden");
      var html = "<div><b>" + dates[i] + "</b></div>";
      series.forEach(function(s) {
        var v = s.vals[i];
        var p = s.pcts[i];
        var lbl = (s.dir === "进") ? "占比" : (s.dir === "出" ? "收率" : "占比");
        html += "<div><i style='display:inline-block;width:8px;height:8px;border-radius:2px;background:" + s.color + ";margin-right:4px'></i>" + s.key + ": <b>" + (v != null ? v.toFixed(2) : "-") + "</b>" + (p != null ? " <span style='color:#2e8b57'>" + lbl + " " + p.toFixed(2) + "%</span>" : "") + "</div>";
      });
      tooltip.innerHTML = html;
      var px = (x(i) / W) * rect.width;
      tooltip.style.left = Math.min(rect.width - 260, px + 12) + "px";
      tooltip.style.top = "10px";
    };
    svg.onmouseleave = function() { tooltip.classList.add("hidden"); };
  }

function setupMaterialControls() {
    var btn = $("materialDropdownBtn");
    var panel = $("materialDropdownPanel");
    btn.addEventListener("click", function(e) {
      e.stopPropagation();
      panel.classList.toggle("hidden");
    });
    panel.addEventListener("click", function(e) { e.stopPropagation(); });
    document.addEventListener("click", function() { panel.classList.add("hidden"); });
    $("matSelectAll").addEventListener("click", function() {
      if (!materialData) return;
      (materialData.materials || []).forEach(function(m) { selectedMaterials[matKey(m)] = true; });
      renderMaterialDropdown();
      drawMaterialChart();
      renderMaterialStats(); drawStabilityChart();
    });
    $("matSelectNo").addEventListener("click", function() {
      selectedMaterials = {};
      if (materialData) { renderMaterialDropdown(); drawMaterialChart(); renderMaterialStats(); drawStabilityChart(); }
    });
    $("matRangeApply").addEventListener("click", function() {
      if (!materialData) return;
      var s = $("matRangeStart").value, e = $("matRangeEnd").value;
      var n = getFilteredDates().length;
      var parts = [];
      if (s || e) parts.push((s || "?") + " ~ " + (e || "?"));
      if (excludedDates.length) parts.push("剔除 " + excludedDates.length + " 天");
      $("matRangeStatus").textContent = parts.length ? ("已过滤：" + n + " 个时间点（" + parts.join("；") + "）") : "";
      drawMaterialChart();
      renderMaterialStats(); drawStabilityChart();
      drawDiagnostics();
      var panel = $("matExcludePanel");
      if (panel && !panel.classList.contains("hidden")) fillExcludeList();
    });
    $("matRangeReset").addEventListener("click", function() {
      if (!materialData || !(materialData.dates || []).length) return;
      var dMin = materialData.dates[0].slice(0, 10), dMax = materialData.dates[materialData.dates.length - 1].slice(0, 10);
      $("matRangeStart").value = dMin; $("matRangeEnd").value = dMax;
      excludedDates = [];
      renderExcludedTags();
      $("matRangeStatus").textContent = "";
      drawMaterialChart();
      renderMaterialStats(); drawStabilityChart();
    });
  }
  // ========== 物料平衡诊断 ==========
  // ---- 稳定性趋势：滚动7日 CV ----
  function drawStabilityChart() {
    var svg = $("stabilityChart");
    var wrap = $("stabilityChartWrap");
    if (!materialData) { svg.innerHTML = ""; return; }
    var dates = getFilteredDates();
    var keys = Object.keys(selectedMaterials);
    var W = Math.max(wrap.clientWidth, 600);
    var H = 280;
    var M = { l: 56, r: 24, t: 20, b: 40 };
    var iw = W - M.l - M.r, ih = H - M.t - M.b;
    svg.setAttribute("viewBox", "0 0 " + W + " " + H);
    svg.innerHTML = "";
    var n = dates.length;
    if (n === 0 || !keys.length) {
      var t = document.createElementNS("http://www.w3.org/2000/svg", "text");
      t.setAttribute("x", W/2); t.setAttribute("y", H/2);
      t.setAttribute("text-anchor", "middle"); t.setAttribute("fill", "#999"); t.setAttribute("font-size", "13");
      t.textContent = !keys.length ? "请在上方选择物料" : "无数据";
      svg.appendChild(t);
      return;
    }
    var detrend = $("detrendToggle") && $("detrendToggle").checked;
    var NS2 = "http://www.w3.org/2000/svg";
    function el2(tag, attrs) { var e = document.createElementNS(NS2, tag); for (var k in attrs) e.setAttribute(k, attrs[k]); return e; }
    var window = 7;
    var allCVs = [];
    var series = [];
    keys.forEach(function(key) {
      var ser = materialData.series[key] || {};
      var vals = dates.map(function(d) { var v = ser[d]; return (v != null && !isNaN(v)) ? v : null; });
      var cvs = [];
      for (var i = 0; i < n; i++) {
        var seg = [];
        for (var j = Math.max(0, i - window + 1); j <= i; j++) { if (vals[j] != null) seg.push(vals[j]); }
        if (seg.length >= 3) {
          var m = seg.reduce(function(a,b){return a+b;},0) / seg.length;
          if (Math.abs(m) > 1e-9) {
            var variance = seg.reduce(function(s,x){return s+(x-m)*(x-m);},0) / seg.length;
            cvs.push(Math.sqrt(variance) / Math.abs(m));
          } else { cvs.push(null); }
        } else { cvs.push(null); }
      }
      if (detrend) {
        var validIdx = [];
        for (var i = 0; i < n; i++) { if (vals[i] != null) validIdx.push(i); }
        if (validIdx.length >= 3) {
          var xs = validIdx.map(function(i){return i;});
          var ys = validIdx.map(function(i){return vals[i];});
          var mx = xs.reduce(function(a,b){return a+b;},0)/xs.length;
          var my = ys.reduce(function(a,b){return a+b;},0)/ys.length;
          var sxx = xs.reduce(function(s,x){return s+(x-mx)*(x-mx);},0);
          var sxy = xs.reduce(function(s,x,i){return s+(x-mx)*(ys[i]-my);},0);
          var slope = sxx > 0 ? sxy/sxx : 0;
          var resid = vals.map(function(v,i){ return (v != null) ? v - (slope * i + (my - slope*mx)) : null; });
          for (var i = 0; i < n; i++) {
            var seg = [];
            for (var j = Math.max(0, i - window + 1); j <= i; j++) { if (resid[j] != null) seg.push(resid[j]); }
            if (seg.length >= 3) {
              var m = seg.reduce(function(a,b){return a+b;},0) / seg.length;
              var variance = seg.reduce(function(s,x){return s+(x-m)*(x-m);},0) / seg.length;
              cvs[i] = m != 0 ? Math.sqrt(variance) / Math.abs(m) : null;
            } else { cvs[i] = null; }
            if (cvs[i] != null) allCVs.push(cvs[i]);
          }
        } else {
          cvs.forEach(function(c){ if (c != null) allCVs.push(c); });
        }
      } else {
        cvs.forEach(function(c){ if (c != null) allCVs.push(c); });
      }
      var mIdx = 0;
      for (var i = 0; i < (materialData.materials||[]).length; i++) { if (matKey(materialData.materials[i]) === key) { mIdx = i; break; } }
      series.push({ key: key, cvs: cvs, color: MAT_COLORS[mIdx % MAT_COLORS.length] });
    });
    var yMax = allCVs.length ? Math.max(0.3, Math.max.apply(null, allCVs)) : 0.5;
    yMax = Math.ceil(yMax * 1.1 * 10) / 10;
    function x(i) { return M.l + (n === 1 ? iw / 2 : i / (n - 1) * iw); }
    function y(v) { return M.t + (1 - v / yMax) * ih; }
    var ticks = niceTicks(0, yMax, 5);
    ticks.forEach(function(t) {
      svg.appendChild(el2("line", { x1: M.l, y1: y(t), x2: W - M.r, y2: y(t), stroke: "#e8eaed", "stroke-width": 1 }));
      var lbl = el2("text", { x: M.l - 8, y: y(t) + 4, "text-anchor": "end", "font-size": 11, fill: "#666" });
      lbl.textContent = t.toFixed(2);
      svg.appendChild(lbl);
    });
    // threshold lines for CV levels
    [[0.1, "#16a34a", "高稳定"], [0.3, "#d97706", "中等"]].forEach(function(arr) {
      var ty = y(arr[0]);
      if (ty > M.t && ty < H - M.b) {
        svg.appendChild(el2("line", { x1: M.l, y1: ty, x2: W - M.r, y2: ty, stroke: arr[1], "stroke-width": 1, "stroke-dasharray": "4 4", opacity: 0.5 }));
        var tl = el2("text", { x: W - M.r - 4, y: ty - 4, "text-anchor": "end", "font-size": 10, fill: arr[1] });
        tl.textContent = arr[2] + " (" + arr[0] + ")";
        svg.appendChild(tl);
      }
    });
    series.forEach(function(s) {
      var d = "";
      for (var i = 0; i < n; i++) { if (s.cvs[i] == null) continue; d += (d ? "L" : "M") + x(i).toFixed(1) + " " + y(s.cvs[i]).toFixed(1) + " "; }
      if (d) svg.appendChild(el2("path", { d: d.trim(), fill: "none", stroke: s.color, "stroke-width": 1.5, "stroke-linejoin": "round" }));
    });
    var step = Math.max(1, Math.ceil(n / 10));
    for (var i = 0; i < n; i += step) {
      var lbl = el2("text", { x: x(i), y: H - M.b + 18, "text-anchor": "middle", "font-size": 10, fill: "#666" });
      lbl.textContent = dates[i].slice(5);
      svg.appendChild(el2("line", { x1: x(i), y1: H - M.b, x2: x(i), y2: H - M.b + 4, stroke: "#ccc" }));
      svg.appendChild(lbl);
    }
    svg.appendChild(el2("line", { x1: M.l, y1: M.t, x2: M.l, y2: H - M.b, stroke: "#999" }));
    svg.appendChild(el2("line", { x1: M.l, y1: H - M.b, x2: W - M.r, y2: H - M.b, stroke: "#999" }));
    var legendX = M.l + 8;
    series.forEach(function(s, idx) {
      var ly = M.t + 6 + idx * 18;
      svg.appendChild(el2("rect", { x: legendX, y: ly - 8, width: 12, height: 3, fill: s.color, rx: 1 }));
      var tl = el2("text", { x: legendX + 16, y: ly - 2, "font-size": 11, fill: "#444" });
      tl.textContent = s.key;
      svg.appendChild(tl);
    });
    var yt = el2("text", { x: 16, y: M.t + ih / 2, "text-anchor": "middle", "font-size": 12, fill: "#444", transform: "rotate(-90 16 " + (M.t + ih / 2) + ")" });
    yt.textContent = "CV (滚动" + window + "点)";
    svg.appendChild(yt);
  }

  function parseDiagDate(s) { var p = String(s).split(' '); var d = p[0].split('-'); var t = p[1] ? p[1].split(':') : [0, 0]; return new Date(+d[0], +d[1] - 1, +d[2], +t[0] || 0, +t[1] || 0); }
  function diagIntervalHours(dates) { if (!dates || dates.length < 2) return 24; var diffs = []; for (var i = 1; i < dates.length; i++) { diffs.push((parseDiagDate(dates[i]) - parseDiagDate(dates[i - 1])) / 3600000); } diffs.sort(function(a, b) { return a - b; }); return diffs[Math.floor(diffs.length / 2)] || 24; }
  function diagYieldSeries(dates) {
    var yields = [], sumIn = [], sumOut = [];
    dates.forEach(function(d) {
      var si = 0, so = 0;
      (materialData.materials || []).forEach(function(m) {
        var ser = materialData.series[matKey(m)] || {}; var v = ser[d];
        if (v != null && !isNaN(v)) { if (m.direction === '进') si += v; else if (m.direction === '出') so += v; }
      });
      sumIn.push(si); sumOut.push(so); yields.push(si > 1e-9 ? so / si * 100 : null);
    });
    return { yields: yields, sumIn: sumIn, sumOut: sumOut };
  }
  function diagDetectAnomalies(dates, yields, persistPoints) {
    var bad = [];
    yields.forEach(function(y, i) { if (y != null && (y < 98 || y > 102)) bad.push(i); });
    var runs = [], cur = null;
    bad.forEach(function(i) {
      if (cur && i === cur.idx[cur.idx.length - 1] + 1) cur.idx.push(i);
      else { if (cur) runs.push(cur); cur = { idx: [i] }; }
    });
    if (cur) runs.push(cur);
    var anomalySet = {}, realRuns = [];
    runs.forEach(function(r) { if (r.idx.length >= persistPoints) { r.idx.forEach(function(i) { anomalySet[i] = true; }); realRuns.push({ start: dates[r.idx[0]], end: dates[r.idx[r.idx.length - 1]] }); } });
    return { anomalySet: anomalySet, runs: realRuns };
  }
  function diagMean(arr) { var f = arr.filter(function(v) { return v != null && !isNaN(v); }); if (!f.length) return null; return f.reduce(function(a, b) { return a + b; }, 0) / f.length; }
  function diagCV(arr) { var f = arr.filter(function(v) { return v != null && !isNaN(v); }); if (f.length < 2) return null; var m = f.reduce(function(a, b) { return a + b; }, 0) / f.length; if (Math.abs(m) < 1e-9) return null; var v = f.reduce(function(s, x) { return s + (x - m) * (x - m); }, 0) / f.length; return Math.sqrt(v) / Math.abs(m); }
  function diagEl(tag, attrs) { var e = document.createElementNS('http://www.w3.org/2000/svg', tag); for (var k in attrs) e.setAttribute(k, attrs[k]); return e; }

  function drawDiagnostics() {
    var sec = $('diagSection');
    if (!materialData) { sec.classList.add('hidden'); return; }
    sec.classList.remove('hidden');
    var dates = getFilteredDates();
    // 叠加分析数据源区的统一筛选（取交集）
    var uStart = $("ioFlowStart") ? $("ioFlowStart").value : "";
    var uEnd = $("ioFlowEnd") ? $("ioFlowEnd").value : "";
    var uExcSet = {};
    ioExcludedDates.forEach(function(d) { uExcSet[d] = true; });
    getYieldExcludedDates().forEach(function(d) { uExcSet[d] = true; });
    if (uStart || uEnd || Object.keys(uExcSet).length) {
      dates = dates.filter(function(d) {
        var dp = (d || "").slice(0, 10);
        if (uStart && dp < uStart) return false;
        if (uEnd && dp > uEnd) return false;
        if (uExcSet[dp]) return false;
        return true;
      });
    }
    var intervalHours = diagIntervalHours(materialData.dates || dates);
    var isHourly = intervalHours < 24;
    var persistVal = parseFloat($('diagPersist').value) || (isHourly ? 4 : 1);
    var persistPoints = isHourly ? Math.max(1, Math.ceil(persistVal / intervalHours)) : Math.max(1, Math.round(persistVal));
    var ratio = (parseFloat($('diagRatio').value) || 5) / 100;
    if (!dates.length) { $('diagStatus').textContent = '无数据'; return; }
    var ys = diagYieldSeries(dates);
    var anom = diagDetectAnomalies(dates, ys.yields, persistPoints);
    $('diagStatus').textContent = '持续越限 ' + anom.runs.length + ' 段（≥' + persistVal + (isHourly ? '小时' : '天') + '），涉及 ' + Object.keys(anom.anomalySet).length + ' 个时间点';
    renderCumulativeBalance(dates, ys, anom);
    renderSwitchTimeline(dates, ratio, anom, intervalHours);
    renderAttribution(dates, ys, anom);
  }
  function renderCumulativeBalance(dates, ys, anom) {
    var svg = $('diagCumChart'), wrap = $('diagCumWrap');
    var n = dates.length;
    if (n === 0) { svg.innerHTML = ''; return; }
    var W = Math.max(wrap.clientWidth, 600), H = 280;
    var M = { l: 64, r: 24, t: 16, b: 40 };
    var iw = W - M.l - M.r, ih = H - M.t - M.b;
    svg.setAttribute('viewBox', '0 0 ' + W + ' ' + H); svg.innerHTML = '';
    var cum = [], run = 0;
    for (var i = 0; i < n; i++) { run += (ys.sumOut[i] - ys.sumIn[i]); cum.push(run); }
    var cMin = Math.min.apply(null, cum), cMax = Math.max.apply(null, cum);
    if (cMin === cMax) { cMin -= 1; cMax += 1; }
    var pad = (cMax - cMin) * 0.1 || 1; cMin -= pad; cMax += pad;
    if (cMin > 0) cMin = 0; if (cMax < 0) cMax = 0;
    function x(i) { return M.l + (n === 1 ? iw / 2 : i / (n - 1) * iw); }
    function y(v) { return M.t + (1 - (v - cMin) / (cMax - cMin)) * ih; }
    anom.runs.forEach(function(r) {
      var si = dates.indexOf(r.start), ei = dates.indexOf(r.end);
      svg.appendChild(diagEl('rect', { x: x(si), y: M.t, width: Math.max(2, x(ei) - x(si)), height: ih, fill: '#fde8e8', opacity: 0.6 }));
    });
    var ticks = niceTicks(cMin, cMax, 6);
    ticks.forEach(function(t) {
      svg.appendChild(diagEl('line', { x1: M.l, y1: y(t), x2: W - M.r, y2: y(t), stroke: t === 0 ? '#bbb' : '#eef0f3', 'stroke-width': t === 0 ? 1.2 : 1 }));
      var lb = diagEl('text', { x: M.l - 8, y: y(t) + 4, 'text-anchor': 'end', 'font-size': 11, fill: '#666' }); lb.textContent = t.toFixed(0); svg.appendChild(lb);
    });
    var d = '';
    for (var i = 0; i < n; i++) { d += (d ? 'L' : 'M') + x(i).toFixed(1) + ' ' + y(cum[i]).toFixed(1) + ' '; }
    svg.appendChild(diagEl('path', { d: d.trim(), fill: 'none', stroke: '#2b6cb0', 'stroke-width': 1.8, 'stroke-linejoin': 'round' }));
    svg.appendChild(diagEl('line', { x1: M.l, y1: M.t, x2: M.l, y2: H - M.b, stroke: '#999' }));
    svg.appendChild(diagEl('line', { x1: M.l, y1: H - M.b, x2: W - M.r, y2: H - M.b, stroke: '#999' }));
    var yt = diagEl('text', { x: 16, y: M.t + ih / 2, 'text-anchor': 'middle', 'font-size': 12, fill: '#444', transform: 'rotate(-90 16 ' + (M.t + ih / 2) + ')' }); yt.textContent = '累积(Σ出−Σ进)'; svg.appendChild(yt);
    var step = Math.max(1, Math.ceil(n / 10));
    for (var i = 0; i < n; i += step) { var lb = diagEl('text', { x: x(i), y: H - M.b + 18, 'text-anchor': 'middle', 'font-size': 10, fill: '#666' }); lb.textContent = dates[i].slice(5); svg.appendChild(lb); }
    var slope = (cum[n - 1] - cum[0]) / n, range = cMax - cMin;
    var hint;
    if (Math.abs(slope) * n < range * 0.15) hint = '累积曲线平坦震荡 → 越限多为瞬时噪声（罐区滞留/测量抖动），平均闭合，通常无需追查。';
    else if (Math.abs(slope) * n >= range * 0.6) hint = '累积曲线呈线性漂移 → 存在系统偏差（某侧线计量偏置/未计入物流），需排查根因。';
    else hint = '累积曲线含漂移与波动 → 兼具系统偏差与瞬时噪声，重点看阶跃处对应的切换事件。';
    $('diagCumHint').textContent = hint + '（粉红区间为持续越限段）';
  }

  function renderSwitchTimeline(dates, ratio, anom, intervalHours) {
    var svg = $('diagGanttChart'), wrap = $('diagGanttWrap');
    var mats = materialData.materials || [];
    var n = dates.length, nrows = mats.length;
    if (n === 0 || nrows === 0) { svg.innerHTML = '<text x=\'300\' y=\'40\' text-anchor=\'middle\' fill=\'#999\' font-size=\'13\'>无数据</text>'; return; }
    var rowH = 20;
    var W = Math.max(wrap.clientWidth, 600), H = nrows * rowH + 56;
    var M = { l: 150, r: 24, t: 12, b: 36 };
    var iw = W - M.l - M.r, ih = nrows * rowH;
    svg.setAttribute('viewBox', '0 0 ' + W + ' ' + H); svg.innerHTML = '';
    function x(i) { return M.l + (n === 1 ? iw / 2 : i / (n - 1) * iw); }
    function rowY(r) { return M.t + r * rowH; }
    anom.runs.forEach(function(r) {
      var si = dates.indexOf(r.start), ei = dates.indexOf(r.end);
      svg.appendChild(diagEl('rect', { x: x(si), y: M.t, width: Math.max(2, x(ei) - x(si)), height: ih, fill: '#fde8e8', opacity: 0.55 }));
    });
    var events = [];
    mats.forEach(function(m, ri) {
      var key = matKey(m), ser = materialData.series[key] || {};
      var vals = dates.map(function(d) { var v = ser[d]; return (v != null && !isNaN(v)) ? v : null; });
      var mx = 0; vals.forEach(function(v) { if (v != null && v > mx) mx = v; });
      var thr = mx * ratio;
      var running = false, segStart = 0, ev = null;
      for (var i = 0; i <= n; i++) {
        var isRun = i < n && vals[i] != null && vals[i] > thr && mx > 1e-9;
        if (isRun && !running) { running = true; segStart = i; ev = { time: dates[i], key: key, action: '开', durPts: 0 }; events.push(ev); }
        if ((!isRun || i === n) && running) {
          running = false;
          var durPts = (i - 1) - segStart;
          if (ev) ev.durPts = durPts;
          svg.appendChild(diagEl('rect', { x: x(segStart), y: rowY(ri) + 3, width: Math.max(1, x(i - 1 < 0 ? 0 : i - 1) - x(segStart)), height: rowH - 6, fill: MAT_COLORS[ri % MAT_COLORS.length], opacity: 0.7, rx: 2 }));
          if (i < n) events.push({ time: dates[i], key: key, action: '关' });
        }
      }
      var lb = diagEl('text', { x: M.l - 8, y: rowY(ri) + rowH / 2 + 4, 'text-anchor': 'end', 'font-size': 11, fill: '#444' }); lb.textContent = key; svg.appendChild(lb);
    });
    var step = Math.max(1, Math.ceil(n / 10));
    for (var i = 0; i < n; i += step) { var lb = diagEl('text', { x: x(i), y: H - M.b + 18, 'text-anchor': 'middle', 'font-size': 10, fill: '#666' }); lb.textContent = dates[i].slice(5); svg.appendChild(lb); }
    svg.appendChild(diagEl('line', { x1: M.l, y1: M.t, x2: M.l, y2: M.t + ih, stroke: '#999' }));
    svg.appendChild(diagEl('line', { x1: M.l, y1: M.t + ih, x2: W - M.r, y2: M.t + ih, stroke: '#999' }));
    events.sort(function(a, b) { return parseDiagDate(a.time) - parseDiagDate(b.time); });
    var html = events.length ? '<table class=\'mat-stats-table\'><thead><tr><th>时间</th><th>物料</th><th>动作</th><th>持续时长</th></tr></thead><tbody>' : '';
    events.slice(0, 200).forEach(function(e) {
      var durStr = '-';
      if (e.durPts != null) {
        if (intervalHours < 24) {
          durStr = (e.durPts * intervalHours).toFixed(0) + '小时';
        } else {
          durStr = e.durPts.toFixed(0) + '天';
        }
      }
      html += '<tr><td>' + e.time + '</td><td>' + escapeHtml(e.key) + '</td><td>' + (e.action === '开' ? '<span style=\'color:#1a8a4a\'>▲ 开</span>' : '<span style=\'color:#c0392b\'>▼ 关</span>') + '</td><td>' + durStr + '</td></tr>';
    });
    if (events.length) { html += '</tbody></table>'; if (events.length > 200) html += '<p class=\'diag-hint\'>仅显示前 200 条事件（共 ' + events.length + ' 条）</p>'; }
    $('diagEvents').innerHTML = html || '<p class=\'diag-hint\'>未检测到切换事件</p>';
  }

  function renderAttribution(dates, ys, anom) {
    var svg = $('diagAttrChart'), wrap = $('diagAttrWrap');
    var mats = materialData.materials || [];
    var base = {}, oBase = 0, iBase = 0;
    mats.forEach(function(m) {
      var ser = materialData.series[matKey(m)] || {};
      var vals = dates.map(function(d) { var v = ser[d]; return (v != null && !isNaN(v)) ? v : null; });
      var bm = diagMean(vals); base[matKey(m)] = bm;
      if (m.direction === '出') oBase += (bm || 0); else if (m.direction === '进') iBase += (bm || 0);
    });
    var yBase = iBase > 1e-9 ? oBase / iBase * 100 : 100;
    var anomIdx = []; dates.forEach(function(d, i) { if (anom.anomalySet[i]) anomIdx.push(i); });
    var contrib = {};
    mats.forEach(function(m) { var k = matKey(m); contrib[k] = { absSum: 0, signSum: 0, dir: m.direction }; });
    anomIdx.forEach(function(i) {
      mats.forEach(function(m) {
        var k = matKey(m), ser = materialData.series[k] || {};
        var v = ser[dates[i]]; if (v == null || isNaN(v)) return;
        var dev = v - (base[k] || 0);
        var c;
        if (m.direction === '出') c = dev;
        else if (m.direction === '进') c = -(yBase / 100) * dev;
        else c = 0;
        contrib[k].absSum += Math.abs(c); contrib[k].signSum += c;
      });
    });
    var ranked = Object.keys(contrib).map(function(k) { return { key: k, abs: contrib[k].absSum, sign: contrib[k].signSum }; }).filter(function(r) { return r.abs > 1e-9; }).sort(function(a, b) { return b.abs - a.abs; });
    svg.innerHTML = '';
    if (!ranked.length) { svg.setAttribute('viewBox', '0 0 600 60'); svg.innerHTML = '<text x=\'300\' y=\'34\' text-anchor=\'middle\' fill=\'#999\' font-size=\'13\'>无持续越限段，无需归因</text>'; $('diagCVTable').innerHTML = ''; return; }
    var rowH = 22, W = Math.max(wrap.clientWidth, 600), H = ranked.length * rowH + 44;
    var M = { l: 170, r: 80, t: 12, b: 28 };
    var iw = W - M.l - M.r;
    svg.setAttribute('viewBox', '0 0 ' + W + ' ' + H);
    var maxAbs = Math.max.apply(null, ranked.map(function(r) { return r.abs; })) || 1;
    function barLen(v) { return v / maxAbs * iw; }
    ranked.forEach(function(r, ri) {
      var yy = M.t + ri * rowH;
      var lb = diagEl('text', { x: M.l - 8, y: yy + 15, 'text-anchor': 'end', 'font-size': 11, fill: '#444' }); lb.textContent = r.key; svg.appendChild(lb);
      var col = r.sign >= 0 ? '#e8821e' : '#2b6cb0';
      svg.appendChild(diagEl('rect', { x: M.l, y: yy + 2, width: Math.max(1, barLen(r.abs)), height: rowH - 6, fill: col, opacity: 0.82, rx: 2 }));
      var vl = diagEl('text', { x: M.l + barLen(r.abs) + 6, y: yy + 15, 'font-size': 11, fill: '#666' }); vl.textContent = r.abs.toFixed(2) + (r.sign >= 0 ? ' ↑' : ' ↓'); svg.appendChild(vl);
    });
    svg.appendChild(diagEl('rect', { x: M.l, y: H - M.b + 4, width: 10, height: 10, fill: '#e8821e', opacity: 0.82, rx: 1 }));
    var lg1 = diagEl('text', { x: M.l + 14, y: H - M.b + 13, 'font-size': 11, fill: '#666' }); lg1.textContent = '推高收率'; svg.appendChild(lg1);
    svg.appendChild(diagEl('rect', { x: M.l + 90, y: H - M.b + 4, width: 10, height: 10, fill: '#2b6cb0', opacity: 0.82, rx: 1 }));
    var lg2 = diagEl('text', { x: M.l + 104, y: H - M.b + 13, 'font-size': 11, fill: '#666' }); lg2.textContent = '压低收率'; svg.appendChild(lg2);
    var compIdx = [], nonIdx = [];
    ys.yields.forEach(function(y, i) { if (y == null) return; if (y >= 98 && y <= 102) compIdx.push(i); else nonIdx.push(i); });
    var rows = mats.map(function(m) {
      var k = matKey(m), ser = materialData.series[k] || {};
      var cvC = diagCV(compIdx.map(function(i) { var v = ser[dates[i]]; return (v != null && !isNaN(v)) ? v : null; }));
      var cvN = diagCV(nonIdx.map(function(i) { var v = ser[dates[i]]; return (v != null && !isNaN(v)) ? v : null; }));
      var delta = (cvC != null && cvN != null) ? cvN - cvC : null;
      var susp = delta == null ? '低' : (delta > 0.1 ? '高' : (delta > 0.02 ? '中' : '低'));
      return { key: k, cvC: cvC, cvN: cvN, delta: delta, susp: susp };
    }).sort(function(a, b) { return (b.delta == null ? -1 : b.delta) - (a.delta == null ? -1 : a.delta); });
    var html = '<table class=\'mat-stats-table\'><thead><tr><th>物料</th><th>CV(合规段)</th><th>CV(非合规段)</th><th>ΔCV</th><th>嫌疑度</th></tr></thead><tbody>';
    rows.forEach(function(r) {
      var sc = r.susp === '高' ? 'lv-low' : (r.susp === '中' ? 'lv-mid' : 'lv-high');
      html += '<tr><td>' + escapeHtml(r.key) + '</td><td>' + (r.cvC != null ? r.cvC.toFixed(3) : '-') + '</td><td>' + (r.cvN != null ? r.cvN.toFixed(3) : '-') + '</td><td>' + (r.delta != null ? r.delta.toFixed(3) : '-') + '</td><td><span class=\'stbl ' + sc + '\'>' + r.susp + '</span></td></tr>';
    });
    html += '</tbody></table><p class=\'diag-hint\'>嫌疑度：非合规段 CV 显著高于合规段（ΔCV>0.1 高 / >0.02 中），提示该物料波动与收率越限强相关。</p>';
    $('diagCVTable').innerHTML = html;
  }
  $('diagApply').addEventListener('click', drawDiagnostics);
  setupMaterialControls();

  // ---- 进出物料变化趋势 ----
  var ioFlowData = null;
  var IO_COLORS = { jin: "#2b6cb0", chu: "#e0322d" };

  function loadIoFlow(sourceId) {
    loadIoFlowSilent(sourceId, "", "", null);
  }

  // 加载进出物料数据，可选保留已有筛选条件
  function loadIoFlowSilent(sourceId, keepStart, keepEnd, keepExc) {
    fetch("/api/analysis/io-flow?source_id=" + encodeURIComponent(sourceId) + "&neg_filter=" + currentNegFilter())
      .then(function(r){ return r.json(); })
      .then(function(res) {
        if (!res.has_data) { $("ioFlowSection").classList.add("hidden"); ioFlowData = null; return; }
        ioFlowData = res;
        var sel = $("ioFlowSelect");
        var prevUnit = sel ? sel.value : "total";
        sel.innerHTML = '<option value="total">总进出物料</option>';
        (res.units || []).forEach(function(u) {
          var opt = document.createElement("option");
          opt.value = u; opt.textContent = u;
          sel.appendChild(opt);
        });
        if (prevUnit && prevUnit !== "total") sel.value = prevUnit;
        var dates = res.dates || [];
        if (dates.length) {
          var dMin = dates[0].slice(0, 10), dMax = dates[dates.length - 1].slice(0, 10);
          $("ioFlowStart").min = dMin; $("ioFlowStart").max = dMax;
          $("ioFlowEnd").min = dMin; $("ioFlowEnd").max = dMax;
          if (keepStart) { $("ioFlowStart").value = keepStart; } else { $("ioFlowStart").value = dMin; }
          if (keepEnd) { $("ioFlowEnd").value = keepEnd; } else { $("ioFlowEnd").value = dMax; }
          $("ioFlowStatus").textContent = "";
        }
        if (keepExc !== null) { ioExcludedDates = keepExc; }
        else { ioExcludedDates = []; }
        renderIoExcludedTags();
        $("ioFlowSection").classList.remove("hidden");
        drawIoFlowChart();
        // 总收率图表也重绘（使用最新 currentData）
        if (currentData) {
          var allPoints = currentData.points || [];
          var filtered = filterPoints(allPoints);
          var st = recomputeStats(filtered.points);
          $("statCount").textContent = st.count || 0;
          $("statRange").textContent = (st.date_min || "-") + " ~ " + (st.date_max || "-");
          $("statMean").textContent = fmt(st.mean) + "%";
          $("statMin").textContent = fmt(st.min) + "%";
          $("statMax").textContent = fmt(st.max) + "%";
          var _qualified = st.in_range_count;
          var _displayed = filtered.points.length;
          var _rate = _displayed ? round2(_qualified / _displayed * 100) : null;
          $("statRate").textContent = fmt(_rate) + "% (" + _qualified + "/" + _displayed + ")";
          updateRangeStatus(filtered.removed, allPoints.length);
          drawChart(filtered.points);
        }
        loadDistribution();
      });
  }

  var ioExcludedDates = [];

  function getIoFlowFilteredDates() {
    if (!ioFlowData || !ioFlowData.dates) return [];
    var dates = ioFlowData.dates;
    var s = $("ioFlowStart").value;
    var e = $("ioFlowEnd").value;
    var excSet = {};
    ioExcludedDates.forEach(function(d) { excSet[d] = true; });
    getYieldExcludedDates().forEach(function(d) { excSet[d] = true; });
    return dates.filter(function(d) {
      var dp = (d || "").slice(0, 10);
      if (s && dp < s) return false;
      if (e && dp > e) return false;
      if (excSet[dp]) return false;
      return true;
    });
  }

  function renderIoExcludedTags() {
    var container = $("ioExcludeTags");
    if (!container) return;
    container.innerHTML = "";
    ioExcludedDates.forEach(function(d) {
      var tag = document.createElement("span");
      tag.className = "mat-exclude-tag";
      tag.innerHTML = d + ' <span class="mat-exclude-x" data-date="' + d + '">✕</span>';
      container.appendChild(tag);
    });
    var btn = $("ioExcludeBtn");
    if (btn) btn.textContent = ioExcludedDates.length ? ("已剔除 " + ioExcludedDates.length + " 天 ▾") : "点击选择剔除日期 ▾";
  }

  function fillIoExcludeList() {
    var listDiv = $("ioExcludeList");
    if (!listDiv || !ioFlowData) return;
    var dates = (ioFlowData.dates || []).map(function(d) { return (d || "").slice(0, 10); });
    var seen = {};
    dates = dates.filter(function(d) { if (seen[d]) return false; seen[d] = true; return true; }).sort();
    var s = $("ioFlowStart").value, e = $("ioFlowEnd").value;
    var filtered = dates.filter(function(d) {
      if (s && d < s) return false;
      if (e && d > e) return false;
      return true;
    });
    var kw = ($("ioExcludeSearch").value || "").trim();
    if (kw) filtered = filtered.filter(function(d) { return d.indexOf(kw) >= 0; });
    listDiv.innerHTML = "";
    var excSet = {};
    ioExcludedDates.forEach(function(d) { excSet[d] = true; });
    filtered.forEach(function(d) {
      var lbl = document.createElement("label");
      lbl.className = "mat-exclude-item";
      var cb = document.createElement("input");
      cb.type = "checkbox";
      cb.value = d;
      cb.checked = !!excSet[d];
      cb.addEventListener("change", function() {
        if (this.checked) {
          if (ioExcludedDates.indexOf(d) < 0) { ioExcludedDates.push(d); ioExcludedDates.sort(); }
        } else {
          var i = ioExcludedDates.indexOf(d);
          if (i >= 0) ioExcludedDates.splice(i, 1);
        }
        renderIoExcludedTags();
      });
      lbl.appendChild(cb);
      lbl.appendChild(document.createTextNode(" " + d));
      listDiv.appendChild(lbl);
    });
    if (!filtered.length) {
      listDiv.innerHTML = '<div style="padding:8px;color:#999;font-size:12px">无可选日期</div>';
    }
  }

  function drawIoFlowChart() {
    var svg = $("ioFlowChart");
    var wrap = $("ioFlowChartWrap");
    var W = Math.max(wrap.clientWidth, 600);
    var H = 360;
    var M = { l: 64, r: 24, t: 16, b: 40 };
    var iw = W - M.l - M.r, ih = H - M.t - M.b;
    svg.setAttribute("viewBox", "0 0 " + W + " " + H);
    svg.innerHTML = "";
    if (!ioFlowData) return;
    var sel = $("ioFlowSelect").value;
    var jinKey = sel === "total" ? "总进料" : sel + " 进";
    var chuKey = sel === "total" ? "总出料" : sel + " 出";
    var jinSeries = ioFlowData.series[jinKey] || {};
    var chuSeries = ioFlowData.series[chuKey] || {};
    var dates = getIoFlowFilteredDates();
    var n = dates.length;
    if (n === 0) {
      svg.innerHTML = '<text x="' + (W/2) + '" y="' + (H/2) + '" text-anchor="middle" fill="#999" font-size="14">无数据</text>';
      return;
    }
    var NS = "http://www.w3.org/2000/svg";
    function el(tag, attrs) {
      var e = document.createElementNS(NS, tag);
      for (var k in attrs) e.setAttribute(k, attrs[k]);
      return e;
    }
    var allVals = [];
    for (var i = 0; i < n; i++) {
      var jv = jinSeries[dates[i]], cv = chuSeries[dates[i]];
      if (jv != null && !isNaN(jv) && jv !== 0) allVals.push(jv);
      if (cv != null && !isNaN(cv) && cv !== 0) allVals.push(cv);
    }
    if (allVals.length === 0) {
      svg.innerHTML = '<text x="' + (W/2) + '" y="' + (H/2) + '" text-anchor="middle" fill="#999" font-size="14">无有效数据</text>';
      return;
    }
    var yMax = Math.max.apply(null, allVals) * 1.1;
    var yMin = 0;
    function x(i) { return M.l + (n === 1 ? iw / 2 : i / (n - 1) * iw); }
    function y(v) { return M.t + (1 - (v - yMin) / (yMax - yMin)) * ih; }
    var ticks = niceTicks(yMin, yMax, 6);
    ticks.forEach(function(t) {
      svg.appendChild(el("line", { x1: M.l, y1: y(t), x2: W - M.r, y2: y(t), stroke: "#e8eaed", "stroke-width": 1 }));
      var lbl = el("text", { x: M.l - 8, y: y(t) + 4, "text-anchor": "end", "font-size": 11, fill: "#666" });
      lbl.textContent = fmt(t, 0);
      svg.appendChild(lbl);
    });
    // jin line
    var jinPath = "";
    for (var i = 0; i < n; i++) {
      var v = jinSeries[dates[i]];
      if (v != null && !isNaN(v) && v !== 0) jinPath += (jinPath ? "L" : "M") + x(i).toFixed(1) + " " + y(v).toFixed(1) + " ";
    }
    if (jinPath) svg.appendChild(el("path", { d: jinPath.trim(), fill: "none", stroke: IO_COLORS.jin, "stroke-width": 2, "stroke-linejoin": "round", "stroke-linecap": "round" }));
    // chu line
    var chuPath = "";
    for (var i = 0; i < n; i++) {
      var v = chuSeries[dates[i]];
      if (v != null && !isNaN(v) && v !== 0) chuPath += (chuPath ? "L" : "M") + x(i).toFixed(1) + " " + y(v).toFixed(1) + " ";
    }
    if (chuPath) svg.appendChild(el("path", { d: chuPath.trim(), fill: "none", stroke: IO_COLORS.chu, "stroke-width": 2, "stroke-linejoin": "round", "stroke-linecap": "round" }));
    // X axis labels
    var step = Math.max(1, Math.ceil(n / 10));
    for (var i = 0; i < n; i += step) {
      var lbl = el("text", { x: x(i), y: H - M.b + 18, "text-anchor": "middle", "font-size": 10, fill: "#666" });
      lbl.textContent = dates[i].slice(5);
      svg.appendChild(el("line", { x1: x(i), y1: H - M.b, x2: x(i), y2: H - M.b + 4, stroke: "#ccc" }));
      svg.appendChild(lbl);
    }
    svg.appendChild(el("line", { x1: M.l, y1: M.t, x2: M.l, y2: H - M.b, stroke: "#999" }));
    svg.appendChild(el("line", { x1: M.l, y1: H - M.b, x2: W - M.r, y2: H - M.b, stroke: "#999" }));
    var yTitle = el("text", { x: 16, y: M.t + ih / 2, "text-anchor": "middle", "font-size": 12, fill: "#444", transform: "rotate(-90 16 " + (M.t + ih / 2) + ")" });
    yTitle.textContent = "物料量 (t)";
    svg.appendChild(yTitle);
    // Hover
    var tooltip = $("ioFlowTooltip");
    svg.onmousemove = function(evt) {
      var rect = svg.getBoundingClientRect();
      var mx = (evt.clientX - rect.left) * (W / rect.width);
      var idx = Math.round((mx - M.l) / (W - M.l - M.r) * (n - 1));
      if (idx < 0) idx = 0; if (idx > n - 1) idx = n - 1;
      tooltip.classList.remove("hidden");
      var jv = jinSeries[dates[idx]], cv = chuSeries[dates[idx]];
      var html = "<div><b>" + dates[idx] + "</b></div>";
      if (jv != null) html += "<div style='color:" + IO_COLORS.jin + "'>进料: <b>" + fmt(jv, 1) + "</b></div>";
      if (cv != null) html += "<div style='color:" + IO_COLORS.chu + "'>出料: <b>" + fmt(cv, 1) + "</b></div>";
      if (jv != null && cv != null && jv !== 0) html += "<div style='color:#666'>收率: <b>" + (cv / jv * 100).toFixed(2) + "%</b></div>";
      tooltip.innerHTML = html;
      var px = (x(idx) / W) * rect.width;
      tooltip.style.left = Math.min(rect.width - 180, px + 12) + "px";
      tooltip.style.top = "10px";
    };
    svg.onmouseleave = function() { tooltip.classList.add("hidden"); };
  }

  // 装置选择变更：重新获取收率数据并刷新所有图表（保留筛选条件）
  $("ioFlowSelect").addEventListener("change", function() {
    runAnalysis();
  });


  // ---- io-flow exclude dropdown ----
  (function() {
    var btn = $("ioExcludeBtn");
    var panel = $("ioExcludePanel");
    if (!btn || !panel) return;
    btn.addEventListener("click", function(e) {
      e.stopPropagation();
      panel.classList.toggle("hidden");
      if (!panel.classList.contains("hidden")) fillIoExcludeList();
    });
    document.addEventListener("click", function(e) {
      if (!panel.contains(e.target) && e.target !== btn) panel.classList.add("hidden");
    });
    var search = $("ioExcludeSearch");
    if (search) {
      search.addEventListener("input", fillIoExcludeList);
      search.addEventListener("click", function(e) { e.stopPropagation(); });
    }
    var clearBtn = $("ioExcludeClear");
    if (clearBtn) {
      clearBtn.addEventListener("click", function(e) {
        e.stopPropagation();
        ioExcludedDates = [];
        renderIoExcludedTags();
        fillIoExcludeList();
      });
    }
    var tagsDiv = $("ioExcludeTags");
    if (tagsDiv) {
      tagsDiv.addEventListener("click", function(e) {
        if (e.target.classList.contains("mat-exclude-x")) {
          var d = e.target.getAttribute("data-date");
          var i = ioExcludedDates.indexOf(d);
          if (i >= 0) ioExcludedDates.splice(i, 1);
          renderIoExcludedTags();
          fillIoExcludeList();
        }
      });
    }
  })();

  setupExcludeControls();
})();