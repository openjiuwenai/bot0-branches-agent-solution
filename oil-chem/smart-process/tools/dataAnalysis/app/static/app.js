// 化工炼化物料平衡数据：装置模块导入（天级/小时级自动检测）、累积预览、清除、导出
const MODULES = [];
const GRAN_NAMES = { daily: "天级", hourly: "小时级", all: "全部" };

const importRow = document.getElementById("importRow");
const resultSection = document.getElementById("resultSection");
const exportSource = document.getElementById("exportSource");
const exportStart = document.getElementById("exportStart");
const exportEnd = document.getElementById("exportEnd");
const exportFilename = document.getElementById("exportFilename");
const exportBtn = document.getElementById("exportBtn");
const exportHint = document.getElementById("exportHint");
let activeSourceId = null;
let selectedModuleId = null;  // 当前选中的导入数据类型(module_id)
// 记录每个模块当前查看的粒度（点击天级/小时级摘要行时更新）
const viewedGran = {};

function granSid(moduleId, gran) {
  return moduleId + (gran === "hourly" ? "_hourly" : "");
}

async function init() {
  await refreshModules();
  for (const m of MODULES) {
    for (const g of ["daily", "hourly"]) {
      const s = m.sources[g];
      if (s && s.has_data) {
        selectedModuleId = m.module_id;
        const typeSel = document.getElementById("typeSel");
        if (typeSel) typeSel.value = selectedModuleId;
        loadPreview(s.source_id);
        return;
      }
    }
  }
}

async function refreshModules() {
  try {
    const res = await fetch("/api/sources");
    const data = await res.json();
    MODULES.length = 0;
    MODULES.push(...(data.modules || []));
  } catch (e) {
    MODULES.length = 0;
  }
  renderImportCards();
  renderExportSources();
}

function renderImportCards() {
  importRow.innerHTML = "";
  const sec = document.createElement("section");
  sec.className = "upload-card";
  sec.innerHTML =
    '<h2 class="card-title">导入数据</h2>' +
    '<div class="type-row">' +
      '<label class="gran-label">数据类型</label>' +
      '<select id="typeSel" class="type-sel"></select>' +
      '<button type="button" id="typeMgmtBtn" class="type-add-btn" title="添加或重命名数据类型">+</button>' +
      '<label class="gran-label">粒度</label>' +
      '<select id="granSel" class="gran-sel">' +
        '<option value="daily">天级</option>' +
        '<option value="hourly">小时级</option>' +
      '</select>' +
    '</div>' +
    '<div class="store-summaries" id="summ"></div>' +
    '<div class="dropzone" id="drop">' +
      '<input type="file" id="fileInput" accept=".xlsx,.xls" class="file-hidden">' +
      '<p>点击选择文件或将 <code>.xlsx / .xls</code> 拖拽到此处</p>' +
      '<button type="button" class="browseBtn">选择文件</button>' +
    '</div>' +
    '<div class="file-info hidden" id="fileInfo"></div>' +
    '<div class="progress hidden" id="prog"><div class="bar"></div><span class="progress-text" id="progText"></span></div>' +
    '<div class="card-actions">' +
      '<button type="button" id="adjBtn" class="adjustBtn">调整字段结构</button>' +
    '</div>' +
    '<div class="clear-section">' +
      '<div class="clear-title">清除数据</div>' +
      '<div class="clear-row">' +
        '<select id="cgranSel" class="clear-gran">' +
          '<option value="daily">天级</option>' +
          '<option value="hourly">小时级</option>' +
          '<option value="all">全部</option>' +
        '</select>' +
        '<input type="date" id="cstartDate" class="clear-date" title="开始日期">' +
        '<input type="date" id="cendDate" class="clear-date" title="结束日期">' +
        '<button type="button" id="clearBtn" class="clearBtn">清除</button>' +
      '</div>' +
      '<div class="clear-hint" id="chint"></div>' +
    '</div>';
  importRow.appendChild(sec);
  // 填充数据类型下拉
  const typeSel = document.getElementById("typeSel");
  typeSel.innerHTML = MODULES.map(m => '<option value="' + m.module_id + '">' + m.name + '</option>').join("");
  if (!selectedModuleId || !MODULES.find(m => m.module_id === selectedModuleId)) {
    selectedModuleId = MODULES.length ? MODULES[0].module_id : null;
  }
  typeSel.value = selectedModuleId;
  typeSel.addEventListener("change", () => {
    selectedModuleId = typeSel.value;
    updateSummary();
  });
  document.getElementById("typeMgmtBtn").addEventListener("click", showTypeMgmtModal);
  bindCard();
  updateSummary();
}

function updateSummary() {
  const el = document.getElementById("summ");
  if (!el || !selectedModuleId) return;
  const m = MODULES.find(mm => mm.module_id === selectedModuleId);
  if (!m) return;
  let html = "";
  for (const g of ["daily", "hourly"]) {
    const s = m.sources[g];
    const gname = GRAN_NAMES[g];
    if (!s || !s.has_data) {
      html += '<div class="summ-line summ-empty"><span class="summ-tag">' + gname + '</span><span class="muted">暂无数据</span></div>';
    } else {
      html += '<div class="summ-line" data-source="' + s.source_id + '" data-gran="' + g + '">' +
        '<span class="summ-tag">' + gname + '</span>' +
        '<b>' + s.row_count + '</b> 点' +
        (s.time_min ? ' <span class="muted">(' + s.time_min + ' ~ ' + s.time_max + ')</span>' : '') +
        (s.import_count ? ' <span class="muted">导入' + s.import_count + '次</span>' : '') +
        '</div>';
    }
  }
  el.innerHTML = html;
  el.querySelectorAll(".summ-line[data-source]").forEach(line => {
    line.addEventListener("click", () => { viewedGran[selectedModuleId] = line.dataset.gran; loadPreview(line.dataset.source); });
  });
}

