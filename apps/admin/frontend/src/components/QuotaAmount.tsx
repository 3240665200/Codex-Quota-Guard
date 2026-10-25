import { Progress, Typography } from 'antd';
import { quotaPercent, useQuotaDisplay } from '../services/quota-display';
import { TokenAmount } from './TokenAmount';

export function QuotaAmount({ used, limit, remaining, label }: {
  used: number; limit: number | null; remaining: number | null; label: string;
}) {
  const { mode, label: displayLabel } = useQuotaDisplay();
  const percent = quotaPercent(mode, used, remaining, limit);
  const exhausted = limit !== null && (limit === 0 || remaining === 0);
  if (limit === null) return <div>
    <Typography.Text type="secondary">不设限</Typography.Text>
    {mode === 'used' ? <div>已用 <TokenAmount value={used} /></div> : null}
  </div>;
  return <div style={{ minWidth: 165 }}>
    <Typography.Text type={exhausted ? 'danger' : undefined}>
      {displayLabel} {remaining === null ? '暂不可得' : <TokenAmount value={mode === 'used' ? used : remaining} />}
      {percent !== null ? <span style={{ marginLeft: 8, whiteSpace: 'nowrap' }}>{new Intl.NumberFormat('zh-CN', { maximumFractionDigits: 1 }).format(percent)}%</span> : null}
    </Typography.Text>
    {percent !== null ? <Progress aria-label={`${label}${displayLabel}额度`} aria-valuenow={percent} aria-valuetext={`${displayLabel} ${percent}%`} percent={percent}
      size="small" showInfo={false} status={exhausted ? 'exception' : 'normal'} /> : null}
    <div><Typography.Text type="secondary">上限 <TokenAmount value={limit} /></Typography.Text></div>
  </div>;
}
