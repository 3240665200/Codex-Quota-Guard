const zhNumber = new Intl.NumberFormat('zh-CN', { maximumFractionDigits: 2 });
const exactNumber = new Intl.NumberFormat('zh-CN', { maximumFractionDigits: 0 });

export function formatExactTokenAmount(value: number): string {
  return `${exactNumber.format(value)} Token`;
}

export function formatTokenAmount(value: number): string {
  if (value < 10_000) return formatExactTokenAmount(value);

  const units = [
    { divisor: 10_000, label: '万' },
    { divisor: 100_000_000, label: '亿' },
    { divisor: 1_000_000_000_000, label: '万亿' },
  ];
  let index = value >= units[2].divisor ? 2 : value >= units[1].divisor ? 1 : 0;
  let unit = units[index];
  let rounded = Math.round((value / unit.divisor) * 100) / 100;
  if (rounded >= 10_000 && index < units.length - 1) {
    unit = units[++index];
    rounded = Math.round((value / unit.divisor) * 100) / 100;
  }
  const approximate = value % (unit.divisor / 100) !== 0;
  return `${approximate ? '约' : ''}${zhNumber.format(rounded)}${unit.label} Token`;
}