function bindCard() {
  const drop = document.getElementById("drop");
  const fileInput = document.getElementById("fileInput");
  const browseBtn = drop.querySelector(".browseBtn");
  browseBtn.addEventListener("click", (e) => { e.stopPropagation(); fileInput.click(); });
  drop.addEventListener("click", (e) => {
    if (e.target === browseBtn || browseBtn.contains(e.target)) return;
    fileInput.click();
  });
  fileInput.addEventListener("change", () => { if (fileInput.files[0]) handleFile(selectedModuleId, fileInput.files[0]); });
  ["dragenter", "dragover"].forEach(ev =>
    drop.addEventListener(ev, (e) => { e.preventDefault(); drop.classList.add("dragover"); })
  );
  ["dragleave", "drop"].forEach(ev =>
    drop.addEventListener(ev, (e) => { e.preventDefault(); drop.classList.remove("dragover"); })
  );
  drop.addEventListener("drop", (e) => { const f = e.dataTransfer.files[0]; if (f) handleFile(selectedModuleId, f); });
  document.getElementById("clearBtn").addEventListener("click", () => {
    const gran = document.getElementById("cgranSel").value;
    const start = document.getElementById("cstartDate").value;
    const end = document.getElementById("cendDate").value;
    clearData(selectedModuleId, gran, start, end);
  });
  document.getElementById("adjBtn").addEventListener("click", () => openAdjustModal(selectedModuleId));
}

function showTypeMgmtModal() {
  let oldm = document.getElementById("typeMgmtModal");
  if (oldm) oldm.remove();
  const modal = document.createElement("div");
  modal.id = "typeMgmtModal";
  modal.className = "modal-overlay";
  let rowsHtml = MODULES.map(m =>
    '<tr data-mid="' + m.module_id + '">' +
      '<td><input type="text" class="tname-input" value="' + _escHtml(m.name) + '" data-orig="' + _escHtml(m.name) + '"></td>' +
      '<td class="tname-status"></td>' +
    '</tr>'
  ).join("");
  modal.innerHTML =
    '<div class="modal-box">' +
      '<div class="modal-head"><h3>管理数据类型</h3><div class="modal-sub">重命名已有类型（不影响已导入数据）或添加新类型</div></div>' +
      '<div class="modal-table-wrap"><table class="modal-table type-mgmt-table"><thead><tr>' +
        '<th>数据类型名称</th><th>状态</th>' +
      '</tr></thead><tbody id="typeMgmtBody">' + rowsHtml + '</tbody></table></div>' +
      '<div class="type-add-row">' +
        '<input type="text" id="newTypeName" placeholder="输入新数据类型名称" class="tname-input">' +
        '<button type="button" id="addTypeBtn" class="btn-primary">添加</button>' +
      '</div>' +
      '<div class="modal-foot">' +
        '<span class="modal-hint">修改名称后点击保存即生效；添加新类型后自动出现在下拉菜单中。</span>' +
        '<button type="button" class="modal-cancel">取消</button>' +
        '<button type="button" class="modal-ok">保存</button>' +
      '</div>' +
    '</div>';
  document.body.appendChild(modal);
  modal.querySelector(".modal-cancel").addEventListener("click", () => modal.remove());
  modal.querySelector("#addTypeBtn").addEventListener("click", async () => {
    const name = modal.querySelector("#newTypeName").value.trim();
    if (!name) return;
    try {
      const fd = new FormData();
      fd.append("name", name);
      const res = await fetch("/api/modules/add", { method: "POST", body: fd });
      const r = await res.json();
      if (!r.ok) { alert(r.error || "添加失败"); return; }
      MODULES.length = 0;
      MODULES.push(...r.modules);
      if (r.module && r.module.id) selectedModuleId = r.module.id;
      modal.remove();
      renderImportCards();
      renderExportSources();
    } catch (e) { alert("添加失败: " + e); }
  });
  modal.querySelector(".modal-ok").addEventListener("click", async () => {
    const inputs = modal.querySelectorAll("#typeMgmtBody tr");
    let changed = false;
    for (const tr of inputs) {
      const mid = tr.dataset.mid;
      const input = tr.querySelector(".tname-input");
      const newName = input.value.trim();
      const origName = input.dataset.orig;
      if (newName && newName !== origName) {
        try {
          const fd = new FormData();
          fd.append("module_id", mid);
          fd.append("name", newName);
          const res = await fetch("/api/modules/rename", { method: "POST", body: fd });
          const r = await res.json();
          if (!r.ok) { tr.querySelector(".tname-status").innerHTML = '<span style="color:var(--red)">✗ ' + r.error + '</span>'; continue; }
          tr.querySelector(".tname-status").innerHTML = '<span style="color:var(--green)">✓ 已更新</span>';
          changed = true;
        } catch (e) {
          tr.querySelector(".tname-status").innerHTML = '<span style="color:var(--red)">✗ ' + e + '</span>';
        }
      }
    }
    if (changed) {
      await refreshModules();
      modal.remove();
    }
  });
}

function handleFile(moduleId, file) {
  const info = document.getElementById("fileInfo");
  info.classList.remove("hidden");
  if (!/\.(xlsx|xls)$/i.test(file.name)) {
    info.innerHTML = '<span style="color:var(--red)">\u2717 仅支持 .xlsx / .xls，当前: ' + file.name + '</span>';
    return;
  }
  info.innerHTML = '\uD83D\uDCC4 ' + file.name + ' <span class="muted">(' + (file.size / 1024).toFixed(1) + ' KB)</span>';
  const gran = document.getElementById("granSel").value;
  upload(moduleId, gran, file, "");
}


var progState = { active: false, totalSheets: 1, currentSheet: 0, basePct: 0 };

