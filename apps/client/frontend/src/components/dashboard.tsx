import { useQuery, useQueryClient } from "@tanstack/react-query";
import {
  useCallback,
  useEffect,
  useRef,
  useState,
  type ReactNode,
} from "react";
import {
  Activity,
  ChartNoAxesCombined,
  CircleAlert,
  Gauge,
  KeyRound,
  ListChecks,
  LogOut,
  PanelLeftClose,
  PanelLeftOpen,
  RefreshCw,
  Search,
  Settings2,
  ShieldCheck,
  UserCircle,
  Users,
} from "lucide-react";
import { useTranslation } from "react-i18next";
import { toast } from "sonner";

import { AppIcon } from "@/components/app-icon";
import { ClientSettings } from "@/components/client-settings";
import { LoginAssistance } from "@/components/login-assistance";
import { SessionBinding } from "@/components/session-binding";
import { subscribeToAssistanceEvents } from "@/lib/assistance-events";
import {
  api,
  bindingGateEvent,
  getApiErrorMessage,
  type OfficialCodexRateLimits,
  type QuotaOverview,
  type SystemSession,
  type TeamMemberUsage,
} from "@/lib/api";
import {
  formatCompactTokenCount,
  formatPercent,
  formatResetTime,
  formatTokenCount,
  cn,
} from "@/lib/utils";
import { Alert, AlertDescription, AlertTitle } from "@/components/ui/alert";
import { Button } from "@/components/ui/button";
import { Card, CardContent, CardHeader } from "@/components/ui/card";
import { Progress } from "@/components/ui/progress";
import { Skeleton } from "@/components/ui/skeleton";
import {
  UsageActivityPanel,
  serviceLabels,
} from "@/components/usage-activity-panel";
import { ThemeSwitcher } from "@/components/theme-switcher";
import { quotaPercent, useQuotaDisplay } from "@/lib/quota-display";
import "./quota-layout.css";

function describeBlockReason(reason: string | null): string {
  switch (reason) {
    case "TOKEN_QUOTA_UNCONFIGURED":
      return "7 天基础额度尚未配置。";
    case "TEAM_UNAVAILABLE":
      return "所属团队不可用。";
    case "FIVE_HOUR_TOKEN_QUOTA_EXCEEDED":
      return "已达到个人 5 小时 Token 限额。";
    case "WEEKLY_TOKEN_QUOTA_EXCEEDED":
      return "已达到个人 7 天 Token 限额。";
    case "TEAM_FIVE_HOUR_TOKEN_QUOTA_EXCEEDED":
      return "所属团队的 5 小时共享 Token 额度已用尽。";
    case "TEAM_WEEKLY_TOKEN_QUOTA_EXCEEDED":
      return "所属团队的 7 天共享 Token 额度已用尽。";
    case "TEAM_TOTAL_TOKEN_QUOTA_EXCEEDED":
      return "所属团队的累计 Token 总额度已用尽。";
    default:
      return "服务端暂无法确认当前个人 Token 额度，请联系管理员。";
  }
}

function TokenAmount({ value }: { value: number }) {
  return (
    <span
      className="cqg-token-amount"
      title={`精确值：${formatTokenCount(value)} Token`}
    >
      <span>{formatCompactTokenCount(value)}</span>{" "}
      <span className="cqg-token-unit">Token</span>
    </span>
  );
}

function QuotaProgress({
  used,
  remaining,
  limit,
  label,
}: {
  used: number;
  remaining: number | null;
  limit: number | null;
  label: string;
}) {
  const { mode, label: displayLabel } = useQuotaDisplay();
  if (limit === null || remaining === null) {
    return (
      <div className="flex justify-between gap-3 text-xs text-muted-foreground">
        <span>{label}</span>
        <span>{limit === null ? "不设限" : "暂不可得"}</span>
      </div>
    );
  }
  const percent = quotaPercent(mode, used, remaining, limit)!;
  const remainingPercent = quotaPercent("remaining", used, remaining, limit)!;
  const exhausted = remaining <= 0 || limit === 0;
  return (
    <div
      className="flex flex-col gap-2"
      role="group"
      aria-label={`${label}${displayLabel}额度`}
    >
      <div className="flex items-center justify-between gap-3 text-xs text-muted-foreground">
        <span>{label}</span>
        <span className="tabular-nums font-medium text-foreground">
          {displayLabel}{" "}
          {new Intl.NumberFormat("zh-CN", { maximumFractionDigits: 1 }).format(
            percent,
          )}
          %
        </span>
      </div>
      <Progress
        aria-label={`${label}${displayLabel}额度`}
        className="h-2 bg-muted"
        indicatorClassName={
          exhausted
            ? "bg-[var(--cqg-status-error)]"
            : remainingPercent <= 20
              ? "bg-[var(--cqg-status-warning)]"
              : "bg-primary"
        }
        value={percent}
      />
    </div>
  );
}

function LoadingCard({ className = "" }: { className?: string }) {
  return (
    <Card className={className} aria-label="正在加载">
      <CardHeader className="gap-3">
        <Skeleton className="h-5 w-2/5" />
        <Skeleton className="h-4 w-3/5" />
      </CardHeader>
      <CardContent className="flex flex-col gap-3">
        <Skeleton className="h-3 w-full" />
        <Skeleton className="h-3 w-4/5" />
      </CardContent>
    </Card>
  );
}

function EmptyState({
  title,
  description,
}: {
  title: string;
  description: string;
}) {
  return (
    <Card>
      <CardContent className="flex flex-col items-center justify-center gap-2 py-14 text-center">
        <Gauge className="mb-2 text-muted-foreground" aria-hidden="true" />
        <p className="font-medium">{title}</p>
        <p className="max-w-md text-sm text-muted-foreground">{description}</p>
      </CardContent>
    </Card>
  );
}

