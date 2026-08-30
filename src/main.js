const RING = 2 * Math.PI * 15.5
const COPY = '<svg width="15" height="15" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><rect x="9" y="9" width="11" height="11" rx="2"/><path d="M5 15V5h10"/></svg>'
const OK = '<svg width="15" height="15" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><path d="M5 12l5 5L20 7"/></svg>'
const EDIT = '<svg width="15" height="15" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><path d="M12 20h9"/><path d="M16.5 3.5a2.1 2.1 0 0 1 3 3L7 19l-4 1 1-4Z"/></svg>'

let state = { accounts: [], codes: {}, remain: 30, settings: {}, shown: new Set(), q: '' }
let timer = null
let clipTimer = null
let isSetup = false

function invoke(name, args) {
  return window.__TAURI__.core.invoke(name, args)
}

async function call(name, args = {}) {
  try {
    const res = await invoke(name, args)
    if (res && res.ok === false) throw new Error(res.error || '失败')
    return res || { ok: true }
  } catch (e) {
    throw new Error(String(e))
  }
}

const $ = (id) => document.getElementById(id)
const esc = (s) => String(s || '').replace(/[&<>"']/g, (c) => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c]))
const fmt = (code) => {
  const s = String(code || '')
  return s.length === 6 ? `${s.slice(0, 3)} ${s.slice(3)}` : s
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
  return out
}

function paintList() {
  const el = $('list')
  const gs = groups(state.accounts)
  if (!gs.length) {
    el.innerHTML = '<div class="empty">还没有账号。用右上角 QR 或 + 添加。</div>'
    return
  }
  const danger = state.remain <= 5
  el.innerHTML = gs.map((g) => `
    <div class="group">
      <div class="label">${esc(g.issuer)}</div>
      ${g.items.map((a) => {
        const shown = state.shown.has(a.id)
        const code = shown ? fmt(state.codes[a.id] || '') : '••• •••'
        const sub = [a.email, a.notes].filter(Boolean).join(' · ')
        return `<div class="row ${shown ? 'shown' : ''} ${danger && shown ? 'danger' : ''}" data-id="${a.id}">
          <div class="avatar">${esc((a.issuer || '?')[0])}</div>
          <div class="meta">
            <div class="name">${esc(a.name || a.issuer || '未命名')}</div>
            ${sub ? `<div class="note">${esc(sub)}</div>` : ''}
          </div>
          <div class="code-wrap" data-act="toggle"><div class="code">${code}</div></div>
          <button class="copy" type="button" data-act="copy">${COPY}</button>
          <button class="edit" type="button" data-act="edit">${EDIT}</button>
        </div>`
      }).join('')}
    </div>`).join('')
}

function paintRing() {
  const sec = state.remain
  const ring = $('ring')
  ring.className = 'ring' + (sec <= 5 ? ' danger' : sec <= 10 ? ' warn' : '')
  $('remain').textContent = String(sec)
  ring.querySelector('.prog').style.strokeDashoffset = String(RING * (1 - sec / 30))
}

async function refresh() {
  const snap = await call('snapshot')
  state.accounts = snap.accounts || []
  state.codes = snap.codes || {}
  state.remain = snap.remain || 30
  state.settings = snap.settings || {}
  paintRing()
  paintList()
}

async function copyCode(id, btn) {
  const code = state.codes[id]
  if (!code) return
  await navigator.clipboard.writeText(code)
  const row = btn.closest('.row')
  row.classList.add('copied')
  btn.innerHTML = OK
  setTimeout(() => { row.classList.remove('copied'); btn.innerHTML = COPY }, 900)
  const sec = Number(state.settings.clipboard_clear_seconds || 0)
  if (clipTimer) clearTimeout(clipTimer)
  if (sec > 0) clipTimer = setTimeout(() => navigator.clipboard.writeText('').catch(() => {}), sec * 1000)
}

function closeModal() {
  $('modal').classList.add('hidden')
  $('sheet').innerHTML = ''
}
function openModal(html) {
  $('sheet').innerHTML = html
  $('modal').classList.remove('hidden')
}
function field(name, label, value, extra = '') {
  return `<label>${label}<input name="${name}" value="${esc(value || '')}" ${extra} /></label>`
}

function openEditor(acc) {
  const a = acc || { issuer: '', name: '', email: '', notes: '', secret: '', algorithm: 'SHA1', digits: 6 }
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
    ${field('digits', '位数', a.digits, 'type="number" min="6" max="8"')}
    <p class="err" id="form-err"></p>
    <div class="row-btns">
      <button type="button" class="ghost" data-close>取消</button>
      ${acc ? '<button type="button" class="danger-btn" id="btn-del">删除</button>' : ''}
      <button type="button" class="primary" id="btn-save">保存</button>
    </div>`)
  $('sheet').querySelector('[data-close]').onclick = closeModal
  $('btn-save').onclick = async () => {
    const data = Object.fromEntries([...$('sheet').querySelectorAll('input,textarea,select')].map((n) => [n.name, n.name === 'digits' ? Number(n.value) : n.value]))
    try {
      if (acc) await call('update_account', { id: acc.id, data })
      else await call('add_account', { data })
      closeModal()
      await refresh()
    } catch (e) { $('form-err').textContent = e.message }
  }
  const del = $('btn-del')
  if (del) del.onclick = async () => {
    if (!confirm('删除这个账号？')) return
    await call('delete_account', { id: acc.id })
    closeModal()
    await refresh()
  }
}

function openImport() {
  openModal(`
    <h2>导入二维码</h2>
    <div class="drop" id="drop">把二维码图片拖到这里，或点击选择</div>
    <input id="file" type="file" accept="image/*" hidden />
    <label>也可以粘贴 otpauth 链接<input id="uri" spellcheck="false" placeholder="otpauth://totp/..." /></label>
    <p class="err" id="form-err"></p>
    <div class="row-btns">
      <button type="button" class="ghost" data-close>取消</button>
      <button type="button" class="primary" id="btn-uri">导入链接</button>
    </div>`)
  $('sheet').querySelector('[data-close]').onclick = closeModal
  const drop = $('drop')
  const file = $('file')
  drop.onclick = () => file.click()
  drop.addEventListener('dragover', (e) => { e.preventDefault(); drop.classList.add('over') })
  drop.addEventListener('dragleave', () => drop.classList.remove('over'))
  drop.addEventListener('drop', (e) => {
    e.preventDefault()
    drop.classList.remove('over')
    if (e.dataTransfer.files[0]) readQr(e.dataTransfer.files[0])
  })
  file.onchange = () => { if (file.files[0]) readQr(file.files[0]) }
  $('btn-uri').onclick = async () => {
    try {
      const res = await call('import_uri', { uri: $('uri').value })
      closeModal()
      await refresh()
      alert('已导入 ' + res.count + ' 个账号')
    } catch (e) { $('form-err').textContent = e.message }
  }
}

function readQr(file) {
  const reader = new FileReader()
  reader.onload = async () => {
    try {
      const res = await call('import_qr', { imageB64: reader.result })
      closeModal()
      await refresh()
      alert('已导入 ' + res.count + ' 个账号')
    } catch (e) { $('form-err').textContent = e.message }
  }
  reader.readAsDataURL(file)
}

function openSettings() {
  const s = state.settings || {}
  openModal(`
    <h2>设置</h2>
    ${field('webdav_url', 'WebDAV 地址', s.webdav_url, 'placeholder="https://dav.example.com/"')}
    ${field('webdav_user', 'WebDAV 用户名', s.webdav_user)}
    ${field('webdav_password', 'WebDAV 密码', '', 'type="password" placeholder="' + (s.webdav_has_password ? '已保存，留空不改' : '') + '"')}
    ${field('webdav_path', '远程文件路径', s.webdav_path || '/authenticator/vault.enc')}
    ${field('autolock_seconds', '自动锁定（秒，0 关闭）', s.autolock_seconds, 'type="number" min="0"')}
    ${field('clipboard_clear_seconds', '复制后清空剪贴板（秒，0 关闭）', s.clipboard_clear_seconds, 'type="number" min="0"')}
    <h2 style="margin-top:18px">修改主密码</h2>
    ${field('old', '当前主密码', '', 'type="password"')}
    ${field('newpw', '新主密码', '', 'type="password"')}
    ${field('new2', '确认新密码', '', 'type="password"')}
    <p class="err" id="form-err"></p>
    <div class="row-btns">
      <button type="button" class="ghost" data-close>关闭</button>
      <button type="button" class="ghost" id="btn-up">上传 WebDAV</button>
    </div>
    <div class="row-btns">
      <button type="button" class="ghost" id="btn-down">从 WebDAV 拉取</button>
      <button type="button" class="primary" id="btn-save-set">保存设置</button>
    </div>
    <div class="row-btns"><button type="button" class="danger-btn" id="btn-pw">更新主密码</button></div>`)
  $('sheet').querySelector('[data-close]').onclick = closeModal
  const val = (name) => $('sheet').querySelector('[name="' + name + '"]').value
  $('btn-save-set').onclick = async () => {
    try {
      await call('save_settings', { data: {
        webdav_url: val('webdav_url'),
        webdav_user: val('webdav_user'),
        webdav_password: val('webdav_password'),
        webdav_path: val('webdav_path'),
        autolock_seconds: Number(val('autolock_seconds') || 0),
        clipboard_clear_seconds: Number(val('clipboard_clear_seconds') || 0),
      }})
      await refresh()
      $('form-err').textContent = '设置已保存'
    } catch (e) { $('form-err').textContent = e.message }
  }
  $('btn-up').onclick = async () => {
    try { await call('webdav_upload'); $('form-err').textContent = '已上传加密保险库' }
    catch (e) { $('form-err').textContent = e.message }
  }
  $('btn-down').onclick = async () => {
    try { await call('webdav_download', { password: '' }); await refresh(); $('form-err').textContent = '已从远程覆盖本地' }
    catch (e) { $('form-err').textContent = e.message }
  }
  $('btn-pw').onclick = async () => {
    try { await call('change_password', { old: val('old'), newPassword: val('newpw'), confirm: val('new2') }); $('form-err').textContent = '主密码已更新' }
    catch (e) { $('form-err').textContent = e.message }
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
  const st = await call('status')
  isSetup = !st.exists
  $('gate-sub').textContent = isSetup ? '首次使用，请设置主密码（至少 8 位）' : '输入主密码解锁'
  $('gate-btn').textContent = isSetup ? '创建保险库' : '解锁'
  document.querySelector('.setup-only').classList.toggle('hidden', !isSetup)
  $('pw1').value = ''
  $('pw2').value = ''
  $('gate-err').textContent = ''
}

$('gate-form').addEventListener('submit', async (e) => {
  e.preventDefault()
  $('gate-err').textContent = ''
  try {
    if (isSetup) await call('setup', { password: $('pw1').value, confirm: $('pw2').value })
    else await call('unlock', { password: $('pw2').value })
    await showApp()
  } catch (err) { $('gate-err').textContent = err.message }
})

$('btn-lock').onclick = async () => { await call('lock'); await showGate() }
$('btn-add').onclick = () => openEditor(null)
$('btn-qr').onclick = openImport
$('btn-set').onclick = openSettings
$('q').addEventListener('input', () => { state.q = $('q').value; paintList() })
$('modal').addEventListener('click', (e) => { if (e.target.id === 'modal') closeModal() })
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
    const res = await call('get_account', { id })
    openEditor(res.account)
    return
  }
  copyCode(id, e.target.closest('.copy') || row.querySelector('.copy'))
})

window.addEventListener('DOMContentLoaded', () => {
  const boot = () => showGate().catch((e) => { $('gate-err').textContent = e.message })
  if (window.__TAURI__) boot()
  else setTimeout(boot, 80)
})