function showProgress(totalSheets) {
  var prog = document.getElementById("prog");
  prog.classList.remove("hidden");
  progState.active = true;
  progState.totalSheets = totalSheets || 1;
  progState.currentSheet = 0;
  progState.basePct = 0;
  updateProgressText(0);
}

function hideProgress() {
  progState.active = false;
  setTimeout(function() {
    var prog = document.getElementById("prog");
    if (!progState.active) prog.classList.add("hidden");
  }, 400);
}

function setProgressPct(pct) {
  var bar = document.querySelector("#prog .bar");
  if (bar) bar.style.width = pct + "%";
  updateProgressText(pct);
}

function updateProgressText(pct) {
  var txt = document.getElementById("progText");
  if (!txt) return;
  var label = "";
  if (progState.totalSheets > 1) {
    label = "(" + (progState.currentSheet + 1) + "/" + progState.totalSheets + ") ";
  }
  txt.textContent = label + Math.round(pct) + "%";
}

let pendingConfirm = null;
var sheetImportQueue = null;
var pendingFileSheets = null;
var savedFieldConfig = null;  // field config from first sheet, reused for remaining sheets

function upload(moduleId, granularity, file, sheetName) {
  showProgress(1);
  setProgressPct(5);
  const fd = new FormData();
  fd.append("file", file);
  fd.append("module_id", moduleId);
  fd.append("granularity", granularity);
  if (sheetName) fd.append("sheet_name", sheetName);
  const xhr = new XMLHttpRequest();
  xhr.open("POST", "/api/import");
  xhr.upload.onprogress = (e) => { if (e.lengthComputable) { var segBase = progState.basePct + (progState.currentSheet / progState.totalSheets) * 100; var segSize = (1 / progState.totalSheets) * 100; var pct = Math.min(segBase + segSize * 0.4 * (e.loaded / e.total), segBase + segSize * 0.4); setProgressPct(pct); } };
  xhr.onload = () => {
    var segBase = progState.basePct + (progState.currentSheet / progState.totalSheets) * 100;
    var segSize = (1 / progState.totalSheets) * 100;
    setProgressPct(segBase + segSize);
    if (xhr.status === 200) {
      const r = JSON.parse(xhr.responseText);
      if (r.needs_confirmation) {
        pendingConfirm = { moduleId: moduleId, granularity: granularity, file: file, sheetName: sheetName, data: r };
        showFieldModal(r);
      } else {
        refreshModules().then(() => {
          renderPreview(r);
          if (sheetImportQueue) {
            processNextSheet();
          } else {
            var importedSheet = r.selected_sheet || sheetName || "";
            fetchSheetsAfterImport(moduleId, granularity, file, importedSheet);
            hideProgress();
          }
        }).catch(err => {
          hideProgress();
          console.error("import display error:", err);
          renderError("导入成功但显示失败: " + err);
        });
      }
    } else {
      let msg = "处理失败 (" + xhr.status + ")";
      try { msg = JSON.parse(xhr.responseText).detail || msg; } catch (_) {}
      renderError(msg);
    }
  };
  xhr.onerror = () => { hideProgress(); renderError("网络错误"); };
  xhr.send(fd);
}

function fetchSheetsAfterImport(moduleId, granularity, file, importedSheet) {
  const fd = new FormData();
  fd.append("file", file);
  fetch("/api/import/sheets", { method: "POST", body: fd })
    .then(r => r.json())
    .then(r => {
      if (r.sheets && r.sheets.length > 1) {
        var remaining = r.sheets.filter(function(s) {
          return s !== importedSheet;
        });
        if (remaining.length > 0) {
          showSheetModal(moduleId, granularity, file, remaining);
        }
      }
    })
    .catch(function() {});
}

function showSheetModal(moduleId, granularity, file, sheets) {
  var old = document.getElementById("sheetModal");
  if (old) old.remove();
  var modal = document.createElement("div");
  modal.id = "sheetModal";
  modal.className = "modal-overlay";
  var checks = sheets.map(function(s) {
    return '<label class="sheet-check"><input type="checkbox" class="sheet-cb" value="' + _escHtml(s) + '" checked> ' + _escHtml(s) + '</label>';
  }).join("");
  modal.innerHTML =
    '<div class="modal-box">' +
      '<div class="modal-head"><h3>选择要导入的Sheet</h3></div>' +
      '<div class="modal-body">' +
        '<div style="margin-bottom:8px"><label><input type="checkbox" id="sheetAll" checked> 全选</label></div>' +
        '<div class="sheet-list">' + checks + '</div>' +
      '</div>' +
      '<div class="modal-foot">' +
        '<button class="btn modal-cancel">取消</button>' +
        '<button class="btn btn-primary modal-ok">确认导入</button>' +
      '</div>' +
    '</div>';
  document.body.appendChild(modal);
  var allCb = modal.querySelector("#sheetAll");
  allCb.addEventListener("change", function() {
    modal.querySelectorAll(".sheet-cb").forEach(function(cb) { cb.checked = allCb.checked; });
  });
  modal.querySelectorAll(".sheet-cb").forEach(function(cb) {
    cb.addEventListener("change", function() {
      var allChecked = true;
      modal.querySelectorAll(".sheet-cb").forEach(function(c2) { if (!c2.checked) allChecked = false; });
      allCb.checked = allChecked;
    });
  });
  modal.querySelector(".modal-cancel").addEventListener("click", function() { modal.remove(); });
  modal.querySelector(".modal-ok").addEventListener("click", function() {
    var selected = [];
    modal.querySelectorAll(".sheet-cb:checked").forEach(function(cb) { selected.push(cb.value); });
    if (!selected.length) { alert("请至少选择一个Sheet"); return; }
    modal.remove();
    sheetImportQueue = { moduleId: moduleId, granularity: granularity, file: file, sheets: selected, index: 0 };
    showProgress(selected.length);
    progState.currentSheet = 0;
    // For remaining sheets: use saved field config to import directly (no field modal)
    directImportSheet(moduleId, granularity, file, selected[0]);
  });
}

