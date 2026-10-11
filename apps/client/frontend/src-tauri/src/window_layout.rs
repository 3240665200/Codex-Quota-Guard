#[derive(Debug, Clone, Copy, PartialEq, Eq)]
struct PhysicalSize {
    width: u32,
    height: u32,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
struct PhysicalPosition {
    x: i32,
    y: i32,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub(crate) struct LogicalSize {
    pub(crate) width: u32,
    pub(crate) height: u32,
}

#[derive(Debug, Clone, Copy)]
struct WindowMetrics {
    work_area_x: i32,
    work_area_y: i32,
    work_area_width: u32,
    work_area_height: u32,
    scale_factor: f64,
    inner_size: PhysicalSize,
    outer_size: PhysicalSize,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
struct WindowLayout {
    inner_size: PhysicalSize,
    min_inner_size: PhysicalSize,
    outer_position: PhysicalPosition,
}

const DEFAULT_WIDTH: f64 = 960.0;
const DEFAULT_HEIGHT: f64 = 640.0;
const MIN_WIDTH: f64 = 800.0;
const MIN_HEIGHT: f64 = 520.0;

fn compute_initial_layout(metrics: WindowMetrics) -> Option<WindowLayout> {
    compute_layout(
        metrics,
        LogicalSize {
            width: DEFAULT_WIDTH as u32,
            height: DEFAULT_HEIGHT as u32,
        },
        true,
    )
}

fn compute_layout(
    metrics: WindowMetrics,
    desired_size: LogicalSize,
    use_default_margin: bool,
) -> Option<WindowLayout> {
    if metrics.work_area_width == 0
        || metrics.work_area_height == 0
        || !metrics.scale_factor.is_finite()
        || metrics.scale_factor <= 0.0
    {
        return None;
    }

    let frame_width = metrics
        .outer_size
        .width
        .checked_sub(metrics.inner_size.width)?;
    let frame_height = metrics
        .outer_size
        .height
        .checked_sub(metrics.inner_size.height)?;

    let desired_width = logical_to_physical(f64::from(desired_size.width), metrics.scale_factor)?;
    let desired_height = logical_to_physical(f64::from(desired_size.height), metrics.scale_factor)?;
    let minimum_width = logical_to_physical(MIN_WIDTH, metrics.scale_factor)?;
    let minimum_height = logical_to_physical(MIN_HEIGHT, metrics.scale_factor)?;
    let max_inner_width = content_limit(metrics.work_area_width, frame_width, use_default_margin)?;
    let max_inner_height =
        content_limit(metrics.work_area_height, frame_height, use_default_margin)?;

    let inner_size = PhysicalSize {
        width: desired_width.min(max_inner_width),
        height: desired_height.min(max_inner_height),
    };
    let min_inner_size = PhysicalSize {
        width: minimum_width.min(inner_size.width),
        height: minimum_height.min(inner_size.height),
    };
    let outer_width = inner_size.width.checked_add(frame_width)?;
    let outer_height = inner_size.height.checked_add(frame_height)?;
    let offset_x = i64::from(metrics.work_area_width - outer_width) / 2;
    let offset_y = i64::from(metrics.work_area_height - outer_height) / 2;
    let position_x = i64::from(metrics.work_area_x) + offset_x;
    let position_y = i64::from(metrics.work_area_y) + offset_y;

    Some(WindowLayout {
        inner_size,
        min_inner_size,
        outer_position: PhysicalPosition {
            x: i32::try_from(position_x).ok()?,
            y: i32::try_from(position_y).ok()?,
        },
    })
}

fn logical_to_physical(logical: f64, scale_factor: f64) -> Option<u32> {
    let scaled = logical * scale_factor;
    if !scaled.is_finite() || scaled <= 0.0 {
        return None;
    }

    let rounded = scaled.round();
    if rounded < 1.0 || rounded > f64::from(u32::MAX) {
        return None;
    }

    Some(rounded as u32)
}

fn content_limit(
    work_area_extent: u32,
    frame_extent: u32,
    use_default_margin: bool,
) -> Option<u32> {
    let recommended_outer_limit = if use_default_margin {
        (u64::from(work_area_extent) * 90 / 100) as u32
    } else {
        work_area_extent
    };
    // If the native frame alone uses 90% of the work area, use the full area
    // as a hard cap so a positive client area can still fit without overflow.
    let outer_limit = if recommended_outer_limit > frame_extent {
        recommended_outer_limit
    } else {
        work_area_extent
    };
    let content_limit = outer_limit.checked_sub(frame_extent)?;

    (content_limit > 0).then_some(content_limit)
}

pub(crate) fn apply_initial_layout(
    window: &tauri::WebviewWindow,
    saved_size: Option<LogicalSize>,
) -> tauri::Result<Option<LogicalSize>> {
    let monitor = match window.current_monitor()? {
        Some(monitor) => Some(monitor),
        None => window.primary_monitor()?,
    };
    let Some(monitor) = monitor else {
        return Ok(None);
    };

    let inner_size = window.inner_size()?;
    let outer_size = window.outer_size()?;
    let work_area = monitor.work_area();
    let metrics = WindowMetrics {
        work_area_x: work_area.position.x,
        work_area_y: work_area.position.y,
        work_area_width: work_area.size.width,
        work_area_height: work_area.size.height,
        scale_factor: monitor.scale_factor(),
        inner_size: PhysicalSize {
            width: inner_size.width,
            height: inner_size.height,
        },
        outer_size: PhysicalSize {
            width: outer_size.width,
            height: outer_size.height,
        },
    };
    let layout = match saved_size {
        Some(size) => compute_layout(metrics, size, false),
        None => compute_initial_layout(metrics),
    };
    let Some(layout) = layout else {
        return Ok(None);
    };

    window.set_min_size(Some(tauri::PhysicalSize::new(
        layout.min_inner_size.width,
        layout.min_inner_size.height,
    )))?;
    window.set_size(tauri::PhysicalSize::new(
        layout.inner_size.width,
        layout.inner_size.height,
    ))?;
    window.set_position(tauri::PhysicalPosition::new(
        layout.outer_position.x,
        layout.outer_position.y,
    ))?;

    Ok(physical_size_to_logical(
        layout.inner_size,
        monitor.scale_factor(),
    ))
}

pub(crate) fn current_logical_size(window: &tauri::WebviewWindow) -> Option<LogicalSize> {
    let size = window.inner_size().ok()?;
    let scale_factor = window.scale_factor().ok()?;
    logical_size_from_tauri(size, scale_factor)
}

fn physical_size_to_logical(size: PhysicalSize, scale_factor: f64) -> Option<LogicalSize> {
    if !scale_factor.is_finite() || scale_factor <= 0.0 {
        return None;
    }

    let width = (f64::from(size.width) / scale_factor).round();
    let height = (f64::from(size.height) / scale_factor).round();
    if !width.is_finite()
        || !height.is_finite()
        || width < 1.0
        || height < 1.0
        || width > f64::from(u32::MAX)
        || height > f64::from(u32::MAX)
    {
        return None;
    }

    Some(LogicalSize {
        width: width as u32,
        height: height as u32,
    })
}

pub(crate) fn logical_size_from_tauri(
    size: tauri::PhysicalSize<u32>,
    scale_factor: f64,
) -> Option<LogicalSize> {
    physical_size_to_logical(
        PhysicalSize {
            width: size.width,
            height: size.height,
        },
        scale_factor,
    )
}

#[cfg(test)]
mod tests {
    use super::*;

