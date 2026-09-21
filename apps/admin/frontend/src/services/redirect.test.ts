import { describe, expect, it } from 'vitest';

import { getSafeRedirectUrl } from './redirect';

describe('safe post-login redirects', () => {
  const origin = 'http://localhost:8000';

  it('keeps same-origin paths with query strings and fragments', () => {
    expect(getSafeRedirectUrl('/users?page=2#results', origin)).toBe(
      '/users?page=2#results',
    );
  });

  it.each([null, '', 'https://example.com', '//example.com/users'])(
    'falls back to the dashboard for unsafe redirect %s',
    (redirect) => {
      expect(getSafeRedirectUrl(redirect, origin)).toBe('/dashboard');
    },
  );
});