function showSheetModalForRemaining(moduleId, granularity, file, allSheets, importedSheet) {
  showSheetModal(moduleId, granularity, file, allSheets.filter(function(s) { return s !== importedSheet; }));
}

function processNextSheet() {
  if (!sheetImportQueue) return;
  sheetImportQueue.index++;
  if (sheetImportQueue.index < sheetImportQueue.sheets.length) {
    progState.currentSheet = sheetImportQueue.index + 1;
    var next = sheetImportQueue.sheets[sheetImportQueue.index];
    var q = sheetImportQueue;
    directImportSheet(q.moduleId, q.granularity, q.file, next);
  } else {
    sheetImportQueue = null;
    setProgressPct(100);
    hideProgress();
  }
}

function directImportSheet(moduleId, granularity, file, sheetName) {
  // Import a sheet directly using saved field config (no field modal)
  if (!savedFieldConfig) {
    upload(moduleId, granularity, file, sheetName);
    return;
  }
  setProgressPct(progState.basePct + (progState.currentSheet / progState.totalSheets) * 100 + 2);
  var fd = new FormData();
  fd.append("file", file);
  fd.append("module_id", moduleId);
  fd.append("granularity", granularity);
  if (sheetName) fd.append("sheet_name", sheetName);
  fd.append("included_fields", savedFieldConfig.included_fields);
  fd.append("value_cols", savedFieldConfig.value_cols);
  fd.append("time_col", savedFieldConfig.time_col);
  fd.append("field_types", savedFieldConfig.field_types);
  fd.append("dtypes", savedFieldConfig.dtypes);
  fd.append("field_renames", savedFieldConfig.field_renames);
  fd.append("included_orig", savedFieldConfig.included_orig);
  var xhr = new XMLHttpRequest();
  xhr.open("POST", "/api/import/confirm");
  xhr.upload.onprogress = function(e) { if (e.lengthComputable) { var segBase = progState.basePct + (progState.currentSheet / progState.totalSheets) * 100; var segSize = (1 / progState.totalSheets) * 100; var pct = Math.min(segBase + segSize * 0.4 * (e.loaded / e.total), segBase + segSize * 0.4); setProgressPct(pct); } };
  xhr.onload = function() {
    var segBase = progState.basePct + (progState.currentSheet / progState.totalSheets) * 100;
    var segSize = (1 / progState.totalSheets) * 100;
    setProgressPct(segBase + segSize);
    if (xhr.status === 200) {
      var r = JSON.parse(xhr.responseText);
      refreshModules().then(function() {
        renderPreview(r);
        processNextSheet();
      });
    } else {
      var msg = "\u5904\u7406\u5931\u8d25 (" + xhr.status + ")";
      try { msg = JSON.parse(xhr.responseText).detail || msg; } catch (_) {}
      renderError(msg);
    }
  };
  xhr.onerror = function() { hideProgress(); renderError("\u7f51\u7edc\u9519\u8bef"); };
  xhr.send(fd);
}

function _escHtml(s) {
  return String(s == null ? "" : s).replace(/[&<>"]/g, function (c) {
    return { "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;" }[c];
  });
}

function _dtypeToLabel(dt) {
  if (!dt) return "文本";
  var s = String(dt);
  if (s.startsWith("int")) return "整数";
  if (s.startsWith("float")) return "小数";
  if (s.startsWith("datetime")) return "日期时间";
  if (s.startsWith("bool")) return "布尔";
  return "文本";
}

function _roleToTypeLabel(f) {
  if (f.is_time) return "时间列";
  if (f.is_value) return "数值列";
  return "描述列";
}

