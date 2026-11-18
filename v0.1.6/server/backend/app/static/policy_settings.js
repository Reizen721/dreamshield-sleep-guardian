// Explicit policy configuration. Loading, editing and resetting never emit IR.
(function (root) {
  'use strict';
  const DEFAULTS = Object.freeze({min_setpoint_c: 24, max_setpoint_c: 28,
    temperature_lower_c: 24, temperature_upper_c: 28, environment_persistence_seconds: 45,
    temperature_step_c: 1, command_cooldown_seconds: 300, confirmation_target_seconds: 20});
  const LIMITS = {min_setpoint_c: [16, 30], max_setpoint_c: [16, 30], temperature_lower_c: [18, 32],
    temperature_upper_c: [18, 32], environment_persistence_seconds: [30, 600], temperature_step_c: [1, 2],
    command_cooldown_seconds: [60, 3600], confirmation_target_seconds: [20, 50]};
  const FIELDS = {min_setpoint_c: 'policyMinSetpoint', max_setpoint_c: 'policyMaxSetpoint',
    temperature_lower_c: 'policyAmbientLower', temperature_upper_c: 'policyAmbientUpper',
    environment_persistence_seconds: 'policyPersistence', temperature_step_c: 'policyStep',
    command_cooldown_seconds: 'policyCooldown', confirmation_target_seconds: 'policyConfirmation'};
  const MODES = {light: 'policyPresetLight', balanced: 'policyPresetBalanced',
    efficient: 'policyPresetEfficient', custom: 'policyPresetCustom'};
  const PRESETS = Object.freeze({
    light: {label: '轻享', description: '耐心观察，较少调节；适合希望减少干预时使用。',
      policy: {...DEFAULTS, temperature_lower_c: 23, temperature_upper_c: 29,
        environment_persistence_seconds: 120, command_cooldown_seconds: 600, confirmation_target_seconds: 40},
      environment_interval_seconds: 30, sensor_sample_target_seconds: 30},
    balanced: {label: '均衡', description: '兼顾响应速度与调节频率，适合先按默认值体验。',
      policy: {...DEFAULTS}, environment_interval_seconds: 15, sensor_sample_target_seconds: 15},
    efficient: {label: '高效', description: '更早响应室温变化，更频繁观察；每次仍只调整 1 °C。',
      policy: {...DEFAULTS, temperature_lower_c: 25, temperature_upper_c: 27,
        environment_persistence_seconds: 30, command_cooldown_seconds: 180},
      environment_interval_seconds: 10, sensor_sample_target_seconds: 10}
  });
  const plain = value => typeof value === 'string' ? value.slice(0, 1000) : '未知错误';
  function number(value, min, max, label, integer = true) {
    if (!['number', 'string'].includes(typeof value) || (typeof value === 'string' && !value.trim())) throw new Error(`${label}需要填写数字`);
    const result = Number(value);
    if (!Number.isFinite(result) || result < min || result > max || (integer && !Number.isInteger(result))) throw new Error(`${label}须为 ${min}–${max}${integer ? ' 的整数' : ' 范围内的数字'}`);
    return result;
  }
  function policy(value) {
    if (!value || typeof value !== 'object') throw new Error('服务器未返回有效策略参数');
    const result = {};
    for (const [key, limits] of Object.entries(LIMITS)) result[key] = number(value[key], ...limits, key, !['temperature_lower_c', 'temperature_upper_c'].includes(key));
    if (result.min_setpoint_c > result.max_setpoint_c) throw new Error('允许设定温度下界不能高于上界');
    if (result.temperature_upper_c - result.temperature_lower_c < 2) throw new Error('室温触发上下限至少相差 2 °C');
    return result;
  }
  function createController(doc, options) {
    const element = id => doc.getElementById(id);
    const text = (id, value) => { if (element(id)) element(id).textContent = value; };
    let loaded = false, busy = false, sensorConfirmed = null;
    let selected = 'balanced', presets = PRESETS, customDraft = null;
    function buttons() {
      for (const id of ['savePolicySettings', 'applySensorSettings']) element(id).disabled = busy || !loaded;
      for (const id of ['loadPolicySettings', 'resetPolicySettings']) element(id).disabled = busy;
      for (const [name, id] of Object.entries(MODES)) {
        element(id).checked = name === selected;
        element(id).disabled = busy || !loaded;
      }
      for (const id of [...Object.values(FIELDS), 'policyPollInterval', 'sensorSampleInterval']) {
        element(id).readOnly = busy || !loaded || selected !== 'custom';
      }
      text('policyCustomHint', selected === 'custom'
        ? '自定义已开启，可以逐项调参；保存后生效。'
        : '预设参数仅供查看；选择“自定义”可逐项修改。');
    }
    function values() { return Object.fromEntries(Object.entries(FIELDS).map(([key, id]) => [key, element(id).value])); }
    function fill(value) { for (const [key, id] of Object.entries(FIELDS)) element(id).value = String(value[key]); }
    function bundle() {
      return {policy: values(), environment_interval_seconds: element('policyPollInterval').value,
        sensor_sample_target_seconds: element('sensorSampleInterval').value};
    }
    function fillBundle(value) {
      fill(value.policy);
      element('policyPollInterval').value = String(value.environment_interval_seconds);
      element('sensorSampleInterval').value = String(value.sensor_sample_target_seconds);
    }
    function matches(value, preset) {
      return Object.keys(DEFAULTS).every(key => Number(value.policy[key]) === preset.policy[key])
        && Number(value.environment_interval_seconds) === preset.environment_interval_seconds
        && Number(value.sensor_sample_target_seconds) === preset.sensor_sample_target_seconds;
    }
    function describe() {
      text('policyPresetDescription', selected === 'custom'
        ? '自定义：保留当前参数，可单独调整每一项。'
        : `${presets[selected].label}：${presets[selected].description}`);
    }
    function selectPreset(name) {
      if (busy || !loaded || !Object.hasOwn(MODES, name)) return;
      if (selected === 'custom') customDraft = bundle();
      selected = name;
      if (name === 'custom') {
        if (customDraft) fillBundle(customDraft);
      } else fillBundle(presets[name]);
      describe(); buttons();
      text('policySettingsStatus', '已预览所选模式，点击“保存策略”后生效。');
      text('sensorSettingsStatus', `期望采样间隔 ${element('sensorSampleInterval').value} 秒，尚未应用到设备；如需修改实际采样，请另按“应用到设备”。`);
    }
    function render(data) {
      const value = policy(data?.policy);
      if (data.presets) {
        const incoming = {};
        for (const name of Object.keys(PRESETS)) {
          const item = data.presets[name];
          if (!item || typeof item.label !== 'string' || typeof item.description !== 'string') throw new Error('服务器预设信息不完整，请重新读取');
          incoming[name] = {label: plain(item.label), description: plain(item.description), policy: policy(item.policy),
            environment_interval_seconds: number(item.environment_interval_seconds, 5, 30, '预设轮询间隔'),
            sensor_sample_target_seconds: number(item.sensor_sample_target_seconds, 5, 30, '预设采样间隔')};
        }
        presets = incoming;
      }
      // Historical recording settings permitted up to one hour. Read them
      // without silently changing them; new policy saves are limited to 5–30s.
      const poll = number(data.environment_interval_seconds, 5, 3600, '服务器轮询间隔');
      fill(value); element('policyPollInterval').value = String(poll);
      for (const [id, key] of [['acAutomaticMin', 'min_setpoint_c'], ['acAutomaticMax', 'max_setpoint_c'], ['acAmbientLower', 'temperature_lower_c'], ['acAmbientUpper', 'temperature_upper_c']]) element(id).value = String(value[key]);
      for (const [id, key] of [['irEmissionLower', 'temperature_lower_c'], ['irEmissionUpper', 'temperature_upper_c']]) {
        if (element(id)) element(id).value = String(value[key]);
      }
      sensorConfirmed = data.sensor_sample_interval_seconds;
      const known = Number.isInteger(sensorConfirmed) && sensorConfirmed >= 5 && sensorConfirmed <= 30;
      const target = data.sensor_sample_target_seconds === undefined ? (known ? sensorConfirmed : 15)
        : number(data.sensor_sample_target_seconds, 5, 30, '期望采样间隔');
      element('sensorSampleInterval').value = String(target);
      const current = {policy: value, environment_interval_seconds: poll, sensor_sample_target_seconds: target};
      const inferred = Object.keys(presets).find(name => matches(current, presets[name])) || 'custom';
      selected = data.preset === 'custom' ? 'custom'
        : Object.hasOwn(presets, data.preset) && matches(current, presets[data.preset]) ? data.preset : inferred;
      customDraft = selected === 'custom' ? bundle() : null;
      describe();
      text('sensorSettingsStatus', known
        ? `上次已确认保存为 ${sensorConfirmed} 秒；本页尚未读取设备当前值。期望值 ${target} 秒，不代表当前已应用。`
        : `设备当前采样间隔未知；期望值 ${target} 秒，尚未应用到设备。`);
      loaded = true;
      text('policySettingsStatus', poll > 30
        ? `已读取历史记录间隔 ${poll} 秒，当前超过自动控制允许范围，请设为 5–30 秒后保存；尚未覆盖旧配置或开启控制。`
        : '已读取保存的参数；未开启 AI、抓拍或空调控制。修改后需点击保存。');
    }
    async function load() {
      if (busy) return; busy = true; buttons();
      try { render(await options.api('/api/policy-settings')); }
      catch (error) { loaded = false; text('policySettingsStatus', `参数读取失败：${plain(error?.message)}；未改变任何控制。`);
      }
      finally { busy = false; buttons(); }
    }
    async function save() {
      if (busy || !loaded) return;
      let payload;
      try {
        payload = {preset: selected, policy: policy(values()),
          environment_interval_seconds: number(element('policyPollInterval').value, 5, 30, '服务器轮询间隔'),
          sensor_sample_target_seconds: number(element('sensorSampleInterval').value, 5, 30, '期望采样间隔')};
        if (selected !== 'custom' && !matches(payload, presets[selected])) throw new Error('预设数值已改变，请选择“自定义”后保存');
      }
      catch (error) { text('policySettingsStatus', plain(error?.message)); return; }
      busy = true; buttons();
      try {
        const result = await options.api('/api/policy-settings', {method: 'POST', headers: {'Content-Type': 'application/json'}, body: JSON.stringify(payload)});
        render(result);
        text('policySettingsStatus', '参数已保存；自动 AI 和自动发射已暂停，需核对后明确重新开启。定时抓拍保留原开关，不会新开启。没有发送红外或改变传感器物理采样。');
        if (options.onSaved) await options.onSaved();
      } catch (error) { text('policySettingsStatus', `保存未确认：${plain(error?.message)}。不会重试或自动开启，请重新读取核对。`); }
      finally { busy = false; buttons(); }
    }
    function reset() {
      if (busy) return;
      if (selected === 'custom') customDraft = bundle();
      selected = 'balanced'; fillBundle(presets.balanced); describe(); buttons();
      text('policySettingsStatus', '均衡默认值已填入，尚未保存。传感器须另按“应用到设备”。');
      text('sensorSettingsStatus', '输入框已恢复 15 秒，尚未应用；当前设备实际值未知。');
    }
    async function applySensor() {
      if (busy || !loaded) return;
      let interval;
      try {
        interval = number(element('sensorSampleInterval').value, 5, 30, 'SHT31 物理采样间隔');
        if (selected !== 'custom' && interval !== presets[selected].sensor_sample_target_seconds) throw new Error('预设数值已改变，请选择“自定义”后应用');
      }
      catch (error) { text('sensorSettingsStatus', plain(error?.message)); return; }
      busy = true; buttons();
      try {
        const result = await options.api('/api/sensor-settings', {method: 'POST', headers: {'Content-Type': 'application/json'}, body: JSON.stringify({sample_interval_seconds: interval})});
        if (result?.sample_interval_seconds !== interval || typeof result.device_id !== 'string' || !result.device_id || typeof result.boot_id !== 'string' || !result.boot_id) throw new Error('设备确认字段不完整，不能确认已应用');
        sensorConfirmed = interval;
        text('sensorSettingsStatus', `本次设备已确认采样间隔 ${interval} 秒；仅保存在本次启动内，设备重启恢复 15 秒默认。自动 AI 和发射已暂停，需明确重新开启；原定时抓拍开关不变。`);
        if (options.onSaved) await options.onSaved();
      } catch (error) { text('sensorSettingsStatus', `应用未确认：${plain(error?.message)}。旧固件需更新主摄像头工程；不会自动重试，不把输入值视为已生效。`); }
      finally { busy = false; buttons(); }
    }
    for (const [name, id] of Object.entries(MODES)) element(id).addEventListener('change', () => {
      if (element(id).checked) selectPreset(name);
    });
    element('savePolicySettings').addEventListener('click', () => { void save(); });
    element('loadPolicySettings').addEventListener('click', () => { void load(); });
    element('resetPolicySettings').addEventListener('click', reset);
    element('applySensorSettings').addEventListener('click', () => { void applySensor(); });
    buttons();
    return {load, save, reset, applySensor, selectPreset};
  }
  root.DreamShieldPolicySettings = {DEFAULTS, LIMITS, PRESETS, policy, createController};
})(globalThis);
