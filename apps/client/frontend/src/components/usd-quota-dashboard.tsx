import { useState } from 'react';
import { Search, Users } from 'lucide-react';
import { Card, CardContent, CardHeader } from '@/components/ui/card';
import { Progress } from '@/components/ui/progress';
import { useQuotaDisplay } from '@/lib/quota-display';
import { compareUsd, formatUsd, usdPercent, type UsdOverview, type UsdWindow } from '@/lib/usd';

function Window({ label, quota }: { label: string; quota: UsdWindow }) {
  const { mode, label: displayLabel } = useQuotaDisplay();
  const percent = usdPercent(quota, mode);
  const exhausted = quota.limitUsd !== null && compareUsd(quota.usedUsd, quota.limitUsd) >= 0;
  return <article className="min-w-0 rounded-lg border p-4" aria-label={`${label}美元额度`}>
    <h4 className="mb-2 text-sm font-medium">{label}</h4>
    <p className="text-xs text-muted-foreground">{displayLabel}额度</p>
    <p className="my-1 break-words text-lg font-semibold tabular-nums" title={mode === 'used' ? quota.usedUsd : quota.remainingUsd ?? undefined}>
      {mode === 'remaining' && quota.limitUsd === null ? '无限制' : mode === 'remaining' && quota.remainingUsd === null ? '待核算' : formatUsd(mode === 'used' ? quota.usedUsd : quota.remainingUsd!)}
    </p>
    {percent !== null ? <Progress value={percent} aria-label={`${label}${displayLabel}美元额度`} aria-valuenow={percent} /> : null}
    <p className="mt-2 break-words text-xs text-muted-foreground">已核算 {formatUsd(quota.usedUsd)}</p>
    <p className="mt-1 break-words text-xs text-muted-foreground">上限 {quota.limitUsd === null ? '无限制' : formatUsd(quota.limitUsd)}</p>
    {exhausted ? <p className="mt-1 text-xs text-[var(--cqg-status-error)]">额度已用尽</p> : null}
    {quota.pendingEvents > 0 ? <p className="mt-2 text-xs text-[var(--cqg-status-warning)]">{quota.pendingEvents} 笔待核算，当前金额不完整</p> : null}
    {quota.modeUnrecordedEvents > 0 ? <p className="mt-1 text-xs text-muted-foreground">{quota.modeUnrecordedEvents} 笔未记录速度模式；历史按普通费率回算，新用量待确认模式</p> : null}
  </article>;
}
function Share({ label, used, total }: { label: string; used: UsdWindow; total: UsdWindow }) {
  const pending = used.pendingEvents > 0 || total.pendingEvents > 0;
  const inconsistent = compareUsd(used.usedUsd, total.usedUsd) > 0;
  const percent = Number(total.usedUsd) > 0 ? Math.max(0, Math.min(100, Number(used.usedUsd) / Number(total.usedUsd) * 100)) : 0;
  return <div className="min-w-0 space-y-1">
    <div className="flex justify-between gap-2 text-xs"><span>{label}</span><span>{pending ? '待核算' : inconsistent ? '待刷新' : `${percent.toFixed(1)}%`}</span></div>
    {pending ? <p className="text-xs text-[var(--cqg-status-warning)]">费用尚未核算完整，暂不计算占比</p> : inconsistent ? <p className="text-xs text-[var(--cqg-status-warning)]">团队汇总与成员用量不一致，请刷新</p> : <Progress value={percent} aria-label={`${label}团队美元用量占比`} aria-valuenow={percent} />}
    <p className="break-words text-xs text-muted-foreground">已核算 {formatUsd(used.usedUsd)} / 团队已核算 {formatUsd(total.usedUsd)}</p>
  </div>;
}
export function UsdQuotaDashboard({ billing }: { billing: UsdOverview }) {
  const [search, setSearch] = useState('');
  const members = billing.teamMembers.filter((member) => member.displayName.toLocaleLowerCase().includes(search.trim().toLocaleLowerCase()));
  return <section className="space-y-5" aria-label="美元额度总览">
    <Card><CardHeader><h2 className="text-lg font-semibold">个人额度（USD）</h2><p className="text-sm text-muted-foreground">依据模型价格表计费；留空上限表示无限制。</p></CardHeader>
      <CardContent className="grid min-w-0 grid-cols-1 gap-4 md:grid-cols-2"><Window label="个人 5 小时" quota={billing.personal.fiveHour} /><Window label="个人 7 天" quota={billing.personal.weekly} /></CardContent>
    </Card>
    <Card><CardHeader><h2 className="text-lg font-semibold">团队额度（USD）</h2></CardHeader><CardContent>
      {billing.team ? <div className="grid min-w-0 grid-cols-1 gap-4 xl:grid-cols-3"><Window label="团队 5 小时" quota={billing.team.fiveHour} /><Window label="团队 7 天" quota={billing.team.weekly} /><Window label="团队累计" quota={billing.team.total} /></div> : <p className="text-muted-foreground">未加入团队</p>}
    </CardContent></Card>
    <Card><CardHeader><h2 className="text-lg font-semibold">官方账户额度（USD）</h2><p className="text-sm text-muted-foreground">账户预算由管理员设置，按关联账户的美元账本累计；官方套餐百分比单独参考。</p></CardHeader><CardContent>
      {billing.officialAccount ? <Window label={billing.officialAccount.label} quota={billing.officialAccount.quota.total} /> : <p className="text-muted-foreground">团队尚未关联官方账户</p>}
    </CardContent></Card>
    <Card><CardHeader className="flex flex-wrap gap-3"><h2 className="flex items-center gap-2 text-lg font-semibold"><Users className="size-5" />成员用量（USD）</h2>
      <label className="flex w-full max-w-sm items-center gap-2 rounded-md border px-3 py-2 text-sm"><Search className="size-4 shrink-0" /><input aria-label="搜索成员姓名" value={search} onChange={(event) => setSearch(event.target.value)} placeholder="搜索成员姓名" className="min-w-0 flex-1 bg-transparent outline-none" /></label>
      <p className="text-xs text-muted-foreground">显示 {members.length} / {billing.teamMembers.length} 位成员 · 个人余额和团队用量占比</p>
    </CardHeader><CardContent className="space-y-4">
      {members.map((member) => <article key={member.userId} aria-label={`${member.displayName}成员用量`} className="min-w-0 rounded-lg border p-4"><h3 className="mb-1 font-semibold">{member.displayName}</h3><p className="mb-3 text-xs text-muted-foreground">{member.status === 'ACTIVE' ? '正常' : '已停用'}</p>
        <div className="grid min-w-0 grid-cols-1 gap-4 xl:grid-cols-2"><div className="grid min-w-0 grid-cols-1 gap-3 sm:grid-cols-2"><Window label="5 小时个人" quota={member.personal.fiveHour} /><Window label="7 天个人" quota={member.personal.weekly} /></div>
          {billing.team ? <div className="min-w-0 space-y-4 rounded-lg bg-muted/30 p-4"><Share label="5 小时" used={member.contribution.fiveHour} total={billing.team.fiveHour} /><Share label="7 天" used={member.contribution.weekly} total={billing.team.weekly} /><Share label="累计" used={member.contribution.total} total={billing.team.total} /></div> : <p className="text-xs text-muted-foreground">团队用量暂不可得，暂无占比</p>}
        </div></article>)}
      {!members.length ? <p className="text-sm text-muted-foreground">暂无匹配成员</p> : null}
      <p className="text-xs text-muted-foreground">费用完整时，占比按成员已用 USD ÷ 团队已用 USD 计算；存在待核算费用时暂不显示占比。团队可能包含已离队成员的历史费用。</p>
    </CardContent></Card>
  </section>;
}