function showFieldModal(r) {
  let oldm = document.getElementById("fieldModal");
  if (oldm) oldm.remove();
  const modal = document.createElement("div");
  modal.id = "fieldModal";
  modal.className = "modal-overlay";
  const gname = ({ daily: "天级", hourly: "小时级" })[r.actual_granularity] || r.actual_granularity;
  let subTxt = (r.module_name || "") + " · 检测粒度：" + gname;
  if (r.granularity_warning) subTxt += " · " + r.granularity_warning;

  let diffHtml = "";
  if (r.has_template && r.diff && !r.diff.match) {
    const d = r.diff;
    let parts = [];
    if (d.missing && d.missing.length) parts.push("模板有但新文件无：" + d.missing.join("、"));
    if (d.extra && d.extra.length) parts.push("新文件新增：" + d.extra.join("、"));
    if (d.reordered) parts.push("字段顺序与模板不一致");
    diffHtml = '<div class="modal-diff warn"><b>字段结构与已存模板不一致：</b>' + _escHtml(parts.join("；")) + "。请确认后导入（将更新模板）。</div>";
  } else if (!r.has_template) {
    diffHtml = '<div class="modal-diff info">首次导入，请确认字段结构（将作为该数据源模板，后续同结构文件可直接导入）。</div>';
  }

  var typeOpts = ["时间列", "描述列", "数值列"];
  var dtypeOpts = ["文本", "整数", "小数", "日期时间", "布尔"];

  let rowsHtml = "";
  (r.fields || []).forEach(function (f, idx) {
    const isTime = !!f.is_time || f.field_type === "时间列";
    const isEmptyHeader = !!f.is_empty_header;
    const isIncluded = f.included !== undefined ? !!f.included : !isEmptyHeader;
    const incChk = isTime ? "checked" : (isIncluded ? "checked" : "");
    const typeLabel = r.adjustMode ? (f.field_type || "描述列") : _roleToTypeLabel(f);
    const dtypeLabel = r.adjustMode ? (f.dtype_label || "文本") : _dtypeToLabel(f.dtype);
    const samples = (f.samples || []).map(function (sm) { return sm === null || sm === undefined ? "—" : String(sm); }).join(", ");
    var typeSel = '<select class="ftype">';
    typeOpts.forEach(function (t) { typeSel += '<option value="' + t + '"' + (t === typeLabel ? " selected" : "") + ">" + t + "</option>"; });
    typeSel += "</select>";
    var dsel = '<select class="dsel">';
    dtypeOpts.forEach(function (d) { dsel += '<option value="' + d + '"' + (d === dtypeLabel ? " selected" : "") + ">" + d + "</option>"; });
    dsel += "</select>";
    rowsHtml += '<tr data-name="' + _escHtml(f.name) + '" data-time="' + isTime + '" draggable="true">' +
      '<td class="ord">' + (idx + 1) + '</td>' +
      '<td class="ctr"><input type="checkbox" class="inc" ' + incChk + '></td>' +
      '<td class="fname"><input type="text" class="fname-input" value="' + _escHtml(f.name) + '" data-orig="' + _escHtml(f.orig_name || f.name) + '" title="点击编辑字段名"></td>' +
      '<td>' + typeSel + '</td>' +
      '<td>' + dsel + '</td>' +
      '<td class="samples">' + _escHtml(samples) + '</td>' +
      '<td class="reorder"><span class="drag-handle" title="拖动调整顺序">⠿</span></td>' +
      '</tr>';
  });

  modal.innerHTML =
    '<div class="modal-box">' +
      '<div class="modal-head"><h3>' + (r.adjustMode ? '调整字段结构' : '确认导入字段结构') + '</h3><div class="modal-sub">' + _escHtml(subTxt) + '</div></div>' +
      diffHtml +
      '<div class="modal-table-wrap"><table class="modal-table"><thead><tr>' +
        '<th>序</th><th>包含</th><th>字段名</th><th>字段类型</th><th>数据类型</th><th>样例值</th><th>拖动</th>' +
      '</tr></thead><tbody>' + rowsHtml + '</tbody></table></div>' +
      '<div class="modal-foot">' +
        '<span class="modal-hint">下拉选择"字段类型"（时间列/描述列/数值列）和"数据类型"；勾选"包含"选择导入字段；拖动行调整顺序。时间列必须保留且仅一个。</span>' +
        '<button type="button" class="modal-cancel">取消</button>' +
        '<button type="button" class="modal-ok">' + (r.adjustMode ? '确认调整' : '确认并导入') + '</button>' +
      '</div>' +
    '</div>';
  document.body.appendChild(modal);

  // 字段类型变化时联动
  modal.querySelectorAll(".ftype").forEach(function (sel) {
    sel.addEventListener("change", function () {
      var tr = sel.closest("tr");
      var inc = tr.querySelector(".inc");
      if (sel.value === "时间列") {
        modal.querySelectorAll(".ftype").forEach(function (s2) {
          if (s2 !== sel && s2.value === "时间列") { s2.value = "描述列"; }
        });
        inc.checked = true; inc.disabled = true;
        tr.dataset.time = "true";
      } else {
        if (tr.dataset.time === "true") { inc.disabled = false; tr.dataset.time = "false"; }
      }
    });
  });

  modal.querySelector(".modal-cancel").addEventListener("click", function () { modal.remove(); pendingConfirm = null; });
  setupRowDragSort(modal.querySelector("tbody"));
  modal.querySelector(".modal-ok").addEventListener("click", confirmImport);
}

function setupRowDragSort(tbody) {
  var dragRow = null;
  tbody.querySelectorAll("tr").forEach(function (tr) {
    tr.addEventListener("dragstart", function (e) {
      dragRow = tr;
      tr.classList.add("dragging");
      if (e.dataTransfer) { e.dataTransfer.effectAllowed = "move"; try { e.dataTransfer.setData("text/plain", ""); } catch (_) {} }
    });
    tr.addEventListener("dragend", function () {
      tr.classList.remove("dragging");
      tbody.querySelectorAll("tr").forEach(function (r) { r.classList.remove("drag-over"); });
      dragRow = null;
    });
    tr.addEventListener("dragover", function (e) {
      e.preventDefault();
      if (e.dataTransfer) { e.dataTransfer.dropEffect = "move"; }
      if (!dragRow || dragRow === tr) return;
      tbody.querySelectorAll("tr").forEach(function (r) { r.classList.remove("drag-over"); });
      tr.classList.add("drag-over");
    });
    tr.addEventListener("dragleave", function () {
      tr.classList.remove("drag-over");
    });
    tr.addEventListener("drop", function (e) {
      e.preventDefault();
      if (!dragRow || dragRow === tr) return;
      var rows = Array.from(tbody.querySelectorAll("tr"));
      var dragIdx = rows.indexOf(dragRow);
      var dropIdx = rows.indexOf(tr);
      if (dragIdx < dropIdx) tbody.insertBefore(dragRow, tr.nextSibling);
      else tbody.insertBefore(dragRow, tr);
      renumberRows(tbody);
    });
  });
}

function renumberRows(tbody) {
  tbody.querySelectorAll("tr").forEach(function (tr, i) { tr.querySelector(".ord").textContent = (i + 1); });
}