    fn metrics(
        work_area: (i32, i32, u32, u32),
        scale_factor: f64,
        inner_size: (u32, u32),
        frame_size: (u32, u32),
    ) -> WindowMetrics {
        WindowMetrics {
            work_area_x: work_area.0,
            work_area_y: work_area.1,
            work_area_width: work_area.2,
            work_area_height: work_area.3,
            scale_factor,
            inner_size: PhysicalSize {
                width: inner_size.0,
                height: inner_size.1,
            },
            outer_size: PhysicalSize {
                width: inner_size.0.saturating_add(frame_size.0),
                height: inner_size.1.saturating_add(frame_size.1),
            },
        }
    }

    fn layout(metrics: WindowMetrics) -> WindowLayout {
        compute_initial_layout(metrics).expect("valid monitor metadata should produce a layout")
    }

    fn assert_fits_work_area(metrics: WindowMetrics, layout: WindowLayout, limit_percent: u32) {
        let frame_width = metrics.outer_size.width - metrics.inner_size.width;
        let frame_height = metrics.outer_size.height - metrics.inner_size.height;
        let outer_width = layout.inner_size.width + frame_width;
        let outer_height = layout.inner_size.height + frame_height;
        let max_width = metrics.work_area_width * limit_percent / 100;
        let max_height = metrics.work_area_height * limit_percent / 100;

        assert!(outer_width <= max_width);
        assert!(outer_height <= max_height);
    }

