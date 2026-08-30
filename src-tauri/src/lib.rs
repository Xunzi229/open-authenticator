mod bio;
mod crypto;
mod qr;
mod totp;
mod vault;
mod webdav;

use std::io::Write;
use std::path::Path;
use std::sync::atomic::{AtomicBool, Ordering};
use std::sync::Mutex;

use base64::Engine;
use serde::Deserialize;
use serde_json::{json, Value};
use tauri::menu::{Menu, MenuItem};
use tauri::tray::{MouseButton, MouseButtonState, TrayIconBuilder, TrayIconEvent};
use tauri::{Manager, State, WebviewWindow};
use vault::{Account, Vault, MIN_PASSWORD};
use zeroize::{Zeroize, Zeroizing};

static QUITTING: AtomicBool = AtomicBool::new(false);

fn show_main(app: &tauri::AppHandle) {
    if let Some(win) = app.get_webview_window("main") {
        let _ = win.unminimize();
        let _ = win.show();
        let _ = win.set_focus();
    }
}

fn quit_app(app: &tauri::AppHandle) {
    QUITTING.store(true, Ordering::Relaxed);
    app.exit(0);
}

fn setup_tray(app: &tauri::App) -> tauri::Result<()> {
    let show = MenuItem::with_id(app, "show", "显示窗口", true, None::<&str>)?;
    let quit = MenuItem::with_id(app, "quit", "退出", true, None::<&str>)?;
    let menu = Menu::with_items(app, &[&show, &quit])?;
    let icon = app
        .default_window_icon()
        .cloned()
        .expect("missing window icon");
    TrayIconBuilder::with_id("tray")
        .tooltip("验证器")
        .icon(icon)
        .menu(&menu)
        .show_menu_on_left_click(false)
        .on_menu_event(|app, event| match event.id.as_ref() {
            "show" => show_main(app),
            "quit" => quit_app(app),
            _ => {}
        })
        .on_tray_icon_event(|tray, event| {
            if let TrayIconEvent::Click {
                button: MouseButton::Left,
                button_state: MouseButtonState::Up,
                ..
            } = event
            {
                show_main(tray.app_handle());
            }
        })
        .build(app)?;
    Ok(())
}

pub struct AppState {
    vault: Mutex<Vault>,
}

fn window_hwnd(window: &WebviewWindow) -> isize {
    #[cfg(windows)]
    {
        window
            .hwnd()
            .ok()
            .map(|h| {
                let p: *mut std::ffi::c_void = unsafe { std::mem::transmute_copy(&h) };
                p as isize
            })
            .unwrap_or(0)
    }
    #[cfg(not(windows))]
    {
        let _ = window;
        0
    }
}

fn bio_password(window: &WebviewWindow, message: &str) -> Result<Zeroizing<String>, String> {
    bio::prompt(window_hwnd(window), message)?;
    bio::take().map(Zeroizing::new)
}

fn resolve_password(
    window: &WebviewWindow,
    password: String,
    biometric: bool,
    message: &str,
) -> Result<Zeroizing<String>, String> {
    if biometric {
        bio_password(window, message)
    } else {
        Ok(Zeroizing::new(password))
    }
}

fn ok(extra: Value) -> Result<Value, String> {
    let mut v = extra;
    if let Some(obj) = v.as_object_mut() {
        obj.insert("ok".into(), json!(true));
        Ok(v)
    } else {
        Ok(json!({ "ok": true }))
    }
}

#[tauri::command]
fn status(state: State<AppState>) -> Result<Value, String> {
    let mut v = state.vault.lock().map_err(|e| e.to_string())?;
    v.check_timeout();
    ok(json!({
        "exists": v.exists(),
        "unlocked": v.unlocked(),
        "min_password": MIN_PASSWORD,
        "bio_available": bio::available(),
        "bio_enabled": bio::enabled()
    }))
}

#[tauri::command]
fn setup(state: State<AppState>, password: String, confirm: String) -> Result<Value, String> {
    let password = Zeroizing::new(password);
    let confirm = Zeroizing::new(confirm);
    state
        .vault
        .lock()
        .map_err(|e| e.to_string())?
        .setup(&password, &confirm)?;
    ok(json!({}))
}

#[tauri::command]
fn unlock(state: State<AppState>, password: String) -> Result<Value, String> {
    let password = Zeroizing::new(password);
    state
        .vault
        .lock()
        .map_err(|e| e.to_string())?
        .unlock(&password)?;
    ok(json!({}))
}

