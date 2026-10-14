import { beforeEach, describe, expect, it, vi } from 'vitest';

import {
  clearSession,
  createTeam,
  createUser,
  deleteTeam,
  deleteUser,
  forceLogoutUser,
  getDashboard,
  getTeamQuotaOverview,
  getUserOfficialQuota,
  getUserQuotaOverview,
  getUserUsageEvents,
  resetTeamQuota,
  resetUserQuota,
  getSession,
  listTeams,
  listUsers,
  login,
  logout,
  PermissionDeniedError,
  setSession,
  updateTeam,
  updateTeamMembers,
  updateTeamTokenLimits,
  updateUserQuotaPolicy,
  updateUserTokenLimits,
  updateUser,
} from './api';
import type { AuthResponse, UserResponse } from './api';

const adminUser: UserResponse = {
  createdAt: '2026-01-01T00:00:00Z',
  displayName: '管理员',
  email: 'admin@example.com',
  id: 'admin-id',
  role: 'ADMIN',
  status: 'ACTIVE',
  teamId: null,
  fiveHourQuotaPercent: null,
  fiveHourTokenLimit: null,
  weeklyQuotaPercent: null,
  weeklyTokenLimit: null,
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

  it('requests only one users paged central usage ledger', async () => {
    setSession({ accessToken: 'admin-access-token', user: adminUser });
    fetchMock.mockResolvedValue(response({ items: [], page: 1, size: 10,
      totalElements: 0, totalPages: 0 }));

    await getUserUsageEvents('user/id', 1, 10);

    const [path, init] = fetchMock.mock.calls[0] as [string, RequestInit];
    expect(path).toBe('/api/v1/admin/users/user%2Fid/usage-events?page=1&size=10');
    expect((init.headers as Headers).get('Authorization')).toBe('Bearer admin-access-token');
  });

  it('requests team shared usage with administrator authentication', async () => {
    setSession({ accessToken: 'admin-access-token', user: adminUser });
    fetchMock.mockResolvedValue(response({ id: 'team-1', fiveHourUsedTokens: 510 }));

    await getTeamQuotaOverview('team/id');

    const [path, init] = fetchMock.mock.calls[0] as [string, RequestInit];
    expect(path).toBe('/api/v1/admin/teams/team%2Fid/quota-overview');
    expect((init.headers as Headers).get('Authorization')).toBe('Bearer admin-access-token');
  });

  it('posts administrator-only quota resets without changing limits', async () => {
    setSession({ accessToken: 'admin-access-token', user: adminUser });
    fetchMock.mockResolvedValue({ ok: true, status: 204, text: async () => '' } as Response);

    await resetUserQuota('user/id');
    await resetTeamQuota('team/id');

    expect(fetchMock).toHaveBeenCalledTimes(2);
    expect(fetchMock.mock.calls.map(([path]) => path)).toEqual([
      '/api/v1/admin/users/user%2Fid/quota-reset',
      '/api/v1/admin/teams/team%2Fid/quota-reset',
    ]);
    for (const [, init] of fetchMock.mock.calls as [string, RequestInit][]) {
      expect(init.method).toBe('POST');
      expect((init.headers as Headers).get('Authorization')).toBe('Bearer admin-access-token');
      expect(init.body).toBeUndefined();
    }
  });

  it('manages team token limits and a user team policy', async () => {
    setSession({ accessToken: 'admin-access-token', user: adminUser });
    fetchMock
      .mockResolvedValueOnce(response([]))
      .mockResolvedValueOnce(response({ id: 'team-id' }))
      .mockResolvedValueOnce(response({ id: 'team-id' }))
      .mockResolvedValueOnce(response({ id: 'team-id', version: 4 }))
      .mockResolvedValueOnce({ ok: true, status: 204, text: async () => '' } as Response)
      .mockResolvedValueOnce(response({ id: 'user-id' }))
      .mockResolvedValueOnce(response({ id: 'user-id' }));

    await expect(listTeams()).resolves.toEqual([]);
    await createTeam({
      description: '研发团队',
      fiveHourQuotaPercent: 100,
      name: '研发',
      weeklyQuotaPercent: 100,
    });
    await updateTeam('team-id', {
      version: 3,
    });
    await updateTeamTokenLimits('team-id', {
      fiveHourTokenLimit: 50_000,
      totalTokenLimit: 200_000,
      officialAccountId: null,
      version: 4,
      weeklyTokenLimit: 100_000,
    });
    await deleteTeam('team-id');
    await updateUserQuotaPolicy('user-id', {
      fiveHourQuotaPercent: null,
      teamId: 'team-id',
      weeklyQuotaPercent: null,
    });
    await updateUserTokenLimits('user-id', {
      fiveHourTokenLimit: 10_000,
      weeklyTokenLimit: null,
    });

    expect(fetchMock.mock.calls.map(([path]) => path)).toEqual([
      '/api/v1/admin/teams',
      '/api/v1/admin/teams',
      '/api/v1/admin/teams/team-id',
      '/api/v1/admin/teams/team-id/token-limits',
      '/api/v1/admin/teams/team-id',
      '/api/v1/admin/users/user-id/quota-policy',
      '/api/v1/admin/users/user-id/token-limits',
    ]);
    expect(fetchMock.mock.calls[1]?.[1]).toMatchObject({
      body: JSON.stringify({
        description: '研发团队',
        fiveHourQuotaPercent: 100,
        name: '研发',
        weeklyQuotaPercent: 100,
      }),
      method: 'POST',
    });
    expect(fetchMock.mock.calls[3]?.[1]).toMatchObject({
      body: JSON.stringify({
        fiveHourTokenLimit: 50_000,
        totalTokenLimit: 200_000,
        officialAccountId: null,
        version: 4,
        weeklyTokenLimit: 100_000,
      }),
      method: 'PUT',
    });
    expect(fetchMock.mock.calls[5]?.[1]).toMatchObject({
      body: JSON.stringify({
        fiveHourQuotaPercent: null,
        teamId: 'team-id',
        weeklyQuotaPercent: null,
      }),
      method: 'PUT',
    });
    expect(fetchMock.mock.calls[6]?.[1]).toMatchObject({
      body: JSON.stringify({ fiveHourTokenLimit: 10_000, weeklyTokenLimit: null }),
      method: 'PUT',
    });
  });

  it('clears an expired session and exposes the response status', async () => {
    setSession({ accessToken: 'expired-token', user: adminUser });
    fetchMock.mockResolvedValue(response({ message: 'token expired' }, 401));

    await expect(listUsers()).rejects.toMatchObject({ status: 401 });
    expect(getSession()).toBeNull();
  });

  it('updates only explicit team membership changes using the authenticated batch contract', async () => {
    setSession({ accessToken: 'admin-access-token', user: adminUser });
    const result = { id: 'team/id', memberCount: 3 };
    const body = { changes: [
      { userId: 'new-user', expectedTeamId: null, selected: true },
      { userId: 'moved-user', expectedTeamId: 'source-team', selected: true },
      { userId: 'old-user', expectedTeamId: 'team/id', selected: false },
    ] };
    fetchMock.mockResolvedValue(response(result));

    await expect(updateTeamMembers('team/id', body)).resolves.toEqual(result);

    expect(fetchMock).toHaveBeenCalledOnce();
    const [path, init] = fetchMock.mock.calls[0] as [string, RequestInit];
    expect(path).toBe('/api/v1/admin/teams/team%2Fid/members');
    expect(init.method).toBe('PUT');
    expect(JSON.parse(init.body as string)).toEqual(body);
    expect((init.headers as Headers).get('Authorization')).toBe('Bearer admin-access-token');
  });

  it('preserves the team membership conflict code without clearing the administrator session', async () => {
    setSession({ accessToken: 'admin-access-token', user: adminUser });
    fetchMock.mockResolvedValue(response({
      code: 'TEAM_MEMBERSHIP_CONFLICT', message: '用户团队归属已发生变化',
    }, 409));

    await expect(updateTeamMembers('team-id', { changes: [
      { userId: 'new-user', expectedTeamId: null, selected: true },
    ] })).rejects.toMatchObject({ status: 409, code: 'TEAM_MEMBERSHIP_CONFLICT' });
    expect(getSession()?.accessToken).toBe('admin-access-token');
  });

  it('refreshes an expired access token and retries the authenticated request', async () => {
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
      .mockResolvedValueOnce(
        response({ items: [], page: 0, size: 20, totalElements: 0, totalPages: 0 }),
      );

    await expect(listUsers()).resolves.toMatchObject({ items: [] });

    expect(fetchMock).toHaveBeenCalledTimes(3);
    expect(fetchMock.mock.calls[1]?.[0]).toBe('/api/v1/auth/refresh');
    const [, retryInit] = fetchMock.mock.calls[2] as [string, RequestInit];
    expect((retryInit.headers as Headers).get('Authorization')).toBe(
      'Bearer fresh-access-token',
    );
    expect(getSession()).toMatchObject({
      accessToken: 'fresh-access-token',
      refreshToken: 'rotated-refresh-token',
    });
  });

  it('coalesces concurrent refresh attempts before retrying protected requests', async () => {
    setSession({
      accessToken: 'expired-access-token',
      refreshToken: 'old-refresh-token',
      user: adminUser,
    });

    let resolveRefresh!: (value: Response) => void;
    const refreshResponse = new Promise<Response>((resolve) => {
      resolveRefresh = resolve;
    });

    fetchMock.mockImplementation((path: string, init: RequestInit) => {
      if (path === '/api/v1/auth/refresh') return refreshResponse;

      const authorization = (init.headers as Headers).get('Authorization');
      if (authorization === 'Bearer expired-access-token') {
        return Promise.resolve(response({ message: 'token expired' }, 401));
      }
      if (path === '/api/v1/admin/dashboard') {
        return Promise.resolve(
          response({
            activeUsers: 1,
            adminUsers: 1,
            disabledUsers: 0,
            newUsersLast7Days: 1,
            totalUsers: 1,
          }),
        );
      }
      return Promise.resolve(
        response({ items: [], page: 0, size: 20, totalElements: 0, totalPages: 0 }),
      );
    });

    const usersRequest = listUsers();
    const dashboardRequest = getDashboard();

    await vi.waitFor(() => {
      expect(
        fetchMock.mock.calls.filter(([path]) => path === '/api/v1/auth/refresh'),
      ).toHaveLength(1);
    });
    resolveRefresh(
      response({
        accessToken: 'fresh-access-token',
        refreshToken: 'rotated-refresh-token',
        user: adminUser,
      } satisfies AuthResponse),
    );

    await expect(Promise.all([usersRequest, dashboardRequest])).resolves.toHaveLength(2);
    expect(
      fetchMock.mock.calls.filter(([path]) => path === '/api/v1/auth/refresh'),
    ).toHaveLength(1);
    const retriedRequests = fetchMock.mock.calls.filter(([, init]) =>
      ((init as RequestInit).headers as Headers)
        .get('Authorization')
        ?.includes('fresh-access-token'),
    );
    expect(retriedRequests).toHaveLength(2);
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

  it('uses the user lifecycle endpoints for create, edit, force logout and delete', async () => {
    setSession({ accessToken: 'admin-access-token', user: adminUser });
    fetchMock
      .mockResolvedValueOnce(response(user('USER'), 201))
      .mockResolvedValueOnce(response({ ...user('USER'), displayName: '新名称' }))
      .mockResolvedValueOnce({ ok: true, status: 204, text: async () => '' } as Response)
      .mockResolvedValueOnce({ ok: true, status: 204, text: async () => '' } as Response);

    await createUser({
      displayName: '普通用户',
      email: 'user@example.com',
      password: 'strong-password-1',
      role: 'USER',
      status: 'ACTIVE',
    });
    await updateUser('user-id', { displayName: '新名称' });
    await forceLogoutUser('user-id');
    await deleteUser('user-id');

    expect(fetchMock.mock.calls.map(([path]) => path)).toEqual([
      '/api/v1/admin/users',
      '/api/v1/admin/users/user-id',
      '/api/v1/admin/users/user-id/force-logout',
      '/api/v1/admin/users/user-id',
    ]);
    expect((fetchMock.mock.calls[0]?.[1] as RequestInit).method).toBe('POST');
    expect((fetchMock.mock.calls[2]?.[1] as RequestInit).method).toBe('POST');
    expect((fetchMock.mock.calls[3]?.[1] as RequestInit).method).toBe('DELETE');
  });

  it('reads user quota and last safe official snapshot without managed account endpoints', async () => {
    setSession({ accessToken: 'admin-access-token', user: adminUser });
    fetchMock
      .mockResolvedValueOnce(response({ policy: {}, usage: {} }))
      .mockResolvedValueOnce(response({ planType: 'plus', windows: [] }));

    await getUserQuotaOverview('user-id');
    await getUserOfficialQuota('user-id');

    expect(fetchMock.mock.calls.map(([path]) => path)).toEqual([
      '/api/v1/admin/users/user-id/quota-overview',
      '/api/v1/admin/users/user-id/official-quota',
    ]);
  });
});
