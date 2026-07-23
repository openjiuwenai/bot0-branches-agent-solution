(() => {
  const $ = (id) => document.getElementById(id);
  const dialog = $("dialog");
  const sessionList = $("sessionList");
  const statusBadge = $("statusBadge");
  const footerStatus = $("footerStatus");
  const assertionList = $("assertionList");
  const assertionSummary = $("assertionSummary");
  const emptyHint = $("emptyHint");

  let queries = [];
  let sessions = [];
  let currentSessionId = null;
  let es = null;
  // 每个会话的消息缓存：sessionId -> [msg]，便于切换时重渲染
  const messagesBySession = {};
  // 每个会话的断言：sessionId -> [{scenarioId, ok, message}]
  const assertionsBySession = {};
  // 当前会话正在流式拼接的 assistant 气泡元素（按 invocationRef）
  const streamingBubbles = {};

  // ---------- 初始化 ----------
  async function init() {
    await loadQueries();
    await loadSessions();
    ensureSse();
    if (sessions.length === 0) {
      // 初始无会话，提示用户
      emptyHint.style.display = "";
    } else {
      selectSession(sessions[0].id);
    }
    bindButtons();
    pollStatus();
  }

  async function loadQueries() {
    try {
      const res = await fetch("/api/queries");
      const body = await res.json();
      queries = body.queries || [];
      renderQueryButtons();
    } catch (e) {
      footerStatus.textContent = "加载 query 目录失败: " + e.message;
    }
  }

  function renderQueryButtons() {
    const groups = { SERIAL: [], SOLO: [], DEMO: [] };
    queries.forEach((q) => groups[q.group].push(q));
    document.querySelectorAll(".query-group").forEach((g) => {
      const groupName = g.dataset.group;
      const container = g.querySelector(".query-buttons");
      container.innerHTML = "";
      (groups[groupName] || []).forEach((q) => {
        const btn = document.createElement("button");
        btn.type = "button";
        btn.className = "query-btn";
        btn.textContent = q.displayName;
        btn.title = q.description;
        btn.dataset.queryId = q.id;
        btn.addEventListener("click", () => sendSingle(q.id));
        container.appendChild(btn);
      });
    });
  }

  async function loadSessions() {
    try {
      const res = await fetch("/api/chat/sessions");
      const body = await res.json();
      sessions = body.sessions || [];
      renderSessionList();
    } catch (e) {
      // 忽略初始加载失败
    }
  }

  function renderSessionList() {
    sessionList.innerHTML = "";
    if (sessions.length === 0) {
      const li = document.createElement("li");
      li.className = "muted";
      li.textContent = "暂无会话";
      sessionList.appendChild(li);
      return;
    }
    sessions.forEach((s) => {
      const li = document.createElement("li");
      li.dataset.sessionId = s.id;
      if (s.id === currentSessionId) li.classList.add("active");
      const label = document.createElement("span");
      label.className = "session-label";
      label.textContent = s.label;
      const meta = document.createElement("span");
      meta.className = "session-meta";
      meta.textContent = s.conversationId;
      li.appendChild(label);
      li.appendChild(meta);
      li.addEventListener("click", () => selectSession(s.id));
      sessionList.appendChild(li);
    });
  }

  function selectSession(sessionId) {
    currentSessionId = sessionId;
    renderSessionList();
    renderDialog();
    renderAssertions();
  }

  // ---------- SSE ----------
  function ensureSse() {
    if (es && es.readyState !== EventSource.CLOSED) return;
    es = new EventSource("/api/chat/events");
    es.addEventListener("chat", (ev) => {
      try {
        handleChatMessage(JSON.parse(ev.data));
      } catch (e) {
        console.error("parse error", e);
      }
    });
    es.addEventListener("connected", () => {
      footerStatus.textContent = "已连接。选择 query 按钮发起调用。";
    });
    es.onerror = () => {
      footerStatus.textContent = "SSE 连接中断，正在自动重连…";
    };
  }

  function handleChatMessage(m) {
    // 会话相关消息：缓存并按需渲染
    if (m.sessionId) {
      if (m.type === "session_created") {
        sessions.push({
          id: m.sessionId,
          label: m.label || "新会话",
          conversationId: "",
          messageCount: 0,
        });
        // 拉取最新会话列表以补全 conversationId
        loadSessions();
        if (!currentSessionId) selectSession(m.sessionId);
        return;
      }

      if (!messagesBySession[m.sessionId]) messagesBySession[m.sessionId] = [];
      messagesBySession[m.sessionId].push(m);

      if (m.type === "assertion") {
        if (!assertionsBySession[m.sessionId]) assertionsBySession[m.sessionId] = [];
        assertionsBySession[m.sessionId].push(m);
      }

      if (m.sessionId === currentSessionId) {
        appendMessage(m);
        if (m.type === "assertion") renderAssertions();
      }
    }
  }

  // ---------- 对话流渲染 ----------
  function renderDialog() {
    dialog.innerHTML = "";
    streamingBubbles = {};
    const msgs = messagesBySession[currentSessionId] || [];
    if (msgs.length === 0) {
      dialog.appendChild(emptyHint);
      emptyHint.style.display = "";
      return;
    }
    emptyHint.style.display = "none";
    msgs.forEach(appendMessage);
  }

  function appendMessage(m) {
    if (emptyHint.parentNode === dialog) dialog.removeChild(emptyHint);
    emptyHint.style.display = "none";

    switch (m.type) {
      case "user":
        appendBubble("user", "你", m.text);
        break;
      case "assistant_delta":
        appendStreamingDelta(m.invocationRef, m.text);
        break;
      case "assistant_final":
        finalizeStreaming(m.invocationRef, m.text);
        break;
      case "tool_call":
        appendToolCard(m.toolCallId, m.text, m.arguments, null);
        break;
      case "tool_result":
        appendToolResult(m.toolCallId, m.outcome, m.payload, m.errorCode, m.message);
        break;
      case "status":
        appendStatus(m.state, m.detail);
        break;
      case "error":
        appendBubble("error", "错误", "[" + (m.errorCode || "?") + "] " + (m.message || ""));
        break;
      case "info":
        appendBubble("info", "", m.message);
        break;
      case "assertion":
        appendAssertionInline(m.scenarioId, m.ok, m.message);
        break;
      case "scenario_start":
        appendScenarioMarker(m.scenarioId + " 开始：" + m.label, "");
        break;
      case "scenario_end":
        appendScenarioMarker(
          m.scenarioId + (m.ok ? " 通过" : " 失败"),
          m.ok ? "end-ok" : "end-fail"
        );
        break;
      default:
        appendBubble("info", "", JSON.stringify(m));
    }
    dialog.scrollTop = dialog.scrollHeight;
  }

  function appendBubble(role, roleLabel, text) {
    const wrap = document.createElement("div");
    wrap.className = "msg " + role;
    if (roleLabel) {
      const r = document.createElement("div");
      r.className = "role";
      r.textContent = roleLabel;
      wrap.appendChild(r);
    }
    const bubble = document.createElement("div");
    bubble.className = "bubble";
    bubble.textContent = text;
    wrap.appendChild(bubble);
    dialog.appendChild(wrap);
  }

  function appendStreamingDelta(invocationRef, text) {
    let bubble = streamingBubbles[invocationRef];
    if (!bubble) {
      const wrap = document.createElement("div");
      wrap.className = "msg bot";
      const r = document.createElement("div");
      r.className = "role";
      r.textContent = "assistant";
      wrap.appendChild(r);
      bubble = document.createElement("div");
      bubble.className = "bubble";
      wrap.appendChild(bubble);
      dialog.appendChild(wrap);
      streamingBubbles[invocationRef] = bubble;
    }
    bubble.textContent += text;
    dialog.scrollTop = dialog.scrollHeight;
  }

  function finalizeStreaming(invocationRef, text) {
    const bubble = streamingBubbles[invocationRef];
    if (bubble) {
      bubble.textContent = text;
      delete streamingBubbles[invocationRef];
    } else {
      // 没有收到 delta，直接追加最终回复
      appendBubble("bot", "assistant", text);
    }
  }

  function appendStatus(state, detail) {
    const wrap = document.createElement("div");
    wrap.className = "msg status";
    const bubble = document.createElement("div");
    bubble.className = "bubble";
    const tag = document.createElement("span");
    tag.className = "state-tag";
    tag.textContent = state;
    bubble.appendChild(tag);
    bubble.appendChild(document.createTextNode(detail || ""));
    wrap.appendChild(bubble);
    dialog.appendChild(wrap);
  }

  function appendToolCard(toolCallId, toolName, argumentsObj, result) {
    const wrap = document.createElement("div");
    wrap.className = "msg tool";
    wrap.dataset.toolCallId = toolCallId;
    const card = document.createElement("div");
    card.className = "tool-card";
    const head = document.createElement("div");
    head.className = "tool-card-head";
    const icon = document.createElement("span");
    icon.className = "tool-icon";
    icon.textContent = "🔧";
    const name = document.createElement("span");
    name.className = "tool-name";
    name.textContent = toolName;
    const toggle = document.createElement("span");
    toggle.className = "tool-toggle";
    head.appendChild(icon);
    head.appendChild(name);
    head.appendChild(toggle);

    const body = document.createElement("div");
    body.className = "tool-card-body";
    if (argumentsObj) {
      const sec = document.createElement("div");
      sec.className = "tool-section";
      const lbl = document.createElement("div");
      lbl.className = "tool-section-label";
      lbl.textContent = "入参";
      const val = document.createElement("div");
      val.textContent = JSON.stringify(argumentsObj, null, 2);
      sec.appendChild(lbl);
      sec.appendChild(val);
      body.appendChild(sec);
    }
    if (result) {
      const sec = document.createElement("div");
      sec.className = "tool-section";
      const lbl = document.createElement("div");
      lbl.className = "tool-section-label";
      lbl.textContent = "结果";
      const outcome = document.createElement("span");
      outcome.className = "outcome-badge " + (result.outcome || "OK");
      outcome.textContent = result.outcome || "OK";
      const val = document.createElement("div");
      val.style.marginTop = "0.3rem";
      val.textContent = result.payload
        ? JSON.stringify(result.payload, null, 2)
        : result.message || "(无结果)";
      lbl.appendChild(outcome);
      sec.appendChild(lbl);
      sec.appendChild(val);
      body.appendChild(sec);
    }

    head.addEventListener("click", () => card.classList.toggle("expanded"));
    card.appendChild(head);
    card.appendChild(body);
    wrap.appendChild(card);
    dialog.appendChild(wrap);
  }

  function appendToolResult(toolCallId, outcome, payload, errorCode, message) {
    // 找到已有的 tool_call 卡片，往 body 里追加结果区
    const cardWrap = dialog.querySelector(
      '.msg.tool[data-tool-call-id="' + cssEscape(toolCallId) + '"]'
    );
    if (cardWrap) {
      const card = cardWrap.querySelector(".tool-card");
      const body = card.querySelector(".tool-card-body");
      const head = card.querySelector(".tool-card-head");
      // 在头部追加 outcome 徽标
      if (outcome) {
        const badge = document.createElement("span");
        badge.className = "outcome-badge " + outcome;
        badge.textContent = outcome;
        badge.style.marginLeft = "0.4rem";
        head.appendChild(badge);
      }
      // 追加结果区
      const sec = document.createElement("div");
      sec.className = "tool-section";
      const lbl = document.createElement("div");
      lbl.className = "tool-section-label";
      lbl.textContent = "结果";
      const val = document.createElement("div");
      val.style.marginTop = "0.3rem";
      val.textContent = payload
        ? JSON.stringify(payload, null, 2)
        : (errorCode ? "[" + errorCode + "] " : "") + (message || "(无结果)");
      sec.appendChild(lbl);
      sec.appendChild(val);
      body.appendChild(sec);
      card.classList.add("expanded"); // 有结果后默认展开
    } else {
      // 没找到对应卡片，单独渲染一个结果卡片
      appendToolCard(toolCallId, "(结果)", null, { outcome, payload, message });
    }
    dialog.scrollTop = dialog.scrollHeight;
  }

  function appendAssertionInline(scenarioId, ok, message) {
    const wrap = document.createElement("div");
    wrap.className = "assertion-inline " + (ok ? "ok" : "fail");
    wrap.textContent = (ok ? "✓ " : "✗ ") + message;
    wrap.title = "[" + scenarioId + "] " + message;
    wrap.addEventListener("click", () => highlightAssertion(scenarioId, message));
    dialog.appendChild(wrap);
  }

  function appendScenarioMarker(text, extraClass) {
    const div = document.createElement("div");
    div.className = "scenario-marker " + (extraClass || "");
    div.textContent = text;
    dialog.appendChild(div);
  }

  function highlightAssertion(scenarioId, message) {
    // 在右栏找到对应断言并高亮
    const items = assertionList.querySelectorAll("li");
    items.forEach((li) => li.classList.remove("highlight"));
    items.forEach((li) => {
      if (li.dataset.scenarioId === scenarioId && li.dataset.message === message) {
        li.classList.add("highlight");
        li.scrollIntoView({ block: "nearest", behavior: "smooth" });
      }
    });
  }

  // ---------- 断言右栏 ----------
  function renderAssertions() {
    const asserts = assertionsBySession[currentSessionId] || [];
    assertionList.innerHTML = "";
    if (asserts.length === 0) {
      assertionSummary.innerHTML = '<p class="muted">尚未运行任何场景。</p>';
      return;
    }
    const okCount = asserts.filter((a) => a.ok).length;
    const failCount = asserts.length - okCount;
    const byScenario = {};
    asserts.forEach((a) => {
      if (!byScenario[a.scenarioId]) byScenario[a.scenarioId] = { ok: 0, fail: 0 };
      if (a.ok) byScenario[a.scenarioId].ok++;
      else byScenario[a.scenarioId].fail++;
    });
    let summaryHtml =
      '<p class="summary-line">共 <strong>' +
      asserts.length +
      "</strong> 条断言：" +
      '<span style="color:var(--ok)">' +
      okCount +
      " 通过</span> / " +
      '<span style="color:var(--fail)">' +
      failCount +
      " 失败</span></p>";
    Object.keys(byScenario).forEach((sid) => {
      const s = byScenario[sid];
      summaryHtml +=
        '<p class="summary-line muted">' + sid + ": " + s.ok + "✓ " + s.fail + "✗</p>";
    });
    assertionSummary.innerHTML = summaryHtml;

    asserts.forEach((a) => {
      const li = document.createElement("li");
      li.className = a.ok ? "ok" : "fail";
      li.dataset.scenarioId = a.scenarioId;
      li.dataset.message = a.message;
      const sid = document.createElement("span");
      sid.className = "assertion-scenario";
      sid.textContent = a.scenarioId;
      li.appendChild(sid);
      li.appendChild(document.createTextNode(a.message));
      assertionList.appendChild(li);
    });
  }

  // ---------- 发送 ----------
  async function sendSingle(queryId) {
    if (!currentSessionId) {
      // 自动新建会话
      await newSession(queryId);
    }
    setBadge("running", "运行中");
    setButtonsDisabled(true);
    try {
      const res = await fetch("/api/chat/send", {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({ queryId: queryId, sessionId: currentSessionId }),
      });
      const body = await res.json();
      if (!res.ok || !body.accepted) {
        footerStatus.textContent = "无法启动: " + (body.message || res.status);
        setBadge("fail", "启动失败");
      }
    } catch (e) {
      footerStatus.textContent = "请求失败: " + e.message;
      setBadge("fail", "请求失败");
    }
  }

  async function sendSerial(queryIds) {
    // 串行发到同一会话（若当前无会话则新建一个 label=serial）
    let sid = currentSessionId;
    if (!sid) {
      sid = await newSession("serial");
    }
    setBadge("running", "串行运行中");
    setButtonsDisabled(true);
    try {
      const res = await fetch("/api/chat/send-serial", {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({ queryIds: queryIds, sessionId: sid }),
      });
      const body = await res.json();
      if (!res.ok || !body.accepted) {
        footerStatus.textContent = "无法启动串行: " + (body.message || res.status);
        setBadge("fail", "启动失败");
      }
    } catch (e) {
      footerStatus.textContent = "请求失败: " + e.message;
      setBadge("fail", "请求失败");
    }
  }

  async function newSession(label) {
    try {
      const res = await fetch("/api/chat/new-session", {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({ label: label || "新会话" }),
      });
      const body = await res.json();
      sessions.push({
        id: body.sessionId,
        label: body.label,
        conversationId: "",
        messageCount: 0,
      });
      await loadSessions();
      selectSession(body.sessionId);
      return body.sessionId;
    } catch (e) {
      footerStatus.textContent = "新建会话失败: " + e.message;
      return null;
    }
  }

  // ---------- 状态轮询 ----------
  async function pollStatus() {
    try {
      const res = await fetch("/api/status");
      const body = await res.json();
      if (!body.running) {
        if (statusBadge.classList.contains("running")) {
          // 从运行中转为空闲，判定结果
          const asserts = assertionsBySession[currentSessionId] || [];
          const anyFail = asserts.some((a) => !a.ok);
          if (asserts.length > 0) {
            setBadge(anyFail ? "fail" : "pass", anyFail ? "存在失败" : "全部通过");
          } else {
            setBadge("idle", "待命");
          }
          setButtonsDisabled(false);
        }
      }
    } catch (e) {
      // 忽略
    }
    setTimeout(pollStatus, 800);
  }

  // ---------- 辅助 ----------
  function setBadge(state, text) {
    statusBadge.className = "badge " + state;
    statusBadge.textContent = text;
  }

  function setButtonsDisabled(disabled) {
    document.querySelectorAll(".query-btn, .serial-all").forEach((b) => {
      b.disabled = disabled;
    });
  }

  function bindButtons() {
    $("newSessionBtn").addEventListener("click", () => newSession("新会话"));
    $("clearAssertionsBtn").addEventListener("click", () => {
      if (currentSessionId) {
        assertionsBySession[currentSessionId] = [];
        renderAssertions();
      }
    });
    document.querySelectorAll(".serial-all").forEach((btn) => {
      btn.addEventListener("click", () => {
        const ids = btn.dataset.serial.split(",").map((s) => s.trim()).filter(Boolean);
        sendSerial(ids);
      });
    });
  }

  // CSS.escape 兜底（老旧浏览器）
  function cssEscape(s) {
    if (window.CSS && CSS.escape) return CSS.escape(s);
    return String(s).replace(/[^a-zA-Z0-9_-]/g, "\\$&");
  }

  init();
})();
