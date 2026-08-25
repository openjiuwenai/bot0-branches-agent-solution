/**
 * DFW-RAG - API Request Module
 * DataWave style: global API object wrapping backend endpoints
 */

const API = (() => {
  'use strict';

  const API_BASE_URL = '';

  function getHeaders(isJson = true) {
    const headers = {};
    const token = App.getCsrfToken();
    if (token) headers['X-CSRFToken'] = token;
    if (isJson) {
      headers['Accept'] = 'application/json';
    }
    return headers;
  }

  function buildUrl(path, params = null) {
    let url = `${API_BASE_URL}${path}`;
    if (params && typeof params === 'object') {
      const qs = new URLSearchParams();
      for (const [key, value] of Object.entries(params)) {
        if (value !== undefined && value !== null && value !== '') {
          qs.append(key, value);
        }
      }
      const qsStr = qs.toString();
      if (qsStr) url += (url.includes('?') ? '&' : '?') + qsStr;
    }
    return url;
  }

  async function handleResponse(response, blob = false) {
    if (response.status === 204) return null;
    let data;
    const contentType = response.headers.get('Content-Type') || '';
    if (blob) {
      data = await response.blob();
    } else if (contentType.includes('application/json')) {
      data = await response.json();
    } else {
      data = await response.text();
    }
    if (!response.ok) {
      const error = new Error(
        (data && (data.msg || data.message || data.error || data.detail)) || `HTTP ${response.status}`
      );
      error.status = response.status;
      error.data = data;
      throw error;
    }
    return data;
  }

  async function request(method, url, data = null, options = {}) {
    const { blob = false, formData = false, params = null, isJson = true } = options;
    const fetchOptions = {
      method: method.toUpperCase(),
      headers: getHeaders(isJson)
    };
    if (formData) {
      delete fetchOptions.headers['Accept'];
      delete fetchOptions.headers['Content-Type'];
      fetchOptions.body = data;
    } else if (data !== null && data !== undefined) {
      fetchOptions.headers['Content-Type'] = 'application/json';
      fetchOptions.body = JSON.stringify(data);
    }
    const fullUrl = buildUrl(url, params);
    try {
      const response = await fetch(fullUrl, fetchOptions);
      return await handleResponse(response, blob);
    } catch (error) {
      if (typeof App !== 'undefined' && App.showToast) {
        App.showToast(error.message || '请求失败', 'error');
      }
      throw error;
    }
  }

  /* ---------- Upload ---------- */
  async function uploadFile(file) {
    const formData = new FormData();
    formData.append('file', file);
    return request('POST', 'api/upload', formData, { formData: true });
  }

  /* ---------- Extract ---------- */
  const extract = {
    importBase(formData) { return request('POST', 'api/extract/import_base', formData, { formData: true }); },
    run(formData) { return request('POST', 'api/extract/run', formData, { formData: true }); },
    importResult(formData) { return request('POST', 'api/extract/import_result', formData, { formData: true }); },
    cleanup(formData) { return request('POST', 'api/extract/cleanup', formData, { formData: true }); }
  };

  /* ---------- Verify ---------- */
  const verify = {
    query(formData) { return request('POST', 'api/verify/query', formData, { formData: true }); }
  };

  /* ---------- Synthesize ---------- */
  const synthesize = {
    run(formData) { return request('POST', 'api/synthesize/run', formData, { formData: true }); }
  };

  /* ---------- LLM Configs ---------- */
  const llmConfigs = {
    list() { return request('GET', 'api/llm-configs'); },
    save(data) { return request('POST', 'api/llm-configs', data); },
    remove(name) { return request('DELETE', `api/llm-configs/${name}`); },
    activate(name) { return request('POST', `api/llm-configs/${name}/active`); },
    test(name) { return request('POST', `api/llm-configs/${name}/test`); },
    testBody(config) { return request('POST', 'api/llm-configs/test', config); },
    template() { return request('GET', 'api/llm-configs/template'); }
  };

  /* ---------- Collections ---------- */
  const collections = {
    list() { return request('GET', 'api/collections'); },
    delete(name) { return request('DELETE', `api/collections/${name}`); }
  };

  /* ---------- Outputs ---------- */
  const outputs = {
    list() { return request('GET', 'api/outputs'); },
    download(filename) { return request('GET', `api/outputs/download/${filename}`, null, { blob: true }); }
  };

  /* ---------- QC ---------- */
  const qc = {
    rules() { return request('GET', 'api/qc/rules'); },
    wordlists() { return request('GET', 'api/qc/wordlists'); },
    env() { return request('GET', 'api/qc/env'); },
    active() { return request('GET', 'api/qc/active_qc'); },
    run(formData) { return request('POST', 'api/qc/run', formData, { formData: true }); },
    cancel(jobId) { return request('POST', 'api/qc/cancel', { job_id: jobId }); },
    job(jobId, logFrom = 0) { return request('GET', 'api/qc/job', null, { params: { job_id: jobId, log_from: logFrom } }); },
    download(jobId, format) { return request('GET', 'api/qc/download', null, { params: { job_id: jobId, format }, blob: true }); },
    kbStats() { return request('GET', 'api/qc/kb_stats'); },
    ingestQuestion(formData) { return request('POST', 'api/qc/ingest_question', formData, { formData: true }); },
    ingestIntent(formData) { return request('POST', 'api/qc/ingest_intent', formData, { formData: true }); },
    exportKb(formData) { return request('POST', 'api/qc/export_kb', formData, { formData: true }); }
  };

  /* ---------- Embedding Configs ---------- */
  const embeddingConfigs = {
    list() { return request('GET', 'api/embedding-configs'); },
    profiles() { return request('GET', 'api/embedding/profiles'); },
    save(data) { return request('POST', 'api/embedding/profiles', data); },
    remove(id) { return request('DELETE', `api/embedding/profiles/${id}`); },
    activate(id) { return request('POST', `api/embedding/profiles/${id}/active`); },
    test(id) { return request('POST', `api/embedding/profiles/${id}/test`); },
    testDraft(data) { return request('POST', 'api/embedding/profiles/test-draft', data); }
  };

  return {
    request,
    buildUrl,
    uploadFile,
    extract,
    verify,
    synthesize,
    llmConfigs,
    embeddingConfigs,
    collections,
    outputs,
    qc
  };
})();

if (typeof window !== 'undefined') {
  window.API = API;
}
