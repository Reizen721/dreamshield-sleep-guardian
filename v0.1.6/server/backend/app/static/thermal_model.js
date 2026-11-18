(() => {
  'use strict';
  const el = id => document.getElementById(id);
  const names = {empty:'无人', covered:'正常覆盖', partially_uncovered:'部分未覆盖', largely_uncovered:'大面积未覆盖', uncertain:'不确定，需要复核'};
  const reasons = {low_top_score:'主模型得分不足', small_class_margin:'前两类得分接近', model_disagreement:'主模型与辅助模型结论不同'};
  const states = {idle:'未开启', stopped:'已停止', observing:'持续观察中', analyzed:'已分析一帧', waiting_new_frame:'等待新帧', waiting:'等待有效输入'};
  let pending = false, snapshot = null, serviceOnline = false, initialized = false;
  async function request(path, body) {
    const controller = new AbortController();
    const timer = setTimeout(() => controller.abort(), body === undefined ? 10000 : 115000);
    try {
      const response = await fetch('/api/thermal-model/' + path, {method:body === undefined ? 'GET':'POST', cache:'no-store', signal:controller.signal,
        ...(body === undefined ? {} : {headers:{'Content-Type':'application/json'}, body:JSON.stringify(body)})});
      const value = await response.json();
      if (!response.ok || !value.ok) throw new Error(value.error || '本地模型服务暂不可用');
      return value.thermal_model;
    } finally { clearTimeout(timer); }
  }
  function feedback(text, error = false) { el('modelFeedback').textContent = text; el('modelFeedback').classList.toggle('error', error); }
  function controls() {
    const running = Boolean(snapshot?.enabled), busy = !serviceOnline || pending || Boolean(snapshot?.busy), confirmed = el('modelPoseConfirmed').checked;
    el('modelAnalyze').disabled = busy || running || !confirmed;
    el('modelStart').disabled = busy || running || !confirmed;
    el('modelStop').disabled = !running; // Stop remains available during inference.
    el('modelInterval').disabled = busy || running;
    el('modelPoseConfirmed').disabled = running;
  }
  function render(value) {
    snapshot = value; serviceOnline = true;
    if (!initialized || value.enabled) el('modelInterval').value = value.interval_seconds;
    initialized = true;
    if (value.enabled) el('modelPoseConfirmed').checked = true;
    controls();
    el('modelRunState').textContent = value.busy ? '模型处理中' : states[value.state] || value.state;
    const result = value.last_live;
    if (!result) { el('modelAnswer').closest('.model-output').dataset.tone = 'waiting'; el('modelSource').textContent = '尚未进行判别'; el('modelAnswer').textContent = '等待观察'; el('modelExplanation').textContent = '请先开启热像实时预览。'; el('modelFacts').hidden = true; return; }
    const current = result.current;
    el('modelAnswer').closest('.model-output').dataset.tone = !current ? 'stale' : result.predicted_state === 'uncertain' ? 'uncertain' : 'result';
    el('modelSource').textContent = `实时预览帧 · ${current ? '帧有效' : '历史结果，已过期或停止'} · 帧号 ${result.frame_number ?? '—'}`;
    el('modelAnswer').textContent = current ? names[result.predicted_state] || result.predicted_state : '等待新的有效判别';
    el('modelExplanation').textContent = !current ? `上一帧结论：${names[result.predicted_state] || result.predicted_state}，当前不沿用。` : result.abstention_reasons.length ? result.abstention_reasons.map(r => reasons[r] || r).join('；') + '。建议复核；云端核对状态见下方联合闭环。' : '两模型给出的类别一致，仍需核对画面。联合闭环还需核对 VLM 与温湿度。';
    el('modelFacts').hidden = false;
    el('modelPrimary').textContent = names[result.raw_predicted_state] || result.raw_predicted_state;
    el('modelSecondary').textContent = names[result.secondary_predicted_state] || result.secondary_predicted_state;
    el('modelScore').textContent = Number(result.top_score).toFixed(3) + '（非准确率）';
    el('modelTime').textContent = `${Number(result.age_seconds || 0).toFixed(1)} 秒前接收`;
  }
  async function action(path, body) {
    if (pending && path !== 'config') return;
    pending = true; controls(); feedback(path === 'config' && !body.enabled ? '正在停止观察…' : '正在处理，首次加载模型可能需要十余秒…');
    try {
      const value = await request(path, body); render(value);
      feedback(value.last_error || (value.enabled ? '持续观察已开启。只读取已有预览帧，不保存新的训练数据。' : path === 'config' ? '持续观察已停止。' : '当前帧分析完成。'), Boolean(value.last_error));
    } catch (error) { feedback(error.name === 'AbortError' ? '请求超时，请检查模型服务。' : error.message, true); }
    finally { pending = false; controls(); }
  }
  el('modelPoseConfirmed').addEventListener('change', controls);
  el('modelAnalyze').addEventListener('click', () => action('analyze', {calibration_confirmed:el('modelPoseConfirmed').checked}));
  el('modelStart').addEventListener('click', () => {
    if (!el('modelInterval').reportValidity()) return;
    action('config', {enabled:true, interval_seconds:Number(el('modelInterval').value), calibration_confirmed:el('modelPoseConfirmed').checked});
  });
  el('modelStop').addEventListener('click', () => action('config', {enabled:false}));
  async function poll() {
    try { const value = await request('status'); if (!pending) { render(value); if (value.last_error) feedback(value.last_error, true); } }
    catch (error) {
      if (!pending) {
        serviceOnline = false; controls();
        el('modelRunState').textContent = '服务连接中断';
        el('modelAnswer').textContent = '判别暂不可用';
        el('modelAnswer').closest('.model-output').dataset.tone = 'offline';
        el('modelExplanation').textContent = '等待电脑服务恢复，上一帧结论不再作为当前结果。';
        el('modelFacts').hidden = true;
        feedback('本地模型服务暂不可用；正在自动重连。', true);
      }
    }
    finally { setTimeout(poll, document.hidden ? 5000 : 2000); }
  }
  poll();
})();