function confirmImport() {
  if (!pendingConfirm) return;
  const modal = document.getElementById("fieldModal");
  const rows = modal.querySelectorAll("tbody tr");
  const included = [];
  const valueCols = [];
  const fieldTypes = {};
  const dtypes = {};
  var timeCol = null;
  var renames = {};
  var includedOrig = [];
  rows.forEach(function (tr) {
    const origName = tr.dataset.name;
    const input = tr.querySelector(".fname-input");
    const realOrig = input ? (input.dataset.orig || origName) : origName;
    const editedName = (input ? input.value.trim() : "") || origName;
    if (editedName !== origName) { renames[realOrig] = editedName; }
    if (tr.querySelector(".inc").checked) {
      included.push(editedName);
      includedOrig.push(realOrig);
      var ftype = tr.querySelector(".ftype").value;
      var dval = tr.querySelector(".dsel").value;
      fieldTypes[editedName] = ftype;
      dtypes[editedName] = dval;
      if (ftype === "时间列") { timeCol = editedName; }
      if (ftype === "数值列") { valueCols.push(editedName); }
    }
  });
  if (!included.length) { alert("请至少选择一个字段"); return; }
  if (!timeCol) { alert("请选择一个时间列（字段类型设为\"时间列\"）"); return; }
  if (!valueCols.length) { if (!confirm("未选择任何数值列，进总/出总汇总可能无法生成。仍要导入？")) return; }

  const okBtn = modal.querySelector(".modal-ok");
  const pc = pendingConfirm;
  const isAdjust = !!pc.adjustMode;
  const okText = isAdjust ? "确认调整" : "确认并导入";
  const loadingText = isAdjust ? "调整中…" : "导入中…";
  okBtn.disabled = true; okBtn.textContent = loadingText;

  const fd = new FormData();
  if (isAdjust) {
    fd.append("included_fields", JSON.stringify(included));
    fd.append("value_cols", JSON.stringify(valueCols));
    fd.append("time_col", timeCol);
    fd.append("field_types", JSON.stringify(fieldTypes));
    fd.append("dtypes", JSON.stringify(dtypes));
    fd.append("field_renames", JSON.stringify(renames));
    fd.append("included_orig", JSON.stringify(includedOrig));
    const xhr = new XMLHttpRequest();
    xhr.open("POST", "/api/source/" + pc.sourceId + "/adjust");
    xhr.onload = function () {
      okBtn.disabled = false; okBtn.textContent = okText;
      if (xhr.status === 200) {
        const r = JSON.parse(xhr.responseText);
        modal.remove(); pendingConfirm = null;
        refreshModules().then(function () { renderPreview(r); });
      } else {
        let msg = "调整失败 (" + xhr.status + ")";
        try { msg = JSON.parse(xhr.responseText).detail || msg; } catch (_) {}
        alert(msg);
      }
    };
    xhr.onerror = function () { okBtn.disabled = false; okBtn.textContent = okText; alert("网络错误"); };
    xhr.send(fd);
  } else {
    fd.append("file", pc.file);
    fd.append("module_id", pc.moduleId);
    fd.append("granularity", pc.granularity);
    fd.append("sheet_name", pc.sheetName || "");
    fd.append("included_fields", JSON.stringify(included));
    fd.append("value_cols", JSON.stringify(valueCols));
    fd.append("time_col", timeCol);
    fd.append("field_types", JSON.stringify(fieldTypes));
    fd.append("dtypes", JSON.stringify(dtypes));
    fd.append("field_renames", JSON.stringify(renames));
    fd.append("included_orig", JSON.stringify(includedOrig));
    const xhr = new XMLHttpRequest();
    xhr.open("POST", "/api/import/confirm");
    showProgress(1);
    setProgressPct(40);
    xhr.upload.onprogress = function(e) { if (e.lengthComputable) setProgressPct(Math.min(40 + 30 * (e.loaded / e.total), 70)); };
    xhr.onload = function () {
      okBtn.disabled = false; okBtn.textContent = okText;
      if (xhr.status === 200) {
        setProgressPct(100);
        const r = JSON.parse(xhr.responseText);
        modal.remove(); pendingConfirm = null;
        // Save field config for reuse with remaining sheets
        savedFieldConfig = {
          included_fields: JSON.stringify(included),
          value_cols: JSON.stringify(valueCols),
          time_col: timeCol,
          field_types: JSON.stringify(fieldTypes),
          dtypes: JSON.stringify(dtypes),
          field_renames: JSON.stringify(renames),
          included_orig: JSON.stringify(includedOrig)
        };
        refreshModules().then(function () {
          renderPreview(r);
          var importedSheet = r.selected_sheet || pc.sheetName || "";
          fetchSheetsAfterImport(pc.moduleId, pc.granularity, pc.file, importedSheet);
        });
        if (!sheetImportQueue) hideProgress();
      } else {
        let msg = "导入失败 (" + xhr.status + ")";
        try { msg = JSON.parse(xhr.responseText).detail || msg; } catch (_) {}
        alert(msg);
      }
    };
    xhr.onerror = function () { okBtn.disabled = false; okBtn.textContent = okText; hideProgress(); alert("网络错误"); };
    xhr.send(fd);
  }
}

async function openAdjustModal(moduleId) {
  // 使用当前查看的粒度，未查看过则回退到导入粒度下拉框
  const gran = viewedGran[moduleId] || document.getElementById("granSel").value;
  const sourceId = granSid(moduleId, gran);
  const store = (MODULES.find(m => m.module_id === moduleId) || {}).sources || {};
  const sInfo = store[gran];
  if (!sInfo || !sInfo.has_data) {
    alert("该粒度暂无已导入数据，请先导入数据后再调整字段结构。");
    return;
  }
  // 获取已导入数据的字段结构
  try {
    const res = await fetch("/api/source/" + sourceId + "/fields");
    const r = await res.json();
    if (r.errors && r.errors.length) { alert(r.errors.join("; ")); return; }
    if (!r.fields || !r.fields.length) { alert("未获取到字段信息"); return; }
    r.adjustMode = true;
    r.sourceId = sourceId;
    r.module_name = (MODULES.find(m => m.module_id === moduleId) || {}).name || "";
    const gname = GRAN_NAMES[gran] || gran;
    r.granularity_warning = "已导入数据 · " + gname;
    pendingConfirm = { sourceId: sourceId, adjustMode: true };
    showFieldModal(r);
  } catch (e) {
    alert("获取字段结构失败: " + e);
  }
}

