import { useState } from "react";
import { Search, Users } from "lucide-react";
import { Card, CardContent, CardHeader } from "@/components/ui/card";
import { Progress } from "@/components/ui/progress";
import { useQuotaDisplay } from "@/lib/quota-display";
import {
  compareUsd,
  formatUsd,
  formatUsdCompact,
  usdPercent,
  type UsdOverview,
  type UsdWindow,
} from "@/lib/usd";
import "./usd-quota-dashboard.css";

function Money({ value }: { value: string }) {
  return (
    <span className="cqg-usd-money" title={`精确金额：${formatUsd(value)}`}>
      {formatUsdCompact(value)}
    </span>
  );
}

function Window({
  label,
  quota,
  compact = false,
}: {
  label: string;
  quota: UsdWindow;
  compact?: boolean;
}) {
  const { mode, label: displayLabel } = useQuotaDisplay();
  const percent = usdPercent(quota, mode);
  const exhausted =
    quota.limitUsd !== null && compareUsd(quota.usedUsd, quota.limitUsd) >= 0;
  const amount = mode === "used" ? quota.usedUsd : quota.remainingUsd;
  return (
    <article
      className={`cqg-usd-window${compact ? " cqg-usd-window-compact" : ""}`}
      aria-label={`${label}美元额度`}
    >
      <p className="cqg-usd-window-label">
        {label}
        <span>{displayLabel}</span>
      </p>
      <div className="cqg-usd-window-amount">
        {mode === "remaining" && quota.limitUsd === null ? (
          <strong>无限制</strong>
        ) : amount === null ? (
          <strong>待核算</strong>
        ) : (
          <>
            <strong>
              <Money value={amount} />
            </strong>
            <small>USD</small>
          </>
        )}
      </div>
      {percent !== null ? (
        <Progress
          className="h-1.5"
          value={percent}
          aria-label={`${label}${displayLabel}美元额度`}
          aria-valuenow={percent}
        />
      ) : (
        <div className="cqg-usd-no-limit" aria-hidden="true" />
      )}
      <div className="cqg-usd-window-meta">
        <p>
          <span>已核算</span>
          <Money value={quota.usedUsd} />
        </p>
        <p>
          <span>上限</span>
          {quota.limitUsd === null ? (
            <span>无限制</span>
          ) : (
            <Money value={quota.limitUsd} />
          )}
        </p>
      </div>
      {exhausted ? <p className="cqg-usd-error">额度已用尽</p> : null}
      {quota.pendingEvents > 0 ? (
        <p className="cqg-usd-warning">
          {quota.pendingEvents} 笔待核算，当前金额不完整
        </p>
      ) : null}
      {quota.modeUnrecordedEvents > 0 ? (
        <p className="cqg-usd-note">
          {quota.modeUnrecordedEvents}{" "}
          笔未记录速度模式；历史按普通费率回算，新用量待确认模式
        </p>
      ) : null}
    </article>
  );
}

function Share({
  label,
  used,
  total,
}: {
  label: string;
  used: UsdWindow;
  total: UsdWindow;
}) {
  const pending = used.pendingEvents > 0 || total.pendingEvents > 0;
  const inconsistent = compareUsd(used.usedUsd, total.usedUsd) > 0;
  const percent =
    Number(total.usedUsd) > 0
      ? Math.max(
          0,
          Math.min(100, (Number(used.usedUsd) / Number(total.usedUsd)) * 100),
        )
      : 0;
  return (
    <div className="cqg-usd-share">
      <div className="cqg-usd-share-heading">
        <span>{label}</span>
        <strong>
          {pending
            ? "待核算"
            : inconsistent
              ? "待刷新"
              : `${percent.toFixed(1)}%`}
        </strong>
      </div>
      {pending ? (
        <p className="cqg-usd-warning">费用尚未核算完整，暂不计算占比</p>
      ) : inconsistent ? (
        <p className="cqg-usd-warning">团队汇总与成员用量不一致，请刷新</p>
      ) : (
        <Progress
          className="h-1.5"
          indicatorClassName="cqg-usd-share-bar"
          value={percent}
          aria-label={`${label}团队美元用量占比`}
          aria-valuenow={percent}
        />
      )}
      <p className="cqg-usd-note">
        <Money value={used.usedUsd} /> / <Money value={total.usedUsd} />
      </p>
    </div>
  );
}

