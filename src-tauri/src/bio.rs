use zeroize::Zeroize;

const SERVICE: &str = "app.local.authenticator";
const USER: &str = "master";

fn entry() -> Result<keyring::Entry, String> {
    keyring::Entry::new(SERVICE, USER).map_err(|e| e.to_string())
}

pub fn enabled() -> bool {
    match stored_password() {
        Ok(Some(mut password)) => {
            password.zeroize();
            true
        }
        _ => false,
    }
}

pub fn store(password: &str) -> Result<(), String> {
    entry()?.set_password(password).map_err(|e| e.to_string())
}

pub fn take() -> Result<String, String> {
    entry()?.get_password().map_err(|_| "未开启指纹解锁".into())
}

pub fn stored_password() -> Result<Option<String>, String> {
    match entry()?.get_password() {
        Ok(password) => Ok(Some(password)),
        Err(keyring::Error::NoEntry) => Ok(None),
        Err(error) => Err(error.to_string()),
    }
}

pub fn clear() -> Result<(), String> {
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
    #[cfg(not(windows))]
    {
        false
    }
}

pub fn prompt(hwnd: isize, message: &str) -> Result<(), String> {
    #[cfg(windows)]
    {
        prompt_windows(hwnd, message)
    }
    #[cfg(not(windows))]
    {
        let _ = (hwnd, message);
        Err("指纹解锁目前仅支持 Windows Hello".into())
    }
}

#[cfg(windows)]
fn hello_available() -> windows::core::Result<bool> {
    use windows::Security::Credentials::UI::{UserConsentVerifier, UserConsentVerifierAvailability};
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
        UserConsentVerificationResult::NotConfiguredForUser => Err("当前用户未配置 Windows Hello".into()),
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
use zeroize::Zeroize;