function TeamCard({ overview }: { overview: QuotaOverview }) {
  const { mode, label: displayLabel } = useQuotaDisplay();
  const { teamQuota } = overview;
  const windows = teamQuota
    ? [
        {
          label: "5 小时团队",
          remaining: teamQuota.fiveHourRemainingTokens,
          limit: teamQuota.fiveHourTokenLimit,
          used: teamQuota.fiveHourUsedTokens,
        },
        {
          label: "7 天团队",
          remaining: teamQuota.weeklyRemainingTokens,
          limit: teamQuota.weeklyTokenLimit,
          used: teamQuota.weeklyUsedTokens,
        },
        {
          label: "累计总额",
          remaining: teamQuota.totalRemainingTokens,
          limit: teamQuota.totalTokenLimit,
          used: teamQuota.totalUsedTokens,
        },
      ]
    : [];
  return (
    <section
      className="cqg-surface cqg-summary-card min-w-0 p-4"
      aria-labelledby="team-shared-quota-heading"
    >
      <div className="mb-4 flex flex-wrap items-center justify-between gap-2">
        <h3 id="team-shared-quota-heading" className="text-sm font-semibold">
          团队共享额度
        </h3>
        {teamQuota?.status === "DISABLED" ? (
          <span className="text-xs text-[var(--cqg-status-warning)]">
            团队已停用
          </span>
        ) : null}
      </div>
      {teamQuota ? (
        <div className="cqg-shared-windows cqg-summary-card-content">
          {windows.map((window) => (
            <div className="cqg-shared-window" key={window.label}>
              <QuotaProgress
                used={window.used}
                label={window.label}
                remaining={window.remaining}
                limit={window.limit}
              />
              <div className="mt-3 flex flex-wrap justify-between gap-x-3 gap-y-1 text-xs tabular-nums text-muted-foreground">
                {mode === "used" || window.remaining !== null ? (
                  <span>
                    <TokenAmount
                      value={mode === "used" ? window.used : window.remaining!}
                    />{" "}
                    {displayLabel}
                  </span>
                ) : (
                  <span>
                    已用 <TokenAmount value={window.used} />
                  </span>
                )}
                {window.limit !== null ? (
                  <span>
                    上限 <TokenAmount value={window.limit} />
                  </span>
                ) : null}
              </div>
            </div>
          ))}
        </div>
      ) : (
        <p className="cqg-summary-card-content text-sm text-muted-foreground">
          暂无团队共享额度
        </p>
      )}
    </section>
  );
}

function MemberUsageMetric({
  label,
  ariaLabel,
  percent,
  detail,
  unavailableLabel = "暂不可得",
  exhausted = false,
  tone = "personal",
}: {
  label: string;
  ariaLabel: string;
  percent: number | null;
  detail: string;
  unavailableLabel?: string;
  exhausted?: boolean;
  tone?: "personal" | "team";
}) {
  const percentText =
    percent === null
      ? unavailableLabel
      : `${new Intl.NumberFormat("zh-CN", { maximumFractionDigits: 1 }).format(percent)}%`;
  return (
    <div className="cqg-member-window" role="group" aria-label={ariaLabel}>
      <div className="cqg-member-window-label">
        <span>{label}</span>
        <span
          className={
            exhausted
              ? "text-[var(--cqg-status-error)]"
              : "font-medium text-foreground"
          }
        >
          {percentText}
          {exhausted ? " · 已用尽" : ""}
        </span>
      </div>
      {percent !== null ? (
        <Progress
          aria-label={ariaLabel}
          aria-valuetext={percentText}
          className="h-1.5 bg-muted"
          indicatorClassName={
            exhausted
              ? "bg-[var(--cqg-status-error)]"
              : tone === "team"
                ? "cqg-member-contribution-bar"
                : "bg-primary"
          }
          value={Math.min(100, Math.max(0, percent))}
        />
      ) : null}
      <p className="cqg-member-window-detail">{detail}</p>
    </div>
  );
}

function MemberRemainingWindow({
  label,
  limit,
  remaining,
}: {
  label: string;
  limit: number | null;
  remaining: number | null;
}) {
  const { mode, label: displayLabel } = useQuotaDisplay();
  // Personal remaining and team-contribution usage have different reset boundaries.
  // In used mode show the consumed quota percentage, without relabeling team usage as personal usage.
  const consumedQuota =
    limit === null || remaining === null ? 0 : Math.max(0, limit - remaining);
  const percent = quotaPercent(mode, consumedQuota, remaining, limit);
  return (
    <MemberUsageMetric
      label={label}
      ariaLabel={`${label}个人${displayLabel}额度`}
      percent={percent}
      exhausted={remaining === 0 && limit !== null}
      unavailableLabel={limit === null ? "未设个人限额" : "暂不可得"}
      detail={
        limit !== null && remaining !== null
          ? mode === "remaining"
            ? `剩余 ${formatTokenCount(remaining)} / 上限 ${formatTokenCount(limit)} Token`
            : `个人上限 ${formatTokenCount(limit)} Token${remaining === 0 ? " · 已达到上限" : ""}`
          : limit === null
            ? "受团队共享额度约束"
            : `个人上限 ${formatTokenCount(limit)} Token`
      }
    />
  );
}

