const RING = 2 * Math.PI * 15.5
const COPY = '<svg width="15" height="15" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><rect x="9" y="9" width="11" height="11" rx="2"/><path d="M5 15V5h10"/></svg>'
const OK = '<svg width="15" height="15" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><path d="M5 12l5 5L20 7"/></svg>'
const ICON_UNLOCK = '<svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><rect x="5" y="11" width="14" height="10" rx="2"/><path d="M8 11V7a4 4 0 0 1 7.9-1"/></svg>'
const ICON_GO = '<svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><path d="M5 12l5 5L20 7"/></svg>'
const EDIT = '<svg width="15" height="15" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><path d="M12 20h9"/><path d="M16.5 3.5a2.1 2.1 0 0 1 3 3L7 19l-4 1 1-4Z"/></svg>'
const DEL = '<svg width="15" height="15" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><path d="M4 7h16"/><path d="M9 7V5h6v2"/><path d="M7 7l1 13h8l1-13"/></svg>'

let state = { accounts: [], codes: {}, remains: {}, settings: {}, shown: new Set(), q: '', export: null, qrPage: 0 }
let bio = { available: false, enabled: false }
let timer = null
let clipTimer = null
let isSetup = false
let lastActivity = 0
let promptDismiss = null
let promptReturnFocus = null

function invoke(name, args) {
  return window.__TAURI__.core.invoke(name, args)
}

async function call(name, args = {}) {
  try {
    const res = await invoke(name, args)
    if (res && res.ok === false) throw new Error(res.error || '失败')
    return res || { ok: true }
  } catch (e) {
    throw new Error(String(e?.message || e).replace(/^Error:\s*/, ''))
  }
}

