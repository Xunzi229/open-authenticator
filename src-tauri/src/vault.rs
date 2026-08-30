use rand::RngCore;
use serde::{Deserialize, Serialize};
use std::collections::HashSet;
use std::fs::{self, OpenOptions};
use std::io::Write;
use std::path::{Path, PathBuf};
use std::thread;
use std::time::{Duration, Instant, SystemTime, UNIX_EPOCH};
use zeroize::Zeroize;

use crate::crypto;
use crate::qr::QrAccount;
use crate::totp;

pub const MIN_PASSWORD: usize = 8;
pub const MAX_VAULT_BYTES: u64 = 16 * 1024 * 1024;
pub const MAX_IMPORT_ACCOUNTS: usize = 10_000;
pub const MAX_AUTOLOCK_SECONDS: u64 = 31 * 24 * 60 * 60;
pub const MAX_CLIPBOARD_SECONDS: u64 = 24 * 60 * 60;
const MAX_TEXT_FIELD: usize = 4096;
const MAX_SECRET_FIELD: usize = 1024;
const PAYLOAD_VERSION: u32 = 1;

#[derive(Clone, Debug, Serialize, Deserialize)]
pub struct Account {
    pub id: String,
    pub issuer: String,
    pub name: String,
    pub email: String,
    pub notes: String,
    pub secret: String,
    pub algorithm: String,
    pub digits: u32,
    pub period: u64,
    pub created: u64,
    pub updated: u64,
}

#[derive(Clone, Debug, Serialize, Deserialize)]
pub struct Settings {
    #[serde(default)]
    pub webdav_url: String,
    #[serde(default)]
    pub webdav_user: String,
    #[serde(default)]
    pub webdav_password: String,
    #[serde(default = "default_dav_path")]
    pub webdav_path: String,
    #[serde(default)]
    pub webdav_etag: String,
    #[serde(default = "default_autolock")]
    pub autolock_seconds: u64,
    #[serde(default = "default_clip")]
    pub clipboard_clear_seconds: u64,
}

fn default_dav_path() -> String {
    "/authenticator/vault.enc".into()
}
fn default_autolock() -> u64 {
    180
}
fn default_clip() -> u64 {
    30
}

impl Default for Settings {
    fn default() -> Self {
        Self {
            webdav_url: String::new(),
            webdav_user: String::new(),
            webdav_password: String::new(),
            webdav_path: default_dav_path(),
            webdav_etag: String::new(),
            autolock_seconds: default_autolock(),
            clipboard_clear_seconds: default_clip(),
        }
    }
}

#[derive(Clone, Debug, Serialize, Deserialize)]
pub struct Payload {
    pub version: u32,
    pub accounts: Vec<Account>,
    #[serde(default)]
    pub settings: Settings,
}

pub struct Vault {
    path: PathBuf,
    password: Option<String>,
    payload: Option<Payload>,
    fail: u32,
    last_active: Option<Instant>,
}

impl Vault {
    pub fn new(path: PathBuf) -> Self {
        Self {
            path,
            password: None,
            payload: None,
            fail: 0,
            last_active: None,
        }
    }

    pub fn default_path() -> PathBuf {
        if cfg!(windows) {
            let base = std::env::var("APPDATA").unwrap_or_else(|_| {
                dirs::home_dir()
                    .unwrap_or_else(|| PathBuf::from("."))
                    .join("AppData")
                    .join("Roaming")
                    .to_string_lossy()
                    .into()
            });
            PathBuf::from(base).join("Authenticator").join("vault.enc")
        } else if cfg!(target_os = "macos") {
            dirs::home_dir()
                .unwrap_or_else(|| PathBuf::from("."))
                .join("Library/Application Support/Authenticator/vault.enc")
        } else {
            dirs::data_dir()
                .unwrap_or_else(|| PathBuf::from("."))
                .join("authenticator/vault.enc")
        }
    }

    pub fn exists(&self) -> bool {
        self.path.is_file()
            && fs::metadata(&self.path)
                .map(|m| m.len() > 0)
                .unwrap_or(false)
    }

    pub fn unlocked(&self) -> bool {
        self.payload.is_some() && self.password.is_some()
    }