function MemberTeamShareWindow({
  label,
  used,
  teamUsed,
}: {
  label: string;
  used: number;
  teamUsed: number | undefined;
}) {
  const inconsistent = teamUsed !== undefined && used > teamUsed;
  return (
    <MemberUsageMetric
      label={label}
      ariaLabel={`${label}团队用量占比`}
      tone="team"
      percent={
        teamUsed === undefined || inconsistent
          ? null
          : teamUsed === 0
            ? 0
            : (used * 100) / teamUsed
      }
      unavailableLabel={inconsistent ? "待刷新" : "暂不可得"}
      detail={
        teamUsed === undefined
          ? `已用 ${formatTokenCount(used)} Token · 团队总量暂不可得`
          : `已用 ${formatTokenCount(used)} / 团队 ${formatTokenCount(teamUsed)} Token${inconsistent ? " · 待刷新" : ""}`
      }
    />
  );
}

function TeamMemberRow({
  member,
  overview,
}: {
  member: TeamMemberUsage;
  overview: QuotaOverview;
}) {
  const { user, teamQuota } = overview;
  return (
    <tr className={member.userId === user.id ? "cqg-member-self" : undefined}>
      <th scope="row">
        <div className="cqg-member-identity">
          <span className="cqg-member-name">
            {member.displayName || "未命名成员"}
          </span>
          {member.userId === user.id ? (
            <span className="cqg-member-self-label">我</span>
          ) : null}
        </div>
        <span
          className={cn(
            "cqg-member-status",
            member.status === "DISABLED" && "cqg-member-disabled",
          )}
        >
          {member.status === "DISABLED" ? "已停用" : "正常"}
        </span>
      </th>
      <td>
        <div className="cqg-member-windows">
          <MemberRemainingWindow
            label="5 小时"
            limit={member.fiveHourTokenLimit}
            remaining={member.fiveHourRemainingTokens}
          />
          <MemberRemainingWindow
            label="7 天"
            limit={member.weeklyTokenLimit}
            remaining={member.weeklyRemainingTokens}
          />
        </div>
      </td>
      <td>
        <div className="cqg-member-windows">
          <MemberTeamShareWindow
            label="5 小时"
            used={member.fiveHourUsedTokens}
            teamUsed={teamQuota?.fiveHourUsedTokens}
          />
          <MemberTeamShareWindow
            label="7 天"
            used={member.weeklyUsedTokens}
            teamUsed={teamQuota?.weeklyUsedTokens}
          />
          <MemberTeamShareWindow
            label="累计"
            used={member.totalUsedTokens}
            teamUsed={teamQuota?.totalUsedTokens}
          />
        </div>
      </td>
    </tr>
  );
}

function TeamMembersCard({ overview }: { overview: QuotaOverview }) {
  const { label: displayLabel } = useQuotaDisplay();
  const [search, setSearch] = useState("");
  const { team, teamMembers } = overview;
  const query = search.trim().toLocaleLowerCase("zh-CN");
  const members = teamMembers
    ?.filter((member) =>
      member.displayName.toLocaleLowerCase("zh-CN").includes(query),
    )
    .sort(
      (left, right) =>
        right.totalUsedTokens - left.totalUsedTokens ||
        left.displayName.localeCompare(right.displayName, "zh-CN") ||
        left.userId.localeCompare(right.userId),
    );
  return (
    <section
      className="cqg-surface cqg-members-card min-w-0"
      aria-labelledby="team-members-heading"
    >
      <div className="cqg-members-toolbar">
        <div className="min-w-0">
          <h3 id="team-members-heading" className="text-sm font-semibold">
            成员用量
          </h3>
          <p className="mt-1 text-xs text-muted-foreground" aria-live="polite">
            {team && teamMembers
              ? `显示 ${members!.length} / ${teamMembers.length} 位成员 · 单位 Token`
              : "当前团队成员的 Token 用量"}
          </p>
        </div>
        {team && teamMembers && teamMembers.length > 0 ? (
          <label className="cqg-member-search">
            <Search className="size-4 shrink-0" aria-hidden="true" />
            <input
              type="search"
              aria-label="搜索成员"
              placeholder="搜索成员姓名"
              value={search}
              onChange={(event) => setSearch(event.target.value)}
            />
          </label>
        ) : null}
      </div>
      {!team ? (
        <p className="cqg-members-empty">加入团队后，可查看团队成员用量。</p>
      ) : teamMembers === null ? (
        <p className="cqg-members-empty" role="status">
          服务器尚未提供成员用量，请更新服务器
        </p>
      ) : teamMembers.length === 0 ? (
        <p className="cqg-members-empty">团队暂无成员</p>
      ) : (
        <>
          <div
            className="cqg-members-scroll"
            role="region"
            aria-label="团队成员用量表格，可横向和纵向滚动"
            tabIndex={0}
          >
            <table className="cqg-members-table">
              <caption className="sr-only">
                当前团队全部成员，按累计用量从高到低排序。个人{displayLabel}
                额度及团队用量占比分窗口显示，单位 Token。
              </caption>
              <thead>
                <tr>
                  <th scope="col">成员</th>
                  <th scope="col">个人{displayLabel}额度</th>
                  <th scope="col">团队用量占比</th>
                </tr>
              </thead>
              <tbody>
                {members!.length === 0 ? (
                  <tr>
                    <td colSpan={3} className="cqg-members-empty">
                      未找到匹配的成员
                    </td>
                  </tr>
                ) : (
                  members!.map((member) => (
                    <TeamMemberRow
                      key={member.userId}
                      member={member}
                      overview={overview}
                    />
                  ))
                )}
              </tbody>
            </table>
          </div>
          <p className="cqg-members-note">
            占比 = 成员已用 ÷
            团队已用；团队总用量可能包含已离队成员的历史用量，当前成员占比合计可能不足100%。
          </p>
        </>
      )}
    </section>
  );
}

