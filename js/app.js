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
      console.log(`Saving to localStorage [${key}]:`, data);
      localStorage.setItem(key, JSON.stringify(data));
    } catch (e) {
      console.warn('Failed to save to localStorage:', e);
    }
  },

  loadFromStorage(key, defaultValue = null) {
    try {
      const data = localStorage.getItem(key);
      console.log(`Loading from localStorage [${key}]:`, data);
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
    console.log('DataManager: New subscriber added');
    subscribers.push(callback);
    return () => {
      subscribers = subscribers.filter(sub => sub !== callback);
      console.log('DataManager: Subscriber removed');
    };
  }

  function notify() {
    console.log(`DataManager: Notifying ${subscribers.length} subscribers`);
    subscribers.forEach(callback => callback(incidents));
  }

  function setData(newData) {
    console.log(`DataManager: Setting data, ${newData.length} items`);
    incidents = [...newData];
    notify();
  }

  function getAll() {
    console.log(`DataManager: getAll called, returning ${incidents.length} items`);
    return [...incidents];
  }

  function update(id, updates) {
    console.log(`DataManager: Updating item ${id}:`, updates);
    const index = incidents.findIndex(item => item.id === id);
    if (index !== -1) {
      incidents[index] = { ...incidents[index], ...updates };
      notify();
    }
  }

  function bulkUpdate(ids, updates) {
    console.log(`DataManager: Bulk updating ${ids.length} items:`, updates);
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
    console.log('SelectionManager: New subscriber added');
    subscribers.push(callback);
    return () => {
      const index = subscribers.indexOf(callback);
      if (index > -1) subscribers.splice(index, 1);
      console.log('SelectionManager: Subscriber removed');
    };
  }

  function notify() {
    console.log(`SelectionManager: Notifying ${subscribers.length} subscribers, selected count: ${selectedIds.size}`);
    subscribers.forEach(callback => callback(getState()));
  }

  function toggle(id) {
    console.log(`SelectionManager: Toggling selection for id ${id}`);
    if (selectedIds.has(id)) {
      selectedIds.delete(id);
      console.log(`SelectionManager: Item ${id} deselected`);
    } else {
      selectedIds.add(id);
      console.log(`SelectionManager: Item ${id} selected`);
    }
    notify();
    return getState();
  }

  function setAll(ids, selected) {
    console.log(`SelectionManager: Setting all ${ids.length} items to ${selected ? 'selected' : 'deselected'}`);
    if (selected) {
      ids.forEach(id => selectedIds.add(id));
    } else {
      selectedIds.clear();
    }
    notify();
    return getState();
  }

  function clear() {
    console.log('SelectionManager: Clearing all selections');
    selectedIds.clear();
    notify();
    return getState();
  }

  function getSelected() {
    console.log(`SelectionManager: getSelected called, returning ${selectedIds.size} items`);
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
    console.log(`ApiClient: Searching users with login "${login}"`);
    if (!login || login.length < CONFIG.MIN_SEARCH_LENGTH) {
      console.log('ApiClient: Search query too short');
      return [];
    }

    try {
      const response = await axios.get(CONFIG.API_ENDPOINTS.USER_SEARCH, {
        params: { login }
      });
      console.log(`ApiClient: Found ${response.data?.length || 0} users`);
      return response.data || [];
    } catch (error) {
      console.error('ApiClient: User search error:', error);
      return [];
    }
  },

  async loadIncidents(incidentId = '') {
    console.log(`ApiClient: Loading incidents${incidentId ? ` for record ${incidentId}` : ''}`);
    try {
      const response = await fetch('./data.json');
      if (!response.ok) throw new Error('Failed to load data');

      const data = await response.json();
      console.log(`ApiClient: Loaded ${data.length} incidents from server`);
      return transformServerData(data);
    } catch (error) {
      console.error('ApiClient: Load incidents error:', error);
      throw error;
    }
  }
};

// ==========================================
// DATA TRANSFORMERS
// ==========================================
function transformServerData(serverData) {
  console.log('transformServerData: Transforming server data');
  const transformed = serverData.map(item => {
    const rawPk = item.pk || '';
    const id = Object.values(rawPk)[0] || '';

    return {
      id: item.incident_record_id || item.id || Date.now() + Math.random(),
      pk: rawPk,
      owner: item.executor_name || item.owner || '',
      status: item.status || '',
      masterIncident: item.master_incident_id || '',
      exception: item.exception || '',
      comment: item.comment || '',
      bd_table: item.bd_table || '',
      bd_table_attr: item.bd_table_attr || '',
      checked: false
    }
  });

  console.log(`transformServerData: Transformed ${transformed.length} items`);
  return transformed;
}

