// Display-only sensor validation. This never feeds manual-image VLM/control inputs.
(function (root) {
  'use strict';
  const MAX_AGE_MS = 45000;
  function view(environment, elapsedMs = 0) {
    const fail = message => ({valid: false, temperature: '—', humidity: '—', detail: message});
    if (!environment || typeof environment !== 'object') return fail('无温湿度字段：请上传 0.4.0 或更新固件');
    if (environment.sensor_model !== 'sht31' || environment.input_origin !== 'sensor') return fail('传感器来源不匹配');
    if (environment.status === 'starting') return fail('SHT31 正在等待首次采样');
    if (environment.status === 'stale') return fail('SHT31 数据已过期，等待新采样');
    if (environment.valid !== true || environment.status !== 'fresh') {
      const reason = typeof environment.last_error === 'string' ? environment.last_error : 'unknown';
      return fail(`SHT31 读数不可用：${reason}`);
    }
    const temperature = environment.ambient_temperature_c;
    const humidity = environment.relative_humidity_percent;
    if (!Number.isFinite(temperature) || temperature < -40 || temperature > 125 ||
        !Number.isFinite(humidity) || humidity < 0 || humidity > 100) return fail('SHT31 数值缺失或越界');
    const age = environment.sample_age_ms;
    if (!Number.isFinite(age) || age < 0 || !Number.isFinite(elapsedMs) || elapsedMs < 0) return fail('采样时间信息无效');
    const effectiveAge = age + elapsedMs;
    if (effectiveAge > MAX_AGE_MS) return fail('SHT31 数据已过期，等待刷新');
    return {
      valid: true,
      temperature: `${temperature.toFixed(2)} °C`,
      humidity: `${humidity.toFixed(2)} %RH`,
      detail: `SHT31 · 约 ${(effectiveAge / 1000).toFixed(1)} 秒前采样 · 本卡只显示，控制规则由服务器独立核验`
    };
  }
  root.DreamShieldEnvironment = {view};
})(globalThis);
