//! Public, read-only service checks. No credentials or journal contents enter UI.
use crate::meter_service::{self, MeterVersionState, ServiceObservation};
use serde::Serialize;

#[derive(Clone, Debug, Serialize)]
#[serde(rename_all = "camelCase")]
pub(crate) struct Check {
    code: &'static str,
    label: &'static str,
    state: &'static str,
    detail: &'static str,
    repairable: bool,
}

#[derive(Debug, Serialize)]
#[serde(rename_all = "camelCase")]
pub(crate) struct Diagnostics {
    supported: bool,
    checked_at_epoch_seconds: u64,
    checks: Vec<Check>,
}

fn check(
    code: &'static str,
    label: &'static str,
    pass: bool,
    detail: &'static str,
    repairable: bool,
) -> Check {
    Check {
        code,
        label,
        state: if pass { "PASS" } else { "WARNING" },
        detail,
        repairable: !pass && repairable,
    }
}

pub(crate) fn diagnose(app: &tauri::AppHandle) -> Diagnostics {
    let observation = meter_service::observe_service();
    let (automatic, recovery) = meter_service::service_settings();
    let version = meter_service::version_status(app);
    let status = crate::meter_status::meter_status(app);
    let running = observation == ServiceObservation::Running;
    let mut checks = vec![
        check(
            "SERVICE",
            "Windows 统计服务",
            running,
            if running {
                "服务正在后台运行，退出客户端不会停止它。"
            } else {
                "服务未运行或未安装。修复将恢复服务并启动。"
            },
            true,
        ),
        check(
            "AUTOSTART",
            "开机自启动",
            automatic == Some(true),
            if automatic == Some(true) {
                "已设置为 Windows 服务自动启动。"
            } else {
                "自动启动未设置或无法读取，修复可重新配置。"
            },
            true,
        ),
        check(
            "RECOVERY",
            "Windows 故障恢复",
            recovery == Some(true),
            if recovery == Some(true) {
                "进程崩溃或非正常停止后由 Windows 自动重启。"
            } else {
                "故障恢复策略未就绪或无法读取，修复可重新配置。"
            },
            true,
        ),
        check(
            "VERSION",
            "统计程序版本与文件",
            version.status == MeterVersionState::Current,
            if version.status == MeterVersionState::Current {
                "统计程序版本与运行状态正常，较新的独立版本会保留。"
            } else {
                "版本或文件校验未通过，修复将恢复统计程序并保留较新的独立版本。"
            },
            true,
        ),
        check(
            "CONNECTION",
            "服务器配置",
            crate::meter_status::meter_connection_matches(app),
            if crate::meter_status::meter_connection_matches(app) {
                "服务与客户端使用相同的服务器地址。"
            } else {
                "无法确认服务器地址一致。请先在设置中保存正确地址。"
            },
            false,
        ),
        check(
            "SCAN",
            "后台扫描心跳",
            status.last_scan_epoch_seconds.is_some(),
            if status.last_scan_epoch_seconds.is_some() {
                "最近 30 秒内有扫描心跳。"
            } else {
                "暂无新鲜扫描心跳，可尝试修复服务后再次自检。"
            },
            true,
        ),
    ];
    if status.state == "UNPAIRED" {
        checks.push(check(
            "PAIRING",
            "设备配对",
            false,
            "设备尚未配对，请登录系统并确认服务器可以访问。修复不会清除或重建设备身份。",
            false,
        ));
    }
    if guard_repairable(status.error_code.as_deref()) {
        checks.push(check("CC_SWITCH_GUARD","CC Switch 检测记录",false,"旧进程退出后可能留下失效检测记录。修复会备份失效记录并重新建立检测连接，不会结束其他仍在运行的客户端。",true));
    } else if status.error_code.is_some() {
        checks.push(check(
            "REPORT",
            "用量上报",
            false,
            "统计程序仍报告异常，详情见本页错误与日志。网络、配对和队列归属问题需要按提示处理。",
            false,
        ));
    }
    if status.deferred_events.unwrap_or(0) > 0 {
        checks.push(check("REVIEW", "历史用量待核对", false, "时间超出接收范围的记录不会自动入账，请在后台“用量核对”处理。修复不会删除队列或改写时间。", false));
    }
    Diagnostics {
        supported: cfg!(windows),
        checked_at_epoch_seconds: crate::unix_seconds(),
        checks,
    }
}

fn guard_repairable(code:Option<&str>)->bool {
    matches!(code,Some("METER_CC_SWITCH_GUARD_FAILED"|"METER_GUARD_CHECK_STALE"|"METER_GUARD_CHECK_UNAVAILABLE"|"METER_GUARD_THREAD_UNAVAILABLE"))
}
#[cfg(test)]mod tests {use super::*;#[test]fn guard_failures_are_repairable_without_reclassifying_identity_or_queue_failures(){assert!(guard_repairable(Some("METER_CC_SWITCH_GUARD_FAILED")));assert!(guard_repairable(Some("METER_GUARD_CHECK_STALE")));assert!(!guard_repairable(Some("METER_QUEUE_CONTEXT_MISMATCH")));assert!(!guard_repairable(None));}}
