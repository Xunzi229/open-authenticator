use std::sync::Mutex;
use zeroize::{Zeroize, Zeroizing};

const SERVICE: &str = "app.local.authenticator";
const USER: &str = "master";

static CACHE: Mutex<Option<Zeroizing<String>>> = Mutex::new(None);

fn entry() -> Result<keyring::Entry, String> {
    keyring::Entry::new(SERVICE, USER).map_err(|e| e.to_string())
}

fn cache_set(password: &str) {
    if let Ok(mut slot) = CACHE.lock() {
        *slot = Some(Zeroizing::new(password.to_string()));
    }
}

fn cache_get() -> Option<String> {
    CACHE
        .lock()
        .ok()?
        .as_ref()
        .map(|password| password.to_string())
}

fn cache_clear() {
    if let Ok(mut slot) = CACHE.lock() {
        *slot = None;
    }
}

pub fn enabled() -> bool {
    if cache_get().is_some() {
        return true;
    }
    match stored_password() {
        Ok(Some(mut password)) => {
            password.zeroize();
            true
        }
        _ => false,
    }
}

pub fn store(password: &str) -> Result<(), String> {
    entry()?
        .set_password(password)
        .map_err(|e| format!("无法保存指纹凭据：{e}"))?;
    cache_set(password);
    Ok(())
}

pub fn take() -> Result<String, String> {
    if let Some(password) = cache_get() {
        return Ok(password);
    }
    entry()
        .and_then(|item| item.get_password().map_err(|e| e.to_string()))
        .map_err(|_| "未开启指纹解锁".into())
}

pub fn stored_password() -> Result<Option<String>, String> {
    if let Some(password) = cache_get() {
        return Ok(Some(password));
    }
    match entry()?.get_password() {
        Ok(password) => Ok(Some(password)),
        Err(keyring::Error::NoEntry) => Ok(None),
        Err(error) => Err(error.to_string()),
    }
}

pub fn clear() -> Result<(), String> {
    cache_clear();
    match entry()?.delete_credential() {
        Ok(()) | Err(keyring::Error::NoEntry) => Ok(()),
        Err(error) => Err(error.to_string()),
    }
}

pub fn available() -> bool {
    #[cfg(windows)]
    {
        hello_available().unwrap_or(false)
    }
    #[cfg(target_os = "macos")]
    {
        macos_available()
    }
    #[cfg(not(any(windows, target_os = "macos")))]
    {
        false
    }
}

pub fn prompt(hwnd: isize, message: &str) -> Result<(), String> {
    #[cfg(windows)]
    {
        prompt_windows(hwnd, message)
    }
    #[cfg(target_os = "macos")]
    {
        let _ = hwnd;
        prompt_macos(message)
    }
    #[cfg(not(any(windows, target_os = "macos")))]
    {
        let _ = (hwnd, message);
        Err("指纹解锁目前仅支持 Windows Hello 与 macOS Touch ID".into())
    }
}

#[cfg(windows)]
fn hello_available() -> windows::core::Result<bool> {
    use windows::Security::Credentials::UI::{
        UserConsentVerifier, UserConsentVerifierAvailability,
    };
    let avail = UserConsentVerifier::CheckAvailabilityAsync()?.get()?;
    Ok(matches!(
        avail,
        UserConsentVerifierAvailability::Available | UserConsentVerifierAvailability::DeviceBusy
    ))
}

#[cfg(windows)]
fn prompt_windows(hwnd: isize, message: &str) -> Result<(), String> {
    use windows::Security::Credentials::UI::UserConsentVerificationResult;
    if !available() {
        return Err("系统未配置指纹或 Windows Hello".into());
    }
    let result = verify_windows(hwnd, message).map_err(|e| e.to_string())?;
    match result {
        UserConsentVerificationResult::Verified => Ok(()),
        UserConsentVerificationResult::Canceled => Err("已取消验证".into()),
        UserConsentVerificationResult::DeviceBusy => Err("指纹设备正忙".into()),
        UserConsentVerificationResult::DeviceNotPresent => Err("没有指纹或 Hello 设备".into()),
        UserConsentVerificationResult::DisabledByPolicy => Err("Windows Hello 被策略禁用".into()),
        UserConsentVerificationResult::NotConfiguredForUser => {
            Err("当前用户未配置 Windows Hello".into())
        }
        UserConsentVerificationResult::RetriesExhausted => Err("验证失败次数过多".into()),
        _ => Err("验证未通过".into()),
    }
}

