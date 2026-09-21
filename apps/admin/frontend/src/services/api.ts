export type UserRole = 'USER' | 'ADMIN';
export type UserStatus = 'ACTIVE' | 'DISABLED';

export interface UserResponse {
  id: string;
  email: string;
  displayName: string;
  role: UserRole;
  status: UserStatus;
  createdAt: string;
  updatedAt: string;
}

export interface DashboardStats {
  totalUsers: number;
  activeUsers: number;
  disabledUsers: number;
  adminUsers: number;
  newUsersLast7Days: number;
}

export interface AuthResponse {
  accessToken: string;
  tokenType?: string;
  expiresIn?: number;
  refreshToken?: string;
  refreshExpiresIn?: number;
  user: UserResponse;
}

export interface Session {
  accessToken: string;
  user: UserResponse;
  tokenType?: string;
  expiresIn?: number;
  refreshToken?: string;
  refreshExpiresIn?: number;
}

export interface UserListResponse {
  items: UserResponse[];
  page: number;
  size: number;
  totalElements: number;
  totalPages: number;
}

export interface UserListParams {
  page?: number;
  size?: number;
  q?: string;
  status?: UserStatus | '';
  role?: UserRole | '';
}

export type UserUpdate = Partial<Pick<UserResponse, 'status' | 'role'>>;

const SESSION_KEY = 'codex-quota-guard.session';
export const SESSION_EXPIRED_EVENT = 'codex-quota-guard:session-expired';

let refreshPromise: Promise<AuthResponse> | null = null;

export class ApiError extends Error {
  readonly status: number;

  constructor(message: string, status: number) {
    super(message);
    this.name = 'ApiError';
    this.status = status;
  }
}

export class PermissionDeniedError extends ApiError {
  constructor(message = '当前账号没有管理员权限') {
    super(message, 403);
    this.name = 'PermissionDeniedError';
  }
}

function storage(): Storage | null {
  if (typeof window === 'undefined') return null;
  try {
    return window.sessionStorage;
  } catch {
    return null;
  }
}

export function getSession(): Session | null {
  const value = storage()?.getItem(SESSION_KEY);
  if (!value) return null;

  try {
    const session = JSON.parse(value) as Partial<Session>;
    if (
      typeof session.accessToken !== 'string' ||
      !session.accessToken ||
      !session.user ||
      typeof session.user !== 'object'
    ) {
      return null;
    }
    return session as Session;
  } catch {
    return null;
  }
}

export function setSession(session: Session): void {
  storage()?.setItem(SESSION_KEY, JSON.stringify(session));
}

export function clearSession(): void {
  storage()?.removeItem(SESSION_KEY);
}

function expireSession(): void {
  clearSession();
  if (typeof window !== 'undefined') {
    window.dispatchEvent(new Event(SESSION_EXPIRED_EVENT));
  }
}

function getErrorMessage(payload: unknown, fallback: string): string {
  if (!payload || typeof payload !== 'object') return fallback;
  const record = payload as Record<string, unknown>;
  for (const key of ['message', 'errorMessage', 'error']) {
    if (typeof record[key] === 'string' && record[key]) return record[key] as string;
  }
  return fallback;
}

async function readPayload(response: Response): Promise<unknown> {
  const text = await response.text();
  if (!text) return undefined;
  try {
    return JSON.parse(text) as unknown;
  } catch {
    return text;
  }
}

