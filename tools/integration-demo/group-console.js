/* 凭证及租户、操作者和记忆调用 Agent 由桥接服务持有；所有外部展示值仅作为文本写入。 */
(() => {
  'use strict';
  /** @typedef {import('../../frontend-shared/src/open-platform/v1').GroupControlCurrent} ControlCurrent */
  /** @typedef {import('../../frontend-shared/src/open-platform/v1').GroupControlReceipt} ControlReceipt */
  /** @typedef {import('../../frontend-shared/src/open-platform/v1').GroupMemoryFact} MemoryFact */
  /** @typedef {import('../../frontend-shared/src/open-platform/v1').OpenPlatformGroupMember} GroupMember */
  /** @typedef {import('../../frontend-shared/src/open-platform/v1').OpenPlatformGroupAgent} GroupAgent */
  /** @typedef {import('../../frontend-shared/src/open-platform/v1').GroupRunDetail} GroupRun */
  /** @typedef {import('../../frontend-shared/src/open-platform/v1').GroupHistoryPage} HistoryPage */
  /** @typedef {{state:'ready'|'forbidden'|'failed',data:object|null,error_code:string|null}} Section */
  /** @typedef {{id:string,label:string,group_binding_id:string,operator_end_user_id:string,agent_id:string,sender_configured?:boolean}} Context */
  /** @typedef {{name:string,label:string,value?:unknown,type?:string,options?:string[],required?:boolean,wide?:boolean}} Field */
  /** @typedef {{operation:string,title:string,fields:Field[],build:Function,latest?:Function,key:string|null,body:object|null,state:string,message:string}} Draft */
  const $ = id => document.getElementById(id);
  if (!$('group-console')) return;
  const SCOPES = ['execution', 'business_write', 'send', 'memory_extraction'];
  const states = new Map();
  /** @type {Context[]} */
  let contexts = [];
  let selected = '', generation = 0, readController, writeController;
  const state = () => states.get(selected);
  const node = (tag, text, className) => {
    const el = document.createElement(tag);
    if (text !== undefined) el.textContent = String(text ?? '—');
    if (className) el.className = className;
    return el;
  };
  const button = (label, action, disabled = false) => {
    const el = node('button', label, 'secondary'); el.type = 'button'; el.disabled = disabled; el.onclick = action; return el;
  };
  const paragraph = (parent, text, className) => parent.append(node('p', text, className));
  const details = (parent, title, value) => {
    const el = node('details'); el.append(node('summary', title), node('pre', JSON.stringify(value, null, 2))); parent.append(el); return el;
  };
  const kv = (parent, entries) => {
    const dl = node('dl', undefined, 'group-kv');
    for (const [name, value] of entries) dl.append(node('dt', name), node('dd', Array.isArray(value) ? value.join('、') : value));
    parent.append(dl);
  };
  const textField = (name, label, value = '', extra = {}) => ({name, label, value, required: true, ...extra});
  const integer = value => { const n = Number(value); if (!Number.isSafeInteger(n) || n < 0 || String(value).trim() === '') throw Error('版本必须是非负安全整数'); return n; };
  const required = value => { if (!String(value ?? '').trim()) throw Error('请填写所有必填字段'); return String(value).trim(); };
  const scopes = v => { const result = SCOPES.filter(scope => v['scope_' + scope]); if (!result.length) throw Error('请至少选择一项控制范围'); return result; };
  const scopeFields = values => SCOPES.map(scope => ({name: 'scope_' + scope, label: scope, type: 'checkbox', value: values.includes(scope)}));
  const rows = data => Array.isArray(data) ? data : (data?.items || []);
  const data = name => state()?.sections[name]?.data;
  const authorized = action => { const s = state()?.permissions?.[action]; return s?.state === 'ready' && s.data?.authorized === true; };
  const contextUrl = (id = selected) => '/api/groups/' + encodeURIComponent(id);
  const status = text => { $('group-status').textContent = text; };
  const operationSection = operation => ({member:'members',agent:'agents','memory-review':'memories','memory-correct':'memories','memory-forget':'memories','memory-restore':'memories','extraction-policy':'extraction_policy',control:'control','control-release':'control','control-reconcile':'control','control-terminalize':'control','sender-intent/create':'sender','sender-intent/reconcile':'sender','sender-intent/release':'sender','sender-delivery-reconcile':'sender','run-command':'run','run-respond':'run'})[operation];
  function draftAccess(draft) {
    if(!draft)return 'none';
    const section=state()?.sections[operationSection(draft.operation)];
    if(section?.state==='forbidden')return 'forbidden';
    if(section?.state!=='ready')return 'disabled';
    const action=({control:'write','control-release':'release','control-reconcile':'reconcile','control-terminalize':'reconcile','sender-intent/create':'write','sender-intent/reconcile':'reconcile','sender-intent/release':'release','sender-delivery-reconcile':'reconcile'})[draft.operation];
    return action&&!authorized(action)?'disabled':'allowed';
  }

  async function jsonResponse(response) {
    let value;
    try { value = await response.json(); } catch { throw Object.assign(Error('响应无法读取，请保留原请求并核对'), {uncertain: response.ok}); }
    if (!response.ok && response.status !== 202) throw Object.assign(Error(value.error_code || value.error || value.message || `HTTP ${response.status}`), {http: response.status});
    return value;
  }
  async function refresh() {
    if (!selected) return;
    const id = selected, token = ++generation, s = state(); readController?.abort(); readController = new AbortController();
    const query = new URLSearchParams();
    for (const [key, value] of Object.entries(s.query)) if (value !== '' && value != null) query.set(key, value);
    status('正在读取最新群状态…');
    try {
      const snapshot = await jsonResponse(await fetch(contextUrl(id) + '/snapshot?' + query, {signal: readController.signal}));
      if (token !== generation || id !== selected) return;
      const previousAccess=draftAccess(s.draft);
      for (const name of ['group','members','agents','history','control','memories','extraction_policy','usage','run','sender']) {
        const incoming = snapshot[name];
        if (!incoming) continue;
        // 普通故障保留可读旧快照；当前撤权立即清除受保护数据。
        s.sections[name] = incoming.state === 'ready' || incoming.state === 'forbidden' ? {...incoming, data: incoming.state === 'forbidden' ? null : incoming.data, stale: false} : {...incoming, data: s.sections[name]?.data ?? null, stale: s.sections[name]?.data != null};
      }
      s.permissions = snapshot.permissions || {}; s.loaded = true; s.readEpoch = (s.readEpoch || 0) + 1;
      render();
      if(previousAccess!==draftAccess(s.draft)){if(s.draft)saveFields(s.draft);renderEditor();}
      status('已读取最新状态。各区域的拒绝、失败与保留旧数据会单独标明。');
    } catch (error) {
      if (token !== generation || id !== selected) return;
      if(error.http===401||error.http===403){
        if(s.draft)saveFields(s.draft);
        for(const name of Object.keys(s.sections))s.sections[name]={state:'forbidden',data:null,error_code:'ACCESS_REVOKED',stale:false};
        s.permissions={};render();renderEditor();status('当前身份已无法读取此群，已清除受保护的展示数据。请核对服务端授权后重新读取。');return;
      }
      status('读取失败：' + error.message + '。保留已显示的数据和操作草稿，请重新读取。');
    }
  }
  function changeContext(id) {
    ++generation; readController?.abort();
    if (state()?.draft?.state === 'submitting') {
      state().draft.state = 'unknown'; state().draft.message = '切换群时请求尚未确认，请回到本群核对原请求。'; writeController?.abort();
    }
    selected = id;
    if (!states.has(id)) states.set(id, {sections: {}, permissions: {}, query: {memory_status: 'ACTIVE'}, draft: null});
    $('group-run-id').value = state().query.run_id || '';
    $('group-result').hidden = !state().result;
    $('group-result-body').textContent = state().result ? JSON.stringify(state().result,null,2) : '';
    render(); renderEditor(); refresh();
  }
  function panel(name, title, initiallyOpen = false) {
    const el = node('details', undefined, 'group-panel'); el.dataset.panel = name; el.open = initiallyOpen;
    el.append(node('summary', title)); $('group-panels').append(el);
    const s = state()?.sections[name];
    if (!s) { paragraph(el, '尚未读取。', 'muted'); return {el, value: null, ready: false}; }
    if (s.state !== 'ready') paragraph(el, `${s.state === 'forbidden' ? '当前身份无权读取' : '读取失败'}：${s.error_code || '未知原因'}${s.stale ? '。以下保留上次读取数据，不能作为最新状态。' : ''}`, 'group-error');
    if (s.stale) el.classList.add('group-stale');
    return {el, value: s.data, ready: s.state === 'ready'};
  }
  function actionRow(parent, actions) { const row = node('div', undefined, 'group-actions'); for (const action of actions) row.append(action); parent.append(row); return row; }
  function record(parent, title) { const el = node('article', undefined, 'group-record'); el.append(node('h4', title)); parent.append(el); return el; }
  function paging(parent, page, key) {
    const next = page?.next_after ?? page?.next_cursor;
    actionRow(parent, [button('第一页', () => { state().query[key] = ''; refresh(); }, !state().query[key]), button('下一页', () => { state().query[key] = next; refresh(); }, !page?.has_more || !next)]);
    paragraph(parent, `本页 ${rows(page).length} 条${page?.has_more ? '，后面还有数据' : ''}；不是全群总数。`, 'muted');
  }
  function render() {
    const open = new Set([...$('group-panels').querySelectorAll('details[open][data-panel]')].map(el => el.dataset.panel));
    const focused = document.activeElement?.dataset?.focus;
    $('group-panels').replaceChildren(); if (!selected) return;
    const ctx = contexts.find(c => c.id === selected);
    const group = panel('group', '群与当前身份', true);
    kv(group.el, [['群', ctx?.group_binding_id], ['操作人', ctx?.operator_end_user_id], ['配置服务 Agent', ctx?.agent_id]]);
    if (group.value) details(group.el, '正式群状态', group.value);
    renderMembers(); renderAgents(); renderHistory(); renderMemory(); renderPolicy(); renderControl(); renderSender(); renderRun();
    const usage = panel('usage', '群用量归属'); if (usage.value) details(usage.el, '正式用量事实', usage.value);
    for (const el of $('group-panels').querySelectorAll('[data-panel]')) if (open.has(el.dataset.panel)) el.open = true;
    if (focused) [...$('group-panels').querySelectorAll('[data-focus]')].find(el => el.dataset.focus === focused)?.focus();
  }
  function renderMembers() {
    const {el, value, ready} = panel('members', '群成员与管理者');
    actionRow(el, [button('添加成员', () => memberDraft(null), !ready)]);
    if (!value) return; paging(el, value, 'members_after');
    for (const m of rows(value)) {
      const row = record(el, m.end_user_id); kv(row, [['角色',m.role],['状态',m.status],['版本',m.revision],['来源版本',m.source_revision]]);
      const b = button('修改成员', () => memberDraft(m), !ready); b.dataset.focus = 'member:' + m.end_user_id; actionRow(row,[b]);
    }
  }
  /** @param {GroupMember|null} m */
  function memberDraft(m) {
    edit('member', '成员投影', [textField('end_user_id','成员 EndUser ID',m?.end_user_id), {name:'role',label:'角色',value:m?.role || 'member',options:['member','manager']}, {name:'status',label:'状态',value:m?.status || 'active',options:['active','left']}, textField('expected_revision','已读版本',m?.revision ?? 0,{type:'number'}), textField('source_event_id','来源事件 ID'), textField('source_revision','来源版本',(m?.source_revision ?? 0) + 1,{type:'number'})], v => ({end_user_id:required(v.end_user_id),role:v.role,status:v.status,expected_revision:integer(v.expected_revision),source_event_id:required(v.source_event_id),source_revision:integer(v.source_revision)}), v => rows(data('members')).find(x => x.end_user_id === v.end_user_id)?.revision ?? (m ? null : 0));
  }
  function renderAgents() {
    const {el,value,ready} = panel('agents','服务 Agent');
    actionRow(el,[button('添加服务 Agent', () => agentDraft(null), !ready)]);
    if (!value) return; paging(el,value,'agents_after');
    for (const a of rows(value)) { const row = record(el,a.agent_id); kv(row,[['状态',a.status],['版本',a.revision],['来源版本',a.source_revision]]); actionRow(row,[button('修改服务 Agent',()=>agentDraft(a),!ready)]); }
  }
  /** @param {GroupAgent|null} a */
  function agentDraft(a) {
    edit('agent','服务 Agent 的成员状态',[textField('agent_id','目标服务 Agent ID',a?.agent_id||''),{name:'status',label:'状态',value:a?.status || 'active',options:['active','removed']},textField('expected_revision','已读版本',a?.revision ?? 0,{type:'number'}),textField('source_event_id','来源事件 ID'),textField('source_revision','来源版本',(a?.source_revision ?? 0)+1,{type:'number'})],v=>({agent_id:required(v.agent_id),status:v.status,expected_revision:integer(v.expected_revision),source_event_id:required(v.source_event_id),source_revision:integer(v.source_revision)}),v=>rows(data('agents')).find(x=>x.agent_id===v.agent_id)?.revision ?? (a ? null : 0));
  }
  function renderHistory() {
    const {el,value} = panel('history','公开历史'); if (!value) return; paging(el,value,'history_cursor');
    for (const m of rows(value)) { const row=record(el,`${m.role} · ${m.message_id} · v${m.message_version}`); paragraph(row,typeof m.content==='string'?m.content:JSON.stringify(m.content)); details(row,'公开元数据与引用',{created_at:m.created_at,state:m.state,phase:m.phase,metadata:m.metadata,references:m.references}); }
  }
  function renderMemory() {
    const {el,value,ready}=panel('memories','群与成员记忆');
    const filters=node('form',undefined,'group-toolbar');
    const label=node('label','状态'); const select=node('select'); select.setAttribute('aria-label','记忆状态');
    for(const option of ['ACTIVE','PENDING_CONFIRM','FORGOTTEN','DECAYED','REJECTED','ARCHIVED']) {const o=node('option',option);o.value=option;select.append(o);} select.value=state().query.memory_status; label.append(select);
    const subjectLabel=node('label','描述对象 EndUser ID');const subject=node('input');subject.setAttribute('aria-label','记忆描述对象');subject.value=state().query.subject_end_user_id||'';subjectLabel.append(subject);
    filters.append(label,subjectLabel);const submit=node('button','筛选记忆');submit.type='submit';filters.append(submit);filters.onsubmit=e=>{e.preventDefault();state().query.memory_status=select.value;state().query.subject_end_user_id=subject.value.trim();refresh();};el.append(filters);
    paragraph(el,'描述对象与当前操作者分开。自动提取、审核、更正和遗忘复用正式记忆治理；遗忘后的防回填由服务端负责。','muted');
    paragraph(el,'每次读取最多 100 条符合筛选的记忆；使用状态与描述对象筛选定位，不代表全群记忆总数。','muted');
    if(!value)return;
    for(const fact of rows(value)){
      const row=record(el,`${fact.scope} · ${fact.status} · ${fact.fact_id}`);
      kv(row,[['描述对象',fact.subject_end_user_id],['事实',`${fact.subject} / ${fact.predicate}`],['版本',fact.revision],['置信度',fact.confidence],['重要度',fact.importance]]);details(row,'内容及来源',fact);
      const actions=[];
      if(fact.status==='PENDING_CONFIRM')for(const approved of [true,false])actions.push(button(approved?'审核通过':'审核拒绝',()=>simpleMemory('memory-review',approved?'审核通过':'审核拒绝',fact,{approved}),!ready));
      if(fact.status!=='FORGOTTEN'){actions.push(button('更正记忆',()=>correctMemory(fact),!ready),button('遗忘',()=>simpleMemory('memory-forget','遗忘记忆',fact),!ready));}
      else actions.push(button('恢复记忆',()=>simpleMemory('memory-restore','恢复记忆',fact),!ready));
      actionRow(row,actions);
    }
    if(!rows(value).length)paragraph(el,'当前筛选没有记忆。','muted');
  }
  const memoryRevision = id => rows(data('memories')).find(x=>x.fact_id===id)?.revision;
  function simpleMemory(operation,title,fact,extra={}) {
    edit(operation,title,[textField('fact_id','事实 ID',fact.fact_id),textField('expected_revision','已读版本',fact.revision,{type:'number'})],v=>({fact_id:required(v.fact_id),expected_revision:integer(v.expected_revision),...extra}),v=>memoryRevision(v.fact_id));
  }
  /** @param {MemoryFact} fact */
  function correctMemory(fact) {
    let content;
    try { content=JSON.parse(fact.object_json); } catch { status('该事实的正式记忆内容无法读取，请先核对原始内容。'); return; }
    const temporal=content.temporal || {};
    edit('memory-correct','更正记忆：以文字内容替换事实值',[textField('fact_id','事实 ID',fact.fact_id),textField('expected_revision','已读版本',fact.revision,{type:'number'}),textField('text','更正后的文字',typeof content.value?.text==='string'?content.value.text:'',{type:'textarea',wide:true}),textField('conditions','适用条件（每行一条）',(content.conditions||[]).join('\n'),{type:'textarea',required:false,wide:true}),{name:'temporal_kind',label:'时效',value:temporal.kind||'unknown',options:['stable','task','interval','unknown']},textField('temporal_from','生效起点（ISO 时间）',temporal.from||'',{required:false}),textField('temporal_to','生效终点（ISO 时间）',temporal.to||'',{required:false}),textField('subject','主体',fact.subject),textField('predicate','关系',fact.predicate)],v=>({fact_id:required(v.fact_id),expected_revision:integer(v.expected_revision),subject:required(v.subject),predicate:required(v.predicate),object_json:JSON.stringify({value:{text:required(v.text)},conditions:v.conditions.split('\n').map(x=>x.trim()).filter(Boolean),temporal:{kind:v.temporal_kind,from:v.temporal_from.trim()||null,to:v.temporal_to.trim()||null}})}),v=>memoryRevision(v.fact_id));
  }
  function renderPolicy() {
    const {el,value,ready}=panel('extraction_policy','自动记忆提取策略');if(!value)return;
    kv(el,[['启用',value.enabled?'是':'否'],['提取 Agent',value.extractor_agent_id],['版本',value.revision]]);
    actionRow(el,[button('修改提取策略',()=>edit('extraction-policy','自动提取策略',[{name:'enabled',label:'启用自动提取',type:'checkbox',value:value.enabled},textField('expected_revision','已读版本',value.revision,{type:'number'})],v=>({enabled:v.enabled,expected_revision:integer(v.expected_revision)}),()=>data('extraction_policy')?.revision),!ready||!authorized('release'))]);
  }
  /** @param {HTMLElement} parent @param {ControlReceipt|null} value */
  function receipt(parent, value) {
    if(!value)return;
    const labels={ACCEPTED:'已受理，仍需参与方应用',PARTIAL:'部分参与方已应用',APPLIED:'要求的参与方已应用',REJECTED:'已拒绝，保留原请求并核对本地意图',SUPERSEDED:'已被后继命令覆盖',TERMINAL_NOT_ACCEPTED:'已证明未受理'};
    paragraph(parent,`${value.status}：${labels[value.status]||'以原回执为准'}`,'group-state');
    kv(parent,[['命令',value.command_id],['控制版本',value.control_revision],['结果凭据',value.result_ref],['阻断边界',value.boundary_ref],['覆盖触发序号',value.through_trigger_sequence]]);
    details(parent,'必需参与方 / 持久 ACK / 在途发送',{required_participants:value.required_participants,durable_acks:value.durable_acks,in_flight:value.in_flight});
  }
  function renderControl() {
    const {el,value,ready}=panel('control','群暂停、恢复与精确积压释放',true);
    paragraph(el,'ACCEPTED 仅表示受理；PARTIAL 与 APPLIED 依据参与方持久 ACK。恢复运行不会自动放行旧的被阻断消息。','muted');
    actionRow(el,[button('暂停 / 恢复',()=>controlDraft(),!ready||!authorized('write')),button('核对原请求',()=>edit('control-reconcile','核对原控制请求',[textField('command_id','原命令 ID',value?.latest_command?.command_id||'')],v=>({command_id:required(v.command_id)})),!authorized('reconcile')),button('精确释放消息',()=>releaseDraft(),!ready||!authorized('release')),button('证明原请求未受理',()=>terminalDraft(),!authorized('reconcile'))]);
    if(!value)return;
    kv(el,[['控制版本',value.control_revision],['最新触发序号',value.latest_trigger_sequence]]);
    details(el,'各控制范围与阻断边界',{states:value.states,blocked_boundaries:value.blocked_boundaries});
    for(const endpoint of value.endpoints||[]){const row=record(el,`${endpoint.endpoint_id} · generation ${endpoint.generation}`);kv(row,[['注册 / 退休状态',endpoint.status],['连接状态',endpoint.connection_state||'UNKNOWN'],['最后持久确认',endpoint.last_confirmed_at],['已应用版本',endpoint.applied_revision],['栅栏版本',endpoint.fence_revision],['退休证据',endpoint.retirement_receipt_ref],['账本',endpoint.ledger_ref]]);}
    paragraph(el,'ACTIVE 表示注册和门禁状态，不能据此推断连接正常。断连、在途投递与退休代际分别保留证据。','muted');
    receipt(el,value.latest_command);
    details(el,'控制动作授权',state().permissions);
  }
  function controlDraft() {
    edit('control','提交群控制意图',[textField('run_id','限定 Run（空为群）',state().query.run_id||'',{required:false}),textField('expected_revision','已读控制版本',data('control')?.control_revision,{type:'number'}),{name:'desired_state',label:'目标状态',value:'paused',options:['paused','running']},...scopeFields(SCOPES)],v=>({command_id:crypto.randomUUID(),run_id:v.run_id.trim()||null,expected_revision:integer(v.expected_revision),desired_state:v.desired_state,scopes:scopes(v)}),()=>data('control')?.control_revision);
  }
  function terminalDraft() {
    const original=data('control')?.latest_command;
    edit('control-terminalize','证明原请求未受理（须提供原本地意图与指纹）',[textField('command_id','原命令 ID',original?.command_id||''),textField('fingerprint','原请求指纹',original?.fingerprint||''),textField('local_intent_id','原本地意图 ID'),textField('run_id','限定 Run',original?.run_id||'',{required:false}),textField('expected_revision','原命令预期版本',Math.max(0,(original?.control_revision||1)-1),{type:'number'})],v=>({command_id:required(v.command_id),fingerprint:required(v.fingerprint),local_intent_id:required(v.local_intent_id),run_id:v.run_id.trim()||null,expected_revision:integer(v.expected_revision)}));
  }
  function releaseDraft() {
    const boundaries=data('control')?.blocked_boundaries||[];
    edit('control-release','精确释放既有消息和边界',[textField('run_id','消息所属 Run',state().query.run_id||''),textField('expected_revision','已读控制版本',data('control')?.control_revision,{type:'number'}),textField('message_id','消息 ID'),textField('message_version','消息版本',1,{type:'number'}),textField('delivery_id','原 delivery_id'),...boundaries.map((b,i)=>({name:'boundary_'+i,label:'释放边界 '+b.boundary_ref,type:'checkbox',value:false}))],v=>{const refs=boundaries.filter((_,i)=>v['boundary_'+i]).map(b=>b.boundary_ref);if(!refs.length)throw Error('请显式选择原阻断边界');return{run_id:required(v.run_id),expected_revision:integer(v.expected_revision),messages:[{message_id:required(v.message_id),message_version:integer(v.message_version),delivery_id:required(v.delivery_id)}],blocked_boundary_refs:refs};},()=>data('control')?.control_revision);
  }
  function renderSender() {
    const {el,value,ready}=panel('sender','独立发送器：本地意图与投递对账');
    paragraph(el,'本地暂停意图和远端控制命令分别展示。核对原控制请求不会解除本地阻断；只有本地意图正式放行回执可以证明解除。','muted');
    actionRow(el,[button('创建本地暂停意图',()=>edit('sender-intent/create','创建发送器本地暂停意图',[textField('local_intent_id','本地意图 ID',crypto.randomUUID()),textField('run_id','限定 Run（空为群）',state().query.run_id||'',{required:false}),...scopeFields(['send'])],v=>({local_intent_id:required(v.local_intent_id),run_id:v.run_id.trim()||null,scopes:scopes(v),expected_revision:0})),!ready||!authorized('write'))]);
    if(!value)return;kv(el,[['账本',value.ledger_ref]]);details(el,'发送端点与投递明细',{endpoints:value.endpoints,deliveries:value.deliveries});
    for(const intent of value.intents||[]){const row=record(el,intent.intent_id);kv(row,[['本地阶段',intent.status],['本地阻断仍有效',intent.active?'是':'否'],['意图版本',intent.revision],['远端命令',intent.command_id],['阻断边界',intent.boundary_ref],['范围',intent.scopes]]);details(row,'原请求、远端回执与终止证据',{attempts:intent.attempts,submissions:intent.submissions,terminalizations:intent.terminalizations});
      actionRow(row,[...['query','resubmit','abandon'].map(action=>button(({query:'对账原意图',resubmit:'核对并重新提交',abandon:'证明终止后放弃'})[action],()=>edit('sender-intent/reconcile','本地意图 '+action,[textField('intent_id','原意图 ID',intent.intent_id),textField('expected_revision','已读意图版本',intent.revision,{type:'number'})],v=>({intent_id:required(v.intent_id),expected_revision:integer(v.expected_revision),action}),v=>(data('sender')?.intents||[]).find(i=>i.intent_id===v.intent_id)?.revision),!ready||!authorized('reconcile'))),button('凭恢复回执放行本地意图',()=>edit('sender-intent/release','放行本地意图',[textField('intent_id','原意图 ID',intent.intent_id),textField('expected_revision','已读意图版本',intent.revision,{type:'number'}),textField('resume_command_id','已 APPLIED 的恢复命令 ID')],v=>({intent_id:required(v.intent_id),expected_revision:integer(v.expected_revision),resume_command_id:required(v.resume_command_id)}),v=>(data('sender')?.intents||[]).find(i=>i.intent_id===v.intent_id)?.revision),!ready||!authorized('release'))]);
    }
    actionRow(el,[button('核对未知投递',()=>edit('sender-delivery-reconcile','核对原投递',[textField('delivery_id','原 delivery_id')],v=>({delivery_id:required(v.delivery_id)})),!ready||!authorized('reconcile'))]);details(el,'发送器审计事实',value.audit||[]);
  }
  function renderRun() {
    const {el,value,ready}=panel('run','Run 状态、等待、取消与纠正');
    if(!value){paragraph(el,'在顶部填写 Run ID 后读取。','muted');return;}
    kv(el,[['Run',value.run_id],['状态',value.status],['阶段',value.phase],['版本',value.revision]]);
    actionRow(el,[button('取消执行',()=>runCommand(value,'cancel_execution'),!ready||value.phase==='FINISHED'),button('发送执行纠正',()=>runCommand(value,'steer'),!ready||value.phase==='FINISHED')]);
    for(const interaction of value.pending_interactions||[])renderInteraction(el,value,interaction,ready);
  }
  /** @param {GroupRun} run @param {'steer'|'cancel_execution'} action */
  function runCommand(run,action) {
    const fields=action==='steer'?[textField('text','执行纠正内容','',{type:'textarea',wide:true})]:[];
    edit('run-command',action==='steer'?'纠正当前执行':'取消当前执行',fields,v=>({run_id:run.run_id,action,...(action==='steer'?{text:required(v.text)}:{})}));
  }
  function schemaFields(schema,prefix) {
    if(schema?.type!=='object'||!schema.properties)throw Error('当前等待的结构不属于业务确认表单，请使用正式会话端处理。');
    return Object.entries(schema.properties).map(([name,spec])=>{
      if(!['string','boolean','integer','number'].includes(spec.type))throw Error('当前字段需要正式会话端的专用控件：'+name);
      return{name:prefix+name,label:spec.title||name,type:spec.type==='boolean'?'checkbox':spec.type==='number'||spec.type==='integer'?'number':'text',value:spec.default??(spec.type==='boolean'?false:''),options:spec.enum?.map(String),required:(schema.required||[]).includes(name),spec,property:name};
    });
  }
  function schemaValue(fields,values) {
    const result={};for(const f of fields){let v=values[f.name];if(f.type!=='checkbox'&&v===''&&!f.required)continue;if(f.required&&v===''&&f.type!=='checkbox')throw Error('请填写 '+f.label);if(f.spec.type==='number'||f.spec.type==='integer'){v=Number(v);if(!Number.isFinite(v)||f.spec.type==='integer'&&!Number.isSafeInteger(v))throw Error('请填写有效数字：'+f.label);}if(f.spec.enum){v=f.spec.enum.find(item=>String(item)===String(v));if(v===undefined)throw Error('请选择有效选项');}result[f.property]=v;}return result;
  }
  function renderInteraction(parent,run,interaction,ready) {
    const card=interaction.card||{},row=record(parent,card.title||interaction.interaction_type);
    paragraph(row,card.body||interaction.question||'当前 Run 正在等待输入。');
    if(card.requests)for(const request of card.requests)paragraph(row,request.message);
    details(row,'公开等待绑定',{run_revision:interaction.run_revision,tool_wait:interaction.tool_wait,interaction_type:interaction.interaction_type,card});
    try {
      let fields=[], build;
      if(interaction.interaction_type==='user_input'){
        fields=[textField('text','回复内容','',{type:'textarea',wide:true})];build=v=>({execution_id:interaction.execution_id,run_revision:interaction.run_revision,human_input:{wait:interaction.tool_wait,text:required(v.text)}});
      }else if(interaction.interaction_type==='tool_input'&&card.requests){
        const requests=card.requests.map((r,i)=>({...r,fields:schemaFields(r.requested_schema,'request_'+i+'_')}));fields=requests.flatMap(r=>r.fields);
        build=v=>({execution_id:interaction.execution_id,run_revision:interaction.run_revision,tool_input:{wait:interaction.tool_wait,responses:Object.fromEntries(requests.map(r=>[r.id,{method:'elicitation/create',result:{schema_id:'application/json',canonical_json:JSON.stringify({action:'accept',content:schemaValue(r.fields,v)})}}]))}});
      }else if(interaction.interaction_type==='workflow_input'){
        const schema=card.requested_schema||card.requestedSchema||card.schema;
        fields=schemaFields(schema,'workflow_');build=v=>({execution_id:interaction.execution_id,run_revision:interaction.run_revision,workflow_input:{wait:interaction.tool_wait,execution_ref:interaction.workflow_execution_id,suspend_ref:interaction.suspend_ref,execution_revision:interaction.execution_revision,action_key:interaction.resume_action_key,response:{schema_id:'application/json',canonical_json:JSON.stringify(schemaValue(fields,v))}}});
      }else{paragraph(row,'当前等待由其正式会话控件处理；依赖等待无需人工回复。','muted');return;}
      actionRow(row,[button('填写当前等待回复',()=>edit('run-respond','回答当前 Run 等待',fields,v=>({run_id:run.run_id,hitl_response:build(v)})),!ready)]);
    }catch(error){paragraph(row,error.message,'group-warning');}
  }

  function values() {
    const result={};for(const input of $('group-editor').querySelectorAll('[data-field]'))result[input.dataset.field]=input.type==='checkbox'?input.checked:input.value;return result;
  }
  function saveFields(draft) { const v=values(); for(const f of draft.fields)if(Object.hasOwn(v,f.name))f.value=v[f.name]; }
  function edit(operation,title,fields,build,latest) {
    const old=state().draft;
    if(old&&['submitting','unknown'].includes(old.state)){status('原操作结果尚未确认。请先在下方核对原请求，保留原请求体与幂等键。');$('group-editor').scrollIntoView?.({block:'nearest'});return;}
    if(old&&old.state!=='confirmed'&&!confirm('放弃当前编辑草稿并切换操作？'))return;
    state().draft={operation,title,fields,build,latest,key:null,body:null,state:'editing',message:''};renderEditor();
    $('group-editor').querySelector('input,select,textarea,button')?.focus();
  }
  function renderEditor() {
    const root=$('group-editor'),draft=state()?.draft;root.replaceChildren();root.hidden=!draft;
    if(!draft)return;
    root.append(node('h3',draft.title));paragraph(root,`业务群：${selected} · 操作：${draft.operation}`,'muted');
    const access=draftAccess(draft);
    if(access==='forbidden'){paragraph(root,'当前已无权读取此对象，编辑内容已隐藏。原请求身份仍保留；恢复访问后可继续核对。','group-error');return;}
    const form=node('form'),fields=node('div',undefined,'group-fields');
    for(const field of draft.fields){
      const label=node('label',field.label,field.wide?'group-wide':'');
      let input;
      if(field.options){input=node('select');for(const option of field.options){const o=node('option',option);o.value=option;input.append(o);}}
      else input=node(field.type==='textarea'?'textarea':'input');
      if(input.tagName==='INPUT')input.type=field.type||'text';
      input.setAttribute('aria-label',field.label);input.dataset.field=field.name;input.name=field.name;input.required=!!field.required;input.disabled=['submitting','unknown','confirmed'].includes(draft.state);
      if(field.type==='checkbox'){input.checked=!!field.value;label.classList.add('group-check');}else input.value=field.value??'';
      if(field.type==='number'){input.min='0';input.step=field.spec?.type==='number'?'any':'1';}
      label.append(input);fields.append(label);
    }
    form.append(fields);const message=node('p',draft.message||'提交前请核对对象、版本与来源。','management-status');message.setAttribute('role','status');root.append(form,message);
    const actions=[];
    if(draft.state==='unknown'){
      paragraph(root,'UNKNOWN：原请求可能已生效。下面核对沿用原请求体和原幂等键，不建立新意图。','group-warning');
      actions.push(button('核对原操作结果',()=>submit(true)));
      if(draft.operation==='control')actions.push(button('读取原控制回执',()=>lookupCommand(draft.body.command_id)));
    }else if(draft.state==='conflict'){
      paragraph(root,'版本冲突：已保留原输入。先读取最新状态，再显式核对版本后提交新意图。','group-warning');
      actions.push(button('读取最新状态',refresh),button('按最新版本重新核对',reviewLatest,!draft.latest));
    }else if(draft.state!=='confirmed'){
      const send=node('button',draft.state==='submitting'?'正在提交…':'提交此操作');send.type='submit';send.disabled=draft.state==='submitting'||access!=='allowed';actions.push(send);
    }
    if(!['unknown','submitting'].includes(draft.state))actions.push(button(draft.state==='confirmed'?'关闭回执与编辑':'放弃草稿',()=>{state().draft=null;renderEditor();}));
    actionRow(form,actions);form.onsubmit=e=>{e.preventDefault();submit(false);};
    if(draft.body)details(root,'保留的原始意图（无服务端凭证）',{idempotency_key:draft.key,body:draft.body});
  }
  function reviewLatest() {
    const draft=state().draft;saveFields(draft);const v=Object.fromEntries(draft.fields.map(f=>[f.name,f.value]));
    const section=operationSection(draft.operation);
    if(!section||state().sections[section]?.state!=='ready'||(state().readEpoch||0)<=(draft.conflictEpoch??-1)){
      draft.message='尚未成功读到冲突后的最新对象，不能使用保留的旧版本。请先读取最新状态后再核对。';renderEditor();return;
    }
    const revision=draft.latest?.(v);
    if(revision==null){draft.message='当前页未包含原对象的最新版本。请先读取对象所在页，或恢复原筛选后再核对。';renderEditor();return;}
    const f=draft.fields.find(f=>f.name==='expected_revision');if(f)f.value=revision;
    draft.key=null;draft.body=null;draft.state='editing';draft.message='已绑定刚读取的最新版本，请重新核对保留的输入后提交。';renderEditor();
  }
  async function lookupCommand(commandId) {
    const id=selected,token=generation;
    try{const result=await jsonResponse(await fetch(contextUrl(id)+'/commands/'+encodeURIComponent(commandId)));if(id===selected&&token===generation)showResult(result);}
    catch(error){if(id===selected)status('核对原命令失败：'+error.message+'。原意图继续保留。');}
  }
  function showResult(result) {state().result=result;$('group-result').hidden=false;$('group-result-body').textContent=JSON.stringify(result,null,2);}
  async function submit(checkOriginal) {
    const id=selected,s=state(),draft=s.draft,token=generation;
    if(!draft||draft.state==='submitting'||draft.state==='confirmed'||draft.state==='unknown'&&!checkOriginal)return;
    if(draftAccess(draft)==='forbidden'||!checkOriginal&&draftAccess(draft)!=='allowed'){draft.message='当前对象不可读或授权尚未确认，请先重新读取最新状态。';renderEditor();return;}
    try{
      if(!checkOriginal){saveFields(draft);draft.key=crypto.randomUUID();draft.body=draft.build(values());if(draft.operation==='control')draft.key=draft.body.command_id;if(['run-command','run-respond'].includes(draft.operation))draft.body.input_id=draft.key;}
    }catch(error){draft.message=error.message;renderEditor();return;}
    draft.state='submitting';draft.message=checkOriginal?'正在核对原请求…':'正在提交原请求…';renderEditor();
    writeController=new AbortController();
    try{
      const response=await fetch(contextUrl(id)+'/'+draft.operation,{method:'POST',headers:{'Content-Type':'application/json','Idempotency-Key':draft.key},body:JSON.stringify(draft.body),signal:writeController.signal});
      const result=await jsonResponse(response);
      if(result.state==='UNKNOWN'){draft.state='unknown';draft.message='结果待核实：'+(result.error_code||'UNKNOWN')+'。原请求体与幂等键已保留。';}
      else{draft.state='confirmed';draft.message='服务端已返回操作回执；具体受理、应用和本地解除状态请查看回执及最新状态。';}
      s.result=result;
      if(id===selected&&s.draft===draft&&token===generation){showResult(result);renderEditor();await refresh();}
    }catch(error){
      // 原结果核对失败不能证明原写入失败。
      draft.state=checkOriginal?'unknown':error.http===409?'conflict':error.http&&error.http<500?'editing':'unknown';
      if(draft.state==='conflict')draft.conflictEpoch=s.readEpoch||0;
      draft.message=(draft.state==='unknown'?'结果尚未确认，继续保留原请求：':'提交失败，保留输入：')+error.message;
      if(id===selected&&s.draft===draft&&token===generation){renderEditor();if(draft.state==='conflict')await refresh();}
    }
  }
  $('group-context-form').onsubmit=e=>{e.preventDefault();if(!selected)return;state().query.run_id=$('group-run-id').value.trim();refresh();};
  $('group-context').onchange=()=>{if(state()?.draft)saveFields(state().draft);changeContext($('group-context').value);};
  window.addEventListener('beforeunload',event=>{if([...states.values()].some(s=>s.draft&&s.draft.state!=='confirmed')){event.preventDefault();event.returnValue='';}});
  window.addEventListener('pagehide',()=>{++generation;readController?.abort();writeController?.abort();});
  async function initialize() {
    try{
      const result=await jsonResponse(await fetch('/api/groups/contexts'));contexts=result.items||[];
      $('group-context').replaceChildren();
      for(const ctx of contexts){const option=node('option',ctx.label||ctx.id);option.value=ctx.id;$('group-context').append(option);}
      if(contexts.length)changeContext(contexts[0].id);else{const option=node('option','未配置业务群');option.value='';$('group-context').append(option);status('请在服务端配置业务群及其操作者凭证。');}
    }catch(error){status('读取业务群配置失败：'+error.message);$('group-context').replaceChildren(node('option','配置读取失败'));}
  }
  initialize();
})();
