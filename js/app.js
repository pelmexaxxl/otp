// js/app.js

// ==========================================
// CONFIGURATION
// ==========================================
const CONFIG = {
  API_ENDPOINTS: {
    USER_SEARCH: '/portal/user_search_login',
    INCIDENTS: '/portal/dq/inc/get_incs_from_record'
  },
  LOCAL_STORAGE_KEYS: {
    COLUMN_WIDTHS: 'errorsTable-column-widths'
  },
  DEBOUNCE_DELAY: 300,
  MIN_SEARCH_LENGTH: 2,
  PAGINATION: {
    ITEMS_PER_PAGE: 20,
    PAGES_TO_SHOW: 5
  }
};

// ==========================================
// UTILITIES
// ==========================================
const Utils = {
  debounce(func, wait) {
    let timeout;
    return function executedFunction(...args) {
      const later = () => {
        clearTimeout(timeout);
        func(...args);
      };
      clearTimeout(timeout);
      timeout = setTimeout(later, wait);
    };
  },

  getPath(obj, path, defaultValue = '') {
    return path.split('.').reduce((current, key) =>
      current && current[key] !== undefined ? current[key] : defaultValue, obj
    );
  },

  saveToStorage(key, data) {
    try {
      localStorage.setItem(key, JSON.stringify(data));
    } catch (e) {
      console.warn('Failed to save to localStorage:', e);
    }
  },

  loadFromStorage(key, defaultValue = null) {
    try {
      const data = localStorage.getItem(key);
      return data ? JSON.parse(data) : defaultValue;
    } catch (e) {
      console.warn('Failed to load from localStorage:', e);
      return defaultValue;
    }
  }
};

// ==========================================
// DATA MANAGER
// ==========================================
const DataManager = (() => {
  let incidents = [];
  let subscribers = [];

  function subscribe(callback) {
    subscribers.push(callback);
    return () => {
      subscribers = subscribers.filter(sub => sub !== callback);
    };
  }

  function notify() {
    subscribers.forEach(callback => callback(incidents));
  }

  function setData(newData) {
    incidents = [...newData];
    notify();
  }

  function getAll() {
    return [...incidents];
  }

  function update(id, updates) {
    const index = incidents.findIndex(item => item.id === id);
    if (index !== -1) {
      incidents[index] = { ...incidents[index], ...updates };
      notify();
    }
  }

  function bulkUpdate(ids, updates) {
    incidents = incidents.map(item =>
      ids.includes(item.id) ? { ...item, ...updates } : item
    );
    notify();
  }

  return {
    subscribe,
    setData,
    getAll,
    update,
    bulkUpdate
  };
})();

// ==========================================
// SELECTION MANAGER (ОБНОВЛЕННЫЙ)
// ==========================================
const SelectionManager = (() => {
  const selectedIds = new Set();
  const subscribers = [];

  function subscribe(callback) {
    subscribers.push(callback);
    return () => {
      const index = subscribers.indexOf(callback);
      if (index > -1) subscribers.splice(index, 1);
    };
  }

  function notify() {
    subscribers.forEach(callback => callback(getState()));
  }

  function toggle(id) {
    if (selectedIds.has(id)) {
      selectedIds.delete(id);
    } else {
      selectedIds.add(id);
    }
    notify();
    return getState();
  }

  function setAll(ids, selected) {
    if (selected) {
      ids.forEach(id => selectedIds.add(id));
    } else {
      selectedIds.clear();
    }
    notify();
    return getState();
  }

  function clear() {
    selectedIds.clear();
    notify();
    return getState();
  }

  function getSelected() {
    return new Set(selectedIds);
  }

  function getState() {
    return {
      selectedIds: new Set(selectedIds),
      count: selectedIds.size
    };
  }

  return {
    subscribe,
    toggle,
    setAll,
    clear,
    getSelected,
    getState
  };
})();

// ==========================================
// API CLIENT
// ==========================================
const ApiClient = {
  async searchUsers(login) {
    if (!login || login.length < CONFIG.MIN_SEARCH_LENGTH) {
      return [];
    }

    try {
      const response = await axios.get(CONFIG.API_ENDPOINTS.USER_SEARCH, {
        params: { login }
      });
      return response.data || [];
    } catch (error) {
      console.error('User search error:', error);
      return [];
    }
  },

  async loadIncidents(incidentId = '') {
    try {
      const response = await fetch('./data.json');
      if (!response.ok) throw new Error('Failed to load data');

      const data = await response.json();
      return transformServerData(data);
    } catch (error) {
      console.error('Load incidents error:', error);
      throw error;
    }
  }
};

// ==========================================
// DATA TRANSFORMERS
// ==========================================
function transformServerData(serverData) {
  return serverData.map(item => {
    const rawPk = item.pk || '';
    const id = Object.values(rawPk)[0] || '';

    return {
      id: item.incident_record_id,
      pk: item.pk,
      owner: item.executor_name || '',
      status: item.status || '',
      masterIncident: item.master_incident_id || '',
      exception: item.status ? '' : 'NO',
      comment: '',
      bd_table: '',
      bd_table_attr: '',
      checked: false
    }
  });
}

// ==========================================
// COLUMN RESIZER
// ==========================================
class ColumnResizer {
  constructor(tableId) {
    this.table = document.getElementById(tableId);
    if (!this.table) {
      console.warn(`Table with id "${tableId}" not found`);
      return;
    }

    this.isResizing = false;
    this.currentHeader = null;
    this.startX = 0;
    this.startWidth = 0;
    this.storageKey = CONFIG.LOCAL_STORAGE_KEYS.COLUMN_WIDTHS;

    // Глобальный флаг для блокировки сортировки во время изменения размера
    window.isResizingColumn = false;

    this.init();
  }

  init() {
    this.addResizeHandles();
    this.bindEvents();
    this.loadSavedWidths();
  }

