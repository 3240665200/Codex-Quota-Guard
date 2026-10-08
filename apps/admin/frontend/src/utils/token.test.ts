import { describe, expect, it } from 'vitest';

import { formatExactTokenAmount, formatTokenAmount } from './token';

describe('Token display units', () => {
  it('keeps small values exact and marks rounded large values', () => {
    expect(formatTokenAmount(0)).toBe('0 Token');
    expect(formatTokenAmount(9_999)).toBe('9,999 Token');
    expect(formatTokenAmount(10_000)).toBe('1万 Token');
    expect(formatTokenAmount(99_999)).toBe('约10万 Token');
    expect(formatTokenAmount(123_456_789)).toBe('约1.23亿 Token');
    expect(formatTokenAmount(1_000_000_000_000)).toBe('1万亿 Token');
    expect(formatExactTokenAmount(99_999)).toBe('99,999 Token');
  });
});
