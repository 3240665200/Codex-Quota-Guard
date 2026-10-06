import { afterEach, describe, expect, it, vi } from 'vitest';

import { generateRandomPassword } from './generatePassword';

afterEach(() => vi.unstubAllGlobals());

describe('generateRandomPassword', () => {
  it('uses Web Crypto to produce a 24-character password from an unbiased alphabet', () => {
    const getRandomValues = vi.fn((bytes: Uint8Array) => {
      bytes.forEach((_, index) => {
        bytes[index] = index;
      });
      return bytes;
    });
    vi.stubGlobal('crypto', { getRandomValues });

    expect(generateRandomPassword()).toBe('ABCDEFGHIJKLMNOPQRSTUVWX');
    expect(getRandomValues).toHaveBeenCalledOnce();
  });

  it('does not fall back to weak randomness when Web Crypto is unavailable', () => {
    vi.stubGlobal('crypto', undefined);

    expect(generateRandomPassword).toThrow('Secure random source unavailable');
  });
});
