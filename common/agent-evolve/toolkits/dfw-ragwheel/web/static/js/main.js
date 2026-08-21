/**
 * DFW-RAG - Global Initialization & Utilities
 * DataWave style: App object exposing common UI helpers
 */

const App = (() => {
  'use strict';

  const CONFIG = {
    defaultToastDuration: 4000,
    dateLocale: 'zh-CN',
    dateOptions: {
      year: 'numeric',
      month: '2-digit',
      day: '2-digit',
      hour: '2-digit',
      minute: '2-digit',
      second: '2-digit'
    }
  };

  let $toastContainer = null;
  let $loadingOverlay = null;
  let $confirmOverlay = null;
  let $confirmMessage = null;
  let $confirmOk = null;
  let $confirmCancel = null;
  let $sidebar = null;
  let $sidebarOverlay = null;
  let $sidebarToggle = null;
  let $userMenuToggle = null;
  let $userDropdown = null;

  function cacheDOM() {
    $toastContainer = document.getElementById('toastContainer');
    $loadingOverlay = document.getElementById('loadingOverlay');
    $confirmOverlay = document.getElementById('confirmOverlay');
    $confirmMessage = document.getElementById('confirmMessage');
    $confirmOk = document.getElementById('confirmOk');
    $confirmCancel = document.getElementById('confirmCancel');
    $sidebar = document.querySelector('.extract-sidebar');
    $sidebarOverlay = document.getElementById('sidebarOverlay');
    $sidebarToggle = document.getElementById('sidebarToggle');
  }

  /* ---------- Toast ---------- */
  function showToast(message, type = 'info', duration) {
    duration = duration !== undefined ? duration : CONFIG.defaultToastDuration;
    if (!$toastContainer) return;

    const icons = {
      success: '<svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="var(--color-success)" stroke-width="2.5"><path d="M20 6L9 17l-5-5"/></svg>',
      error: '<svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="var(--color-danger)" stroke-width="2.5"><circle cx="12" cy="12" r="10"/><line x1="15" y1="9" x2="9" y2="15"/><line x1="9" y1="9" x2="15" y2="15"/></svg>',
      warning: '<svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="var(--color-warning)" stroke-width="2.5"><circle cx="12" cy="12" r="10"/><line x1="12" y1="8" x2="12" y2="12"/><line x1="12" y1="16" x2="12.01" y2="16"/></svg>',
      info: '<svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="var(--color-info)" stroke-width="2.5"><circle cx="12" cy="12" r="10"/><line x1="12" y1="16" x2="12" y2="12"/><line x1="12" y1="8" x2="12.01" y2="8"/></svg>'
    };

    const titles = { success: '成功', error: '错误', warning: '警告', info: '提示' };

    const toast = document.createElement('div');
    toast.className = `toast toast-${type}`;
    toast.setAttribute('role', 'alert');
    toast.innerHTML = `
      <div class="toast-icon">${icons[type] || icons.info}</div>
      <div class="toast-content">
        <div class="toast-title">${escapeHtml(titles[type] || '提示')}</div>
        <div class="toast-message">${escapeHtml(message)}</div>
      </div>
      <button class="toast-close" aria-label="关闭通知">
        <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.5"><line x1="18" y1="6" x2="6" y2="18"/><line x1="6" y1="6" x2="18" y2="18"/></svg>
      </button>
    `;

    const closeBtn = toast.querySelector('.toast-close');
    closeBtn.addEventListener('click', () => dismissToast(toast));

    let dismissTimer = null;
    if (duration > 0) {
      dismissTimer = setTimeout(() => dismissToast(toast), duration);
    }

    toast.addEventListener('mouseenter', () => {
      if (dismissTimer) clearTimeout(dismissTimer);
    });
    toast.addEventListener('mouseleave', () => {
      if (duration > 0) dismissTimer = setTimeout(() => dismissToast(toast), duration);
    });

    $toastContainer.appendChild(toast);
  }

  function dismissToast(toast) {
    if (!toast || toast.classList.contains('is-hiding')) return;
    toast.classList.add('is-hiding');
    toast.addEventListener('animationend', () => {
      if (toast.parentNode) toast.parentNode.removeChild(toast);
    });
  }

  /* ---------- Loading ---------- */
  function showLoading(text) {
    if (!$loadingOverlay) return;
    const textEl = $loadingOverlay.querySelector('.loading-text');
    if (textEl && text) textEl.textContent = text;
    $loadingOverlay.classList.add('is-visible');
    $loadingOverlay.setAttribute('aria-hidden', 'false');
  }

  function hideLoading() {
    if (!$loadingOverlay) return;
    $loadingOverlay.classList.remove('is-visible');
    $loadingOverlay.setAttribute('aria-hidden', 'true');
  }

  /* ---------- Confirm ---------- */
  let _confirmResolve = null;

  function confirm(message) {
    if (!$confirmOverlay || !$confirmMessage) {
      return Promise.resolve(window.confirm(message));
    }
    return new Promise((resolve) => {
      _confirmResolve = resolve;
      $confirmMessage.textContent = message;
      $confirmOverlay.classList.add('is-visible');
      $confirmOverlay.setAttribute('aria-hidden', 'false');
      setTimeout(() => $confirmOk && $confirmOk.focus(), 50);
    });
  }

  function _onConfirmOk() {
    $confirmOverlay.classList.remove('is-visible');
    $confirmOverlay.setAttribute('aria-hidden', 'true');
    if (_confirmResolve) { _confirmResolve(true); _confirmResolve = null; }
  }

  function _onConfirmCancel() {
    $confirmOverlay.classList.remove('is-visible');
    $confirmOverlay.setAttribute('aria-hidden', 'true');
    if (_confirmResolve) { _confirmResolve(false); _confirmResolve = null; }
  }

  /* ---------- Sidebar ---------- */
  function toggleSidebar() {
    if (!$sidebar) return;
    $sidebar.classList.toggle('is-open');
    if ($sidebarOverlay) $sidebarOverlay.classList.toggle('is-visible');
    document.body.style.overflow = $sidebar.classList.contains('is-open') ? 'hidden' : '';
  }

  function closeSidebar() {
    if (!$sidebar) return;
    $sidebar.classList.remove('is-open');
    if ($sidebarOverlay) $sidebarOverlay.classList.remove('is-visible');
    document.body.style.overflow = '';
  }

  function highlightCurrentPage() {
    const currentPath = window.location.pathname;
    const links = document.querySelectorAll('.nav-link');
    links.forEach((link) => {
      const href = link.getAttribute('href');
      if (!href) return;
      const normHref = href.length > 1 && href.endsWith('/') ? href.slice(0, -1) : href;
      const normCurrent = currentPath.length > 1 && currentPath.endsWith('/') ? currentPath.slice(0, -1) : currentPath;
      if (normHref === normCurrent) {
        link.classList.add('active');
      } else {
        link.classList.remove('active');
      }
    });
  }

  /* ---------- Utilities ---------- */
  function formatDate(dateStr) {
    if (!dateStr) return '-';
    try {
      const date = new Date(dateStr);
      if (isNaN(date.getTime())) return String(dateStr);
      return date.toLocaleString(CONFIG.dateLocale, CONFIG.dateOptions);
    } catch (e) {
      return String(dateStr);
    }
  }

  function formatFileSize(bytes) {
    if (bytes === null || bytes === undefined || isNaN(bytes)) return '-';
    const abs = Math.abs(bytes);
    if (abs === 0) return '0 B';
    const units = ['B', 'KB', 'MB', 'GB', 'TB', 'PB'];
    const k = 1024;
    const i = Math.floor(Math.log(abs) / Math.log(k));
    const idx = Math.min(i, units.length - 1);
    const val = (abs / Math.pow(k, idx)).toFixed(idx === 0 ? 0 : 2);
    return val + ' ' + units[idx];
  }

  function escapeHtml(str) {
    if (str === null || str === undefined) return '';
    const div = document.createElement('div');
    div.textContent = String(str);
    return div.innerHTML;
  }

  function getCsrfToken() {
    const meta = document.querySelector('meta[name="csrf-token"]');
    return meta ? meta.getAttribute('content') || '' : '';
  }

  /* ---------- Events ---------- */
  function bindEvents() {
    if ($sidebarToggle) {
      $sidebarToggle.addEventListener('click', toggleSidebar);
    }
    if ($sidebarOverlay) {
      $sidebarOverlay.addEventListener('click', closeSidebar);
    }
    if ($confirmOk) $confirmOk.addEventListener('click', _onConfirmOk);
    if ($confirmCancel) $confirmCancel.addEventListener('click', _onConfirmCancel);
    if ($confirmOverlay) {
      $confirmOverlay.addEventListener('keydown', (e) => {
        if (e.key === 'Escape') _onConfirmCancel();
        if (e.key === 'Enter') _onConfirmOk();
      });
      $confirmOverlay.addEventListener('click', (e) => {
        if (e.target === $confirmOverlay) _onConfirmCancel();
      });
    }

    document.addEventListener('keydown', (e) => {
      if (e.key === 'Escape') {
        closeSidebar();
      }
    });

    highlightCurrentPage();
  }

  function init() {
    cacheDOM();
    bindEvents();
  }

  if (document.readyState === 'loading') {
    document.addEventListener('DOMContentLoaded', init);
  } else {
    init();
  }

  return {
    showToast,
    showLoading,
    hideLoading,
    confirm,
    formatDate,
    formatFileSize,
    escapeHtml,
    getCsrfToken,
    toggleSidebar,
    closeSidebar,
    highlightCurrentPage
  };
})();