    pub fn lock(&mut self) {
        if let Some(mut password) = self.password.take() {
            password.zeroize();
        }
        self.payload = None;
        self.last_active = None;
    }

    fn touch(&mut self) {
        self.last_active = Some(Instant::now());
    }

    fn need(&mut self) -> Result<&mut Payload, String> {
        if self.payload.is_none() || self.password.is_none() {
            return Err("已锁定".into());
        }
        let idle = self
            .payload
            .as_ref()
            .map(|p| p.settings.autolock_seconds)
            .unwrap_or(0);
        if idle > 0 {
            if let Some(t) = self.last_active {
                if t.elapsed() > Duration::from_secs(idle) {
                    self.lock();
                    return Err("已自动锁定".into());
                }
            }
        }
        self.payload.as_mut().ok_or_else(|| "已锁定".into())
    }

    pub fn activity(&mut self) -> Result<(), String> {
        self.need()?;
        self.touch();
        Ok(())
    }

    pub fn check_timeout(&mut self) {
        let _ = self.need();
    }

    pub fn setup(&mut self, password: &str, confirm: &str) -> Result<(), String> {
        if self.exists() {
            return Err("保险库已存在".into());
        }
        if password != confirm {
            return Err("两次密码不一致".into());
        }
        if password.chars().count() < MIN_PASSWORD {
            return Err("密码至少 8 位".into());
        }
        let payload = Payload {
            version: PAYLOAD_VERSION,
            accounts: vec![],
            settings: Settings::default(),
        };
        self.write_payload(&payload, password)?;
        self.password = Some(password.to_string());
        self.payload = Some(payload);
        self.touch();
        Ok(())
    }

    pub fn unlock(&mut self, password: &str) -> Result<(), String> {
        if !self.exists() {
            return Err("还没有保险库".into());
        }
        if self.fail > 0 {
            let delay = (350u64 * 2u64.pow(self.fail.min(4))).min(4000);
            thread::sleep(Duration::from_millis(delay));
        }
        let size = fs::metadata(&self.path)
            .map_err(|_| "无法读取保险库")?
            .len();
        if size > MAX_VAULT_BYTES {
            return Err("保险库文件过大".into());
        }
        let raw = fs::read(&self.path).map_err(|_| "无法读取保险库")?;
        let mut plain = match crypto::decrypt(&raw, password) {
            Ok(p) => p,
            Err(_) => {
                self.fail += 1;
                return Err("密码错误".into());
            }
        };
        let payload = serde_json::from_slice(&plain).map_err(|_| "保险库损坏");
        plain.zeroize();
        let payload: Payload = payload?;
        validate_payload(&payload).map_err(|_| "保险库损坏")?;
        self.fail = 0;
        self.password = Some(password.to_string());
        self.payload = Some(payload);
        self.touch();
        Ok(())
    }

    fn write_payload(&self, payload: &Payload, password: &str) -> Result<(), String> {
        let mut plain = serde_json::to_vec(payload).map_err(|e| e.to_string())?;
        if plain.len() as u64 > MAX_VAULT_BYTES {
            plain.zeroize();
            return Err("保险库数据过大".into());
        }
        let blob = crypto::encrypt(&plain, password).map_err(|e| e.to_string());
        plain.zeroize();
        let blob = blob?;
        if let Some(dir) = self.path.parent() {
            fs::create_dir_all(dir).map_err(|e| e.to_string())?;
            secure_directory(dir)?;
        }
        let tmp = temporary_path(&self.path);
        let mut file = OpenOptions::new()
            .create_new(true)
            .write(true)
            .secure_mode()
            .open(&tmp)
            .map_err(|e| e.to_string())?;
        if let Err(e) = file.write_all(&blob).and_then(|_| file.sync_all()) {
            drop(file);
            let _ = fs::remove_file(&tmp);
            return Err(e.to_string());
        }
        drop(file);
        if let Err(e) = atomic_replace(&tmp, &self.path) {
            let _ = fs::remove_file(&tmp);
            return Err(e);
        }
        sync_parent(&self.path)?;
        Ok(())
    }

    fn commit_payload(&mut self, payload: Payload) -> Result<(), String> {
        let password = self.password.as_deref().ok_or("已锁定")?;
        self.write_payload(&payload, password)?;
        self.payload = Some(payload);
        self.touch();
        Ok(())
    }