function TeamTokenSection({ overview }: { overview: QuotaOverview }) {
  const memberCount =
    overview.teamQuota?.memberCount ?? overview.teamMembers?.length;
  return (
    <section className="cqg-quota-section" aria-labelledby="team-token-heading">
      <header className="cqg-quota-section-heading">
        <Users className="cqg-quota-section-icon" aria-hidden="true" />
        <div className="min-w-0">
          <h2 id="team-token-heading">团队 Token</h2>
          <p>
            {overview.team
              ? `${overview.team.name}${memberCount !== undefined ? ` · ${memberCount} 位成员` : ""}`
              : "未加入团队"}
          </p>
        </div>
      </header>
      <TeamCard overview={overview} />
      <UsageCard overview={overview} />
      <TeamMembersCard
        key={overview.team?.id ?? "no-team"}
        overview={overview}
      />
    </section>
  );
}

function UsageWindow({
  label,
  usedTokens,
  remainingTokens,
  limitTokens,
}: {
  label: string;
  usedTokens: number;
  remainingTokens: number | null;
  limitTokens: number | null;
}) {
  const { mode, label: displayLabel } = useQuotaDisplay();
  const nearLimit =
    remainingTokens !== null &&
    limitTokens !== null &&
    remainingTokens <= Math.max(1, Math.floor(limitTokens * 0.2));
  return (
    <article className="cqg-window cqg-usage-window min-w-0 p-4">
      <header className="cqg-usage-window-header flex items-start justify-between gap-3">
        <h4 className="text-sm font-semibold">{label}</h4>
        <span className="text-xs text-muted-foreground">
          {limitTokens === null ? "不设限" : "个人上限"}
        </span>
      </header>
      <div className="cqg-usage-window-primary">
        <p className="cqg-usage-window-label text-xs text-muted-foreground">
          个人{displayLabel}
        </p>
        <p
          className={`cqg-usage-window-value break-words font-semibold tracking-tight tabular-nums ${nearLimit ? "text-[var(--cqg-status-warning)]" : "text-foreground"}`}
        >
          {mode === "used" ? (
            <TokenAmount value={usedTokens} />
          ) : limitTokens === null ? (
            "不限额"
          ) : remainingTokens === null ? (
            "暂不可得"
          ) : (
            <TokenAmount value={remainingTokens} />
          )}
        </p>
      </div>
      <footer className="cqg-usage-window-footer">
        {limitTokens === null ? (
          <p className="text-xs leading-4 text-muted-foreground">
            本窗口只记录用量，不参与个人限额判断
          </p>
        ) : (
          <>
            <QuotaProgress
              used={usedTokens}
              label={`${label}个人额度`}
              remaining={remainingTokens}
              limit={limitTokens}
            />
            <div className="cqg-usage-window-metrics mt-2 flex flex-wrap justify-between gap-x-2 gap-y-1 text-xs tabular-nums text-muted-foreground">
              <span>
                上限 <TokenAmount value={limitTokens} />
              </span>
            </div>
          </>
        )}
      </footer>
    </article>
  );
}

function UsageCard({ overview }: { overview: QuotaOverview }) {
  const { usage, policy } = overview;
  return (
    <section aria-labelledby="personal-quota-heading">
      <div className="mb-3 flex flex-wrap items-end justify-between gap-2">
        <div>
          <h3
            id="personal-quota-heading"
            className="text-base font-semibold"
            title="个人额度按事件发生时间滚动统计"
          >
            个人 Token
          </h3>
          <p className="mt-1 text-xs text-muted-foreground">
            {usage.measuredAt
              ? `最后上报 ${new Date(usage.measuredAt).toLocaleString("zh-CN")}`
              : "尚无已接收的用量"}
          </p>
        </div>
      </div>
      <div className="cqg-quota-grid">
        <UsageWindow
          label="5 小时"
          usedTokens={usage.fiveHourUsedTokens}
          remainingTokens={usage.fiveHourRemainingTokens}
          limitTokens={policy.fiveHourTokenLimit}
        />
        <UsageWindow
          label="7 天"
          usedTokens={usage.weeklyUsedTokens}
          remainingTokens={usage.weeklyRemainingTokens}
          limitTokens={policy.weeklyTokenLimit}
        />
      </div>
    </section>
  );
}

function OfficialRateLimitWindow({
  label,
  window,
}: {
  label: string;
  window: OfficialCodexRateLimits["windows"][number];
}) {
  const { mode, label: displayLabel } = useQuotaDisplay();
  const remainingPercent = Math.min(100, Math.max(0, 100 - window.usedPercent));
  const percent =
    mode === "remaining"
      ? remainingPercent
      : Math.min(100, Math.max(0, window.usedPercent));
  return (
    <div className="min-w-0">
      <div className="flex items-center justify-between gap-3 text-xs leading-4">
        <span className="font-medium">{label}</span>
        <span className="font-semibold tabular-nums text-primary">
          {displayLabel} {formatPercent(percent)}
        </span>
      </div>
      <div className="mt-2">
        <Progress
          aria-label={`${label}官方${displayLabel}额度`}
          className="h-2 bg-muted"
          indicatorClassName="bg-primary"
          value={percent}
        />
      </div>
      <p className="mt-3 text-xs text-muted-foreground">
        {window.resetsAt ? formatResetTime(window.resetsAt) : "重置时间未知"}
      </p>
    </div>
  );
}

