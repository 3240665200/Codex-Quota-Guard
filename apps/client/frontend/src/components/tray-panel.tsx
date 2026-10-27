import {
  Activity,
  ArrowUpRight,
  Check,
  CircleAlert,
  Gauge,
  ListChecks,
  LoaderCircle,
  Power,
  RefreshCw,
  Settings2,
  ShieldCheck,
  X,
} from "lucide-react";
import { useEffect, useRef, useState } from "react";
import { Progress } from "./ui/progress";
import {
  formatUsd,
  formatUsdCompact,
  usdPercent,
  type UsdWindow,
} from "@/lib/usd";
import { formatPercent, formatResetTime } from "@/lib/utils";
import { officialQuotaStatus } from "@/lib/official-quota";
import {
  listenTraySnapshots,
  readTraySnapshot,
  trayAction,
  type TrayAction,
  type TraySnapshot,
} from "@/lib/tray-panel";
import "./tray-panel.css";

function Budget({
  label,
  quota,
  mode,
}: {
  label: string;
  quota: UsdWindow;
  mode: "used" | "remaining";
}) {
  const percent = quota.pendingEvents ? null : usdPercent(quota, mode);
  const amount = mode === "used" ? quota.usedUsd : quota.remainingUsd;
  return (
    <div className="cqg-tray-budget">
      <div className="cqg-tray-line">
        <span>{label}</span>
        <strong title={amount === null ? undefined : formatUsd(amount)}>
          {mode === "remaining" && quota.limitUsd === null
            ? "无限制"
            : quota.pendingEvents && mode === "remaining"
              ? "待核算"
              : amount === null
                ? "待核算"
                : formatUsdCompact(amount)}
        </strong>
      </div>
      {percent !== null ? (
        <Progress
          value={percent}
          aria-label={`${label}${mode === "remaining" ? "剩余" : "已用"}额度`}
          className="h-1.5"
        />
      ) : null}
      <p className="cqg-tray-hint">
        已用 {formatUsdCompact(quota.usedUsd)} ·{" "}
        {quota.limitUsd === null
          ? "未设置上限"
          : `上限 ${formatUsdCompact(quota.limitUsd)}`}
        {quota.pendingEvents ? ` · ${quota.pendingEvents} 笔待核算` : ""}
      </p>
    </div>
  );
}