  addResizeHandles() {
    const headers = this.table.querySelectorAll('thead tr:first-child th:not(.checkbox-cell):not(.edit-btn-header)');
    headers.forEach(header => {
      const handle = document.createElement('div');
      handle.className = 'resize-handle';

      // Добавляем обработчик события mousedown непосредственно на handle
      handle.addEventListener('mousedown', (e) => {
        e.stopPropagation();
        e.stopImmediatePropagation();
        e.preventDefault();

        this.isResizing = true;
        this.currentHeader = header;
        this.startX = e.clientX;
        this.startWidth = header.offsetWidth;

        document.body.style.userSelect = 'none';
        document.body.style.cursor = 'col-resize';
        handle.classList.add('active');
      });

      // Блокируем click событие полностью
      handle.addEventListener('click', (e) => {
        e.stopPropagation();
        e.stopImmediatePropagation();
        e.preventDefault();
      });

      header.appendChild(handle);
    });
  }

  bindEvents() {
    document.addEventListener('mousedown', this.handleMouseDown.bind(this));
    document.addEventListener('mousemove', this.handleMouseMove.bind(this));
    document.addEventListener('mouseup', this.handleMouseUp.bind(this));
  }

  handleMouseDown(e) {
    if (!e.target.classList.contains('resize-handle')) return;

    e.preventDefault();
    e.stopPropagation(); // Останавливаем всплытие, чтобы не сработала сортировка
    e.stopImmediatePropagation(); // Дополнительная защита

    window.isResizingColumn = true; // Устанавливаем флаг блокировки сортировки
    this.isResizing = true;
    this.currentHeader = e.target.parentElement;
    this.startX = e.clientX;
    this.startWidth = this.currentHeader.offsetWidth;

    document.body.style.userSelect = 'none';
    document.body.style.cursor = 'col-resize';
    e.target.classList.add('active');
  }

  handleMouseMove(e) {
    if (!this.isResizing) return;

    e.preventDefault();
    const deltaX = e.clientX - this.startX;
    const newWidth = Math.max(20, this.startWidth + deltaX); // Уменьшили минимум до 20px
    const columnIndex = Array.from(this.currentHeader.parentElement.children).indexOf(this.currentHeader);

    this.updateColumnWidth(columnIndex, newWidth);
  }

  handleMouseUp() {
    if (!this.isResizing) return;

    this.isResizing = false;

    // Сбрасываем флаг с небольшой задержкой, чтобы предотвратить race condition
    setTimeout(() => {
      window.isResizingColumn = false;
    }, 100);

    document.body.style.userSelect = '';
    document.body.style.cursor = '';

    if (this.currentHeader) {
      const handle = this.currentHeader.querySelector('.resize-handle');
      if (handle) handle.classList.remove('active');
    }

    this.currentHeader = null;
  }

  updateColumnWidth(columnIndex, width) {
    const rows = this.table.querySelectorAll('tr');

    rows.forEach(row => {
      const cell = row.children[columnIndex];
      if (cell) {
        cell.style.width = `${width}px`;
        cell.style.minWidth = `${width}px`;

        const content = cell.querySelector('.cell-text, .owner-cell, input, select');
        if (content) {
          content.style.maxWidth = `${width - 20}px`;
        }
      }
    });

    this.saveColumnWidth(columnIndex, width);
  }

  saveColumnWidth(columnIndex, width) {
    const savedWidths = Utils.loadFromStorage(this.storageKey, {});
    savedWidths[columnIndex] = width;
    Utils.saveToStorage(this.storageKey, savedWidths);
  }

  loadSavedWidths() {
    const savedWidths = Utils.loadFromStorage(this.storageKey, {});

    Object.entries(savedWidths).forEach(([index, width]) => {
      const columnIndex = parseInt(index);
      const rows = this.table.querySelectorAll('tr');

      rows.forEach(row => {
        const cell = row.children[columnIndex];
        if (cell) {
          cell.style.width = `${width}px`;
          cell.style.minWidth = `${width}px`;

          const content = cell.querySelector('.cell-text, .owner-cell, input, select');
          if (content) {
            content.style.maxWidth = `${width - 20}px`;
          }
        }
      });
    });
  }

  lockColumnWidths() {
    const headers = this.table.querySelectorAll('thead tr:first-child th');
    headers.forEach((th, idx) => {
      const w = th.style.width || `${th.offsetWidth}px`;
      document.documentElement.style.setProperty(`--col-w-${idx}`, w);
    });
  }

  reset() {
    const allCells = this.table.querySelectorAll('th, td');
    allCells.forEach(cell => {
      cell.style.width = '';
      cell.style.minWidth = '';

      const content = cell.querySelector('.cell-text, .owner-cell, input, select');
      if (content) {
        content.style.maxWidth = '';
      }
    });

    localStorage.removeItem(this.storageKey);
  }
}

// ==========================================
// SORTING MANAGER
// ==========================================
const SortingManager = (() => {
  let currentSort = { index: null, asc: true };

  function sort(data, columnIndex) {
    if (currentSort.index === columnIndex) {
      currentSort.asc = !currentSort.asc;
    } else {
      currentSort.index = columnIndex;
      currentSort.asc = true;
    }

    const columns = ['pk', 'owner', 'exception', 'status', 'bd_table', 'bd_table_attr', 'comment'];
    const key = columns[columnIndex - 1];

    return [...data].sort((a, b) => {
      const aVal = String(a[key] || '').toLowerCase();
      const bVal = String(b[key] || '').toLowerCase();

      if (aVal < bVal) return currentSort.asc ? -1 : 1;
      if (aVal > bVal) return currentSort.asc ? 1 : -1;
      return 0;
    });
  }

  function getState() {
    return { ...currentSort };
  }

  function reset() {
    currentSort = { index: null, asc: true };
  }

  return {
    sort,
    getState,
    reset
  };
})();