    pub fn change_password(&mut self, old: &str, new: &str, confirm: &str) -> Result<(), String> {
        self.need()?;
        if self.password.as_deref() != Some(old) {
            return Err("当前密码错误".into());
        }
        if new != confirm {
            return Err("两次密码不一致".into());
        }
        if new.chars().count() < MIN_PASSWORD {
            return Err("密码至少 8 位".into());
        }
        let payload = self.payload.as_ref().ok_or("已锁定")?.clone();
        self.write_payload(&payload, new)?;
        if let Some(mut password) = self.password.replace(new.to_string()) {
            password.zeroize();
        }
        self.touch();
        Ok(())
    }

    pub fn settings(&mut self) -> Result<Settings, String> {
        Ok(self.need()?.settings.clone())
    }

    pub fn update_settings(
        &mut self,
        mut s: Settings,
        preserve_empty_password: bool,
    ) -> Result<(), String> {
        let cur = self.need()?.settings.clone();
        if preserve_empty_password && s.webdav_password.is_empty() {
            s.webdav_password = cur.webdav_password;
        }
        if s.webdav_path.is_empty() {
            s.webdav_path = default_dav_path();
        }
        if s.webdav_url != cur.webdav_url
            || s.webdav_user != cur.webdav_user
            || s.webdav_path != cur.webdav_path
        {
            s.webdav_etag.clear();
        }
        validate_settings(&s)?;
        let mut payload = self.need()?.clone();
        payload.settings = s;
        self.commit_payload(payload)
    }

    pub fn accounts(&mut self) -> Result<Vec<Account>, String> {
        Ok(self.need()?.accounts.clone())
    }

    pub fn get(&mut self, id: &str) -> Result<Account, String> {
        self.need()?
            .accounts
            .iter()
            .find(|a| a.id == id)
            .cloned()
            .ok_or_else(|| "账号不存在".into())
    }

    pub fn add(&mut self, mut a: Account) -> Result<Account, String> {
        normalize(&mut a)?;
        let mut payload = self.need()?.clone();
        if payload.accounts.len() >= MAX_IMPORT_ACCOUNTS {
            return Err("账号数量已达到上限".into());
        }
        payload.accounts.push(a.clone());
        self.commit_payload(payload)?;
        Ok(a)
    }

    pub fn add_many(&mut self, items: Vec<QrAccount>) -> Result<usize, String> {
        let mut seen: HashSet<(String, String, u32, u64)> = self
            .need()?
            .accounts
            .iter()
            .map(|a| {
                (
                    totp::normalize_secret(&a.secret)
                        .unwrap_or_else(|_| a.secret.replace(' ', "").to_ascii_uppercase()),
                    a.algorithm.to_ascii_uppercase().replace('-', ""),
                    a.digits,
                    a.period,
                )
            })
            .collect();
        let mut added = Vec::new();
        for it in items {
            let mut a = Account {
                id: String::new(),
                issuer: it.issuer,
                name: it.name,
                email: String::new(),
                notes: String::new(),
                secret: it.secret,
                algorithm: it.algorithm,
                digits: it.digits,
                period: it.period,
                created: 0,
                updated: 0,
            };
            normalize(&mut a)?;
            if !seen.insert((a.secret.clone(), a.algorithm.clone(), a.digits, a.period)) {
                continue;
            }
            added.push(a);
        }
        let n = added.len();
        if n == 0 {
            return Ok(0);
        }
        let mut payload = self.need()?.clone();
        if payload.accounts.len().saturating_add(added.len()) > MAX_IMPORT_ACCOUNTS {
            return Err("账号数量已达到上限".into());
        }
        payload.accounts.extend(added);
        self.commit_payload(payload)?;
        Ok(n)
    }

