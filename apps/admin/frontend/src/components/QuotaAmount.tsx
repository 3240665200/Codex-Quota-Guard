import { Progress, Typography } from 'antd';
import { quotaPercent, useQuotaDisplay } from '../services/quota-display';
import { UsdAmount } from './UsdAmount';
import type { UsdWindow } from '../services/usd-types';

export function QuotaAmount({ quota, label }: {
  quota?: UsdWindow; label: string;
}) {
  const { mode, label: displayLabel } = useQuotaDisplay();
  if (!quota) return <Typography.Text type="secondary">USD 额度暂不可得</Typography.Text>;
  const used = Number(quota.usedUsd), limit = quota.limitUsd === null ? null : Number(quota.limitUsd), remaining = quota.remainingUsd === null ? null : Number(quota.remainingUsd);
  const percent = quotaPercent(mode, used, remaining, limit);
  const exhausted = limit !== null && (limit === 0 || remaining === 0);
  if (limit === null) return <div>
    <Typography.Text type="secondary">无限制</Typography.Text>
    <div>已核算 <UsdAmount value={quota.usedUsd} /></div>
    {quota.pendingEvents > 0 ? <Typography.Text type="warning">{quota.pendingEvents} 笔待核算</Typography.Text> : null}
  </div>;
  return <div style={{ minWidth: 165 }}>
    <Typography.Text type={exhausted ? 'danger' : undefined}>
      {displayLabel} {remaining === null ? '暂不可得' : <UsdAmount value={mode === 'used' ? quota.usedUsd : quota.remainingUsd} />}
      {percent !== null ? <span style={{ marginLeft: 8, whiteSpace: 'nowrap' }}>{new Intl.NumberFormat('zh-CN', { maximumFractionDigits: 1 }).format(percent)}%</span> : null}
    </Typography.Text>
    {percent !== null ? <Progress aria-label={`${label}${displayLabel}额度`} aria-valuenow={percent} aria-valuetext={`${displayLabel} ${percent}%`} percent={percent}
      size="small" showInfo={false} status={exhausted ? 'exception' : 'normal'} /> : null}
    <div><Typography.Text type="secondary">上限 <UsdAmount value={quota.limitUsd} /></Typography.Text></div>
    {quota.pendingEvents > 0 ? <Typography.Text type="warning">{quota.pendingEvents} 笔待核算，当前金额不完整</Typography.Text> : null}
  </div>;
}
