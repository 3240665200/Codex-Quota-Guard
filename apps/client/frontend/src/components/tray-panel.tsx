import {
  Activity,
  ArrowUpRight,
  Check,
  ChevronLeft,
  CircleAlert,
  Gauge,
  KeyRound,
  ListChecks,
  LoaderCircle,
  Power,
  RefreshCw,
  Search,
  Settings2,
  ShieldCheck,
  Wallet,
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

type Category = "personal" | "official" | "account";
const categoryTitles: Record<Category, string> = {
  personal: "个人额度",
  official: "官方套餐参考",
  account: "账户美元预算",
};

function budgetAmount(quota: UsdWindow, mode: "used" | "remaining") {
  if (mode === "remaining" && quota.limitUsd === null) return "无限制";
  if (mode === "remaining" && quota.pendingEvents) return "待核算";
  const amount = mode === "used" ? quota.usedUsd : quota.remainingUsd;
  return amount === null ? "待核算" : formatUsdCompact(amount);
}

function MiniUsage({
  label,
  percent,
  warning,
}: {
  label: string;
  percent: number | null;
  warning?: boolean;
}) {
  return (
    <span className={`cqg-tray-mini${warning ? " is-warning" : ""}`}>
      <span>{label}</span>
      {percent !== null ? (
        <span className="cqg-tray-mini-track" aria-hidden="true">
          <span style={{ width: `${percent}%` }} />
        </span>
      ) : null}
    </span>
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
  const [category, setCategory] = useState<Category>("personal");
  const [tab, setTab] = useState<"usage" | "shortcuts">("usage");
  const [search, setSearch] = useState("");
  const [detailOpen, setDetailOpen] = useState(false);
  const mode = snapshot?.mode ?? "remaining";
  const allowed = !!snapshot?.bindingAllowed;
  const official = allowed ? snapshot?.official : null;
  const personal = allowed ? snapshot?.personal : null;
  const account = allowed ? snapshot?.account : null;
  const windows =
    official?.windows.filter((window) =>
      [300, 10080].includes(window.windowDurationMins),
    ) ?? [];
  const mainWindow =
    windows.find((window) => window.windowDurationMins === 10080) ?? windows[0];
  const matches = (label: string) =>
    label.toLocaleLowerCase().includes(search.trim().toLocaleLowerCase());
  const selectCategory = (next: Category) => {
    setCategory(next);
    setSearch("");
    setDetailOpen(true);
  };
  const shortcuts = [
    {
      action: "quota",
      label: "额度总览",
      description: "个人、团队与账户额度",
      Icon: Gauge,
    },
    {
      action: "ledger",
      label: "消耗流水",
      description: "查看每笔 USD 消耗",
      Icon: ListChecks,
    },
    {
      action: "diagnostics",
      label: "统计程序",
      description: "运行状态与自修复",
      Icon: Activity,
    },
    {
      action: "login-assistance",
      label: "登录辅助",
      description: "绑定会话与登录验证",
      Icon: KeyRound,
    },
  ] as const;
  const syncTime = snapshot?.updatedAt
    ? new Date(snapshot.updatedAt).toLocaleTimeString("zh-CN", {
        hour12: false,
      })
    : "等待同步";
  const rows =
    category === "personal"
      ? ["5 小时", "7 天"]
      : category === "account"
        ? [account?.label ?? "账户预算"]
        : windows.map((window) =>
            window.windowDurationMins === 300 ? "5 小时窗口" : "7 天窗口",
          );
  return (
    <main
      className={`cqg-tray-panel${detailOpen ? " is-detail-open" : ""}`}
      aria-label="Quota Guard 用量速览"
      onContextMenu={(event) => event.preventDefault()}
    >
      <section className="cqg-tray-details" aria-label="额度详情">
        <header className="cqg-tray-detail-header">
          <button
            className="cqg-tray-back"
            aria-label="返回托盘菜单"
            onClick={() => setDetailOpen(false)}
          >
            <ChevronLeft size={17} />
          </button>
          <div>
            <h1>{categoryTitles[category]}</h1>
            <p>
              {category === "official"
                ? (official?.planType ?? "服务端同步")
                : `USD · ${mode === "remaining" ? "剩余额度" : "已用额度"}`}
            </p>
          </div>
          <button
            aria-label="关闭速览"
            title="关闭速览"
            onClick={() => onAction("close")}
          >
            <X size={16} />
          </button>
        </header>
        <label className="cqg-tray-search">
          <Search size={15} aria-hidden="true" />
          <input
            aria-label="搜索额度"
            placeholder="搜索额度..."
            value={search}
            disabled={!allowed}
            onChange={(event) => setSearch(event.target.value)}
          />
        </label>
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
              {category === "personal" ? (
                <section className="cqg-tray-section" aria-label="个人额度">
                  {personal ? (
                    <>
                      {matches("5 小时") ? (
                        <Budget
                          label="5 小时"
                          quota={personal.fiveHour}
                          mode={mode}
                        />
                      ) : null}
                      {matches("7 天") ? (
                        <Budget
                          label="7 天"
                          quota={personal.weekly}
                          mode={mode}
                        />
                      ) : null}
                    </>
                  ) : (
                    <p className="cqg-tray-hint">个人额度暂不可用</p>
                  )}
                </section>
              ) : null}
              {category === "official" ? (
                <section className="cqg-tray-section" aria-label="官方套餐参考">
                  {official?.accountLabel ? (
                    <p className="cqg-tray-account">{official.accountLabel}</p>
                  ) : null}
                  {windows.map((window) => {
                    const label =
                      window.windowDurationMins === 300
                        ? "5 小时窗口"
                        : "7 天窗口";
                    const percent =
                      mode === "remaining"
                        ? 100 - window.usedPercent
                        : window.usedPercent;
                    if (!matches(label)) return null;
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
              ) : null}
              {category === "account" && account && matches(account.label) ? (
                <section
                  className="cqg-tray-section"
                  aria-label="官方账户美元预算"
                >
                  <Budget
                    label={account.label}
                    quota={account.total}
                    mode={mode}
                  />
                </section>
              ) : null}
              {search.trim() && !rows.some(matches) ? (
                <p className="cqg-tray-no-results">没有匹配的额度</p>
              ) : null}
            </>
          )}
        </div>
        <div className="cqg-tray-sync" role="status">
          <span className={snapshot?.quotaFailed ? "is-warning" : ""}>
            {snapshot?.refreshing ? (
              <LoaderCircle size={12} className="animate-spin" />
            ) : snapshot?.quotaFailed ? (
              <CircleAlert size={12} />
            ) : (
              <Check size={12} />
            )}
            {snapshot?.quotaFailed
              ? "连接暂不可用，显示上次数据"
              : snapshot?.refreshing
                ? "正在同步最新用量"
                : snapshot
                  ? "已同步中央服务器"
                  : "尚未登录"}
          </span>
          <time>{syncTime}</time>
        </div>
      </section>
      <aside className="cqg-tray-menu" aria-label="托盘菜单">
        <header className="cqg-tray-header">
          <h2>Quota Guard</h2>
          <button
            aria-label="设置"
            title="设置"
            onClick={() => onAction("settings")}
          >
            <Settings2 size={18} />
          </button>
        </header>
        <div className="cqg-tray-tabs" role="tablist" aria-label="菜单内容">
          {(
            [
              ["usage", "用量"],
              ["shortcuts", "快捷"],
            ] as const
          ).map(([value, label]) => (
            <button
              key={value}
              role="tab"
              aria-selected={tab === value}
              id={`tray-tab-${value}`}
              aria-controls="tray-menu-content"
              tabIndex={tab === value ? 0 : -1}
              onClick={() => setTab(value)}
              onKeyDown={(event) => {
                if (
                  ["ArrowLeft", "ArrowRight", "Home", "End"].includes(event.key)
                ) {
                  event.preventDefault();
                  const next =
                    event.key === "Home"
                      ? "usage"
                      : event.key === "End"
                        ? "shortcuts"
                        : value === "usage"
                          ? "shortcuts"
                          : "usage";
                  setTab(next);
                  document.getElementById(`tray-tab-${next}`)?.focus();
                }
              }}
            >
              {label}
            </button>
          ))}
        </div>
        <div
          id="tray-menu-content"
          role="tabpanel"
          aria-labelledby={`tray-tab-${tab}`}
          className="cqg-tray-menu-content"
        >
          {tab === "usage" ? (
            <nav aria-label="用量分类" className="cqg-tray-categories">
              {(
                [
                  {
                    id: "personal",
                    title: "个人额度",
                    description: snapshot?.userName ?? "尚未登录",
                    Icon: Gauge,
                    available: allowed,
                  },
                  {
                    id: "official",
                    title: "官方套餐参考",
                    description: official?.accountLabel ?? "中央服务器",
                    Icon: ShieldCheck,
                    available: allowed,
                  },
                  {
                    id: "account",
                    title: "账户美元预算",
                    description: account?.label ?? "尚未关联账户",
                    Icon: Wallet,
                    available: !!account,
                  },
                ] as const
              ).map(({ id, title, description, Icon, available }) => (
                <button
                  key={id}
                  className={`cqg-tray-category${category === id ? " is-selected" : ""}`}
                  aria-label={`查看${title}`}
                  aria-pressed={category === id}
                  disabled={!available}
                  onClick={() => selectCategory(id)}
                >
                  <span className="cqg-tray-app-icon">
                    <Icon size={22} />
                  </span>
                  <span className="cqg-tray-category-copy">
                    <strong>{title}</strong>
                    <span className="cqg-tray-category-description">
                      <span className="cqg-tray-description-name">
                        {description}
                      </span>
                      {id === "personal" && snapshot ? (
                        <span className="cqg-tray-me">我</span>
                      ) : null}
                    </span>
                    {id === "personal" && personal ? (
                      <MiniUsage
                        label={`7 天${mode === "remaining" ? "剩余" : "已用"} ${budgetAmount(personal.weekly, mode)}${personal.weekly.pendingEvents && mode === "used" ? " · 待核算" : ""}`}
                        percent={
                          personal.weekly.pendingEvents
                            ? null
                            : usdPercent(personal.weekly, mode)
                        }
                        warning={!!personal.weekly.pendingEvents}
                      />
                    ) : null}
                    {id === "official" ? (
                      <MiniUsage
                        label={
                          snapshot?.officialFailed
                            ? "套餐查询失败"
                            : mainWindow
                              ? `${mainWindow.windowDurationMins === 300 ? "5 小时" : "每周"}${mode === "remaining" ? "剩余" : "已用"} ${formatPercent(mode === "remaining" ? 100 - mainWindow.usedPercent : mainWindow.usedPercent)}${official?.stale ? " · 上次数据" : ""}`
                              : "等待服务端套餐数据"
                        }
                        percent={
                          mainWindow && !snapshot?.officialFailed
                            ? mode === "remaining"
                              ? 100 - mainWindow.usedPercent
                              : mainWindow.usedPercent
                            : null
                        }
                        warning={snapshot?.officialFailed || official?.stale}
                      />
                    ) : null}
                    {id === "account" && account ? (
                      <MiniUsage
                        label={`${mode === "remaining" ? "剩余" : "已用"} ${budgetAmount(account.total, mode)}${account.total.pendingEvents && mode === "used" ? " · 待核算" : ""}`}
                        percent={
                          account.total.pendingEvents
                            ? null
                            : usdPercent(account.total, mode)
                        }
                        warning={!!account.total.pendingEvents}
                      />
                    ) : null}
                  </span>
                  <ChevronLeft className="cqg-tray-category-arrow" size={15} />
                </button>
              ))}
            </nav>
          ) : (
            <nav className="cqg-tray-shortcuts" aria-label="快捷操作">
              {shortcuts.map(({ action, label, description, Icon }) => (
                <button key={action} onClick={() => onAction(action)}>
                  <span className="cqg-tray-app-icon">
                    <Icon size={21} />
                  </span>
                  <span className="cqg-tray-category-copy">
                    <strong>{label}</strong>
                    <span className="cqg-tray-category-description">
                      {description}
                    </span>
                  </span>
                  <ArrowUpRight size={15} />
                </button>
              ))}
            </nav>
          )}
          <button
            className="cqg-tray-meter"
            aria-label="查看统计服务状态"
            onClick={() => onAction("diagnostics")}
          >
            <Activity size={15} />
            <span>统计服务独立运行</span>
            <span
              className={`cqg-tray-dot${snapshot?.meterRunning ? " is-running" : ""}`}
            />
          </button>
        </div>
        <div className="cqg-tray-refresh-row">
          <button
            aria-label="刷新实时用量"
            onClick={() => onAction("refresh")}
            disabled={snapshot?.refreshing}
          >
            <RefreshCw
              size={15}
              className={snapshot?.refreshing ? "animate-spin" : ""}
            />
            <span>刷新用量</span>
            <span className="cqg-tray-auto-sync">每 15 秒同步</span>
          </button>
        </div>
        <footer className="cqg-tray-footer">
          <button onClick={() => onAction("show")}>
            <ArrowUpRight size={16} />
            打开主窗口
          </button>
          <button onClick={() => onAction("quit")}>
            <Power size={16} />
            退出客户端
          </button>
        </footer>
      </aside>
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
      Array.from(
        refreshButton.current?.querySelectorAll<HTMLButtonElement>("button") ??
          [],
      )
        .find(
          (button) => !button.disabled && button.getClientRects().length > 0,
        )
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
