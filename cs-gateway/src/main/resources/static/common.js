/* cs-pro 前端公共工具：Result 解包、管理令牌、格式化、toast */
const CS = {
  tokenKey: 'cs-admin-token',

  async api(path, opts = {}) {
    const headers = Object.assign({ 'Content-Type': 'application/json' }, opts.headers || {});
    if (path.startsWith('/api/admin/')) {
      headers['X-Admin-Token'] = localStorage.getItem(this.tokenKey) || 'cs-admin';
    }
    const resp = await fetch(path, Object.assign({}, opts, { headers }));
    let body;
    try { body = await resp.json(); } catch (e) { throw new Error('HTTP ' + resp.status); }
    if (body.code !== '00000') {
      const err = new Error(body.message || ('错误 ' + body.code));
      err.code = body.code;
      throw err;
    }
    return body.data;
  },

  get(path) { return this.api(path); },
  post(path, data) { return this.api(path, { method: 'POST', body: JSON.stringify(data || {}) }); },
  put(path, data) { return this.api(path, { method: 'PUT', body: JSON.stringify(data || {}) }); },
  del(path) { return this.api(path, { method: 'DELETE' }); },

  yuan(cents) { return (Number(cents || 0) / 100).toFixed(2); },

  time(ms) {
    if (!ms) return '-';
    const d = new Date(Number(ms));
    const p = n => String(n).padStart(2, '0');
    return `${d.getMonth() + 1}-${p(d.getDate())} ${p(d.getHours())}:${p(d.getMinutes())}`;
  },

  day(ms) {
    if (!ms) return '-';
    const d = new Date(Number(ms));
    const p = n => String(n).padStart(2, '0');
    return `${d.getFullYear()}-${p(d.getMonth() + 1)}-${p(d.getDate())}`;
  },

  esc(s) {
    return String(s == null ? '' : s).replace(/[&<>"']/g,
      c => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c]));
  },

  toast(msg, isErr) {
    let el = document.getElementById('toast');
    if (!el) {
      el = document.createElement('div');
      el.id = 'toast';
      document.body.appendChild(el);
    }
    el.textContent = msg;
    el.className = 'show' + (isErr ? ' err' : '');
    clearTimeout(this._toastTimer);
    this._toastTimer = setTimeout(() => { el.className = ''; }, 2600);
  },

  statusBadge(status) {
    const map = {
      ACTIVE: ['ok', '进行中'], ENDED: ['gray', '已结束'],
      OPEN: ['warn', '待处理'], PROCESSING: ['', '处理中'], RESOLVED: ['ok', '已解决'],
      CLOSED: ['gray', '已关闭'], CANCELLED: ['gray', '已取消'],
      PENDING: ['warn', '待支付'], PAID: ['', '已支付'], ACTIVATED: ['ok', '已开通'],
      FAILED: ['err', '失败'], UNUSED: ['ok', '未使用'], USED: ['gray', '已使用'],
      FROZEN: ['err', '已冻结'],
    };
    const [cls, text] = map[status] || ['gray', status];
    return `<span class="badge ${cls}">${text}</span>`;
  },

  qs(name) { return new URLSearchParams(location.search).get(name); },
};