#[tauri::command]
fn lock(state: State<AppState>) -> Result<Value, String> {
    state.vault.lock().map_err(|e| e.to_string())?.lock();
    ok(json!({}))
}

#[tauri::command]
fn activity(state: State<AppState>) -> Result<Value, String> {
    state.vault.lock().map_err(|e| e.to_string())?.activity()?;
    ok(json!({}))
}

#[tauri::command]
fn snapshot(state: State<AppState>) -> Result<Value, String> {
    let mut v = state.vault.lock().map_err(|e| e.to_string())?;
    let accounts = v.accounts()?;
    let settings = v.settings()?;
    let mut codes = serde_json::Map::new();
    let mut remains = serde_json::Map::new();
    let mut pub_acc = Vec::with_capacity(accounts.len());
    for a in &accounts {
        codes.insert(
            a.id.clone(),
            json!(totp::totp(&a.secret, a.digits, &a.algorithm, a.period)?),
        );
        remains.insert(a.id.clone(), json!(totp::remain(a.period)));
        pub_acc.push(json!({
            "id": a.id,
            "issuer": a.issuer,
            "name": a.name,
            "email": a.email,
            "notes": a.notes,
            "algorithm": a.algorithm,
            "digits": a.digits,
            "period": a.period
        }));
    }
    ok(json!({
        "accounts": pub_acc,
        "codes": codes,
        "remains": remains,
        "settings": {
            "webdav_url": settings.webdav_url,
            "webdav_user": settings.webdav_user,
            "webdav_path": settings.webdav_path,
            "webdav_has_password": !settings.webdav_password.is_empty(),
            "autolock_seconds": settings.autolock_seconds,
            "clipboard_clear_seconds": settings.clipboard_clear_seconds
        }
    }))
}

#[tauri::command]
fn get_account(state: State<AppState>, id: String) -> Result<Value, String> {
    let acc = state.vault.lock().map_err(|e| e.to_string())?.get(&id)?;
    ok(json!({ "account": account_view(acc) }))
}

fn account_view(acc: Account) -> Value {
    json!({
        "id": acc.id,
        "issuer": acc.issuer,
        "name": acc.name,
        "email": acc.email,
        "notes": acc.notes,
        "algorithm": acc.algorithm,
        "digits": acc.digits,
        "period": acc.period
    })
}

fn account_from(v: Value) -> Account {
    Account {
        id: String::new(),
        issuer: v["issuer"].as_str().unwrap_or("").into(),
        name: v["name"].as_str().unwrap_or("").into(),
        email: v["email"].as_str().unwrap_or("").into(),
        notes: v["notes"].as_str().unwrap_or("").into(),
        secret: v["secret"].as_str().unwrap_or("").into(),
        algorithm: v["algorithm"].as_str().unwrap_or("SHA1").into(),
        digits: v["digits"].as_u64().unwrap_or(6) as u32,
        period: v["period"].as_u64().unwrap_or(30),
        created: 0,
        updated: 0,
    }
}

#[tauri::command]
fn add_account(state: State<AppState>, data: Value) -> Result<Value, String> {
    let acc = state
        .vault
        .lock()
        .map_err(|e| e.to_string())?
        .add(account_from(data))?;
    ok(json!({ "id": acc.id }))
}

#[tauri::command]
fn update_account(
    window: WebviewWindow,
    state: State<AppState>,
    id: String,
    data: Value,
    password: String,
    biometric: bool,
) -> Result<Value, String> {
    let mut v = state.vault.lock().map_err(|e| e.to_string())?;
    if !data["secret"].as_str().unwrap_or("").trim().is_empty() {
        drop(v);
        let pw = resolve_password(&window, password, biometric, "验证身份以更改密钥")?;
        let mut v = state.vault.lock().map_err(|e| e.to_string())?;
        v.verify_password(&pw)?;
        v.update(&id, account_from(data))?;
        return ok(json!({}));
    }
    v.update(&id, account_from(data))?;
    ok(json!({}))
}

#[tauri::command]
fn delete_account(state: State<AppState>, id: String) -> Result<Value, String> {
    state.vault.lock().map_err(|e| e.to_string())?.delete(&id)?;
    ok(json!({}))
}

