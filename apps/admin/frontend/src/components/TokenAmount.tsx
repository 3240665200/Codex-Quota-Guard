import { formatExactTokenAmount, formatTokenAmount } from '../utils/token';

export function TokenAmount({ value }: { value: number | null | undefined }) {
  if (value === undefined) return <span>—</span>;
  if (value === null) return <span>未配置</span>;
  return <span title={`精确值：${formatExactTokenAmount(value)}`}>
    {formatTokenAmount(value)}
  </span>;
}