// ==========================================
// FILTER MANAGER
// ==========================================
const FilterManager = (() => {
  const filters = {};

  function set(columnIndex, value) {
    if (value.trim()) {
      filters[columnIndex] = value.trim().toLowerCase();
    } else {
      delete filters[columnIndex];
    }
  }

  function apply(data) {
    const activeFilters = Object.entries(filters);
    if (activeFilters.length === 0) return data;

    // !!! исправленный порядок колонок (pk вместо id) !!!
    const columns = ['pk', 'owner', 'exception', 'status', 'bd_table', 'bd_table_attr', 'comment'];

    console.log('Фильтры:', activeFilters); // ➜ сразу видим, что фильтруем

    const result = data.filter(item => {
      return activeFilters.every(([colIndex, filterValue]) => {
        const key = columns[colIndex - 1];
        const value = String(item[key] || '').toLowerCase();
        const ok = value.includes(filterValue);
        console.log(`  col=${colIndex} key=${key} filter="${filterValue}" value="${value}" match=${ok}`);
        return ok;
      });
    });

    console.log('До фильтра:', data.length, 'После:', result.length);
    return result;
  }

  function clear() {
    Object.keys(filters).forEach(key => delete filters[key]);
  }

  return {
    set,
    apply,
    clear
  };
})();

// ==========================================
// PAGINATION MANAGER
// ==========================================
const PaginationManager = (() => {
  let currentPage = 1;
  let totalItems = 0;
  let itemsPerPage = CONFIG.PAGINATION.ITEMS_PER_PAGE;

  function init(total) {
    totalItems = total;
    currentPage = 1;
  }

  function setPage(page) {
    currentPage = Math.max(1, Math.min(page, getTotalPages()));
    return currentPage;
  }

  function nextPage() {
    return setPage(currentPage + 1);
  }

  function prevPage() {
    return setPage(currentPage - 1);
  }

  function getCurrentPage() {
    return currentPage;
  }

  function getTotalPages() {
    return Math.ceil(totalItems / itemsPerPage) || 1;
  }

  function getPageData(data) {
    const start = (currentPage - 1) * itemsPerPage;
    const end = start + itemsPerPage;
    return data.slice(start, end);
  }

  function getPaginationInfo() {
    const totalPages = getTotalPages();
    const startItem = (currentPage - 1) * itemsPerPage + 1;
    const endItem = Math.min(currentPage * itemsPerPage, totalItems);

    return {
      currentPage,
      totalPages,
      totalItems,
      startItem: totalItems > 0 ? startItem : 0,
      endItem: totalItems > 0 ? endItem : 0,
      hasPrev: currentPage > 1,
      hasNext: currentPage < totalPages
    };
  }

  function reset() {
    currentPage = 1;
    totalItems = 0;
  }

  return {
    init,
    setPage,
    nextPage,
    prevPage,
    getCurrentPage,
    getTotalPages,
    getPageData,
    getPaginationInfo,
    reset
  };
})();

// ==========================================
// UI COMPONENTS
// ==========================================
const UIComponents = {
  renderTable(data) {
    const tbody = document.querySelector('#errorsTable tbody');
    if (!tbody) return;

    const selectedIds = SelectionManager.getSelected();

    tbody.innerHTML = data.map(item => `
      <tr class="table-row ${selectedIds.has(item.id) ? 'selected' : ''}" 
          data-id="${item.id}">
        <td class="checkbox-cell">
          <input type="checkbox" class="row-checkbox select-checkbox" 
                 ${selectedIds.has(item.id) ? 'checked' : ''}>
        </td>
        <td>
            <div class="cell-text" title="Кликните для просмотра полного текста">
                ${item.pk}
            </div>
        </td>
        <td>
          <div class="owner-cell">
            <div class="cell-text owner-input">${item.owner}</div>
          </div>
        </td>
        <td><div class="cell-text">${item.exception}</div></td>
        <td><div class="cell-text">${item.status}</div></td>
        <!-- <td><div class="cell-text">${item.bd_table}</div></td>
        <td><div class="cell-text">${item.bd_table_attr}</div></td> -->
        <td>
          ${item.comment ? `<textarea class="cell-text comment-field" readonly>${item.comment}</textarea>` : ''}
        </td>
        <td class="edit-btn-cell"><button class="edit-btn" title="Редактировать">✎</button></td>
      </tr>
    `).join('');
  },

  updateActionPanel() {
    const panel = document.getElementById('actionButtons');
    const countEl = document.getElementById('selectedCount');
    const differentStatus = document.getElementById('differentStatus');

    if (!panel) {
      console.warn('Action panel not found');
      return;
    }

    const state = SelectionManager.getState();
    const selectedIncidents = DataManager.getAll().filter(item => state.selectedIds.has(item.id));
    const statuses = new Set(selectedIncidents.map(i => i.status));
    const hasSingleStatus = statuses.size === 1;
    const currentStatus = hasSingleStatus ? [...statuses][0] : null;

    console.log('Updating action panel:', {
      selectedCount: state.count,
      statuses: [...statuses],
      hasSingleStatus,
      currentStatus
    });

    // Показываем/скрываем панель
    if (state.count > 0) {
      panel.style.display = 'block';
      panel.classList.remove('hidden');
      countEl.textContent = state.count;
    } else {
      panel.style.display = 'none';
      panel.classList.add('hidden');
      return;
    }

    // Скрываем все блоки статусов
    const statusBlocks = [
      'statusNew', 'statusInAnalysis', 'statusInWork',
      'statusReassigned', 'statusWaiting'
    ];

    statusBlocks.forEach(id => {
      const el = document.getElementById(id);
      if (el) el.style.display = 'none';
    });

    if (differentStatus) differentStatus.style.display = 'none';

    // Показываем соответствующий блок
    if (hasSingleStatus && currentStatus) {
      const statusMap = {
        '10': 'statusNew',
        '101': 'statusInAnalysis',
        '20': 'statusInWork',
        '102': 'statusReassigned',
        '30': 'statusWaiting',
        '70': 'published'
      };

      const blockId = statusMap[currentStatus];
      console.log('Looking for block:', currentStatus, '->', blockId);

      if (blockId) {
        const block = document.getElementById(blockId);
        if (block) {
          console.log('Showing block:', blockId);
          block.style.display = 'block';
        }
      }
    } else if (state.count > 1) {
      if (differentStatus) {
        console.log('Showing different status warning');
        differentStatus.style.display = 'block';
      }
    }
  },

  renderPagination() {
    const container = document.getElementById('paginationContainer');
    if (!container) return;

    const info = PaginationManager.getPaginationInfo();

    if (info.totalPages <= 1) {
      // container.innerHTML = '';
      container.style.display = 'none';
      return;
    }

    const pages = [];
    const maxPages = CONFIG.PAGINATION.PAGES_TO_SHOW;
    let startPage = Math.max(1, info.currentPage - Math.floor(maxPages / 2));
    let endPage = Math.min(info.totalPages, startPage + maxPages - 1);

    if (endPage - startPage + 1 < maxPages) {
      startPage = Math.max(1, endPage - maxPages + 1);
    }

    // Previous button
    pages.push(`
      <button class="pagination-btn ${!info.hasPrev ? 'disabled' : ''}" 
              data-page="prev" ${!info.hasPrev ? 'disabled' : ''}>
        ←
      </button>
    `);

    // First page + ellipsis
    if (startPage > 1) {
      pages.push(`<button class="pagination-btn" data-page="1">1</button>`);
      if (startPage > 2) {
        pages.push(`<span class="pagination-ellipsis">...</span>`);
      }
    }

    // Page numbers
    for (let i = startPage; i <= endPage; i++) {
      pages.push(`
        <button class="pagination-btn ${i === info.currentPage ? 'active' : ''}" 
                data-page="${i}">
          ${i}
        </button>
      `);
    }

    // Last page + ellipsis
    if (endPage < info.totalPages) {
      if (endPage < info.totalPages - 1) {
        pages.push(`<span class="pagination-ellipsis">...</span>`);
      }
      pages.push(`<button class="pagination-btn" data-page="${info.totalPages}">${info.totalPages}</button>`);
    }

    // Next button
    pages.push(`
      <button class="pagination-btn ${!info.hasNext ? 'disabled' : ''}" 
              data-page="next" ${!info.hasNext ? 'disabled' : ''}>
        →
      </button>
    `);

    // Info text
    const infoText = `
      <div class="pagination-info">
        ${info.startItem}-${info.endItem} из ${info.totalItems}
      </div>
    `;

    container.innerHTML = `<div class="pagination">${pages.join('')}${infoText}</div>`;
  },

  forceUpdateActionPanel() {
    console.log('Forcing action panel update');
    this.updateActionPanel();
  }
};