    pub fn update(&mut self, id: &str, patch: Account) -> Result<(), String> {
        let mut cur = self.get(id)?;
        cur.issuer = patch.issuer;
        cur.name = patch.name;
        cur.email = patch.email;
        cur.notes = patch.notes;
        if !patch.algorithm.is_empty() {
            cur.algorithm = patch.algorithm;
        }
        if patch.digits != 0 {
            cur.digits = patch.digits;
        }
        if patch.period != 0 {
            cur.period = patch.period;
        }
        if !patch.secret.trim().is_empty() {
            cur.secret = patch.secret;
        }
        normalize(&mut cur)?;
        cur.id = id.to_string();
        let mut payload = self.need()?.clone();
        if let Some(slot) = payload.accounts.iter_mut().find(|a| a.id == id) {
            *slot = cur;
        }
        self.commit_payload(payload)
    }

    pub fn delete(&mut self, id: &str) -> Result<(), String> {
        let mut payload = self.need()?.clone();
        let before = payload.accounts.len();
        payload.accounts.retain(|a| a.id != id);
        let n = before - payload.accounts.len();
        if n == 0 {
            return Err("账号不存在".into());
        }
        self.commit_payload(payload)
    }

    pub fn encrypted_bytes(&self) -> Result<Vec<u8>, String> {
        fs::read(&self.path).map_err(|_| "没有本地保险库".into())
    }

    pub fn replace_bytes(&mut self, blob: &[u8], password: &str, etag: &str) -> Result<(), String> {
        if blob.len() as u64 > MAX_VAULT_BYTES {
            return Err("远程保险库过大".into());
        }
        let mut plain =
            crypto::decrypt(blob, password).map_err(|_| "远程文件无法用当前密码解密")?;
        let payload = serde_json::from_slice(&plain).map_err(|_| "远程保险库损坏");
        plain.zeroize();
        let mut payload: Payload = payload?;
        validate_payload(&payload).map_err(|_| "远程保险库损坏")?;
        if let Some(local) = self.payload.as_ref() {
            payload.settings.webdav_url = local.settings.webdav_url.clone();
            payload.settings.webdav_user = local.settings.webdav_user.clone();
            payload.settings.webdav_password = local.settings.webdav_password.clone();
            payload.settings.webdav_path = local.settings.webdav_path.clone();
        }
        payload.settings.webdav_etag = etag.to_string();
        self.backup()?;
        self.write_payload(&payload, password)?;
        if let Some(mut old) = self.password.replace(password.to_string()) {
            old.zeroize();
        }
        self.payload = Some(payload);
        self.touch();
        Ok(())
    }

    pub fn set_webdav_etag(&mut self, etag: String) -> Result<(), String> {
        let mut payload = self.need()?.clone();
        payload.settings.webdav_etag = etag;
        self.commit_payload(payload)
    }

    fn backup(&self) -> Result<(), String> {
        if !self.exists() {
            return Ok(());
        }
        let parent = self
            .path
            .parent()
            .ok_or_else(|| "保险库路径无效".to_string())?;
        let dir = parent.join("backups");
        fs::create_dir_all(&dir).map_err(|e| e.to_string())?;
        secure_directory(&dir)?;
        let mut random = [0u8; 4];
        rand::thread_rng().fill_bytes(&mut random);
        let target = dir.join(format!("vault-{}-{}.enc", now(), hex::encode(random)));
        fs::copy(&self.path, &target).map_err(|e| e.to_string())?;
        secure_file(&target)?;
        Ok(())
    }

    pub fn password(&self) -> Option<zeroize::Zeroizing<String>> {
        self.password.clone().map(zeroize::Zeroizing::new)
    }

    pub fn verify_password(&mut self, password: &str) -> Result<(), String> {
        if !self.unlocked() {
            return Err("已锁定".into());
        }
        if password.is_empty() {
            return Err("请输入主密码".into());
        }
        if self.fail > 0 {
            let delay = (350u64 * 2u64.pow(self.fail.min(4))).min(4000);
            thread::sleep(Duration::from_millis(delay));
        }
        if self.password.as_deref() != Some(password) {
            self.fail += 1;
            return Err("密码错误".into());
        }
        self.fail = 0;
        Ok(())
    }
}

impl Drop for Vault {
    fn drop(&mut self) {
        self.lock();
    }
}

trait SecureOpenOptions {
    fn secure_mode(&mut self) -> &mut OpenOptions;
}

impl SecureOpenOptions for OpenOptions {
    fn secure_mode(&mut self) -> &mut OpenOptions {
        #[cfg(unix)]
        {
            use std::os::unix::fs::OpenOptionsExt;
            self.mode(0o600);
        }
        self
    }
}

