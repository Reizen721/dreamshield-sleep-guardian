// Explicit state editing and opt-in controls. Polling/rendering never send commands.
(function (root) {
  'use strict';
  const MODES = {auto: '自动', cool: '制冷', dry: '除湿', fan: '送风', heat: '制热'};
  const FANS = {auto: '自动风速', low: '低风', medium: '中风', high: '高风'};
  const REASONS = {
    not_enabled: '空调控制尚未启用', user_stopped: '用户已禁用控制',
    user_paused: '用户已暂停自动睡眠控制', manual_override: '手动操作已暂停自动，需明确重新开启',
    policy_settings_changed_explicit_resume_required: '策略参数已更新，请确认后手动恢复自动控制',
    manual_command: '手动操作已暂停自动，需明确重新开启',
    waiting_verified_visual: '等待可靠视觉判决', visual_expired_or_missing: '视觉结论缺失或过期，保持不发射',
    no_reliable_occupied_coverage: '无人、画面不确定或未覆盖待确认，保持不发射',
    environment_unavailable_or_expired: '环境读数不可用或缓存已过期，保持不发射',
    within_band_no_change: '环境在设定范围内，保持当前命令',
    command_cooldown: '发射冷却期内，保持当前命令',
    setpoint_unchanged_no_duplicate_emission: '目标不变，不重复发射',
    manual_outcome_unknown: '手动发射结果未知；不自动重试，需核对后重新操作',
    outcome_unknown_no_retry: '发射结果未知；不自动重试，自动控制已暂停',
    device_rebooted_rearm_required: '设备已重启，需重新启用控制',
    automatic_ai_disabled: '自动 AI 已停止，自动睡眠控制已暂停',
    automatic_ai_stopped: '自动 AI 已停止，自动睡眠控制已暂停',
    state_unknown: '最后命令状态未知，不能进入自动睡眠控制',
    current_state_not_cooling: '当前假设 / 最后命令不是开机制冷，不能进入自动睡眠控制',
    audit_write_failed: '发射记录保存失败，控制已暂停',
    session_disabled: '空调控制已禁用', automatic_disabled: '自动睡眠控制已暂停',
    session_enabled_no_transmission: '控制已启用，初始状态仅作假设，尚未发射',
    waiting_explicit_automatic_resume: '等待明确开启自动睡眠控制',
    waiting_new_verified_visual: '自动控制已开启，等待本次开启后的新可靠视觉判决',
    user_stopped_automatic: '用户已暂停自动睡眠控制',
    automatic_stopped: '自动睡眠控制已暂停',
    manual_override_requires_explicit_resume: '手动操作已暂停自动，需要明确重新开启',
    request_history_limit_rearm_required: '达到本次请求记录上限，需重新启用控制',
    manual_command_cooldown: '手动发射处于 5 秒冷却期，保持不发射',
    automatic_command_cooldown: '自动发射处于配置的冷却期，保持不发射',
    invalid_visual_job: '视觉任务编号缺失，保持不发射',
    invalid_visual_result: '视觉结果无效，保持不发射',
    visual_result_incomplete_or_failed: '视觉任务失败或结果未完成，保持不发射',
    visual_history_limit: '达到本次视觉任务上限，自动控制已暂停',
    device_identity_changed_rearm_required: '设备或启动编号已变化，需重新启用控制',
    visual_expired_or_predates_resume: '视觉结果过期或早于本次自动开启，等待新判决',
    environment_source_invalid: '环境数据来源无效，保持不发射',
    invalid_sensor_sequence: '传感器采样编号无效，保持不发射',
    automatic_requires_known_on_cool_state: '缺少已知开机制冷状态，自动控制已暂停',
    emission_deferred_environment_tracking: '正在记录真实环境连续性，暂不发射',
    automatic_setpoint_bound_reached: '已达到用户允许的设定温度边界，保持不发射',
    coverage_event_already_handled: '本次未盖被事件已经调节，等待稳定恢复，不重复保护性升温',
    coverage_event_recovery_pending: '正在观察是否稳定盖回或空床，尚未解除本次事件锁',
    temperature_below_band_for_45_seconds: '至少 3 个独立样本确认低温持续 45 秒',
    temperature_above_band_for_45_seconds_covered: '正常盖被，且至少 3 个独立样本确认高温持续 45 秒',
    explicit_manual_state: '用户明确提交的手动完整状态',
    emitted_local_only_not_ac_acknowledged: '本地红外已发出，实际空调响应未确认',
    emission_outcome_unknown_rearm_required: '发射结果未知；控制已停用，不自动重试，需重新启用',
    result_audit_write_failed: '发射回执无法保存，控制已停用'
  };
  const FIELD_IDS = ['acPower', 'acCommandMode', 'acSetpoint', 'acFan', 'acSwingVertical', 'acSwingHorizontal'];
  function plain(value, fallback = '—') { return typeof value === 'string' && value ? value.slice(0, 4000) : fallback; }
  function reasonText(value) {
    return plain(value).split(';').map(code => {
      const match = code.match(/^temperature_(below_band_for_([0-9]{2,3})_seconds|above_band_for_([0-9]{2,3})_seconds_covered)$/);
      const seconds = match ? Number(match[2] || match[3]) : NaN;
      if (seconds >= 30 && seconds <= 600) return match[2] ? `低温持续 ${seconds} 秒且满足独立样本条件` : `正常盖被且高温持续 ${seconds} 秒，满足独立样本条件`;
      return REASONS[code] || code;
    }).join('；');
  }
  function number(value, low, high, label, integer = false) {
    if ((typeof value !== 'string' && typeof value !== 'number') || (typeof value === 'string' && !value.trim())) throw new Error(`${label}须填写数字`);
    const parsed = Number(value);
    if (!Number.isFinite(parsed) || parsed < low || parsed > high || (integer && !Number.isInteger(parsed))) throw new Error(`${label}须在 ${low}–${high} 之间${integer ? '且为整数' : ''}`);
    return parsed;
  }
  function state(values) {
    const s = values && typeof values === 'object' ? values : {};
    if (!['on', 'off'].includes(s.power)) throw new Error('请选择开机或关机状态。');
    if (!Object.hasOwn(MODES, s.mode)) throw new Error('请选择支持的运行模式。');
    if (!Object.hasOwn(FANS, s.fan)) throw new Error('请选择支持的风速。');
    for (const key of ['swing_vertical', 'swing_horizontal']) if (!['on', 'off'].includes(s[key])) throw new Error('请选择上下 / 左右摆风状态。');
    const setpoint = number(s.setpoint_c, 16, 30, '设定温度', true);
    if (s.mode === 'auto' && setpoint !== 25) throw new Error('此协议的自动模式温度固定为 25 °C，请核对预览后发送。');
    if (s.mode === 'dry' && s.fan !== 'low') throw new Error('此协议的除湿模式风速固定为低风，请核对预览后发送。');
    return {power: s.power, mode: s.mode, setpoint_c: setpoint, fan: s.fan,
      swing_vertical: s.swing_vertical, swing_horizontal: s.swing_horizontal};
  }
  function stateText(value) {
    try {
      const s = state(value);
      return `${s.power === 'on' ? '开机' : '关机'} · ${MODES[s.mode]} · ${s.setpoint_c} °C · ${FANS[s.fan]} · 上下摆风${s.swing_vertical === 'on' ? '开' : '关'} · 左右摆风${s.swing_horizontal === 'on' ? '开' : '关'}`;
    } catch (_) { return '状态未知'; }
  }
  function outcomeText(value) {
    if (!value || typeof value !== 'object') return '尚无本地发射回执';
    if (value.ac_acknowledged === true) return '响应异常：本地回执不能确认实际空调执行';
    if (value.status === 'emitted_local_only' && value.emitted === true) return `已发出本地红外：${stateText(value.state)}；实际空调响应未确认${value.duplicate === true ? '；重复命令未再次发射' : ''}`;
    if (value.status === 'outcome_unknown_no_retry' || value.emitted === null) return '发射结果未知，可能已经发出；不自动重试，需核对后重新操作';
    if (value.status === 'held' || value.emitted === false) return `未发射：${reasonText(value.reason || value.status)}`;
    return `${plain(value.status)}；实际空调响应未确认`;
  }
  function snapshotState(data) { return data?.state || data?.last_commanded_state || data?.assumed_state || null; }
  function snapshotAutomatic(data) { return data?.automatic || data?.automatic_sleep_control || {}; }
  function coverageEventText(event) {
    if (!event || typeof event !== 'object') return '尚无事件记录；不能据此认定已经恢复';
    const labels = {
      normal: '正常覆盖监测 · 尚无活动未盖被事件',
      candidate: '未盖被待确认 · 尚未完成持续性核对',
      confirmed: '持续未盖被已确认 · 仍需通过温度和控制门控',
      handled: '本事件已调节 · 不重复保护性升温（空调执行未确认）',
      recovering: '恢复观察中 · 事件锁尚未解除',
      recovered: '稳定恢复已确认 · 本次未盖被事件结束',
      awaiting_reliable_visual: '等待可靠画面 · 不确定状态不会解除事件锁'
    };
    if (!Object.hasOwn(labels, event.phase)) return '事件状态未知 · 请检查服务状态，不视为已经恢复';
    let detail = labels[event.phase];
    if (event.phase === 'recovering') {
      const count = event.recovery?.count;
      detail += ` · 同类可靠观察 ${Number.isInteger(count) && count >= 0 ? count : '—'} 次；需至少 2 次、跨度 60 秒`;
    }
    if (event.handled === true && event.phase !== 'handled') detail += ' · 本事件已处理锁仍在';
    return detail;
  }
  function view(snapshot, automaticAiEnabled = false) {
    const data = snapshot && typeof snapshot === 'object' ? snapshot : null;
    const current = snapshotState(data), automatic = snapshotAutomatic(data);
    let known = false;
    try { state(current); known = ['user_assumption', 'last_local_emission'].includes(data?.state_source); } catch (_) {}
    const enabled = data?.enabled === true;
    const sleepEnabled = automatic.enabled === true;
    const manualRemaining = data?.manual_cooldown_remaining_seconds;
    const automaticRemaining = automatic.cooldown_remaining_seconds;
    return {supported: Boolean(data), enabled, automaticEnabled: sleepEnabled,
      canEnable: Boolean(data) && !enabled,
      canManual: Boolean(data) && enabled,
      canAutomatic: Boolean(data) && enabled && !sleepEnabled && automaticAiEnabled === true && known && current?.power === 'on' && current?.mode === 'cool',
      badge: enabled ? '实验空调控制已启用' : '实验空调控制已禁用',
      state: current, stateKnown: known,
      stateText: `${stateText(current)} · ${known ? '最后命令 / 用户假设' : '仅保留上次状态，当前未知'}，不是空调实况`,
      reason: reasonText(data?.reason),
      automaticBadge: sleepEnabled ? '自动睡眠控制已开启' : '自动睡眠控制已暂停',
      automaticReason: reasonText(automatic.reason || 'user_paused'),
      coverageEvent: coverageEventText(automatic.coverage_event),
      cooldown: `手动 ${Number.isFinite(manualRemaining) && manualRemaining >= 0 ? Math.ceil(manualRemaining) : '—'} 秒 / 自动 ${Number.isFinite(automaticRemaining) && automaticRemaining >= 0 ? Math.ceil(automaticRemaining) : '—'} 秒`,
      outcome: outcomeText(data?.last_result), automatic,
      receipt: data?.last_result ? `请求 ${plain(data.last_result.request_id || data.last_result.command_id)} · ${plain(data.last_result.created_at)}` : '尚无发射记录'};
  }
  function configPayload(enabled, values, consent) {
    if (!enabled) return {enabled: false};
    if (consent !== true) throw new Error('请先同意实验空调控制，并确认本地发射不代表空调执行成功。');
    return {enabled: true, control_consent: true, initial_state: state(values)};
  }
  function manualPayload(values, requestId) {
    if (typeof requestId !== 'string' || !/^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i.test(requestId)) throw new Error('无法生成有效请求编号，请刷新页面。');
    return {request_id: requestId, state: state(values)};
  }
  function automaticPayload(enabled, values, snapshot, automaticAiEnabled) {
    if (!enabled) return {enabled: false};
    if (!view(snapshot, automaticAiEnabled).canAutomatic) throw new Error('自动控制需要已启用空调控制、自动 AI，以及已知的开机制冷假设 / 最近命令。');
    const min = number(values.min, 16, 30, '自动设定温度下界', true);
    const max = number(values.max, 16, 30, '自动设定温度上界', true);
    if (min > max) throw new Error('自动设定温度下界不能高于上界。');
    const current = snapshotState(snapshot);
    if (current.setpoint_c < min || current.setpoint_c > max) throw new Error('最后命令 / 初始假设的设定温度须位于允许的自动范围内。');
    const lower = number(values.lower, 18, 32, '环境温度下限'), upper = number(values.upper, 18, 32, '环境温度上限');
    if (upper - lower < 2) throw new Error('环境温度上限须至少比下限高 2 °C。');
    // Bounds are display-only; the server owns the saved global policy.
    return {enabled: true};
  }
  function requestId() {
    if (root.crypto?.randomUUID) return root.crypto.randomUUID();
    return 'xxxxxxxx-xxxx-4xxx-yxxx-xxxxxxxxxxxx'.replace(/[xy]/g, character => {
      const random = Math.floor(Math.random() * 16);
      return (character === 'x' ? random : (random & 3) | 8).toString(16);
    });
  }
  function createController(doc, options) {
    const element = id => doc.getElementById(id);
    const text = (id, value) => { const node = element(id); if (node && node.textContent !== value) node.textContent = value; };
    function setDisabled(id, value) {
      const node = element(id);
      if (node.disabled !== value) node.disabled = value;
    }
    let lastStatus = null, initialized = false, busy = false;
    function values() { return {power: element('acPower').value, mode: element('acCommandMode').value,
      setpoint_c: element('acSetpoint').value, fan: element('acFan').value,
      swing_vertical: element('acSwingVertical').value, swing_horizontal: element('acSwingHorizontal').value}; }
    function bands() { return {min: element('acAutomaticMin').value, max: element('acAutomaticMax').value,
      lower: element('acAmbientLower').value, upper: element('acAmbientUpper').value}; }
    function buttons() {
      const data = view(lastStatus?.ac_control, lastStatus?.automatic_ai?.enabled);
      let valid = false;
      try { state(values()); valid = true; } catch (_) {}
      setDisabled('enableAcControl', busy || !data.canEnable || !valid || element('acControlConsent').checked !== true);
      setDisabled('disableAcControl', busy || !data.enabled);
      setDisabled('sendAcManual', busy || !data.canManual || !valid);
      setDisabled('enableAcAutomatic', busy || !data.canAutomatic);
      setDisabled('pauseAcAutomatic', busy || !data.automaticEnabled);
      setDisabled('acControlConsent', busy || data.enabled);
      const mode = element('acCommandMode').value;
      for (const id of FIELD_IDS) setDisabled(id, busy || (id === 'acSetpoint' && mode === 'auto') || (id === 'acFan' && mode === 'dry'));
      for (const id of ['acAutomaticMin', 'acAutomaticMax', 'acAmbientLower', 'acAmbientUpper']) {
        if (!element(id).readOnly) element(id).readOnly = true;
        setDisabled(id, true);
      }
    }
    function preview() {
      try {
        const s = state(values());
        text('acCommandPreview', `将发送：${stateText(s)}。请核对后点击发送；实际空调响应未确认。`);
        text('sendAcManual', `发送${s.power === 'on' ? '开机' : '关机'} · ${MODES[s.mode]}状态`);
      } catch (error) { text('acCommandPreview', `状态无效：${plain(error?.message)}`); }
      buttons();
    }
    function constraints() {
      const mode = element('acCommandMode').value;
      if (mode === 'auto') element('acSetpoint').value = 25;
      if (mode === 'dry') element('acFan').value = 'low';
      text('acProtocolConstraint', mode === 'auto' ? '协议约束：自动模式固定为 25 °C，温度不可编辑；预览已明确显示该值。'
        : mode === 'dry' ? '协议约束：除湿模式固定低风，风速不可编辑；预览已明确显示该值。'
        : '基本 128 位状态：仅所选开关、模式、温度、风速和摆风；不含定时、静音、强劲或 iFeel。');
      preview();
    }
    function render(status) {
      lastStatus = status;
      const data = view(status?.ac_control, status?.automatic_ai?.enabled);
      if (!initialized && data.supported) {
        if (data.stateKnown) for (const [id, key] of FIELD_IDS.map((id, index) => [id, ['power', 'mode', 'setpoint_c', 'fan', 'swing_vertical', 'swing_horizontal'][index]])) element(id).value = data.state[key];
        element('acAutomaticMin').value = data.automatic.min_setpoint_c ?? 24;
        element('acAutomaticMax').value = data.automatic.max_setpoint_c ?? 28;
        element('acAmbientLower').value = data.automatic.temperature_lower_c ?? 24;
        element('acAmbientUpper').value = data.automatic.temperature_upper_c ?? 28;
        initialized = true;
      }
      text('acControlBadge', data.supported ? data.badge : '本地服务器尚未支持完整控制');
      text('acControlState', data.stateText);
      text('acControlStatus', data.reason);
      text('acAutomaticBadge', data.automaticBadge);
      text('acAutomaticStatus', data.automaticReason);
      text('acCoverageEvent', data.coverageEvent);
      text('acControlCooldown', data.cooldown);
      text('acControlLastOutcome', data.outcome);
      text('acControlLastReceipt', data.receipt);
      constraints();
    }
    function setUnavailable(message) {
      lastStatus = null;
      text('acControlBadge', '控制状态未知');
      text('acControlStatus', `无法连接本地服务器：${plain(message)}；旧回执不能代表当前状态。不会自动重试发射。`);
      text('acAutomaticBadge', '自动控制状态未知');
      text('acCoverageEvent', '事件状态未知 · 等待服务恢复，不能依据旧记录重新调节');
      buttons();
    }
    async function update(kind, enabled = true) {
      if (busy) return;
      busy = true; buttons(); text('acControlFeedback', '正在提交…');
      try {
        if (!lastStatus?.ac_control) throw new Error('请先恢复本地服务连接并读取控制状态。');
        let path, payload;
        if (kind === 'config') {
          if (enabled && lastStatus.ac_control.enabled === true) throw new Error('请先禁用控制，再更改初始假设。');
          path = '/api/ac-control/config'; payload = configPayload(enabled, values(), element('acControlConsent').checked);
        } else if (kind === 'manual') {
          if (lastStatus.ac_control.enabled !== true) throw new Error('请先明确启用空调控制。');
          path = '/api/ac-control/manual'; payload = manualPayload(values(), options.newRequestId ? options.newRequestId() : requestId());
        } else if (kind === 'automatic') {
          path = '/api/ac-control/automatic'; payload = automaticPayload(enabled, bands(), lastStatus.ac_control, lastStatus.automatic_ai?.enabled);
        } else throw new Error('未知控制操作。');
        const response = await options.api(path, {method: 'POST', headers: {'Content-Type': 'application/json'}, body: JSON.stringify(payload)});
        if (kind === 'config' && !enabled) element('acControlConsent').checked = false;
        text('acControlFeedback', kind === 'manual' ? '手动请求已提交，自动睡眠控制已暂停，不会自行恢复。请查看本地回执，空调响应未确认。'
          : kind === 'automatic' ? (enabled ? '自动睡眠控制已开启，按设置页已保存策略及允许温度范围判断。' : '自动睡眠控制已暂停。')
          : enabled ? '控制已启用，初始状态仅作为用户假设；尚未发送任何手动命令。' : '控制已禁用；这不会向空调发送关机指令。');
        if (options.onUpdated) await options.onUpdated(response);
        else render(response.ac_control ? {...lastStatus, ...response} : {...lastStatus, ac_control: response});
      } catch (error) {
        text('acControlFeedback', `操作失败：${plain(error?.message)}${kind === 'manual' ? '；结果可能未知，不会自动重试。' : ''}`);
      } finally { busy = false; buttons(); }
    }
    for (const id of FIELD_IDS) element(id).addEventListener('change', id === 'acCommandMode' ? constraints : preview);
    element('acSetpoint').addEventListener('input', preview);
    element('acControlConsent').addEventListener('change', buttons);
    element('enableAcControl').addEventListener('click', () => { void update('config', true); });
    element('disableAcControl').addEventListener('click', () => { void update('config', false); });
    element('sendAcManual').addEventListener('click', () => { void update('manual'); });
    element('enableAcAutomatic').addEventListener('click', () => { void update('automatic', true); });
    element('pauseAcAutomatic').addEventListener('click', () => { void update('automatic', false); });
    constraints();
    return {render, update, preview, setUnavailable};
  }
  root.DreamShieldAcControl = {state, stateText, outcomeText, coverageEventText, view, configPayload, manualPayload, automaticPayload, createController};
})(globalThis);