#[tauri::command]
fn import_uri(state: State<AppState>, uri: String) -> Result<Value, String> {
    if uri.len() > 2 * 1024 * 1024 {
        return Err("导入内容过大".into());
    }
    let items = qr::parse_uri(&uri)?;
    let count = state
        .vault
        .lock()
        .map_err(|e| e.to_string())?
        .add_many(items)?;
    ok(json!({ "count": count }))
}

#[tauri::command]
fn import_qr(state: State<AppState>, image_b64: String) -> Result<Value, String> {
    const MAX_IMAGE_B64: usize = 14 * 1024 * 1024;
    if image_b64.len() > MAX_IMAGE_B64 {
        return Err("二维码图片不能超过 10 MiB".into());
    }
    let raw = image_b64.split(',').next_back().unwrap_or(&image_b64);
    let bytes = base64::engine::general_purpose::STANDARD
        .decode(raw)
        .map_err(|_| "图片解码失败")?;
    if bytes.len() > 10 * 1024 * 1024 {
        return Err("二维码图片不能超过 10 MiB".into());
    }
    let items = qr::decode_image(&bytes)?;
    let count = state
        .vault
        .lock()
        .map_err(|e| e.to_string())?
        .add_many(items)?;
    ok(json!({ "count": count }))
}

fn to_qr(a: &Account) -> qr::QrAccount {
    qr::QrAccount {
        issuer: a.issuer.clone(),
        name: a.name.clone(),
        secret: a.secret.clone(),
        algorithm: a.algorithm.clone(),
        digits: a.digits,
        period: a.period,
    }
}

#[tauri::command]
fn import_text(state: State<AppState>, text: String) -> Result<Value, String> {
    if text.len() > 16 * 1024 * 1024 {
        return Err("导入文件不能超过 16 MiB".into());
    }
    let items = qr::parse_text(&text)?;
    let count = state
        .vault
        .lock()
        .map_err(|e| e.to_string())?
        .add_many(items)?;
    ok(json!({ "count": count }))
}

#[tauri::command]
fn export_data(
    window: WebviewWindow,
    state: State<AppState>,
    password: String,
    biometric: bool,
) -> Result<Value, String> {
    let pw = resolve_password(&window, password, biometric, "验证身份以查看导出二维码")?;
    let mut v = state.vault.lock().map_err(|e| e.to_string())?;
    v.verify_password(&pw)?;
    let accounts = v.accounts()?;
    let qrs: Vec<qr::QrAccount> = accounts.iter().map(to_qr).collect();
    let json_acc: Vec<Value> = qrs
        .iter()
        .map(|a| {
            json!({
                "issuer": a.issuer,
                "name": a.name,
                "secret": a.secret,
                "algorithm": a.algorithm,
                "digits": a.digits,
                "period": a.period,
                "uri": qr::otpauth_uri(a)
            })
        })
        .collect();
    let txt = qrs
        .iter()
        .map(qr::otpauth_uri)
        .collect::<Vec<_>>()
        .join("\n");
    let migration_accounts: Vec<qr::QrAccount> = qrs
        .iter()
        .filter(|account| account.period == 30)
        .cloned()
        .collect();
    let excluded = qrs.len() - migration_accounts.len();
    let images = if migration_accounts.is_empty() {
        vec![]
    } else {
        qr::migration_qrs(&migration_accounts)?
            .into_iter()
            .enumerate()
            .map(|(i, (svg, count))| json!({ "svg": svg, "index": i, "count": count }))
            .collect()
    };
    ok(json!({
        "json": serde_json::to_string_pretty(&json_acc).unwrap_or_default(),
        "txt": txt,
        "qrs": images,
        "total": qrs.len(),
        "migration_warning": if excluded > 0 {
            format!("有 {excluded} 个非 30 秒周期账号未包含在 Google 转移二维码中，请使用 JSON 或链接备份")
        } else {
            String::new()
        }
    }))
}

#[tauri::command]
fn account_qr(
    window: WebviewWindow,
    state: State<AppState>,
    id: String,
    password: String,
    biometric: bool,
) -> Result<Value, String> {
    let pw = resolve_password(&window, password, biometric, "验证身份以查看账号二维码")?;
    let mut v = state.vault.lock().map_err(|e| e.to_string())?;
    v.verify_password(&pw)?;
    let acc = v.get(&id)?;
    let q = to_qr(&acc);
    let uri = qr::otpauth_uri(&q);
    ok(json!({ "uri": uri, "svg": qr::qr_svg(&uri)? }))
}