// ==========================================
// VIEW ID MODAL MANAGER
// ==========================================
const ViewIdModalManager = (() => {
  let elements = {};
  let currentContent = '';

  function init() {
    elements = {
      modal: document.getElementById('viewIdModal'),
      closeBtn: document.getElementById('viewIdModalClose'),
      cancelBtn: document.getElementById('btnCloseViewId'),
      copyBtn: document.getElementById('btnCopyId'),
      contentEl: document.getElementById('idContent')
    };

    if (!elements.modal) {
      console.warn('View ID modal elements not found');
      return false;
    }

    attachEventListeners();
    return true;
  }

  function attachEventListeners() {
    elements.closeBtn.addEventListener('click', close);
    elements.cancelBtn.addEventListener('click', close);
    elements.copyBtn.addEventListener('click', copyToClipboard);
    elements.modal.addEventListener('click', (e) => {
      if (e.target === elements.modal) close();
    });

    document.addEventListener('keydown', (e) => {
      if (e.key === 'Escape' && elements.modal.classList.contains('show')) {
        close();
      }
    });
  }

  function open(content) {
    if (!elements.modal) return;

    currentContent = content;
    elements.contentEl.textContent = content;
    elements.modal.classList.add('show');
    elements.copyBtn.textContent = 'Копировать';
    elements.copyBtn.classList.remove('copied');
  }

  function close() {
    if (!elements.modal) return;

    elements.modal.classList.remove('show');
    currentContent = '';
  }

  async function copyToClipboard() {
    if (!currentContent) return;

    try {
      await navigator.clipboard.writeText(currentContent);

      // Визуальная обратная связь
      elements.copyBtn.textContent = 'Скопировано!';
      elements.copyBtn.classList.add('copied');

      setTimeout(() => {
        if (elements.copyBtn) {
          elements.copyBtn.textContent = 'Копировать';
          elements.copyBtn.classList.remove('copied');
        }
      }, 2000);

    } catch (err) {
      console.error('Failed to copy:', err);

      // Fallback для старых браузеров
      const textArea = document.createElement('textarea');
      textArea.value = currentContent;
      textArea.style.position = 'fixed';
      textArea.style.left = '-999999px';
      textArea.style.top = '-999999px';
      document.body.appendChild(textArea);
      textArea.focus();
      textArea.select();

      try {
        const successful = document.execCommand('copy');
        if (successful) {
          elements.copyBtn.textContent = 'Скопировано!';
          elements.copyBtn.classList.add('copied');

          setTimeout(() => {
            if (elements.copyBtn) {
              elements.copyBtn.textContent = 'Копировать';
              elements.copyBtn.classList.remove('copied');
            }
          }, 2000);
        } else {
          elements.copyBtn.textContent = 'Ошибка копирования';
          setTimeout(() => {
            if (elements.copyBtn) {
              elements.copyBtn.textContent = 'Копировать';
            }
          }, 2000);
        }
      } catch (fallbackErr) {
        console.error('Fallback copy failed:', fallbackErr);
        elements.copyBtn.textContent = 'Ошибка';
        setTimeout(() => {
          if (elements.copyBtn) {
            elements.copyBtn.textContent = 'Копировать';
          }
        }, 2000);
      } finally {
        document.body.removeChild(textArea);
      }
    }
  }

  return {
    init,
    open,
    close
  };
})();

