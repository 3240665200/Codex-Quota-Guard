import { describe, expect, it } from 'vitest';
import { compareUsd, formatUsd, usdPercent, usdWindowSchema, type UsdWindow } from './usd';
const window: UsdWindow = { limitUsd:'100.00',usedUsd:'25.00',remainingUsd:'75.00',pendingEvents:0,modeUnrecordedEvents:0,historicalEvents:0 };
describe('USD display and quota contracts', () => {
  it('compares quota boundaries without floating point rounding', () => {
    expect(compareUsd('999999999999.999999999999', '1000000000000')).toBe(-1);
    expect(compareUsd('1000000000000.000000000001', '1000000000000')).toBe(1);
    expect(compareUsd('0E-12', '0.000000000000')).toBe(0);
  });
  it('preserves sub-cent values and large decimal amounts', () => {
    expect(formatUsd('1E-12')).toBe('$0.000000000001 USD');
    expect(formatUsd('1000000000000.000000000001')).toBe('$1,000,000,000,000.000000000001 USD');
    expect(formatUsd('0E-12')).toBe('$0.00 USD');
  });
  it('switches between used and remaining percentages without changing money', () => {
    expect(usdPercent(window,'remaining')).toBe(75);
    expect(usdPercent(window,'used')).toBe(25);
    expect(usdPercent({...window,limitUsd:null,remainingUsd:null},'remaining')).toBeNull();
    expect(usdPercent({...window,limitUsd:'0',usedUsd:'0',remainingUsd:'0'},'used')).toBe(100);
  });
  it('keeps missing-price counts separate from a known zero subtotal', () => {
    expect(usdWindowSchema.parse({...window,usedUsd:'0',pendingEvents:2}).pendingEvents).toBe(2);
    expect(usdWindowSchema.safeParse({...window,limitUsd:null,remainingUsd:'75'}).success).toBe(false);
  });
});