#[derive(Deserialize)]
struct SettingsIn {
    webdav_url: Option<String>,
    webdav_user: Option<String>,
    webdav_password: Option<String>,
    webdav_path: Option<String>,
    autolock_seconds: Option<u64>,
    clipboard_clear_seconds: Option<u64>,
    clear_webdav_password: Option<bool>,
}

#[tauri::command]
fn save_settings(state: State<AppState>, data: SettingsIn) -> Result<Value, String> {
    let mut v = state.vault.lock().map_err(|e| e.to_string())?;
    let mut s = v.settings()?;
    if let Some(x) = data.webdav_url {
        s.webdav_url = x;
    }
    if let Some(x) = data.webdav_user {
        s.webdav_user = x;
    }
    if data.clear_webdav_password.unwrap_or(false) {
        s.webdav_password.clear();
    } else if let Some(x) = data.webdav_password {
        s.webdav_password = x;
    }
    if let Some(x) = data.webdav_path {
        s.webdav_path = x;
    }
    if let Some(x) = data.autolock_seconds {
        s.autolock_seconds = x;
    }
    if let Some(x) = data.clipboard_clear_seconds {
        s.clipboard_clear_seconds = x;
    }
    v.update_settings(s, !data.clear_webdav_password.unwrap_or(false))?;
    ok(json!({}))
}

#[tauri::command]
fn change_password(
    state: State<AppState>,
    old: String,
    new_password: String,
    confirm: String,
) -> Result<Value, String> {
    let old = Zeroizing::new(old);
    let new_password = Zeroizing::new(new_password);
    let confirm = Zeroizing::new(confirm);
    let mut old_bio = bio::stored_password()?;
    if old_bio.is_some() {
        bio::store(&new_password)?;
    }
    if let Err(error) = state
        .vault
        .lock()
        .map_err(|e| e.to_string())?
        .change_password(&old, &new_password, &confirm)
    {
        if let Some(password) = old_bio.as_deref() {
            bio::store(password)
                .map_err(|rollback| format!("{error}；恢复系统凭据失败：{rollback}"))?;
        }
        if let Some(password) = old_bio.as_mut() {
            password.zeroize();
        }
        return Err(error);
    }
    if let Some(password) = old_bio.as_mut() {
        password.zeroize();
    }
    ok(json!({}))
}

#[tauri::command]
fn save_text(name: String, content: String) -> Result<Value, String> {
    let file = Path::new(&name)
        .file_name()
        .ok_or_else(|| "文件名无效".to_string())?
        .to_string_lossy();
    if file.is_empty() {
        return Err("文件名无效".into());
    }
    let dir = dirs::download_dir().ok_or_else(|| "找不到下载文件夹".to_string())?;
    std::fs::create_dir_all(&dir).map_err(|e| e.to_string())?;
    let source = Path::new(file.as_ref());
    let stem = source
        .file_stem()
        .and_then(|value| value.to_str())
        .unwrap_or("authenticator");
    let extension = source.extension().and_then(|value| value.to_str());
    let mut saved = None;
    for index in 0..1000 {
        let candidate = if index == 0 {
            file.to_string()
        } else if let Some(extension) = extension {
            format!("{stem} ({index}).{extension}")
        } else {
            format!("{stem} ({index})")
        };
        let path = dir.join(candidate);
        match std::fs::OpenOptions::new()
            .create_new(true)
            .write(true)
            .open(&path)
        {
            Ok(mut output) => {
                output
                    .write_all(content.as_bytes())
                    .map_err(|e| e.to_string())?;
                output.sync_all().map_err(|e| e.to_string())?;
                saved = Some(path);
                break;
            }
            Err(error) if error.kind() == std::io::ErrorKind::AlreadyExists => continue,
            Err(error) => return Err(error.to_string()),
        }
    }
    let path = saved.ok_or_else(|| "无法生成不重复的导出文件名".to_string())?;
    ok(json!({ "path": path.to_string_lossy() }))
}