async function clearData(moduleId, granularity, startDate, endDate) {
  const granText = GRAN_NAMES[granularity] || granularity;
  const rangeText = (startDate || endDate)
    ? "\uFF08" + (startDate || "\u5F00\u59CB") + " ~ " + (endDate || "\u7ED3\u675F") + "\uFF09"
    : "\uFF08\u5168\u90E8\uFF09";
  if (!confirm("确认清除 " + granText + " 数据" + rangeText + "？此操作不可撤销。")) return;
  const hint = document.getElementById("chint");
  hint.textContent = "清除中…";
  hint.style.color = "var(--muted)";
  try {
    const fd = new FormData();
    fd.append("module_id", moduleId);
    fd.append("granularity", granularity);
    fd.append("start_date", startDate);
    fd.append("end_date", endDate);
    const res = await fetch("/api/clear", { method: "POST", body: fd });
    const r = await res.json();
    if (!res.ok) {
      hint.innerHTML = '<span style="color:var(--red)">\u2717 ' + (r.detail || "清除失败") + '</span>';
      return;
    }
    let msg = "已清除 " + r.total_cleared + " 行";
    if (granularity === "all") {
      const d = r.results.daily ? r.results.daily.cleared : 0;
      const h = r.results.hourly ? r.results.hourly.cleared : 0;
      msg += "（天级 " + d + "，小时级 " + h + "）";
    }
    hint.innerHTML = '<span style="color:var(--green)">\u2713 ' + msg + '</span>';
    await refreshModules();
  } catch (e) {
    hint.innerHTML = '<span style="color:var(--red)">\u2717 网络错误</span>';
  }
}

async function loadPreview(sourceId) {
  activeSourceId = sourceId;
  resultSection.classList.remove("hidden");
  const banner = document.getElementById("statusBanner");
  banner.className = "status-banner warn";
  banner.textContent = "加载中…";
  document.getElementById("errorsBox").classList.add("hidden");
  document.getElementById("wideWrap").classList.add("hidden");
  try {
    const res = await fetch("/api/source/" + sourceId + "/preview");
    const r = await res.json();
    renderPreview(r);
  } catch (e) {
    renderError("加载预览失败");
  }
}

function renderError(msg) {
  resultSection.classList.remove("hidden");
  const banner = document.getElementById("statusBanner");
  banner.className = "status-banner err";
  banner.textContent = "\u2717 " + msg;
  document.getElementById("errorsBox").classList.add("hidden");
  document.getElementById("wideWrap").classList.add("hidden");
}

function renderPreview(r) {
  resultSection.classList.remove("hidden");
  if (r.source_id) activeSourceId = r.source_id;
  const banner = document.getElementById("statusBanner");
  const eBox = document.getElementById("errorsBox");
  const eList = document.getElementById("errorList");
  eList.innerHTML = "";

  if (r.errors && r.errors.length) {
    banner.className = "status-banner err";
    banner.textContent = "\u2717 " + (r.source_name || "") + " 处理出错";
    eBox.classList.remove("hidden");
    r.errors.forEach(e => { const li = document.createElement("li"); li.textContent = e; eList.appendChild(li); });
    document.getElementById("wideWrap").classList.add("hidden");
    return;
  }
  eBox.classList.add("hidden");

  if (!r.has_data || !r.wide_columns || !r.wide_columns.length) {
    banner.className = "status-banner warn";
    banner.textContent = (r.source_name || "") + " 暂无数据，请先导入。";
    document.getElementById("wideWrap").classList.add("hidden");
    return;
  }

  if (r.action === "import") {
    banner.className = "status-banner ok";
    let html = "\u2713 已导入并保存到 <b>" + (r.source_name || "") + "</b>，累计 <b>" + r.row_count + "</b> 个时间点" +
      (r.time_min ? "（" + r.time_min + " ~ " + r.time_max + "）" : "") + "，下方为累积预览。";
    if (r.template_matched) html += ' <span class="muted">（字段结构与模板一致，已直接导入）</span>';
    else if (r.template_updated) html += ' <span class="muted">（字段模板已更新）</span>';
    if (r.granularity_warning) {
      banner.className = "status-banner warn";
      html = '<span style="font-size:13px">\u26A0 ' + r.granularity_warning + '</span><br>' + html;
    }
    banner.innerHTML = html;
  } else {
    banner.className = "status-banner ok";
    banner.innerHTML = "查看 <b>" + (r.source_name || "") + "</b> 数据，共 <b>" + r.row_count + "</b> 个时间点" +
      (r.time_min ? "（" + r.time_min + " ~ " + r.time_max + "）" : "") + "。";
  }

  renderWideTable(r.wide_preview_rows, r.wide_columns, r.wide_header_rows);
  document.getElementById("wideWrap").classList.remove("hidden");
  setTimeout(updateStickyHeader, 100);
}

function updateStickyHeader() {
  const thead = document.querySelector("#wideTable thead");
  if (!thead) return;
  const rows = thead.querySelectorAll("tr");
  let offset = 0;
  rows.forEach(tr => {
    const ths = tr.querySelectorAll("th");
    let rowHeight = 0;
    ths.forEach(th => { rowHeight = Math.max(rowHeight, th.offsetHeight); });
    ths.forEach(th => { th.style.top = offset + "px"; });
    offset += rowHeight;
  });
}

function ioOf(col) {
  if (col.includes("||")) {
    const parts = col.split("||");
    const last = parts[parts.length - 1];
    if (last === "总收率") return "ratio";
    if (last === "进总" || (last.includes("进") && !last.includes("总收率"))) return "进";
    if (last === "出总" || (last.includes("出") && !last.includes("总收率"))) return "出";
  }
  return "";
}

