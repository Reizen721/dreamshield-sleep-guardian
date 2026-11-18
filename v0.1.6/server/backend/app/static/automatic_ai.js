// Display and explicit opt-in controls only. Loading this file never enables AI.
(function (root) {
  'use strict';
  const STATE_LABELS = {
    disabled: '已关闭', off: '已关闭', waiting_baseline: '等待首张定时图片作为基线',
    waiting_frame: '等待下一张定时图片', waiting_capture: '等待下一张定时图片',
    idle: '等待定时图片', ready: '等待定时图片', analyzing: '前后图分析中',
    queued: '等待模型分析', comparing: '前后图分析中',
    waiting_confirmation: '等待确认帧', confirming: '确认帧分析中',
    completed: '本次判决已完成', finished: '本次判决已完成',
    pair_completed: '前后图判决已完成', confirmation_completed: '确认帧判决已完成',
    cooldown: '调用间隔内，等待下一次分析', backoff: '调用失败，退避等待',
    paused_budget: '调用预算已用完，暂停上传', budget_exhausted: '调用预算已用完，暂停上传',
    error: '任务失败', failed: '任务失败', cancelled: '任务已取消',
    stopped: '已停止', no_candidate: '未发现覆盖减少候选',
    confirmation_pending: '需要有效确认帧', confirmed: '未覆盖状态已持续确认',
    recovered: '确认帧显示恢复覆盖', not_required: '无需追加确认',
    candidate: '覆盖显著减少候选', suspected_blanket_kicking: '疑似踢被事件（覆盖显著减少）',
    significant_coverage_reduction_observed: '覆盖显著减少',
    significant_coverage_reduction_candidate: '覆盖显著减少候选',
    coverage_reduction_below_candidate_threshold: '覆盖减少未达候选门槛',
    no_significant_coverage_change: '未见显著覆盖变化',
    indeterminate: '证据不足，需复核', inconclusive: '证据不足，需复核', no_person: '未见人物',
    covered: '当前保持覆盖', empty: '当前床上无人', uncovered_candidate: '当前未覆盖 · 等待持续确认',
    confirmed_uncovered: '未覆盖已持续确认', uncertain: '当前画面不确定 · 保持不发射'
  };
  const REASON_LABELS = {
    not_enabled: '尚未开启', baseline_required: '需要建立新基线', baseline_saved: '首张定时图已建立基线，等待后图',
    device_or_boot_changed: '设备或重启编号变化，已重新建立基线', capture_not_newer: '忽略非递增时间的图片',
    frame_gap_too_large: '前后帧间隔过大，重新建立基线', worker_busy_frame_not_queued: 'AI 正忙，新抓拍不会积压为分析队列',
    failure_backoff: '最近调用失败，暂缓再次上传', session_budget_exhausted: '本次服务器运行调用预算不足',
    hourly_budget_exhausted: '每小时调用预算不足', call_budget_exhausted: '模型调用预算不足',
    minimum_interval: '尚未到最短对比间隔', image_device_identity_missing: '图片缺少设备或重启编号，请更新摄像头固件',
    capture_device_url_mismatch: '抓拍设备地址不一致', capture_timestamp_invalid: '抓拍时间无效',
    capture_timestamp_in_future: '抓拍时间异常，请检查电脑时钟', capture_file_reference_missing: '缺少保存图片路径',
    server_stopped: '服务器已停止', user_stopped: '用户已停止自动 AI',
    device_changed: '设备地址改变，自动 AI 已停止；请确认新设备后重新开启',
    schedule_disabled: '定时抓拍已关闭，自动 AI 同时停止',
    capture_interval_too_large: '抓拍间隔过长，自动 AI 已停止'
  };
  function plain(value, fallback = '—') {
    return typeof value === 'string' && value ? value.slice(0, 4000) : fallback;
  }
  function stateText(value) { return STATE_LABELS[value] || plain(value); }
  function reasonText(value) { return plain(value?.message || value?.code || value); }
  function percent(value) {
    return Number.isFinite(value) && value >= 0 && value <= 1 ? `${Math.round(value * 100)}%` : '—';
  }
  function timeText(value) {
    if (!value || (typeof value !== 'string' && !Number.isFinite(value))) return '—';
    const date = new Date(value);
    return Number.isFinite(date.getTime()) ? date.toLocaleString() : '—';
  }
  function safeLocalUrl(value, kind) {
    if (typeof value !== 'string' || !value.startsWith('/') || value.startsWith('//')) return null;
    let decoded;
    try { decoded = decodeURIComponent(value); } catch (_) { return null; }
    if (decoded.includes('\\') || /[\u0000-\u0020?#]/.test(decoded) || decoded.split('/').includes('..')) return null;
    const prefix = kind === 'image' ? '/captures/' : '/automatic-ai-results/';
    const suffix = kind === 'image' ? /\.jpg$/i : /\.json$/i;
    return decoded.startsWith(prefix) && suffix.test(decoded) && /^\/[A-Za-z0-9_\-/.]+$/.test(decoded) ? value : null;
  }
  function view(automatic, keyConfigured = false, now = Date.now()) {
    const a = automatic && typeof automatic === 'object' ? automatic : {};
    const enabled = a.enabled === true;
    const record = a.last_record && typeof a.last_record === 'object' ? a.last_record : null;
    const result = record?.result && typeof record.result === 'object' ? record.result : {};
    const assessment = result.assessment && typeof result.assessment === 'object' ? result.assessment : {};
    const environment = assessment.environment_analysis && typeof assessment.environment_analysis === 'object' ? assessment.environment_analysis : {};
    const control = assessment.control_decision && typeof assessment.control_decision === 'object' ? assessment.control_decision : {};
    const sensorOrigin = environment.input_origin === 'esp32_telemetry' && environment.sensor_model === 'sht31';
    const sensorValid = sensorOrigin && environment.data_status === 'valid';
    const fallback = sensorOrigin && ['last_valid', 'fallback_expired'].includes(environment.data_status) && environment.source === 'last_valid';
    const sensorDisplayable = sensorValid || fallback;
    const temperature = sensorDisplayable && Number.isFinite(environment.ambient_temperature_c) ? `${environment.ambient_temperature_c.toFixed(2)} °C` : '—';
    const humidity = sensorDisplayable && Number.isFinite(environment.relative_humidity_percent) ? `${environment.relative_humidity_percent.toFixed(2)} %RH` : '—';
    const readingAge = environment.age_seconds ?? environment.measurement_age_seconds;
    const fallbackAge = Number.isFinite(readingAge) && readingAge >= 0 ? `${Math.ceil(readingAge)} 秒` : '未知';
    const environmentText = fallback
      ? `真实 SHT31 · 最后有效缓存（不是当前实测） · 原采样 ${timeText(environment.original_measured_at || environment.measured_at)} · 年龄 ${fallbackAge} · 故障 ${plain(environment.fault_reason || environment.collection_error)} · ${environment.data_status === 'fallback_expired' ? '已超过 300 秒，仅显示、不供新控制' : '最多沿用 300 秒'}；不形成趋势。`
      : sensorValid ? `真实 SHT31 · 本次判决历史读数 · 采样时间 ${timeText(environment.measured_at)} · 判决时年龄 ${fallbackAge}（不是当前实时值）`
      : `本次环境数据不可用或不足：${plain(environment.data_status, '尚无数据')}。不会从图片估计温湿度。`;
    const liveVisual = assessment.live_visual && typeof assessment.live_visual === 'object' ? assessment.live_visual : {};
    const completeControl = record?.ac_control && typeof record.ac_control === 'object' ? record.ac_control : null;
    const emission = completeControl;
    const emissionOutcome = root.DreamShieldAcControl?.outcomeText(emission)
      || (emission ? `实验发射状态：${plain(emission.status)}；实际空调响应未确认` : '本次尚无实验发射记录');
    const next = Number.isFinite(a.next_analysis_epoch_ms) && a.next_analysis_epoch_ms > now
      ? `；最早下次对比：${timeText(a.next_analysis_epoch_ms)}` : '';
    const errors = [a.last_error, record?.error].filter(value => typeof value === 'string' && value);
    const unsafe = control.would_execute === true || control.execution_allowed === true ||
      (Array.isArray(control.proposed_actions) && control.proposed_actions.some(action => action?.would_execute === true));
    const actions = Array.isArray(control.proposed_actions) ? control.proposed_actions : [];
    const blockers = Array.isArray(control.blockers) ? control.blockers : [];
    const pending = Array.isArray(control.pending_requirements) ? control.pending_requirements : [];
    return {
      supported: Boolean(automatic), enabled, canEnable: Boolean(automatic) && keyConfigured === true && !enabled,
      badge: enabled ? (['paused_budget', 'budget_exhausted'].includes(a.state) ? '预算暂停 · 不上传' : '运行中 · 自动判决') : '已关闭 · 不上传',
      status: `${enabled ? stateText(a.state) : '自动 AI 已关闭'}${next}${a.skip_reason ? `；${REASON_LABELS[a.skip_reason] || plain(a.skip_reason)}` : ''}`,
      budget: `模型调用：本小时 ${a.calls_this_hour ?? 0}/${a.max_calls_per_hour ?? 20}，本次服务器运行 ${a.calls_this_session ?? 0}/${a.max_calls_per_session ?? 50}（含确认与失败；开关不重置计数）`,
      error: errors.length ? errors.map(value => plain(value)).join('；') : '',
      event: stateText(liveVisual.state || assessment.validated_visual_decision || assessment.visual_event),
      confidence: liveVisual.state === 'uncertain' ? '证据不足' : percent(liveVisual.state_evidence_score ?? assessment.visual_comparison_confidence),
      confirmation: liveVisual.state ? (liveVisual.confirmation_required ? '未覆盖待确认' : stateText(liveVisual.state))
        : stateText(assessment.blanket_event_state || assessment.confirmation_state),
      temperature, humidity,
      environment: environmentText,
      execution: unsafe ? '响应异常：出现执行许可，停止并检查'
        : emission?.status === 'emitted_local_only' && emission.emitted === true ? '已发出实验红外 · 空调响应未确认'
        : emission?.status === 'outcome_unknown_no_retry' ? '发射结果未知 · 不自动重试'
        : '否 · dry_run · execute=false',
      irOutcome: emission ? emissionOutcome : '本次尚无实验发射记录；默认模拟。',
      control: liveVisual.state ? '统一策略 v0.3：仅开机制冷，按已保存策略调节（默认 ±1 °C、300 秒冷却）；同一次未盖被只调节一次；湿度不切换模式。本地红外回执不代表空调实况。'
        : actions.length
        ? `模拟建议（不会执行）：${actions.map(action => plain(action?.action || action?.type || action?.command, '建议动作')).join('；')}`
        : `无可执行动作${blockers.length || pending.length ? `；${[...blockers, ...pending].map(reasonText).join('；')}` : '；仅记录判决'}`,
      meta: record ? `${stateText(record.status)} · ${plain(result.provider, '当前提供商')} / ${plain(result.model, '当前模型')} · 任务 ${plain(record.job_id)}` : '尚无自动任务。首张定时图片只建立基线，不调用模型。',
      record
    };
  }
  function integer(value, minimum, maximum, label) {
    const number = typeof value === 'number' ? value : Number(value);
    if (!Number.isInteger(number) || number < minimum || number > maximum) throw new Error(`${label}须为 ${minimum}–${maximum} 的整数`);
    return number;
  }
  function enablePayload(values, keyConfigured) {
    if (values.consent !== true) throw new Error('请先勾选同意图片上传与 API 费用。');
    if (keyConfigured !== true) throw new Error('请先用带 VLM 密钥的启动方式运行本地服务器。');
    return {
      enabled: true, cloud_upload_consent: true,
      min_interval_seconds: integer(values.interval, 60, 3600, 'AI 对比间隔'),
      max_calls_per_hour: integer(values.hourly, 2, 120, '每小时调用上限'),
      max_calls_per_session: integer(values.session, 2, 500, '本次调用上限')
    };
  }
  function createController(doc, options) {
    const element = id => doc.getElementById(id);
    let lastStatus = null, initialized = false, busy = false, historyBusy = false, historyAt = -Infinity;
    let historySignature = null;
    const resultDetails = element('automaticAiResultJson').closest?.('details');
    function renderRecord() {
      if (resultDetails && !resultDetails.open) return;
      const record = lastStatus?.automatic_ai?.last_record;
      text('automaticAiResultJson', record ? JSON.stringify(record, null, 2).slice(0, 60000) : '尚无记录');
    }
    resultDetails?.addEventListener('toggle', renderRecord);
    function text(id, value) { const node = element(id); if (node && node.textContent !== value) node.textContent = value; }
    function toggleDisabled(id, value) {
      const node = element(id);
      if (node.disabled !== value) node.disabled = value;
    }
    function setLink(node, attribute, url) {
      if (node.hidden !== !url) node.hidden = !url;
      const old = node.getAttribute ? node.getAttribute(attribute) : node[attribute];
      if (url && old !== url) node[attribute] = url;
      if (!url && old) node.removeAttribute(attribute);
    }
    function buttons() {
      const data = view(lastStatus?.automatic_ai, lastStatus?.vlm_key_configured);
      toggleDisabled('enableAutomaticAi', busy || !data.canEnable || element('automaticAiConsent').checked !== true);
      toggleDisabled('stopAutomaticAi', busy || !data.enabled);
    }
    function image(slot, frame) {
      const node = element(`automaticAi${slot}Image`);
      const url = safeLocalUrl(frame?.url, 'image');
      setLink(node, 'src', url);
      const labels = {Before: '前图', After: '后图', Confirmation: '确认帧'};
      text(`automaticAi${slot}Caption`, `${labels[slot]}：${frame ? timeText(frame.captured_at) : '—'}`);
    }
    function render(status) {
      lastStatus = status;
      const automatic = status?.automatic_ai;
      if (!initialized && automatic) {
        element('automaticAiInterval').value = automatic.min_interval_seconds ?? 60;
        element('automaticAiHourlyLimit').value = automatic.max_calls_per_hour ?? 20;
        element('automaticAiSessionLimit').value = automatic.max_calls_per_session ?? 50;
        initialized = true;
      }
      const data = view(automatic, status?.vlm_key_configured);
      text('automaticAiBadge', data.badge);
      text('automaticAiStatus', !data.supported ? '服务器尚未支持自动 AI，请重启更新后的服务器。' : data.status);
      text('automaticAiBudget', data.budget);
      text('automaticAiResultMeta', data.error ? `${data.meta}；错误：${data.error}` : data.meta);
      for (const [id, value] of [
        ['automaticAiVisualEvent', data.event], ['automaticAiConfidence', data.confidence],
        ['automaticAiConfirmationState', data.confirmation], ['automaticAiTemperature', data.temperature],
        ['automaticAiHumidity', data.humidity], ['automaticAiExecution', data.execution],
        ['automaticAiEnvironmentStatus', data.environment], ['automaticAiControlSummary', data.control],
        ['automaticAiIrOutcome', data.irOutcome]
      ]) text(id, value);
      image('Before', data.record?.before); image('After', data.record?.after); image('Confirmation', data.record?.confirmation);
      const link = element('automaticAiResultLink'), url = safeLocalUrl(data.record?.result_url, 'result');
      setLink(link, 'href', url);
      renderRecord();
      buttons();
      if (data.supported && Date.now() - historyAt >= 15000) void loadHistory();
    }
    function setUnavailable(message) {
      lastStatus = null;
      text('automaticAiBadge', '服务状态未知');
      text('automaticAiStatus', `无法连接本地服务器：${plain(message)}。旧判决仅供查看，不能代表当前运行状态。`);
      buttons();
    }
    async function loadHistory() {
      if (options.shouldLoadHistory && !options.shouldLoadHistory()) return;
      if (historyBusy) return;
      historyBusy = true; historyAt = Date.now();
      const container = element('automaticAiHistory');
      try {
        const response = await options.api('/api/automatic-ai/history');
        const records = Array.isArray(response?.items) ? response.items.slice(0, 20) : [];
        const signature = JSON.stringify(records.map(record => [record.after?.captured_at, record.status, record.job_id, record.error, record.result_url]));
        if (signature === historySignature) return;
        container.replaceChildren();
        if (!records.length) { const node = doc.createElement('li'); node.textContent = '尚无自动任务。'; container.append(node); }
        for (const record of records) {
          const node = doc.createElement('li');
          node.textContent = `${timeText(record.after?.captured_at)} · ${stateText(record.status)} · ${plain(record.job_id)}${record.error ? ` · ${plain(record.error)}` : ''}`;
          const url = safeLocalUrl(record.result_url, 'result');
          if (url) { const link = doc.createElement('a'); link.href = url; link.target = '_blank'; link.rel = 'noopener noreferrer'; link.textContent = 'JSON'; node.append(link); }
          container.append(node);
        }
        historySignature = signature;
      } catch (error) {
        historySignature = null;
        container.replaceChildren(); const node = doc.createElement('li'); node.textContent = `记录读取失败：${plain(error?.message)}`; container.append(node);
      } finally { historyBusy = false; }
    }
    async function update(enabled) {
      if (busy) return;
      busy = true; buttons(); text('automaticAiFeedback', enabled ? '正在开启…' : '正在停止…');
      try {
        let payload = {enabled: false};
        if (enabled) {
          payload = enablePayload({consent: element('automaticAiConsent').checked,
            interval: element('automaticAiInterval').value, hourly: element('automaticAiHourlyLimit').value,
            session: element('automaticAiSessionLimit').value}, lastStatus?.vlm_key_configured);
          if (options.beforeEnable) await options.beforeEnable();
        }
        const response = await options.api('/api/automatic-ai', {method: 'POST', headers: {'Content-Type': 'application/json'}, body: JSON.stringify(payload)});
        if (!enabled) element('automaticAiConsent').checked = false;
        text('automaticAiFeedback', enabled ? '已开启；首张定时抓拍建立基线，之后按预算分析。' : '已停止新任务；已发送的模型请求无法撤回。定时抓拍和温湿度记录仍继续。');
        if (options.onUpdated) await options.onUpdated(response);
        else render(response);
        await loadHistory();
      } catch (error) { text('automaticAiFeedback', `操作失败：${plain(error?.message)}`); }
      finally { busy = false; buttons(); }
    }
    element('automaticAiConsent').addEventListener('change', buttons);
    element('enableAutomaticAi').addEventListener('click', () => { void update(true); });
    element('stopAutomaticAi').addEventListener('click', () => { void update(false); });
    element('refreshAutomaticAiHistory').addEventListener('click', () => { void loadHistory(); });
    buttons();
    return {render, loadHistory, update, setUnavailable};
  }
  root.DreamShieldAutomaticAi = {view, safeLocalUrl, enablePayload, createController};
})(globalThis);
