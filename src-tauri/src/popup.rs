use std::sync::Mutex;
use std::time::{Duration, Instant};

use tauri::{AppHandle, LogicalPosition, Manager, Monitor, Position, Rect, WebviewWindow};

use crate::tray_position;

const FOCUS_HIDE_DELAY_MILLIS: u64 = 350;
const TRAY_TOGGLE_SUPPRESS_MILLIS: u64 = 250;
const POPUP_WIDTH: i32 = 440;
const POPUP_HEIGHT: i32 = 780;
const WINDOW_MARGIN: i32 = 12;

pub struct PopupState {
    last_shown_at: Mutex<Option<Instant>>,
    last_hidden_at: Mutex<Option<Instant>>,
    took_focus: Mutex<bool>,
    tray_anchor: Mutex<Option<(f64, f64, f64, f64)>>,
}

impl PopupState {
    pub fn new() -> Self {
        Self {
            last_shown_at: Mutex::new(None),
            last_hidden_at: Mutex::new(None),
            took_focus: Mutex::new(false),
            tray_anchor: Mutex::new(None),
        }
    }
}

pub fn anchor_from_rect(rect: &Rect) -> Option<(f64, f64, f64, f64)> {
    let origin = rect.position.to_physical::<f64>(1.0);
    let size = rect.size.to_physical::<f64>(1.0);
    if !(1.0..400.0).contains(&size.width) || !(1.0..400.0).contains(&size.height) {
        return None;
    }
    Some((origin.x, origin.y, size.width, size.height))
}

pub fn show_main(app: &AppHandle) {
    let Some(window) = app.get_webview_window("main") else {
        return;
    };
    if let Ok(mut last_shown_at) = app.state::<PopupState>().last_shown_at.lock() {
        *last_shown_at = Some(Instant::now());
    }
    if let Ok(mut took_focus) = app.state::<PopupState>().took_focus.lock() {
        *took_focus = false;
    }
    position_main_window(&window);
    prepare_popup_window(&window);
    let _ = window.show();
    let _ = window.set_focus();
}

pub fn toggle_main(app: &AppHandle, anchor: Option<(f64, f64, f64, f64)>) {
    if let Some(anchor) = anchor {
        if let Ok(mut slot) = app.state::<PopupState>().tray_anchor.lock() {
            *slot = Some(anchor);
        }
    }
    let Some(window) = app.get_webview_window("main") else {
        return;
    };
    match window.is_visible() {
        Ok(true) => {
            note_hidden(app);
            let _ = window.hide();
        }
        _ => {
            let hidden_too_recently = app
                .state::<PopupState>()
                .last_hidden_at
                .lock()
                .ok()
                .and_then(|last| *last)
                .map(|instant| {
                    instant.elapsed() < Duration::from_millis(TRAY_TOGGLE_SUPPRESS_MILLIS)
                })
                .unwrap_or(false);
            if hidden_too_recently {
                return;
            }
            show_main(app);
        }
    }
}

pub fn note_hidden(app: &AppHandle) {
    if let Ok(mut last_hidden_at) = app.state::<PopupState>().last_hidden_at.lock() {
        *last_hidden_at = Some(Instant::now());
    }
}

pub fn handle_focus(window: &tauri::Window, focused: bool) {
    if window.label() != "main" {
        return;
    }
    let app = window.app_handle();
    let state = app.state::<PopupState>();
    if focused {
        if let Ok(mut took_focus) = state.took_focus.lock() {
            *took_focus = true;
        }
        return;
    }
    let took_focus = state.took_focus.lock().map(|flag| *flag).unwrap_or(true);
    if !took_focus {
        return;
    }
    let should_hide = state
        .last_shown_at
        .lock()
        .ok()
        .and_then(|last| *last)
        .map(|instant| instant.elapsed() >= Duration::from_millis(FOCUS_HIDE_DELAY_MILLIS))
        .unwrap_or(true);
    if should_hide {
        note_hidden(app);
        let _ = window.hide();
    }
}

fn prepare_popup_window(window: &WebviewWindow) {
    #[cfg(target_os = "macos")]
    configure_popup_window(window);
    #[cfg(not(target_os = "macos"))]
    let _ = window;
}

#[cfg(target_os = "macos")]
fn configure_popup_window(window: &WebviewWindow) {
    let window = window.clone();
    run_on_main(move || {
        let Some(ns_window) = ns_window(&window) else {
            return;
        };
        configure_ns_window(&ns_window);
    });
}

#[cfg(target_os = "macos")]
fn ns_window(window: &WebviewWindow) -> Option<objc2::rc::Retained<objc2_app_kit::NSWindow>> {
    let ptr = window.ns_window().ok()?;
    unsafe { objc2::rc::Retained::retain(ptr.cast::<objc2_app_kit::NSWindow>()) }
}

#[cfg(target_os = "macos")]
fn configure_ns_window(ns_window: &objc2_app_kit::NSWindow) {
    ns_window.setLevel(objc2_app_kit::NSPopUpMenuWindowLevel);
    ns_window.setHidesOnDeactivate(false);
    ns_window.setCollectionBehavior(
        objc2_app_kit::NSWindowCollectionBehavior::MoveToActiveSpace
            | objc2_app_kit::NSWindowCollectionBehavior::CanJoinAllApplications
            | objc2_app_kit::NSWindowCollectionBehavior::FullScreenAuxiliary,
    );
    round_window_corners(ns_window);
}