function renderWideTable(rows, columns, headerRows) {
  const table = document.getElementById("wideTable");
  const thead = table.querySelector("thead");
  thead.innerHTML = "";
  if (headerRows && headerRows.length) {
    headerRows.forEach(hrow => {
      const tr = document.createElement("tr");
      hrow.forEach(cell => {
        const th = document.createElement("th");
        th.textContent = cell.text || "";
        if (cell.rowspan) th.rowSpan = cell.rowspan;
        if (cell.colspan) th.colSpan = cell.colspan;
        tr.appendChild(th);
      });
      thead.appendChild(tr);
    });
  } else {
    const tr = document.createElement("tr");
    columns.forEach(col => {
      const th = document.createElement("th");
      th.textContent = col.includes("||") ? col.split("||").slice(1).join(" | ") : col;
      tr.appendChild(th);
    });
    thead.appendChild(tr);
  }
  const tbody = table.querySelector("tbody");
  tbody.innerHTML = "";
  if (!rows.length) {
    tbody.innerHTML = '<tr><td colspan="' + columns.length + '" class="muted">无数据</td></tr>';
    return;
  }
  rows.forEach(row => {
    const tr = document.createElement("tr");
    columns.forEach(col => {
      const td = document.createElement("td");
      const v = row[col];
      const io = ioOf(col);
      if (v === null || v === undefined || v === "") {
        td.textContent = "—";
        td.className = "null";
      } else if (typeof v === "number") {
        if (io === "ratio") {
          td.textContent = v.toFixed(2) + "%";
        } else {
          td.textContent = v.toLocaleString("zh-CN", { maximumFractionDigits: 3 });
        }
      } else {
        td.textContent = String(v);
      }
      if (io === "进") td.classList.add("col-in");
      else if (io === "出") td.classList.add("col-out");
      else if (io === "ratio") td.classList.add("col-ratio");
      tr.appendChild(td);
    });
    tbody.appendChild(tr);
  });
  updateStickyHeader();
}

document.getElementById("wideWrap").addEventListener("scroll", updateStickyHeader);
window.addEventListener("resize", updateStickyHeader);

function renderExportSources() {
  const prev = exportSource.value;
  exportSource.innerHTML = "";
  MODULES.forEach(m => {
    for (const g of ["daily", "hourly"]) {
      const s = m.sources[g];
      const opt = document.createElement("option");
      opt.value = s.source_id;
      opt.textContent = m.name + "（" + GRAN_NAMES[g] + "）" + (s.has_data ? "  " + s.row_count + " 点" : "  暂无");
      exportSource.appendChild(opt);
    }
  });
  const opts = [...exportSource.options];
  if (prev && opts.some(o => o.value === prev)) exportSource.value = prev;
  else {
    const firstWithData = opts.find(o => o.textContent.includes("点"));
    if (firstWithData) exportSource.value = firstWithData.value;
  }
  syncExportRange();
}

function currentSource() {
  for (const m of MODULES) {
    for (const g of ["daily", "hourly"]) {
      if (m.sources[g].source_id === exportSource.value) return m.sources[g];
    }
  }
  return null;
}

function syncExportRange() {
  const s = currentSource();
  if (!s) { exportHint.textContent = ""; return; }
  exportStart.value = s.time_min ? s.time_min.split(" ")[0] : "";
  exportEnd.value = s.time_max ? s.time_max.split(" ")[0] : "";
  exportStart.min = s.time_min ? s.time_min.split(" ")[0] : "";
  exportStart.max = s.time_max ? s.time_max.split(" ")[0] : "";
  exportEnd.min = s.time_min ? s.time_min.split(" ")[0] : "";
  exportEnd.max = s.time_max ? s.time_max.split(" ")[0] : "";
  if (!exportFilename.dataset.touched) {
    exportFilename.value = s.name + "_导出";
  }
  exportHint.textContent = s.has_data
    ? "数据范围：" + (s.time_min || "—") + " ~ " + (s.time_max || "—") + "，共 " + s.row_count + " 个时间点；保存位置由浏览器下载设置决定。"
    : "该数据源暂无数据。";
}

exportSource.addEventListener("change", syncExportRange);
exportFilename.addEventListener("input", () => { exportFilename.dataset.touched = "1"; });

exportBtn.addEventListener("click", () => {
  const s = currentSource();
  if (!s || !s.has_data) { alert("该数据源暂无数据，请先导入"); return; }
  const start = exportStart.value, end = exportEnd.value;
  if (start && end && start > end) { alert("开始日期不能晚于结束日期"); return; }
  const fn = exportFilename.value.trim();

  exportBtn.disabled = true;
  const orig = exportBtn.textContent;
  exportBtn.textContent = "导出中…";

  const fd = new FormData();
  fd.append("source_id", s.source_id);
  fd.append("start_date", start);
  fd.append("end_date", end);
  fd.append("filename", fn);

  const xhr = new XMLHttpRequest();
  xhr.open("POST", "/api/export");
  xhr.responseType = "blob";
  xhr.onload = () => {
    exportBtn.disabled = false; exportBtn.textContent = orig;
    if (xhr.status === 200) {
      const disp = xhr.getResponseHeader("Content-Disposition") || "";
      let name = fn ? (fn.toLowerCase().endsWith(".xlsx") ? fn : fn + ".xlsx") : (s.name + "_导出.xlsx");
      const m = disp.match(/filename\*=UTF-8''(.+)/);
      if (m) { try { name = decodeURIComponent(m[1]); } catch (e) {} }
      const blob = new Blob([xhr.response], { type: "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet" });
      const url = URL.createObjectURL(blob);
      const a = document.createElement("a");
      a.href = url; a.download = name;
      document.body.appendChild(a); a.click(); a.remove();
      setTimeout(() => URL.revokeObjectURL(url), 2000);
    } else {
      const reader = new FileReader();
      reader.onload = () => { let msg = "导出失败"; try { msg = JSON.parse(reader.result).detail || msg; } catch (e) {} alert(msg); };
      reader.readAsText(xhr.response);
    }
  };
  xhr.onerror = () => { exportBtn.disabled = false; exportBtn.textContent = orig; alert("网络错误，导出失败"); };
  xhr.send(fd);
});

init();
