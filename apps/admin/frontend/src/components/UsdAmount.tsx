import { formatUsdAmount } from '../utils/usd';
export function UsdAmount({ value }: { value: string | number | null | undefined }) {
  return <span title={value == null ? undefined : `${value} USD`} style={{ whiteSpace: 'nowrap' }}>{value == null ? '无限制' : formatUsdAmount(value)}</span>;
}