export function TrayPanelView({
  snapshot,
  error,
  onAction,
}: {
  snapshot: TraySnapshot | null;
  error?: boolean;
  onAction: (action: TrayAction) => void;
}) {
  const mode = snapshot?.mode ?? "remaining";
  const official = snapshot?.official;
  const windows =
    official?.windows.filter((window) =>
      [300, 10080].includes(window.windowDurationMins),
    ) ?? [];
  return (
    <main
      className="cqg-tray-panel"
      aria-label="Quota Guard 用量速览"
      onContextMenu={(event) => event.preventDefault()}
    >
      <header className="cqg-tray-header">
        <span className="cqg-tray-logo">
          <ShieldCheck size={21} aria-hidden="true" />
        </span>
        <div>
          <h1>用量速览</h1>
          <p>
            {snapshot?.userName ?? "Quota Guard"}
            {snapshot ? <span className="cqg-tray-me">我</span> : null}
          </p>
        </div>
        <button
          aria-label="刷新实时用量"
          title="刷新实时用量"
          onClick={() => onAction("refresh")}
          disabled={snapshot?.refreshing}
        >
          <RefreshCw
            size={16}
            className={snapshot?.refreshing ? "animate-spin" : ""}
          />
        </button>
        <button aria-label="关闭速览" onClick={() => onAction("close")}>
          <X size={16} />
        </button>
      </header>
      <div className="cqg-tray-scroll">
        {error ? (
          <p role="alert" className="cqg-tray-warning">
            暂时无法同步，请刷新或打开主窗口。
          </p>
        ) : null}
        {!snapshot ? (
          <div className="cqg-tray-empty">
            <Gauge size={28} aria-hidden="true" />
            <h2>登录后查看实时额度</h2>
            <p>官方套餐由服务端定时更新</p>
            <button onClick={() => onAction("show")}>
              打开客户端登录 <ArrowUpRight size={14} />
            </button>
          </div>
        ) : !snapshot.bindingAllowed ? (
          <div className="cqg-tray-empty">
            <ShieldCheck size={28} aria-hidden="true" />
            <h2>请先绑定当前会话</h2>
            <p>绑定后可查看个人与官方套餐额度</p>
            <button onClick={() => onAction("login-assistance")}>
              前往登录辅助 <ArrowUpRight size={14} />
            </button>
          </div>
        ) : (
          <>
            <div className="cqg-tray-sync" role="status">
              <span className={snapshot.quotaFailed ? "is-warning" : ""}>
                {snapshot.refreshing ? (
                  <LoaderCircle size={12} className="animate-spin" />
                ) : snapshot.quotaFailed ? (
                  <CircleAlert size={12} />
                ) : (
                  <Check size={12} />
                )}
                {snapshot.quotaFailed
                  ? "连接暂不可用，显示上次数据"
                  : snapshot.refreshing
                    ? "正在同步最新用量"
                    : "已同步中央服务器"}
              </span>
              <time>
                {snapshot.updatedAt
                  ? new Date(snapshot.updatedAt).toLocaleTimeString("zh-CN", {
                      hour12: false,
                    })
                  : "等待同步"}
              </time>
            </div>
            <section className="cqg-tray-section" aria-label="个人额度">
              <div className="cqg-tray-section-title">
                <h2>
                  <Gauge size={14} aria-hidden="true" />
                  个人额度
                </h2>
                <span>USD · {mode === "remaining" ? "剩余" : "已用"}</span>
              </div>
              {snapshot.personal ? (
                <>
                  <Budget
                    label="5 小时"
                    quota={snapshot.personal.fiveHour}
                    mode={mode}
                  />
                  <Budget
                    label="7 天"
                    quota={snapshot.personal.weekly}
                    mode={mode}
                  />
                </>
              ) : (
                <p className="cqg-tray-hint">个人额度暂不可用</p>
              )}
            </section>
            <section className="cqg-tray-section" aria-label="官方套餐参考">
              <div className="cqg-tray-section-title">
                <h2>
                  <ShieldCheck size={14} aria-hidden="true" />
                  官方套餐参考
                </h2>
                <span>{official?.planType ?? "服务端"}</span>
              </div>
              {official?.accountLabel ? (
                <p className="cqg-tray-account">{official.accountLabel}</p>
              ) : null}
              {windows.map((window) => {
                const label =
                  window.windowDurationMins === 300 ? "5 小时窗口" : "7 天窗口";
                const percent =
                  mode === "remaining"
                    ? 100 - window.usedPercent
                    : window.usedPercent;
                return (
                  <div
                    className="cqg-tray-budget"
                    key={`${window.limitId}:${window.slot}`}
                  >
                    <div className="cqg-tray-line">
                      <span>{label}</span>
                      <strong>
                        {mode === "remaining" ? "剩余" : "已用"}{" "}
                        {formatPercent(percent)}
                      </strong>
                    </div>
                    <Progress
                      value={percent}
                      aria-label={`${label}官方套餐额度`}
                      className="h-1.5"
                    />
                    <p className="cqg-tray-hint">
                      {window.resetsAt
                        ? formatResetTime(window.resetsAt)
                        : "重置时间未知"}
                    </p>
                  </div>
                );
              })}
              <p
                className={`cqg-tray-hint${snapshot.officialFailed || official?.stale ? " is-warning" : ""}`}
              >
                {snapshot.officialFailed
                  ? "服务端套餐数据读取失败"
                  : official
                    ? officialQuotaStatus(official)
                    : "等待服务端套餐数据"}
              </p>
            </section>
            {snapshot.account ? (
              <section
                className="cqg-tray-section"
                aria-label="官方账户美元预算"
              >
                <div className="cqg-tray-section-title">
                  <h2>账户美元预算</h2>
                  <span>USD</span>
                </div>
                <Budget
                  label={snapshot.account.label}
                  quota={snapshot.account.total}
                  mode={mode}
                />
              </section>
            ) : null}
          </>
        )}
      </div>
      <nav className="cqg-tray-actions" aria-label="快捷操作">
        <button onClick={() => onAction("quota")}>
          <Gauge size={15} />
          额度总览
          <ArrowUpRight size={12} />
        </button>
        <button onClick={() => onAction("ledger")}>
          <ListChecks size={15} />
          消耗流水
          <ArrowUpRight size={12} />
        </button>
        <button onClick={() => onAction("diagnostics")}>
          <Activity size={15} />
          统计程序
          <span
            className={`cqg-tray-dot${snapshot?.meterRunning ? " is-running" : ""}`}
          />
        </button>
        <button onClick={() => onAction("settings")}>
          <Settings2 size={15} />
          设置
          <ArrowUpRight size={12} />
        </button>
      </nav>
      <footer className="cqg-tray-footer">
        <span>统计服务独立运行</span>
        <button onClick={() => onAction("quit")}>
          <Power size={13} />
          退出客户端
        </button>
      </footer>
    </main>
  );
}

export function TrayPanel() {
  const [snapshot, setSnapshot] = useState<TraySnapshot | null>(null);
  const [error, setError] = useState(false);
  const refreshButton = useRef<HTMLDivElement>(null);
  const act = (action: TrayAction) => {
    void trayAction(action).catch(() => setError(true));
  };
  useEffect(() => {
    document.body.classList.add("cqg-tray-body");
    let active = true;
    const accept = (value: TraySnapshot | null) => {
      if (!active) return;
      setSnapshot(value);
      setError(false);
      if (value) {
        document.documentElement.classList.toggle(
          "dark",
          value.theme === "dark",
        );
        document.documentElement.style.colorScheme = value.theme;
      }
    };
    const stop = listenTraySnapshots(accept);
    void stop
      .then(() => readTraySnapshot())
      .then(accept)
      .catch(() => {
        if (active) setError(true);
      });
    const refresh = () => {
      if (!document.hidden)
        void trayAction("refresh").catch(() => {
          if (active) setError(true);
        });
    };
    const timer = window.setInterval(refresh, 15_000);
    const escape = (event: KeyboardEvent) => {
      if (event.key === "Escape") {
        event.preventDefault();
        act("close");
      }
    };
    const focus = () => {
      refresh();
      refreshButton.current
        ?.querySelector<HTMLButtonElement>("button")
        ?.focus();
    };
    window.addEventListener("keydown", escape);
    window.addEventListener("focus", focus);
    refresh();
    return () => {
      active = false;
      window.clearInterval(timer);
      window.removeEventListener("keydown", escape);
      window.removeEventListener("focus", focus);
      void stop.then((unlisten) => unlisten()).catch(() => {});
      document.body.classList.remove("cqg-tray-body");
    };
  }, []);
  return (
    <div ref={refreshButton} className="cqg-tray-canvas">
      <TrayPanelView snapshot={snapshot} error={error} onAction={act} />
    </div>
  );
}