// ==========================================
// EVENT HANDLERS
// ==========================================
const EventHandlers = {
  handleCheckboxChange(e) {
    const checkbox = e.target;
    const row = checkbox.closest('tr');

    if (!row) return;

    const id = row.dataset.id;

    SelectionManager.toggle(id);
    // UIComponents.updateActionPanel(); будет вызвано через подписку

    if (checkbox.checked) {
      row.classList.add('selected');
    } else {
      row.classList.remove('selected');
      const selectAll = document.getElementById('selectAll');
      if (selectAll) selectAll.checked = false;
    }
    // Обновляем "Выбрать все"
    this.updateSelectAllCheckbox();
  },

  handleIdCellClick(e) {
    const cell = e.target.closest('td');
    const row = e.target.closest('tr');

    if (!cell || !row) return;

    // Проверяем, что это ячейка с идентификатором (вторая колонка)
    const cellIndex = Array.from(row.cells).indexOf(cell);
    if (cellIndex !== 1) return; // 1 - это вторая колонка (нумерация с 0)

    // Получаем полный текст из data-атрибута или из ячейки
    const incidentId = row.dataset.id;
    const incident = DataManager.getAll().find(item => item.id === incidentId);

    if (incident && incident.pk) {
      ViewIdModalManager.open(incident.pk);
    } else {
      // Если нет в данных, берем из ячейки
      const content = cell.querySelector('.cell-text')?.textContent || '';
      if (content.trim()) {
        ViewIdModalManager.open(content);
      }
    }
  },

  handleSelectAll(e) {
    const checked = e.target.checked;
    const allData = DataManager.getAll();
    const ids = allData.map(item => item.id);
    SelectionManager.setAll(ids, checked);

    // Обновляем визуальное состояние
    document.querySelectorAll('.row-checkbox').forEach(cb => {
      cb.checked = checked;
    });

    document.querySelectorAll('.table-row').forEach(row => {
      row.classList.toggle('selected', checked);
    });

    // Сбрасываем indeterminate состояние
    e.target.indeterminate = false;

    // UIComponents.updateActionPanel(); будет вызвано через подписку
  },

  handleFilterInput: Utils.debounce(function (e) {
    console.log('handleFilterInput fired, value:', e.target.value);
    const input = e.target;
    const th = input.closest('th');
    if (!th) return;

    const colIndex = parseInt(th.dataset.col);
    console.log('th:', th, 'colIndex:', colIndex);
    if (isNaN(colIndex)) return;

    FilterManager.set(colIndex, input.value);

    // console.log('Filter col', colIndex, 'key=', key, 'value=', filterValue);
    console.log('Before filter:', DataManager.getAll().length);
    const filtered = FilterManager.apply(DataManager.getAll());
    console.log('After filter:', filtered.length);
    const sorted = SortingManager.sort(filtered, SortingManager.getState().index);

    // Сбрасываем пагинацию при изменении фильтра
    PaginationManager.init(sorted.length);
    const pageData = PaginationManager.getPageData(sorted);
    window.columnResizer.lockColumnWidths();
    UIComponents.renderTable(pageData);
    UIComponents.renderPagination();
    // UIComponents.updateActionPanel();
    UIComponents.forceUpdateActionPanel();

  }, CONFIG.DEBOUNCE_DELAY),

  handlePaginationClick(e) {
    const btn = e.target.closest('.pagination-btn');
    if (!btn || btn.disabled) return;

    e.preventDefault();

    const page = btn.dataset.page;
    let newPage;

    switch (page) {
      case 'prev':
        newPage = PaginationManager.prevPage();
        break;
      case 'next':
        newPage = PaginationManager.nextPage();
        break;
      default:
        newPage = PaginationManager.setPage(parseInt(page));
    }

    const filtered = FilterManager.apply(DataManager.getAll());
    const sorted = SortingManager.sort(filtered, SortingManager.getState().index);
    const pageData = PaginationManager.getPageData(sorted);

    UIComponents.renderTable(pageData);
    UIComponents.renderPagination();
    // UIComponents.updateActionPanel();
    UIComponents.forceUpdateActionPanel();

  },

  handleHeaderClick(e) {
    // 1. Блокировка только если реально идёт ресайз
    if (window.isResizingColumn) return;

    const th = e.target.closest('th');
    if (!th || !th.classList.contains('sortable')) return;

    const colIndex = parseInt(th.dataset.col, 10);
    if (isNaN(colIndex)) return;

    // 2. Сортируем и сохраняем результат
    const filtered = FilterManager.apply(DataManager.getAll());
    const sorted = SortingManager.sort(filtered, colIndex);

    // 3. Сброс пагинации и перерисовка
    PaginationManager.init(sorted.length);
    const pageData = PaginationManager.getPageData(sorted);

    UIComponents.renderTable(pageData);
    UIComponents.renderPagination();
    // UIComponents.updateActionPanel();
    UIComponents.forceUpdateActionPanel();

    // 4. Визуальный индикатор
    document.querySelectorAll('th.sortable').forEach(h => h.classList.remove('sort-asc', 'sort-desc'));
    const { index, asc } = SortingManager.getState();
    const curTh = document.querySelector(`th[data-col="${index}"]`);
    if (curTh) curTh.classList.add(asc ? 'sort-asc' : 'sort-desc');
  },

  handleActionClick(e) {
    const btn = e.target.closest('button[data-action]');
    if (!btn) return;

    const action = btn.dataset.action;
    const selectedIds = Array.from(SelectionManager.getSelected());

    if (selectedIds.length === 0) {
      alert('Пожалуйста, выберите хотя бы один инцидент');
      return;
    }

    // Открываем модалку для ввода комментария
    CommentModalManager.open(
      action,
      selectedIds.length,
      (actionType, comment) => {
        // Этот callback будет вызван после подтверждения
        this.executeAction(actionType, comment, selectedIds);
      }
    );
  },

  executeAction(action, comment, selectedIds) {
    console.log(`Действие: ${action}`);
    console.log(`Комментарий: ${comment}`);
    console.log(`Выбрано инцидентов: ${selectedIds.length}`);

    // Здесь реализуйте логику для каждого действия
    const updates = {};

    // Определяем обновления в зависимости от действия
    switch (action) {
      case 'status-new':
        updates.status = '101'; // В анализе
        break;
      case 'to-exception':
        updates.exception = 'YES';
        break;
      case 'analysis-to-work':
        updates.status = '20'; // В работе
        break;
      case 'close':
        updates.status = '70'; // Закрыт
        break;
      case 'work-to-wait':
        updates.status = '30'; // В ожидании
        break;
      case 'reassigned-to-work':
        updates.status = '20'; // В работе
        break;
      case 'wait-to-work':
        updates.status = '20'; // В работе
        break;
      // Добавьте другие действия по необходимости
    }

    // Добавляем комментарий к обновлениям
    if (comment) {
      updates.comment = comment;
    }

    // Применяем обновления к выбранным инцидентам
    if (Object.keys(updates).length > 0) {
      DataManager.bulkUpdate(selectedIds, updates);
    }

    // Показываем уведомление
    this.showNotification(`Действие "${action}" применено к ${selectedIds.length} инцидентам`);
  },

  showNotification(message, type = 'success') {
    // Создаем уведомление
    const notification = document.createElement('div');
    notification.className = `alert alert-${type} notification-alert`;
    notification.textContent = message;
    notification.style.cssText = `
    position: fixed;
    top: 20px;
    right: 20px;
    z-index: 9999;
    min-width: 300px;
  `;

    document.body.appendChild(notification);

    // Удаляем уведомление через 3 секунды
    setTimeout(() => {
      notification.remove();
    }, 3000);
  },

  handleEditClick(e) {
    const row = e.target.closest('tr');
    const id = row.dataset.id;
    const incident = DataManager.getAll().find(item => item.id === id);

    if (incident) {
      ModalManager.open(incident);
    }
  },

  initSortHandlers() {
    const table = document.getElementById('dataTable');
    if (!table) return;

    const headerCells = table.querySelectorAll('thead th[data-sort]');
    headerCells.forEach(th => {
      // Удаляем старые обработчики, чтобы избежать дублирования
      th.replaceWith(th.cloneNode(true));
    });

    // Назначаем обработчики заново
    const refreshedHeaderCells = table.querySelectorAll('thead th[data-sort]');
    refreshedHeaderCells.forEach(th => {
      th.addEventListener('click', (e) => {
        e.stopPropagation(); // Предотвращаем всплытие к tbody

        const column = th.dataset.sort;
        const currentDirection = th.dataset.direction || 'asc';
        const newDirection = currentDirection === 'asc' ? 'desc' : 'asc';

        // Обновляем индикаторы сортировки
        refreshedHeaderCells.forEach(header => {
          delete header.dataset.direction;
          header.classList.remove('sort-asc', 'sort-desc');
        });

        th.dataset.direction = newDirection;
        th.classList.add(`sort-${newDirection}`);

        // Выполняем сортировку
        DataTableManager.sortData(column, newDirection);
      });
    });
  },

  updateSelectAllCheckbox() {
    const selectAll = document.getElementById('selectAll');
    if (!selectAll) return;

    const allRows = DataManager.getAll();
    const selectedCount = SelectionManager.getState().count;

    if (selectedCount === 0) {
      selectAll.checked = false;
      selectAll.indeterminate = false;
    } else if (selectedCount === allRows.length) {
      selectAll.checked = true;
      selectAll.indeterminate = false;
    } else {
      selectAll.checked = false;
      selectAll.indeterminate = true;
    }
  },

  attachRowEventListeners() {
    document.querySelectorAll('.row-checkbox').forEach(cb => {
      cb.removeEventListener('change', this.handleCheckboxChange);
      cb.addEventListener('change', this.handleCheckboxChange);
    });

    document.querySelectorAll('.edit-btn').forEach(btn => {
      btn.removeEventListener('click', this.handleEditClick);
      btn.addEventListener('click', this.handleEditClick);
    });

    // Добавляем обработчики для ячеек с ID
    document.querySelectorAll('#errorsTable td:nth-child(2) .cell-text').forEach(cell => {
      cell.removeEventListener('click', this.handleIdCellClick);
      cell.addEventListener('click', this.handleIdCellClick);
    });
  }
};