fn temporary_path(path: &Path) -> PathBuf {
    let mut random = [0u8; 8];
    rand::thread_rng().fill_bytes(&mut random);
    let name = path
        .file_name()
        .and_then(|x| x.to_str())
        .unwrap_or("vault.enc");
    path.with_file_name(format!(".{name}.{}.tmp", hex::encode(random)))
}

fn secure_directory(path: &Path) -> Result<(), String> {
    #[cfg(unix)]
    {
        use std::os::unix::fs::PermissionsExt;
        fs::set_permissions(path, fs::Permissions::from_mode(0o700)).map_err(|e| e.to_string())?;
    }
    #[cfg(not(unix))]
    let _ = path;
    Ok(())
}

fn secure_file(path: &Path) -> Result<(), String> {
    #[cfg(unix)]
    {
        use std::os::unix::fs::PermissionsExt;
        fs::set_permissions(path, fs::Permissions::from_mode(0o600)).map_err(|e| e.to_string())?;
    }
    #[cfg(not(unix))]
    let _ = path;
    Ok(())
}

fn sync_parent(path: &Path) -> Result<(), String> {
    #[cfg(unix)]
    {
        if let Some(parent) = path.parent() {
            fs::File::open(parent)
                .and_then(|dir| dir.sync_all())
                .map_err(|e| e.to_string())?;
        }
    }
    #[cfg(not(unix))]
    let _ = path;
    Ok(())
}

fn validate_settings(settings: &Settings) -> Result<(), String> {
    if settings.autolock_seconds > MAX_AUTOLOCK_SECONDS {
        return Err("自动锁定时间不能超过 31 天".into());
    }
    if settings.clipboard_clear_seconds > MAX_CLIPBOARD_SECONDS {
        return Err("剪贴板清理时间不能超过 24 小时".into());
    }
    for value in [
        &settings.webdav_url,
        &settings.webdav_user,
        &settings.webdav_password,
        &settings.webdav_path,
        &settings.webdav_etag,
    ] {
        if value.len() > MAX_TEXT_FIELD {
            return Err("设置内容过长".into());
        }
    }
    Ok(())
}

fn validate_payload(payload: &Payload) -> Result<(), String> {
    if payload.version != PAYLOAD_VERSION || payload.accounts.len() > MAX_IMPORT_ACCOUNTS {
        return Err("不支持的保险库版本或账号数量".into());
    }
    validate_settings(&payload.settings)?;
    let mut ids = HashSet::new();
    for account in &payload.accounts {
        if account.id.is_empty()
            || !ids.insert(account.id.as_str())
            || account.secret.len() > MAX_SECRET_FIELD
            || [
                &account.issuer,
                &account.name,
                &account.email,
                &account.notes,
            ]
            .into_iter()
            .any(|value| value.len() > MAX_TEXT_FIELD)
        {
            return Err("账号数据无效".into());
        }
        totp::normalize_secret(&account.secret)?;
        totp::validate_parameters(account.digits, &account.algorithm, account.period)?;
    }
    Ok(())
}

#[cfg(windows)]
fn atomic_replace(from: &Path, to: &Path) -> Result<(), String> {
    use std::os::windows::ffi::OsStrExt;
    use windows::Win32::Storage::FileSystem::{
        MoveFileExW, MOVEFILE_REPLACE_EXISTING, MOVEFILE_WRITE_THROUGH,
    };

    let from: Vec<u16> = from.as_os_str().encode_wide().chain(Some(0)).collect();
    let to: Vec<u16> = to.as_os_str().encode_wide().chain(Some(0)).collect();
    unsafe {
        MoveFileExW(
            windows::core::PCWSTR(from.as_ptr()),
            windows::core::PCWSTR(to.as_ptr()),
            MOVEFILE_REPLACE_EXISTING | MOVEFILE_WRITE_THROUGH,
        )
    }
    .map_err(|e| e.to_string())
}

#[cfg(not(windows))]
fn atomic_replace(from: &Path, to: &Path) -> Result<(), String> {
    fs::rename(from, to).map_err(|e| e.to_string())
}

fn now() -> u64 {
    SystemTime::now()
        .duration_since(UNIX_EPOCH)
        .unwrap()
        .as_secs()
}