export function UsdQuotaDashboard({ billing }: { billing: UsdOverview }) {
  const [search, setSearch] = useState("");
  const members = billing.teamMembers.filter((member) =>
    member.displayName
      .toLocaleLowerCase()
      .includes(search.trim().toLocaleLowerCase()),
  );
  return (
    <section className="cqg-usd-overview" aria-label="美元额度总览">
      <div className="cqg-usd-budget-grid">
        <Card className="cqg-usd-card cqg-usd-personal">
          <CardHeader className="cqg-usd-card-heading">
            <h2>个人额度（USD）</h2>
            <p>留空上限表示无限制；悬停金额可查看精确值。</p>
          </CardHeader>
          <CardContent className="cqg-usd-card-content cqg-usd-personal-windows">
            <Window label="个人 5 小时" quota={billing.personal.fiveHour} />
            <Window label="个人 7 天" quota={billing.personal.weekly} />
          </CardContent>
        </Card>
        <Card className="cqg-usd-card cqg-usd-team">
          <CardHeader className="cqg-usd-card-heading">
            <h2>团队额度（USD）</h2>
            <p>共享预算 · 按团队全部成员的已核算费用统计</p>
          </CardHeader>
          <CardContent className="cqg-usd-card-content">
            {billing.team ? (
              <div className="cqg-usd-team-windows">
                <Window label="团队 5 小时" quota={billing.team.fiveHour} />
                <Window label="团队 7 天" quota={billing.team.weekly} />
                <Window label="团队累计" quota={billing.team.total} />
              </div>
            ) : (
              <p className="cqg-usd-note">未加入团队</p>
            )}
          </CardContent>
        </Card>
        <Card className="cqg-usd-card cqg-usd-account">
          <CardHeader className="cqg-usd-card-heading">
            <h2>官方账户额度（USD）</h2>
            <p>管理员设置的账户预算 · 官方套餐百分比单独参考</p>
          </CardHeader>
          <CardContent className="cqg-usd-card-content">
            {billing.officialAccount ? (
              <Window
                label={billing.officialAccount.label}
                quota={billing.officialAccount.quota.total}
              />
            ) : (
              <p className="cqg-usd-note">团队尚未关联官方账户</p>
            )}
          </CardContent>
        </Card>
      </div>
      <Card className="cqg-usd-card cqg-usd-members">
        <CardHeader className="cqg-usd-card-heading">
          <div className="cqg-usd-member-toolbar">
            <div>
              <h2>
                <Users className="size-4" aria-hidden="true" />
                成员用量（USD）
              </h2>
              <p>
                显示 {members.length} / {billing.teamMembers.length} 位成员 ·
                个人余额和团队用量占比
              </p>
            </div>
            <label className="cqg-usd-member-search">
              <Search className="size-4 shrink-0" aria-hidden="true" />
              <input
                aria-label="搜索成员姓名"
                value={search}
                onChange={(event) => setSearch(event.target.value)}
                placeholder="搜索成员姓名"
              />
            </label>
          </div>
        </CardHeader>
        <CardContent className="cqg-usd-card-content">
          <div className="cqg-usd-members-head" aria-hidden="true">
            <span>成员</span>
            <span>个人额度</span>
            <span>团队用量占比 · 成员 / 团队已核算费用</span>
          </div>
          {members.map((member) => (
            <article
              key={member.userId}
              aria-label={`${member.displayName}成员用量`}
              className="cqg-usd-member"
            >
              <header className="cqg-usd-member-name">
                <h3>{member.displayName}</h3>
                <span
                  className={`cqg-usd-member-status${member.status !== "ACTIVE" ? " is-disabled" : ""}`}
                >
                  {member.status === "ACTIVE" ? "正常" : "已停用"}
                </span>
              </header>
              <div className="cqg-usd-member-personal">
                <Window
                  label="5 小时个人"
                  quota={member.personal.fiveHour}
                  compact
                />
                <Window
                  label="7 天个人"
                  quota={member.personal.weekly}
                  compact
                />
              </div>
              {billing.team ? (
                <div className="cqg-usd-member-shares">
                  <Share
                    label="5 小时"
                    used={member.contribution.fiveHour}
                    total={billing.team.fiveHour}
                  />
                  <Share
                    label="7 天"
                    used={member.contribution.weekly}
                    total={billing.team.weekly}
                  />
                  <Share
                    label="累计"
                    used={member.contribution.total}
                    total={billing.team.total}
                  />
                </div>
              ) : (
                <p className="cqg-usd-note">团队用量暂不可得，暂无占比</p>
              )}
            </article>
          ))}
          {!members.length ? (
            <p className="cqg-usd-note">暂无匹配成员</p>
          ) : null}
          <p className="cqg-usd-member-footnote">
            费用完整时，占比按成员已用 USD ÷ 团队已用 USD
            计算；待核算时不显示占比。团队可能包含已离队成员的历史费用。金额展示到美分，精确金额可悬停查看。
          </p>
        </CardContent>
      </Card>
    </section>
  );
}