#[tauri::command]
async fn webdav_upload(state: State<'_, AppState>) -> Result<Value, String> {
    let (s, blob) = {
        let mut v = state.vault.lock().map_err(|e| e.to_string())?;
        (v.settings()?, v.encrypted_bytes()?)
    };
    let expected_etag = (!s.webdav_etag.is_empty()).then(|| s.webdav_etag.clone());
    let etag = tauri::async_runtime::spawn_blocking(move || {
        webdav::put(
            &s.webdav_url,
            &s.webdav_path,
            &s.webdav_user,
            &s.webdav_password,
            &blob,
            expected_etag.as_deref(),
        )
    })
    .await
    .map_err(|e| e.to_string())??;
    state
        .vault
        .lock()
        .map_err(|e| e.to_string())?
        .set_webdav_etag(etag)?;
    ok(json!({}))
}

#[tauri::command]
async fn webdav_download(state: State<'_, AppState>, password: String) -> Result<Value, String> {
    let (s, pw) = {
        let mut v = state.vault.lock().map_err(|e| e.to_string())?;
        let s = v.settings()?;
        let pw = if password.is_empty() {
            v.password().ok_or_else(|| "请先解锁".to_string())?
        } else {
            Zeroizing::new(password)
        };
        (s, pw)
    };
    let download = tauri::async_runtime::spawn_blocking(move || {
        webdav::get(
            &s.webdav_url,
            &s.webdav_path,
            &s.webdav_user,
            &s.webdav_password,
        )
    })
    .await
    .map_err(|e| e.to_string())??;
    state
        .vault
        .lock()
        .map_err(|e| e.to_string())?
        .replace_bytes(&download.bytes, &pw, &download.etag)?;
    ok(json!({}))
}

#[tauri::command]
fn bio_status() -> Result<Value, String> {
    ok(json!({
        "available": bio::available(),
        "enabled": bio::enabled()
    }))
}

#[tauri::command]
fn bio_enable(
    window: WebviewWindow,
    state: State<AppState>,
    password: String,
) -> Result<Value, String> {
    let password = Zeroizing::new(password);
    {
        let mut v = state.vault.lock().map_err(|e| e.to_string())?;
        v.verify_password(&password)?;
    }
    bio::prompt(window_hwnd(&window), "开启指纹解锁")?;
    bio::store(&password)?;
    ok(json!({ "enabled": true }))
}

#[tauri::command]
fn bio_disable() -> Result<Value, String> {
    bio::clear()?;
    ok(json!({ "enabled": false }))
}

#[tauri::command]
fn unlock_bio(window: WebviewWindow, state: State<AppState>) -> Result<Value, String> {
    let pw = bio_password(&window, "解锁验证器")?;
    state.vault.lock().map_err(|e| e.to_string())?.unlock(&pw)?;
    ok(json!({}))
}

#[cfg_attr(mobile, tauri::mobile_entry_point)]
pub fn run() {
    tauri::Builder::default()
        .plugin(tauri_plugin_single_instance::init(|app, _args, _cwd| {
            show_main(app);
        }))
        .manage(AppState {
            vault: Mutex::new(Vault::new(Vault::default_path())),
        })
        .setup(|app| {
            setup_tray(app)?;
            let handle = app.handle().clone();
            std::thread::spawn(move || {
                while !QUITTING.load(Ordering::Relaxed) {
                    std::thread::sleep(std::time::Duration::from_secs(1));
                    if let Ok(mut vault) = handle.state::<AppState>().vault.lock() {
                        vault.check_timeout();
                    }
                }
            });
            Ok(())
        })
        .on_window_event(|window, event| {
            if let tauri::WindowEvent::CloseRequested { api, .. } = event {
                if !QUITTING.load(Ordering::Relaxed) {
                    api.prevent_close();
                    let _ = window.hide();
                }
            }
        })
        .invoke_handler(tauri::generate_handler![
            status,
            setup,
            unlock,
            lock,
            activity,
            snapshot,
            get_account,
            add_account,
            update_account,
            delete_account,
            import_uri,
            import_qr,
            import_text,
            export_data,
            account_qr,
            save_settings,
            change_password,
            webdav_upload,
            webdav_download,
            save_text,
            bio_status,
            bio_enable,
            bio_disable,
            unlock_bio
        ])
        .run(tauri::generate_context!())
        .expect("error while running tauri application");
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn editable_account_view_never_exposes_secret() {
        let account = Account {
            id: "id".into(),
            issuer: "Example".into(),
            name: "user".into(),
            email: String::new(),
            notes: String::new(),
            secret: "JBSWY3DPEHPK3PXP".into(),
            algorithm: "SHA1".into(),
            digits: 6,
            period: 30,
            created: 0,
            updated: 0,
        };
        assert!(account_view(account).get("secret").is_none());
    }
}