// ==========================================
// MODAL MANAGER (ленивая инициализация)
// ==========================================
const ModalManager = (() => {
  let currentIncident = null;
  let elements = {};
  let suggestions = [];
  let activeIndex = -1;

  /**
   * Initialize modal elements (called after DOM is ready)
   */
  function init() {
    elements = {
      modal: document.getElementById('editModal'),
      modalOverlay: document.getElementById('editModal'),
      closeBtn: document.getElementById('modalClose'),
      cancelBtn: document.getElementById('btnCancel'),
      saveBtn: document.getElementById('btnSave'),
      ownerInput: document.getElementById('ownerInput'),
      suggestionsList: document.getElementById('suggestionsList'),
      countModal: document.getElementById('selectedCountModal')
    };

    // Check if all elements exist
    const missingElements = Object.entries(elements)
      .filter(([_, el]) => !el)
      .map(([name, _]) => name);

    if (missingElements.length > 0) {
      console.warn('Modal elements not found:', missingElements);
      return false;
    }

    attachEventListeners();
    return true;
  }

  function attachEventListeners() {
    elements.closeBtn.addEventListener('click', close);
    elements.cancelBtn.addEventListener('click', close);
    elements.saveBtn.addEventListener('click', save);
    elements.ownerInput.addEventListener('input', (e) => searchUsers(e.target.value));
    elements.suggestionsList.addEventListener('click', handleSuggestionClick);
    elements.modal.addEventListener('click', (e) => {
      if (e.target === elements.modal) close();
    });
    document.addEventListener('keydown', handleKeydown);
  }


  function open(incident) {
    if (!elements.modal) {
      console.error('Modal not initialized');
      return;
    }
    currentIncident = incident;
    elements.countModal.textContent = SelectionManager.getSelected().size;
    elements.ownerInput.value = incident.owner || '';

    console.log('before add show – display:', getComputedStyle(elements.modal).display);
    elements.modal.classList.add('show');
    elements.modalOverlay.classList.add('show');
    console.log('after  add show – display:', getComputedStyle(elements.modal).display);
    elements.ownerInput.focus();
  }

  function close() {
    if (!elements.modal) return;

    elements.modal.classList.remove('show');
    elements.modalOverlay.classList.remove('show');
    currentIncident = null;
    suggestions = [];
    activeIndex = -1;
    renderSuggestions();
  }

  const searchUsers = Utils.debounce(async (query) => {
    if (query.length < CONFIG.MIN_SEARCH_LENGTH) {
      suggestions = [];
      renderSuggestions();
      return;
    }

    suggestions = await ApiClient.searchUsers(query);
    activeIndex = -1;
    renderSuggestions();
  }, CONFIG.DEBOUNCE_DELAY);

  function renderSuggestions() {
    if (!elements.suggestionsList) return;

    if (suggestions.length === 0) {
      elements.suggestionsList.classList.remove('show');
      elements.suggestionsList.innerHTML = '';
      return;
    }

    elements.suggestionsList.innerHTML = suggestions
      .map((user, index) => `
        <div class="suggestion-item ${index === activeIndex ? 'active' : ''}" 
             data-index="${index}">
          ${user.name || user.login}
        </div>
      `).join('');

    elements.suggestionsList.classList.add('show');
  }

  function handleSuggestionClick(e) {
    const item = e.target.closest('.suggestion-item');
    if (!item) return;

    const index = parseInt(item.dataset.index);
    if (index >= 0 && index < suggestions.length) {
      selectUser(suggestions[index]);
    }
  }

  function selectUser(user) {
    elements.ownerInput.value = user.login || user.name;
    close();
  }

  function handleKeydown(e) {
    if (!elements.modal || !elements.modal.classList.contains('show')) return;

    switch (e.key) {
      case 'ArrowDown':
        e.preventDefault();
        activeIndex = Math.min(activeIndex + 1, suggestions.length - 1);
        renderSuggestions();
        break;
      case 'ArrowUp':
        e.preventDefault();
        activeIndex = Math.max(activeIndex - 1, 0);
        renderSuggestions();
        break;
      case 'Enter':
        e.preventDefault();
        if (activeIndex >= 0 && activeIndex < suggestions.length) {
          selectUser(suggestions[activeIndex]);
        }
        break;
      case 'Escape':
        close();
        break;
    }
  }

  function save() {
    if (!currentIncident) return;

    const updates = {
      owner: elements.ownerInput.value,
      table: document.getElementById('tableName')?.value || '',
      attribute: document.getElementById('tableAttribute')?.value || '',
      exception: document.getElementById('exceptionSelect')?.value || '',
      status: document.getElementById('statusSelect')?.value || '',
      comment: document.getElementById('commentInput')?.value || ''
    };

    const selectedIds = Array.from(SelectionManager.getSelected());
    if (selectedIds.length > 0) {
      DataManager.bulkUpdate(selectedIds, updates);
    } else {
      DataManager.update(currentIncident.id, updates);
    }

    close();
  }

  return {
    init,
    open,
    close
  };
})();