#[cfg(windows)]
fn verify_windows(
    hwnd: isize,
    message: &str,
) -> windows::core::Result<windows::Security::Credentials::UI::UserConsentVerificationResult> {
    use windows::core::HSTRING;
    use windows::Security::Credentials::UI::UserConsentVerifier;

    let msg = HSTRING::from(message);
    if let Ok(r) = verify_for_window(hwnd, &msg) {
        return Ok(r);
    }
    UserConsentVerifier::RequestVerificationAsync(&msg)?.get()
}

#[cfg(windows)]
fn verify_for_window(
    hwnd: isize,
    msg: &windows::core::HSTRING,
) -> windows::core::Result<windows::Security::Credentials::UI::UserConsentVerificationResult> {
    use windows::core::factory;
    use windows::Security::Credentials::UI::{UserConsentVerificationResult, UserConsentVerifier};
    use windows::Win32::Foundation::HWND;
    use windows::Win32::System::WinRT::IUserConsentVerifierInterop;
    use windows::Win32::UI::WindowsAndMessaging::GetForegroundWindow;
    use windows_future::IAsyncOperation;

    let interop: IUserConsentVerifierInterop =
        factory::<UserConsentVerifier, IUserConsentVerifierInterop>()?;
    let mut target = HWND(hwnd as *mut core::ffi::c_void);
    if target.0.is_null() {
        target = unsafe { GetForegroundWindow() };
    }
    let op: IAsyncOperation<UserConsentVerificationResult> =
        unsafe { interop.RequestVerificationForWindowAsync(target, msg)? };
    op.get()
}

#[cfg(target_os = "macos")]
fn macos_policy() -> objc2_local_authentication::LAPolicy {
    objc2_local_authentication::LAPolicy::DeviceOwnerAuthenticationWithBiometrics
}

#[cfg(target_os = "macos")]
fn macos_available() -> bool {
    use objc2_local_authentication::LAContext;
    let ctx = unsafe { LAContext::new() };
    unsafe { ctx.canEvaluatePolicy_error(macos_policy()) }.is_ok()
}

#[cfg(target_os = "macos")]
fn map_la_error(error: Option<&objc2_foundation::NSError>) -> String {
    let Some(error) = error else {
        return "验证未通过".into();
    };
    match error.code() {
        -2 | -3 | -9 => "已取消验证".into(),
        -4 => "验证被系统中断".into(),
        -5 => "系统未设置登录密码".into(),
        -6 => "没有可用的 Touch ID 或 Face ID".into(),
        -7 => "尚未录入指纹或面容".into(),
        -8 => "指纹已锁定，请用系统密码解锁后再试".into(),
        -1004 => "当前无法显示验证窗口".into(),
        _ => "验证未通过".into(),
    }
}

#[cfg(target_os = "macos")]
fn prompt_macos(message: &str) -> Result<(), String> {
    use block2::RcBlock;
    use objc2::runtime::Bool;
    use objc2_foundation::{NSError, NSString};
    use objc2_local_authentication::LAContext;
    use std::sync::mpsc;

    if !available() {
        return Err("系统未配置 Touch ID 或 Face ID".into());
    }
    let reason = message.trim();
    if reason.is_empty() {
        return Err("验证说明不能为空".into());
    }

    let ctx = unsafe { LAContext::new() };
    let empty = NSString::from_str("");
    let cancel = NSString::from_str("取消");
    unsafe {
        ctx.setLocalizedFallbackTitle(Some(&empty));
        ctx.setLocalizedCancelTitle(Some(&cancel));
    }

    let (tx, rx) = mpsc::channel::<Result<(), String>>();
    let reply = RcBlock::new(move |success: Bool, error: *mut NSError| {
        let result = if bool::from(success) {
            Ok(())
        } else {
            let error = unsafe { error.as_ref() };
            Err(map_la_error(error))
        };
        let _ = tx.send(result);
    });
    let reason = NSString::from_str(reason);
    unsafe {
        ctx.evaluatePolicy_localizedReason_reply(macos_policy(), &reason, &reply);
    }
    rx.recv().map_err(|_| "指纹验证中断".to_string())?
}