// ==========================================
// COLUMN RESIZER
// ==========================================
class ColumnResizer {
  constructor(tableId) {
    console.log(`ColumnResizer: Initializing for table "${tableId}"`);
    this.table = document.getElementById(tableId);
    if (!this.table) {
      console.warn(`ColumnResizer: Table with id "${tableId}" not found`);
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
    console.log('ColumnResizer: Initializing...');
    this.addResizeHandles();
    this.bindEvents();
    this.loadSavedWidths();
  }

  addResizeHandles() {
    console.log('ColumnResizer: Adding resize handles');
    const headers = this.table.querySelectorAll('thead tr:first-child th:not(.checkbox-cell):not(.edit-btn-header)');
    console.log(`ColumnResizer: Found ${headers.length} headers to add handles to`);

    headers.forEach((header, index) => {
      const handle = document.createElement('div');
      handle.className = 'resize-handle';

      // Добавляем обработчик события mousedown непосредственно на handle
      handle.addEventListener('mousedown', (e) => {
        console.log(`ColumnResizer: Resize handle mousedown on column ${index}`);
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
    console.log('ColumnResizer: Binding events');
    document.addEventListener('mousedown', this.handleMouseDown.bind(this));
    document.addEventListener('mousemove', this.handleMouseMove.bind(this));
    document.addEventListener('mouseup', this.handleMouseUp.bind(this));
  }

  handleMouseDown(e) {
    if (!e.target.classList.contains('resize-handle')) return;

    console.log('ColumnResizer: Mouse down on resize handle');
    e.preventDefault();
    e.stopPropagation(); // Останавливаем всплытие, чтобы не сработала сортировка
    e.stopImmediatePropagation(); // Дополнительная защита

    window.isResizingColumn = true; // Устанавливаем флаг блокировки сортировки
    console.log('ColumnResizer: isResizingColumn flag set to true');

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

    console.log(`ColumnResizer: Moving column ${columnIndex}, deltaX: ${deltaX}, newWidth: ${newWidth}`);

    this.updateColumnWidth(columnIndex, newWidth);
  }

  handleMouseUp() {
    if (!this.isResizing) return;

    console.log('ColumnResizer: Mouse up, ending resize');
    this.isResizing = false;

    // Сбрасываем флаг с небольшой задержкой, чтобы предотвратить race condition
    setTimeout(() => {
      window.isResizingColumn = false;
      console.log('ColumnResizer: isResizingColumn flag set to false');
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
    console.log(`ColumnResizer: Updating column ${columnIndex} to width ${width}px for ${rows.length} rows`);

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
    console.log(`ColumnResizer: Saving width ${width}px for column ${columnIndex}`);
    const savedWidths = Utils.loadFromStorage(this.storageKey, {});
    savedWidths[columnIndex] = width;
    Utils.saveToStorage(this.storageKey, savedWidths);
  }

  loadSavedWidths() {
    const savedWidths = Utils.loadFromStorage(this.storageKey, {});
    console.log(`ColumnResizer: Loading saved widths for ${Object.keys(savedWidths).length} columns:`, savedWidths);

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
    console.log('ColumnResizer: Locking column widths');
    const headers = this.table.querySelectorAll('thead tr:first-child th');
    headers.forEach((th, idx) => {
      const w = th.style.width || `${th.offsetWidth}px`;
      document.documentElement.style.setProperty(`--col-w-${idx}`, w);
    });
  }

  reset() {
    console.log('ColumnResizer: Resetting all column widths');
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
    console.log(`SortingManager: Sorting data by column ${columnIndex}, current direction: ${currentSort.asc ? 'asc' : 'desc'}`);

    if (currentSort.index === columnIndex) {
      currentSort.asc = !currentSort.asc;
      console.log(`SortingManager: Toggling sort direction to ${currentSort.asc ? 'asc' : 'desc'}`);
    } else {
      currentSort.index = columnIndex;
      currentSort.asc = true;
      console.log(`SortingManager: New sort column ${columnIndex}, direction asc`);
    }

    const columns = ['pk', 'owner', 'exception', 'status', 'bd_table', 'bd_table_attr', 'comment'];
    const key = columns[columnIndex - 1];

    console.log(`SortingManager: Sorting by key "${key}"`);

    return [...data].sort((a, b) => {
      const aVal = String(a[key] || '').toLowerCase();
      const bVal = String(b[key] || '').toLowerCase();

      if (aVal < bVal) return currentSort.asc ? -1 : 1;
      if (aVal > bVal) return currentSort.asc ? 1 : -1;
      return 0;
    });
  }

  function getState() {
    console.log(`SortingManager: getState - column: ${currentSort.index}, asc: ${currentSort.asc}`);
    return { ...currentSort };
  }

  function reset() {
    console.log('SortingManager: Resetting sort');
    currentSort = { index: null, asc: true };
  }

  return {
    sort,
    getState,
    reset
  };
})();

// ==========================================
// FILTER MANAGER (Работает вместе с DropdownFilterManager)
// ==========================================
const FilterManager = (() => {
  const filters = {};

  function set(columnIndex, value) {
    console.log(`FilterManager: Setting filter for column ${columnIndex} to "${value}"`);
    if (value.trim()) {
      filters[columnIndex] = value.trim().toLowerCase();
    } else {
      delete filters[columnIndex];
      console.log(`FilterManager: Clearing filter for column ${columnIndex}`);
    }
  }

  function apply(data) {
    console.log(`FilterManager: Applying filters to ${data.length} items`);

    // Сначала применяем выпадающие фильтры
    let filteredData = DropdownFilterManager.applyToData(data);
    console.log(`FilterManager: After dropdown filters: ${filteredData.length} items`);

    // Затем применяем текстовые фильтры (для колонок 1 и 7)
    const activeFilters = Object.entries(filters).filter(([colIndex]) => {
      return colIndex === '1' || colIndex === '7'; // Только для текстовых колонок
    });

    if (activeFilters.length === 0) {
      console.log('FilterManager: No text filters to apply');
      return filteredData;
    }

    const columns = ['pk', 'owner', 'exception', 'status', 'bd_table', 'bd_table_attr', 'comment'];

    const result = filteredData.filter(item => {
      return activeFilters.every(([colIndex, filterValue]) => {
        const key = columns[parseInt(colIndex) - 1];
        const value = String(item[key] || '').toLowerCase();
        const matches = value.includes(filterValue);

        if (!matches) {
          console.log(`FilterManager: Item ${item.id} filtered out by column ${colIndex} (${key}="${value}" does not contain "${filterValue}")`);
        }
        return matches;
      });
    });

    console.log(`FilterManager: After all filters: ${result.length} items`);
    return result;
  }

  function clear() {
    console.log('FilterManager: Clearing all filters');
    Object.keys(filters).forEach(key => delete filters[key]);
    DropdownFilterManager.clearAllFilters();

    // Очищаем текстовые фильтры
    document.querySelectorAll('.col-filter:not(.dropdown-filter-input)').forEach(input => {
      input.value = '';
    });
  }

  return {
    set,
    apply,
    clear
  };
})();

// ==========================================
// DROPDOWN FILTER MANAGER (Мультивыбор для всех)
// ==========================================
const DropdownFilterManager = (() => {
  const filters = {
    2: new Set(), // Ответственный
    3: new Set(), // Исключение
    4: new Set()  // Статус
  };

  const valueCache = {
    2: new Set(), // Ответственный
    3: new Set(), // Исключение (фиксированные значения)
    4: new Set()  // Статус
  };

  const columnNames = {
    2: 'owner',
    3: 'exception',
    4: 'status'
  };

  const displayNames = {
    2: 'Ответственный',
    3: 'Исключение',
    4: 'Статус'
  };

  function init() {
    console.log('DropdownFilterManager: Initializing');

    // Проверяем структуру DOM
    console.log('DropdownFilterManager: Checking DOM structure...');
    [2, 3, 4].forEach(colIndex => {
      const input = document.querySelector(`.dropdown-filter-input[data-col="${colIndex}"]`);
      const parent = input?.parentElement;
      const list = parent?.querySelector('.dropdown-filter-list');

      console.log(`Column ${colIndex}:`, {
        inputExists: !!input,
        parentExists: !!parent,
        listExists: !!list,
        inputHTML: input?.outerHTML?.substring(0, 100),
        listHTML: list?.outerHTML?.substring(0, 100)
      });
    });

    updateValueCache(DataManager.getAll());
    DataManager.subscribe(updateValueCache);
    initAllDropdowns();
    attachGlobalListeners();

    // Добавляем тестовый обработчик для отладки
    document.querySelectorAll('.dropdown-filter-input').forEach((input, idx) => {
      console.log(`Adding test listener to input ${idx}`);
      input.addEventListener('click', function (e) {
        console.log(`Input ${idx} clicked!`, {
          target: e.target,
          currentTarget: e.currentTarget,
          hasParent: !!e.target.closest('.dropdown-filter'),
          classList: Array.from(e.target.classList)
        });
      });
    });
  }

  function updateValueCache(data) {
    console.log(`DropdownFilterManager: Updating value cache with ${data.length} items`);

    valueCache[2].clear();
    valueCache[3].clear();
    valueCache[4].clear();

    data.forEach(item => {
      if (item.owner) valueCache[2].add(item.owner);
      if (item.exception) valueCache[3].add(item.exception);
      if (item.status) valueCache[4].add(item.status);
    });

    console.log('DropdownFilterManager: Value cache updated:', {
      owner: Array.from(valueCache[2]),
      exception: Array.from(valueCache[3]),
      status: Array.from(valueCache[4])
    });

    updateAllDropdowns();
  }

  function initAllDropdowns() {
    console.log('DropdownFilterManager: Initializing all dropdowns');
    [2, 3, 4].forEach(colIndex => initDropdown(colIndex));
  }

  function initDropdown(colIndex) {
    const filterInput = document.querySelector(`.dropdown-filter-input[data-col="${colIndex}"]`);
    const parent = filterInput?.parentElement;
    const dropdownList = parent?.querySelector('.dropdown-filter-list');
    const clearBtn = parent?.querySelector('.clear-filter-btn');

    console.log(`DropdownFilterManager: initDropdown for column ${colIndex}`, {
      filterInput: !!filterInput,
      parent: !!parent,
      dropdownList: !!dropdownList,
      clearBtn: !!clearBtn,
      parentClasses: parent?.className,
      listClasses: dropdownList?.className
    });

    if (!filterInput || !dropdownList) {
      console.warn(`DropdownFilterManager: Elements not found for column ${colIndex}`);

      // Попробуем создать выпадающий список, если его нет
      if (filterInput && !dropdownList) {
        console.log(`DropdownFilterManager: Creating dropdown list for column ${colIndex}`);
        createDropdownList(colIndex, filterInput.parentElement);
        return initDropdown(colIndex); // Повторная инициализация
      }
      return;
    }

    console.log(`DropdownFilterManager: Initializing dropdown for column ${colIndex}`);

    // Сначала очистим содержимое
    dropdownList.innerHTML = '';
    renderDropdown(colIndex, dropdownList);

    // Открытие/закрытие по клику
    filterInput.addEventListener('click', (e) => {
      console.log(`Input click for column ${colIndex}`, {
        eventTarget: e.target.tagName,
        eventType: e.type,
        bubbles: e.bubbles
      });
      e.stopPropagation();
      toggleDropdown(colIndex);
    });

    // Очистка фильтра
    if (clearBtn) {
      clearBtn.addEventListener('click', (e) => {
        console.log(`Clear button click for column ${colIndex}`);
        e.stopPropagation();
        clearColumnFilter(colIndex);
      });
    }

    // Обновление отображения
    updateFilterDisplay(colIndex);
  }

  function createDropdownList(colIndex, parentElement) {
    console.log(`DropdownFilterManager: Creating dropdown list for column ${colIndex}`);

    const dropdownList = document.createElement('div');
    dropdownList.className = 'dropdown-filter-list'; // Убрали 'hidden'
    dropdownList.style.cssText = `
    position: absolute;
    top: 100%;
    left: 0;
    background: white;
    border: 1px solid #ccc;
    border-radius: 4px;
    box-shadow: 0 2px 10px rgba(0,0,0,0.1);
    z-index: 1000;
    max-height: 300px;
    overflow-y: auto;
    display: none;
    width: 250px;
  `;

    parentElement.style.position = 'relative';
    parentElement.appendChild(dropdownList);

    console.log(`Dropdown list created for column ${colIndex}`, dropdownList);
  }

  function toggleDropdown(colIndex) {
    console.log(`DropdownFilterManager: toggleDropdown called for column ${colIndex}`);

    const filterInput = document.querySelector(`.dropdown-filter-input[data-col="${colIndex}"]`);
    const parent = filterInput?.parentElement;
    const dropdownList = parent?.querySelector('.dropdown-filter-list');

    console.log(`DropdownFilterManager: Elements for column ${colIndex}`, {
      filterInput: !!filterInput,
      parent: !!parent,
      dropdownList: !!dropdownList,
      listDisplay: dropdownList?.style?.display,
      listClasses: dropdownList?.className
    });

    if (!dropdownList) {
      console.error(`DropdownFilterManager: No dropdown list found for column ${colIndex}`);
      return;
    }

    const isOpening = !dropdownList.classList.contains('show');
    console.log(`DropdownFilterManager: ${isOpening ? 'Opening' : 'Closing'} dropdown for column ${colIndex}`);

    // Закрываем все остальные dropdowns
    document.querySelectorAll('.dropdown-filter-list').forEach(list => {
      if (list !== dropdownList) {
        list.classList.remove('show');
        list.style.display = 'none';
        // Убираем все скрывающие классы
        list.classList.remove('hidden');
      }
    });

    // Переключаем текущий
    if (isOpening) {
      // Убираем все классы, которые могут скрывать элемент
      dropdownList.classList.remove('hidden');
      dropdownList.classList.add('show');

      // Явно устанавливаем display: block с !important через style
      dropdownList.style.cssText += 'display: block !important;';

      // Обновляем содержимое перед показом
      renderDropdown(colIndex, dropdownList);

      // Проверяем видимость
      setTimeout(() => {
        console.log(`Dropdown visibility after show:`, {
          offsetParent: dropdownList.offsetParent,
          clientWidth: dropdownList.clientWidth,
          clientHeight: dropdownList.clientHeight,
          display: window.getComputedStyle(dropdownList).display,
          visibility: window.getComputedStyle(dropdownList).visibility,
          opacity: window.getComputedStyle(dropdownList).opacity,
          classList: Array.from(dropdownList.classList)
        });

        // Если все еще не видно, попробуем более агрессивные методы
        if (dropdownList.clientWidth === 0 || dropdownList.clientHeight === 0) {
          console.log('Dropdown still not visible, trying alternative methods...');

          // Метод 1: Проверяем родительские элементы
          let parent = dropdownList.parentElement;
          while (parent) {
            console.log('Parent element:', {
              tag: parent.tagName,
              display: window.getComputedStyle(parent).display,
              visibility: window.getComputedStyle(parent).visibility,
              overflow: window.getComputedStyle(parent).overflow
            });
            parent = parent.parentElement;
          }

          // Метод 2: Временное изменение позиции
          dropdownList.style.position = 'fixed';
          dropdownList.style.top = '100px';
          dropdownList.style.left = '100px';
          dropdownList.style.zIndex = '9999';

          setTimeout(() => {
            console.log('Dropdown after repositioning:', {
              clientWidth: dropdownList.clientWidth,
              clientHeight: dropdownList.clientHeight
            });

            // Возвращаем обратно
            dropdownList.style.position = '';
            dropdownList.style.top = '';
            dropdownList.style.left = '';
          }, 100);
        }
      }, 10);

      // Фокусируем поиск
      const searchInput = dropdownList.querySelector('.dropdown-filter-search input');
      if (searchInput) {
        setTimeout(() => {
          searchInput.focus();
          console.log('Search input focused');
        }, 50);
      }
    } else {
      dropdownList.classList.remove('show');
      dropdownList.style.display = 'none';
      // Можно добавить обратно hidden если нужно
      // dropdownList.classList.add('hidden');
    }
  }

  function renderDropdown(colIndex, container) {
    console.log(`DropdownFilterManager: renderDropdown for column ${colIndex}`);

    const values = getValuesForColumn(colIndex);
    const selectedValues = filters[colIndex];
    const columnName = displayNames[colIndex];

    console.log(`DropdownFilterManager: ${values.length} values, ${selectedValues.size} selected for column ${colIndex}`);

    // Все значения показываем как есть
    const displayValues = values.map(v => ({
      value: v,
      display: v
    }));

    // Проверяем контейнер
    console.log(`Container state:`, {
      tagName: container.tagName,
      className: container.className,
      parentElement: container.parentElement?.tagName,
      childrenCount: container.children.length
    });

    container.innerHTML = `
      <div class="dropdown-filter-search" style="padding: 8px; border-bottom: 1px solid #eee;">
        <input type="text" placeholder="Поиск ${columnName.toLowerCase()}..." 
               data-col="${colIndex}" style="width: 100%; padding: 4px; box-sizing: border-box;">
      </div>
      <div class="dropdown-filter-items" style="max-height: 200px; overflow-y: auto;">
        ${displayValues.length > 0
        ? displayValues.map(item => `
            <div class="dropdown-filter-checkbox ${selectedValues.has(item.value) ? 'selected' : ''}" 
                 style="padding: 6px 12px; cursor: pointer; display: flex; align-items: center;">
              <input type="checkbox" id="${colIndex}-${item.value}" 
                     value="${item.value}" 
                     ${selectedValues.has(item.value) ? 'checked' : ''}
                     style="margin-right: 8px;">
              <label for="${colIndex}-${item.value}" style="cursor: pointer; flex: 1;">${item.display}</label>
            </div>
          `).join('')
        : `<div class="dropdown-filter-empty" style="padding: 12px; text-align: center; color: #999;">Нет данных</div>`
      }
      </div>
      <div class="dropdown-filter-footer" style="padding: 8px; border-top: 1px solid #eee; display: flex; gap: 8px;">
        <button class="select-all-btn" data-col="${colIndex}" 
                style="flex: 1; padding: 6px; background: #f0f0f0; border: 1px solid #ccc; border-radius: 3px; cursor: pointer;">
          Выбрать все
        </button>
        <button class="clear-selection-btn" data-col="${colIndex}" 
                style="flex: 1; padding: 6px; background: #f0f0f0; border: 1px solid #ccc; border-radius: 3px; cursor: pointer;">
          Очистить
        </button>
      </div>
    `;

    console.log(`DropdownFilterManager: Dropdown rendered with ${container.children.length} children`);

    // Обработчики для поиска
    const searchInput = container.querySelector('.dropdown-filter-search input');
    if (searchInput) {
      searchInput.addEventListener('input', (e) => {
        console.log(`Search input for column ${colIndex}: "${e.target.value}"`);
        filterDropdownItems(colIndex, e.target.value);
      });

      // Обработчик нажатия Enter
      searchInput.addEventListener('keydown', (e) => {
        if (e.key === 'Enter') {
          e.stopPropagation();
        }
      });
    }

    // Обработчики для чекбоксов
    container.querySelectorAll('.dropdown-filter-checkbox input[type="checkbox"]').forEach(checkbox => {
      checkbox.addEventListener('change', (e) => {
        const value = e.target.value;
        console.log(`Checkbox ${value} ${e.target.checked ? 'checked' : 'unchecked'} for column ${colIndex}`);

        if (e.target.checked) {
          filters[colIndex].add(value);
        } else {
          filters[colIndex].delete(value);
        }

        updateFilterDisplay(colIndex);
        updateCheckboxStates(colIndex);
        applyFilters();
      });
    });

    // Обработчики для кнопок в футере
    const selectAllBtn = container.querySelector('.select-all-btn');
    const clearSelectionBtn = container.querySelector('.clear-selection-btn');

    if (selectAllBtn) {
      selectAllBtn.addEventListener('click', (e) => {
        console.log(`Select all button clicked for column ${colIndex}`);
        e.stopPropagation();
        e.preventDefault();
        selectAll(colIndex);
      });
    }

    if (clearSelectionBtn) {
      clearSelectionBtn.addEventListener('click', (e) => {
        console.log(`Clear selection button clicked for column ${colIndex}`);
        e.stopPropagation();
        e.preventDefault();
        clearSelection(colIndex);
      });
    }
  }

  function getValuesForColumn(colIndex) {
    const values = Array.from(valueCache[colIndex] || []).sort();
    console.log(`DropdownFilterManager: getValuesForColumn ${colIndex}: ${values.length} values`);
    return values;
  }

  function filterDropdownItems(colIndex, searchText) {
    console.log(`DropdownFilterManager: Filtering dropdown items for column ${colIndex}, search: "${searchText}"`);
    const container = document.querySelector(`.dropdown-filter-input[data-col="${colIndex}"]`)
      ?.parentElement?.querySelector(`.dropdown-filter-list .dropdown-filter-items`);

    if (!container) {
      console.warn(`No container found for column ${colIndex}`);
      return;
    }

    const items = container.querySelectorAll('.dropdown-filter-checkbox');
    const searchLower = searchText.toLowerCase();

    let visibleCount = 0;
    items.forEach(item => {
      const label = item.querySelector('label');
      if (label) {
        const text = label.textContent.toLowerCase();
        const isVisible = text.includes(searchLower);
        item.style.display = isVisible ? '' : 'none';
        if (isVisible) visibleCount++;
      }
    });

    console.log(`Filtered to ${visibleCount} visible items`);
  }

  function selectAll(colIndex) {
    console.log(`DropdownFilterManager: Selecting all for column ${colIndex}`);
    const values = getValuesForColumn(colIndex);
    filters[colIndex] = new Set(values);
    updateFilterDisplay(colIndex);
    updateDropdown(colIndex);
    applyFilters();
  }

  function clearSelection(colIndex) {
    console.log(`DropdownFilterManager: Clearing selection for column ${colIndex}`);
    filters[colIndex].clear();
    updateFilterDisplay(colIndex);
    updateDropdown(colIndex);
    applyFilters();
  }

  function clearColumnFilter(colIndex) {
    console.log(`DropdownFilterManager: Clearing column filter ${colIndex}`);
    clearSelection(colIndex);
    const dropdownList = document.querySelector(`.dropdown-filter-input[data-col="${colIndex}"]`)
      ?.parentElement?.querySelector('.dropdown-filter-list');
    if (dropdownList) {
      dropdownList.classList.remove('show');
      dropdownList.style.display = 'none';
    }
  }

  function updateFilterDisplay(colIndex) {
    const input = document.querySelector(`.dropdown-filter-input[data-col="${colIndex}"]`);
    const clearBtn = input?.parentElement?.querySelector('.clear-filter-btn');
    const selectedCount = filters[colIndex].size;

    if (!input) {
      console.warn(`No input found for column ${colIndex}`);
      return;
    }

    if (selectedCount === 0) {
      input.value = '';
      input.placeholder = `Выберите ${displayNames[colIndex].toLowerCase()}...`;
      if (clearBtn) clearBtn.style.display = 'none';
    } else if (selectedCount === 1) {
      const value = Array.from(filters[colIndex])[0];
      const displayValue = value;
      input.value = displayValue;
      if (clearBtn) clearBtn.style.display = 'block';
    } else {
      input.value = `Выбрано: ${selectedCount}`;
      if (clearBtn) clearBtn.style.display = 'block';
    }

    console.log(`DropdownFilterManager: Updated filter display for column ${colIndex}, selected: ${selectedCount}`);
  }

  function updateCheckboxStates(colIndex) {
    const container = document.querySelector(`.dropdown-filter-input[data-col="${colIndex}"]`)
      ?.parentElement?.querySelector(`.dropdown-filter-list .dropdown-filter-items`);

    if (!container) {
      console.warn(`No container found for updating checkbox states for column ${colIndex}`);
      return;
    }

    const checkboxes = container.querySelectorAll('input[type="checkbox"]');
    const selectedValues = filters[colIndex];

    checkboxes.forEach(checkbox => {
      const isChecked = selectedValues.has(checkbox.value);
      checkbox.checked = isChecked;
      checkbox.closest('.dropdown-filter-checkbox')
        .classList.toggle('selected', isChecked);
    });
  }

  function updateDropdown(colIndex) {
    console.log(`DropdownFilterManager: updateDropdown for column ${colIndex}`);
    const dropdownList = document.querySelector(`.dropdown-filter-input[data-col="${colIndex}"]`)
      ?.parentElement?.querySelector('.dropdown-filter-list');

    if (dropdownList) {
      renderDropdown(colIndex, dropdownList);
      updateCheckboxStates(colIndex);
    } else {
      console.warn(`No dropdown list found for updating column ${colIndex}`);
    }
  }

  function updateAllDropdowns() {
    console.log('DropdownFilterManager: Updating all dropdowns');
    [2, 3, 4].forEach(colIndex => {
      updateDropdown(colIndex);
      updateFilterDisplay(colIndex);
    });
  }

  function attachGlobalListeners() {
    console.log('DropdownFilterManager: Attaching global listeners');

    // Закрытие dropdown при клике вне
    document.addEventListener('click', (e) => {
      const isClickInsideDropdown = e.target.closest('.dropdown-filter');
      console.log('Global click handler:', {
        target: e.target.tagName,
        isClickInsideDropdown,
        dropdownLists: document.querySelectorAll('.dropdown-filter-list').length
      });

      if (!isClickInsideDropdown) {
        const lists = document.querySelectorAll('.dropdown-filter-list');
        console.log(`Closing ${lists.length} dropdown lists`);

        lists.forEach(list => {
          list.classList.remove('show');
          list.style.display = 'none';
        });
      }
    });

    // Закрытие по ESC
    document.addEventListener('keydown', (e) => {
      if (e.key === 'Escape') {
        console.log('ESC pressed, closing all dropdowns');
        document.querySelectorAll('.dropdown-filter-list').forEach(list => {
          list.classList.remove('show');
          list.style.display = 'none';
        });
      }
    });

    // Предотвращаем всплытие событий от элементов dropdown
    document.addEventListener('click', (e) => {
      if (e.target.closest('.dropdown-filter-list') ||
        e.target.closest('.dropdown-filter-search input') ||
        e.target.closest('.dropdown-filter-checkbox') ||
        e.target.closest('.dropdown-filter-footer button')) {
        e.stopPropagation();
      }
    }, true); // Используем capture phase
  }

  function applyFilters() {
    console.log('DropdownFilterManager: Applying filters');
    const event = new Event('filterchange');
    document.dispatchEvent(event);
  }

  function getFilterForColumn(colIndex) {
    const filter = Array.from(filters[colIndex] || []);
    console.log(`DropdownFilterManager: getFilterForColumn ${colIndex}: ${filter.length} values`);
    return filter;
  }

  function clearAllFilters() {
    console.log('DropdownFilterManager: Clearing all filters');
    [2, 3, 4].forEach(colIndex => {
      filters[colIndex].clear();
      updateFilterDisplay(colIndex);
    });
    updateAllDropdowns();
    applyFilters();
  }

  function applyToData(data) {
    console.log(`DropdownFilterManager: applyToData called with ${data.length} items`);
    let filteredData = [...data];

    // Применяем фильтры для каждой колонки
    [2, 3, 4].forEach(colIndex => {
      const columnFilter = filters[colIndex];
      if (columnFilter.size > 0) {
        console.log(`DropdownFilterManager: Applying filter for column ${colIndex} with ${columnFilter.size} values`);

        const beforeCount = filteredData.length;
        filteredData = filteredData.filter(item => {
          const itemValue = String(item[columnNames[colIndex]] || '');
          const matches = columnFilter.has(itemValue);

          if (!matches) {
            console.log(`DropdownFilterManager: Item ${item.id} filtered out by column ${colIndex} (${itemValue} not in selected values)`);
          }
          return matches;
        });

        console.log(`DropdownFilterManager: Column ${colIndex} filter reduced data from ${beforeCount} to ${filteredData.length} items`);
      }
    });

    console.log(`DropdownFilterManager: Final filtered data: ${filteredData.length} items`);
    return filteredData;
  }

  return {
    init,
    getFilterForColumn,
    clearAllFilters,
    applyToData
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
    console.log(`PaginationManager: Initializing with ${total} total items, ${itemsPerPage} per page`);
    totalItems = total;
    currentPage = 1;
  }

  function setPage(page) {
    const newPage = Math.max(1, Math.min(page, getTotalPages()));
    console.log(`PaginationManager: Setting page from ${currentPage} to ${newPage}`);
    currentPage = newPage;
    return currentPage;
  }

  function nextPage() {
    console.log(`PaginationManager: Next page from ${currentPage}`);
    return setPage(currentPage + 1);
  }

  function prevPage() {
    console.log(`PaginationManager: Previous page from ${currentPage}`);
    return setPage(currentPage - 1);
  }

  function getCurrentPage() {
    console.log(`PaginationManager: getCurrentPage: ${currentPage}`);
    return currentPage;
  }

  function getTotalPages() {
    const pages = Math.ceil(totalItems / itemsPerPage) || 1;
    console.log(`PaginationManager: getTotalPages: ${pages} (${totalItems} items, ${itemsPerPage} per page)`);
    return pages;
  }

  function getPageData(data) {
    const start = (currentPage - 1) * itemsPerPage;
    const end = start + itemsPerPage;
    const pageData = data.slice(start, end);

    console.log(`PaginationManager: getPageData: items ${start}-${end} of ${data.length}, returning ${pageData.length} items`);
    return pageData;
  }

  function getPaginationInfo() {
    const totalPages = getTotalPages();
    const startItem = (currentPage - 1) * itemsPerPage + 1;
    const endItem = Math.min(currentPage * itemsPerPage, totalItems);

    const info = {
      currentPage,
      totalPages,
      totalItems,
      startItem: totalItems > 0 ? startItem : 0,
      endItem: totalItems > 0 ? endItem : 0,
      hasPrev: currentPage > 1,
      hasNext: currentPage < totalPages
    };

    console.log('PaginationManager: getPaginationInfo:', info);
    return info;
  }

  function reset() {
    console.log('PaginationManager: Resetting');
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
    console.log(`UIComponents: renderTable called with ${data.length} items`);
    const tbody = document.querySelector('#errorsTable tbody');
    if (!tbody) {
      console.warn('UIComponents: Table body not found');
      return;
    }

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

    console.log(`UIComponents: Rendered ${data.length} rows`);
  },

  updateActionPanel() {
    console.log('UIComponents: updateActionPanel called');
    const panel = document.getElementById('actionButtons');
    const countEl = document.getElementById('selectedCount');
    const differentStatus = document.getElementById('differentStatus');

    if (!panel) {
      console.warn('UIComponents: Action panel not found');
      return;
    }

    const state = SelectionManager.getState();
    const selectedIncidents = DataManager.getAll().filter(item => state.selectedIds.has(item.id));
    const statuses = new Set(selectedIncidents.map(i => i.status));
    const hasSingleStatus = statuses.size === 1;
    const currentStatus = hasSingleStatus ? [...statuses][0] : null;

    console.log('UIComponents: Updating action panel:', {
      selectedCount: state.count,
      statuses: [...statuses],
      hasSingleStatus,
      currentStatus
    });

    // Показываем/скрываем панель
    if (state.count > 0) {
      console.log('UIComponents: Showing action panel');
      panel.style.display = 'block';
      panel.classList.remove('hidden');
      countEl.textContent = state.count;
    } else {
      console.log('UIComponents: Hiding action panel');
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
      console.log('UIComponents: Looking for block:', currentStatus, '->', blockId);

      if (blockId) {
        const block = document.getElementById(blockId);
        if (block) {
          console.log('UIComponents: Showing block:', blockId);
          block.style.display = 'block';
        }
      }
    } else if (state.count > 1) {
      if (differentStatus) {
        console.log('UIComponents: Showing different status warning');
        differentStatus.style.display = 'block';
      }
    }
  },

  renderPagination() {
    console.log('UIComponents: renderPagination called');
    const container = document.getElementById('paginationContainer');
    if (!container) {
      console.warn('UIComponents: Pagination container not found');
      return;
    }

    const info = PaginationManager.getPaginationInfo();

    if (info.totalPages <= 1) {
      console.log('UIComponents: Hiding pagination (only 1 page)');
      container.style.display = 'none';
      return;
    }

    console.log(`UIComponents: Rendering pagination for ${info.totalPages} pages`);
    container.style.display = 'block';

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

  updateActiveFiltersCount() {
    console.log('UIComponents: updateActiveFiltersCount called');
    const countEl = document.getElementById('activeFiltersCount');
    if (!countEl) {
      console.warn('UIComponents: activeFiltersCount element not found');
      return;
    }

    let count = 0;

    // Считаем текстовые фильтры
    document.querySelectorAll('.col-filter:not(.dropdown-filter-input)').forEach(input => {
      if (input.value.trim()) {
        count++;
        console.log(`UIComponents: Text filter active: ${input.value}`);
      }
    });

    // Считаем выпадающие фильтры
    [2, 3, 4].forEach(colIndex => {
      const filter = DropdownFilterManager.getFilterForColumn(colIndex);
      if (filter && filter.length > 0) {
        count++;
        console.log(`UIComponents: Dropdown filter active for column ${colIndex}: ${filter.length} values`);
      }
    });

    countEl.textContent = count;
    countEl.style.display = count > 0 ? 'inline-block' : 'none';
    console.log(`UIComponents: Active filters count: ${count}`);

    const btn = document.getElementById('btnClearAllFilters');
    if (btn) {
      btn.disabled = count === 0;
    }
  },

  forceUpdateActionPanel() {
    console.log('UIComponents: forceUpdateActionPanel called');
    this.updateActionPanel();
    this.updateActiveFiltersCount();
  }
};

// ==========================================
// VIEW ID MODAL MANAGER
// ==========================================
const ViewIdModalManager = (() => {
  let elements = {};
  let currentContent = '';

  function init() {
    console.log('ViewIdModalManager: Initializing');
    elements = {
      modal: document.getElementById('viewIdModal'),
      closeBtn: document.getElementById('viewIdModalClose'),
      cancelBtn: document.getElementById('btnCloseViewId'),
      copyBtn: document.getElementById('btnCopyId'),
      contentEl: document.getElementById('idContent')
    };

    if (!elements.modal) {
      console.warn('ViewIdModalManager: Modal elements not found');
      return false;
    }

    console.log('ViewIdModalManager: All elements found');
    attachEventListeners();
    return true;
  }

  function attachEventListeners() {
    console.log('ViewIdModalManager: Attaching event listeners');
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

    console.log('ViewIdModalManager: Opening modal with content length:', content.length);
    currentContent = content;
    elements.contentEl.textContent = content;
    elements.modal.classList.add('show');
    elements.copyBtn.textContent = 'Копировать';
    elements.copyBtn.classList.remove('copied');
  }

  function close() {
    if (!elements.modal) return;

    console.log('ViewIdModalManager: Closing modal');
    elements.modal.classList.remove('show');
    currentContent = '';
  }

  async function copyToClipboard() {
    if (!currentContent) return;

    console.log('ViewIdModalManager: Copying to clipboard');
    try {
      await navigator.clipboard.writeText(currentContent);
      console.log('ViewIdModalManager: Successfully copied to clipboard');

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
      console.error('ViewIdModalManager: Failed to copy:', err);

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
          console.log('ViewIdModalManager: Fallback copy successful');
          elements.copyBtn.textContent = 'Скопировано!';
          elements.copyBtn.classList.add('copied');

          setTimeout(() => {
            if (elements.copyBtn) {
              elements.copyBtn.textContent = 'Копировать';
              elements.copyBtn.classList.remove('copied');
            }
          }, 2000);
        } else {
          console.warn('ViewIdModalManager: Fallback copy failed');
          elements.copyBtn.textContent = 'Ошибка копирования';
          setTimeout(() => {
            if (elements.copyBtn) {
              elements.copyBtn.textContent = 'Копировать';
            }
          }, 2000);
        }
      } catch (fallbackErr) {
        console.error('ViewIdModalManager: Fallback copy failed:', fallbackErr);
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

    if (!row) {
      console.warn('EventHandlers: handleCheckboxChange - row not found');
      return;
    }

    const id = row.dataset.id;
    console.log(`EventHandlers: Checkbox change for row ${id}, checked: ${checkbox.checked}`);

    SelectionManager.toggle(id);

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
    console.log('EventHandlers: ID cell clicked');
    const cell = e.target.closest('td');
    const row = e.target.closest('tr');

    if (!cell || !row) {
      console.warn('EventHandlers: handleIdCellClick - cell or row not found');
      return;
    }

    // Проверяем, что это ячейка с идентификатором (вторая колонка)
    const cellIndex = Array.from(row.cells).indexOf(cell);
    if (cellIndex !== 1) {
      console.log(`EventHandlers: Not an ID cell (index: ${cellIndex})`);
      return;
    }

    // Получаем полный текст из data-атрибута или из ячейки
    const incidentId = row.dataset.id;
    const incident = DataManager.getAll().find(item => item.id === incidentId);

    if (incident && incident.pk) {
      console.log(`EventHandlers: Opening modal for incident ${incidentId}`);
      ViewIdModalManager.open(incident.pk);
    } else {
      // Если нет в данных, берем из ячейки
      const content = cell.querySelector('.cell-text')?.textContent || '';
      if (content.trim()) {
        console.log(`EventHandlers: Opening modal with cell content, length: ${content.length}`);
        ViewIdModalManager.open(content);
      }
    }
  },

  handleSelectAll(e) {
    const checked = e.target.checked;
    console.log(`EventHandlers: Select all checkbox ${checked ? 'checked' : 'unchecked'}`);

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
  },

  handleFilterInput: Utils.debounce(function (e) {
    console.log('EventHandlers: handleFilterInput fired, value:', e.target.value);
    const input = e.target;

    // Игнорируем клики по выпадающим фильтрам
    if (input.classList.contains('dropdown-filter-input') ||
      input.classList.contains('dropdown-filter-select')) {
      console.log('EventHandlers: Ignoring dropdown filter input');
      return;
    }

    // Только для текстовых фильтров
    const th = input.closest('th');
    if (!th) {
      console.warn('EventHandlers: No parent th found');
      return;
    }

    const colIndex = parseInt(th.dataset.col);
    if (isNaN(colIndex)) {
      console.warn('EventHandlers: Invalid column index');
      return;
    }

    FilterManager.set(colIndex, input.value);

    // Применяем фильтры и обновляем таблицу
    const filtered = FilterManager.apply(DataManager.getAll());
    const sorted = SortingManager.sort(filtered, SortingManager.getState().index);

    PaginationManager.init(sorted.length);
    const pageData = PaginationManager.getPageData(sorted);
    window.columnResizer.lockColumnWidths();
    UIComponents.renderTable(pageData);
    UIComponents.renderPagination();
    UIComponents.forceUpdateActionPanel();

  }, CONFIG.DEBOUNCE_DELAY),

  applyFiltersAndUpdate() {
    console.log('EventHandlers: applyFiltersAndUpdate called');
    const filtered = FilterManager.apply(DataManager.getAll());
    const sorted = SortingManager.sort(filtered, SortingManager.getState().index);

    PaginationManager.init(sorted.length);
    const pageData = PaginationManager.getPageData(sorted);

    window.columnResizer.lockColumnWidths();
    UIComponents.renderTable(pageData);
    UIComponents.renderPagination();
    UIComponents.forceUpdateActionPanel();
  },

  handlePaginationClick(e) {
    const btn = e.target.closest('.pagination-btn');
    if (!btn || btn.disabled) {
      console.log('EventHandlers: Pagination button not found or disabled');
      return;
    }

    e.preventDefault();

    const page = btn.dataset.page;
    console.log(`EventHandlers: Pagination click, page: ${page}`);

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

    console.log(`EventHandlers: New page: ${newPage}`);

    const filtered = FilterManager.apply(DataManager.getAll());
    const sorted = SortingManager.sort(filtered, SortingManager.getState().index);
    const pageData = PaginationManager.getPageData(sorted);

    UIComponents.renderTable(pageData);
    UIComponents.renderPagination();
    UIComponents.forceUpdateActionPanel();
  },

  handleHeaderClick(e) {
    console.log('EventHandlers: Header click');
    // 1. Блокировка только если реально идёт ресайз
    if (window.isResizingColumn) {
      console.log('EventHandlers: Column resizing in progress, ignoring click');
      return;
    }

    const th = e.target.closest('th');
    if (!th || !th.classList.contains('sortable')) {
      console.log('EventHandlers: Not a sortable header');
      return;
    }

    const colIndex = parseInt(th.dataset.col, 10);
    if (isNaN(colIndex)) {
      console.warn('EventHandlers: Invalid column index');
      return;
    }

    console.log(`EventHandlers: Sorting by column ${colIndex}`);

    // 2. Сортируем и сохраняем результат
    const filtered = FilterManager.apply(DataManager.getAll());
    const sorted = SortingManager.sort(filtered, colIndex);

    // 3. Сброс пагинации и перерисовка
    PaginationManager.init(sorted.length);
    const pageData = PaginationManager.getPageData(sorted);

    UIComponents.renderTable(pageData);
    UIComponents.renderPagination();
    UIComponents.forceUpdateActionPanel();

    // 4. Визуальный индикатор
    document.querySelectorAll('th.sortable').forEach(h => h.classList.remove('sort-asc', 'sort-desc'));
    const { index, asc } = SortingManager.getState();
    const curTh = document.querySelector(`th[data-col="${index}"]`);
    if (curTh) curTh.classList.add(asc ? 'sort-asc' : 'sort-desc');
  },

  handleActionClick(e) {
    const btn = e.target.closest('button[data-action]');
    if (!btn) {
      console.log('EventHandlers: No action button found');
      return;
    }

    const action = btn.dataset.action;
    const selectedIds = Array.from(SelectionManager.getSelected());
    const selectedIncidents = DataManager.getAll().filter(item => selectedIds.includes(item.id));

    console.log(`EventHandlers: Action "${action}" clicked on ${selectedIncidents.length} incidents`);
    alert(`Действие: ${action}\nВыбрано: ${selectedIncidents.length} инцидентов`);
  },

  handleEditClick(e) {
    console.log('EventHandlers: Edit button clicked');
    const row = e.target.closest('tr');
    const id = row.dataset.id;
    const incident = DataManager.getAll().find(item => item.id === id);

    if (incident) {
      console.log(`EventHandlers: Opening modal for incident ${id}`);
      ModalManager.open(incident);
    } else {
      console.warn(`EventHandlers: Incident ${id} not found`);
    }
  },

  initSortHandlers() {
    console.log('EventHandlers: Initializing sort handlers');
    const table = document.getElementById('dataTable');
    if (!table) {
      console.warn('EventHandlers: Data table not found');
      return;
    }

    const headerCells = table.querySelectorAll('thead th[data-sort]');
    console.log(`EventHandlers: Found ${headerCells.length} sortable headers`);

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

        console.log(`EventHandlers: Sorting by ${column}, direction ${newDirection}`);

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
    if (!selectAll) {
      console.warn('EventHandlers: Select all checkbox not found');
      return;
    }

    const allRows = DataManager.getAll();
    const selectedCount = SelectionManager.getState().count;

    console.log(`EventHandlers: Updating select all checkbox, total: ${allRows.length}, selected: ${selectedCount}`);

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
    console.log('EventHandlers: Attaching row event listeners');
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
    console.log('ModalManager: Initializing');
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
      console.warn('ModalManager: Elements not found:', missingElements);
      return false;
    }

    console.log('ModalManager: All elements found');
    attachEventListeners();
    return true;
  }

  function attachEventListeners() {
    console.log('ModalManager: Attaching event listeners');
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
      console.error('ModalManager: Modal not initialized');
      return;
    }

    console.log(`ModalManager: Opening modal for incident ${incident.id}`);
    currentIncident = incident;
    elements.countModal.textContent = SelectionManager.getSelected().size;
    elements.ownerInput.value = incident.owner || '';

    console.log('ModalManager: before add show – display:', getComputedStyle(elements.modal).display);
    elements.modal.classList.add('show');
    elements.modalOverlay.classList.add('show');
    console.log('ModalManager: after  add show – display:', getComputedStyle(elements.modal).display);
    elements.ownerInput.focus();
  }

  function close() {
    if (!elements.modal) return;

    console.log('ModalManager: Closing modal');
    elements.modal.classList.remove('show');
    elements.modalOverlay.classList.remove('show');
    currentIncident = null;
    suggestions = [];
    activeIndex = -1;
    renderSuggestions();
  }

  const searchUsers = Utils.debounce(async (query) => {
    console.log(`ModalManager: Searching users with query "${query}"`);
    if (query.length < CONFIG.MIN_SEARCH_LENGTH) {
      console.log('ModalManager: Query too short');
      suggestions = [];
      renderSuggestions();
      return;
    }

    suggestions = await ApiClient.searchUsers(query);
    activeIndex = -1;
    console.log(`ModalManager: Found ${suggestions.length} users`);
    renderSuggestions();
  }, CONFIG.DEBOUNCE_DELAY);

  function renderSuggestions() {
    if (!elements.suggestionsList) return;

    if (suggestions.length === 0) {
      console.log('ModalManager: No suggestions to render');
      elements.suggestionsList.classList.remove('show');
      elements.suggestionsList.innerHTML = '';
      return;
    }

    console.log(`ModalManager: Rendering ${suggestions.length} suggestions`);
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
    console.log(`ModalManager: Suggestion ${index} clicked`);

    if (index >= 0 && index < suggestions.length) {
      selectUser(suggestions[index]);
    }
  }

  function selectUser(user) {
    console.log(`ModalManager: Selecting user: ${user.name || user.login}`);
    elements.ownerInput.value = user.login || user.name;
    close();
  }

  function handleKeydown(e) {
    if (!elements.modal || !elements.modal.classList.contains('show')) return;

    console.log(`ModalManager: Keydown event: ${e.key}`);

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
    if (!currentIncident) {
      console.warn('ModalManager: No current incident to save');
      return;
    }

    console.log('ModalManager: Saving changes');
    const updates = {
      owner: elements.ownerInput.value,
      table: document.getElementById('tableName')?.value || '',
      attribute: document.getElementById('tableAttribute')?.value || '',
      exception: document.getElementById('exceptionSelect')?.value || '',
      status: document.getElementById('statusSelect')?.value || '',
      comment: document.getElementById('commentInput')?.value || ''
    };

    console.log('ModalManager: Updates:', updates);

    const selectedIds = Array.from(SelectionManager.getSelected());
    if (selectedIds.length > 0) {
      console.log(`ModalManager: Bulk updating ${selectedIds.length} incidents`);
      DataManager.bulkUpdate(selectedIds, updates);
    } else {
      console.log(`ModalManager: Updating single incident ${currentIncident.id}`);
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
// APPLICATION INITIALIZER
// ==========================================
const App = {
  async init() {
    try {
      console.log('==========================================');
      console.log('App: Starting initialization...');
      console.log('==========================================');

      // Initialize modal first
      console.log('App: Initializing modals...');
      const modalInitialized = ModalManager.init();
      const viewIdModalInitialized = ViewIdModalManager.init();

      if (!modalInitialized) {
        console.warn('App: Edit modal initialization failed');
      }
      if (!viewIdModalInitialized) {
        console.warn('App: View ID modal initialization failed');
      }

      // Load data
      console.log('App: Loading data...');
      const data = await ApiClient.loadIncidents();
      DataManager.setData(data);
      console.log(`App: Data loaded, ${data.length} items`);

      // Initialize dropdown filters
      console.log('App: Initializing dropdown filters...');
      DropdownFilterManager.init();
      console.log('App: Dropdown filters initialized');

      // Обработчик изменений фильтров
      document.addEventListener('filterchange', () => {
        console.log('App: Filter change event received');
        EventHandlers.applyFiltersAndUpdate();
      });

      // Initialize pagination
      console.log('App: Initializing pagination...');
      PaginationManager.init(data.length);
      const pageData = PaginationManager.getPageData(data);

      // Initial render
      console.log('App: Rendering table...');
      UIComponents.renderTable(pageData);
      UIComponents.renderPagination();

      // Selection manager subscription
      console.log('App: Setting up selection manager...');
      SelectionManager.subscribe((selectionState) => {
        console.log('App: Selection changed:', selectionState);
        UIComponents.updateActionPanel();
        EventHandlers.updateSelectAllCheckbox();
      });

      // Принудительное обновление панели при начальной загрузке
      setTimeout(() => {
        console.log('App: Forcing initial action panel update');
        UIComponents.forceUpdateActionPanel();
      }, 100);

      // Initialize components
      console.log('App: Initializing components...');
      this.initializeComponents();

      window.columnResizer.lockColumnWidths();

      // Генерируем CSS-правила
      const headers = document.querySelectorAll('#errorsTable thead th');
      const style = document.createElement('style');
      document.head.appendChild(style);
      console.log(`App: Generating CSS rules for ${headers.length} columns`);

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
      console.log('App: Attaching event listeners...');
      this.attachEventListeners();

      // Subscribe to data changes
      console.log('App: Subscribing to data changes...');
      DataManager.subscribe((newData) => {
        console.log(`App: Data changed, ${newData.length} items`);
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
      });

      console.log('==========================================');
      console.log('App: Application initialized successfully');
      console.log('==========================================');
    } catch (error) {
      console.error('App: Failed to initialize application:', error);
      this.showError('Не удалось загрузить данные');
    }
  },

  initializeComponents() {
    console.log('App: Initializing column resizer');
    window.columnResizer = new ColumnResizer('errorsTable');

    console.log('App: Adding sort indicators');
    document.querySelectorAll('th.sortable').forEach(th => {
      const indicator = document.createElement('span');
      indicator.className = 'sort-indicator';
      indicator.textContent = '↕';
      th.appendChild(indicator);
    });
  },

  attachEventListeners() {
    console.log('App: Attaching event listeners');
    const table = document.getElementById('errorsTable');

    // Обработка "Выбрать все"
    const selectAll = document.getElementById('selectAll');
    if (selectAll) {
      console.log('App: Adding select all listener');
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
      console.log('App: Adding clear selection listener');
      clearBtn.addEventListener('click', () => {
        console.log('App: Clear selection clicked');
        SelectionManager.clear();
        UIComponents.updateActionPanel();
        document.querySelector('#selectAll').checked = false;
        document.querySelectorAll('.row-checkbox').forEach(cb => cb.checked = false);
        document.querySelectorAll('.table-row').forEach(tr => tr.classList.remove('selected'));
      });
    }

    // Кнопка "Сбросить все фильтры"
    const clearFiltersBtn = document.getElementById('btnClearAllFilters');
    if (clearFiltersBtn) {
      console.log('App: Adding clear filters listener');
      clearFiltersBtn.addEventListener('click', () => {
        console.log('App: Clear all filters clicked');
        FilterManager.clear();
        EventHandlers.applyFiltersAndUpdate();
      });
    }

    // Фильтры
    document.querySelectorAll('.col-filter').forEach(input => {
      console.log(`App: Adding filter listener for input`);
      input.addEventListener('input', EventHandlers.handleFilterInput.bind(EventHandlers));
    });

    // Заголовки сортировки
    document.querySelectorAll('th.sortable').forEach(th => {
      console.log(`App: Adding sort listener for column`);
      th.addEventListener('click', EventHandlers.handleHeaderClick.bind(EventHandlers));
    });

    // Пагинация
    const paginationContainer = document.getElementById('paginationContainer');
    if (paginationContainer) {
      console.log('App: Adding pagination listener');
      paginationContainer.addEventListener('click', EventHandlers.handlePaginationClick.bind(EventHandlers));
    }

    // ДЕЛЕГИРОВАНИЕ НА САМОЙ ТАБЛИЦЕ для чекбоксов
    if (table) {
      console.log('App: Adding table event delegation');
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
  },

  showError(message) {
    console.error('App: Showing error:', message);
    const container = document.getElementById('app');
    if (!container) {
      console.error('App: App container not found');
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
  console.log('DOM fully loaded and parsed');
  App.init();
});