// ==========================================
// COMMENT MODAL MANAGER
// ==========================================
const CommentModalManager = (() => {
  let elements = {};
  let currentAction = null;
  let currentCallback = null;

  function init() {
    elements = {
      modal: document.getElementById('commentModal'),
      closeBtn: document.getElementById('commentModalClose'),
      cancelBtn: document.getElementById('btnCancelAnalysis'),
      confirmBtn: document.getElementById('btnConfirmAnalysis'),
      commentInput: document.getElementById('analysisComment'),
      countEl: document.getElementById('analysisCount')
    };

    if (!elements.modal) {
      console.warn('Comment modal elements not found');
      return false;
    }

    attachEventListeners();
    return true;
  }

  function attachEventListeners() {
    elements.closeBtn.addEventListener('click', close);
    elements.cancelBtn.addEventListener('click', close);
    elements.confirmBtn.addEventListener('click', confirm);

    elements.modal.addEventListener('click', (e) => {
      if (e.target === elements.modal) close();
    });

    document.addEventListener('keydown', (e) => {
      if (e.key === 'Escape' && elements.modal.classList.contains('show')) {
        close();
      }
    });
  }

  function open(action, selectedCount, callback) {
    if (!elements.modal) return;

    currentAction = action;
    currentCallback = callback;

    // Устанавливаем заголовок в зависимости от действия
    const actionTitles = {
      'status-new': 'Взять в анализ',
      'to-exception': 'Переместить в исключения',
      'reassign': 'Переназначить',
      'analysis-to-work': 'Взять в работу',
      'close': 'Закрыть инцидент',
      'work-to-wait': 'Переместить в ожидание',
      'reassigned-to-work': 'Взять в работу',
      'wait-to-work': 'Вернуть в работу'
    };

    const title = actionTitles[action] || 'Подтверждение действия';
    elements.modal.querySelector('h3').textContent = title;

    // Устанавливаем текст для кнопки подтверждения
    const confirmTexts = {
      'status-new': 'Взять в анализ',
      'to-exception': 'Переместить',
      'reassign': 'Переназначить',
      'analysis-to-work': 'Взять в работу',
      'close': 'Закрыть',
      'work-to-wait': 'Переместить',
      'reassigned-to-work': 'Взять в работу',
      'wait-to-work': 'Вернуть в работу'
    };

    elements.confirmBtn.textContent = confirmTexts[action] || 'Подтвердить';

    // Устанавливаем количество выбранных инцидентов
    elements.countEl.textContent = selectedCount;

    // Очищаем предыдущий комментарий
    elements.commentInput.value = '';

    // Показываем модалку
    elements.modal.classList.add('show');
    elements.commentInput.focus();
  }

  function close() {
    if (!elements.modal) return;

    elements.modal.classList.remove('show');
    currentAction = null;
    currentCallback = null;
  }

  function confirm() {
    const comment = elements.commentInput.value.trim();

    if (currentCallback) {
      currentCallback(currentAction, comment);
    }

    close();
  }

  return {
    init,
    open,
    close
  };
})();