    #[test]
    fn keeps_default_size_on_a_large_standard_dpi_display_and_centers_it() {
        let metrics = metrics((0, 0, 1920, 1080), 1.0, (960, 640), (16, 39));
        let layout = layout(metrics);

        assert_eq!(
            layout.inner_size,
            PhysicalSize {
                width: 960,
                height: 640
            }
        );
        assert_eq!(
            layout.min_inner_size,
            PhysicalSize {
                width: 800,
                height: 520
            }
        );
        assert_eq!(layout.outer_position, PhysicalPosition { x: 472, y: 200 });
        assert_fits_work_area(metrics, layout, 90);
    }

    #[test]
    fn shrinks_to_ninety_percent_of_a_small_work_area_and_respects_its_offset() {
        let metrics = metrics((1440, 80, 1000, 700), 1.0, (960, 640), (16, 39));
        let layout = layout(metrics);

        assert_eq!(
            layout.inner_size,
            PhysicalSize {
                width: 884,
                height: 591
            }
        );
        assert_eq!(
            layout.min_inner_size,
            PhysicalSize {
                width: 800,
                height: 520
            }
        );
        assert_eq!(layout.outer_position, PhysicalPosition { x: 1490, y: 115 });
        assert_fits_work_area(metrics, layout, 90);
    }

    #[test]
    fn accounts_for_one_hundred_fifty_percent_scaling() {
        let metrics = metrics((0, 0, 1920, 1080), 1.5, (1440, 960), (24, 58));
        let layout = layout(metrics);

        assert_eq!(
            layout.inner_size,
            PhysicalSize {
                width: 1440,
                height: 914
            }
        );
        assert_eq!(
            layout.min_inner_size,
            PhysicalSize {
                width: 1200,
                height: 780
            }
        );
        assert_eq!(layout.outer_position, PhysicalPosition { x: 228, y: 54 });
        assert_fits_work_area(metrics, layout, 90);
    }

    #[test]
    fn accounts_for_two_hundred_percent_scaling_and_clamps_minimum_to_initial_size() {
        let metrics = metrics((0, 0, 1920, 1080), 2.0, (1920, 1280), (32, 78));
        let layout = layout(metrics);

        assert_eq!(
            layout.inner_size,
            PhysicalSize {
                width: 1696,
                height: 894
            }
        );
        assert_eq!(
            layout.min_inner_size,
            PhysicalSize {
                width: 1600,
                height: 894
            }
        );
        assert_eq!(layout.outer_position, PhysicalPosition { x: 96, y: 54 });
        assert!(layout.min_inner_size.width <= layout.inner_size.width);
        assert!(layout.min_inner_size.height <= layout.inner_size.height);
        assert_fits_work_area(metrics, layout, 90);
    }

    #[test]
    fn centers_using_negative_work_area_origins() {
        let metrics = metrics((-1920, 50, 1280, 800), 1.0, (960, 640), (16, 39));
        let layout = layout(metrics);

        assert_eq!(
            layout.inner_size,
            PhysicalSize {
                width: 960,
                height: 640
            }
        );
        assert_eq!(layout.outer_position, PhysicalPosition { x: -1768, y: 110 });
    }

    #[test]
    fn lowers_minimums_below_the_normal_minimum_when_the_screen_is_small() {
        let metrics = metrics((0, 0, 160, 120), 1.0, (960, 640), (12, 32));
        let layout = layout(metrics);

        assert_eq!(
            layout.inner_size,
            PhysicalSize {
                width: 132,
                height: 76
            }
        );
        assert_eq!(layout.min_inner_size, layout.inner_size);
        assert_fits_work_area(metrics, layout, 90);
    }

    #[test]
    fn handles_a_tiny_work_area_when_its_native_frame_can_still_fit() {
        let metrics = metrics((25, -10, 80, 60), 1.0, (960, 640), (4, 6));
        let layout = layout(metrics);

        assert_eq!(
            layout.inner_size,
            PhysicalSize {
                width: 68,
                height: 48
            }
        );
        assert_eq!(layout.min_inner_size, layout.inner_size);
        assert_eq!(layout.outer_position, PhysicalPosition { x: 29, y: -7 });
        assert_fits_work_area(metrics, layout, 90);
    }

