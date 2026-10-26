export function formatUsdAmount(value: string | number): string {
  const match = /^(\d+)(?:\.(\d+))?(?:[eE]([+-]?\d+))?$/.exec(String(value));
  if (!match || Math.abs(Number(match[3] || 0)) > 30) return '暂不可得';
  const digits = match[1] + (match[2] || '');
  const point = match[1].length + Number(match[3] || 0);
  const plain = point <= 0 ? '0.' + '0'.repeat(-point) + digits
    : point >= digits.length ? digits + '0'.repeat(point - digits.length)
    : digits.slice(0, point) + '.' + digits.slice(point);
  const [whole, fraction = ''] = plain.split('.');
  return '$' + whole.replace(/^0+(?=\d)/, '').replace(/\B(?=(\d{3})+(?!\d))/g, ',') + '.'
    + fraction.replace(/0+$/, '').padEnd(2, '0') + ' USD';
}
export const usdBudgetRule = {
  validator: async (_: unknown, value: unknown) => {
    if (value === null || value === undefined || value === '') return;
    const text = String(value);
    const [whole, fraction = ''] = text.replace(/^0+(?=\d)/, '').split('.');
    if (!/^\d+(\.\d{1,12})?$/.test(text) || whole.length > 13
      || whole.length === 13 && (whole > '1000000000000' || whole === '1000000000000' && /[1-9]/.test(fraction))) {
      throw new Error('请输入非负美元金额，最多 12 位小数；留空表示无限制。');
    }
  },
};
