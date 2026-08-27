/**
 * DFW-RAG - Pipeline Pages Script (Extract / Synthesize / Verify)
 * DataWave style: uses global App utilities and API module.
 */

(function () {
  'use strict';

  const ICONS = {
    upload: '<svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M21 15v4a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2v-4"/><polyline points="7 10 12 15 17 10"/><line x1="12" y1="15" x2="12" y2="3"/></svg>',
    run: '<svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><polygon points="5 3 19 12 5 21 5 3"/></svg>',
    spinner: '<svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round" class="spin"><circle cx="12" cy="12" r="10" stroke-dasharray="32" stroke-dashoffset="12" stroke-linecap="butt"/></svg>',
    check: '<svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><polyline points="20 6 9 17 4 12"/></svg>',
    x: '<svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><line x1="18" y1="6" x2="6" y2="18"/><line x1="6" y1="6" x2="18" y2="18"/></svg>',
    refresh: '<svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><polyline points="23 4 23 10 17 10"/><polyline points="1 20 1 14 7 14"/><path d="M3.51 9a9 9 0 0 1 14.85-3.36L23 10M1 14l4.64 4.36A9 9 0 0 0 20.49 15"/></svg>',
    download: '<svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M21 15v4a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2v-4"/><polyline points="7 10 12 15 17 10"/><line x1="12" y1="15" x2="12" y2="3"/></svg>'
  };

  const PipelineApp = {
    init() {
      this.bindSidebarNavigation();
      this.bindVerifyTabs();
      this.bindDropzones();
      this.bindFileSourceTabs();
      this.bindClearFileButtons();
      this.bindReplaceWarning();
      this.bindFilenameGeneration();
      this.bindVerifyBatchOutput();
      this.bindForms();
      this.bindClearOutput();
      this.bindCollectionRefresh();

      if (document.getElementById('collections-table')) {
        this.loadCollections();
      }
      if (document.querySelector('.outputs-select')) {
        this.loadOutputsFiles();
      }
      if (document.querySelector('.llm-config-select')) {
        this.loadLlmConfigSelects();
      }
      if (document.querySelector('.embedding-config-select')) {
        this.loadEmbeddingConfigSelects();
      }
    },

    /* ---------- Sidebar navigation ---------- */
    bindSidebarNavigation() {
      document.querySelectorAll('.sidebar-link[data-panel]').forEach(link => {
        link.addEventListener('click', () => {
          const panelId = link.dataset.panel;
          document.querySelectorAll('.sidebar-link[data-panel]').forEach(l => l.classList.remove('active'));
          link.classList.add('active');

          document.querySelectorAll('.extract-panel').forEach(panel => panel.classList.remove('active'));
          const targetPanel = document.getElementById(`panel-${panelId}`);
          if (targetPanel) targetPanel.classList.add('active');

          if (panelId === 'collections') this.loadCollections();
        });
      });
    },

    switchPanel(panelId) {
      const link = document.querySelector(`.sidebar-link[data-panel="${panelId}"]`);
      if (link) {
        link.click();
      } else {
        document.querySelectorAll('.sidebar-link[data-panel]').forEach(l => l.classList.remove('active'));
        document.querySelectorAll('.extract-panel').forEach(p => p.classList.remove('active'));
        const targetPanel = document.getElementById(`panel-${panelId}`);
        if (targetPanel) targetPanel.classList.add('active');
      }
    },

    /* ---------- Verify tabs ---------- */
    bindVerifyTabs() {
      document.querySelectorAll('.verify-tab').forEach(tab => {
        tab.addEventListener('click', () => {
          const target = tab.dataset.verifyTab;
          document.querySelectorAll('.verify-tab').forEach(t => t.classList.remove('active'));
          tab.classList.add('active');

          document.querySelectorAll('.verify-content').forEach(c => c.classList.remove('active'));
          const content = document.getElementById(`verify-${target}`);
          if (content) content.classList.add('active');
        });
      });
    },

    /* ---------- Collections ---------- */
    async loadCollections() {
      const tbody = document.querySelector('#collections-table tbody');
      const refreshBtn = document.getElementById('refresh-collections');
      if (!tbody) return;

      if (refreshBtn) {
        refreshBtn.disabled = true;
        refreshBtn.innerHTML = `${ICONS.spinner} 刷新中…`;
      }

      try {
        const data = await API.collections.list();
        if (!data.success) {
          tbody.innerHTML = `<tr><td colspan="4" class="empty-cell" style="color: var(--color-danger);">加载失败：${App.escapeHtml(data.message || '未知错误')}</td></tr>`;
          return;
        }

        const collections = data.collections || [];
        if (collections.length === 0) {
          tbody.innerHTML = '<tr><td colspan="4" class="empty-cell">暂无 Collection</td></tr>';
          return;
        }

        tbody.innerHTML = collections.map(item => `
          <tr>
            <td><code>${App.escapeHtml(item.name)}</code></td>
            <td>${item.count !== null && item.count !== undefined ? item.count : '-'}</td>
            <td>
              ${item.embedding_config ? `<span class="badge badge-info">${App.escapeHtml(item.embedding_config)}</span>` : '-'}
              ${item.embedding_model ? `<div class="text-secondary" style="font-size: 12px; margin-top: 2px;">${App.escapeHtml(item.embedding_model)}</div>` : ''}
            </td>
            <td class="text-end">
              <div class="action-buttons">
                <button type="button" class="btn btn-secondary btn-sm btn-use-collection" data-collection="${App.escapeHtml(item.name)}">使用</button>
                <button type="button" class="btn btn-danger btn-sm btn-delete-collection" data-collection="${App.escapeHtml(item.name)}">删除</button>
              </div>
            </td>
          </tr>
        `).join('');

        this.populateCollectionSelects(collections);

        tbody.querySelectorAll('.btn-use-collection').forEach(btn => {
          btn.addEventListener('click', (e) => {
            e.preventDefault();
            const collection = btn.dataset.collection;
            this.switchPanel('execute');
            const importBaseInput = document.getElementById('collection-import-base');
            if (importBaseInput) importBaseInput.value = collection;
            const targetKbInput = document.getElementById('target-kb');
            if (targetKbInput) targetKbInput.value = collection;
            const resultInput = document.getElementById('collection-result');
            if (resultInput) resultInput.value = collection;
          });
        });

        tbody.querySelectorAll('.btn-delete-collection').forEach(btn => {
          btn.addEventListener('click', (e) => {
            e.preventDefault();
            this.deleteCollection(btn.dataset.collection);
          });
        });
      } catch (error) {
        tbody.innerHTML = `<tr><td colspan="4" class="empty-cell" style="color: var(--color-danger);">加载失败：${App.escapeHtml(error.message)}</td></tr>`;
      } finally {
        if (refreshBtn) {
          refreshBtn.disabled = false;
          refreshBtn.innerHTML = `${ICONS.refresh} 刷新`;
        }
      }
    },

    populateCollectionSelects(collections) {
      const selects = [
        document.getElementById('verify-single-collection'),
        document.getElementById('verify-batch-collection')
      ].filter(Boolean);
      if (selects.length === 0) return;

      const options = collections.map(item => {
        const name = App.escapeHtml(item.name);
        const count = item.count !== null && item.count !== undefined ? item.count : '-';
        return `<option value="${name}">${name}（${count}）</option>`;
      }).join('');

      selects.forEach(select => {
        const currentValue = select.value;
        select.innerHTML = '<option value="" disabled selected>请选择 Collection</option>' + options;
        if (currentValue && collections.some(c => c.name === currentValue)) {
          select.value = currentValue;
        }
      });
    },

    async deleteCollection(collectionName) {
      if (!collectionName) return;
      const ok = await App.confirm(`确定要删除 Collection "${collectionName}" 吗？\n该操作会清空该 collection 中的所有数据，且不可恢复。`);
      if (!ok) return;

      this.appendConsoleOutput(`[删除 Collection] ${collectionName} ...`);
      try {
        const data = await API.collections.delete(collectionName);
        if (data.success) {
          this.appendConsoleOutput(`[删除成功] Collection "${collectionName}" 已删除`);
          App.showToast(`Collection "${collectionName}" 已删除`, 'success');
        } else {
          this.appendConsoleOutput(`[删除失败] ${data.message || '未知错误'}`);
          App.showToast(`删除失败：${data.message || '未知错误'}`, 'error');
        }
      } catch (error) {
        this.appendConsoleOutput(`[删除异常] ${error.message}`);
        App.showToast(`删除失败：${error.message}`, 'error');
      } finally {
        this.loadCollections();
      }
    },

    bindCollectionRefresh() {
      const refreshBtn = document.getElementById('refresh-collections');
      if (refreshBtn) refreshBtn.addEventListener('click', () => this.loadCollections());
    },

    appendConsoleOutput(text) {
      const consoleEl = document.getElementById('console-output');
      if (!consoleEl) return;
      const code = consoleEl.querySelector('code') || consoleEl;
      const prefix = new Date().toLocaleTimeString();
      code.textContent += `\n[${prefix}] ${text}`;
      consoleEl.scrollTop = consoleEl.scrollHeight;
    },

    /* ---------- Outputs ---------- */
    async loadOutputsFiles() {
      try {
        const data = await API.outputs.list();
        if (!data.success) {
          console.warn('加载 outputs 文件失败：', data.message);
          return;
        }
        const files = data.files || [];
        document.querySelectorAll('.outputs-select select').forEach(select => {
          const currentValue = select.value;
          select.innerHTML = '<option value="">请选择 outputs 中的文件…</option>' +
            files.map(f => `<option value="${App.escapeHtml(f.path)}">${App.escapeHtml(f.name)}</option>`).join('');
          select.value = currentValue;
        });
      } catch (error) {
        console.error('加载 outputs 文件失败：', error);
      }
    },

    /* ---------- File source tabs ---------- */
    bindFileSourceTabs() {
      document.querySelectorAll('.file-source-tabs').forEach(group => {
        const target = group.dataset.target;
        const dropzone = document.getElementById(`drop-${target}`);
        const selectWrapper = document.getElementById(`select-wrapper-${target}`);
        const fileInput = document.getElementById(`file-${target}`);
        const select = document.getElementById(`select-${target}`);
        const buttons = group.querySelectorAll('.tab-btn');

        if (!dropzone || !selectWrapper || !fileInput || !select) return;

        select.addEventListener('change', () => {
          this.updateSelectedFilename(select);
          select.dispatchEvent(new Event('auto-filename'));
        });

        buttons.forEach(btn => {
          btn.addEventListener('click', () => {
            buttons.forEach(b => b.classList.remove('active'));
            btn.classList.add('active');

            const isUpload = btn.dataset.source === 'upload';
            if (isUpload) {
              dropzone.classList.remove('hidden');
              selectWrapper.classList.add('hidden');
              fileInput.disabled = false;
              fileInput.required = true;
              select.disabled = true;
              select.required = false;
              this.updateFileName(fileInput);
            } else {
              dropzone.classList.add('hidden');
              selectWrapper.classList.remove('hidden');
              fileInput.disabled = true;
              fileInput.required = false;
              select.disabled = false;
              select.required = true;
              this.loadOutputsFiles().then(() => this.updateSelectedFilename(select));
            }
          });
        });
      });
    },

    bindClearFileButtons() {
      document.querySelectorAll('.clear-file-btn').forEach(btn => {
        btn.addEventListener('click', () => {
          const target = btn.dataset.target;
          const fileInput = document.getElementById(`file-${target}`);
          const select = document.getElementById(`select-${target}`);
          const display = document.getElementById(`name-${target}`);
          const uploadBtn = document.querySelector(`.file-source-tabs[data-target="${target}"] .tab-btn[data-source="upload"]`);
          const outputsBtn = document.querySelector(`.file-source-tabs[data-target="${target}"] .tab-btn[data-source="outputs"]`);
          const dropzone = document.getElementById(`drop-${target}`);
          const selectWrapper = document.getElementById(`select-wrapper-${target}`);

          if (fileInput) {
            fileInput.value = '';
            fileInput.required = true;
            fileInput.disabled = false;
          }
          if (select) {
            select.value = '';
            select.required = false;
            select.disabled = true;
          }
          if (display) display.textContent = '未选择文件';

          if (uploadBtn) uploadBtn.classList.add('active');
          if (outputsBtn) outputsBtn.classList.remove('active');
          if (dropzone) dropzone.classList.remove('hidden');
          if (selectWrapper) selectWrapper.classList.add('hidden');

          if (target === 'extract-run') {
            const outInput = document.getElementById('out-path');
            if (outInput) outInput.value = '';
          }
          if (target === 'import-result') {
            const exportIdsInput = document.getElementById('export-ids');
            if (exportIdsInput) exportIdsInput.value = '';
          }
        });
      });
    },

    /* ---------- Dropzones ---------- */
    bindDropzones() {
      document.querySelectorAll('.dropzone').forEach(zone => {
        const input = zone.querySelector('.file-input');
        if (!input) return;

        zone.addEventListener('dragover', (e) => {
          e.preventDefault();
          zone.classList.add('dragover');
        });

        zone.addEventListener('dragleave', () => {
          zone.classList.remove('dragover');
        });

        zone.addEventListener('drop', (e) => {
          e.preventDefault();
          zone.classList.remove('dragover');
          if (e.dataTransfer.files.length) {
            input.files = e.dataTransfer.files;
            this.updateFileName(input);
            input.dispatchEvent(new Event('change', { bubbles: true }));
          }
        });

        input.addEventListener('change', () => this.updateFileName(input));
      });
    },

    updateFileName(input) {
      const displayId = 'name-' + input.id.replace('file-', '');
      const display = document.getElementById(displayId);
      if (!display) return;
      display.textContent = (input.files && input.files.length) ? input.files[0].name : '未选择文件';
    },

    updateSelectedFilename(select) {
      const displayId = 'name-' + select.id.replace('select-', '');
      const display = document.getElementById(displayId);
      if (!display) return;
      const text = select.options[select.selectedIndex]?.text || '未选择文件';
      display.textContent = select.value ? text : '未选择文件';
    },

    /* ---------- Warnings & helpers ---------- */
    bindReplaceWarning() {
      document.querySelectorAll('.replace-checkbox').forEach(box => {
        box.addEventListener('change', async () => {
          if (box.checked) {
            const ok = await App.confirm('勾选"覆盖已有 Collection"后，导入时会清空该 Collection 中已有的全部数据，且不可恢复。\n\n请确认是否继续勾选？');
            if (!ok) box.checked = false;
          }
        });
      });
    },

    bindFilenameGeneration() {
      const extractInput = document.getElementById('file-extract-run');
      const extractSelect = document.getElementById('select-extract-run');
      const outInput = document.getElementById('out-path');
      if (outInput) {
        const updateOut = (name) => {
          if (name) outInput.value = this.generateTimestampFilename(name, 'extract', '.xlsx');
        };
        if (extractInput) {
          extractInput.addEventListener('change', () => {
            const file = extractInput.files && extractInput.files[0];
            if (file) updateOut(file.name);
          });
        }
        if (extractSelect) {
          extractSelect.addEventListener('change', () => {
            if (extractSelect.value) updateOut(extractSelect.value.split('/').pop());
          });
          extractSelect.addEventListener('auto-filename', () => {
            if (extractSelect.value) updateOut(extractSelect.value.split('/').pop());
          });
        }
      }

      const resultInput = document.getElementById('file-import-result');
      const resultSelect = document.getElementById('select-import-result');
      const exportIdsInput = document.getElementById('export-ids');
      if (exportIdsInput) {
        const updateExportIds = (name) => {
          if (name) exportIdsInput.value = 'outputs/' + this.generateTimestampFilename(name, 'extracted_ids', '.xlsx');
        };
        if (resultInput) {
          resultInput.addEventListener('change', () => {
            const file = resultInput.files && resultInput.files[0];
            if (file) updateExportIds(file.name);
          });
        }
        if (resultSelect) {
          resultSelect.addEventListener('change', () => {
            if (resultSelect.value) updateExportIds(resultSelect.value.split('/').pop());
          });
          resultSelect.addEventListener('auto-filename', () => {
            if (resultSelect.value) updateExportIds(resultSelect.value.split('/').pop());
          });
        }
      }

      const synthesizeInput = document.getElementById('file-synthesize-single');
      const synthesizeOutput = document.getElementById('single-output');
      if (synthesizeOutput) {
        const updateSynthesizeOut = (name) => {
          if (name) synthesizeOutput.value = 'outputs/' + this.generateTimestampFilename(name, 'synthesized', '.csv');
        };
        if (synthesizeInput) {
          synthesizeInput.addEventListener('change', () => {
            const file = synthesizeInput.files && synthesizeInput.files[0];
            if (file) updateSynthesizeOut(file.name);
          });
        }
      }
    },

    bindVerifyBatchOutput() {
      const fileInput = document.getElementById('file-verify-batch');
      const outputInput = document.getElementById('verify-batch-output');
      if (!fileInput || !outputInput) return;

      fileInput.addEventListener('change', () => {
        const file = fileInput.files && fileInput.files[0];
        if (file) outputInput.value = this.generateTimestampFilename(file.name, 'test_result', '.xlsx');
      });
    },

    generateTimestampFilename(originalName, tag, forceExt) {
      const base = originalName ? originalName.replace(/\\/g, '/').split('/').pop() : 'file';
      const dot = base.lastIndexOf('.');
      const name = dot > 0 ? base.slice(0, dot) : base;
      const ext = forceExt || (dot > 0 ? base.slice(dot) : '.xlsx');
      const now = new Date();
      const ts = now.getFullYear().toString() +
        String(now.getMonth() + 1).padStart(2, '0') +
        String(now.getDate()).padStart(2, '0') +
        String(now.getHours()).padStart(2, '0') +
        String(now.getMinutes()).padStart(2, '0') +
        String(now.getSeconds()).padStart(2, '0');
      return `${name}_${tag}_${ts}${ext}`;
    },

    async checkOutputFileExists(filename) {
      if (!filename) return false;
      try {
        const data = await API.outputs.list();
        if (!data.success) return false;
        return (data.files || []).some(f => f.name === filename);
      } catch (error) {
        console.error('检查 outputs 文件失败：', error);
        return false;
      }
    },

    /* ---------- Forms ---------- */
    bindForms() {
      document.querySelectorAll('form[data-endpoint]').forEach(form => {
        form.addEventListener('submit', async (e) => {
          e.preventDefault();
          const submitBtn = form.querySelector('button[type="submit"]');
          const originalHtml = submitBtn ? submitBtn.innerHTML : '';

          App.showLoading('正在执行，请稍候…');
          this.appendOutput('>> 开始执行：' + form.id);
          this.hideDownloadLinks();

          try {
            if (submitBtn) {
              submitBtn.disabled = true;
              submitBtn.innerHTML = `${ICONS.spinner} 执行中…`;
            }

            const result = await this.submitForm(form);
            this.renderResult(result);
            if (form.id === 'form-verify-single') this.renderVerifySingleResult(result);
            if (form.id === 'form-verify-batch') this.renderVerifyBatchResult(result);

            App.showToast(result.message || (result.success ? '执行成功' : '执行失败'), result.success ? 'success' : 'error');
            this.afterFormSubmit(form, result);
          } catch (err) {
            this.appendOutput('ERROR: ' + err.message);
            App.showToast('执行失败：' + err.message, 'error');
          } finally {
            App.hideLoading();
            if (submitBtn) {
              submitBtn.disabled = false;
              submitBtn.innerHTML = originalHtml;
            }
          }
        });
      });
    },

    async submitForm(form) {
      const endpoint = form.dataset.endpoint;
      if (!endpoint) throw new Error('表单缺少 data-endpoint 属性');

      const formData = new FormData(form);

      if (form.id === 'form-extract-run') {
        const outName = (formData.get('out') || '').toString().trim();
        if (!outName) throw new Error('请填写输出文件名');
        const fullOut = outName.startsWith('outputs/') ? outName : 'outputs/' + outName;
        if (await this.checkOutputFileExists(outName.replace(/^outputs\//, ''))) {
          const ok = await App.confirm(`outputs/${outName.replace(/^outputs\//, '')} 已存在，是否覆盖？`);
          if (!ok) throw new Error('已取消执行');
        }
        formData.set('out', fullOut);

        const source = form.querySelector('.file-source-tabs[data-target="extract-run"] .tab-btn.active');
        if (source && source.dataset.source === 'outputs') {
          const path = (formData.get('path') || '').toString();
          if (!path) throw new Error('请从 outputs 中选择一个输入文件');
        }
      }

      if (form.id === 'form-import-result') {
        const exportName = (formData.get('export_ids') || '').toString().trim();
        if (!exportName) throw new Error('请填写新增数据ID文件名');
        const fullExport = exportName.startsWith('outputs/') ? exportName : 'outputs/' + exportName;
        if (await this.checkOutputFileExists(exportName.replace(/^outputs\//, ''))) {
          const ok = await App.confirm(`outputs/${exportName.replace(/^outputs\//, '')} 已存在，是否覆盖？`);
          if (!ok) throw new Error('已取消执行');
        }
        formData.set('export_ids', fullExport);

        const source = form.querySelector('.file-source-tabs[data-target="import-result"] .tab-btn.active');
        if (source && source.dataset.source === 'outputs') {
          const path = (formData.get('path') || '').toString();
          if (!path) throw new Error('请从 outputs 中选择一个结果文件');
          if (!path.toLowerCase().endsWith('.xlsx')) throw new Error('结果入库请选择 .xlsx 文件');
        }
      }

      let responseData;
      switch (endpoint) {
        case 'api/extract/import_base':
          responseData = await API.extract.importBase(formData);
          break;
        case 'api/extract/run':
          responseData = await API.extract.run(formData);
          break;
        case 'api/extract/import_result':
          responseData = await API.extract.importResult(formData);
          break;
        case 'api/extract/cleanup':
          responseData = await API.extract.cleanup(formData);
          break;
        case 'api/synthesize/run':
          responseData = await API.synthesize.run(formData);
          break;
        case 'api/verify/query':
          responseData = await API.verify.query(formData);
          break;
        default:
          responseData = await API.request('POST', endpoint, formData, { formData: true });
      }
      return responseData;
    },

    renderResult(result) {
      const lines = [];
      lines.push('success: ' + (result.success ? 'true' : 'false'));
      lines.push('message: ' + (result.message || ''));
      if (result.returncode !== undefined) lines.push('returncode: ' + result.returncode);
      if (result.stdout) lines.push('\n--- stdout ---\n' + result.stdout);
      if (result.stderr) lines.push('\n--- stderr ---\n' + result.stderr);
      this.appendOutput(lines.join('\n'));
    },

    renderVerifySingleResult(result) {
      const card = document.getElementById('verify-single-result');
      if (!card) return;
      const metaEl = card.querySelector('.verify-result-meta');
      const outputEl = card.querySelector('.verify-result-output');

      card.classList.remove('hidden');
      if (metaEl) {
        metaEl.innerHTML = result.success
          ? `${ICONS.check} <span style="color: var(--color-success);">验证成功</span> · returncode: ${result.returncode !== undefined ? result.returncode : '-'}`
          : `${ICONS.x} <span style="color: var(--color-danger);">验证失败</span> · returncode: ${result.returncode !== undefined ? result.returncode : '-'}`;
      }
      if (outputEl) outputEl.textContent = result.stdout || result.stderr || '(无输出)';
    },

    renderVerifyBatchResult(result) {
      const card = document.getElementById('verify-batch-result');
      const link = document.getElementById('verify-batch-download');
      if (!card || !link) return;

      const metaEl = card.querySelector('.verify-result-meta');
      card.classList.remove('hidden');

      if (metaEl) {
        metaEl.innerHTML = result.success
          ? `${ICONS.check} <span style="color: var(--color-success);">验证成功</span> · returncode: ${result.returncode !== undefined ? result.returncode : '-'}`
          : `${ICONS.x} <span style="color: var(--color-danger);">验证失败</span> · returncode: ${result.returncode !== undefined ? result.returncode : '-'}`;
      }

      const outputPath = this.extractOutputPath(result.stdout);
      if (outputPath) {
        const filename = outputPath.replace(/\\/g, '/').split('/').pop();
        link.href = `api/outputs/download/${encodeURIComponent(filename)}`;
        link.classList.remove('hidden');
        link.innerHTML = `${ICONS.download} 下载 ${App.escapeHtml(filename)}`;
      } else {
        link.href = '#';
        link.classList.add('hidden');
      }
    },

    extractOutputPath(stdout) {
      if (!stdout) return null;
      const patterns = [
        /(?:^|\s)import_ids_out=(\S+)/i,
        /(?:^|\s)out=(\S+)/i,
        /(?:^|\n)output=(.+)/i
      ];
      for (const pattern of patterns) {
        const match = stdout.match(pattern);
        if (match) return match[1].trim();
      }
      return null;
    },

    renderDownloadLink(containerId, source) {
      const container = document.getElementById(containerId);
      if (!container) return;
      const link = container.querySelector('a');
      if (!link) return;

      let stdout, explicitPath;
      if (source && typeof source === 'object') {
        stdout = source.stdout;
        explicitPath = source.outputPath || source.path || source.output_path;
      } else {
        stdout = source;
      }

      const outputPath = explicitPath || this.extractOutputPath(stdout);
      if (outputPath) {
        const filename = outputPath.replace(/\\/g, '/').split('/').pop();
        link.href = `api/outputs/download/${encodeURIComponent(filename)}`;
        const label = link.querySelector('.download-label');
        if (label) label.textContent = `下载 ${filename}`;
        container.classList.remove('hidden');
      } else {
        link.href = '#';
        const label = link.querySelector('.download-label');
        if (label) label.textContent = '下载生成文件';
        container.classList.add('hidden');
      }
    },

    hideDownloadLinks() {
      document.querySelectorAll('.result-download').forEach(el => el.classList.add('hidden'));
    },

    appendOutput(text) {
      const output = document.getElementById('console-output');
      if (!output) return;
      const code = output.querySelector('code');
      if (!code) return;
      if (code.textContent === '等待执行…') {
        code.textContent = text;
      } else {
        code.textContent += '\n\n' + text;
      }
      output.scrollTop = output.scrollHeight;
    },

    bindClearOutput() {
      const btn = document.getElementById('clear-output');
      if (!btn) return;
      btn.addEventListener('click', () => {
        const output = document.getElementById('console-output');
        if (output) {
          const code = output.querySelector('code');
          if (code) code.textContent = '等待执行…';
        }
      });
    },

    afterFormSubmit(form, result) {
      if (form.id === 'form-extract-run') {
        this.renderDownloadLink('extract-run-download', {
          stdout: result.stdout,
          outputPath: result.output_path || result.outputPath
        });
      }
      if (form.id === 'form-import-result') {
        this.renderDownloadLink('import-result-download', {
          stdout: result.stdout,
          outputPath: result.output_path || result.outputPath
        });
      }
      if (form.id === 'form-synthesize-single') {
        this.renderDownloadLink('synthesize-single-download', {
          stdout: result.stdout,
          outputPath: result.output_path || result.outputPath
        });
      }
      if (form.id === 'form-import-base' || form.id === 'form-extract-run') {
        // 刷新 collection 列表（如果用户在 collections 面板）
      }
    },

    /* ---------- LLM config selects ---------- */
    async loadLlmConfigSelects() {
      const selects = document.querySelectorAll('.llm-config-select');
      if (selects.length === 0) return;
      try {
        const data = await API.llmConfigs.list();
        if (!data.success) return;
        const items = data.items || [];
        const options = items.map(item => {
          const label = item.active ? `${App.escapeHtml(item.name)}（当前激活）` : App.escapeHtml(item.name);
          return `<option value="${App.escapeHtml(item.name)}">${label}</option>`;
        }).join('');
        selects.forEach(select => {
          const current = select.value;
          select.innerHTML = options;
          if (current && Array.from(select.options).some(o => o.value === current)) {
            select.value = current;
          }
        });
      } catch (error) {
        console.error('加载 LLM 配置下拉框失败：', error);
      }
    },

    /* ---------- Embedding config selects ---------- */
    async loadEmbeddingConfigSelects() {
      const selects = document.querySelectorAll('.embedding-config-select');
      if (selects.length === 0) return;
      try {
        const data = await API.embeddingConfigs.list();
        if (!data.success) return;
        const items = data.items || [];
        const options = items.map(item => {
          const label = item.active ? `${App.escapeHtml(item.name)}（当前激活）` : App.escapeHtml(item.name);
          return `<option value="${App.escapeHtml(item.name)}">${label}</option>`;
        }).join('');
        selects.forEach(select => {
          const current = select.value;
          select.innerHTML = options;
          if (current && Array.from(select.options).some(o => o.value === current)) {
            select.value = current;
          }
        });
      } catch (error) {
        console.error('加载 Embedding 配置下拉框失败：', error);
      }
    }
  };

  if (document.readyState === 'loading') {
    document.addEventListener('DOMContentLoaded', () => PipelineApp.init());
  } else {
    PipelineApp.init();
  }
})();
