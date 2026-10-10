import { render, screen } from '@testing-library/react';
import { describe, expect, it } from 'vitest';
import { formatUsd, UsageCost } from './UsageAmounts';

describe('USD display', () => {
  it('preserves zero and distinguishes positive sub-micro USD values', () => {
    expect(formatUsd(0)).toBe('0.00');
    expect(formatUsd(0.000000000001)).toBe('< 0.000001');
    expect(formatUsd(0.000001)).toBe('0.000001');
    expect(formatUsd(1234.567891)).toBe('1,234.567891');
  });

  it('does not describe an unknown aggregate as an unpriced model', () => {
    render(<><UsageCost value={null} /><UsageCost value={null} status="MISSING_PRICE" />
      <UsageCost value={null} status="MISSING_BREAKDOWN" /><UsageCost value={0} status="PRICED" /></>);
    expect(screen.getByText('不可估价')).toBeVisible();
    expect(screen.getByText('未定价')).toBeVisible();
    expect(screen.getByText('分项缺失')).toBeVisible();
    expect(screen.getByText('USD 0.00')).toBeVisible();
  });
});
