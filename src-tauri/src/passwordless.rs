use std::sync::Mutex;

use zeroize::Zeroizing;

const SERVICE: &str = "app.local.authenticator";
const USER: &str = "passwordless";

static CACHE: Mutex<Option<Zeroizing<String>>> = Mutex::new(None);
static KNOWN: Mutex<Option<bool>> = Mutex::new(None);

fn entry() -> Result<keyring::Entry, String> {
    keyring::Entry::new(SERVICE, USER).map_err(|e| e.to_string())
}

fn remember(on: bool) {
    if let Ok(mut slot) = KNOWN.lock() {
        *slot = Some(on);
    }
}

fn cache_password(password: &str) {
    if let Ok(mut cache) = CACHE.lock() {
        *cache = Some(Zeroizing::new(password.to_string()));
    }
}

fn read_keyring() -> Result<Option<String>, String> {
    match entry()?.get_password() {
        Ok(password) => Ok(Some(password)),
        Err(keyring::Error::NoEntry) => Ok(None),
        Err(error) => Err(error.to_string()),
    }
}

pub fn enabled() -> bool {
    if let Ok(slot) = KNOWN.lock() {
        if let Some(on) = *slot {
            return on;
        }
    }
    match read_keyring() {
        Ok(Some(password)) => {
            cache_password(&password);
            remember(true);
            true
        }
        Ok(None) => {
            remember(false);
            false
        }
        Err(_) => false,
    }
}

pub fn store(password: &str) -> Result<(), String> {
    entry()?
        .set_password(password)
        .map_err(|e| format!("无法保存免密凭据：{e}"))?;
    cache_password(password);
    remember(true);
    Ok(())
}

pub fn take() -> Result<Zeroizing<String>, String> {
    if let Ok(cache) = CACHE.lock() {
        if let Some(password) = cache.as_ref() {
            return Ok(Zeroizing::new(password.to_string()));
        }
    }
    match read_keyring()? {
        Some(password) => {
            cache_password(&password);
            remember(true);
            Ok(Zeroizing::new(password))
        }
        None => {
            remember(false);
            Err("未开启无需密码进入".into())
        }
    }
}

pub fn stored_password() -> Result<Option<String>, String> {
    if let Ok(cache) = CACHE.lock() {
        if let Some(password) = cache.as_ref() {
            return Ok(Some(password.to_string()));
        }
    }
    if let Ok(slot) = KNOWN.lock() {
        if *slot == Some(false) {
            return Ok(None);
        }
    }
    let found = read_keyring()?;
    match &found {
        Some(password) => {
            cache_password(password);
            remember(true);
        }
        None => remember(false),
    }
    Ok(found)
}

pub fn clear() -> Result<(), String> {
    match entry()?.delete_credential() {
        Ok(()) | Err(keyring::Error::NoEntry) => {
            if let Ok(mut cache) = CACHE.lock() {
                *cache = None;
            }
            remember(false);
            Ok(())
        }
        Err(error) => Err(error.to_string()),
    }
}