function OfficialRateLimitsCard({
  rateLimits,
  loading,
  failed,
}: {
  rateLimits: OfficialCodexRateLimits | undefined;
  loading: boolean;
  failed: boolean;
}) {
  const fiveHour = rateLimits?.windows.find(
    (window) => window.windowDurationMins === 300,
  );
  const weekly = rateLimits?.windows.find(
    (window) => window.windowDurationMins === 10_080,
  );
  return (
    <section
      className="cqg-quota-section min-w-0"
      aria-labelledby="official-quota-heading"
    >
      <header className="cqg-quota-section-heading">
        <Gauge className="cqg-quota-section-icon" aria-hidden="true" />
        <div className="min-w-0">
          <h2 id="official-quota-heading">官方 Token</h2>
          <p>
            官方额度百分比 ·{" "}
            {loading
              ? "正在读取…"
              : failed
                ? "读取失败，请检查官方登录"
                : rateLimits?.planType
                  ? `${rateLimits.planType} 套餐 · 账户级参考`
                  : "账户级参考"}
          </p>
        </div>
      </header>
      <div className="cqg-surface min-w-0 p-4">
        {fiveHour || weekly ? (
          <div className="cqg-quota-grid gap-5">
            {fiveHour ? (
              <OfficialRateLimitWindow label="5 小时窗口" window={fiveHour} />
            ) : null}
            {weekly ? (
              <OfficialRateLimitWindow label="7 天窗口" window={weekly} />
            ) : null}
          </div>
        ) : (
          <p className="text-sm text-muted-foreground">暂无可显示的官方额度</p>
        )}
        <p className="mt-4 border-t pt-3 text-xs text-muted-foreground">
          官方仅提供额度百分比，不提供 Token 数量；账户额度仅作参考。
        </p>
      </div>
    </section>
  );
}

function AccessCard({ overview }: { overview: QuotaOverview }) {
  if (overview.allowed) {
    return (
      <div
        className="cqg-status-strip cqg-status-ok flex flex-wrap items-center gap-3 px-4 py-3"
        role="status"
      >
        <ShieldCheck
          aria-hidden="true"
          className="size-5 shrink-0 text-[var(--cqg-status-success)]"
        />
        <span className="font-semibold">额度可用</span>
        <span className="text-sm text-muted-foreground">
          尚未达到已设置的 Token 上限
        </span>
      </div>
    );
  }

  const exhausted =
    overview.blockReason === "FIVE_HOUR_TOKEN_QUOTA_EXCEEDED" ||
    overview.blockReason === "WEEKLY_TOKEN_QUOTA_EXCEEDED" ||
    overview.blockReason === "TEAM_FIVE_HOUR_TOKEN_QUOTA_EXCEEDED" ||
    overview.blockReason === "TEAM_WEEKLY_TOKEN_QUOTA_EXCEEDED" ||
    overview.blockReason === "TEAM_TOTAL_TOKEN_QUOTA_EXCEEDED";
  return (
    <div
      className="cqg-status-strip cqg-status-error flex flex-wrap items-center gap-3 px-4 py-3"
      role="alert"
    >
      <CircleAlert
        aria-hidden="true"
        className="size-5 shrink-0 text-[var(--cqg-status-error)]"
      />
      <span className="font-semibold">
        {exhausted
          ? "Token 额度已用尽"
          : overview.blockReason === "TOKEN_QUOTA_UNCONFIGURED"
            ? "Token 额度未配置"
            : "额度暂不可用"}
      </span>
      <span className="text-sm text-muted-foreground">
        {describeBlockReason(overview.blockReason)}
        本应用不会退出官方 Codex 应用或 CLI。
      </span>
    </div>
  );
}

export interface DashboardProps {
  session: SystemSession;
  onLogout: () => void;
  guardNotice?: ReactNode;
}

const workspaces = [
  {
    id: "quota",
    label: "额度总览",
    title: "额度仪表盘",
    description: "团队共享额度、成员用量与官方额度百分比。",
    Icon: Gauge,
  },
  {
    id: "login-assistance",
    label: "登录辅助",
    title: "登录辅助",
    description: "接收所属团队官方账户本次登录的新验证码。",
    Icon: KeyRound,
  },
  {
    id: "analytics",
    label: "用量统计",
    title: "用量统计",
    description: "Token 构成、使用趋势与 USD 费用估算。",
    Icon: ChartNoAxesCombined,
  },
  {
    id: "ledger",
    label: "消耗流水",
    title: "消耗流水",
    description: "已接收的用量记录，点击详情查看完整分项。",
    Icon: ListChecks,
  },
  {
    id: "diagnostics",
    label: "统计程序",
    title: "统计程序",
    description: "运行状态、版本更新与本机诊断日志。",
    Icon: Activity,
  },
  {
    id: "settings",
    label: "设置",
    title: "设置",
    description: "管理桌面客户端的启动偏好。",
    Icon: Settings2,
  },
] as const;
type WorkspaceId = (typeof workspaces)[number]["id"];