    #[test]
    fn uses_the_work_area_as_a_hard_cap_if_only_it_can_fit_the_native_frame() {
        let metrics = metrics((0, 0, 20, 20), 1.0, (960, 640), (19, 19));
        let layout = layout(metrics);

        assert_eq!(
            layout.inner_size,
            PhysicalSize {
                width: 1,
                height: 1
            }
        );
        assert_eq!(layout.min_inner_size, layout.inner_size);
        assert_eq!(layout.outer_position, PhysicalPosition { x: 0, y: 0 });
    }

    #[test]
    fn rejects_missing_or_inconsistent_monitor_metadata() {
        let invalid = [
            metrics((0, 0, 0, 100), 1.0, (960, 640), (16, 39)),
            metrics((0, 0, 100, 0), 1.0, (960, 640), (16, 39)),
            metrics((0, 0, 100, 100), 0.0, (960, 640), (16, 39)),
            metrics((0, 0, 100, 100), 0.000_01, (960, 640), (16, 39)),
            metrics((0, 0, 100, 100), f64::NAN, (960, 640), (16, 39)),
            metrics((0, 0, 100, 100), f64::INFINITY, (960, 640), (16, 39)),
            WindowMetrics {
                outer_size: PhysicalSize {
                    width: 900,
                    height: 640,
                },
                ..metrics((0, 0, 100, 100), 1.0, (960, 640), (16, 39))
            },
        ];

        for metrics in invalid {
            assert!(compute_initial_layout(metrics).is_none());
        }
    }

    #[test]
    fn returns_no_layout_if_the_native_frame_itself_cannot_fit() {
        let metrics = metrics((0, 0, 20, 20), 1.0, (960, 640), (24, 39));

        assert!(compute_initial_layout(metrics).is_none());
    }

    #[test]
    fn configured_window_dimensions_match_the_layout_constants() {
        let config: serde_json::Value = serde_json::from_str(include_str!("../tauri.conf.json"))
            .expect("Tauri configuration should be valid JSON");
        let main_window = config["app"]["windows"]
            .as_array()
            .and_then(|windows| windows.iter().find(|window| window["label"] == "main"))
            .expect("Tauri configuration should contain the main window");

        assert_eq!(main_window["width"].as_f64(), Some(DEFAULT_WIDTH));
        assert_eq!(main_window["height"].as_f64(), Some(DEFAULT_HEIGHT));
        assert_eq!(main_window["minWidth"].as_f64(), Some(MIN_WIDTH));
        assert_eq!(main_window["minHeight"].as_f64(), Some(MIN_HEIGHT));
    }

    #[test]
    fn restores_a_saved_size_larger_than_the_default_without_a_default_size_cap() {
        let metrics = metrics((0, 0, 2400, 1600), 1.0, (960, 640), (16, 39));
        let layout = compute_layout(
            metrics,
            LogicalSize {
                width: 1400,
                height: 1000,
            },
            false,
        )
        .expect("saved dimensions should fit the available work area");

        assert_eq!(
            layout.inner_size,
            PhysicalSize {
                width: 1400,
                height: 1000
            }
        );
        assert_fits_work_area(metrics, layout, 100);
    }

    #[test]
    fn saved_logical_dimensions_scale_with_current_display_dpi() {
        let metrics = metrics((0, 0, 2600, 1800), 2.0, (1920, 1280), (32, 78));
        let layout = compute_layout(
            metrics,
            LogicalSize {
                width: 1200,
                height: 800,
            },
            false,
        )
        .expect("scaled saved dimensions should fit");

        assert_eq!(
            layout.inner_size,
            PhysicalSize {
                width: 2400,
                height: 1600
            }
        );
        assert_eq!(
            physical_size_to_logical(layout.inner_size, 2.0),
            Some(LogicalSize {
                width: 1200,
                height: 800
            })
        );
    }

    #[test]
    fn clamps_a_saved_size_to_the_full_small_work_area_instead_of_the_default_ninety_percent() {
        let metrics = metrics((0, 0, 1000, 700), 1.0, (960, 640), (16, 39));
        let layout = compute_layout(
            metrics,
            LogicalSize {
                width: 1500,
                height: 1200,
            },
            false,
        )
        .expect("a saved size should clamp to the work area");

        assert_eq!(
            layout.inner_size,
            PhysicalSize {
                width: 984,
                height: 661
            }
        );
        let outer_width = layout.inner_size.width + 16;
        let outer_height = layout.inner_size.height + 39;
        assert!(outer_width <= metrics.work_area_width);
        assert!(outer_height <= metrics.work_area_height);
    }
}
