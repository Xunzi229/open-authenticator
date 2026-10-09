// All inputs are physical desktop pixels. Work areas can have negative origins.

/// Size left after removing the invisible frame around a shadow window.
pub fn visible_size(outer: (f64, f64), insets: (f64, f64, f64, f64)) -> (f64, f64) {
    (
        (outer.0 - insets.0 - insets.2).max(1.0),
        (outer.1 - insets.1 - insets.3).max(1.0),
    )
}

/// Outer top-left for a visible origin. Windows keeps the shadow inside the window rect.
pub fn outer_origin(visible_x: f64, visible_y: f64, inset_left: f64, inset_top: f64) -> (i32, i32) {
    (
        (visible_x - inset_left).round() as i32,
        (visible_y - inset_top).round() as i32,
    )
}

pub fn below_tray(
    tray: (f64, f64, f64, f64),
    window: (f64, f64),
    work: (f64, f64, f64, f64),
    gap: f64,
) -> (i32, i32) {
    let left = work.0 + gap;
    let top = work.1 + gap;
    let right = (work.0 + work.2 - window.0 - gap).max(left);
    let bottom = (work.1 + work.3 - window.1 - gap).max(top);
    let x = (tray.0 + tray.2 / 2.0 - window.0 / 2.0).clamp(left, right);
    let y = (tray.1 + tray.3 + gap).clamp(top, bottom);
    (x.round() as i32, y.round() as i32)
}

#[cfg(test)]
mod tests {
    use super::below_tray;

    #[test]
    fn centers_below_menu_bar_and_stays_anchored_after_resize() {
        let tray = (700.0, 0.0, 24.0, 24.0);
        let work = (0.0, 24.0, 1440.0, 876.0);
        assert_eq!(below_tray(tray, (420.0, 540.0), work, 6.0), (502, 30));
        assert_eq!(below_tray(tray, (420.0, 650.0), work, 6.0), (502, 30));
    }

    #[test]
    fn clamps_to_right_edge_on_retina_monitor() {
        assert_eq!(
            below_tray(
                (2800.0, 0.0, 48.0, 48.0),
                (840.0, 1080.0),
                (0.0, 48.0, 2880.0, 1752.0),
                12.0
            ),
            (2028, 60)
        );
    }

    #[test]
    fn supports_secondary_monitor_with_negative_origin() {
        assert_eq!(
            below_tray(
                (-1900.0, -200.0, 24.0, 24.0),
                (420.0, 540.0),
                (-1920.0, -176.0, 1920.0, 1056.0),
                6.0
            ),
            (-1914, -170)
        );
    }

    #[test]
    fn shadow_frame_does_not_leave_a_gap_above_the_work_area() {
        let insets = (8.0, 0.0, 8.0, 12.0);
        let visible = super::visible_size((456.0, 800.0), insets);
        let work = (0.0, 0.0, 1920.0, 1040.0);
        let (x, y) = super::below_tray((1800.0, 1040.0, 40.0, 40.0), visible, work, 6.0);
        let (outer_x, outer_y) = super::outer_origin(x as f64, y as f64, insets.0, insets.1);
        let visible_bottom = outer_y as f64 + insets.1 + visible.1;
        assert_eq!((outer_x, outer_y), (x - 8, y));
        assert_eq!(visible_bottom, work.1 + work.3 - 6.0);
    }

    #[test]
    fn oversized_window_does_not_panic() {
        assert_eq!(
            below_tray(
                (100.0, 0.0, 24.0, 24.0),
                (420.0, 540.0),
                (0.0, 24.0, 320.0, 300.0),
                6.0
            ),
            (6, 30)
        );
    }
}
