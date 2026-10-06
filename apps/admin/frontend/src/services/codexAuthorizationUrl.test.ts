import { describe, expect, it } from 'vitest';
import type { CodexAuthorization } from './api';
import { officialAuthorizationUrl } from './codexAuthorizationUrl';

const valid = 'https://auth.openai.com/oauth/authorize?response_type=code'
  + '&redirect_uri=http%3A%2F%2Flocalhost%3A1455%2Fauth%2Fcallback&state=test-state'
  + '&code_challenge_method=S256&code_challenge=' + 'a'.repeat(43);
const session: CodexAuthorization = {
  id: 'test', mode: 'BROWSER', authUrl: valid, status: 'PENDING', userCode: null,
  verificationUrl: null, expiresAt: '', account: null, errorCode: null, message: null,
};

describe('official authorization URL validation', () => {
  it('accepts only official PKCE flows with the local callback', () => {
    expect(officialAuthorizationUrl(session)).toBe(valid);
    const alternate = valid.replace('auth.openai.com', 'chatgpt.com');
    expect(officialAuthorizationUrl({ ...session, authUrl: alternate })).toBe(alternate);
  });

  it.each([
    'javascript:alert(1)', 'https://auth.openai.com/oauth/authorize',
    valid.replace('https:', 'http:'), valid.replace('auth.openai.com', 'auth.openai.com.attacker.invalid'),
    valid.replace('auth.openai.com', 'attacker@auth.openai.com'),
    valid.replace('auth.openai.com', 'auth.openai.com:444'),
    valid.replace('localhost', 'attacker.invalid'), valid.replace('1455', '8080'),
    valid.replace('S256', 'plain'), valid.replace('test-state', ''),
    valid + '&state=other', valid + '&%73tate=other', valid + '#fragment',
    valid + '&extra=' + 'x'.repeat(8192),
  ])('rejects unsafe browser login URLs %#', (authUrl) => {
    expect(officialAuthorizationUrl({ ...session, authUrl })).toBeNull();
  });

  it('does not allow arbitrary links for device-code login', () => {
    const device = { ...session, mode: 'DEVICE_CODE' as const, authUrl: null };
    expect(officialAuthorizationUrl({ ...device, verificationUrl: 'https://auth.openai.com/codex/device' }))
      .toBe('https://auth.openai.com/codex/device');
    expect(officialAuthorizationUrl({ ...device, verificationUrl: 'https://attacker.invalid' })).toBeNull();
  });
});