export function Dashboard({ session, onLogout, guardNotice }: DashboardProps) {
  const { t } = useTranslation();
  const queryClient = useQueryClient();
  // Keep the shared invalidation stream alive for this signed-in dashboard;
  // navigating away from the assistance cards must not reopen the native SSE.
  useEffect(
    () => subscribeToAssistanceEvents(session.user!.id, () => {}),
    [session.user!.id],
  );
  const [accessEpoch, setAccessEpoch] = useState(0);
  const [checkingReturn, setCheckingReturn] = useState(false);
  const [now, setNow] = useState(Date.now);
  const accessQuery = useQuery({
    queryKey: ["client-access", session.user!.id, accessEpoch],
    queryFn: api.clientAccess,
    retry: false,
    gcTime: 0,
    staleTime: 0,
    refetchInterval: 5_000,
    refetchOnMount: "always",
    refetchOnWindowFocus: "always",
    refetchOnReconnect: "always",
  });
  const recheckAccess = useCallback(
    () => setAccessEpoch((value) => value + 1),
    [],
  );
  const bindingAllowed =
    accessQuery.isSuccess &&
    !accessQuery.isError &&
    accessQuery.data.allowed &&
    now - accessQuery.dataUpdatedAt < 10_000;
  const blockingReturn = bindingAllowed && checkingReturn;
  const { refetch: refetchAccess } = accessQuery;
  const handleBound = (bindingId: string | null) => {
    // Restoring the already-authorized record is not a new binding or a revocation.
    if (bindingAllowed && bindingId === accessQuery.data.bindingId) return;
    // A completion is only a hint: the central access result still decides access.
    void refetchAccess({ cancelRefetch: false });
  };
  useEffect(() => {
    let disposed = false;
    let returnCheck = 0;
    setCheckingReturn(false);
    const recheckOnReturn = () => {
      // Native title-bar interactions can refocus the WebView. Revalidate the
      // current user's query without tearing down a still-fresh workspace.
      const check = ++returnCheck;
      setNow(Date.now());
      setCheckingReturn(true);
      void refetchAccess({ cancelRefetch: false }).finally(() => {
        if (!disposed && check === returnCheck) setCheckingReturn(false);
      });
    };
    const onVisible = () => {
      if (document.visibilityState === "visible") recheckOnReturn();
    };
    window.addEventListener(bindingGateEvent, recheckAccess);
    window.addEventListener("online", recheckAccess);
    window.addEventListener("offline", recheckAccess);
    window.addEventListener("focus", recheckOnReturn);
    document.addEventListener("visibilitychange", onVisible);
    const timer = window.setInterval(() => setNow(Date.now()), 500);
    return () => {
      disposed = true;
      window.removeEventListener(bindingGateEvent, recheckAccess);
      window.removeEventListener("online", recheckAccess);
      window.removeEventListener("offline", recheckAccess);
      window.removeEventListener("focus", recheckOnReturn);
      document.removeEventListener("visibilitychange", onVisible);
      window.clearInterval(timer);
    };
  }, [recheckAccess, refetchAccess, accessEpoch, session.user!.id]);
  const [workspace, setWorkspace] = useState<WorkspaceId>("quota");
  const accessResolvedFor = useRef<string | null>(null);
  if (!accessQuery.isPending) accessResolvedFor.current = session.user!.id;
  const [collapsed, setCollapsed] = useState(false);
  const scrollArea = useRef<HTMLDivElement>(null);
  const visibleWorkspace = bindingAllowed ? workspace : "login-assistance";
  const showAssistance =
    visibleWorkspace === "login-assistance" &&
    (workspace === "login-assistance" ||
      accessResolvedFor.current === session.user!.id);
  const currentPage = workspaces.find((page) => page.id === visibleWorkspace)!;
  const navigate = (next: WorkspaceId) => {
    if (blockingReturn) return;
    if (!bindingAllowed && next !== "login-assistance") return;
    setWorkspace(next);
    if (scrollArea.current) scrollArea.current.scrollTop = 0;
  };
  const quotaQuery = useQuery({
    queryKey: ["quota-overview"],
    queryFn: api.quotaOverview,
    retry: false,
    refetchInterval: 10_000,
    enabled: bindingAllowed,
  });
  const usageStatusQuery = useQuery({
    queryKey: ["usage-status"],
    queryFn: api.usageStatus,
    retry: false,
    refetchInterval: 5_000,
  });
  // Keep version detection active even when the diagnostics page is closed.
  const meterVersionQuery = useQuery({
    queryKey: ["meter-service-version"],
    queryFn: api.meterServiceVersion,
    retry: false,
    refetchInterval: 30_000,
  });
  const overview = quotaQuery.data;
  const rateLimitsQuery = useQuery({
    queryKey: ["official-codex-rate-limits"],
    queryFn: api.codexRateLimits,
    retry: false,
    refetchInterval: 60_000,
    enabled: bindingAllowed,
  });
  const isRefreshing =
    quotaQuery.isFetching ||
    usageStatusQuery.isFetching ||
    rateLimitsQuery.isFetching;
  const refresh = async () => {
    if (blockingReturn) return;
    window.dispatchEvent(new Event("quota-guard:assistance-refresh"));
    if (!bindingAllowed) {
      recheckAccess();
      return;
    }
    try {
      const results = await Promise.all([
        quotaQuery.refetch(),
        usageStatusQuery.refetch(),
        rateLimitsQuery.refetch(),
        meterVersionQuery.refetch(),
      ]);
      await Promise.all(
        ["usage-analytics", "usage-events", "meter-runtime-logs"].map((key) =>
          queryClient.invalidateQueries({ queryKey: [key, session.user?.id] }),
        ),
      );
      const failedResult = results.find((result) => result.error);
      if (failedResult?.error) {
        throw failedResult.error;
      }
      toast.success(t("dataRefreshed"));
    } catch (error) {
      toast.error(`${t("refreshFailed")}：${getApiErrorMessage(error)}`);
    }
  };

  const requestError = quotaQuery.error;
  const statusOnline = accessQuery.isSuccess && !accessQuery.isError;
  const meterState = usageStatusQuery.data?.state;
  const meterVersion = meterVersionQuery.data;
  const runtimeVersion = usageStatusQuery.data?.version;
  const upgradeRequired =
    meterVersion?.needsUpgrade || meterVersion?.status === "UPGRADE_REQUIRED";
  const versionUnverified =
    meterVersionQuery.isError ||
    meterVersionQuery.isPending ||
    meterVersion?.status === "UNKNOWN" ||
    (!!runtimeVersion &&
      !!meterVersion?.installedVersion &&
      runtimeVersion !== meterVersion.installedVersion) ||
    ((meterState === "UP_TO_DATE" || meterState === "REPORTED") &&
      !runtimeVersion);
  const meterHealthy =
    !usageStatusQuery.isError &&
    !upgradeRequired &&
    !versionUnverified &&
    (meterState === "UP_TO_DATE" ||
      meterState === "REPORTED" ||
      meterState === "BASELINE_READY");
  const meterLabel = usageStatusQuery.isError
    ? "统计状态读取失败"
    : upgradeRequired
      ? "统计程序待更新"
      : meterState === "NOT_INSTALLED" ||
          meterState === "OFFLINE" ||
          meterState === "ERROR" ||
          meterState === "UNPAIRED"
        ? serviceLabels[meterState]
        : versionUnverified
          ? "统计版本待确认"
          : meterState
            ? serviceLabels[meterState]
            : "正在读取统计状态…";

  return (
    <div className="cqg-dashboard cqg-shell" data-collapsed={collapsed}>
      <aside
        className="cqg-sidebar cqg-scrollbar-hidden"
        aria-label="客户端导航"
      >
        <div className="cqg-brand">
          <AppIcon className="size-9 shrink-0" />
          <div className="cqg-nav-copy min-w-0">
            <p className="whitespace-nowrap text-sm font-semibold tracking-tight">
              Quota Guard
            </p>
            <p className="mt-0.5 text-[10px] tracking-widest text-muted-foreground">
              用量与额度工作台
            </p>
          </div>
        </div>
        <nav className="flex-1 space-y-1 px-3 pt-5" aria-label="工作区">
          <p className="cqg-nav-copy mb-3 px-3 text-[10px] font-medium tracking-widest text-muted-foreground">
            工作区
          </p>
          {workspaces.map(({ id, label, Icon }) => (
            <button
              key={id}
              type="button"
              disabled={
                blockingReturn || (!bindingAllowed && id !== "login-assistance")
              }
              onClick={() => navigate(id)}
              aria-label={label}
              title={label}
              aria-current={visibleWorkspace === id ? "page" : undefined}
              className={cn(
                "cqg-nav-item",
                visibleWorkspace === id && "cqg-nav-item-active",
                !bindingAllowed &&
                  id !== "login-assistance" &&
                  "cursor-not-allowed opacity-40",
              )}
            >
              <Icon className="size-[18px] shrink-0" aria-hidden="true" />
              <span className="cqg-nav-copy truncate">{label}</span>
            </button>
          ))}
        </nav>
        <div className="cqg-sidebar-bottom">
          <button
            type="button"
            className="cqg-meter-link"
            disabled={!bindingAllowed || blockingReturn}
            onClick={() => navigate("diagnostics")}
            title={meterLabel}
            aria-label={`查看统计程序：${meterLabel}`}
          >
            <span
              aria-hidden="true"
              className={cn(
                "size-2 shrink-0 rounded-full",
                meterHealthy
                  ? "bg-[var(--cqg-status-success)]"
                  : "bg-[var(--cqg-status-warning)]",
              )}
            />
            <span className="cqg-nav-copy truncate text-xs">{meterLabel}</span>
          </button>
          <div className="cqg-theme-row">
            <span className="cqg-nav-copy text-xs text-muted-foreground">
              外观
            </span>
            <ThemeSwitcher compact className="cqg-sidebar-themes" />
          </div>
          <div className="cqg-sidebar-account">
            <UserCircle
              aria-hidden="true"
              className="cqg-account-avatar size-8 shrink-0 rounded-full bg-accent p-1.5 text-accent-foreground"
            />
            <div
              className="cqg-nav-copy min-w-0 flex-1"
              title={session.user?.email}
            >
              <p className="truncate text-xs font-medium">
                {session.user?.displayName}
              </p>
              <p className="mt-0.5 truncate text-[10px] text-muted-foreground">
                {session.user?.email}
              </p>
            </div>
            <Button
              variant="ghost"
              size="icon"
              className="size-8 shrink-0"
              onClick={() => {
                // Clear renderer-only codes; authenticated logout owns server cancellation.
                window.dispatchEvent(new Event("quota-guard:assistance-clear"));
                onLogout();
              }}
              aria-label="退出系统"
              title="退出系统"
            >
              <LogOut className="size-4" aria-hidden="true" />
            </Button>
          </div>
        </div>
      </aside>
      <main className="cqg-workspace">
        <header className="cqg-dashboard-header flex min-h-[60px] shrink-0 items-center justify-between gap-3 border-b px-4 py-2">
          <div className="flex min-w-0 items-center gap-3">
            <Button
              variant="ghost"
              size="icon"
              className="cqg-collapse-button size-8 shrink-0 text-muted-foreground"
              onClick={() => setCollapsed(!collapsed)}
              aria-expanded={!collapsed}
              aria-label={collapsed ? "展开导航" : "收起导航"}
              title={collapsed ? "展开导航" : "收起导航"}
            >
              {collapsed ? (
                <PanelLeftOpen aria-hidden="true" />
              ) : (
                <PanelLeftClose aria-hidden="true" />
              )}
            </Button>
            <div className="min-w-0">
              <h1 className="text-base font-semibold">{currentPage.title}</h1>
              <p className="cqg-page-description mt-1 truncate text-xs text-muted-foreground">
                {blockingReturn
                  ? "正在重新核验本设备绑定…"
                  : currentPage.description}
              </p>
            </div>
          </div>
          <div className="flex shrink-0 items-center gap-3">
            <span className="cqg-connection hidden items-center gap-2 text-xs text-muted-foreground sm:inline-flex">
              <span
                aria-hidden="true"
                className={cn(
                  "size-1.5 rounded-full",
                  statusOnline
                    ? "bg-[var(--cqg-status-success)]"
                    : "bg-[var(--cqg-status-warning)]",
                )}
              />
              {statusOnline ? t("connected") : t("serviceUnavailable")}
            </span>
            <Button
              variant="outline"
              size="sm"
              onClick={refresh}
              disabled={isRefreshing || blockingReturn}
              aria-label={isRefreshing ? t("refreshing") : t("refresh")}
              title={isRefreshing ? t("refreshing") : t("refresh")}
            >
              <RefreshCw
                aria-hidden="true"
                className={
                  isRefreshing ? "motion-safe:animate-spin" : undefined
                }
              />
              <span className="hidden sm:inline">刷新</span>
            </Button>
          </div>
        </header>
        {guardNotice ? (
          <aside
            aria-label="运行注意事项"
            className="cqg-scrollbar-hidden max-h-[30vh] shrink-0 overflow-y-auto border-b bg-muted/50 px-4 py-1.5 text-xs"
          >
            {guardNotice}
          </aside>
        ) : null}
        <div
          className="cqg-workspace-scroll cqg-scrollbar-hidden"
          ref={scrollArea}
          tabIndex={0}
          role="region"
          aria-label={`${currentPage.label}内容`}
          aria-busy={blockingReturn || undefined}
          onClickCapture={(event) => {
            if (blockingReturn) {
              event.preventDefault();
              event.stopPropagation();
            }
          }}
          onSubmitCapture={(event) => {
            if (blockingReturn) {
              event.preventDefault();
              event.stopPropagation();
            }
          }}
        >
          <div
            className="cqg-workspace-content mx-auto flex w-full max-w-[1240px] flex-col gap-4 p-4"
            {...{ inert: blockingReturn ? "" : undefined }}
          >
            {!bindingAllowed ? (
              <Alert>
                <AlertTitle>
                  {accessQuery.isFetching
                    ? "正在核对本设备绑定"
                    : accessQuery.isError
                      ? "绑定状态核验失败"
                      : "请先绑定本设备"}
                </AlertTitle>
                <AlertDescription>
                  {accessQuery.isError
                    ? "无法确认有效绑定，其他菜单暂不可用。已保存的绑定记录不代表当前访问权限已核验通过，请检查连接后重新核对。"
                    : "完成本设备官方会话绑定后，中央后台确认有效才会开放其他菜单。"}
                  {usageStatusQuery.data?.state === "NOT_INSTALLED" ? (
                    <p className="mt-2">
                      统计程序未安装。请重新运行客户端安装器；此处不会自动安装。
                    </p>
                  ) : null}
                  {accessQuery.isError ? (
                    <p>{getApiErrorMessage(accessQuery.error)}</p>
                  ) : null}
                  <Button
                    variant="outline"
                    size="sm"
                    className="ml-3"
                    onClick={recheckAccess}
                  >
                    重新核对绑定
                  </Button>
                </AlertDescription>
              </Alert>
            ) : null}
            {showAssistance ? (
              <SessionBinding
                key={`binding:${session.user!.id}`}
                userId={session.user!.id}
                onBound={handleBound}
              />
            ) : null}
            {bindingAllowed && requestError ? (
              <Alert variant="destructive">
                <CircleAlert aria-hidden="true" />
                <AlertTitle>{t("serviceUnavailable")}</AlertTitle>
                <AlertDescription className="flex flex-wrap items-center gap-3">
                  <span>{getApiErrorMessage(requestError)}</span>
                  <Button variant="outline" size="sm" onClick={refresh}>
                    {t("retry")}
                  </Button>
                </AlertDescription>
              </Alert>
            ) : null}

            {bindingAllowed && visibleWorkspace === "quota" ? (
              quotaQuery.isPending ? (
                <div className="grid gap-4 lg:grid-cols-2">
                  <LoadingCard />
                  <LoadingCard />
                  <LoadingCard className="lg:col-span-2" />
                </div>
              ) : overview ? (
                <>
                  <AccessCard overview={overview} />
                  <TeamTokenSection overview={overview} />
                  <OfficialRateLimitsCard
                    rateLimits={rateLimitsQuery.data}
                    loading={rateLimitsQuery.isPending}
                    failed={rateLimitsQuery.isError}
                  />
                </>
              ) : (
                <EmptyState
                  title="暂时无法读取个人额度"
                  description="请刷新重试；如问题持续，请联系管理员。"
                />
              )
            ) : visibleWorkspace === "login-assistance" ? (
              showAssistance ? (
                <LoginAssistance
                  key={`assistance:${session.user!.id}`}
                  userId={session.user!.id}
                />
              ) : null
            ) : bindingAllowed && visibleWorkspace === "settings" ? (
              <ClientSettings />
            ) : bindingAllowed ? (
              <UsageActivityPanel
                key={session.user!.id}
                view={
                  visibleWorkspace === "analytics"
                    ? "overview"
                    : visibleWorkspace === "ledger"
                      ? "ledger"
                      : "diagnostics"
                }
                userId={session.user!.id}
                measuredAt={overview?.usage.measuredAt ?? null}
                status={usageStatusQuery.data}
                statusError={usageStatusQuery.error}
              />
            ) : null}
          </div>
        </div>
      </main>
    </div>
  );
}
