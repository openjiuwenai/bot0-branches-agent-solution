(() => {
  const $ = (id) => document.getElementById(id);
  const scenarioList = $("scenarioList");
  const assertionList = $("assertionList");
  const summaryBar = $("summaryBar");
  const statusBadge = $("statusBadge");
  const footerStatus = $("footerStatus");
  const gatewayInfo = $("gatewayInfo");
  const detailFilter = $("detailFilter");

  // 场景目录来自后端注册表，前端不硬编码任何场景 id
  let scenarios = [];
  // scenarioId -> { checks: [{ok, message}], state: 'idle'|'running'|'pass'|'fail' }
  let results = {};
  // 明细区当前只看某个场景；null 表示全部
  let focused = null;
  let running = false;
  let es = null;

  async function init() {
    await loadScenarios();
    ensureSse();
    bindButtons();
    pollStatus();
  }

  async function loadScenarios() {
    try {
      const res = await fetch("/api/scenarios");
      const body = await res.json();
      scenarios = body.scenarios || [];
      scenarios.forEach((s) => {
        results[s.id] = { checks: [], state: "idle" };
      });
      renderScenarios();
    } catch (e) {
      footerStatus.textContent = "加载场景目录失败：" + e.message;
    }
  }

  // ---------- 场景列表 ----------
  function renderScenarios() {
    scenarioList.innerHTML = "";
    scenarios.forEach((s) => {
      const r = results[s.id];
      const row = document.createElement("div");
      row.className = "scenario " + r.state + (focused === s.id ? " active" : "");
      row.dataset.scenarioId = s.id;

      const cb = document.createElement("input");
      cb.type = "checkbox";
      cb.dataset.scenarioId = s.id;
      cb.checked = isChecked(s.id);
      cb.addEventListener("click", (ev) => ev.stopPropagation());
      cb.addEventListener("change", () => setChecked(s.id, cb.checked));

      const body = document.createElement("div");
      body.className = "scenario-body";
      const titleRow = document.createElement("div");
      titleRow.className = "scenario-title";
      const sid = document.createElement("span");
      sid.className = "scenario-id";
      sid.textContent = s.id;
      const cat = document.createElement("span");
      cat.className = "scenario-category";
      cat.textContent = s.category;
      titleRow.appendChild(sid);
      titleRow.appendChild(cat);
      const summary = document.createElement("div");
      summary.className = "scenario-summary";
      summary.textContent = s.summary;
      summary.title = s.title;
      body.appendChild(titleRow);
      body.appendChild(summary);

      const counts = document.createElement("div");
      counts.className = "scenario-counts";
      counts.innerHTML = countsHtml(r);

      row.appendChild(cb);
      row.appendChild(body);
      row.appendChild(counts);
      row.addEventListener("click", () => focusScenario(s.id));
      scenarioList.appendChild(row);
    });
  }

  function countsHtml(r) {
    if (r.state === "running") return '<span class="c-idle">运行中…</span>';
    if (r.checks.length === 0) return '<span class="c-idle">—</span>';
    const ok = r.checks.filter((c) => c.ok).length;
    const fail = r.checks.length - ok;
    let html = '<span class="c-ok">' + ok + " ✓</span>";
    if (fail > 0) html += ' <span class="c-fail">' + fail + " ✗</span>";
    return html;
  }

  // 勾选状态存在 DOM 之外，避免 renderScenarios 重绘时丢失
  const checkedIds = new Set();
  function isChecked(id) {
    return checkedIds.has(id);
  }
  function setChecked(id, on) {
    if (on) checkedIds.add(id);
    else checkedIds.delete(id);
  }

  function focusScenario(id) {
    focused = focused === id ? null : id;
    renderScenarios();
    renderDetail();
  }

  // ---------- 明细 ----------
  function renderDetail() {
    const shown = focused ? [focused] : scenarios.map((s) => s.id);
    detailFilter.textContent = focused ? "仅看 " + focused + "（点场景卡片取消）" : "";

    let total = 0;
    let ok = 0;
    shown.forEach((id) => {
      const r = results[id];
      total += r.checks.length;
      ok += r.checks.filter((c) => c.ok).length;
    });

    if (total === 0) {
      summaryBar.innerHTML = running
        ? '<p class="muted">运行中…</p>'
        : '<p class="muted">尚未运行。点右上角「运行全部」，或勾选若干场景后「运行选中」。</p>';
    } else {
      const fail = total - ok;
      summaryBar.innerHTML =
        '<p class="summary-line">共 <strong>' + total + "</strong> 条断言：" +
        '<span style="color:var(--ok)">' + ok + " 通过</span> / " +
        '<span style="color:var(--fail)">' + fail + " 失败</span></p>";
    }

    assertionList.innerHTML = "";
    shown.forEach((id) => {
      const r = results[id];
      if (r.checks.length === 0 && r.state === "idle") return;
      const spec = scenarios.find((s) => s.id === id);
      const marker = document.createElement("li");
      marker.className = "marker";
      marker.textContent = spec ? spec.title : id;
      assertionList.appendChild(marker);
      r.checks.forEach((c) => {
        const li = document.createElement("li");
        li.className = c.kind === "info" ? "info" : c.ok ? "ok" : "fail";
        const sid = document.createElement("span");
        sid.className = "assertion-scenario";
        sid.textContent = id;
        li.appendChild(sid);
        li.appendChild(document.createTextNode(c.message));
        assertionList.appendChild(li);
      });
    });
  }

  // ---------- SSE ----------
  function ensureSse() {
    if (es && es.readyState !== EventSource.CLOSED) return;
    es = new EventSource("/api/events");
    es.addEventListener("progress", (ev) => {
      try {
        handleProgress(JSON.parse(ev.data));
      } catch (e) {
        console.error("parse error", e);
      }
    });
    es.addEventListener("info", (ev) => {
      try {
        const m = JSON.parse(ev.data);
        if (m.message) footerStatus.textContent = m.message;
      } catch (e) {
        /* 忽略 */
      }
    });
    es.addEventListener("connected", () => {
      footerStatus.textContent = "已连接。";
    });
    es.onerror = () => {
      footerStatus.textContent = "事件流中断，正在自动重连…";
    };
  }

  function handleProgress(e) {
    const id = e.scenarioId;
    switch (e.kind) {
      case "RUN_START":
        gatewayInfo.textContent = "网关：" + e.message;
        break;
      case "SCENARIO_START":
        // 重跑同一场景时先清空旧结论，避免新旧断言混在一起
        results[id] = { checks: [], state: "running" };
        renderScenarios();
        renderDetail();
        break;
      case "CHECK":
        ensureResult(id).checks.push({ ok: e.ok, message: e.message });
        renderScenarios();
        renderDetail();
        break;
      case "INFO":
        if (id) {
          ensureResult(id).checks.push({ ok: true, kind: "info", message: e.message });
          renderDetail();
        } else if (e.message) {
          footerStatus.textContent = e.message;
        }
        break;
      case "SCENARIO_END":
        ensureResult(id).state = e.ok ? "pass" : "fail";
        renderScenarios();
        break;
      case "RUN_END":
        footerStatus.textContent = e.message;
        setBadge(e.ok ? "pass" : "fail", e.ok ? "全部通过" : "存在失败");
        break;
      default:
        break;
    }
  }

  function ensureResult(id) {
    if (!results[id]) results[id] = { checks: [], state: "running" };
    return results[id];
  }

  // ---------- 运行 ----------
  async function run(ids) {
    const query = ids && ids.length ? "?ids=" + encodeURIComponent(ids.join(",")) : "";
    // 只清空本次要跑的场景，未选中的保留上一次结论
    const targets = ids && ids.length ? ids : scenarios.map((s) => s.id);
    targets.forEach((id) => {
      results[id] = { checks: [], state: "idle" };
    });
    renderScenarios();
    renderDetail();

    setBadge("running", "运行中");
    setControlsDisabled(true);
    try {
      const res = await fetch("/api/run" + query, { method: "POST" });
      const body = await res.json();
      if (!res.ok || !body.accepted) {
        footerStatus.textContent = "无法启动：" + (body.message || res.status);
        setBadge("idle", "待命");
        setControlsDisabled(false);
      } else {
        running = true;
      }
    } catch (e) {
      footerStatus.textContent = "请求失败：" + e.message;
      setBadge("idle", "待命");
      setControlsDisabled(false);
    }
  }

  async function pollStatus() {
    try {
      const res = await fetch("/api/status");
      const body = await res.json();
      const now = !!body.running;
      if (!now && running) {
        // 由运行中转为空闲：解锁按钮（结论徽标已由 RUN_END 设置）
        setControlsDisabled(false);
      }
      running = now;
    } catch (e) {
      /* 忽略轮询失败 */
    }
    setTimeout(pollStatus, 400);
  }

  // ---------- 辅助 ----------
  function setBadge(state, text) {
    statusBadge.className = "badge " + state;
    statusBadge.textContent = text;
  }

  function setControlsDisabled(disabled) {
    $("runAllBtn").disabled = disabled;
    $("runSelectedBtn").disabled = disabled;
  }

  function bindButtons() {
    $("runAllBtn").addEventListener("click", () => run([]));
    $("runSelectedBtn").addEventListener("click", () => {
      const ids = [...checkedIds];
      if (ids.length === 0) {
        footerStatus.textContent = "请先勾选至少一个场景，或直接点「运行全部」。";
        return;
      }
      // 保持注册表顺序，避免按勾选先后乱序执行
      run(scenarios.map((s) => s.id).filter((id) => checkedIds.has(id)));
    });
    $("selectAllBtn").addEventListener("click", () => {
      scenarios.forEach((s) => checkedIds.add(s.id));
      renderScenarios();
    });
    $("selectNoneBtn").addEventListener("click", () => {
      checkedIds.clear();
      renderScenarios();
    });
  }

  init();
})();