export async function request<T>(
  path: string,
  init: RequestInit = {},
  options: { authenticated?: boolean; retryAfterRefresh?: boolean } = {},
): Promise<T> {
  const authenticated = options.authenticated !== false;
  const requestSession = authenticated ? getSession() : null;
  const headers = new Headers(init.headers);
  if (!headers.has('Content-Type')) headers.set('Content-Type', 'application/json');

  if (authenticated && requestSession?.accessToken) {
    headers.set('Authorization', `Bearer ${requestSession.accessToken}`);
  }

  const response = await fetch(path, { ...init, headers });
  const payload = await readPayload(response);

  if (response.status === 401 && authenticated && options.retryAfterRefresh !== false) {
    const currentSession = getSession();

    if (
      currentSession?.accessToken &&
      currentSession.accessToken !== requestSession?.accessToken
    ) {
      return request<T>(path, init, { ...options, retryAfterRefresh: false });
    }

    if (
      currentSession?.refreshToken &&
      currentSession.accessToken === requestSession?.accessToken
    ) {
      await refreshAuthenticatedSession(currentSession.refreshToken);
      return request<T>(path, init, { ...options, retryAfterRefresh: false });
    }
  }

  if (!response.ok) {
    if (response.status === 401 || response.status === 403) {
      if (authenticated) expireSession();
      else clearSession();
    }
    const fallback =
      response.status === 401
        ? '登录已过期，请重新登录'
        : response.status === 403
          ? '当前账号没有访问权限'
          : '请求失败，请稍后重试';
    throw new ApiError(getErrorMessage(payload, fallback), response.status);
  }

  return payload as T;
}

async function refreshAuthenticatedSession(refreshToken: string): Promise<AuthResponse> {
  if (!refreshPromise) {
    refreshPromise = request<AuthResponse>(
      '/api/v1/auth/refresh',
      {
        body: JSON.stringify({ refreshToken }),
        method: 'POST',
      },
      { authenticated: false, retryAfterRefresh: false },
    ).then((response) => {
      if (
        !response.accessToken ||
        !response.refreshToken ||
        !response.user ||
        response.user.role !== 'ADMIN'
      ) {
        throw new PermissionDeniedError('刷新后的会话不具备管理员权限');
      }
      setSession(response);
      return response;
    });
  }

  const pendingRefresh = refreshPromise;
  try {
    return await pendingRefresh;
  } catch (error) {
    expireSession();
    throw error;
  } finally {
    if (refreshPromise === pendingRefresh) refreshPromise = null;
  }
}

export async function login(email: string, password: string): Promise<AuthResponse> {
  const response = await request<AuthResponse>(
    '/api/v1/auth/login',
    {
      body: JSON.stringify({ email, password }),
      method: 'POST',
    },
    { authenticated: false },
  );

  if (response.user?.role !== 'ADMIN') {
    clearSession();
    throw new PermissionDeniedError();
  }

  setSession(response);
  return response;
}

export async function logout(): Promise<void> {
  const session = getSession();
  if (!session?.refreshToken) {
    clearSession();
    return;
  }

  try {
    try {
      await revokeSession(session.accessToken, session.refreshToken);
    } catch (error) {
      if (!(error instanceof ApiError) || error.status !== 401) throw error;

      const refreshed = await request<AuthResponse>(
        '/api/v1/auth/refresh',
        {
          body: JSON.stringify({ refreshToken: session.refreshToken }),
          method: 'POST',
        },
        { authenticated: false },
      );

      if (refreshed.refreshToken) {
        await revokeSession(refreshed.accessToken, refreshed.refreshToken);
      }
    }
  } finally {
    clearSession();
  }
}

function revokeSession(accessToken: string, refreshToken: string): Promise<void> {
  return request<void>(
    '/api/v1/auth/logout',
    {
      body: JSON.stringify({ refreshToken }),
      headers: { Authorization: `Bearer ${accessToken}` },
      method: 'POST',
    },
    { authenticated: false },
  );
}

export function getCurrentUser(): Promise<UserResponse> {
  return request<UserResponse>('/api/v1/users/me');
}

export function getDashboard(): Promise<DashboardStats> {
  return request<DashboardStats>('/api/v1/admin/dashboard');
}

export function listUsers({
  page = 0,
  size = 20,
  q = '',
  status = '',
  role = '',
}: UserListParams = {}): Promise<UserListResponse> {
  const params = new URLSearchParams({
    page: String(page),
    size: String(size),
    q,
    status,
    role,
  });
  return request<UserListResponse>(`/api/v1/admin/users?${params.toString()}`);
}

export function updateUser(id: string, body: UserUpdate): Promise<UserResponse> {
  return request<UserResponse>(`/api/v1/admin/users/${encodeURIComponent(id)}`, {
    body: JSON.stringify(body),
    method: 'PATCH',
  });
}
