export type UserRole = 'USER' | 'ADMIN';
export type UserStatus = 'ACTIVE' | 'DISABLED';
export type TeamStatus = 'ACTIVE' | 'DISABLED';

export interface UserResponse {
  id: string;
  email: string;
  displayName: string;
  role: UserRole;
  status: UserStatus;
  teamId: string | null;
  fiveHourQuotaPercent: number | null;
  weeklyQuotaPercent: number | null;
  fiveHourTokenLimit: number | null;
  weeklyTokenLimit: number | null;
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

export interface UserCreate {
  email: string;
  password: string;
  displayName: string;
  role?: UserRole;
  status?: UserStatus;
}

export type UserUpdate = Partial<
  Pick<UserResponse, 'email' | 'displayName' | 'status' | 'role'> & { password: string }
>;

export interface TeamResponse {
  id: string;
  name: string;
  description: string;
  status: TeamStatus;
  fiveHourQuotaPercent: number;
  weeklyQuotaPercent: number;
  fiveHourTokenLimit: number | null;
  weeklyTokenLimit: number | null;
  totalTokenLimit: number | null;
  officialAccountId: string | null;
  memberCount: number;
  createdAt: string;
  updatedAt: string;
  version: number;
}

/** Alias used by callers that treat the response as a domain model. */
export type Team = TeamResponse;

export interface TeamCreate {
  name: string;
  description: string;
  status?: TeamStatus;
  fiveHourQuotaPercent: number;
  weeklyQuotaPercent: number;
}

export type TeamUpdate = Partial<
  Pick<
    TeamResponse,
    | 'name'
    | 'description'
    | 'status'
    | 'fiveHourQuotaPercent'
    | 'weeklyQuotaPercent'
    | 'version'
  >
>;

export interface TeamMemberChange {
  userId: string;
  expectedTeamId: string | null;
  selected: boolean;
}

export interface TeamMembersUpdate {
  changes: TeamMemberChange[];
}

export interface UserQuotaPolicy {
  teamId: string | null;
  fiveHourQuotaPercent: number | null;
  weeklyQuotaPercent: number | null;
}

export type QuotaPolicyUpdate = UserQuotaPolicy;

export interface UserTokenLimitUpdate {
  fiveHourTokenLimit: number | null;
  weeklyTokenLimit: number | null;
}

export interface TeamTokenLimitUpdate extends UserTokenLimitUpdate {
  totalTokenLimit: number | null;
  officialAccountId: string | null;
  version?: number;
}

export interface UserQuotaOverview {
  allowed?: boolean;
  blockReason?: string | null;
  policy: {
    fiveHourTokenLimit: number | null;
    weeklyTokenLimit: number | null;
  };
  usage: {
    fiveHourUsedTokens: number;
    weeklyUsedTokens: number;
    fiveHourRemainingTokens: number | null;
    weeklyRemainingTokens: number | null;
  };
  teamQuota?: TeamQuotaOverview | null;
}

export interface TeamQuotaOverview {
  id: string;
  name: string;
  status: TeamStatus;
  memberCount: number;
  fiveHourTokenLimit: number | null;
  weeklyTokenLimit: number | null;
  totalTokenLimit: number | null;
  fiveHourUsedTokens: number;
  weeklyUsedTokens: number;
  totalUsedTokens: number;
  fiveHourRemainingTokens: number | null;
  weeklyRemainingTokens: number | null;
  totalRemainingTokens: number | null;
  measuredAt: string | null;
}

export interface OfficialQuotaSnapshot {
  planType: string | null;
  fetchedAt: string;
  receivedAt: string;
  windows: Array<{
    limitId: string;
    slot: string;
    windowDurationMins: number;
    usedPercent: number;
    resetsAt: string | null;
  }>;
}

export interface OfficialAccountResponse {
  id: string;
  label: string;
  estimatedTotalTokens: number;
  reporterEmail: string | null;
  quota: OfficialQuotaSnapshot | null;
  createdAt: string;
  updatedAt: string;
  version: number;
}

export interface OfficialAccountInput {
  label: string;
  estimatedTotalTokens: number;
  reporterEmail: string | null;
  version?: number;
}

export type MailboxAuthMode = 'PASSWORD' | 'GOOGLE_OAUTH2' | 'MICROSOFT_OAUTH2';

export interface MailboxProvider {
  id: string;
  label: string;
  host: string;
  port: number;
  authModes: MailboxAuthMode[];
  help: string;
}

export interface OfficialAccountMailbox {
  configured: boolean;
  enabled: boolean;
  provider: string | null;
  host: string | null;
  port: number | null;
  username: string | null;
  recipientEmail: string | null;
  folder: string | null;
  senderEmail: string | null;
  authMode: MailboxAuthMode | null;
  oauthClientId: string | null;
  oauthTenant: string | null;
  hasPassword: boolean;
  hasClientSecret: boolean;
  hasRefreshToken: boolean;
  encryptionReady: boolean;
}

export interface OfficialAccountMailboxInput {
  enabled: boolean;
  provider: string;
  host: string;
  port: number;
  username: string;
  recipientEmail: string;
  folder: string;
  senderEmail: string;
  authMode: MailboxAuthMode;
  oauthClientId: string;
  oauthTenant: string;
  password?: string;
  oauthClientSecret?: string;
  oauthRefreshToken?: string;
}

export interface MailboxConnectionTest {
  success: boolean;
  message: string;
}

export type OfficialConnectionStatus = 'NOT_CONFIGURED' | 'UNVERIFIED' | 'CONNECTED'
  | 'AUTH_REQUIRED' | 'ACCESS_BLOCKED' | 'RATE_LIMITED' | 'UNAVAILABLE' | 'INVALID_RESPONSE';

export interface OfficialAccountConnection {
  configured: boolean;
  encryptionReady: boolean;
  status: OfficialConnectionStatus;
  requestedAccountId: string | null;
  proxyNodeId: string | null;
  frontProxyNodeId?: string | null;
  hasAuthorization: boolean;
  hasCookie: boolean;
  importedAt: string | null;
  lastAttemptAt: string | null;
  lastSuccessAt: string | null;
  lastErrorCode: string | null;
  version: number;
}

export interface OfficialConnectionInput {
  authorization: string;
  cookie?: string;
  requestedAccountId: string;
  proxyNodeId: string | null;
  frontProxyNodeId?: string | null;
  version: number;
}

export interface OfficialIdentity {
  userId: string | null;
  email: string | null;
  userName: string | null;
  accountId: string | null;
  accountName: string | null;
  planType: string | null;
  accountUserRole: string | null;
  deactivated: boolean | null;
  activeSubscription: boolean | null;
}

export interface OfficialSessionDevice {
  renderId: string;
  sessionId: string | null;
  platform: string | null;
  displayName: string | null;
  deviceModel: string | null;
  humanReadableDescription: string | null;
  osVersion: string | null;
  currentDevice: boolean | null;
  trustedDevice: boolean | null;
  lastSignedInAt: string | null;
  sessionCreatedAt: string | null;
  city: string | null;
  regionCode: string | null;
  country: string | null;
  applications: string[];
}

export interface OfficialAccountSnapshot {
  connection: OfficialAccountConnection;
  account: OfficialIdentity;
  sessions: { showSessionManager: boolean | null; devices: OfficialSessionDevice[] };
  fetchedAt: string;
}

export type ProxyProtocol = 'HTTP' | 'HTTPS' | 'SOCKS5' | 'VLESS' | 'VMESS' | 'TROJAN' | 'SHADOWSOCKS';
export interface ProxyNode {
  id: string;
  label: string;
  protocol: ProxyProtocol;
  host: string;
  port: number;
  enabled: boolean;
  hasCredentials: boolean;
  sourceSubscriptionId?: string | null;
  sourceMissing?: boolean;
  createdAt: string;
  updatedAt: string;
  version: number;
  egressIp: string | null;
  country: string | null;
  region: string | null;
  city: string | null;
  testedAt: string | null;
  testStatus: 'UNTESTED' | 'CONNECTED' | 'FAILED';
}
export interface ProxyNodeInput {
  label: string;
  protocol: 'HTTP' | 'HTTPS' | 'SOCKS5';
  host: string;
  port: number;
  username?: string;
  password?: string;
  enabled: boolean;
  version?: number;
}
export interface ProxyConnectionTest {
  connected: boolean;
  httpStatus: number | null;
  status: 'CONNECTED' | 'FAILED';
  egressIp: string | null;
  country: string | null;
  region: string | null;
  city: string | null;
  testedAt: string;
}

export function isSecureManagementContext(): boolean {
  if (typeof window === 'undefined') return false;
  const { protocol, hostname } = window.location;
  return protocol === 'https:' || (protocol === 'http:'
    && ['localhost', '127.0.0.1', '[::1]'].includes(hostname));
}

export interface ProxyChainInput {
  frontProxyNodeId?: string | null;
  proxyNodeId: string;
}
export interface ProxyChainTest extends ProxyConnectionTest {
  frontProxyNodeId: string | null;
  proxyNodeId: string;
}
export interface ProxySubscription {
  id: string;
  label: string;
  hasUrl: boolean;
  sourceHost: string;
  nodeCount: number;
  enabledNodeCount: number;
  status: string;
  createdAt: string | null;
  updatedAt: string | null;
  refreshedAt: string | null;
  version: number;
}
export interface ProxySubscriptionInput {
  label: string;
  url?: string;
  version?: number;
}

export interface OfficialAuthorizationPreset {
  available: boolean;
  requestedAccountId: string | null;
  revision: string | null;
  errorCode: string | null;
}

export interface OfficialAuthorizationPresetInput {
  version: number;
  proxyNodeId: string | null;
  frontProxyNodeId?: string | null;
  presetRevision: string;
}

export function isSupportedManagementContext(): boolean {
  if (typeof window === 'undefined') return false;
  return window.location.protocol === 'https:' || window.location.protocol === 'http:';
}

export function getOfficialAccountConnection(id: string): Promise<OfficialAccountConnection> {
  return request(`/api/v1/admin/official-accounts/${encodeURIComponent(id)}/connection`);
}

export function getOfficialAuthorizationPreset(): Promise<OfficialAuthorizationPreset> {
  return request('/api/v1/admin/official-authorization-preset');
}

export function applyOfficialAuthorizationPreset(id: string, body: OfficialAuthorizationPresetInput): Promise<OfficialAccountConnection> {
  return request(`/api/v1/admin/official-accounts/${encodeURIComponent(id)}/connection/preset`, {
    method: 'POST', body: JSON.stringify(body),
  });
}

export function saveOfficialAccountConnection(id: string, body: OfficialConnectionInput): Promise<OfficialAccountConnection> {
  return request(`/api/v1/admin/official-accounts/${encodeURIComponent(id)}/connection`, {
    method: 'PUT', body: JSON.stringify(body),
  });
}

export function deleteOfficialAccountConnection(id: string): Promise<void> {
  return request(`/api/v1/admin/official-accounts/${encodeURIComponent(id)}/connection`, { method: 'DELETE' });
}

export function getOfficialAccountSessions(id: string): Promise<OfficialAccountSnapshot> {
  return request(`/api/v1/admin/official-accounts/${encodeURIComponent(id)}/sessions`);
}

export function refreshOfficialAccountConnection(id: string): Promise<OfficialAccountSnapshot> {
  return request(`/api/v1/admin/official-accounts/${encodeURIComponent(id)}/connection/refresh`, { method: 'POST' });
}

export function listProxyNodes(): Promise<ProxyNode[]> {
  return request('/api/v1/admin/proxy-nodes');
}

export function createProxyNode(body: ProxyNodeInput): Promise<ProxyNode> {
  return request('/api/v1/admin/proxy-nodes', { method: 'POST', body: JSON.stringify(body) });
}

export function updateProxyNode(id: string, body: ProxyNodeInput): Promise<ProxyNode> {
  return request(`/api/v1/admin/proxy-nodes/${encodeURIComponent(id)}`, { method: 'PUT', body: JSON.stringify(body) });
}

export function deleteProxyNode(id: string): Promise<void> {
  return request(`/api/v1/admin/proxy-nodes/${encodeURIComponent(id)}`, { method: 'DELETE' });
}

export function testProxyNode(id: string): Promise<ProxyConnectionTest> {
  return request(`/api/v1/admin/proxy-nodes/${encodeURIComponent(id)}/test`, { method: 'POST' });
}

export function setProxyNodeEnabled(id: string, body: { enabled: boolean; version: number }): Promise<ProxyNode> {
  return request(`/api/v1/admin/proxy-nodes/${encodeURIComponent(id)}/enabled`, { method: 'PUT', body: JSON.stringify(body) });
}

export function testProxyChain(body: ProxyChainInput): Promise<ProxyChainTest> {
  return request('/api/v1/admin/proxy-chains/test', { method: 'POST', body: JSON.stringify(body) });
}

export function saveOfficialAccountProxyChain(id: string, body: ProxyChainInput & { version: number }): Promise<OfficialAccountConnection> {
  return request(`/api/v1/admin/official-accounts/${encodeURIComponent(id)}/connection/proxy`, { method: 'PUT', body: JSON.stringify(body) });
}

export function listProxySubscriptions(): Promise<ProxySubscription[]> {
  return request('/api/v1/admin/proxy-subscriptions');
}

export function createProxySubscription(body: ProxySubscriptionInput & { url: string }): Promise<ProxySubscription> {
  return request('/api/v1/admin/proxy-subscriptions', { method: 'POST', body: JSON.stringify(body) });
}

export function updateProxySubscription(id: string, body: ProxySubscriptionInput & { version: number }): Promise<ProxySubscription> {
  return request(`/api/v1/admin/proxy-subscriptions/${encodeURIComponent(id)}`, { method: 'PUT', body: JSON.stringify(body) });
}

export function refreshProxySubscription(id: string, version: number): Promise<ProxySubscription> {
  return request(`/api/v1/admin/proxy-subscriptions/${encodeURIComponent(id)}/refresh`, { method: 'POST', body: JSON.stringify({ version }) });
}

export function deleteProxySubscription(id: string, version: number): Promise<void> {
  return request(`/api/v1/admin/proxy-subscriptions/${encodeURIComponent(id)}?version=${encodeURIComponent(version)}`, { method: 'DELETE' });
}

export interface MeterDeviceStatus {
  deviceId: string;
  status: 'ACTIVE' | 'REVOKED';
  expiresAt: string;
  lastSeenAt: string | null;
  online: boolean;
}

export type UsageCostStatus = 'PRICED' | 'MISSING_PRICE' | 'MISSING_BREAKDOWN' | 'INVALID_BREAKDOWN';

export interface UsageTokenBreakdown {
  inputTokens?: number | null;
  cachedInputTokens?: number | null;
  cacheWriteInputTokens?: number | null;
  outputTokens?: number | null;
  reasoningOutputTokens?: number | null;
  estimatedCostUsd?: number | null;
  costStatus?: UsageCostStatus;
}

export interface UsageEventLogItem extends UsageTokenBreakdown {
    id: string;
    occurredAt: string;
    receivedAt: string;
    totalTokens: number;
    source: 'TOKEN_USAGE_RECORD' | 'CUMULATIVE_TOKEN_COUNT_FALLBACK';
    model?: string | null;
    reasoningEffort?: string | null;
}

export interface UsageEventLogPage {
  items: UsageEventLogItem[];
  page: number;
  size: number;
  totalElements: number;
  totalPages: number;
}

export interface UsageStats {
  eventCount: number;
  totalTokens: number;
  inputTokens: number | null;
  cachedInputTokens: number | null;
  cacheWriteInputTokens: number | null;
  outputTokens: number | null;
  reasoningOutputTokens: number | null;
  breakdownEventCount: number;
  costedEventCount: number;
  estimatedCostUsd: number | null;
  cacheHitRatePercent: number | null;
}

export interface UsageGroup {
  key: string | null;
  label: string;
  stats: UsageStats;
}

export interface UsageLedgerItem extends UsageEventLogItem {
  userId: string | null;
  userName: string | null;
  teamId: string | null;
  teamName: string | null;
}

export interface UsageAnalytics {
  from: string;
  to: string;
  currency: 'USD';
  timezone: 'UTC';
  granularity: 'HOUR' | 'DAY';
  summary: UsageStats;
  trend: Array<{ bucket: string; stats: UsageStats }>;
  models: UsageGroup[];
  reasoningEfforts: UsageGroup[];
  users: UsageGroup[];
  teams: UsageGroup[];
  events: Omit<UsageEventLogPage, 'items'> & { items: UsageLedgerItem[] };
}

export interface UsageAnalyticsParams {
  from?: string;
  to?: string;
  granularity?: 'HOUR' | 'DAY';
  model?: string;
  reasoningEffort?: string;
  userId?: string;
  teamId?: string;
  page?: number;
  size?: number;
}

export interface UsageOptions {
  models: Array<{ value: string; label: string }>;
  users: Array<{ value: string; label: string }>;
  teams: Array<{ value: string; label: string }>;
}

export interface UsageModelPriceInput {
  model: string;
  inputPrice: number;
  outputPrice: number;
  cacheReadPrice: number;
  cacheWritePrice: number;
}

export interface UsageModelPrice extends UsageModelPriceInput {
  currency: 'USD';
  updatedAt: string;
  priceSource: 'CC_SWITCH' | 'MANUAL';
  sourceReference: string | null;
  sourceCheckedAt: string | null;
}

export function getUsageAnalytics(filters: UsageAnalyticsParams): Promise<UsageAnalytics> {
  const params = new URLSearchParams();
  Object.entries(filters).forEach(([key, value]) => {
    if (value !== undefined && value !== '') params.set(key, String(value));
  });
  return request<UsageAnalytics>(`/api/v1/admin/usage/analytics?${params.toString()}`);
}

export function getUsageOptions(): Promise<UsageOptions> {
  return request<UsageOptions>('/api/v1/admin/usage/options');
}

export function listUsageModelPrices(): Promise<UsageModelPrice[]> {
  return request<UsageModelPrice[]>('/api/v1/admin/usage/model-prices');
}

export function saveUsageModelPrice(body: UsageModelPriceInput): Promise<UsageModelPrice> {
  return request<UsageModelPrice>('/api/v1/admin/usage/model-prices', {
    method: 'PUT', body: JSON.stringify(body),
  });
}

export function deleteUsageModelPrice(model: string): Promise<void> {
  return request<void>(`/api/v1/admin/usage/model-prices?${new URLSearchParams({ model })}`, {
    method: 'DELETE',
  });
}

export function getUserMeterDevices(id: string): Promise<MeterDeviceStatus[]> {
  return request<MeterDeviceStatus[]>(
    `/api/v1/admin/users/${encodeURIComponent(id)}/meter-devices`,
  );
}

export function getUserQuotaOverview(id: string): Promise<UserQuotaOverview> {
  return request<UserQuotaOverview>(
    `/api/v1/admin/users/${encodeURIComponent(id)}/quota-overview`,
  );
}

export function getTeamQuotaOverview(id: string): Promise<TeamQuotaOverview> {
  return request<TeamQuotaOverview>(
    `/api/v1/admin/teams/${encodeURIComponent(id)}/quota-overview`,
  );
}

export function resetUserQuota(id: string): Promise<void> {
  return request<void>(`/api/v1/admin/users/${encodeURIComponent(id)}/quota-reset`, {
    method: 'POST',
  });
}

export function resetTeamQuota(id: string): Promise<void> {
  return request<void>(`/api/v1/admin/teams/${encodeURIComponent(id)}/quota-reset`, {
    method: 'POST',
  });
}

export function getUserUsageEvents(
  id: string,
  page = 0,
  size = 10,
): Promise<UsageEventLogPage> {
  const params = new URLSearchParams({ page: String(page), size: String(size) });
  return request<UsageEventLogPage>(
    `/api/v1/admin/users/${encodeURIComponent(id)}/usage-events?${params.toString()}`,
  );
}

export async function getUserOfficialQuota(id: string): Promise<OfficialQuotaSnapshot | null> {
  return (await request<OfficialQuotaSnapshot | undefined>(
    `/api/v1/admin/users/${encodeURIComponent(id)}/official-quota`,
  )) ?? null;
}

export function listOfficialAccounts(): Promise<OfficialAccountResponse[]> {
  return request<OfficialAccountResponse[]>('/api/v1/admin/official-accounts');
}

export function createOfficialAccount(body: OfficialAccountInput): Promise<OfficialAccountResponse> {
  return request<OfficialAccountResponse>('/api/v1/admin/official-accounts', {
    body: JSON.stringify(body), method: 'POST',
  });
}

export function updateOfficialAccount(id: string, body: OfficialAccountInput): Promise<OfficialAccountResponse> {
  return request<OfficialAccountResponse>(`/api/v1/admin/official-accounts/${encodeURIComponent(id)}`, {
    body: JSON.stringify(body), method: 'PUT',
  });
}

export function deleteOfficialAccount(id: string): Promise<void> {
  return request<void>(`/api/v1/admin/official-accounts/${encodeURIComponent(id)}`, { method: 'DELETE' });
}

export function listMailboxProviders(): Promise<MailboxProvider[]> {
  return request<MailboxProvider[]>('/api/v1/admin/mail-assistance/providers');
}

export function getOfficialAccountMailbox(id: string): Promise<OfficialAccountMailbox> {
  return request<OfficialAccountMailbox>(`/api/v1/admin/official-accounts/${encodeURIComponent(id)}/mailbox`);
}

export function saveOfficialAccountMailbox(id: string, body: OfficialAccountMailboxInput): Promise<OfficialAccountMailbox> {
  return request<OfficialAccountMailbox>(`/api/v1/admin/official-accounts/${encodeURIComponent(id)}/mailbox`, {
    body: JSON.stringify(body), method: 'PUT',
  });
}

export function deleteOfficialAccountMailbox(id: string): Promise<void> {
  return request<void>(`/api/v1/admin/official-accounts/${encodeURIComponent(id)}/mailbox`, { method: 'DELETE' });
}

export function testOfficialAccountMailbox(id: string): Promise<MailboxConnectionTest> {
  return request<MailboxConnectionTest>(`/api/v1/admin/official-accounts/${encodeURIComponent(id)}/mailbox/test`, { method: 'POST' });
}

const SESSION_KEY = 'codex-quota-guard.session';
export const SESSION_EXPIRED_EVENT = 'codex-quota-guard:session-expired';

let refreshPromise: Promise<AuthResponse> | null = null;

export class ApiError extends Error {
  readonly status: number;
  readonly code?: string;

