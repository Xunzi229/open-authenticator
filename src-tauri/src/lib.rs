mod crypto;
mod qr;
mod totp;
mod vault;
mod webdav;

use std::sync::Mutex;

use base64::Engine;
use serde::Deserialize;
use serde_json::{json, Value};
use tauri::State;

use vault::{Account, Vault, MIN_PASSWORD};

pub struct AppState {
    vault: Mutex<Vault>,
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
    let v = state.vault.lock().map_err(|e| e.to_string())?;
    ok(json!({
        "exists": v.exists(),
        "unlocked": v.unlocked(),
        "min_password": MIN_PASSWORD
    }))
}

#[tauri::command]
fn setup(state: State<AppState>, password: String, confirm: String) -> Result<Value, String> {
    state.vault.lock().map_err(|e| e.to_string())?.setup(&password, &confirm)?;
    ok(json!({}))
}

#[tauri::command]
fn unlock(state: State<AppState>, password: String) -> Result<Value, String> {
    state.vault.lock().map_err(|e| e.to_string())?.unlock(&password)?;
    ok(json!({}))
}

#[tauri::command]
fn lock(state: State<AppState>) -> Result<Value, String> {
    state.vault.lock().map_err(|e| e.to_string())?.lock();
    ok(json!({}))
}

#[tauri::command]
fn snapshot(state: State<AppState>) -> Result<Value, String> {
    let mut v = state.vault.lock().map_err(|e| e.to_string())?;
    let accounts = v.accounts()?;
    let settings = v.settings()?;
    let mut codes = serde_json::Map::new();
    let mut remain = 30u64;
    let pub_acc: Vec<Value> = accounts
        .iter()
        .map(|a| {
            codes.insert(a.id.clone(), json!(totp::totp(&a.secret, a.digits, &a.algorithm, a.period)));
            remain = totp::remain(a.period);
            json!({
                "id": a.id,
                "issuer": a.issuer,
                "name": a.name,
                "email": a.email,
                "notes": a.notes,
                "algorithm": a.algorithm,
                "digits": a.digits,
                "period": a.period
            })
        })
        .collect();
    ok(json!({
        "accounts": pub_acc,
        "codes": codes,
        "remain": remain,
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
    ok(json!({ "account": acc }))
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
    let acc = state.vault.lock().map_err(|e| e.to_string())?.add(account_from(data))?;
    ok(json!({ "id": acc.id }))
}

#[tauri::command]
fn update_account(state: State<AppState>, id: String, data: Value) -> Result<Value, String> {
    state.vault.lock().map_err(|e| e.to_string())?.update(&id, account_from(data))?;
    ok(json!({}))
}

#[tauri::command]
fn delete_account(state: State<AppState>, id: String) -> Result<Value, String> {
    state.vault.lock().map_err(|e| e.to_string())?.delete(&id)?;
    ok(json!({}))
}

#[tauri::command]
fn import_uri(state: State<AppState>, uri: String) -> Result<Value, String> {
    let items = qr::parse_uri(&uri)?;
    let count = state.vault.lock().map_err(|e| e.to_string())?.add_many(items)?;
    ok(json!({ "count": count }))
}

#[tauri::command]
fn import_qr(state: State<AppState>, image_b64: String) -> Result<Value, String> {
    let raw = image_b64.split(',').next_back().unwrap_or(&image_b64);
    let bytes = base64::engine::general_purpose::STANDARD
        .decode(raw)
        .map_err(|_| "图片解码失败")?;
    let items = qr::decode_image(&bytes)?;
    let count = state.vault.lock().map_err(|e| e.to_string())?.add_many(items)?;
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
    let items = qr::parse_text(&text)?;
    let count = state.vault.lock().map_err(|e| e.to_string())?.add_many(items)?;
    ok(json!({ "count": count }))
}

#[tauri::command]
fn export_data(state: State<AppState>) -> Result<Value, String> {
    let mut v = state.vault.lock().map_err(|e| e.to_string())?;
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
    let txt = qrs.iter().map(qr::otpauth_uri).collect::<Vec<_>>().join("\n");
    let images = if qrs.is_empty() {
        vec![]
    } else {
        qr::migration_qrs(&qrs)?
            .into_iter()
            .enumerate()
            .map(|(i, (svg, count))| json!({ "svg": svg, "index": i, "count": count }))
            .collect()
    };
    ok(json!({
        "json": serde_json::to_string_pretty(&json_acc).unwrap_or_default(),
        "txt": txt,
        "qrs": images,
        "total": qrs.len()
    }))
}

#[tauri::command]
fn account_qr(state: State<AppState>, id: String) -> Result<Value, String> {
    let acc = state.vault.lock().map_err(|e| e.to_string())?.get(&id)?;
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
}

#[tauri::command]
fn save_settings(state: State<AppState>, data: SettingsIn) -> Result<Value, String> {
    let mut v = state.vault.lock().map_err(|e| e.to_string())?;
    let mut s = v.settings()?;
    if let Some(x) = data.webdav_url { s.webdav_url = x; }
    if let Some(x) = data.webdav_user { s.webdav_user = x; }
    if let Some(x) = data.webdav_password { s.webdav_password = x; }
    if let Some(x) = data.webdav_path { s.webdav_path = x; }
    if let Some(x) = data.autolock_seconds { s.autolock_seconds = x; }
    if let Some(x) = data.clipboard_clear_seconds { s.clipboard_clear_seconds = x; }
    v.update_settings(s)?;
    ok(json!({}))
}

#[tauri::command]
fn change_password(state: State<AppState>, old: String, new_password: String, confirm: String) -> Result<Value, String> {
    state.vault.lock().map_err(|e| e.to_string())?.change_password(&old, &new_password, &confirm)?;
    ok(json!({}))
}

#[tauri::command]
fn webdav_upload(state: State<AppState>) -> Result<Value, String> {
    let mut v = state.vault.lock().map_err(|e| e.to_string())?;
    let s = v.settings()?;
    let blob = v.encrypted_bytes()?;
    drop(v);
    webdav::put(&s.webdav_url, &s.webdav_path, &s.webdav_user, &s.webdav_password, &blob)?;
    ok(json!({}))
}

#[tauri::command]
fn webdav_download(state: State<AppState>, password: String) -> Result<Value, String> {
    let mut v = state.vault.lock().map_err(|e| e.to_string())?;
    let s = v.settings()?;
    let pw = if password.is_empty() {
        v.password().ok_or_else(|| "请先解锁".to_string())?
    } else {
        password
    };
    drop(v);
    let blob = webdav::get(&s.webdav_url, &s.webdav_path, &s.webdav_user, &s.webdav_password)?;
    state.vault.lock().map_err(|e| e.to_string())?.replace_bytes(&blob, &pw)?;
    ok(json!({}))
}

#[cfg_attr(mobile, tauri::mobile_entry_point)]
pub fn run() {
    tauri::Builder::default()
        .manage(AppState {
            vault: Mutex::new(Vault::new(Vault::default_path())),
        })
        .invoke_handler(tauri::generate_handler![
            status, setup, unlock, lock, snapshot, get_account, add_account, update_account,
            delete_account, import_uri, import_qr, import_text, export_data, account_qr,
            save_settings, change_password, webdav_upload, webdav_download
        ])
        .run(tauri::generate_context!())
        .expect("error while running tauri application");
}
