import { Buffer } from 'node:buffer';
import { describe, expect, it } from 'vitest';
import { importAuthenticator } from './authenticator-import';

// Public RFC 6238 example key; no account credentials are used in these fixtures.
const KEY = '12345678901234567890';
const varint = (value: number): number[] => value < 128 ? [value] : [value % 128 + 128, ...varint(Math.floor(value / 128))];
const bytes = (field: number, value: string | number[]) => {
  const data = typeof value === 'string' ? [...Buffer.from(value)] : value;
  return [...varint(field * 8 + 2), ...varint(data.length), ...data];
};
const number = (field: number, value: number) => [...varint(field * 8), ...varint(value)];
function entry({ algorithm = 1, digits = 1, type = 2, key = KEY } = {}) {
  return [...bytes(1, key), ...bytes(2, 'OpenAI:synthetic@example.com'), ...bytes(3, 'OpenAI'),
    ...number(4, algorithm), ...number(5, digits), ...number(6, type)];
}
function migration(accounts = [entry()], extra = [...number(2, 1), ...number(3, 1)]) {
  return `otpauth-migration://offline?data=${encodeURIComponent(Buffer.from([...accounts.flatMap((value) => bytes(1, value)), ...extra]).toString('base64'))}`;
}

describe('local Google Authenticator import', () => {
  it('converts a single exported account to a standard URI without changing its secret', () => {
    const imported = importAuthenticator(migration());
    const uri = new URL(imported.uri);
    expect(imported.label).toBe('OpenAI:synthetic@example.com');
    expect(uri.protocol).toBe('otpauth:');
    expect(uri.host).toBe('totp');
    expect(uri.searchParams.get('secret')).toBe('GEZDGNBVGY3TQOJQGEZDGNBVGY3TQOJQ');
    expect(uri.searchParams.get('algorithm')).toBe('SHA1');
    expect(uri.searchParams.get('digits')).toBe('6');
    expect(uri.searchParams.get('period')).toBe('30');
  });
  it.each([[2, 2, 'SHA256', '8'], [3, 1, 'SHA512', '6']])('preserves the exported algorithm and digits (%s)', (algorithm, digits, expectedAlgorithm, expectedDigits) => {
    const uri = new URL(importAuthenticator(migration([entry({ algorithm: Number(algorithm), digits: Number(digits) })])).uri);
    expect(uri.searchParams.get('algorithm')).toBe(expectedAlgorithm);
    expect(uri.searchParams.get('digits')).toBe(expectedDigits);
  });
  it('accepts URL-safe base64 and ignores bounded, unknown protobuf fields', () => {
    const value = migration([[...entry(), ...bytes(8, 'synthetic-id'), ...number(19, 42)]])
      .replace(/%2B/g, '-').replace(/%2F/g, '_').replace(/%3D/g, '');
    expect(importAuthenticator(value).label).toContain('synthetic@example.com');
  });
  it('accepts standard setup QR links', () => {
    const uri = 'otpauth://totp/OpenAI%3Asynthetic?secret=GEZDGNBVGY3TQOJQGEZDGNBVGY3TQOJQ';
    expect(importAuthenticator(uri)).toEqual({ uri, label: 'OpenAI:synthetic' });
  });
  it('imports current Google migration v2 without changing the key or TOTP parameters', () => {
    const v2 = migration([[...entry(), ...bytes(8, 'synthetic-metadata')]], [...number(2, 2), ...number(3, 1), ...number(4, 0)]);
    expect(importAuthenticator(v2).uri).toBe(importAuthenticator(migration()).uri);
  });
  it.each([
    migration([entry(), entry()]), migration([entry()], [...number(2, 1), ...number(3, 2)]),
    migration([entry()], [...number(2, 1), ...number(3, 1), ...number(4, 1)]),
  ])('requires an export containing exactly one complete account', (uri) => {
    expect(() => importAuthenticator(uri)).toThrow('只勾选当前 GPT 账户');
  });
  it.each([migration([entry({ type: 1 })]), migration([entry({ type: 0 })])])('rejects HOTP and unspecified OTP types', (uri) => {
    expect(() => importAuthenticator(uri)).toThrow('TOTP');
  });
  it.each([migration([entry({ algorithm: 4 })]), migration([entry({ algorithm: 0 })]), migration([entry({ digits: 0 })])])('does not silently substitute unsupported algorithms or digit counts', (uri) => {
    expect(() => importAuthenticator(uri)).toThrow('不受支持');
  });
  it.each([
    '', 'https://example.com/?secret=private', 'otpauth://hotp/test?secret=private',
    'otpauth-migration://evil.test?data=AAAA', 'otpauth-migration://offline?data=AA&data=AA',
    'otpauth-migration://offline?data=AA&extra=1', 'otpauth-migration://offline?data=%ZZ',
    'otpauth-migration://offline?data=AAAA#private', 'otpauth-migration://user@offline?data=AAAA',
    migration([[...entry(), ...bytes(1, KEY)]]), migration([entry({ key: 'short' })]),
    migration([entry()], [...number(2, 3), ...number(3, 1)]),
    `otpauth-migration://offline?data=${encodeURIComponent(Buffer.from([10, 255, 255]).toString('base64'))}`,
    `otpauth-migration://offline?data=${encodeURIComponent(Buffer.from([128, 128, 128]).toString('base64'))}`,
    'x'.repeat(8193),
  ])('rejects malformed or ambiguous input without echoing it', (uri) => {
    expect(() => importAuthenticator(uri)).toThrow();
    try { importAuthenticator(uri); }
    catch (error) {
      expect(String(error)).not.toContain(KEY);
      expect(String(error)).not.toContain('private');
      expect(String(error)).not.toContain('data=');
    }
  });
});