  constructor(message: string, status: number, code?: string) {
    super(message);
    this.name = 'ApiError';
    this.status = status;
    this.code = code;
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
    const code = payload && typeof payload === 'object' && 'code' in payload
      && typeof payload.code === 'string' ? payload.code : undefined;
    throw new ApiError(getErrorMessage(payload, fallback), response.status, code);
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

export function getUser(id: string): Promise<UserResponse> {
  return request<UserResponse>(`/api/v1/admin/users/${encodeURIComponent(id)}`);
}

export function createUser(body: UserCreate): Promise<UserResponse> {
  return request<UserResponse>('/api/v1/admin/users', {
    body: JSON.stringify(body),
    method: 'POST',
  });
}

export function updateUser(id: string, body: UserUpdate): Promise<UserResponse> {
  return request<UserResponse>(`/api/v1/admin/users/${encodeURIComponent(id)}`, {
    body: JSON.stringify(body),
    method: 'PATCH',
  });
}

export function deleteUser(id: string): Promise<void> {
  return request<void>(`/api/v1/admin/users/${encodeURIComponent(id)}`, {
    method: 'DELETE',
  });
}

export function forceLogoutUser(id: string): Promise<void> {
  return request<void>(
    `/api/v1/admin/users/${encodeURIComponent(id)}/force-logout`,
    { method: 'POST' },
  );
}

export function updateUserQuotaPolicy(
  userId: string,
  body: UserQuotaPolicy,
): Promise<UserResponse | void> {
  return request<UserResponse | void>(
    `/api/v1/admin/users/${encodeURIComponent(userId)}/quota-policy`,
    {
      body: JSON.stringify(body),
      method: 'PUT',
    },
  );
}

export function updateUserTokenLimits(
  userId: string,
  body: UserTokenLimitUpdate,
): Promise<UserResponse> {
  return request<UserResponse>(
    `/api/v1/admin/users/${encodeURIComponent(userId)}/token-limits`,
    {
      body: JSON.stringify(body),
      method: 'PUT',
    },
  );
}

export async function listTeams(): Promise<TeamResponse[]> {
  const response = await request<TeamResponse[] | { items?: TeamResponse[] }>(
    '/api/v1/admin/teams',
  );
  return Array.isArray(response) ? response : response.items ?? [];
}

export function getTeam(id: string): Promise<TeamResponse> {
  return request<TeamResponse>(`/api/v1/admin/teams/${encodeURIComponent(id)}`);
}

export function createTeam(body: TeamCreate): Promise<TeamResponse> {
  return request<TeamResponse>('/api/v1/admin/teams', {
    body: JSON.stringify(body),
    method: 'POST',
  });
}

export function updateTeam(id: string, body: TeamUpdate): Promise<TeamResponse> {
  return request<TeamResponse>(`/api/v1/admin/teams/${encodeURIComponent(id)}`, {
    body: JSON.stringify(body),
    method: 'PATCH',
  });
}

export function updateTeamMembers(id: string, body: TeamMembersUpdate): Promise<TeamResponse> {
  return request<TeamResponse>(`/api/v1/admin/teams/${encodeURIComponent(id)}/members`, {
    body: JSON.stringify(body),
    method: 'PUT',
  });
}

export function updateTeamTokenLimits(
  id: string,
  body: TeamTokenLimitUpdate,
): Promise<TeamResponse> {
  return request<TeamResponse>(`/api/v1/admin/teams/${encodeURIComponent(id)}/token-limits`, {
    body: JSON.stringify(body),
    method: 'PUT',
  });
}

export function deleteTeam(id: string): Promise<void> {
  return request<void>(`/api/v1/admin/teams/${encodeURIComponent(id)}`, {
    method: 'DELETE',
  });
}
