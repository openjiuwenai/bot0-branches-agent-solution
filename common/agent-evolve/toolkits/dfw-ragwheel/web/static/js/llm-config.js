/**
 * DFW-RAG - LLM Config Page Script
 * 使用模板动态渲染表单，支持新增、编辑、删除、测试。
 */

(function () {
  'use strict';

  const ICONS = {
    spinner: '<svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round" class="spin"><circle cx="12" cy="12" r="10" stroke-dasharray="32" stroke-dashoffset="12" stroke-linecap="butt"/></svg>',
    check: '<svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><polyline points="20 6 9 17 4 12"/></svg>',
    x: '<svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><line x1="18" y1="6" x2="6" y2="18"/><line x1="6" y1="6" x2="18" y2="18"/></svg>',
    lightning: '<svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><polygon points="13 2 3 14 12 14 11 22 21 10 12 10 13 2"/></svg>',
    save: '<svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M19 21H5a2 2 0 0 1-2-2V5a2 2 0 0 1 2-2h11l5 5v11a2 2 0 0 1-2 2z"/></svg>'
  };

  let templates = [];
  let currentScaffold = null;
  let currentTemplateName = '';

  document.addEventListener('DOMContentLoaded', function () {
    bindEvents();
    loadTemplates();
    loadProfiles();
  });

  function bindEvents() {
    const openBtn = document.getElementById('btnOpenAddModal');
    const closeBtn = document.getElementById('btnCloseModal');
    const cancelBtn = document.getElementById('btnCancelModal');
    const templateSelect = document.getElementById('templateSelect');
    const saveBtn = document.getElementById('btnSaveProfile');
    const testBtn = document.getElementById('btnTestDraft');

    if (openBtn) openBtn.addEventListener('click', openAddModal);
    if (closeBtn) closeBtn.addEventListener('click', closeProfileModal);
    if (cancelBtn) cancelBtn.addEventListener('click', closeProfileModal);
    if (templateSelect) templateSelect.addEventListener('change', onTemplateChange);
    if (saveBtn) saveBtn.addEventListener('click', saveProfile);
    if (testBtn) testBtn.addEventListener('click', testDraft);

    const modal = document.getElementById('profileModal');
    if (modal) {
      modal.addEventListener('click', (e) => {
        if (e.target === modal) closeProfileModal();
      });
    }

    bindTemplateEvents();
    bindSidebarPanels();
  }

  function bindSidebarPanels() {
    document.querySelectorAll('.sidebar-link[data-panel]').forEach(link => {
      link.addEventListener('click', (e) => {
        e.preventDefault();
        const panelId = link.dataset.panel;
        document.querySelectorAll('.sidebar-link[data-panel]').forEach(l => l.classList.remove('active'));
        link.classList.add('active');
        document.querySelectorAll('.config-panel').forEach(p => p.classList.remove('active'));
        const target = document.getElementById(`panel-${panelId}`);
        if (target) target.classList.add('active');
      });
    });
  }

  function bindTemplateEvents() {
    const openBtn = document.getElementById('btnOpenTemplateModal');
    const closeBtn = document.getElementById('btnCloseTemplateModal');
    const cancelBtn = document.getElementById('btnCancelTemplateModal');
    const saveBtn = document.getElementById('btnSaveTemplate');
    const closeViewBtn = document.getElementById('btnCloseViewTemplate');
    const closeViewX = document.getElementById('btnCloseViewTemplateModal');

    if (openBtn) openBtn.addEventListener('click', openTemplateModal);
    if (closeBtn) closeBtn.addEventListener('click', closeTemplateModal);
    if (cancelBtn) cancelBtn.addEventListener('click', closeTemplateModal);
    if (saveBtn) saveBtn.addEventListener('click', saveTemplate);
    if (closeViewBtn) closeViewBtn.addEventListener('click', closeViewTemplateModal);
    if (closeViewX) closeViewX.addEventListener('click', closeViewTemplateModal);

    const templateModal = document.getElementById('templateModal');
    if (templateModal) {
      templateModal.addEventListener('click', (e) => {
        if (e.target === templateModal) closeTemplateModal();
      });
    }

    const viewTemplateModal = document.getElementById('viewTemplateModal');
    if (viewTemplateModal) {
      viewTemplateModal.addEventListener('click', (e) => {
        if (e.target === viewTemplateModal) closeViewTemplateModal();
      });
    }
  }

  /* ---------- Data loading ---------- */

  async function loadTemplates() {
    try {
      const data = await API.request('GET', 'api/llm/templates');
      templates = Array.isArray(data) ? data : [];
      const select = document.getElementById('templateSelect');
      if (select) {
        select.innerHTML = '<option value="">请选择模板</option>';
        templates.forEach(t => {
          const option = document.createElement('option');
          option.value = t.name;
          option.textContent = `${App.escapeHtml(t.display_name)} (${App.escapeHtml(t.name)})`;
          select.appendChild(option);
        });
      }
      renderTemplateList(templates);
    } catch (err) {
      App.showToast('加载模板失败：' + err.message, 'error');
      renderTemplateList([]);
    }
  }

  async function loadProfiles() {
    const container = document.getElementById('profileListContainer');
    if (!container) return;
    container.innerHTML = '<div class="empty-cell">加载中...</div>';

    try {
      const profiles = await API.request('GET', 'api/llm/profiles');
      renderProfileList(Array.isArray(profiles) ? profiles : []);
    } catch (err) {
      container.innerHTML = `<div class="empty-cell" style="color: var(--color-danger);">
          加载失败：${App.escapeHtml(err.message)}
        </div>`;
      App.showToast('加载配置失败：' + err.message, 'error');
    }
  }

  function renderProfileList(profiles) {
    const container = document.getElementById('profileListContainer');
    if (!container) return;

    if (!profiles.length) {
      container.innerHTML = '<div class="empty-cell">暂无 LLM 配置，点击右上角"新增配置"添加。</div>';
      return;
    }

    const table = document.createElement('table');
    table.className = 'data-table';
    table.innerHTML = `
      <thead>
        <tr>
          <th>名称</th>
          <th>模板</th>
          <th>基础 URL</th>
          <th>模型</th>
          <th>API Key</th>
          <th class="text-end">操作</th>
        </tr>
      </thead>
      <tbody></tbody>
    `;

    const tbody = table.querySelector('tbody');
    profiles.forEach(p => {
      const tr = document.createElement('tr');
      tr.innerHTML = `
        <td><strong>${App.escapeHtml(p.name)}</strong></td>
        <td><span class="badge badge-info">${App.escapeHtml(p.template)}</span></td>
        <td>${App.escapeHtml(p.base_url || '-')}</td>
        <td>${App.escapeHtml(p.model || '-')}</td>
        <td><span class="api-key-mask">${App.escapeHtml(p.api_key_mask || '***')}</span></td>
        <td class="text-end">
          <div class="action-buttons">
            <button type="button" class="btn btn-success btn-sm btn-test-saved" data-id="${App.escapeHtml(p.id)}">测试</button>
            <button type="button" class="btn btn-secondary btn-sm btn-edit" data-id="${App.escapeHtml(p.id)}">编辑</button>
            <button type="button" class="btn btn-danger btn-sm btn-delete" data-id="${App.escapeHtml(p.id)}">删除</button>
          </div>
        </td>
      `;
      tbody.appendChild(tr);
    });

    container.innerHTML = '';
    container.appendChild(table);

    container.querySelectorAll('.btn-test-saved').forEach(btn => {
      btn.addEventListener('click', () => testSavedProfile(btn.dataset.id));
    });
    container.querySelectorAll('.btn-edit').forEach(btn => {
      btn.addEventListener('click', () => openEditModal(btn.dataset.id));
    });
    container.querySelectorAll('.btn-delete').forEach(btn => {
      btn.addEventListener('click', () => deleteProfile(btn.dataset.id));
    });
  }

  function renderTemplateList(items) {
    const container = document.getElementById('templateListContainer');
    if (!container) return;

    if (!items.length) {
      container.innerHTML = '<div class="empty-cell">暂无模板，可点击右上角"新增模板"添加。</div>';
      return;
    }

    const table = document.createElement('table');
    table.className = 'data-table';
    table.innerHTML = `
      <thead>
        <tr>
          <th>模板名称</th>
          <th>显示名称</th>
          <th>说明</th>
          <th class="text-end">操作</th>
        </tr>
      </thead>
      <tbody></tbody>
    `;

    const tbody = table.querySelector('tbody');
    items.forEach(t => {
      const tr = document.createElement('tr');
      tr.innerHTML = `
        <td><code>${App.escapeHtml(t.name)}</code></td>
        <td>${App.escapeHtml(t.display_name || '-')}</td>
        <td>${App.escapeHtml(t.description || '-')}</td>
        <td class="text-end">
          <button type="button" class="btn btn-secondary btn-sm btn-view-template" data-name="${App.escapeHtml(t.name)}">查看</button>
        </td>
      `;
      tbody.appendChild(tr);
    });

    container.innerHTML = '';
    container.appendChild(table);

    container.querySelectorAll('.btn-view-template').forEach(btn => {
      btn.addEventListener('click', () => viewTemplate(btn.dataset.name));
    });
  }

  /* ---------- Modal operations ---------- */

  function openProfileModal() {
    const modal = document.getElementById('profileModal');
    if (modal) {
      modal.classList.add('active');
      modal.setAttribute('aria-hidden', 'false');
    }
  }

  function closeProfileModal() {
    const modal = document.getElementById('profileModal');
    if (modal) {
      modal.classList.remove('active');
      modal.setAttribute('aria-hidden', 'true');
    }
  }

  function openTemplateModal() {
    const modal = document.getElementById('templateModal');
    const yamlEl = document.getElementById('templateYaml');
    if (yamlEl) yamlEl.value = '';
    hideTemplateResult();
    if (modal) {
      modal.classList.add('active');
      modal.setAttribute('aria-hidden', 'false');
    }
  }

  function closeTemplateModal() {
    const modal = document.getElementById('templateModal');
    if (modal) {
      modal.classList.remove('active');
      modal.setAttribute('aria-hidden', 'true');
    }
  }

  function showTemplateResult(success, message) {
    const el = document.getElementById('templateResult');
    if (!el) return;
    el.style.display = 'block';
    el.className = 'llm-test-result ' + (success ? 'success' : 'error');
    el.innerHTML = `<strong>${success ? `${ICONS.check} 成功` : `${ICONS.x} 失败`}</strong>
      <p>${App.escapeHtml(message)}</p>`;
  }

  function hideTemplateResult() {
    const el = document.getElementById('templateResult');
    if (!el) return;
    el.style.display = 'none';
    el.className = 'llm-test-result';
    el.innerHTML = '';
  }

  async function saveTemplate() {
    const yamlEl = document.getElementById('templateYaml');
    const yaml = yamlEl ? yamlEl.value.trim() : '';
    if (!yaml) {
      showTemplateResult(false, '请输入模板 YAML 内容');
      return;
    }

    App.showLoading('保存模板中…');
    try {
      const result = await API.request('POST', 'api/llm/templates', { yaml });
      if (result.success) {
        App.showToast('模板保存成功', 'success');
        closeTemplateModal();
        await loadTemplates();
      } else {
        showTemplateResult(false, result.message || '保存失败');
      }
    } catch (err) {
      showTemplateResult(false, '保存失败：' + err.message);
    } finally {
      App.hideLoading();
    }
  }

  async function viewTemplate(name) {
    App.showLoading('加载模板…');
    try {
      const data = await API.request('GET', `api/llm/templates/${encodeURIComponent(name)}/yaml`);
      const contentEl = document.getElementById('viewTemplateContent');
      const titleEl = document.getElementById('viewTemplateTitle');
      if (contentEl) contentEl.textContent = data.yaml || '';
      if (titleEl) titleEl.textContent = `模板详情：${App.escapeHtml(name)}`;
      openViewTemplateModal();
    } catch (err) {
      App.showToast('加载模板详情失败：' + err.message, 'error');
    } finally {
      App.hideLoading();
    }
  }

  function openViewTemplateModal() {
    const modal = document.getElementById('viewTemplateModal');
    if (modal) {
      modal.classList.add('active');
      modal.setAttribute('aria-hidden', 'false');
    }
  }

  function closeViewTemplateModal() {
    const modal = document.getElementById('viewTemplateModal');
    if (modal) {
      modal.classList.remove('active');
      modal.setAttribute('aria-hidden', 'true');
    }
  }

  function openAddModal() {
    document.getElementById('profileId').value = '';
    document.getElementById('originalName').value = '';
    document.getElementById('profileName').value = '';
    const templateSelect = document.getElementById('templateSelect');
    templateSelect.value = '';
    templateSelect.disabled = false;
    document.getElementById('modalTitle').textContent = '新增 LLM 配置';
    document.getElementById('templateFormContainer').innerHTML = '<div class="empty-cell">请先选择模板</div>';
    hideTestResult();
    currentScaffold = null;
    currentTemplateName = '';
    openProfileModal();
  }

  async function openEditModal(id) {
    App.showLoading('加载配置…');
    try {
      const profile = await API.request('GET', `api/llm/profiles/${id}`);
      document.getElementById('profileId').value = profile.id || '';
      document.getElementById('originalName').value = profile.name || '';
      document.getElementById('profileName').value = profile.name || '';
      const templateSelect = document.getElementById('templateSelect');
      templateSelect.value = profile.template || '';
      templateSelect.disabled = true;
      document.getElementById('modalTitle').textContent = '编辑 LLM 配置';
      hideTestResult();

      currentTemplateName = profile.template || '';
      currentScaffold = profile;
      await renderFormFromTemplate(profile.template, profile);
      openProfileModal();
    } catch (err) {
      App.showToast('加载配置失败：' + err.message, 'error');
    } finally {
      App.hideLoading();
    }
  }

  async function onTemplateChange() {
    const templateName = document.getElementById('templateSelect').value;
    currentTemplateName = templateName;
    hideTestResult();

    if (!templateName) {
      document.getElementById('templateFormContainer').innerHTML = '<div class="empty-cell">请先选择模板</div>';
      currentScaffold = null;
      return;
    }

    App.showLoading('加载模板…');
    try {
      const scaffold = await API.request('GET', `api/llm/templates/${templateName}/scaffold`);
      currentScaffold = scaffold;
      await renderFormFromTemplate(templateName, scaffold);
    } catch (err) {
      App.showToast('加载模板表单失败：' + err.message, 'error');
    } finally {
      App.hideLoading();
    }
  }

  async function renderFormFromTemplate(templateName, values) {
    const data = await API.request('GET', `api/llm/templates/${templateName}/form`);
    const container = document.getElementById('templateFormContainer');
    container.innerHTML = '';

    const row = document.createElement('div');
    row.className = 'form-row';
    row.style.gridTemplateColumns = 'repeat(2, 1fr)';

    const formFields = data.form || [];
    formFields.forEach((field, index) => {
      const group = document.createElement('div');
      group.className = 'form-group';

      const label = document.createElement('label');
      label.className = 'form-label';
      label.textContent = field.label;
      if (field.required) {
        label.innerHTML += ' <span style="color: var(--color-danger)">*</span>';
      }
      group.appendChild(label);

      const inputId = `field_${index}`;
      let input;

      if (field.type === 'select' && field.options) {
        input = document.createElement('select');
        input.className = 'form-select';
        field.options.forEach(opt => {
          const option = document.createElement('option');
          option.value = opt.value;
          option.textContent = opt.label;
          input.appendChild(option);
        });
      } else if (field.type === 'checkbox') {
        input = document.createElement('input');
        input.type = 'checkbox';
        input.style.width = '18px';
        input.style.height = '18px';
      } else if (field.type === 'textarea') {
        input = document.createElement('textarea');
        input.className = 'form-textarea';
        input.rows = 3;
      } else if (field.type === 'password' || field.sensitive) {
        input = document.createElement('input');
        input.type = 'password';
        input.className = 'form-input';
        input.setAttribute('autocomplete', 'new-password');
      } else {
        input = document.createElement('input');
        input.type = (field.type === 'int' || field.type === 'number' || field.type === 'float') ? 'number' : 'text';
        input.className = 'form-input';
        if (field.type === 'number' || field.type === 'float') {
          if (field.step !== undefined) input.step = field.step;
          if (field.min !== undefined) input.min = field.min;
          if (field.max !== undefined) input.max = field.max;
        }
      }

      input.id = inputId;
      input.dataset.key = field.key;
      if (field.placeholder) input.placeholder = field.placeholder;

      const value = getValueByPath(values, field.key);
      if (field.type === 'checkbox') {
        input.checked = value === true || value === 'true';
      } else if (value !== undefined && value !== null) {
        input.value = value;
      } else if (field.default !== undefined) {
        input.value = field.default;
      }

      group.appendChild(input);

      if (field.help) {
        const help = document.createElement('div');
        help.className = 'form-hint';
        help.textContent = field.help;
        group.appendChild(help);
      }

      row.appendChild(group);
    });

    container.appendChild(row);
  }

  function getValueByPath(obj, path) {
    const parts = path.split('.');
    let value = obj;
    for (const part of parts) {
      if (value === null || value === undefined) return undefined;
      value = value[part];
    }
    return value;
  }

  function setValueByPath(obj, path, value) {
    const parts = path.split('.');
    let target = obj;
    for (let i = 0; i < parts.length - 1; i++) {
      if (!(parts[i] in target) || typeof target[parts[i]] !== 'object') {
        target[parts[i]] = {};
      }
      target = target[parts[i]];
    }
    target[parts[parts.length - 1]] = value;
  }

  function collectProfileData() {
    const id = document.getElementById('profileId').value;
    const originalName = document.getElementById('originalName').value;
    const name = document.getElementById('profileName').value.trim();
    const template = document.getElementById('templateSelect').value;

    if (!name) throw new Error('请输入配置名称');
    if (!template) throw new Error('请选择模板');

    const data = {
      id: id || undefined,
      original_name: originalName || undefined,
      name: name,
      template: template
    };

    const templateObj = templates.find(t => t.name === template);
    const formFields = templateObj ? (templateObj.form || []) : [];

    formFields.forEach(field => {
      const input = document.querySelector(`[data-key="${field.key}"]`);
      if (!input) return;

      let value;
      if (field.type === 'checkbox') {
        value = input.checked;
      } else if (field.type === 'int') {
        value = parseInt(input.value, 10) || 0;
      } else if (field.type === 'number' || field.type === 'float') {
        value = parseFloat(input.value) || 0;
      } else {
        value = input.value;
      }

      data[field.key] = value;
    });

    return data;
  }

  /* ---------- Save / Test ---------- */

  async function saveProfile() {
    App.showLoading('保存中…');
    try {
      const data = collectProfileData();
      await API.request('POST', 'api/llm/profiles', data);
      App.showToast('保存成功', 'success');
      closeProfileModal();
      await loadProfiles();
    } catch (err) {
      App.showToast('保存失败：' + err.message, 'error');
    } finally {
      App.hideLoading();
    }
  }

  async function testDraft() {
    App.showLoading('测试中…');
    try {
      const data = collectProfileData();
      const result = await API.request('POST', 'api/llm/profiles/test-draft', data);
      showTestResult(result);
    } catch (err) {
      App.showToast('测试失败：' + err.message, 'error');
    } finally {
      App.hideLoading();
    }
  }

  async function testSavedProfile(id) {
    App.showLoading('测试中…');
    try {
      const result = await API.request('POST', `api/llm/profiles/${id}/test`, {});
      showTestResult(result);
    } catch (err) {
      App.showToast('测试失败：' + err.message, 'error');
    } finally {
      App.hideLoading();
    }
  }

  function showTestResult(result) {
    const el = document.getElementById('testResult');
    el.style.display = 'block';
    el.className = 'llm-test-result ' + (result.success ? 'success' : 'error');

    if (result.success) {
      App.showToast('LLM 配置测试成功', 'success');
    }

    let html = `<strong>${result.success ? `${ICONS.check} 测试成功` : `${ICONS.x} 测试失败`}</strong>
      <p>${App.escapeHtml(result.message || '')}</p>`;
    if (result.response) {
      html += `<pre class="llm-result-content">${App.escapeHtml(formatJson(result.response))}</pre>`;
    } else if (result.error) {
      html += `<pre class="llm-result-content">${App.escapeHtml(formatJson(result.error))}</pre>`;
    }
    el.innerHTML = html;
    el.scrollIntoView({ behavior: 'smooth', block: 'nearest' });
  }

  function hideTestResult() {
    const el = document.getElementById('testResult');
    el.style.display = 'none';
    el.className = 'llm-test-result';
    el.innerHTML = '';
  }

  async function deleteProfile(id) {
    const ok = await App.confirm('确定要删除这个 LLM 配置吗？');
    if (!ok) return;

    App.showLoading('删除中…');
    try {
      const result = await API.request('DELETE', `api/llm/profiles/${id}`);
      if (result.success) {
        App.showToast('删除成功', 'success');
        await loadProfiles();
      } else {
        App.showToast('删除失败', 'error');
      }
    } catch (err) {
      App.showToast('删除失败：' + err.message, 'error');
    } finally {
      App.hideLoading();
    }
  }

  function formatJson(obj) {
    try {
      return JSON.stringify(obj, null, 2);
    } catch (e) {
      return String(obj);
    }
  }
})();
