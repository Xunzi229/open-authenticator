const RING = 2 * Math.PI * 15.5
const COPY = '<svg width="15" height="15" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><rect x="9" y="9" width="11" height="11" rx="2"/><path d="M5 15V5h10"/></svg>'
const OK = '<svg width="15" height="15" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><path d="M5 12l5 5L20 7"/></svg>'
const EDIT = '<svg width="15" height="15" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><path d="M12 20h9"/><path d="M16.5 3.5a2.1 2.1 0 0 1 3 3L7 19l-4 1 1-4Z"/></svg>'
const DEL = '<svg width="15" height="15" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><path d="M4 7h16"/><path d="M9 7V5h6v2"/><path d="M7 7l1 13h8l1-13"/></svg>'

let state = { accounts: [], codes: {}, remain: 30, settings: {}, shown: new Set(), q: '', export: null, qrPage: 0 }
let bio = { available: false, enabled: false }
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
function hue(s) {
  let h = 0
  for (const c of String(s || '')) h = (h * 33 + c.charCodeAt(0)) >>> 0
  return h % 360
}
function avatarStyle(name) {
  const h = hue(name)
  return `background:linear-gradient(180deg,hsl(${h} 42% 46%),hsl(${h} 48% 32%))`
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
    el.innerHTML = '<div class="empty"><strong>还没有账号</strong>用右上角 + 添加，或从导入导出里扫入 Google 验证器二维码。</div>'
    return
  }
  const danger = state.remain <= 5
  el.innerHTML = gs.map((g) => `
    <div class="group">
      <div class="label">${esc(g.issuer)}</div>
      <div class="card-list">
      ${g.items.map((a) => {
        const shown = state.shown.has(a.id)
        const code = shown ? fmt(state.codes[a.id] || '') : '••• •••'
        const sub = [a.email, a.notes].filter(Boolean).join(' · ')
        return `<div class="row ${shown ? 'shown' : ''} ${danger && shown ? 'danger' : ''}" data-id="${a.id}">
          <div class="avatar" style="${avatarStyle(a.issuer || a.name)}">${esc((a.issuer || a.name || '?')[0])}</div>
          <div class="meta">
            <div class="name">${esc(a.name || a.issuer || '未命名')}</div>
            ${sub ? `<div class="note">${esc(sub)}</div>` : ''}
          </div>
          <div class="code-wrap" data-act="toggle"><div class="code">${code}</div></div>
          <button class="copy" type="button" data-act="copy">${COPY}</button>
          <button class="edit" type="button" data-act="edit">${EDIT}</button>
          <button class="del" type="button" data-act="del">${DEL}</button>
        </div>`
      }).join('')}
      </div>
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

function closePrompt() {
  $('prompt').classList.add('hidden')
  $('prompt-sheet').innerHTML = ''
  $('prompt').onclick = null
}
function openPrompt(html) {
  $('prompt-sheet').innerHTML = html
  $('prompt').classList.remove('hidden')
}
function closeModal() {
  closePrompt()
  $('modal').classList.add('hidden')
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
  $('modal').classList.remove('hidden')
}
function askAuth(hint, run) {
  return new Promise((resolve) => {
    let done = false
    openPrompt(`
      <h2>验证身份</h2>
      <p class="hint">${esc(hint)}</p>
      ${field('auth-pw', '主密码', '', 'type="password" autocomplete="current-password"')}
      <p class="err" id="auth-err"></p>
      <div class="row-btns">
        <button type="button" class="ghost" id="auth-cancel">取消</button>
        ${bio.enabled ? '<button type="button" class="ghost" id="auth-bio">指纹验证</button>' : ''}
        <button type="button" class="primary" id="auth-ok">验证</button>
      </div>
    `)
    const finish = (value) => {
      if (done) return
      done = true
      closePrompt()
      resolve(value)
    }
    $('auth-cancel').onclick = () => finish(null)
    $('prompt').onclick = (e) => { if (e.target.id === 'prompt') finish(null) }
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
    input?.addEventListener('keydown', (e) => { if (e.key === 'Enter') { e.preventDefault(); go(false) } })
    input?.focus()
  })
}
function showAccountQr(svg) {
  openPrompt(`
    <h2>账号二维码</h2>
    <p class="hint">可供其他验证器扫描</p>
    <div class="qr-box">${svg}</div>
    <div class="row-btns"><button type="button" class="ghost" id="qr-done">关闭</button></div>
  `)
  $('qr-done').onclick = closePrompt
  $('prompt').onclick = (e) => { if (e.target.id === 'prompt') closePrompt() }
}
function field(name, label, value, extra = '') {
  return `<label>${label}<input name="${name}" value="${esc(value || '')}" ${extra} /></label>`
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
    const data = Object.fromEntries([...$('sheet').querySelectorAll('input,textarea,select')].map((n) => [n.name, n.name === 'digits' ? Number(n.value) : n.value]))
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
    box.innerHTML = '<p class="hint">没有可导出的账号</p>'
    return
  }
  const n = data.qrs.length
  if (state.qrPage >= n) state.qrPage = 0
  const cur = data.qrs[state.qrPage]
  box.innerHTML = `
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
    ${field('export-pw', '主密码', '', 'type="password" autocomplete="current-password"')}
    ${bio.enabled ? '<div class="row-btns tight"><button type="button" class="ghost" id="btn-export-bio">使用指纹验证</button></div>' : ''}
    <div class="row-btns tight"><button type="button" class="primary" id="btn-export-auth">验证并显示</button></div>`
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
  alert(count ? '已导入 ' + count + ' 个账号' : '没有新账号，重复项已跳过')
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

function renderSettings(s) {
  openModal(`
    <h2>设置</h2>
    <p class="sec-title">常规</p>
    <section class="block">
      <div class="grid-2">
        ${field('autolock_seconds', '自动锁定（秒）', s.autolock_seconds, 'type="number" min="0"')}
        ${field('clipboard_clear_seconds', '清空剪贴板（秒）', s.clipboard_clear_seconds, 'type="number" min="0"')}
      </div>
      <p class="hint">填 0 表示关闭该项。</p>
    </section>
    <p class="sec-title">系统解锁</p>
    <section class="block">
      ${bio.available ? `
        <p class="hint">${bio.enabled ? '已开启。锁定后可用指纹 / Windows Hello 解锁，查看二维码和更改密钥也可指纹验证。' : '开启后可用 Windows Hello 或指纹解锁，以及二次验证。'}</p>
        ${bio.enabled
          ? '<div class="row-btns tight"><button type="button" class="ghost" id="btn-bio-off">关闭指纹解锁</button></div>'
          : `${field('bio-pw', '主密码', '', 'type="password" autocomplete="current-password"')}<div class="row-btns tight"><button type="button" id="btn-bio-on">开启指纹解锁</button></div>`}
      ` : '<p class="hint">当前设备没有可用的指纹或 Windows Hello。</p>'}
    </section>
    <p class="sec-title">WebDAV 同步</p>
    <section class="block">
      ${field('webdav_url', '服务器地址', s.webdav_url, 'placeholder="https://dav.example.com/"')}
      <div class="grid-2">
        ${field('webdav_user', '用户名', s.webdav_user)}
        ${field('webdav_password', '密码', '', 'type="password" placeholder="' + (s.webdav_has_password ? '已保存，留空不改' : '') + '"')}
      </div>
      ${field('webdav_path', '远程文件', s.webdav_path || '/authenticator/vault.enc')}
      <div class="row-btns tight">
        <button type="button" class="ghost" id="btn-down">拉取</button>
        <button type="button" class="ghost" id="btn-up">上传</button>
      </div>
    </section>
    <p class="sec-title">主密码</p>
    <section class="block">
      ${field('old', '当前密码', '', 'type="password"')}
      ${field('newpw', '新密码', '', 'type="password"')}
      ${field('new2', '确认新密码', '', 'type="password"')}
      <div class="row-btns tight"><button type="button" class="danger-btn" id="btn-pw">更新主密码</button></div>
    </section>
    <p class="err" id="form-err"></p>
    <div class="row-btns sheet-actions">
      <button type="button" class="ghost" data-close>关闭</button>
      <button type="button" class="primary" id="btn-save-set">保存设置</button>
    </div>`)
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
  const bioOn = $('btn-bio-on')
  if (bioOn) bioOn.onclick = async () => {
    $('form-err').classList.remove('ok')
    try {
      await call('bio_enable', { password: val('bio-pw') })
      bio.enabled = true
      $('form-err').classList.add('ok')
      $('form-err').textContent = '已开启指纹解锁'
    } catch (e) { $('form-err').textContent = e.message }
  }
  const bioOff = $('btn-bio-off')
  if (bioOff) bioOff.onclick = async () => {
    $('form-err').classList.remove('ok')
    try {
      await call('bio_disable')
      bio.enabled = false
      $('form-err').classList.add('ok')
      $('form-err').textContent = '已关闭指纹解锁'
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
  const st = await call('status')
  isSetup = !st.exists
  bio.available = !!st.bio_available
  bio.enabled = !!st.bio_enabled
  $('gate-sub').textContent = isSetup ? '首次使用，请设置主密码（至少 8 位）' : (bio.enabled ? '指纹或主密码解锁' : '输入主密码解锁')
  $('gate-btn').textContent = isSetup ? '创建保险库' : '解锁'
  document.querySelector('.setup-only').classList.toggle('hidden', !isSetup)
  $('btn-bio').classList.toggle('hidden', isSetup || !bio.enabled)
  $('pw1').value = ''
  $('pw2').value = ''
  $('gate-err').textContent = ''
  if (!isSetup && bio.enabled) tryBioUnlock()
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
  $('gate-err').textContent = ''
  try {
    if (isSetup) await call('setup', { password: $('pw1').value, confirm: $('pw2').value })
    else await call('unlock', { password: $('pw2').value })
    await showApp()
  } catch (err) { $('gate-err').textContent = err.message }
})

$('btn-lock').onclick = async () => { await call('lock'); await showGate() }
$('btn-bio').onclick = () => tryBioUnlock()
$('btn-add').onclick = () => openEditor(null)
$('btn-io').onclick = openTransfer
$('btn-set').onclick = openSettings
$('q').addEventListener('input', () => { state.q = $('q').value; paintList() })
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
    const res = await call('get_account', { id })
    openEditor(res.account)
    return
  }
  if (act === 'del') {
    const acc = state.accounts.find((a) => a.id === id) || { id, name: row.querySelector('.name')?.textContent || '' }
    askDelete(acc)
    return
  }
  copyCode(id, e.target.closest('.copy') || row.querySelector('.copy'))
})

window.addEventListener('DOMContentLoaded', () => {
  const boot = () => showGate().catch((e) => { $('gate-err').textContent = e.message })
  if (window.__TAURI__) boot()
  else setTimeout(boot, 80)
})
