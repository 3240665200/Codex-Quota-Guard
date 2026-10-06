import type { CodexAuthorization } from './api';

/** Defense in depth: never navigate to an arbitrary URL returned by an API. */
export function officialAuthorizationUrl(value: CodexAuthorization): string | null {
  if (value.mode === 'DEVICE_CODE') {
    return value.verificationUrl === 'https://auth.openai.com/codex/device'
      ? value.verificationUrl : null;
  }
  if (value.mode !== 'BROWSER' || !value.authUrl || value.authUrl.length > 8192) return null;
  try {
    const url = new URL(value.authUrl);
    if (url.protocol !== 'https:' || !['auth.openai.com', 'chatgpt.com'].includes(url.hostname)
      || url.port || url.username || url.password || url.hash) return null;
    const keys = [...url.searchParams.keys()];
    if (new Set(keys).size !== keys.length) return null;
    const callback = new URL(url.searchParams.get('redirect_uri') ?? '');
    if (callback.protocol !== 'http:' || !['localhost', '127.0.0.1'].includes(callback.hostname)
      || callback.port !== '1455' || callback.pathname !== '/auth/callback'
      || callback.username || callback.password || callback.search || callback.hash
      || url.searchParams.get('response_type') !== 'code'
      || url.searchParams.get('code_challenge_method') !== 'S256'
      || !/^[A-Za-z0-9_-]{43}$/.test(url.searchParams.get('code_challenge') ?? '')
      || !url.searchParams.get('state')?.trim()) return null;
    return value.authUrl;
  } catch {
    return null;
  }
}