// ==========================================
// APPLICATION INITIALIZER
// ==========================================
const App = {
  async init() {
    try {
      // Initialize modal first
      const modalInitialized = ModalManager.init();
      const viewIdModalInitialized = ViewIdModalManager.init();
      const commentModalInitialized = CommentModalManager.init();

      if (!modalInitialized) {
        console.warn('Edit modal initialization failed');
      }
      if (!viewIdModalInitialized) {
        console.warn('View ID modal initialization failed');
      }
      if (!commentModalInitialized) {
        console.warn('Comment modal initialization failed');
      }

      // Load data
      const data = await ApiClient.loadIncidents();
      DataManager.setData(data);

      // Initialize pagination
      PaginationManager.init(data.length);
      const pageData = PaginationManager.getPageData(data);

      // Initial render
      UIComponents.renderTable(pageData);
      UIComponents.renderPagination();

      SelectionManager.subscribe((selectionState) => {
        console.log('Selection changed:', selectionState);
        UIComponents.updateActionPanel();
        EventHandlers.updateSelectAllCheckbox();
      });

      // Принудительное обновление панели при начальной загрузке
      setTimeout(() => {
        UIComponents.forceUpdateActionPanel();
      }, 100);

      // Initialize components
      this.initializeComponents();

      window.columnResizer.lockColumnWidths();

      // Генерируем CSS-правила
      const headers = document.querySelectorAll('#errorsTable thead th');
      const style = document.createElement('style');
      document.head.appendChild(style);
      headers.forEach((_, idx) => {
        style.sheet.insertRule(
          `#errorsTable th:nth-child(${idx + 1}),
     #errorsTable td:nth-child(${idx + 1}) {
       width: var(--col-w-${idx}) !important;
       max-width: var(--col-w-${idx}) !important;
     }`
        );
      });

      // Attach event listeners
      this.attachEventListeners();

      // Subscribe to data changes
      DataManager.subscribe((newData) => {
        const filtered = FilterManager.apply(newData);
        const sorted = SortingManager.sort(filtered, SortingManager.getState().index);

        PaginationManager.init(sorted.length);
        const pageData = PaginationManager.getPageData(sorted);

        UIComponents.renderTable(pageData);
        UIComponents.renderPagination();
        UIComponents.updateActionPanel();

        if (window.columnResizer) {
          window.columnResizer.loadSavedWidths();
        }

        // Обновляем чекбокс "Выбрать все"
        EventHandlers.updateSelectAllCheckbox();
        // this.attachRowEventListeners(); используем делегирование
      });

      console.log('Application initialized successfully');
    } catch (error) {
      console.error('Failed to initialize application:', error);
      this.showError('Не удалось загрузить данные');
    }
  },

  initializeComponents() {
    window.columnResizer = new ColumnResizer('errorsTable');

    document.querySelectorAll('th.sortable').forEach(th => {
      const indicator = document.createElement('span');
      indicator.className = 'sort-indicator';
      indicator.textContent = '↕';
      th.appendChild(indicator);
    });
  },

  attachEventListeners() {
    const table = document.getElementById('errorsTable');

    // Обработка "Выбрать все"
    const selectAll = document.getElementById('selectAll');
    if (selectAll) {
      selectAll.addEventListener('change', EventHandlers.handleSelectAll.bind(EventHandlers));
    }

    // Делегирование обработки кнопок панели действий
    document.addEventListener('click', (e) => {
      if (e.target.closest('#actionButtons button[data-action]')) {
        EventHandlers.handleActionClick(e);
      }
    });

    // Кнопка "Очистить выбор"
    const clearBtn = document.getElementById('btnClearSelection');
    if (clearBtn) {
      clearBtn.addEventListener('click', () => {
        SelectionManager.clear();
        UIComponents.updateActionPanel();
        document.querySelector('#selectAll').checked = false;
        document.querySelectorAll('.row-checkbox').forEach(cb => cb.checked = false);
        document.querySelectorAll('.table-row').forEach(tr => tr.classList.remove('selected'));
      });
    }

    // Фильтры
    document.querySelectorAll('.col-filter').forEach(input => {
      input.addEventListener('input', EventHandlers.handleFilterInput.bind(EventHandlers));
    });

    // Заголовки сортировки
    document.querySelectorAll('th.sortable').forEach(th => {
      th.addEventListener('click', EventHandlers.handleHeaderClick.bind(EventHandlers));
    });

    // Пагинация
    const paginationContainer = document.getElementById('paginationContainer');
    if (paginationContainer) {
      paginationContainer.addEventListener('click', EventHandlers.handlePaginationClick.bind(EventHandlers));
    }

    // ДЕЛЕГИРОВАНИЕ НА САМОЙ ТАБЛИЦЕ для чекбоксов
    if (table) {
      // Чекбоксы строк (делегирование на таблице)
      table.addEventListener('change', (e) => {
        if (e.target.classList.contains('row-checkbox')) {
          EventHandlers.handleCheckboxChange(e);
        }
      });

      // Клики на таблице
      table.addEventListener('click', (e) => {
        // Кнопки редактирования
        if (e.target.classList.contains('edit-btn')) {
          EventHandlers.handleEditClick(e);
        }

        // Ячейки с ID (вторая колонка)
        if (e.target.closest('td:nth-child(2) .cell-text')) {
          EventHandlers.handleIdCellClick(e);
        }
      });
    }

    // Удаляем старое делегирование на document для change
    // document.addEventListener('change', (e) => { ... })
  },

  showError(message) {
    const container = document.getElementById('app');
    if (!container) {
      console.error('App container not found');
      return;
    }

    const errorDiv = document.createElement('div');
    errorDiv.className = 'alert alert-danger';
    errorDiv.textContent = message;
    errorDiv.style.marginBottom = '20px';
    container.insertBefore(errorDiv, container.firstChild);

    setTimeout(() => errorDiv.remove(), 5000);
  }
};

// ==========================================
// START APPLICATION
// ==========================================
document.addEventListener('DOMContentLoaded', () => {
  App.init();
});