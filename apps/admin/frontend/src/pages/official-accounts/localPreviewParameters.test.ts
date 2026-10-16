import { expect, it } from 'vitest';
import { parseLocalPreviewParameters } from './localPreviewParameters';

it('imports only three validated local draft fields without swallowing surrounding instructions', () => {
  const fixture = (cookie = '__Secure-next-auth.session-token.0=fixture-zero; __Secure-next-auth.session-token.1=fixture-one') => [
    'Local preview parameters. Keep this file private.',
    '',
    '[Official Account ID]',
    'workspace-fixture_123',
    '',
    '[Official Authorization - include Bearer]',
    'Bearer fixture.token-value_123',
    '',
    '[Official Cookie - optional]',
    cookie,
    '',
    'Proxy selection: keep the existing selection in the browser.',
    'Delete this file after importing your local draft.',
  ].join('\r\n');

  expect(parseLocalPreviewParameters(`\uFEFF${fixture()}`)).toEqual({
    requestedAccountId: 'workspace-fixture_123',
    authorization: 'Bearer fixture.token-value_123',
    cookie: '__Secure-next-auth.session-token.0=fixture-zero; __Secure-next-auth.session-token.1=fixture-one',
  });
  expect(parseLocalPreviewParameters(fixture('')).cookie).toBe('');

  for (const invalid of [
    fixture().replace('workspace-fixture_123', 'workspace with spaces'),
    fixture().replace('Bearer fixture.token-value_123', 'fixture-without-bearer'),
    fixture().replace('Bearer fixture.token-value_123', `Bearer ${'a'.repeat(16_384)}`),
    fixture().replace('[Official Account ID]', '[Unknown Account ID]'),
    `${fixture()}\n[Official Account ID]\nsecond-workspace`,
    fixture('other-cookie=fixture'),
    fixture('__Secure-next-auth.session-token=one; __Secure-next-auth.session-token=two'),
    fixture('__Secure-next-auth.session-token=fixture\u0000'),
    fixture().replace('Bearer fixture.token-value_123', 'Bearer fixture\nBearer another'),
    `${fixture()}\n${'x'.repeat(65_537)}`,
  ]) {
    expect(() => parseLocalPreviewParameters(invalid)).toThrow();
  }
});

it('accepts a private preset JSON file as a local draft with the same validation as txt', () => {
  const draft = { requestedAccountId: 'workspace-json', authorization: 'Bearer synthetic-json',
    cookie: '__Secure-next-auth.session-token=synthetic-cookie' };
  expect(parseLocalPreviewParameters(JSON.stringify(draft))).toEqual(draft);
  expect(parseLocalPreviewParameters(`\uFEFF${JSON.stringify({ ...draft, cookie: '' })}`)).toEqual({ ...draft, cookie: '' });
  for (const input of [null, [], { ...draft, authorization: null }, { ...draft, requestedAccountId: 123 },
    { ...draft, cookie: 'other=private' }, { ...draft, cookie: '__Secure-next-auth.session-token=one\n' },
    { ...draft, authorization: 'Bearer invalid\n' }, { ...draft, extra: 'ignored-secret' }]) {
    expect(() => parseLocalPreviewParameters(JSON.stringify(input))).toThrow();
  }
  expect(() => parseLocalPreviewParameters(`${JSON.stringify(draft)}${' '.repeat(65_536)}`)).toThrow();
});
