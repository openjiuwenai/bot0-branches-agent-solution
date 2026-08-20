/**
 * RAG 知识质检前端交互
 */

(function () {
  'use strict';

  const API_BASE = 'api/qc';
  const ragKqcPollIntervalMs = 1500;
  const ragKqcMaxDomLogLines = 500;
  const ragKqcPersistKey = 'dfw_ragwheel_kqc_job';

  let ragKqcRunning = false;
  let ragKqcRestoring = false;
  let ragKqcLastErrorCount = 0;
  let ragKqcCurrentQcJobId = '';
  let ragKqcLastQcJobId = '';
  let ragKqcLastJobId = '';
  let ragKqcLastJobKind = '';
  let ragKqcPollTimer = null;
  let ragKqcLastLogIndex = 0;
  let ragKqcQuestionCheckerSnapshot = null;

  var ragKqcLlmConfigs = [];
  var ragKqcEmbeddingConfigs = [];

  var ragKqcCheckerControlIds = [
    'ragKqcChkFormat', 'ragKqcChkCompliance', 'ragKqcChkDuplicate',
    'ragKqcChkConflict', 'ragKqcChkSemantic', 'ragKqcChkLlm'
  ];

  document.addEventListener('DOMContentLoaded', function () {
    initRagKnowledgeQcModule();
  });

  function initRagKnowledgeQcModule() {
    var root = document.getElementById('ragKqcPanelQc');
    if (!root) return;
    if (root.dataset.kqcInited === '1') return;
    root.dataset.kqcInited = '1';

    document.querySelectorAll('.rag-kqc-tab').forEach(function (tab) {
      tab.addEventListener('click', function () {
        ragKqcSwitchTab(this.getAttribute('data-kqc-tab'));
      });
    });

    ragKqcBindFileBrowse('ragKqcBrowseInputBtn', 'ragKqcInputExcelFile', 'ragKqcInputExcel');
    ragKqcBindFileBrowse('ragKqcBrowseIngestQBtn', 'ragKqcIngestQuestionFile', 'ragKqcIngestQuestionExcel');
    ragKqcBindFileBrowse('ragKqcBrowseIngestIBtn', 'ragKqcIngestIntentFile', 'ragKqcIngestIntentExcel');

    var startBtn = document.getElementById('ragKqcStartBtn');
    if (startBtn) startBtn.addEventListener('click', ragKqcStartQc);
    var stopBtn = document.getElementById('ragKqcStopBtn');
    if (stopBtn) stopBtn.addEventListener('click', ragKqcStopQc);
    var exportQc = document.getElementById('ragKqcExportQcBtn');
    if (exportQc) exportQc.addEventListener('click', ragKqcExportQcResult);
    var ingestQ = document.getElementById('ragKqcIngestQBtn');
    if (ingestQ) ingestQ.addEventListener('click', function () { ragKqcStartIngest('question'); });
    var ingestI = document.getElementById('ragKqcIngestIBtn');
    if (ingestI) ingestI.addEventListener('click', function () { ragKqcStartIngest('intent'); });
    var refreshKb = document.getElementById('ragKqcRefreshKbCountBtn');
    if (refreshKb) refreshKb.addEventListener('click', ragKqcRefreshKbCount);
    var exportKb = document.getElementById('ragKqcExportKbBtn');
    if (exportKb) exportKb.addEventListener('click', ragKqcStartExportKb);

    var loadRules = document.getElementById('ragKqcLoadRulesBtn');
    if (loadRules) loadRules.addEventListener('click', ragKqcLoadRules);

    var loadWl = document.getElementById('ragKqcLoadWordlistsBtn');
    if (loadWl) loadWl.addEventListener('click', ragKqcLoadWordlists);

    var loadEnv = document.getElementById('ragKqcLoadEnvBtn');
    if (loadEnv) loadEnv.addEventListener('click', ragKqcLoadEnv);

    var llmSel = document.getElementById('ragKqcLlmConfigSelect');
    if (llmSel) llmSel.addEventListener('change', function () { ragKqcApplySelectedConfig('llm', this.value); });
    var embSel = document.getElementById('ragKqcEmbeddingConfigSelect');
    if (embSel) embSel.addEventListener('change', function () { ragKqcApplySelectedConfig('embedding', this.value); });

    var retryBtn = document.getElementById('ragKqcRetryErrorsBtn');
    if (retryBtn) retryBtn.addEventListener('click', ragKqcRetryErrorsQc);

    document.querySelectorAll('input[name="ragKqcTask"]').forEach(function (r) {
      r.addEventListener('change', ragKqcOnTaskChange);
    });
    ['ragKqcChkDuplicate', 'ragKqcChkConflict', 'ragKqcChkSemantic'].forEach(function (id) {
      var el = document.getElementById(id);
      if (el) el.addEventListener('change', ragKqcSyncLlmVisibility);
    });
    var prodMode = document.getElementById('ragKqcModeProduction');
    if (prodMode) prodMode.addEventListener('change', ragKqcSyncIntentFilterVisibility);
    ragKqcOnTaskChange();
    ragKqcSyncIntentFilterVisibility();
    ragKqcLoadRules();
    ragKqcLoadEnv(true);
    ragKqcLoadWordlists(true);
    ragKqcRefreshKbCount();
    ragKqcRestoreActiveQcJob();
  }

  function ragKqcSwitchTab(tabId) {
    if (tabId === 'env') {
      ragKqcLoadEnv(true);
    }
    document.querySelectorAll('.rag-kqc-tab').forEach(function (t) {
      var active = t.getAttribute('data-kqc-tab') === tabId;
      t.classList.toggle('active', active);
      t.setAttribute('aria-selected', active ? 'true' : 'false');
    });
    document.querySelectorAll('.rag-kqc-panel').forEach(function (p) {
      p.classList.toggle('active', p.getAttribute('data-kqc-panel') === tabId);
    });
  }

  function ragKqcBindFileBrowse(btnId, fileInputId, textInputId) {
    var btn = document.getElementById(btnId);
    var fileInput = document.getElementById(fileInputId);
    var textInput = document.getElementById(textInputId);
    if (!btn || !fileInput || !textInput) return;
    btn.addEventListener('click', function () { fileInput.click(); });
    fileInput.addEventListener('change', function () {
      if (fileInput.files && fileInput.files.length) {
        textInput.value = fileInput.files[0].name;
        textInput.dataset.fullPath = fileInput.files[0].name;
      }
    });
  }

  function ragKqcAppendLog(line) {
    var log = document.getElementById('ragKqcLog');
    if (!log) return;
    var ts = new Date().toLocaleTimeString('zh-CN', { hour12: false });
    var text = String(line == null ? '' : line).replace(/\r\n/g, '\n').replace(/\r/g, '\n');
    log.value += '[' + ts + '] ' + text + '\n';
    var lines = log.value.split('\n');
    if (lines.length > ragKqcMaxDomLogLines) {
      log.value = lines.slice(-ragKqcMaxDomLogLines).join('\n');
    }
    log.scrollTop = log.scrollHeight;
  }

  function ragKqcSetProgress(current, total) {
    var bar = document.getElementById('ragKqcProgressBar');
    var text = document.getElementById('ragKqcProgressText');
    var pct = total > 0 ? Math.round((current / total) * 100) : 0;
    if (bar) {
      bar.style.width = pct + '%';
      bar.setAttribute('aria-valuenow', String(pct));
    }
    if (text) text.textContent = current + ' / ' + total;
  }

  function ragKqcIsIngestJobKind(kind) {
    return kind === 'ingest_question' || kind === 'ingest_intent';
  }

  function ragKqcShowKbProgress(show) {
    var wrap = document.getElementById('ragKqcKbProgressWrap');
    if (!wrap) return;
    if (show) {
      wrap.removeAttribute('hidden');
    } else {
      wrap.setAttribute('hidden', 'hidden');
    }
  }

  function ragKqcResetKbProgress() {
    ragKqcSetKbProgress(0, 0, 'prepare', '');
    var st = document.getElementById('ragKqcKbStatus');
    if (st) st.textContent = '';
  }

  function ragKqcKbPhaseTitle(phase) {
    if (phase === 'embedding') return 'Embedding';
    if (phase === 'chroma') return '写入向量库';
    if (phase === 'prepare') return '准备导入';
    return '导入中';
  }

  function ragKqcSetKbProgress(current, total, phase, label) {
    ragKqcShowKbProgress(true);
    var phaseEl = document.getElementById('ragKqcKbProgressPhase');
    if (phaseEl) phaseEl.textContent = ragKqcKbPhaseTitle(phase || '');
    var bar = document.getElementById('ragKqcKbProgressBar');
    var text = document.getElementById('ragKqcKbProgressText');
    var cur = parseInt(current, 10) || 0;
    var tot = parseInt(total, 10) || 0;
    var pct = tot > 0 ? Math.round((cur / tot) * 100) : 0;
    if (bar) {
      bar.style.width = pct + '%';
      bar.setAttribute('aria-valuenow', String(pct));
      bar.classList.toggle('is-animated', tot > 0 && cur < tot);
    }
    if (text) text.textContent = '已完成 ' + cur + ' / 总计 ' + tot;
    var st = document.getElementById('ragKqcKbStatus');
    if (st) st.textContent = label || '';
  }

  function ragKqcSetIngestButtonsDisabled(disabled) {
    ['ragKqcIngestQBtn', 'ragKqcIngestIBtn'].forEach(function (id) {
      var btn = document.getElementById(id);
      if (btn) btn.disabled = !!disabled;
    });
  }

  function ragKqcSetStatus(msg) {
    var el = document.getElementById('ragKqcQcStatus');
    if (el) el.textContent = msg;
  }

  function ragKqcCheckerLabelForInput(inputId) {
    var el = document.getElementById(inputId);
    return el ? el.closest('.rag-kqc-check') : null;
  }

  function ragKqcSetCheckerDisabled(inputId, disabled) {
    var input = document.getElementById(inputId);
    var label = ragKqcCheckerLabelForInput(inputId);
    if (input) input.disabled = !!disabled;
    if (label) label.classList.toggle('rag-kqc-check--disabled', !!disabled);
  }

  function ragKqcCheckerActive(inputId) {
    var el = document.getElementById(inputId);
    return !!(el && el.checked && !el.disabled);
  }

  function ragKqcSetSubOptionVisible(wrapId, show, inputId) {
    var wrap = document.getElementById(wrapId);
    if (!wrap) return;
    if (show) {
      wrap.removeAttribute('hidden');
    } else {
      wrap.setAttribute('hidden', 'hidden');
      var input = inputId ? document.getElementById(inputId) : null;
      if (input) input.checked = false;
    }
  }

  function ragKqcSyncLlmVisibility() {
    var dupLlm = document.getElementById('ragKqcChkDupLlm');
    if (dupLlm) dupLlm.disabled = !(ragKqcCheckerActive('ragKqcChkDuplicate') || ragKqcCheckerActive('ragKqcChkConflict'));
    var llm = document.getElementById('ragKqcChkLlm');
    if (llm) llm.disabled = !ragKqcCheckerActive('ragKqcChkSemantic');
  }

  function ragKqcOnTaskChange() {
    var taskEl = document.querySelector('input[name="ragKqcTask"]:checked');
    var isIntent = taskEl && taskEl.value === 'intent';

    var intentLlmWrap = document.getElementById('ragKqcChkIntentLlmWrap');
    if (intentLlmWrap) intentLlmWrap.classList.toggle('hidden', !isIntent);

    ['ragKqcChkFormatWrap', 'ragKqcChkComplianceWrap', 'ragKqcChkConflictWrap',
     'ragKqcChkDupLlmWrap', 'ragKqcChkSemanticWrap', 'ragKqcChkLlmWrap'].forEach(function (id) {
      var el = document.getElementById(id);
      if (el) el.classList.toggle('hidden', isIntent);
    });

    if (isIntent) {
      if (!ragKqcQuestionCheckerSnapshot) {
        ragKqcQuestionCheckerSnapshot = {};
        ragKqcCheckerControlIds.forEach(function (id) {
          var el = document.getElementById(id);
          if (el) ragKqcQuestionCheckerSnapshot[id] = el.checked;
        });
      }
      ragKqcCheckerControlIds.forEach(function (id) {
        if (id === 'ragKqcChkDuplicate') {
          var dup = document.getElementById(id);
          if (dup) dup.checked = true;
          ragKqcSetCheckerDisabled(id, false);
        } else {
          var el = document.getElementById(id);
          if (el) el.checked = false;
          ragKqcSetCheckerDisabled(id, true);
        }
      });
    } else {
      ragKqcCheckerControlIds.forEach(function (id) {
        ragKqcSetCheckerDisabled(id, false);
        var el = document.getElementById(id);
        if (el && ragKqcQuestionCheckerSnapshot && Object.prototype.hasOwnProperty.call(ragKqcQuestionCheckerSnapshot, id)) {
          el.checked = ragKqcQuestionCheckerSnapshot[id];
        }
      });
      ragKqcQuestionCheckerSnapshot = null;
    }
    ragKqcSyncLlmVisibility();
  }

  function ragKqcSyncIntentFilterVisibility() {
    var prodMode = document.getElementById('ragKqcModeProduction');
    var filterArea = document.getElementById('ragKqcIntentFilterArea');
    if (!filterArea) return;
    var show = !!(prodMode && prodMode.checked);
    filterArea.classList.toggle('hidden', !show);
  }

  function ragKqcCollectCheckerConfig() {
    var taskEl = document.querySelector('input[name="ragKqcTask"]:checked');
    var isIntent = taskEl && taskEl.value === 'intent';
    if (isIntent) {
      return {
        format: false,
        compliance: false,
        duplicate: !!(document.getElementById('ragKqcChkDuplicate') && document.getElementById('ragKqcChkDuplicate').checked),
        conflict: false,
        semantic: false,
        llm_semantic: false,
        llm_dup_conflict: false,
        intent_llm: !!(document.getElementById('ragKqcChkIntentLlm') && document.getElementById('ragKqcChkIntentLlm').checked),
      };
    }
    return {
      format: !!(document.getElementById('ragKqcChkFormat') && document.getElementById('ragKqcChkFormat').checked),
      compliance: !!(document.getElementById('ragKqcChkCompliance') && document.getElementById('ragKqcChkCompliance').checked),
      duplicate: !!(document.getElementById('ragKqcChkDuplicate') && document.getElementById('ragKqcChkDuplicate').checked),
      conflict: !!(document.getElementById('ragKqcChkConflict') && document.getElementById('ragKqcChkConflict').checked),
      semantic: !!(document.getElementById('ragKqcChkSemantic') && document.getElementById('ragKqcChkSemantic').checked),
      llm_semantic: !!(document.getElementById('ragKqcChkLlm') && document.getElementById('ragKqcChkLlm').checked),
      llm_dup_conflict: ragKqcCheckerActive('ragKqcChkDuplicate') || ragKqcCheckerActive('ragKqcChkConflict')
        ? !!(document.getElementById('ragKqcChkDupLlm') && document.getElementById('ragKqcChkDupLlm').checked)
        : false
    };
  }

  function ragKqcNumVal(id, fallback) {
    var el = document.getElementById(id);
    if (!el || el.value === '') return fallback;
    var n = parseFloat(el.value);
    return isNaN(n) ? fallback : n;
  }

  function ragKqcCollectEnvOverride() {
    var env = {};
    document.querySelectorAll('.rag-kqc-env-field').forEach(function (inp) {
      var key = inp.getAttribute('data-env-key');
      if (key) env[key] = inp.value;
    });
    return env;
  }

  function ragKqcCollectRulesPayload() {
    var yamlEl = document.getElementById('ragKqcRulesYaml');
    var yaml = yamlEl ? String(yamlEl.value || '').trim() : '';
    var idEl = document.getElementById('ragKqcIdStrategy');
    var payload = {
      similarity: {
        duplicate_threshold: ragKqcNumVal('ragKqcDupThreshold', 0.88),
        conflict_threshold: ragKqcNumVal('ragKqcConfThreshold', 0.78),
        top_k: ragKqcNumVal('ragKqcTopK', 5)
      },
      length: {
        min_chars: ragKqcNumVal('ragKqcMinChars', 2),
        max_chars: ragKqcNumVal('ragKqcMaxChars', 50)
      },
      qc: {
        row_start: ragKqcNumVal('ragKqcRowStart', 0),
        row_end: ragKqcNumVal('ragKqcRowEnd', 0),
        checkpoint_interval: ragKqcNumVal('ragKqcCheckpointInterval', 20),
        worker_count: ragKqcNumVal('ragKqcWorkerCount', 1)
      },
      id: {
        strategy: idEl ? idEl.value : 'uuid'
      }
    };
    if (yaml) payload.yaml = yaml;
    return payload;
  }

  function ragKqcCollectRunConfig() {
    var taskEl = document.querySelector('input[name="ragKqcTask"]:checked');
    var reg = document.getElementById('ragKqcRegulatoryText');
    var pro = document.getElementById('ragKqcProhibitedText');
    var llmSel = document.getElementById('ragKqcLlmConfigSelect');
    var embSel = document.getElementById('ragKqcEmbeddingConfigSelect');
    var cfg = {
      task: taskEl ? taskEl.value : 'question',
      llm_config_name: llmSel ? llmSel.value : '',
      embedding_config_name: embSel ? embSel.value : '',
      checkers: ragKqcCollectCheckerConfig(),
      detection_mode: {
        batch: !!(document.getElementById('ragKqcModeBatch') && document.getElementById('ragKqcModeBatch').checked),
        production: !!(document.getElementById('ragKqcModeProduction') && document.getElementById('ragKqcModeProduction').checked)
      },
      wordlists: {
        regulatory: reg ? reg.value : '',
        prohibited: pro ? pro.value : ''
      }
    };
    var filterModeEl = document.getElementById('ragKqcIntentFilterMode');
    var filterIntentsEl = document.getElementById('ragKqcIntentFilterIntents');
    if (filterModeEl && filterIntentsEl) {
      cfg.intent_filter = {
        mode: filterModeEl.value || '',
        intents: (filterIntentsEl.value || '').split(',').map(function (s) { return s.trim(); }).filter(Boolean)
      };
    }
    var rulesPayload = ragKqcCollectRulesPayload();
    Object.keys(rulesPayload).forEach(function (k) { cfg[k] = rulesPayload[k]; });
    return cfg;
  }

  function ragKqcApiJson(method, url, body) {
    var opts = { method: method, headers: { 'Content-Type': 'application/json' } };
    if (body !== undefined) opts.body = JSON.stringify(body);
    return fetch(url, opts).then(function (r) {
      return r.json().then(function (data) {
        if (!r.ok || data.success === false) {
          var err = new Error((data && data.error) || ('HTTP ' + r.status));
          err.data = data;
          throw err;
        }
        return data;
      });
    });
  }

  function ragKqcStopPolling() {
    if (ragKqcPollTimer) {
      clearInterval(ragKqcPollTimer);
      ragKqcPollTimer = null;
    }
  }

  function ragKqcApplyJobSnapshot(data, opts) {
    opts = opts || {};
    var prog = data.progress || {};
    var jobKind = data.kind || ragKqcLastJobKind || '';
    if (ragKqcIsIngestJobKind(jobKind)) {
      ragKqcSetKbProgress(prog.current || 0, prog.total || 0, prog.phase || '', prog.label || '');
    } else if (jobKind === 'qc') {
      ragKqcSetProgress(prog.current || 0, prog.total || 0);
      if (prog.label) ragKqcSetStatus(prog.label);
    }
    var logs = data.logs || [];
    if (opts.resetLogs) {
      ragKqcLastLogIndex = 0;
      var logEl = document.getElementById('ragKqcLog');
      if (logEl) logEl.value = '';
    }
    for (var i = 0; i < logs.length; i++) {
      ragKqcAppendLog(logs[i]);
    }
    if (typeof data.log_offset === 'number') {
      ragKqcLastLogIndex = data.log_offset;
    } else {
      ragKqcLastLogIndex += logs.length;
    }
  }

  function ragKqcPollJob(jobId, onComplete, pollMs, options) {
    options = options || {};
    ragKqcStopPolling();
    ragKqcLastJobId = jobId;
    if (options.kind) ragKqcLastJobKind = options.kind;
    if (!options.resume) {
      ragKqcLastLogIndex = 0;
    }
    if ((options.kind || ragKqcLastJobKind) === 'qc') {
      ragKqcPersistActiveQcJob(jobId);
    }
    var interval = pollMs || ragKqcPollIntervalMs;
    ragKqcPollTimer = setInterval(function () {
      var url = API_BASE + '/job?job_id=' + encodeURIComponent(jobId)
        + '&log_from=' + encodeURIComponent(String(ragKqcLastLogIndex || 0));
      fetch(url)
        .then(function (r) { return r.json(); })
        .then(function (data) {
          if (!data.success) {
            if (ragKqcLastJobKind === 'qc') ragKqcClearPersistedQcJob();
            throw new Error(data.error || '任务查询失败');
          }
          ragKqcApplyJobSnapshot(data);
          if (data.status === 'completed' || data.status === 'failed' || data.status === 'cancelled') {
            ragKqcStopPolling();
            ragKqcRunning = false;
            if (onComplete) onComplete(data);
          }
        })
        .catch(function (e) {
          ragKqcStopPolling();
          ragKqcRunning = false;
          ragKqcAppendLog('轮询错误: ' + e.message);
          ragKqcResetQcButtons();
          if (ragKqcLastJobKind === 'qc') {
            ragKqcUpdateRetryErrorsBtn(ragKqcLastErrorCount);
          }
          ragKqcResetStartBtn();
          ragKqcSetIngestButtonsDisabled(false);
          ragKqcShowKbProgress(false);
          showToast(e.message, 'danger');
        });
    }, interval);
  }

  function ragKqcResetStartBtn() {
    var startBtn = document.getElementById('ragKqcStartBtn');
    if (startBtn) {
      startBtn.disabled = false;
      startBtn.innerHTML = '<i class="fas fa-play"></i> 开始质检';
    }
  }

  function ragKqcSetQcRunningUi(running) {
    var startBtn = document.getElementById('ragKqcStartBtn');
    var stopBtn = document.getElementById('ragKqcStopBtn');
    if (startBtn) {
      startBtn.disabled = running;
      startBtn.innerHTML = running
        ? '<i class="fas fa-spinner fa-spin"></i> 质检中…'
        : '<i class="fas fa-play"></i> 开始质检';
    }
    if (stopBtn) {
      stopBtn.hidden = !running;
      stopBtn.classList.toggle('hidden', !running);
      stopBtn.disabled = false;
    }
  }

  function ragKqcUpdateRetryErrorsBtn(errorCount, opts) {
    opts = opts || {};
    var retryBtn = document.getElementById('ragKqcRetryErrorsBtn');
    if (!retryBtn) return;
    var errors = parseInt(errorCount, 10) || 0;
    var running = !!opts.running;
    if (!running && errors > 0) {
      retryBtn.removeAttribute('hidden');
      retryBtn.disabled = false;
      retryBtn.textContent = '继续质检异常行 (' + errors + ')';
    } else {
      retryBtn.setAttribute('hidden', 'hidden');
      if (!running) retryBtn.disabled = false;
    }
  }

  function ragKqcResetQcButtons() {
    ragKqcSetQcRunningUi(false);
  }

  function ragKqcStopQc() {
    if (!ragKqcCurrentQcJobId) {
      showToast('无运行中的质检任务', 'info');
      return;
    }
    var stopBtn = document.getElementById('ragKqcStopBtn');
    if (stopBtn) stopBtn.disabled = true;
    ragKqcAppendLog('正在请求终止质检…');
    fetch(API_BASE + '/cancel', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ job_id: ragKqcCurrentQcJobId })
    })
      .then(function (r) { return r.json(); })
      .then(function (data) {
        if (!data.success) throw new Error(data.error || '终止失败');
        ragKqcSetStatus('正在终止，请稍候…');
      })
      .catch(function (e) {
        if (stopBtn) stopBtn.disabled = false;
        showToast(e.message, 'danger');
      });
  }

  function ragKqcRetryableCount(res) {
    res = res || {};
    if (res.retryable_errors != null && res.retryable_errors !== '') {
      return parseInt(res.retryable_errors, 10) || 0;
    }
    return parseInt(res.errors, 10) || 0;
  }

  function ragKqcSetExportBtnEnabled(enabled) {
    var btn = document.getElementById('ragKqcExportQcBtn');
    if (btn) btn.disabled = !enabled;
  }

  function ragKqcOnQcJobComplete(snap, qcJobId) {
    ragKqcCurrentQcJobId = qcJobId;
    ragKqcSetQcRunningUi(false);
    if (snap.status === 'failed') {
      ragKqcUpdateRetryErrorsBtn(0);
      ragKqcClearPersistedQcJob();
      ragKqcSetStatus('质检失败');
      ragKqcSetExportBtnEnabled(false);
      showToast(snap.error || '质检失败', 'danger');
      return;
    }
    var res = snap.result || {};
    ragKqcLastQcJobId = qcJobId;
    var cancelled = snap.status === 'cancelled';
    ragKqcSetExportBtnEnabled(true);
    ragKqcSetStatus(cancelled ? '已终止' : '质检完成');
    if (res.summary) {
      res.summary.split('\n').forEach(function (line) {
        if (line.trim()) ragKqcAppendLog(line);
      });
    }
    if (res.excel_name) {
      ragKqcAppendLog('可点击「导出质检结果」下载: ' + res.excel_name);
    }
    ragKqcUpdateRetryErrorsBtn(ragKqcRetryableCount(res));
    ragKqcLastErrorCount = ragKqcRetryableCount(res);
    if (cancelled) {
      showToast('质检已终止，可导出已完成部分结果', 'warning');
    } else if (!snap._restoredTerminal) {
      showToast('质检完成，可导出结果', 'success');
    }
  }

  function ragKqcAttachToQcJob(jobId, meta) {
    meta = meta || {};
    return fetch(API_BASE + '/job?job_id=' + encodeURIComponent(jobId) + '&log_from=0')
      .then(function (r) { return r.json(); })
      .then(function (data) {
        if (!data.success) {
          ragKqcClearPersistedQcJob();
          return;
        }
        if (data.kind !== 'qc') {
          ragKqcClearPersistedQcJob();
          return;
        }
        ragKqcSwitchTab('qc');
        ragKqcLastJobKind = 'qc';
        ragKqcApplyRestoredFileMeta(meta);
        ragKqcPersistActiveQcJob(jobId, meta);
        ragKqcApplyJobSnapshot(data, { resetLogs: true });

        var fileLabel = meta.source_filename ? (' · ' + meta.source_filename) : '';

        if (data.status === 'running') {
          ragKqcRunning = true;
          ragKqcCurrentQcJobId = jobId;
          ragKqcSetQcRunningUi(true);
          ragKqcSetExportBtnEnabled(false);
          ragKqcSetStatus('质检进行中…（已恢复' + fileLabel + '）');
          ragKqcAppendLog('--- 已恢复运行中的质检任务（刷新后自动接续） ---');
          ragKqcPollJob(jobId, function (snap) {
            ragKqcOnQcJobComplete(snap, jobId);
          }, ragKqcPollIntervalMs, { resume: true, kind: 'qc' });
          return;
        }

        if (data.status === 'completed' || data.status === 'cancelled' || data.status === 'failed') {
          data._restoredTerminal = true;
          ragKqcOnQcJobComplete(data, jobId);
          if (data.status === 'completed') {
            showToast('质检已在后台完成，可导出结果', 'success');
          } else if (data.status === 'cancelled') {
            showToast('质检已终止，可导出已完成部分结果', 'warning');
          }
        }
      });
  }

  function ragKqcRestoreActiveQcJob() {
    if (ragKqcRunning) return;

    ragKqcRestoring = true;
    fetch(API_BASE + '/active_qc')
      .then(function (r) { return r.json(); })
      .then(function (active) {
        var jobId = null;
        var meta = {};
        if (active && active.success && active.has_job && active.job_id) {
          jobId = active.job_id;
          meta = {
            source_filename: active.source_filename || '',
            task: active.task || ''
          };
        } else {
          var saved = ragKqcReadPersistedQcJob();
          if (saved && saved.job_id) {
            jobId = saved.job_id;
            meta = saved;
          }
        }
        if (!jobId) return;
        return ragKqcAttachToQcJob(jobId, meta);
      })
      .catch(function () {
        var saved = ragKqcReadPersistedQcJob();
        if (saved && saved.job_id) {
          return ragKqcAttachToQcJob(saved.job_id, saved);
        }
        ragKqcClearPersistedQcJob();
      })
      .finally(function () {
        ragKqcRestoring = false;
      });
  }

  function ragKqcApplyRestoredFileMeta(meta) {
    if (!meta) return;
    if (meta.source_filename) {
      var input = document.getElementById('ragKqcInputExcel');
      if (input) {
        input.value = meta.source_filename;
        input.dataset.fullPath = meta.source_filename;
      }
    }
    if (meta.task) {
      document.querySelectorAll('input[name="ragKqcTask"]').forEach(function (r) {
        r.checked = (r.value === meta.task);
      });
      ragKqcOnTaskChange();
    }
  }

  function ragKqcPersistActiveQcJob(jobId, meta) {
    try {
      var data = { job_id: jobId, saved_at: Date.now() };
      if (meta && meta.source_filename) data.source_filename = meta.source_filename;
      if (meta && meta.task) data.task = meta.task;
      localStorage.setItem(ragKqcPersistKey, JSON.stringify(data));
    } catch (e) { /* ignore */ }
  }

  function ragKqcReadPersistedQcJob() {
    try {
      var raw = localStorage.getItem(ragKqcPersistKey);
      return raw ? JSON.parse(raw) : null;
    } catch (e) {
      return null;
    }
  }

  function ragKqcClearPersistedQcJob() {
    try {
      localStorage.removeItem(ragKqcPersistKey);
    } catch (e) { /* ignore */ }
  }

  function ragKqcStartQc() {
    if (ragKqcRestoring) {
      showToast('正在恢复任务状态，请稍候', 'info');
      return;
    }
    if (ragKqcRunning) {
      showToast('质检任务进行中', 'info');
      return;
    }
    var fileInput = document.getElementById('ragKqcInputExcelFile');
    if (!fileInput || !fileInput.files || !fileInput.files.length) {
      showToast('请先选择待检 Excel 文件', 'danger');
      return;
    }
    var cfg = ragKqcCollectRunConfig();
    if (!cfg.detection_mode.batch && !cfg.detection_mode.production) {
      showToast('请至少选择一种检测模式', 'danger');
      return;
    }
    var log = document.getElementById('ragKqcLog');
    if (log) log.value = '';
    ragKqcLastLogIndex = 0;

    var fd = new FormData();
    fd.append('file', fileInput.files[0]);
    fd.append('config', JSON.stringify(cfg));

    ragKqcRunning = true;
    ragKqcLastJobKind = 'qc';
    ragKqcCurrentQcJobId = '';
    ragKqcLastErrorCount = 0;
    ragKqcSetExportBtnEnabled(false);
    ragKqcSetQcRunningUi(true);
    ragKqcUpdateRetryErrorsBtn(0, { running: true });
    ragKqcSetStatus('正在提交任务…');
    ragKqcAppendLog('=== 开始质检 ===');
    ragKqcAppendLog('任务: ' + (cfg.task === 'intent' ? '意图描述质检' : '相似问质检'));
    var dm = cfg.detection_mode || {};
    var scopeParts = [];
    if (dm.batch) scopeParts.push('本批 staging');
    if (dm.production) scopeParts.push('生产库 production');
    ragKqcAppendLog('检测模式: ' + (scopeParts.length ? scopeParts.join(' + ') : '无'));

    fetch(API_BASE + '/run', { method: 'POST', body: fd })
      .then(function (r) { return r.json(); })
      .then(function (data) {
        if (!data.success) throw new Error(data.error || '启动失败');
        var qcJobId = data.job_id;
        var fileName = fileInput.files[0] ? fileInput.files[0].name : '';
        var runMeta = { source_filename: fileName, task: cfg.task };
        ragKqcPersistActiveQcJob(qcJobId, runMeta);
        if (data.resumed) {
          ragKqcSetStatus('质检进行中…（已接续）');
          ragKqcAppendLog('--- 接续当前账号进行中的质检任务 ---');
        } else {
          ragKqcSetStatus('质检进行中…');
        }
        ragKqcCurrentQcJobId = qcJobId;
        ragKqcPollJob(qcJobId, function (snap) {
          ragKqcOnQcJobComplete(snap, qcJobId);
        }, ragKqcPollIntervalMs, { kind: 'qc' });
      })
      .catch(function (e) {
        ragKqcRunning = false;
        ragKqcResetQcButtons();
        ragKqcSetStatus('提交失败');
        showToast(e.message, 'danger');
      });
  }

  function ragKqcExportQcResult() {
    if (!ragKqcLastQcJobId) {
      showToast('请先完成一次质检后再导出', 'info');
      return;
    }
    var url = API_BASE + '/download?job_id=' + encodeURIComponent(ragKqcLastQcJobId) + '&type=excel';
    var btn = document.getElementById('ragKqcExportQcBtn');
    if (btn) btn.disabled = true;
    fetch(url, { method: 'GET' })
      .then(function (resp) {
        if (!resp.ok) {
          return resp.json().then(function (d) {
            throw new Error((d && d.error) || ('HTTP ' + resp.status));
          });
        }
        var cd = resp.headers.get('Content-Disposition') || '';
        var m = cd.match(/filename\*?=(?:UTF-8''|")?([^";]+)"?/i);
        var fname = m ? decodeURIComponent(m[1]) : ('qc_result_' + new Date().toISOString().slice(0, 19).replace(/[T:-]/g, '') + '.xlsx');
        return resp.blob().then(function (blob) { return { blob: blob, fname: fname }; });
      })
      .then(function (r) {
        var objUrl = URL.createObjectURL(r.blob);
        var a = document.createElement('a');
        a.href = objUrl;
        a.download = r.fname;
        document.body.appendChild(a);
        a.click();
        a.remove();
        URL.revokeObjectURL(objUrl);
        showToast('质检结果已下载', 'success');
        ragKqcClearPersistedQcJob();
        ragKqcLastQcJobId = '';
      })
      .catch(function (e) {
        showToast(e.message || '导出失败', 'danger');
      })
      .finally(function () {
        if (ragKqcLastQcJobId) ragKqcSetExportBtnEnabled(true);
      });
  }

  function ragKqcStartIngest(kind) {
    if (ragKqcRunning) {
      showToast('有任务进行中', 'info');
      return;
    }
    var fileId = kind === 'intent' ? 'ragKqcIngestIntentFile' : 'ragKqcIngestQuestionFile';
    var clearId = kind === 'intent' ? 'ragKqcIngestIClear' : 'ragKqcIngestQClear';
    var fileInput = document.getElementById(fileId);
    if (!fileInput || !fileInput.files || !fileInput.files.length) {
      showToast('请先选择 Excel 文件', 'danger');
      return;
    }
    var fd = new FormData();
    fd.append('file', fileInput.files[0]);
    var clearEl = document.getElementById(clearId);
    if (clearEl && clearEl.checked) fd.append('clear', '1');
    var url = kind === 'intent'
      ? API_BASE + '/ingest_intent'
      : API_BASE + '/ingest_question';
    ragKqcRunning = true;
    ragKqcLastJobKind = kind === 'intent' ? 'ingest_intent' : 'ingest_question';
    ragKqcSetIngestButtonsDisabled(true);
    ragKqcResetKbProgress();
    ragKqcSetKbProgress(0, 0, 'prepare', '正在提交导入任务…');
    ragKqcSwitchTab('kb');
    fetch(url, { method: 'POST', body: fd })
      .then(function (r) { return r.json(); })
      .then(function (data) {
        if (!data.success) throw new Error(data.error || '启动失败');
        ragKqcPollJob(data.job_id, function (snap) {
          ragKqcSetIngestButtonsDisabled(false);
          if (snap.status === 'failed') {
            ragKqcShowKbProgress(false);
            showToast(snap.error || '导入失败', 'danger');
            return;
          }
          var n = (snap.result && snap.result.count) || 0;
          var prog = snap.progress || {};
          ragKqcSetKbProgress(prog.total || n, prog.total || n, 'chroma', '导入完成');
          var bar = document.getElementById('ragKqcKbProgressBar');
          if (bar) bar.classList.remove('is-animated');
          showToast('导入完成，共 ' + n + ' 条', 'success');
          ragKqcRefreshKbCount();
        }, 800);
      })
      .catch(function (e) {
        ragKqcRunning = false;
        ragKqcSetIngestButtonsDisabled(false);
        ragKqcShowKbProgress(false);
        showToast(e.message, 'danger');
      });
  }

  function ragKqcDownloadKbExport(jobId) {
    var url = API_BASE + '/download?job_id=' + encodeURIComponent(jobId) + '&type=export';
    return fetch(url, { method: 'GET' })
      .then(function (resp) {
        if (!resp.ok) {
          return resp.json().then(function (d) {
            throw new Error((d && d.error) || ('HTTP ' + resp.status));
          });
        }
        var cd = resp.headers.get('Content-Disposition') || '';
        var m = cd.match(/filename\*?=(?:UTF-8''|")?([^";]+)"?/i);
        var fname = m ? decodeURIComponent(m[1]) : ('kb_export_' + new Date().toISOString().slice(0, 19).replace(/[T:-]/g, '') + '.xlsx');
        return resp.blob().then(function (blob) { return { blob: blob, fname: fname }; });
      })
      .then(function (r) {
        var objUrl = URL.createObjectURL(r.blob);
        var a = document.createElement('a');
        a.href = objUrl;
        a.download = r.fname;
        document.body.appendChild(a);
        a.click();
        a.remove();
        URL.revokeObjectURL(objUrl);
      });
  }

  function ragKqcStartExportKb() {
    if (ragKqcRunning) {
      showToast('有任务进行中', 'info');
      return;
    }
    var btn = document.getElementById('ragKqcExportKbBtn');
    if (btn) {
      btn.disabled = true;
      btn.innerHTML = '<i class="fas fa-spinner fa-spin"></i> 导出中…';
    }
    ragKqcRunning = true;
    ragKqcLastJobKind = 'export_kb';
    ragKqcApiJson('POST', API_BASE + '/export_kb', {})
      .then(function (data) {
        var jobId = data.job_id;
        ragKqcPollJob(jobId, function (snap) {
          ragKqcRunning = false;
          if (btn) {
            btn.disabled = false;
            btn.innerHTML = '<i class="fas fa-download"></i> 导出生产库语料';
          }
          if (snap.status === 'failed') {
            showToast(snap.error || '导出失败', 'danger');
            return;
          }
          var res = snap.result || {};
          ragKqcDownloadKbExport(jobId)
            .then(function () {
              showToast(
                '已下载：相似问 ' + (res.question_count || 0) + ' 条，意图描述 ' + (res.intent_count || 0) + ' 条',
                'success'
              );
            })
            .catch(function (e) {
              showToast(e.message || '下载失败', 'danger');
            });
        });
      })
      .catch(function (e) {
        ragKqcRunning = false;
        if (btn) {
          btn.disabled = false;
          btn.innerHTML = '<i class="fas fa-download"></i> 导出生产库语料';
        }
        showToast(e.message, 'danger');
      });
  }

  function ragKqcRefreshKbCount() {
    fetch(API_BASE + '/kb_stats')
      .then(function (r) { return r.json(); })
      .then(function (data) {
        if (!data.success) throw new Error(data.error || '查询失败');
        var el = document.getElementById('ragKqcKbCount');
        if (el) {
          el.textContent = '库条数：相似问 ' + (data.question_count || 0) + ' 条 | 意图描述 ' + (data.intent_count || 0) + ' 条';
        }
      })
      .catch(function (e) {
        showToast(e.message, 'danger');
      });
  }

  function ragKqcApplyRulesForm(form) {
    if (!form) return;
    var sim = form.similarity || {};
    var len = form.length || {};
    var idCfg = form.id || {};
    var set = function (id, v) {
      var el = document.getElementById(id);
      if (el && v !== undefined && v !== null) el.value = v;
    };
    var chk = form.checkers || {};
    var qc = form.qc || {};
    var dupLlm = document.getElementById('ragKqcChkDupLlm');
    if (dupLlm && chk.llm_dup_conflict !== undefined) {
      dupLlm.checked = !!chk.llm_dup_conflict;
    }
    set('ragKqcDupThreshold', sim.duplicate_threshold);
    set('ragKqcConfThreshold', sim.conflict_threshold);
    set('ragKqcTopK', sim.top_k);
    set('ragKqcMinChars', len.min_chars);
    set('ragKqcMaxChars', len.max_chars);
    set('ragKqcRowStart', qc.row_start);
    set('ragKqcRowEnd', qc.row_end);
    set('ragKqcCheckpointInterval', qc.checkpoint_interval);
    set('ragKqcWorkerCount', qc.worker_count);
    set('ragKqcIdStrategy', idCfg.strategy);
    ragKqcSyncLlmVisibility();
  }

  function ragKqcLoadRules() {
    fetch(API_BASE + '/rules')
      .then(function (r) { return r.json(); })
      .then(function (data) {
        if (!data.success) throw new Error(data.error || '加载失败');
        ragKqcApplyRulesForm(data.form);
        var yaml = document.getElementById('ragKqcRulesYaml');
        if (yaml) {
          yaml.value = data.yaml || '';
        }
      })
      .catch(function (e) { showToast(e.message, 'danger'); });
  }

  function ragKqcLoadWordlists(silent) {
    return fetch(API_BASE + '/wordlists')
      .then(function (r) { return r.json(); })
      .then(function (data) {
        if (!data.success) throw new Error(data.error || '加载失败');
        var reg = document.getElementById('ragKqcRegulatoryText');
        var pro = document.getElementById('ragKqcProhibitedText');
        if (reg) reg.value = data.regulatory || '';
        if (pro) pro.value = data.prohibited || '';
        if (!silent) showToast('违禁词库已加载', 'success');
      })
      .catch(function (e) {
        if (!silent) showToast(e.message, 'danger');
      });
  }

  function ragKqcApplyEnvToForm(env) {
    if (!env) return;
    document.querySelectorAll('.rag-kqc-env-field').forEach(function (inp) {
      var key = inp.getAttribute('data-env-key');
      if (key && Object.prototype.hasOwnProperty.call(env, key)) {
        inp.value = env[key] == null ? '' : String(env[key]);
      }
    });
  }

  function ragKqcLoadEnv(silent) {
    return fetch(API_BASE + '/env')
      .then(function (r) { return r.json(); })
      .then(function (data) {
        if (!data.success) throw new Error(data.error || '加载失败');
        ragKqcApplyEnvToForm(data.env || {});
        ragKqcLoadConfigSelects(data || {});
        if (!silent) showToast('已从 .env 加载', 'success');
      })
      .catch(function (e) {
        if (!silent) showToast(e.message, 'danger');
      });
  }

  function ragKqcLoadConfigSelects(envData) {
    envData = envData || {};
    var llmNames = envData.llm_configs || [];
    var embItems = envData.embedding_configs || [];
    var llmSel = document.getElementById('ragKqcLlmConfigSelect');
    var embSel = document.getElementById('ragKqcEmbeddingConfigSelect');

    if (llmSel) {
      var curLlm = llmSel.value;
      llmSel.innerHTML = '';
      llmNames.forEach(function (name) {
        var opt = document.createElement('option');
        opt.value = name;
        opt.textContent = name;
        llmSel.appendChild(opt);
      });
      if (curLlm && llmNames.indexOf(curLlm) >= 0) llmSel.value = curLlm;
      else if (llmNames.length) llmSel.value = llmNames[0];
    }

    if (embSel) {
      var curEmb = embSel.value;
      embSel.innerHTML = '';
      ragKqcEmbeddingConfigs = [];
      embItems.forEach(function (it) {
        var name = it.name || '';
        var opt = document.createElement('option');
        opt.value = name;
        opt.textContent = name + (it.active ? '（当前激活）' : '');
        embSel.appendChild(opt);
        ragKqcEmbeddingConfigs.push(it);
      });
      if (curEmb && embItems.some(function (x) { return (x.name || '') === curEmb; })) embSel.value = curEmb;
      else if (embItems.length) embSel.value = embItems[0].name || '';
    }

    ragKqcLlmConfigs = [];
    fetch('api/llm-configs')
      .then(function (r) { return r.json(); })
      .then(function (data) {
        if (data && data.success && Array.isArray(data.items)) {
          ragKqcLlmConfigs = data.items;
          if (llmSel && data.active) {
            Array.from(llmSel.options).forEach(function (opt) {
              if (opt.value && opt.value === data.active) {
                opt.textContent = opt.value + '（当前激活）';
              }
            });
          }
        }
      })
      .catch(function () { /* 静默失败，不影响手动输入 */ });
  }

  function ragKqcSetEnvField(key, value) {
    var inp = document.querySelector('.rag-kqc-env-field[data-env-key="' + key + '"]');
    if (inp) inp.value = value == null ? '' : String(value);
  }

  function ragKqcApplySelectedConfig(kind, name) {
    if (!name) return;
    if (kind === 'llm') {
      var item = null;
      for (var i = 0; i < ragKqcLlmConfigs.length; i++) {
        if (ragKqcLlmConfigs[i].name === name) {
          item = ragKqcLlmConfigs[i];
          break;
        }
      }
      if (!item || !item.config) return;
      var cfg = item.config;
      var mode = cfg.request_mode === 'http_post' ? 'http' : 'openai';
      ragKqcSetEnvField('LLM_MODE', mode);
      if (mode === 'http') {
        ragKqcSetEnvField('HTTP_LLM_URL', cfg.http_post_url || cfg.base_url || '');
        ragKqcSetEnvField('HTTP_LLM_TOKEN', cfg.api_key || '');
      } else {
        ragKqcSetEnvField('LLM_API_KEY', cfg.api_key || '');
        ragKqcSetEnvField('LLM_BASE_URL', cfg.base_url || '');
        ragKqcSetEnvField('LLM_MODEL', cfg.model || '');
      }
    } else if (kind === 'embedding') {
      var item = null;
      for (var j = 0; j < ragKqcEmbeddingConfigs.length; j++) {
        if (ragKqcEmbeddingConfigs[j].name === name) {
          item = ragKqcEmbeddingConfigs[j];
          break;
        }
      }
      if (!item) return;
      var emode = (item.mode || '').toLowerCase();
      if (emode === 'local') {
        ragKqcSetEnvField('EMBEDDING_MODE', 'local');
        ragKqcSetEnvField('EMBEDDING_MODEL', item.base_url || '');
      } else if (emode === 'http') {
        ragKqcSetEnvField('EMBEDDING_MODE', 'http');
        ragKqcSetEnvField('HTTP_EMBEDDING_URL', item.base_url || '');
      } else {
        ragKqcSetEnvField('EMBEDDING_MODE', 'openai');
        ragKqcSetEnvField('EMBEDDING_BASE_URL', item.base_url || '');
        ragKqcSetEnvField('EMBEDDING_MODEL', item.model || '');
      }
      if (item.batch_size !== undefined && item.batch_size !== null) {
        ragKqcSetEnvField('EMBEDDING_BATCH_SIZE', item.batch_size);
      }
    }
  }

  function ragKqcRetryErrorsQc() {
    if (!ragKqcCurrentQcJobId) {
      showToast('当前没有可续检的质检任务', 'info');
      return;
    }
    if (ragKqcRunning) {
      showToast('质检任务进行中', 'info');
      return;
    }
    ragKqcRunning = true;
    ragKqcSetQcRunningUi(true);
    ragKqcSetExportBtnEnabled(false);
    ragKqcUpdateRetryErrorsBtn(0, { running: true });
    fetch(API_BASE + '/retry_errors', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ job_id: ragKqcCurrentQcJobId })
    })
      .then(function (r) {
        if (!r.ok) {
          if (r.status === 404) throw new Error('后端暂未实现续检');
          return r.json().then(function (d) { throw new Error(d.error || ('HTTP ' + r.status)); });
        }
        return r.json();
      })
      .then(function (data) {
        if (!data.success) throw new Error(data.error || '续检失败');
        var retryJobId = data.job_id || ragKqcCurrentQcJobId;
        ragKqcCurrentQcJobId = retryJobId;
        ragKqcAppendLog('=== 继续质检异常行 ===');
        ragKqcSetStatus('异常行质检进行中…');
        ragKqcPollJob(retryJobId, function (snap) {
          ragKqcOnQcJobComplete(snap, retryJobId);
        }, ragKqcPollIntervalMs, { kind: 'qc' });
      })
      .catch(function (e) {
        ragKqcRunning = false;
        ragKqcSetQcRunningUi(false);
        ragKqcSetExportBtnEnabled(!!ragKqcLastQcJobId);
        var msg = e.message || '续检失败';
        if (ragKqcCurrentQcJobId) {
          fetch(API_BASE + '/job?job_id=' + encodeURIComponent(ragKqcCurrentQcJobId))
            .then(function (r) { return r.json(); })
            .then(function (data) {
              if (data && data.success && data.result) {
                var count = ragKqcRetryableCount(data.result);
                ragKqcUpdateRetryErrorsBtn(count);
                ragKqcLastErrorCount = count;
              }
            })
            .catch(function () { /* ignore */ });
        } else {
          ragKqcUpdateRetryErrorsBtn(ragKqcLastErrorCount);
        }
        if (msg.indexOf('404') !== -1 || msg.indexOf('暂未实现') !== -1) {
          showToast('后端暂未实现续检', 'warning');
        } else {
          showToast(msg, 'danger');
        }
      });
  }

  // 全局提示
  function showToast(message, type) {
    if (typeof App !== 'undefined' && App.showToast) {
      App.showToast(message, type || 'info');
    } else {
      alert(message);
    }
  }
})();