const THEME_KEY = 'oa-theme'
const $ = (id) => document.getElementById(id)
function currentTheme() {
  return localStorage.getItem(THEME_KEY) === 'light' ? 'light' : 'dark'
}
function applyTheme(theme) {
  const t = theme === 'light' ? 'light' : 'dark'
  document.documentElement.dataset.theme = t
  try { localStorage.setItem(THEME_KEY, t) } catch (_) {}
  try { currentWindow()?.setTheme?.(t)?.catch?.(() => {}) } catch (_) {}
}
const esc = (s) => String(s || '').replace(/[&<>"']/g, (c) => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c]))
const fmt = (code) => {
  const s = String(code || '')
  return s.length === 6 ? `${s.slice(0, 3)} ${s.slice(3)}` : s.length === 8 ? `${s.slice(0, 4)} ${s.slice(4)}` : s
}
function hue(s) {
  let h = 0
  for (const c of String(s || '')) h = (h * 33 + c.charCodeAt(0)) >>> 0
  return h % 360
}
function avatarStyle(name) {
  const h = hue(name)
  return `background:linear-gradient(180deg,hsl(${h} 42% 46%),hsl(${h} 48% 32%))`
}
function avatarSource(a) {
  return String(a.name || '').trim() || accountEmail(a) || String(a.issuer || '').trim() || '?'
}
function avatarInitial(a) {
  const src = avatarSource(a)
  const ch = [...src][0] || '?'
  return ch.toUpperCase()
}

function looksLikeEmail(s) {
  return /^[^\s@]+@[^\s@]+\.[^\s@]+$/.test(String(s || '').trim())
}

function accountEmail(a) {
  const email = String(a.email || '').trim()
  if (email) return email
  const name = String(a.name || '').trim()
  return looksLikeEmail(name) ? name : ''
}

function groups(accounts) {
  const q = state.q.trim().toLowerCase()
  const out = []
  const map = new Map()
  for (const a of accounts) {
    const blob = [a.issuer, a.name, a.email, a.notes].join(' ').toLowerCase()
    if (q && !blob.includes(q)) continue
    if (!map.has(a.issuer)) {
      const g = { issuer: a.issuer || '其他', items: [] }
      map.set(a.issuer, g)
      out.push(g)
    }
    map.get(a.issuer).items.push(a)
  }
  for (const g of out) {
    g.items.sort((a, b) =>
      avatarSource(a).localeCompare(avatarSource(b), 'zh-CN', { sensitivity: 'base', numeric: true })
    )
  }
  return out
}

function paintList() {
  const el = $('list')
  const gs = groups(state.accounts)
  if (!gs.length) {
    el.innerHTML = '<div class="empty"><strong>还没有账号</strong>用右上角 + 添加，或从导入导出里扫入 Google 验证器二维码。</div>'
    return
  }
  el.innerHTML = gs.map((g) => `
    <div class="group">
      <div class="label">${esc(g.issuer)}</div>
      <div class="card-list">
      ${g.items.map((a) => {
        const shown = state.shown.has(a.id)
        const danger = Number(state.remains[a.id] || a.period || 30) <= 5
        const code = shown ? fmt(state.codes[a.id] || '') : (a.digits === 8 ? '•••• ••••' : '••• •••')
        const email = accountEmail(a)
        const rawName = String(a.name || '').trim()
        const title = (email && rawName === email) ? (a.issuer || rawName) : (rawName || a.issuer || '未命名')
        const showEmailRow = !!email
        const showTitle = title && title !== email
        const notes = String(a.notes || '').trim()
        return `<div class="row ${shown ? 'shown' : ''} ${danger && shown ? 'danger' : ''} ${showEmailRow ? 'has-email' : ''} ${showEmailRow && notes ? 'has-notes' : ''}" data-id="${a.id}" data-email="${esc(email)}">
          <div class="avatar" style="${avatarStyle(avatarSource(a))}">${esc(avatarInitial(a))}</div>
          <div class="meta">
            ${showTitle || !showEmailRow ? `<div class="name">${esc(showTitle ? title : (title || '未命名'))}</div>` : ''}
            ${notes && !showEmailRow ? `<div class="note">${esc(notes)}</div>` : ''}
          </div>
          <div class="code-wrap" data-act="toggle"><div class="code">${code}</div></div>
          <button class="copy" type="button" data-act="copy" title="复制验证码">${COPY}</button>
          <button class="edit" type="button" data-act="edit">${EDIT}</button>
          <button class="del" type="button" data-act="del">${DEL}</button>
          ${showEmailRow ? `<div class="email-line" data-act="copy-email">
            <span class="email">${esc(email)}</span>
            <button class="copy-email" type="button" data-act="copy-email" title="复制邮箱">${COPY}</button>
          </div>` : ''}
          ${showEmailRow && notes ? `<div class="note-line">${esc(notes)}</div>` : ''}
        </div>`
      }).join('')}
      </div>
    </div>`).join('')
}

function paintRing() {
  const active = state.accounts
    .map((a) => ({ remain: Number(state.remains[a.id] || a.period || 30), period: Number(a.period || 30) }))
    .sort((a, b) => a.remain - b.remain)[0] || { remain: 30, period: 30 }
  const sec = active.remain
  const ring = $('ring')
  ring.className = 'ring' + (sec <= 5 ? ' danger' : sec <= 10 ? ' warn' : '')
  $('remain').textContent = String(sec)
  ring.querySelector('.prog').style.strokeDashoffset = String(RING * (1 - sec / active.period))
}

async function refresh() {
  const snap = await call('snapshot')
  state.accounts = snap.accounts || []
  state.codes = snap.codes || {}
  state.remains = snap.remains || {}
  state.settings = snap.settings || {}
  paintRing()
  paintList()
}

async function copyEmail(email, btn) {
  if (!email) return
  await navigator.clipboard.writeText(email)
  if (!btn) return
  const prev = btn.innerHTML
  btn.innerHTML = OK
  setTimeout(() => { btn.innerHTML = prev }, 900)
}

async function copyCode(id, btn) {
  const code = state.codes[id]
  if (!code || !btn) return
  await navigator.clipboard.writeText(code)
  const row = btn.closest('.row')
  if (!row) return
  row.classList.add('copied')
  btn.innerHTML = OK
  setTimeout(() => { row.classList.remove('copied'); btn.innerHTML = COPY }, 900)
  const sec = Number(state.settings.clipboard_clear_seconds || 0)
  if (clipTimer) clearTimeout(clipTimer)
  if (sec > 0) clipTimer = setTimeout(async () => {
    try {
      if (await navigator.clipboard.readText() === code) await navigator.clipboard.writeText('')
    } catch (_) {}
  }, sec * 1000)
}

function closePrompt() {
  const returnFocus = promptReturnFocus
  promptDismiss = null
  promptReturnFocus = null
  $('prompt').classList.add('hidden')
  $('prompt').removeAttribute('aria-labelledby')
  $('prompt-sheet').innerHTML = ''
  $('prompt').onclick = null
  if (returnFocus?.isConnected) returnFocus.focus()
}
function dismissPrompt() {
  if (promptDismiss) {
    const dismiss = promptDismiss
    promptDismiss = null
    dismiss()
  } else {
    closePrompt()
  }
}
function openPrompt(html, onDismiss = null) {
  promptReturnFocus = document.activeElement
  promptDismiss = onDismiss
  $('prompt-sheet').innerHTML = html
  const heading = $('prompt-sheet').querySelector('h2')
  if (heading) {
    heading.id = 'prompt-title'
    $('prompt').setAttribute('aria-labelledby', heading.id)
  }
  $('prompt').classList.remove('hidden')
  requestAnimationFrame(() => {
    $('prompt-sheet').querySelector('[autofocus], button, input, textarea, select')?.focus()
  })
}
function closeModal() {
  if ($('prompt').classList.contains('hidden')) closePrompt()
  else dismissPrompt()
  $('modal').classList.add('hidden')
  $('modal').removeAttribute('aria-labelledby')
  $('sheet').innerHTML = ''
  state.export = null
  if (window._pasteQr) {
    document.removeEventListener('paste', window._pasteQr)
    window._pasteQr = null
  }
}
function openModal(html) {
  closePrompt()
  $('sheet').innerHTML = html
  const heading = $('sheet').querySelector('h2')
  if (heading) {
    heading.id = 'modal-title'
    $('modal').setAttribute('aria-labelledby', heading.id)
  }
  $('modal').classList.remove('hidden')
}
function askAuth(hint, run) {
  return new Promise((resolve) => {
    let done = false
    const finish = (value) => {
      if (done) return
      done = true
      closePrompt()
      resolve(value)
    }
    openPrompt(`
      <h2>验证身份</h2>
      <p class="hint">${esc(hint)}</p>
      ${field('auth-pw', '主密码', '', 'type="password" autocomplete="current-password"', { id: 'auth-ok', title: '验证', icon: ICON_GO })}
      <p class="err" id="auth-err"></p>
      <div class="row-btns">
        <button type="button" class="ghost" id="auth-cancel">取消</button>
        ${bio.enabled ? '<button type="button" class="ghost" id="auth-bio">指纹验证</button>' : ''}
      </div>
    `, () => finish(null))
    $('auth-cancel').onclick = () => finish(null)
    $('prompt').onclick = (e) => { if (e.target.id === 'prompt') dismissPrompt() }
    const go = async (biometric) => {
      if (done) return
      const pw = biometric ? '' : ($('prompt-sheet').querySelector('[name="auth-pw"]')?.value || '')
      if (!biometric && !pw) {
        $('auth-err').textContent = '请输入主密码'
        return
      }
      $('auth-err').textContent = ''
      try {
        finish(await run(pw, biometric))
      } catch (e) {
        const err = $('auth-err')
        if (err) err.textContent = e.message
      }
    }
    $('auth-ok').onclick = () => go(false)
    const bioBtn = $('auth-bio')
    if (bioBtn) bioBtn.onclick = () => go(true)
    const input = $('prompt-sheet').querySelector('[name="auth-pw"]')
    bindPwGo(input, $('auth-ok'), [], () => go(false))
    input?.focus()
  })
}
function showAccountQr(svg) {
  openPrompt(`
    <h2>账号二维码</h2>
    <p class="hint">可供其他验证器扫描</p>
    <div class="qr-box">${svg}</div>
    <div class="row-btns"><button type="button" class="ghost" id="qr-done">关闭</button></div>
  `, closePrompt)
  $('qr-done').onclick = closePrompt
  $('prompt').onclick = (e) => { if (e.target.id === 'prompt') dismissPrompt() }
}

function appDialog({ title, message, confirmLabel = '确定', cancelLabel = '', tone = 'default' }) {
  return new Promise((resolve) => {
    let done = false
    const finish = (value) => {
      if (done) return
      done = true
      closePrompt()
      resolve(value)
    }
    const icon = tone === 'warning'
      ? '<svg viewBox="0 0 24 24" aria-hidden="true"><path d="M12 9v4m0 4h.01M10.3 4.2 2.6 17.5A2 2 0 0 0 4.3 20h15.4a2 2 0 0 0 1.7-2.5L13.7 4.2a2 2 0 0 0-3.4 0Z"/></svg>'
      : '<svg viewBox="0 0 24 24" aria-hidden="true"><path d="m5 12 4 4L19 6"/></svg>'
    openPrompt(`
      <div class="dialog-head">
        <div class="dialog-icon ${tone}">${icon}</div>
        <div class="dialog-copy">
          <h2>${esc(title)}</h2>
          <p>${esc(message)}</p>
        </div>
      </div>
      <div class="row-btns dialog-actions">
        ${cancelLabel ? `<button type="button" class="ghost" id="dialog-cancel">${esc(cancelLabel)}</button>` : ''}
        <button type="button" class="${tone === 'warning' ? 'danger-solid' : 'primary'}" id="dialog-confirm" autofocus>${esc(confirmLabel)}</button>
      </div>
    `, () => finish(false))
    const cancel = $('dialog-cancel')
    if (cancel) cancel.onclick = () => finish(false)
    $('dialog-confirm').onclick = () => finish(true)
    $('prompt').onclick = (e) => { if (e.target.id === 'prompt') dismissPrompt() }
  })
}
function field(name, label, value, extra = '', action = null) {
  const input = `<input name="${name}" value="${esc(value || '')}" ${extra} />`
  if (!action) return `<label>${label}${input}</label>`
  return `<label class="pw-field">${label}<span class="pw-wrap">${input}<button type="button" class="pw-go hidden" id="${esc(action.id)}" title="${esc(action.title)}" aria-label="${esc(action.title)}">${action.icon}</button></span></label>`
}
function bindPwGo(input, btn, extra = [], onEnter = null) {
  if (!input || !btn) return
  const sync = () => {
    const ready = [input, ...extra].every((el) => String(el?.value || '').length > 0)
    btn.classList.toggle('hidden', !ready)
  }
  for (const el of [input, ...extra]) {
    el.addEventListener('input', sync)
    el.addEventListener('change', sync)
    if (onEnter) {
      el.addEventListener('keydown', (e) => {
        if (e.key !== 'Enter') return
        e.preventDefault()
        if (!btn.classList.contains('hidden')) onEnter()
      })
    }
  }
  sync()
}
function download(name, text, type) {
  const a = document.createElement('a')
  a.href = URL.createObjectURL(new Blob([text], { type }))
  a.download = name
  a.click()
  setTimeout(() => URL.revokeObjectURL(a.href), 1000)
}

async function saveExport(name, content) {
  const el = $('export-err')
  el.classList.remove('ok')
  if (!content) {
    el.textContent = '请先验证主密码'
    return
  }
  try {
    if (!window.__TAURI__) {
      download(name, content, name.endsWith('.json') ? 'application/json' : 'text/plain')
      el.classList.add('ok')
      el.textContent = '已开始下载 ' + name
      return
    }
    const res = await call('save_text', { name, content })
    el.classList.add('ok')
    el.textContent = '已保存到 ' + res.path
  } catch (e) {
    el.textContent = e.message
  }
}

function openEditor(acc) {
  const a = acc || { issuer: '', name: '', email: '', notes: '', secret: '', algorithm: 'SHA1', digits: 6, period: 30 }
  openModal(`
    <h2>${acc ? '编辑账号' : '添加账号'}</h2>
    ${field('issuer', '发行方', a.issuer)}
    ${field('name', '用户名', a.name)}
    ${field('email', '邮箱', a.email)}
    <label>备注<textarea name="notes">${esc(a.notes)}</textarea></label>
    ${field('secret', acc ? '密钥（留空则不改）' : '密钥', acc ? '' : a.secret, 'spellcheck="false" autocomplete="off"')}
    <label>算法<select name="algorithm">
      <option ${a.algorithm === 'SHA1' ? 'selected' : ''}>SHA1</option>
      <option ${a.algorithm === 'SHA256' ? 'selected' : ''}>SHA256</option>
      <option ${a.algorithm === 'SHA512' ? 'selected' : ''}>SHA512</option>
    </select></label>
    <div class="grid-2">
      ${field('digits', '位数', a.digits, 'type="number" min="6" max="8" step="2"')}
      ${field('period', '周期（秒）', a.period || 30, 'type="number" min="1" max="86400"')}
    </div>
    ${acc ? '<button type="button" class="link-btn" id="btn-show-qr">显示二维码</button>' : ''}
    <p class="err" id="form-err"></p>
    <div class="row-btns">
      <button type="button" class="ghost" data-close>取消</button>
      <button type="button" class="primary" id="btn-save">保存</button>
    </div>
    ${acc ? '<div class="row-btns"><button type="button" class="danger-btn" id="btn-del">删除账号</button></div>' : ''}
  `)
  $('sheet').querySelector('[data-close]').onclick = closeModal
  $('btn-save').onclick = async () => {
    const data = Object.fromEntries([...$('sheet').querySelectorAll('input,textarea,select')].map((n) => [n.name, ['digits', 'period'].includes(n.name) ? Number(n.value) : n.value]))
    try {
      if (acc) {
        if (String(data.secret || '').trim()) {
          const ok = await askAuth('更改密钥需要验证主密码', (password, biometric) =>
            call('update_account', { id: acc.id, data, password, biometric })
          )
          if (!ok) return
        } else {
          await call('update_account', { id: acc.id, data, password: '', biometric: false })
        }
      } else {
        await call('add_account', { data })
      }
      closeModal()
      await refresh()
    } catch (e) { $('form-err').textContent = e.message }
  }
  const del = $('btn-del')
  if (del) del.onclick = () => askDelete(acc)
  const showQr = $('btn-show-qr')
  if (showQr) showQr.onclick = async () => {
    const res = await askAuth('查看二维码需要验证主密码', (password, biometric) =>
      call('account_qr', { id: acc.id, password, biometric })
    )
    if (!res) return
    showAccountQr(res.svg)
  }
}

function askDelete(acc) {
  const name = acc.name || acc.issuer || '这个账号'
  openModal(`
    <h2>删除账号</h2>
    <p class="hint">确定删除「${esc(name)}」？删除后无法恢复。</p>
    <p class="err" id="form-err"></p>
    <div class="row-btns">
      <button type="button" class="ghost" data-close>取消</button>
      <button type="button" class="danger-btn" id="btn-del-ok">删除</button>
    </div>
  `)
  $('sheet').querySelector('[data-close]').onclick = closeModal
  $('btn-del-ok').onclick = async () => {
    try {
      await call('delete_account', { id: acc.id })
      state.shown.delete(acc.id)
      closeModal()
      await refresh()
    } catch (e) { $('form-err').textContent = e.message }
  }
}

function bindPaste() {
  if (window._pasteQr) document.removeEventListener('paste', window._pasteQr)
  window._pasteQr = (e) => {
    if ($('modal').classList.contains('hidden')) return
    const item = [...(e.clipboardData?.items || [])].find((i) => i.type.startsWith('image/'))
    if (item) {
      e.preventDefault()
      readImportFile(item.getAsFile())
    }
  }
  document.addEventListener('paste', window._pasteQr)
}

function paintExport() {
  const data = state.export
  const box = $('export-qr')
  if (!box) return
  if (!data || !data.qrs || !data.qrs.length) {
    box.innerHTML = `${data?.migration_warning ? `<p class="err">${esc(data.migration_warning)}</p>` : ''}<p class="hint">没有可生成 Google 转移二维码的标准 30 秒账号，可下载 JSON 或链接备份。</p>`
    return
  }
  const n = data.qrs.length
  if (state.qrPage >= n) state.qrPage = 0
  const cur = data.qrs[state.qrPage]
  box.innerHTML = `
    ${data.migration_warning ? `<p class="err">${esc(data.migration_warning)}</p>` : ''}
    <div class="qr-box">${cur.svg}</div>
    <div class="qr-cap">第 ${state.qrPage + 1}/${n} 张 · ${cur.count} 个账号<br>用 Google 验证器「转移账号」扫描</div>
    ${n > 1 ? `<div class="row-btns">
      <button type="button" class="ghost" id="qr-prev">上一张</button>
      <button type="button" class="ghost" id="qr-next">下一张</button>
    </div>` : ''}`
  const prev = $('qr-prev')
  const next = $('qr-next')
  if (prev) prev.onclick = () => { state.qrPage = (state.qrPage + n - 1) % n; paintExport() }
  if (next) next.onclick = () => { state.qrPage = (state.qrPage + 1) % n; paintExport() }
}

function showExportGate() {
  state.export = null
  const box = $('export-qr')
  const err = $('export-err')
  if (err) {
    err.classList.remove('ok')
    err.textContent = ''
  }
  if (!box) return
  box.innerHTML = `
    <p class="hint">查看导出二维码或下载备份，需要再次验证身份。</p>
    ${field('export-pw', '主密码', '', 'type="password" autocomplete="current-password"', { id: 'btn-export-auth', title: '验证', icon: ICON_GO })}
    ${bio.enabled ? '<div class="row-btns tight"><button type="button" class="ghost" id="btn-export-bio">使用指纹验证</button></div>' : ''}`
  const runExport = async (biometric) => {
    const pw = biometric ? '' : ($('sheet').querySelector('[name="export-pw"]')?.value || '')
    err.classList.remove('ok')
    try {
      state.export = await call('export_data', { password: pw, biometric })
      state.qrPage = 0
      paintExport()
    } catch (e) { err.textContent = e.message }
  }
  $('btn-export-auth').onclick = () => runExport(false)
  bindPwGo($('sheet').querySelector('[name="export-pw"]'), $('btn-export-auth'), [], () => runExport(false))
  const expBio = $('btn-export-bio')
  if (expBio) expBio.onclick = () => runExport(true)
}

function showPane(name) {
  $('pane-in').classList.toggle('on', name === 'in')
  $('pane-out').classList.toggle('on', name === 'out')
  $('tab-in').classList.toggle('on', name === 'in')
  $('tab-out').classList.toggle('on', name === 'out')
}

function imported(count) {
  return appDialog({
    title: count ? '导入完成' : '没有新增账号',
    message: count ? `已安全导入 ${count} 个账号。` : '导入内容中的账号均已存在，重复项已跳过。',
    confirmLabel: '完成',
    tone: 'success',
  })
}

function openTransfer() {
  openModal(`
    <h2>导入导出</h2>
    <div class="seg">
      <button type="button" class="on" id="tab-in">导入</button>
      <button type="button" id="tab-out">导出</button>
    </div>
    <div class="pane on" id="pane-in">
      <p class="hint">支持 Google 验证器转移二维码、otpauth 链接，以及 JSON / 文本备份。</p>
      <div class="drop" id="drop">把二维码、JSON 或 txt 拖到这里<br>也可点击选择或粘贴截图</div>
      <input id="file" type="file" accept="image/*,.json,.txt,.otpauth" hidden />
      <label>粘贴链接或 JSON<textarea id="uri" spellcheck="false" placeholder="otpauth-migration:// 或 otpauth://totp/..."></textarea></label>
      <p class="err" id="form-err"></p>
      <div class="row-btns">
        <button type="button" class="ghost" data-close>取消</button>
        <button type="button" class="primary" id="btn-uri">导入</button>
      </div>
    </div>
    <div class="pane" id="pane-out">
      <div id="export-qr"></div>
      <p class="err" id="export-err"></p>
      <div class="row-btns">
        <button type="button" class="ghost" id="btn-json">下载 JSON</button>
        <button type="button" class="ghost" id="btn-txt">下载链接</button>
      </div>
      <div class="row-btns"><button type="button" class="ghost" data-close>完成</button></div>
    </div>`)
  $('sheet').querySelectorAll('[data-close]').forEach((b) => { b.onclick = closeModal })
  $('tab-in').onclick = () => {
    state.export = null
    showPane('in')
  }
  $('tab-out').onclick = () => {
    showPane('out')
    showExportGate()
  }
  const drop = $('drop')
  const file = $('file')
  drop.onclick = () => file.click()
  drop.addEventListener('dragover', (e) => { e.preventDefault(); drop.classList.add('over') })
  drop.addEventListener('dragleave', () => drop.classList.remove('over'))
  drop.addEventListener('drop', (e) => {
    e.preventDefault()
    drop.classList.remove('over')
    if (e.dataTransfer.files[0]) readImportFile(e.dataTransfer.files[0])
  })
  file.onchange = () => { if (file.files[0]) readImportFile(file.files[0]) }
  bindPaste()
  $('btn-uri').onclick = async () => {
    try {
      const text = $('uri').value.trim()
      const res = text.startsWith('otpauth') && !text.includes('\n') && !text.startsWith('{') && !text.startsWith('[')
        ? await call('import_uri', { uri: text })
        : await call('import_text', { text })
      closeModal()
      await refresh()
      imported(res.count)
    } catch (e) { $('form-err').textContent = e.message }
  }
  $('btn-json').onclick = () => saveExport('authenticator.json', state.export?.json)
  $('btn-txt').onclick = () => saveExport('authenticator-otpauth.txt', state.export?.txt)
}

function readImportFile(file) {
  if (file.size > 10 * 1024 * 1024) {
    $('form-err').textContent = '导入文件不能超过 10 MiB'
    return
  }
  const name = file.name || ''
  const isImg = (file.type || '').startsWith('image/') || /\.(png|jpe?g|gif|webp|bmp)$/i.test(name)
  if (isImg) {
    const reader = new FileReader()
    reader.onload = async () => {
      try {
        const res = await call('import_qr', { imageB64: reader.result })
        closeModal()
        await refresh()
        imported(res.count)
      } catch (e) { $('form-err').textContent = e.message }
    }
    reader.readAsDataURL(file)
    return
  }
  file.text().then(async (text) => {
    try {
      const res = await call('import_text', { text })
      closeModal()
      await refresh()
      imported(res.count)
    } catch (e) { $('form-err').textContent = e.message }
  })
}

function openSettings() {
  const s = state.settings || {}
  call('bio_status').then((b) => {
    bio.available = !!b.available
    bio.enabled = !!b.enabled
  }).catch(() => {}).finally(() => renderSettings(s))
}

function bindThemeSeg() {
  const theme = currentTheme()
  $('sheet')?.querySelectorAll('#theme-seg [data-theme]').forEach((btn) => {
    btn.classList.toggle('on', btn.dataset.theme === theme)
    btn.onclick = () => {
      applyTheme(btn.dataset.theme)
      bindThemeSeg()
    }
  })
}

function renderSettings(s) {
  openModal(`
    <h2>设置</h2>
    <p class="sec-title">外观</p>
    <div class="seg" id="theme-seg">
      <button type="button" data-theme="dark"${currentTheme() === 'dark' ? ' class="on"' : ''}>深色</button>
      <button type="button" data-theme="light"${currentTheme() === 'light' ? ' class="on"' : ''}>浅色</button>
    </div>
    <p class="sec-title">常规</p>
    <section class="block">
      <div class="grid-2">
        ${field('autolock_seconds', '自动锁定（秒）', s.autolock_seconds, 'type="number" min="0" max="2678400"')}
        ${field('clipboard_clear_seconds', '清空剪贴板（秒）', s.clipboard_clear_seconds, 'type="number" min="0" max="86400"')}
      </div>
      <p class="hint">填 0 表示关闭该项。</p>
    </section>
    <p class="sec-title">系统解锁</p>
    <section class="block">
      ${bio.available ? `
        <div class="bio-status ${bio.enabled ? 'on' : 'off'}">
          <span class="bio-dot" aria-hidden="true"></span>
          <strong>${bio.enabled ? '已开启' : '未开启'}</strong>
        </div>
        <p class="hint">${bio.enabled ? '锁定后可用指纹解锁，查看二维码和更改密钥也可指纹验证。' : '开启后可用 Touch ID 或 Windows Hello 解锁，以及二次验证。'}</p>
        ${bio.enabled
          ? '<div class="row-btns tight"><button type="button" class="ghost" id="btn-bio-off">关闭指纹解锁</button></div>'
          : `${field('bio-pw', '主密码', '', 'type="password" autocomplete="current-password"', { id: 'btn-bio-on', title: '开启指纹解锁', icon: ICON_GO })}`}
      ` : '<p class="hint">当前设备没有可用的指纹、Touch ID 或 Windows Hello。</p>'}
    </section>
    <p class="sec-title">WebDAV 同步</p>
    <section class="block">
      ${field('webdav_url', '服务器地址', s.webdav_url, 'placeholder="https://dav.example.com/"')}
      <div class="grid-2">
        ${field('webdav_user', '用户名', s.webdav_user)}
        ${field('webdav_password', '密码', '', 'type="password" placeholder="' + (s.webdav_has_password ? '已保存，留空不改' : '') + '"')}
      </div>
        ${field('webdav_path', '远程文件', s.webdav_path || '/authenticator/vault.enc')}
        <label class="check"><input type="checkbox" name="clear_webdav_password" /> 清除已保存的 WebDAV 密码</label>
        ${field('remote_vault_password', '远程保险库主密码（仅在与本地不同时填写）', '', 'type="password" autocomplete="off"')}
      <div class="row-btns tight">
        <button type="button" class="ghost" id="btn-down">拉取</button>
        <button type="button" class="ghost" id="btn-up">上传</button>
      </div>
      <p class="err" id="dav-err"></p>
    </section>
    <p class="sec-title">主密码</p>
    <section class="block">
      ${field('old', '当前密码', '', 'type="password"')}
      ${field('newpw', '新密码', '', 'type="password"')}
      ${field('new2', '确认新密码', '', 'type="password"', { id: 'btn-pw', title: '更新主密码', icon: ICON_GO })}
    </section>
    <p class="err" id="form-err"></p>
    <div class="row-btns sheet-actions">
      <button type="button" class="ghost" data-close>关闭</button>
      <button type="button" class="primary" id="btn-save-set">保存设置</button>
    </div>`)
  $('sheet').querySelector('[data-close]').onclick = closeModal
  bindThemeSeg()
  const val = (name) => $('sheet').querySelector('[name="' + name + '"]').value
  const snapshotForm = () => ({
    ...state.settings,
    autolock_seconds: Number(val('autolock_seconds') || 0),
    clipboard_clear_seconds: Number(val('clipboard_clear_seconds') || 0),
    webdav_url: val('webdav_url'),
    webdav_user: val('webdav_user'),
    webdav_path: val('webdav_path'),
  })
  const refreshBioSection = (message) => {
    renderSettings(snapshotForm())
    $('form-err').classList.add('ok')
    $('form-err').textContent = message
  }
  const saveSet = () => call('save_settings', { data: {
    webdav_url: val('webdav_url'),
    webdav_user: val('webdav_user'),
    webdav_password: val('webdav_password'),
    webdav_path: val('webdav_path'),
    autolock_seconds: Number(val('autolock_seconds') || 0),
    clipboard_clear_seconds: Number(val('clipboard_clear_seconds') || 0),
    clear_webdav_password: $('sheet').querySelector('[name="clear_webdav_password"]').checked,
  }})
  const davMsg = (text, kind) => {
    const el = $('dav-err')
    el.className = kind === 'ok' ? 'err ok' : kind === 'busy' ? 'hint' : 'err'
    el.textContent = text
  }
  $('btn-save-set').onclick = async () => {
    try {
      await saveSet()
      await refresh()
      $('form-err').classList.add('ok')
      $('form-err').textContent = '设置已保存'
    } catch (e) {
      $('form-err').classList.remove('ok')
      $('form-err').textContent = e.message
    }
  }
  const runDav = async (btn, doing, work, done) => {
    davMsg(doing, 'busy')
    btn.disabled = true
    try {
      await saveSet()
      await work()
      davMsg(done, 'ok')
    } catch (e) {
      davMsg(String(e.message || e).replace(/^Error:\s*/, ''), 'err')
    } finally {
      btn.disabled = false
    }
  }
  $('btn-up').onclick = () => runDav($('btn-up'), '正在上传…', () => call('webdav_upload'), '已上传加密保险库')
  $('btn-down').onclick = async () => {
    const confirmed = await appDialog({
      title: '使用远程保险库？',
      message: '远程数据将替换当前本地数据。替换前会自动创建一份本地加密备份。',
      confirmLabel: '拉取并替换',
      cancelLabel: '取消',
      tone: 'warning',
    })
    if (!confirmed) return
    await runDav($('btn-down'), '正在拉取…', async () => {
      await call('webdav_download', { password: val('remote_vault_password') })
      await refresh()
    }, '已从远程覆盖本地')
  }
  const updatePassword = async () => {
    try { await call('change_password', { old: val('old'), newPassword: val('newpw'), confirm: val('new2') }); $('form-err').textContent = '主密码已更新' }
    catch (e) { $('form-err').textContent = e.message }
  }
  $('btn-pw').onclick = updatePassword
  bindPwGo($('sheet').querySelector('[name="new2"]'), $('btn-pw'), [
    $('sheet').querySelector('[name="old"]'),
    $('sheet').querySelector('[name="newpw"]'),
  ], updatePassword)
  const bioOn = $('btn-bio-on')
  if (bioOn) {
    const enableBio = async () => {
      $('form-err').classList.remove('ok')
      try {
        await call('bio_enable', { password: val('bio-pw') })
        const b = await call('bio_status')
        bio.available = !!b.available
        bio.enabled = !!b.enabled
        if (!bio.enabled) throw new Error('指纹凭据未能保存')
        refreshBioSection('已开启指纹解锁')
      } catch (e) { $('form-err').textContent = e.message }
    }
    bioOn.onclick = enableBio
    bindPwGo($('sheet').querySelector('[name="bio-pw"]'), bioOn, [], enableBio)
  }
  const bioOff = $('btn-bio-off')
  if (bioOff) bioOff.onclick = async () => {
    $('form-err').classList.remove('ok')
    try {
      await call('bio_disable')
      const b = await call('bio_status')
      bio.available = !!b.available
      bio.enabled = !!b.enabled
      refreshBioSection('已关闭指纹解锁')
    } catch (e) { $('form-err').textContent = e.message }
  }
}

async function showApp() {
  $('gate').classList.add('hidden')
  $('app').classList.remove('hidden')
  await refresh()
  if (timer) clearInterval(timer)
  timer = setInterval(async () => {
    try { await refresh() }
    catch (e) {
      if (String(e.message).includes('锁定')) { clearInterval(timer); await showGate() }
    }
  }, 1000)
}

async function showGate() {
  if (timer) clearInterval(timer)
  $('app').classList.add('hidden')
  $('gate').classList.remove('hidden')
  closeModal()
  state.accounts = []
  state.codes = {}
  state.remains = {}
  state.shown.clear()
  state.export = null
  $('list').replaceChildren()
  const st = await call('status')
  isSetup = !st.exists
  bio.available = !!st.bio_available
  bio.enabled = !!st.bio_enabled
  $('gate-sub').textContent = isSetup ? '首次使用，请设置主密码（至少 8 位）' : (bio.enabled ? '指纹或主密码解锁' : '输入主密码解锁')
  const gateTitle = isSetup ? '创建保险库' : '解锁'
  $('gate-btn').title = gateTitle
  $('gate-btn').setAttribute('aria-label', gateTitle)
  $('gate-btn').innerHTML = isSetup ? ICON_GO : ICON_UNLOCK
  document.querySelector('.setup-only').classList.toggle('hidden', !isSetup)
  $('btn-bio').classList.toggle('hidden', isSetup || !bio.available || !bio.enabled)
  $('pw1').value = ''
  $('pw2').value = ''
  $('gate-err').textContent = ''
  syncGateAction()
}

function syncGateAction() {
  const ready = isSetup
    ? ($('pw1').value.length > 0 && $('pw2').value.length > 0)
    : $('pw2').value.length > 0
  $('gate-btn').classList.toggle('hidden', !ready)
}

async function tryBioUnlock() {
  $('gate-err').textContent = ''
  try {
    await call('unlock_bio')
    await showApp()
  } catch (e) { $('gate-err').textContent = e.message }
}

$('gate-form').addEventListener('submit', async (e) => {
  e.preventDefault()
  if ($('gate-btn').classList.contains('hidden')) return
  $('gate-err').textContent = ''
  try {
    if (isSetup) await call('setup', { password: $('pw1').value, confirm: $('pw2').value })
    else await call('unlock', { password: $('pw2').value })
    await showApp()
  } catch (err) { $('gate-err').textContent = err.message }
})

$('pw1').addEventListener('input', syncGateAction)
$('pw1').addEventListener('change', syncGateAction)
$('pw2').addEventListener('input', syncGateAction)
$('pw2').addEventListener('change', syncGateAction)
$('btn-lock').onclick = async () => {
  try {
    await call('lock')
  } catch (e) {
    $('gate-err').textContent = e.message
  }
  await showGate()
}
$('btn-bio').onclick = () => tryBioUnlock()
$('btn-add').onclick = () => openEditor(null)
$('btn-io').onclick = openTransfer
$('btn-set').onclick = openSettings
$('q').addEventListener('input', () => { state.q = $('q').value; paintList() })

async function reportActivity() {
  if ($('app').classList.contains('hidden')) return
  const now = Date.now()
  if (now - lastActivity < 1000) return
  lastActivity = now
  try {
    await call('activity')
  } catch (e) {
    if (String(e.message).includes('锁定')) await showGate()
  }
}
document.addEventListener('pointerdown', reportActivity, { passive: true })
document.addEventListener('keydown', reportActivity)
document.addEventListener('keydown', (e) => {
  if (e.key === 'Escape') {
    if (!$('prompt').classList.contains('hidden')) {
      e.preventDefault()
      dismissPrompt()
    } else if (!$('modal').classList.contains('hidden')) {
      e.preventDefault()
      closeModal()
    }
    return
  }
  if (e.key !== 'Tab' || $('prompt').classList.contains('hidden')) return
  const focusable = [...$('prompt-sheet').querySelectorAll('button:not(:disabled), input:not(:disabled), textarea:not(:disabled), select:not(:disabled), [tabindex]:not([tabindex="-1"])')]
  if (!focusable.length) return
  const first = focusable[0]
  const last = focusable[focusable.length - 1]
  if (e.shiftKey && document.activeElement === first) {
    e.preventDefault()
    last.focus()
  } else if (!e.shiftKey && document.activeElement === last) {
    e.preventDefault()
    first.focus()
  }
})
document.addEventListener('wheel', reportActivity, { passive: true })
document.addEventListener('touchmove', reportActivity, { passive: true })
$('modal').addEventListener('click', (e) => {
  if (e.target.id === 'modal' && $('prompt').classList.contains('hidden')) closeModal()
})
function currentWindow() {
  return window.__TAURI__?.window?.getCurrentWindow?.()
}
$('win-min').onclick = () => currentWindow()?.minimize()
$('win-max').onclick = () => currentWindow()?.toggleMaximize()
$('win-close').onclick = () => currentWindow()?.close()
$('list').addEventListener('click', async (e) => {
  const row = e.target.closest('.row')
  if (!row) return
  const id = row.dataset.id
  const act = e.target.closest('[data-act]')?.dataset.act
  if (act === 'toggle') {
    if (state.shown.has(id)) state.shown.delete(id)
    else state.shown.add(id)
    paintList()
    return
  }
  if (act === 'edit') {
    try {
      const res = await call('get_account', { id })
      openEditor(res.account)
    } catch (error) {
      if (String(error.message).includes('锁定')) await showGate()
    }
    return
  }
  if (act === 'del') {
    const acc = state.accounts.find((a) => a.id === id) || { id, name: row.querySelector('.name')?.textContent || '' }
    askDelete(acc)
    return
  }
  if (act === 'copy-email') {
    try {
      await copyEmail(row.dataset.email || '', e.target.closest('button') || row.querySelector('.copy-email'))
    } catch (_) {}
    return
  }
  try {
    await copyCode(id, e.target.closest('button.copy') || row.querySelector('.copy'))
  } catch (_) {}
})

window.addEventListener('DOMContentLoaded', () => {
  applyTheme(currentTheme())
  const boot = () => showGate().catch((e) => { $('gate-err').textContent = e.message })
  if (window.__TAURI__) boot()
  else setTimeout(boot, 80)
})
