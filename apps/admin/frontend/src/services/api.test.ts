import { beforeEach, describe, expect, it, vi } from 'vitest';

import {
  clearSession,
  getSession,
  listUsers,
  login,
  logout,
  PermissionDeniedError,
  setSession,
} from './api';
import type { AuthResponse, UserResponse } from './api';

const adminUser: UserResponse = {
  createdAt: '2026-01-01T00:00:00Z',
  displayName: '管理员',
  email: 'admin@example.com',
  id: 'admin-id',
  role: 'ADMIN',
  status: 'ACTIVE',
  updatedAt: '2026-01-01T00:00:00Z',
};

const user = (role: UserResponse['role']): UserResponse => ({
  ...adminUser,
  email: `${role.toLowerCase()}@example.com`,
  id: `${role.toLowerCase()}-id`,
  role,
});

function response(body: unknown, status = 200): Response {
  return {
    ok: status >= 200 && status < 300,
    status,
    text: async () => JSON.stringify(body),
  } as Response;
}

describe('admin API client', () => {
  let fetchMock: ReturnType<typeof vi.fn>;

  beforeEach(() => {
    clearSession();
    fetchMock = vi.fn();
    vi.stubGlobal('fetch', fetchMock);
  });

  it('logs in without sending a stale bearer token and stores the admin session', async () => {
    const payload: AuthResponse = {
      accessToken: 'admin-access-token',
      refreshToken: 'refresh-token',
      user: adminUser,
    };
    setSession({ accessToken: 'stale-token', user: adminUser });
    fetchMock.mockResolvedValue(response(payload));

    await expect(login('admin@example.com', 'password')).resolves.toEqual(payload);

    const [, init] = fetchMock.mock.calls[0] as [string, RequestInit];
    expect(init.method).toBe('POST');
    expect(init.headers).toBeInstanceOf(Headers);
    expect((init.headers as Headers).has('Authorization')).toBe(false);
    expect(init.body).toBe(JSON.stringify({ email: 'admin@example.com', password: 'password' }));
    expect(getSession()).toMatchObject({ accessToken: 'admin-access-token', user: adminUser });
  });

  it('clears the session and rejects non-admin logins', async () => {
    const existingSession = { accessToken: 'old-token', user: adminUser };
    setSession(existingSession);
    fetchMock.mockResolvedValue(response({ accessToken: 'user-token', user: user('USER') }));

    await expect(login('user@example.com', 'password')).rejects.toBeInstanceOf(
      PermissionDeniedError,
    );
    expect(getSession()).toBeNull();
  });

  it('sends filters, pagination and the bearer token for user queries', async () => {
    setSession({ accessToken: 'admin-access-token', user: adminUser });
    fetchMock.mockResolvedValue(
      response({ items: [], page: 2, size: 20, totalElements: 0, totalPages: 0 }),
    );

    await listUsers({ page: 2, q: 'alice', role: 'USER', size: 20, status: 'ACTIVE' });

    const [path, init] = fetchMock.mock.calls[0] as [string, RequestInit];
    expect(path).toBe('/api/v1/admin/users?page=2&size=20&q=alice&status=ACTIVE&role=USER');
    expect((init.headers as Headers).get('Authorization')).toBe('Bearer admin-access-token');
  });

  it('clears an expired session and exposes the response status', async () => {
    setSession({ accessToken: 'expired-token', user: adminUser });
    fetchMock.mockResolvedValue(response({ message: 'token expired' }, 401));

    await expect(listUsers()).rejects.toMatchObject({ status: 401 });
    expect(getSession()).toBeNull();
  });

  it('revokes the refresh session on logout and always clears local credentials', async () => {
    setSession({
      accessToken: 'admin-access-token',
      refreshToken: 'admin-refresh-token',
      user: adminUser,
    });
    fetchMock.mockResolvedValue({ ok: true, status: 204, text: async () => '' } as Response);

    await logout();

    const [path, init] = fetchMock.mock.calls[0] as [string, RequestInit];
    expect(path).toBe('/api/v1/auth/logout');
    expect(init.method).toBe('POST');
    expect(init.body).toBe(JSON.stringify({ refreshToken: 'admin-refresh-token' }));
    expect((init.headers as Headers).get('Authorization')).toBe('Bearer admin-access-token');
    expect(getSession()).toBeNull();
  });

  it('refreshes an expired access token before revoking the rotated session', async () => {
    setSession({
      accessToken: 'expired-access-token',
      refreshToken: 'old-refresh-token',
      user: adminUser,
    });
    fetchMock
      .mockResolvedValueOnce(response({ message: 'token expired' }, 401))
      .mockResolvedValueOnce(
        response({
          accessToken: 'fresh-access-token',
          refreshToken: 'rotated-refresh-token',
          user: adminUser,
        } satisfies AuthResponse),
      )
      .mockResolvedValueOnce({ ok: true, status: 204, text: async () => '' } as Response);

    await logout();

    expect(fetchMock).toHaveBeenCalledTimes(3);
    expect(fetchMock.mock.calls[1]?.[0]).toBe('/api/v1/auth/refresh');
    const [, finalInit] = fetchMock.mock.calls[2] as [string, RequestInit];
    expect(finalInit.body).toBe(JSON.stringify({ refreshToken: 'rotated-refresh-token' }));
    expect((finalInit.headers as Headers).get('Authorization')).toBe('Bearer fresh-access-token');
    expect(getSession()).toBeNull();
  });
});
