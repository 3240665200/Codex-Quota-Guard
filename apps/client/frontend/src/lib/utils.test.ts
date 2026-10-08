import { describe, expect, it } from "vitest";

import { formatCompactTokenCount, formatTokenCount } from "@/lib/utils";

describe("Token display units", () => {
  it("uses Chinese magnitude units without hiding the exact value", () => {
    expect(formatCompactTokenCount(0)).toBe("0");
    expect(formatCompactTokenCount(9_999)).toBe("9,999");
    expect(formatCompactTokenCount(10_000)).toBe("1万");
    expect(formatCompactTokenCount(99_999)).toBe("约10万");
    expect(formatCompactTokenCount(123_456_789)).toBe("约1.23亿");
    expect(formatCompactTokenCount(1_000_000_000_000)).toBe("1万亿");
    expect(formatTokenCount(99_999)).toBe("99,999");
  });
});
