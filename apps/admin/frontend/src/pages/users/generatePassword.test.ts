import { afterEach, describe, expect, it, vi } from 'vitest';

import { generateRandomPassword } from './generatePassword';

afterEach(() => vi.unstubAllGlobals());

describe('generateRandomPassword', () => {
  it('uses Web Crypto on HTTP without depending on secure-context-only randomUUID', () => {
    const getRandomValues = vi.fn((bytes: Uint8Array) => {
      bytes.forEach((_, index) => {
        bytes[index] = index;
      });
      return bytes;
    });
    vi.stubGlobal('crypto', { getRandomValues });
    vi.stubGlobal('isSecureContext', false);

    expect(globalThis.crypto.randomUUID).toBeUndefined();
    expect(generateRandomPassword()).toBe('ABCDEFGHIJKLMNOPQRSTUVWX');
    expect(getRandomValues).toHaveBeenCalledOnce();
  });

  it('does not fall back to weak randomness when Web Crypto is unavailable', () => {
    vi.stubGlobal('crypto', undefined);

    expect(generateRandomPassword).toThrow('Secure random source unavailable');
  });
});
