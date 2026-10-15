import { describe, expect, it } from 'vitest';
import routes from './routes';
describe('官方账户子菜单', () => {
  it('has separate account and session pages and preserves the old URL with a redirect', () => {
    const parent = routes.find((route) => route.path === '/official-accounts');
    expect(parent).toMatchObject({ name: '官方账户', access: 'canAdmin' });
    expect(parent?.routes).toEqual(expect.arrayContaining([
      expect.objectContaining({ path: '/official-accounts/accounts', name: '账户管理', component: './official-accounts' }),
      expect.objectContaining({ path: '/official-accounts/sessions', name: '会话管理', component: './official-accounts/sessions' }),
      expect.objectContaining({ path: '/official-accounts', redirect: '/official-accounts/accounts' }),
    ]));
  });
});