fn new_id() -> String {
    let mut b = [0u8; 16];
    rand::thread_rng().fill_bytes(&mut b);
    hex::encode(b)
}

fn normalize(a: &mut Account) -> Result<(), String> {
    if a.secret.len() > MAX_SECRET_FIELD
        || [&a.issuer, &a.name, &a.email, &a.notes]
            .into_iter()
            .any(|value| value.len() > MAX_TEXT_FIELD)
    {
        return Err("账号内容过长".into());
    }
    a.secret = totp::normalize_secret(&a.secret)?;
    a.issuer = a.issuer.trim().to_string();
    a.name = a.name.trim().to_string();
    a.email = a.email.trim().to_string();
    a.notes = a.notes.trim().to_string();
    if a.name.is_empty() && a.issuer.is_empty() {
        return Err("请填写名称或发行方".into());
    }
    a.algorithm = totp::validate_parameters(a.digits, &a.algorithm, a.period)?;
    let t = now();
    if a.id.is_empty() {
        a.id = new_id();
    }
    if a.created == 0 {
        a.created = t;
    }
    a.updated = t;
    Ok(())
}

#[cfg(test)]
mod tests {
    use super::*;

    fn account(secret: &str) -> Account {
        Account {
            id: String::new(),
            issuer: "Example".into(),
            name: "user".into(),
            email: String::new(),
            notes: String::new(),
            secret: secret.into(),
            algorithm: "SHA1".into(),
            digits: 6,
            period: 30,
            created: 0,
            updated: 0,
        }
    }

    #[test]
    fn saves_over_existing_vault_and_reopens_it() {
        let dir = tempfile::tempdir().unwrap();
        let path = dir.path().join("vault.enc");
        let mut vault = Vault::new(path.clone());
        vault.setup("correct horse", "correct horse").unwrap();
        vault.add(account("JBSWY3DPEHPK3PXP")).unwrap();

        let mut reopened = Vault::new(path);
        reopened.unlock("correct horse").unwrap();
        assert_eq!(reopened.accounts().unwrap().len(), 1);
    }

    #[test]
    fn rejects_invalid_otp_data_before_saving() {
        let dir = tempfile::tempdir().unwrap();
        let mut vault = Vault::new(dir.path().join("vault.enc"));
        vault.setup("correct horse", "correct horse").unwrap();

        assert!(vault.add(account("not-a-base32-secret-0189")).is_err());
        let mut invalid_digits = account("JBSWY3DPEHPK3PXP");
        invalid_digits.digits = 7;
        assert!(vault.add(invalid_digits).is_err());
        assert!(vault.accounts().unwrap().is_empty());
    }

    #[test]
    fn polling_reads_do_not_reset_auto_lock() {
        let dir = tempfile::tempdir().unwrap();
        let mut vault = Vault::new(dir.path().join("vault.enc"));
        vault.setup("correct horse", "correct horse").unwrap();
        let mut settings = vault.settings().unwrap();
        settings.autolock_seconds = 10;
        vault.update_settings(settings, true).unwrap();

        let previous = Instant::now() - Duration::from_secs(9);
        vault.last_active = Some(previous);
        assert!(vault.accounts().is_ok());
        assert_eq!(vault.last_active, Some(previous));

        vault.last_active = Some(Instant::now() - Duration::from_secs(11));
        assert!(vault.accounts().is_err());
        assert!(!vault.unlocked());
    }

    #[test]
    fn failed_save_does_not_commit_candidate_state_in_memory() {
        let dir = tempfile::tempdir().unwrap();
        let path = dir.path().join("vault.enc");
        let mut vault = Vault::new(path);
        vault.setup("correct horse", "correct horse").unwrap();
        vault.path = dir.path().to_path_buf();

        assert!(vault.add(account("JBSWY3DPEHPK3PXP")).is_err());
        assert!(vault.accounts().unwrap().is_empty());
    }

    #[test]
    fn password_minimum_counts_characters_not_utf8_bytes() {
        let dir = tempfile::tempdir().unwrap();
        let mut vault = Vault::new(dir.path().join("vault.enc"));
        assert!(vault.setup("密码密码", "密码密码").is_err());
    }
}
