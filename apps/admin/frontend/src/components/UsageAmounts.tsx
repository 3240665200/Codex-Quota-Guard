import { Tooltip } from 'antd';
import { TokenAmount } from './TokenAmount';
import type { UsageCostStatus } from '../services/api';

const usd = new Intl.NumberFormat('en-US', { minimumFractionDigits: 2, maximumFractionDigits: 6 });
const statusLabels: Record<UsageCostStatus, string> = {
  MISSING_PRICE: '未定价',
  MISSING_BREAKDOWN: '分项缺失',
  INVALID_BREAKDOWN: '分项不一致',
  PRICED: '已入账',
  PENDING_BILLING: '待核算',
  MISSING_SERVICE_TIER: '待确认速度模式',
};

export function formatUsd(value: number): string {
  if (value > 0 && value < 0.000001) return '< 0.000001';
  return usd.format(value);
}

export function UsageTokens({ value }: { value: number | null | undefined }) {
  return value == null ? <span aria-label="分项缺失">—</span> : <TokenAmount value={value} />;
}

export function UsageCost({ value, status }: { value: number | null | undefined; status?: UsageCostStatus }) {
  return value == null
    ? <span className="cqg-cost-unknown">{status ? statusLabels[status] : '不可估价'}</span>
    : <Tooltip title={`USD ${value} · 入账时价格快照`}><span className="cqg-usage-number">USD {formatUsd(value)}</span></Tooltip>;
}