#[cfg(target_os = "macos")]
fn run_on_main<F>(work: F)
where
    F: FnOnce() + Send + 'static,
{
    if objc2::MainThreadMarker::new().is_some() {
        work();
        return;
    }
    dispatch2::DispatchQueue::main().exec_sync(work);
}

#[cfg(target_os = "macos")]
fn round_window_corners(ns_window: &objc2_app_kit::NSWindow) {
    const CORNER_RADIUS: f64 = 16.0;
    ns_window.setOpaque(false);
    ns_window.setBackgroundColor(Some(&objc2_app_kit::NSColor::clearColor()));
    ns_window.setHasShadow(true);
    let Some(content) = ns_window.contentView() else {
        return;
    };
    round_view(&content, CORNER_RADIUS);
    let subviews = content.subviews();
    for index in 0..subviews.count() {
        round_view(&subviews.objectAtIndex(index), CORNER_RADIUS);
    }
    ns_window.invalidateShadow();
}

#[cfg(target_os = "macos")]
fn round_view(view: &objc2_app_kit::NSView, radius: f64) {
    view.setWantsLayer(true);
    if let Some(layer) = view.layer() {
        layer.setCornerRadius(radius);
        layer.setMasksToBounds(true);
    }
    if !view
        .class()
        .name()
        .to_bytes()
        .windows(9)
        .any(|name| name == b"WKWebView")
    {
        return;
    }
    let key = objc2_foundation::NSString::from_str("drawsBackground");
    let no = objc2_foundation::NSNumber::numberWithBool(false);
    unsafe {
        let _: () = objc2::msg_send![view, setValue: &*no, forKey: &*key];
    }
}

fn position_main_window(window: &WebviewWindow) {
    if position_window_below_tray(window) {
        return;
    }
    if window.is_visible().unwrap_or(false) {
        return;
    }
    position_window_bottom_right(window, POPUP_WIDTH, POPUP_HEIGHT);
}

fn monitor_containing(window: &WebviewWindow, x: f64, y: f64) -> Option<Monitor> {
    window
        .available_monitors()
        .ok()?
        .into_iter()
        .find(|monitor| {
            let position = monitor.position();
            let size = monitor.size();
            let left = position.x as f64;
            let top = position.y as f64;
            x >= left && x < left + size.width as f64 && y >= top && y < top + size.height as f64
        })
}

fn tray_anchor(window: &WebviewWindow) -> Option<(f64, f64, f64, f64)> {
    if let Ok(slot) = window.app_handle().state::<PopupState>().tray_anchor.lock() {
        if slot.is_some() {
            return *slot;
        }
    }
    let tray = window.app_handle().tray_by_id("tray")?;
    tray.rect()
        .ok()
        .flatten()
        .as_ref()
        .and_then(anchor_from_rect)
}

fn position_window_below_tray(window: &WebviewWindow) -> bool {
    let Some((origin_x, origin_y, tray_width, tray_height)) = tray_anchor(window) else {
        return false;
    };
    let scale = window.scale_factor().unwrap_or(1.0);
    let Some(monitor) = monitor_containing(
        window,
        origin_x + tray_width / 2.0,
        origin_y + tray_height / 2.0,
    ) else {
        return false;
    };
    let Ok(window_size) = window.outer_size() else {
        return false;
    };
    let area = monitor.work_area();
    let (x, y) = tray_position::below_tray(
        (origin_x, origin_y, tray_width, tray_height),
        (
            window_size.width as f64 / scale * monitor.scale_factor(),
            window_size.height as f64 / scale * monitor.scale_factor(),
        ),
        (
            area.position.x as f64,
            area.position.y as f64,
            area.size.width as f64,
            area.size.height as f64,
        ),
        6.0 * monitor.scale_factor(),
    );
    let monitor_scale = monitor.scale_factor();
    window
        .set_position(Position::Logical(LogicalPosition::new(
            x as f64 / monitor_scale,
            y as f64 / monitor_scale,
        )))
        .is_ok()
}

fn position_window_bottom_right(window: &WebviewWindow, fallback_width: i32, fallback_height: i32) {
    let Ok(Some(monitor)) = window.primary_monitor() else {
        return;
    };
    let monitor_scale = monitor.scale_factor();
    let work_area = monitor.work_area();
    let work_x = work_area.position.x as f64 / monitor_scale;
    let work_y = work_area.position.y as f64 / monitor_scale;
    let work_width = work_area.size.width as f64 / monitor_scale;
    let work_height = work_area.size.height as f64 / monitor_scale;
    let window_scale = window.scale_factor().unwrap_or(1.0);
    let window_size = window
        .outer_size()
        .ok()
        .map(|size| {
            (
                size.width as f64 / window_scale,
                size.height as f64 / window_scale,
            )
        })
        .unwrap_or((fallback_width as f64, fallback_height as f64));
    let x = work_x + work_width - window_size.0 - WINDOW_MARGIN as f64;
    let y = work_y + work_height - window_size.1 - WINDOW_MARGIN as f64;
    let _ = window.set_position(Position::Logical(LogicalPosition::new(
        x.max(work_x),
        y.max(work_y),
    )));
}
