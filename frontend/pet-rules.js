(() => {
  'use strict';
  if (window.PetRules && typeof window.PetRules.open === 'function') return;

  const scopes = { all: '全部报表', dhh: '大航海', jd: '京东日报', bid: '出价监测' };
  const dimensions = { summary: '汇总', account: '账户', task: '任务', optimizer: '优化师', plan: '计划' };
  const operators = { gt: '大于 >', gte: '大于等于 ≥', lt: '小于 <', lte: '小于等于 ≤', eq: '等于 =', ne: '不等于 ≠' };
  const metrics = ['消耗', '现金消耗', '预估佣金', '佣金', '现金利润', '预估利润', '实际利润', 'ROI', '现金ROI', '预估ROI', '实际ROI', '注册数', '注册成本', '转化数', '计划累计转化数', '有效订单数', '结算数', '当前出价', 'gap', '结算单价', '实际单价', '预估赔付', '预估eCPM'];
  const limits = { rules: 20, title: 60, content: 2000, totalContent: 12000, conditions: 5 };
  const state = { loaded: false, loading: false, saving: false, canManage: false, version: 0, updatedAt: null, rules: [], selectedId: null, baseline: '', conflict: false, submissionUnknown: false, message: '', tone: '' };
  let dialog, ui, returnFocus, loadPromise;

  const element = (tag, className, text) => {
    const node = document.createElement(tag);
    if (className) node.className = className;
    if (text !== undefined) node.textContent = text;
    return node;
  };
  const snapshot = () => JSON.stringify(state.rules);
  const isDirty = () => state.loaded && snapshot() !== state.baseline;
  const selectedRule = () => state.rules.find(rule => rule.id === state.selectedId);
  const canEdit = () => state.loaded && state.canManage && !state.loading && !state.saving;

  function createDialog() {
    dialog = element('dialog', 'pet-rules-dialog');
    dialog.setAttribute('aria-labelledby', 'pet-rules-heading');
    dialog.setAttribute('aria-describedby', 'pet-rules-description');
    dialog.innerHTML = `
      <div class="pet-rules-shell">
        <header class="pet-rules-header">
          <div><h2 id="pet-rules-heading">AI 分析规则</h2><p id="pet-rules-description">统一设置分析指令和指标条件，供全站数据助手使用。</p></div>
          <button type="button" class="pet-rules-close" aria-label="关闭分析规则">×</button>
        </header>
        <div class="pet-rules-notice" hidden>当前账号可查看规则；仅管理员可以修改和保存。</div>
        <div class="pet-rules-message" role="status" aria-live="polite" hidden></div>
        <div class="pet-rules-workspace">
          <aside class="pet-rules-sidebar" aria-label="分析规则列表">
            <div class="pet-rules-list-heading"><strong>全部规则</strong><span class="pet-rules-count"></span></div>
            <p class="pet-rules-order-hint">从上到下执行，冲突时前面的优先</p>
            <div class="pet-rules-add-actions">
              <button type="button" data-add="text">＋ 文字规则</button>
              <button type="button" data-add="condition">＋ 指标规则</button>
              <button type="button" class="pet-rules-template">使用「高消耗低 ROI」示例</button>
            </div>
            <div class="pet-rules-list"></div>
          </aside>
          <section class="pet-rules-detail" aria-label="规则详情"></section>
        </div>
        <footer class="pet-rules-footer">
          <div class="pet-rules-save-info"><strong class="pet-rules-save-state"></strong><span class="pet-rules-updated"></span></div>
          <div class="pet-rules-footer-actions"><button type="button" class="pet-rules-reload">刷新规则</button><button type="button" class="pet-rules-save">保存全部规则</button></div>
        </footer>
      </div>`;
    document.body.appendChild(dialog);
    const find = selector => dialog.querySelector(selector);
    ui = { count: find('.pet-rules-count'), list: find('.pet-rules-list'), detail: find('.pet-rules-detail'), notice: find('.pet-rules-notice'), message: find('.pet-rules-message'), saveState: find('.pet-rules-save-state'), updated: find('.pet-rules-updated'), save: find('.pet-rules-save'), reload: find('.pet-rules-reload'), add: [...dialog.querySelectorAll('[data-add]')], template: find('.pet-rules-template') };
    find('.pet-rules-close').addEventListener('click', () => dialog.close());
    dialog.addEventListener('close', () => {
      if (returnFocus && returnFocus.isConnected) returnFocus.focus({ preventScroll: true });
    });
    ui.add.forEach(button => button.addEventListener('click', () => addRule(button.dataset.add)));
    ui.template.addEventListener('click', () => addRule('condition', true));
    ui.reload.addEventListener('click', () => loadConfig());
    ui.save.addEventListener('click', saveRules);
    dialog.addEventListener('keydown', event => {
      if ((event.ctrlKey || event.metaKey) && event.key.toLowerCase() === 's') {
        event.preventDefault();
        if (!ui.save.disabled) saveRules();
      }
    });
  }

  function setMessage(message = '', tone = '') {
    state.message = message;
    state.tone = tone;
    ui.message.textContent = message;
    ui.message.hidden = !message;
    ui.message.dataset.tone = tone;
  }

  function refreshChrome() {
    const dirty = isDirty();
    const enabled = state.rules.filter(rule => rule.enabled).length;
    ui.count.textContent = `${state.rules.length}/${limits.rules} · ${enabled} 启用`;
    ui.notice.hidden = !state.loaded || state.canManage;
    ui.save.hidden = state.loaded && !state.canManage;
    ui.save.disabled = !canEdit() || !dirty || state.conflict || state.submissionUnknown;
    ui.save.textContent = state.saving ? '正在保存…' : '保存全部规则';
    ui.reload.disabled = state.loading || state.saving;
    ui.reload.textContent = state.loading ? '正在加载…' : state.submissionUnknown ? '刷新核对并放弃草稿' : state.conflict || dirty ? '重新加载并放弃草稿' : !state.loaded ? '重试加载' : '刷新规则';
    ui.add.forEach(button => { button.disabled = !canEdit() || state.rules.length >= limits.rules; });
    ui.template.disabled = !canEdit() || state.rules.length >= limits.rules;
    ui.saveState.textContent = state.loading ? '正在加载规则' : state.saving ? '正在保存到网站' : !state.loaded ? '尚未加载' : dirty ? '有未保存的草稿' : '已与网站同步';
    ui.saveState.dataset.dirty = String(dirty);
    ui.updated.textContent = state.loaded ? state.updatedAt ? `更新于 ${new Date(state.updatedAt).toLocaleString('zh-CN', { hour12: false })}` : '尚未保存规则' : '';
    dialog.setAttribute('aria-busy', String(state.loading || state.saving));
  }

  function refreshList() {
    ui.list.replaceChildren();
    if (!state.rules.length) {
      ui.list.appendChild(element('p', 'pet-rules-list-empty', state.loading ? '正在加载…' : state.loaded ? '还没有规则' : '加载后显示规则'));
      return;
    }
    state.rules.forEach((rule, index) => {
      const card = element('button', 'pet-rules-card');
      card.type = 'button';
      card.dataset.ruleId = rule.id;
      card.setAttribute('aria-current', String(rule.id === state.selectedId));
      card.disabled = state.loading || state.saving;
      const title = element('strong', 'pet-rules-card-title', rule.title.trim() || '未命名规则');
      const meta = element('span', 'pet-rules-card-meta');
      meta.append(element('span', '', `${index + 1} · ${rule.type === 'text' ? '文字指令' : '指标条件'}`), element('span', 'pet-rules-scope-tag', scopes[rule.scope] || rule.scope));
      const status = element('span', 'pet-rules-card-status', rule.enabled ? '已启用' : '已停用');
      status.dataset.enabled = String(rule.enabled);
      card.append(title, meta, status);
      card.addEventListener('click', () => { state.selectedId = rule.id; render(); });
      ui.list.appendChild(card);
    });
  }

  function makeField(labelText, input, className = '') {
    const label = element('label', `pet-rules-field ${className}`.trim());
    label.append(element('span', 'pet-rules-field-label', labelText), input);
    return label;
  }

  function makeSelect(values, value, label) {
    const select = element('select');
    select.setAttribute('aria-label', label);
    Object.entries(values).forEach(([key, name]) => {
      const option = element('option', '', name);
      option.value = key;
      select.appendChild(option);
    });
    select.value = value;
    return select;
  }

  function changed() {
    if (!state.conflict && !state.submissionUnknown) setMessage();
    refreshList();
    refreshChrome();
  }

  function renderEditor() {
    ui.detail.replaceChildren();
    const rule = selectedRule();
    if (!rule) {
      const empty = element('div', 'pet-rules-empty-editor');
      empty.append(element('div', 'pet-rules-empty-mark', '≋'), element('h3', '', state.loading ? '正在读取全站规则' : '让分析遵循你的规则'), element('p', '', state.loaded && state.canManage ? '添加文字规则约定分析方式，或添加指标规则筛出需要关注的数据。' : state.loaded ? '网站管理员保存的规则会显示在这里。' : '规则只在打开此窗口时加载。'));
      ui.detail.appendChild(empty);
      return;
    }
    const index = state.rules.indexOf(rule);
    const toolbar = element('div', 'pet-rules-editor-toolbar');
    toolbar.appendChild(element('strong', '', rule.type === 'text' ? '文字指令' : '指标条件'));
    const actions = element('div', 'pet-rules-editor-actions');
    [['上移', -1], ['下移', 1]].forEach(([text, offset]) => {
      const button = element('button', '', text);
      button.type = 'button';
      button.disabled = !canEdit() || index + offset < 0 || index + offset >= state.rules.length;
      button.addEventListener('click', () => {
        if (!canEdit()) return;
        state.rules.splice(index + offset, 0, state.rules.splice(index, 1)[0]);
        render();
      });
      actions.appendChild(button);
    });
    const remove = element('button', 'pet-rules-remove', '删除');
    remove.type = 'button';
    remove.disabled = !canEdit();
    remove.addEventListener('click', () => {
      if (!canEdit()) return;
      state.rules.splice(index, 1);
      state.selectedId = state.rules[Math.min(index, state.rules.length - 1)]?.id || null;
      render();
    });
    actions.appendChild(remove);
    toolbar.appendChild(actions);
    const fieldset = element('fieldset', 'pet-rules-fields');
    fieldset.disabled = !canEdit();
    const title = element('input');
    title.type = 'text'; title.maxLength = limits.title; title.value = rule.title;
    title.placeholder = '给规则起个名称';
    title.setAttribute('aria-label', '规则名称');
    title.addEventListener('input', () => { rule.title = title.value; changed(); });
    fieldset.appendChild(makeField('规则名称', title));
    const settings = element('div', 'pet-rules-settings-row');
    const scope = makeSelect(scopes, rule.scope, '适用报表');
    scope.addEventListener('change', () => { rule.scope = scope.value; changed(); });
    settings.appendChild(makeField('适用报表', scope));
    if (rule.type === 'condition') {
      const dimension = makeSelect(dimensions, rule.dimension, '匹配维度');
      dimension.addEventListener('change', () => { rule.dimension = dimension.value; changed(); });
      settings.appendChild(makeField('匹配维度', dimension));
    }
    const enabled = element('input');
    enabled.type = 'checkbox'; enabled.checked = rule.enabled;
    enabled.addEventListener('change', () => { rule.enabled = enabled.checked; changed(); });
    const enabledLabel = element('label', 'pet-rules-enabled');
    enabledLabel.append(enabled, element('span', '', '启用规则'));
    settings.appendChild(enabledLabel);
    fieldset.appendChild(settings);
    if (rule.type === 'condition') {
      const conditions = element('div', 'pet-rules-conditions');
      const heading = element('div', 'pet-rules-conditions-heading');
      heading.append(element('strong', '', '触发条件'), element('span', '', `全部满足（AND）· ${rule.conditions.length}/${limits.conditions}`));
      conditions.appendChild(heading);
      rule.conditions.forEach((condition, conditionIndex) => {
        const row = element('div', 'pet-rules-condition');
        const metric = makeSelect(Object.fromEntries(metrics.map(name => [name, name])), condition.metric, `条件 ${conditionIndex + 1} 指标`);
        const operator = makeSelect(operators, condition.operator, `条件 ${conditionIndex + 1} 比较方式`);
        const value = element('input');
        value.type = 'number'; value.step = 'any'; value.inputMode = 'decimal'; value.value = condition.value;
        value.setAttribute('aria-label', `条件 ${conditionIndex + 1} 数值`);
        value.placeholder = '输入数值';
        metric.addEventListener('change', () => { condition.metric = metric.value; changed(); });
        operator.addEventListener('change', () => { condition.operator = operator.value; changed(); });
        value.addEventListener('input', () => { condition.value = value.value === '' ? '' : Number(value.value); changed(); });
        const removeCondition = element('button', 'pet-rules-remove-condition', '×');
        removeCondition.type = 'button';
        removeCondition.setAttribute('aria-label', `删除条件 ${conditionIndex + 1}`);
        removeCondition.disabled = rule.conditions.length <= 1;
        removeCondition.addEventListener('click', () => {
          if (!canEdit() || rule.conditions.length <= 1) return;
          rule.conditions.splice(conditionIndex, 1); render();
        });
        row.append(metric, operator, value, removeCondition);
        conditions.appendChild(row);
      });
      const addCondition = element('button', 'pet-rules-add-condition', '＋ 添加条件');
      addCondition.type = 'button'; addCondition.disabled = rule.conditions.length >= limits.conditions;
      addCondition.addEventListener('click', () => {
        if (!canEdit() || rule.conditions.length >= limits.conditions) return;
        rule.conditions.push({ metric: '消耗', operator: 'gt', value: 0 }); render();
      });
      conditions.append(addCondition, element('p', 'pet-rules-hint', 'ROI 按倍数填写：1 表示 1 倍；gap 按比率填写：0.1 表示 10%。金额填写元，计数填写数量。'));
      fieldset.appendChild(conditions);
    }
    const content = element('textarea');
    content.rows = rule.type === 'text' ? 7 : 4; content.maxLength = limits.content; content.value = rule.content;
    content.setAttribute('aria-label', rule.type === 'text' ? '分析指令' : '触发后的建议');
    content.placeholder = rule.type === 'text' ? '例如：先指出现金利润和预估 ROI，再说明依据，最后给出可执行的建议。' : '例如：提醒复核素材与流量质量，并结合转化数据判断是否调整出价。';
    const contentField = makeField(rule.type === 'text' ? '分析指令' : '触发后的建议', content, 'pet-rules-content-field');
    const counter = element('span', 'pet-rules-content-count', `${rule.content.length}/${limits.content}`);
    content.addEventListener('input', () => { rule.content = content.value; counter.textContent = `${rule.content.length}/${limits.content}`; changed(); });
    contentField.appendChild(counter);
    fieldset.append(contentField, element('p', 'pet-rules-hint', rule.type === 'text' ? '文字规则作为分析指令使用，指标判断请使用指标规则。' : '仅根据当前报表的实际数据判断；缺少指标或维度时会标记「无法判定」。'));
    ui.detail.append(toolbar, fieldset);
  }

  function render() {
    refreshList(); renderEditor(); refreshChrome();
  }

  function addRule(type, sample = false) {
    if (!canEdit() || state.rules.length >= limits.rules) return;
    const rule = { id: crypto.randomUUID(), title: sample ? '高消耗低 ROI' : '', scope: sample ? 'jd' : 'all', enabled: false, type, dimension: type === 'text' ? 'summary' : 'account', content: sample ? '提醒检查高消耗账户的素材、转化质量及投放节奏，结合利润判断是否调整。' : '', conditions: type === 'text' ? [] : sample ? [{ metric: '消耗', operator: 'gt', value: 200 }, { metric: '预估ROI', operator: 'lt', value: 1 }] : [{ metric: '消耗', operator: 'gt', value: 0 }] };
    state.rules.push(rule); state.selectedId = rule.id;
    setMessage(sample ? '已添加示例草稿，默认停用；核对后启用并保存。' : '新规则默认停用，填写后可启用并保存。');
    render();
    ui.detail.querySelector('input[type="text"]')?.focus();
  }

  function applyConfig(config) {
    const validRule = rule => rule && typeof rule.id === 'string' && typeof rule.title === 'string' && typeof rule.content === 'string' && typeof rule.enabled === 'boolean' && Object.hasOwn(scopes, rule.scope) && Object.hasOwn(dimensions, rule.dimension) && ['text', 'condition'].includes(rule.type) && Array.isArray(rule.conditions) && rule.conditions.every(condition => condition && metrics.includes(condition.metric) && Object.hasOwn(operators, condition.operator) && typeof condition.value === 'number' && Number.isFinite(condition.value));
    if (!config || !Number.isSafeInteger(config.version) || config.version < 0 || !Array.isArray(config.rules) || !config.rules.every(validRule) || typeof config.canManage !== 'boolean' || !(config.updatedAt === null || typeof config.updatedAt === 'number' && Number.isFinite(config.updatedAt))) throw new Error('服务器返回的规则格式不正确，请重试。');
    state.rules = config.rules.map(rule => ({ id: rule.id, title: rule.title, scope: rule.scope, enabled: rule.enabled, type: rule.type, dimension: rule.type === 'text' ? 'summary' : rule.dimension, content: rule.content, conditions: rule.type === 'text' ? [] : rule.conditions.map(condition => ({ metric: condition.metric, operator: condition.operator, value: condition.value })) }));
    state.version = config.version; state.updatedAt = config.updatedAt;
    state.canManage = config.canManage; state.loaded = true; state.conflict = false; state.submissionUnknown = false;
    if (!state.rules.some(rule => rule.id === state.selectedId)) state.selectedId = state.rules[0]?.id || null;
    state.baseline = snapshot();
  }

  async function request(method, body) {
    let response, data;
    const signal = AbortSignal.timeout(15000);
    try {
      response = await fetch('/api/pet/rules', { method, credentials: 'same-origin', cache: method === 'GET' ? 'no-store' : undefined, signal, headers: body ? { 'Content-Type': 'application/json' } : undefined, body: body ? JSON.stringify(body) : undefined });
      try { data = await response.json(); }
      catch (cause) {
        if (signal.aborted || cause?.name === 'TimeoutError') throw cause;
        data = null;
      }
    } catch (cause) {
      if (signal.aborted || cause?.name === 'TimeoutError') {
        const error = new Error(method === 'POST' ? '保存请求超时，提交结果未知。草稿仍保留，请先刷新核对服务器规则，再决定是否修改和保存。' : '加载规则超时，请重试。');
        error.submissionUnknown = method === 'POST';
        throw error;
      }
      throw new Error('无法连接服务器，请检查网络后重试。');
    }
    if (!response.ok) {
      const message = response.status === 409 ? '服务器规则已被其他管理员更新。你的草稿仍保留；重新加载会放弃当前草稿。' : response.status === 401 ? '登录状态已失效，请重新登录后再打开规则。' : response.status === 403 ? '当前账号没有修改规则的权限。' : String(data?.error || data?.message || `请求失败（${response.status}），请重试。`).slice(0, 600);
      const error = new Error(message); error.status = response.status; throw error;
    }
    return data;
  }

  async function loadConfig() {
    if (state.loading || state.saving) return loadPromise;
    state.loading = true; setMessage(); render();
    loadPromise = (async () => {
      try { applyConfig(await request('GET')); setMessage(); }
      catch (error) { setMessage(error.message, 'error'); }
      finally { state.loading = false; render(); }
    })();
    return loadPromise;
  }

  function validatedRules() {
    if (state.rules.length > limits.rules) throw new Error(`最多保存 ${limits.rules} 条规则。`);
    let totalContent = 0;
    return state.rules.map((rule, index) => {
      const prefix = `第 ${index + 1} 条规则：`;
      if (!rule.title.trim()) throw new Error(`${prefix}请填写规则名称。`);
      if (rule.title.length > limits.title) throw new Error(`${prefix}名称最多 ${limits.title} 字。`);
      if (!rule.content.trim()) throw new Error(`${prefix}请填写${rule.type === 'text' ? '分析指令' : '触发后的建议'}。`);
      if (rule.content.length > limits.content) throw new Error(`${prefix}内容最多 ${limits.content} 字。`);
      totalContent += rule.content.length;
      if (totalContent > limits.totalContent) throw new Error(`全部规则的内容合计最多 ${limits.totalContent} 字。`);
      if (!(rule.scope in scopes) || !(rule.dimension in dimensions) || !['text', 'condition'].includes(rule.type)) throw new Error(`${prefix}报表或维度无效，请核对。`);
      if (rule.type === 'condition' && (!rule.conditions.length || rule.conditions.length > limits.conditions)) throw new Error(`${prefix}请设置 1–${limits.conditions} 个条件。`);
      const conditions = rule.type === 'text' ? [] : rule.conditions.map(condition => {
        if (!metrics.includes(condition.metric) || !(condition.operator in operators) || condition.value === '' || !Number.isFinite(Number(condition.value))) throw new Error(`${prefix}请为每个条件选择指标、比较方式并填写有效数值。`);
        return { metric: condition.metric, operator: condition.operator, value: Number(condition.value) };
      });
      return { ...rule, title: rule.title.trim(), dimension: rule.type === 'text' ? 'summary' : rule.dimension, conditions };
    });
  }

  async function saveRules() {
    if (!canEdit() || !isDirty() || state.conflict || state.submissionUnknown) return;
    let rules;
    try { rules = validatedRules(); } catch (error) { setMessage(error.message, 'error'); return; }
    state.saving = true; setMessage(); render();
    try {
      applyConfig(await request('POST', { version: state.version, rules }));
      setMessage('全部规则已保存，全站数据助手将在新的分析中使用。', 'success');
      window.dispatchEvent(new CustomEvent('pet:rules-saved', { detail: { version: state.version, enabledCount: state.rules.filter(rule => rule.enabled).length } }));
    } catch (error) {
      if (error.status === 409) state.conflict = true;
      if (error.submissionUnknown) state.submissionUnknown = true;
      setMessage(error.message, error.status === 409 ? 'conflict' : 'error');
    } finally { state.saving = false; render(); }
  }

  window.PetRules = Object.freeze({
    async open() {
      if (!dialog) createDialog();
      if (dialog.open) return loadPromise;
      returnFocus = document.activeElement;
      render();
      dialog.showModal();
      if (isDirty()) return;
      return loadConfig();
    }
  });
})();
