import { describe, expect, it } from 'vitest';
import { formatUsdAmount, usdBudgetRule } from './usd';
describe('exact USD amounts', () => {
  it('preserves twelve decimal places at large balances', () => {
    expect(formatUsdAmount('1000000000000.000000000001')).toBe('$1,000,000,000,000.000000000001 USD');
    expect(formatUsdAmount('1E-12')).toBe('$0.000000000001 USD');
  });
  it('does not round an over-limit amount down to the permitted maximum', async () => {
    await expect(usdBudgetRule.validator(null,'1000000000000.000000000001')).rejects.toThrow();
    await expect(usdBudgetRule.validator(null,'999999999999.999999999999')).resolves.toBeUndefined();
    await expect(usdBudgetRule.validator(null,'0')).resolves.toBeUndefined();
    await expect(usdBudgetRule.validator(null,null)).resolves.toBeUndefined();
  });
});
