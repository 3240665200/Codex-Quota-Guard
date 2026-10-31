import { invoke } from "@tauri-apps/api/core";
import axios, { type AxiosRequestConfig } from "axios";
import { z } from "zod";
import { compareUsd, usdOverviewSchema } from './usd';

const isoInstant = z.string().min(1);
const tokenCount = z
  .number()
  .finite()
  .int()
  .min(0)
  .max(Number.MAX_SAFE_INTEGER);

const autostartSchema = z.object({
  supported: z.boolean(),
  enabled: z.boolean(),
});
const launchPreferencesSchema = z.object({ silentStartup: z.boolean() });
const desktopVersionSchema = z.object({ currentVersion: z.string().regex(/^\d+\.\d+\.\d+$/), target: z.enum(["windows", "darwin", "linux"]), arch: z.enum(["x86_64", "aarch64"]) });
const desktopUpdateSchema = z.object({ currentVersion: z.string().max(32), latestVersion: z.string().max(32).regex(/^\d+\.\d+\.\d+$/).nullable(), available: z.boolean(), notes: z.string().max(4000), publishedAt: z.string().nullable(), sizeBytes: z.number().int().min(0).max(512 * 1024 * 1024), sha256: z.string().regex(/^[a-f0-9]{64}$/i).nullable() });

const startupGateSchema = z.object({
  state: z.enum([
    "READY",
    "CHECKING",
    "ADMIN_REQUIRED",
    "METER_UNAVAILABLE",
    "GUARD_ERROR",
    "CODEX_RESTORE_FAILED",
    "UNSUPPORTED",
  ]),
  ccSwitchTerminated: z.boolean(),
  codexConnectionRestored: z.boolean().optional(),
  codexRestoreError: z.string().min(1).max(512).nullable().optional(),
});

const meterVersionSchema = z.object({
  installedVersion: z.string().min(1).max(64).nullable(),
  bundledVersion: z.string().min(1).max(64).nullable(),
  needsUpgrade: z.boolean().nullable(),
  status: z.enum([
    "CURRENT",
    "UPGRADE_REQUIRED",
    "NOT_INSTALLED",
    "UNKNOWN",
    "UNSUPPORTED",
  ]),
});

const meterDiagnosticsSchema = z.object({
  supported: z.boolean(),
  checkedAtEpochSeconds: tokenCount,
  checks: z.array(z.object({
    code: z.string().min(1).max(32),
    label: z.string().min(1).max(100),
    state: z.enum(["PASS", "WARNING"]),
    detail: z.string().min(1).max(512),
    repairable: z.boolean(),
  })).max(20),
});
export type MeterDiagnostics = z.infer<typeof meterDiagnosticsSchema>;

const serverConfigSchema = z.object({
  url: z.string().url(),
  meterSync: z.enum(["NOT_INSTALLED", "UPDATED", "UNCHANGED"]).optional(),
});

const deviceIdentitySchema = z
  .strictObject({
    deviceId: z.string().uuid().nullable(),
    enrollmentId: z.string().uuid().nullable(),
  })
  .refine(
    ({ deviceId, enrollmentId }) =>
      (deviceId === null && enrollmentId === null) ||
      (deviceId !== null && deviceId === enrollmentId),
    "安装设备标识无效。",
  );

const sessionVaultSchema = z.strictObject({
  serverOrigin: z.string().url(),
  refreshToken: z.string().min(1).max(500),
  userId: z.string().uuid(),
  loginAtEpochSeconds: tokenCount,
  expiresAtEpochSeconds: tokenCount,
});

const systemUserSchema = z.object({
  id: z.string().min(1),
  email: z.string().email(),
  displayName: z.string().min(1),
  role: z.string().min(1),
  status: z.string().min(1),
});

const authResponseSchema = z.strictObject({
  accessToken: z.string().min(1).max(4096),
  tokenType: z.string().min(1).max(32),
  expiresIn: tokenCount.min(1).max(86400),
  refreshToken: z.string().min(1).max(500),
  refreshExpiresIn: tokenCount.min(1).max(365 * 86400),
  user: systemUserSchema,
});

const systemSessionSchema = z.object({
  authenticated: z.boolean(),
  user: systemUserSchema.nullable(),
});

const clientAccessSchema = z
  .strictObject({
    allowed: z.boolean(),
    bindingId: z.string().uuid().nullable(),
    reason: z.string().min(1).max(100).nullable(),
  })
  .refine((value) =>
    value.allowed
      ? value.bindingId !== null && value.reason === null
      : value.bindingId === null && value.reason !== null,
  );

export const bindingRequiredMessage = "请先完成本设备官方会话绑定。";
export const bindingGateEvent = "quota-guard-binding-required";

const sessionBindingSchema = z.strictObject({
  id: z.string().uuid(),
  userId: z.string().uuid(),
  userDisplayName: z.string().max(200),
  userEmail: z.string().email(),
  deviceId: z.string().uuid(),
  enrollmentId: z.string().uuid(),
  officialAccountId: z.string().uuid(),
  sessionId: z.string().min(1).max(2048),
  status: z.string().min(1).max(40),
  matchMethod: z.string().min(1).max(80),
  verificationLevel: z.string().min(1).max(80),
  createdAt: z.iso.datetime({ offset: true }),
  endedAt: z.iso.datetime({ offset: true }).nullable(),
});
const sessionBindingAttemptSchema = z.strictObject({
  id: z.string().uuid(),
  deviceId: z.string().uuid(),
  enrollmentId: z.string().uuid(),
  officialAccountId: z.string().uuid(),
  status: z.enum([
    "QUEUED",
    "PREPARING",
    "READY_FOR_LOGIN",
    "DETECTING",
    "AWAITING_CONFIRMATION",
    "BOUND",
    "CANCELLED",
    "EXPIRED",
    "REJECTED",
  ]),
  queuePosition: z.number().int().nonnegative(),
  loginAllowed: z.boolean(),
  candidateSessionId: z.string().min(1).max(2048).nullable(),
  candidateDisplayName: z.string().max(300).nullable(),
  candidatePlatform: z.string().max(200).nullable(),
  expiresAt: z.iso.datetime({ offset: true }),
  errorCode: z
    .string()
    .regex(/^[A-Z0-9_]{1,100}$/)
    .nullable(),
  quarantined: z.boolean(),
  binding: sessionBindingSchema.nullable(),
  queueState: z.enum(["IDLE", "SETTLING", "RETRYING", "PREPARING", "ACTIVE"]),
  retryAt: z.iso.datetime({ offset: true }).nullable(),
});
const sessionBindingCurrentSchema = z.strictObject({
  attempt: sessionBindingAttemptSchema.nullable(),
  binding: sessionBindingSchema.nullable(),
});

const loginAssistanceReason = z
  .string()
  .regex(/^[A-Z][A-Z0-9_]{0,63}$/)
  .nullable();
const loginAssistanceContextSchema = z
  .strictObject({
    allowed: z.boolean(),
    reasonCode: loginAssistanceReason,
    bindingAttemptId: z.string().uuid().nullable(),
    expiresAt: z.iso.datetime({ offset: true }).nullable(),
    serverTime: z.iso.datetime({ offset: true }),
    mailEnabled: z.boolean(),
    authenticatorEnabled: z.boolean(),
  })
  .refine((value) =>
    value.allowed
      ? value.bindingAttemptId !== null &&
        value.expiresAt !== null &&
        Date.parse(value.expiresAt) > Date.parse(value.serverTime)
      : value.bindingAttemptId === null && value.expiresAt === null,
  );
export type LoginAssistanceContext = z.infer<
  typeof loginAssistanceContextSchema
>;
export const assistanceAccessRevokedEvent = "quota-guard:assistance-revoked";
const loginAssistanceInfoSchema = z.strictObject({
  available: z.boolean(),
  reasonCode: loginAssistanceReason,
  officialAccountId: z.string().min(1).max(128).nullable(),
  accountLabel: z.string().min(1).max(160).nullable(),
  recipientEmail: z.string().email().nullable(),
});

const authenticatorInfoSchema = z.strictObject({
  available: z.boolean(),
  reasonCode: z.string().max(64).nullable(),
  officialAccountId: z.string().uuid().nullable(),
  accountLabel: z.string().max(120).nullable(),
});
const authenticatorCodeSchema = z
  .strictObject({
    code: z.string().regex(/^(?:[0-9]{6}|[0-9]{8})$/),
    serverTime: z.string().datetime({ offset: true }),
    expiresAt: z.string().datetime({ offset: true }),
    periodSeconds: z.number().int().min(15).max(120),
    officialAccountId: z.string().uuid(),
  })
  .refine((value) => {
    const remaining =
      Date.parse(value.expiresAt) - Date.parse(value.serverTime);
    return remaining > 0 && remaining <= value.periodSeconds * 1000;
  });
export type AuthenticatorInfo = z.infer<typeof authenticatorInfoSchema>;
export type AuthenticatorCode = z.infer<typeof authenticatorCodeSchema>;
const loginAssistanceListenerSchema = z
  .strictObject({
    id: z.string().uuid(),
    state: z.enum([
      "CONNECTING",
      "LISTENING",
      "RECEIVED",
      "CANCELLED",
      "TIMED_OUT",
      "FAILED",
    ]),
    startedAt: z.iso.datetime({ offset: true }),
    expiresAt: z.iso.datetime({ offset: true }),
    listeningAt: z.iso.datetime({ offset: true }).nullable(),
    receivedAt: z.iso.datetime({ offset: true }).nullable(),
    code: z
      .string()
      .regex(/^\d{6}$/)
      .nullable(),
    errorCode: loginAssistanceReason,
  })
  .superRefine((value, context) => {
    const started = Date.parse(value.startedAt);
    const expires = Date.parse(value.expiresAt);
    const listening =
      value.listeningAt === null ? null : Date.parse(value.listeningAt);
    const received =
      value.receivedAt === null ? null : Date.parse(value.receivedAt);
    if (
      expires <= started ||
      (listening !== null && (listening < started || listening >= expires)) ||
      (value.state === "RECEIVED"
        ? value.code === null ||
          listening === null ||
          received === null ||
          received < listening ||
          received >= expires
        : value.code !== null || received !== null)
    ) {
      context.addIssue({
        code: "custom",
        message: "监听结果与本次监听时间或状态不一致。",
      });
    }
  });

const quotaTeamSchema = z.object({
  id: z.string().min(1),
  name: z.string().min(1),
});

const teamQuotaSchema = quotaTeamSchema.extend({
  status: z.enum(["ACTIVE", "DISABLED"]),
  memberCount: tokenCount,
  fiveHourTokenLimit: tokenCount.nullable(),
  weeklyTokenLimit: tokenCount.nullable(),
  totalTokenLimit: tokenCount.nullable(),
  fiveHourUsedTokens: tokenCount,
  weeklyUsedTokens: tokenCount,
  totalUsedTokens: tokenCount,
  fiveHourRemainingTokens: tokenCount.nullable(),
  weeklyRemainingTokens: tokenCount.nullable(),
  totalRemainingTokens: tokenCount.nullable(),
  measuredAt: isoInstant.nullable(),
});

const teamMemberUsageSchema = z
  .object({
    userId: z.string().uuid(),
    displayName: z.string(),
    status: z.enum(["ACTIVE", "DISABLED"]),
    fiveHourUsedTokens: tokenCount,
    weeklyUsedTokens: tokenCount,
    totalUsedTokens: tokenCount,
    fiveHourTokenLimit: tokenCount.nullable(),
    weeklyTokenLimit: tokenCount.nullable(),
    fiveHourRemainingTokens: tokenCount.nullable(),
    weeklyRemainingTokens: tokenCount.nullable(),
    measuredAt: z.iso.datetime({ offset: true }).nullable(),
  })
  .superRefine((member, context) => {
    for (const [limitKey, remainingKey] of [
      ["fiveHourTokenLimit", "fiveHourRemainingTokens"],
      ["weeklyTokenLimit", "weeklyRemainingTokens"],
    ] as const) {
      const limit = member[limitKey];
      const remaining = member[remainingKey];
      if (
        (limit === null) !== (remaining === null) ||
        (remaining !== null && limit !== null && remaining > limit)
      ) {
        context.addIssue({
          code: "custom",
          message: "成员个人剩余额度必须在已设置的个人限额内。",
          path: [remainingKey],
        });
      }
    }
  });

const quotaPolicySchema = z.object({
  fiveHourTokenLimit: tokenCount.nullable(),
  weeklyTokenLimit: tokenCount.nullable(),
  fiveHourSource: z.enum(["USER", "TEAM", "UNCONFIGURED"]),
  weeklySource: z.enum(["USER", "TEAM", "UNCONFIGURED"]),
});

const quotaUsageSchema = z.object({
  measurementStatus: z.literal("CLIENT_REPORTED"),
  fiveHourUsedTokens: tokenCount,
  weeklyUsedTokens: tokenCount,
  fiveHourRemainingTokens: tokenCount.nullable(),
  weeklyRemainingTokens: tokenCount.nullable(),
  fiveHourResetsAt: isoInstant.nullable(),
  weeklyResetsAt: isoInstant.nullable(),
  measuredAt: isoInstant.nullable(),
});

const usageStatusSchema = z.strictObject({
  state: z.enum([
    "NOT_INSTALLED",
    "OFFLINE",
    "BASELINE_READY",
    "UNPAIRED",
    "UP_TO_DATE",
    "REPORTED",
    "ERROR",
  ]),
  lastScanEpochSeconds: tokenCount.nullable(),
  lastReportEpochSeconds: tokenCount.nullable(),
  reportedEvents: tokenCount,
  warningCode: z
    .string()
    .regex(/^[A-Z_]{1,64}$/)
    .nullable()
    .optional(),
  deferredEvents: tokenCount.optional(),
  reviewSubmittedEvents: tokenCount.optional(),
  reviewErrorCode: z.string().regex(/^[A-Z_]{1,64}$/).nullable().optional(),
  errorCode: z
    .string()
    .regex(/^[A-Z_]{1,64}$/)
    .nullable(),
  version: z.string().min(1).max(64).nullable(),
});

const usageEventModelSchema = z
  .string()
  .regex(/^[A-Za-z0-9][A-Za-z0-9._:/-]{0,95}$/)
  .nullable()
  .catch(null)
  .default(null);
const usageEventReasoningEffortSchema = z
  .enum(["none", "minimal", "low", "medium", "high", "xhigh", "max", "ultra"])
  .nullable()
  .catch(null)
  .default(null);

const usageEventsSchema = z.object({
  items: z
    .array(
      z.object({
        id: z.string().regex(/^[a-zA-Z0-9-]{1,128}$/),
        occurredAt: z.iso.datetime({ offset: true }),
        receivedAt: z.iso.datetime({ offset: true }),
        totalTokens: tokenCount,
        model: usageEventModelSchema,
        reasoningEffort: usageEventReasoningEffortSchema,
        inputTokens: tokenCount.nullable().default(null),
        cachedInputTokens: tokenCount.nullable().default(null),
        cacheWriteInputTokens: tokenCount.nullable().default(null),
        outputTokens: tokenCount.nullable().default(null),
        reasoningOutputTokens: tokenCount.nullable().default(null),
        estimatedCostUsd: z
          .number()
          .finite()
          .nonnegative()
          .nullable()
          .default(null),
        serviceTier: z.string().max(24).nullable().optional(),
        billingMultiplier: z.number().finite().min(1).max(100).nullable().optional(),
        billingModeStatus: z.enum(['FAST','STANDARD','UNRECORDED']).nullable().optional(),
        billingOrigin: z.string().max(32).nullable().optional(),
        billingModeSource: z.string().max(32).nullable().optional(),
        costStatus: z
          .enum([
            "PRICED",
            "MISSING_PRICE",
            "MISSING_BREAKDOWN",
            "INVALID_BREAKDOWN",
            "PENDING_BILLING",
            "MISSING_SERVICE_TIER",
          ])
          .nullable()
          .default(null),
        source: z.enum([
          "TOKEN_USAGE_RECORD",
          "CUMULATIVE_TOKEN_COUNT_FALLBACK",
        ]),
      }),
    )
    .max(100),
  page: tokenCount,
  size: tokenCount.min(1).max(100),
  totalElements: tokenCount,
  totalPages: tokenCount,
});

const usageStatsSchema = z
  .object({
    eventCount: tokenCount,
    totalTokens: tokenCount,
    inputTokens: tokenCount.nullable(),
    cachedInputTokens: tokenCount.nullable(),
    cacheWriteInputTokens: tokenCount.nullable(),
    outputTokens: tokenCount.nullable(),
    reasoningOutputTokens: tokenCount.nullable(),
    breakdownEventCount: tokenCount,
    costedEventCount: tokenCount,
    estimatedCostUsd: z.number().finite().nonnegative().nullable(),
    cacheHitRatePercent: z.number().finite().min(0).max(100).nullable(),
  })
  .refine(
    (value) =>
      value.costedEventCount <= value.breakdownEventCount &&
      value.breakdownEventCount <= value.eventCount,
    "统计事件数不一致。",
  );
const usageAnalyticsSchema = z
  .object({
    from: z.iso.datetime({ offset: true }),
    to: z.iso.datetime({ offset: true }),
    currency: z.literal("USD"),
    timezone: z.literal("UTC"),
    granularity: z.enum(["HOUR", "DAY"]),
    summary: usageStatsSchema,
    trend: z
      .array(
        z.object({
          bucket: z.iso.datetime({ offset: true }),
          stats: usageStatsSchema,
        }),
      )
      .max(32),
  })
  .refine(
    (value) =>
      Date.parse(value.to) > Date.parse(value.from) &&
      Date.parse(value.to) - Date.parse(value.from) <= 30 * 86400000,
    "统计时间范围无效。",
  );

const meterRuntimeLogsSchema = z.object({
  state: z.enum(["READY", "NOT_AVAILABLE", "ADMIN_REQUIRED", "READ_FAILED"]),
  skippedLines: tokenCount,
  entries: z
    .array(
      z.object({
        atEpochSeconds: tokenCount.min(1),
        kind: z.enum([
          "SERVICE_STARTED",
          "SERVICE_STOPPED",
          "BASELINE_READY",
          "UNPAIRED",
          "USAGE_REPORTED",
          "HEARTBEAT",
          "ERROR",
          "STATUS_CHANGE",
          "GUARD_ACTIVE",
          "GUARD_INACTIVE",
          "GUARD_DETECTED",
          "GUARD_TERMINATED",
          "GUARD_ERROR",
        ]),
        state: z
          .enum([
            "BASELINE_READY",
            "UNPAIRED",
            "UP_TO_DATE",
            "REPORTED",
            "ERROR",
          ])
          .nullable(),
        reportedEvents: tokenCount,
        reportedTokens: tokenCount,
        heartbeatSent: z.boolean(),
        errorCode: z
          .string()
          .regex(/^[A-Z_]{1,64}$/)
          .nullable(),
        ccSwitchGuard: z
          .enum(["INACTIVE", "CLEAR", "TERMINATED", "ERROR"])
          .nullable(),
      }),
    )
    .max(50),
});

const officialCodexRateLimitWindowSchema = z.object({
  limitId: z.string().min(1),
  slot: z.string().min(1),
  windowDurationMins: z.number().finite().int().positive(),
  usedPercent: z.number().finite().min(0).max(100),
  resetsAt: isoInstant.nullable(),
});

/** Account-level reference data, explicitly separate from personal token quota. */
const officialCodexRateLimitsSchema = z.object({
  planType: z.string().min(1).nullable(),
  windows: z.array(officialCodexRateLimitWindowSchema),
  fetchedAt: isoInstant.nullable(),
  source: z.literal("SERVER").optional(),
  accountLabel: z.string().nullable().optional(),
  status: z.enum(["NOT_LINKED", "SUCCESS", "FETCHING", "PENDING", "AUTH_REQUIRED", "RETRY_WAIT", "DISABLED"]).optional(),
  enabled: z.boolean().optional(),
  intervalSeconds: z.number().int().min(60).max(86400).optional(),
  stale: z.boolean().optional(),
  nextAttemptAt: isoInstant.nullable().optional(),
});

/** The fixed central response for the currently signed-in product user. */
const quotaOverviewSchema = z
  .object({
    user: systemUserSchema,
    team: quotaTeamSchema.nullable(),
    teamQuota: teamQuotaSchema.nullable().optional(),
    // Older servers omit member usage. Preserve that absence instead of
    // presenting an empty team or inventing zero usage.
    teamMembers: z
      .array(teamMemberUsageSchema)
      .nullish()
      .transform((members) => members ?? null),
    policy: quotaPolicySchema,
    usage: quotaUsageSchema,
    billing: usdOverviewSchema,
    allowed: z.boolean(),
    blockReason: z.string().nullable(),
  })
  .superRefine((value, context) => {
    if (value.teamQuota && value.teamQuota.id !== value.team?.id) {
      context.addIssue({
        code: "custom",
        message: "团队额度归属不一致。",
        path: ["teamQuota"],
      });
    }
    const unconfiguredFiveHour = value.policy.fiveHourTokenLimit === null;
    const unconfiguredWeekly = value.policy.weeklyTokenLimit === null;
    if (
      unconfiguredFiveHour !==
        (value.policy.fiveHourSource === "UNCONFIGURED") ||
      unconfiguredWeekly !== (value.policy.weeklySource === "UNCONFIGURED")
    ) {
      context.addIssue({
        code: "custom",
        message: "Token 限额与来源状态不一致。",
        path: ["policy"],
      });
    }
    if (
      (unconfiguredFiveHour && value.usage.fiveHourRemainingTokens !== null) ||
      (unconfiguredWeekly && value.usage.weeklyRemainingTokens !== null)
    ) {
      context.addIssue({
        code: "custom",
        message: "个人未设限的窗口不能带有个人剩余额度。",
        path: ["usage"],
      });
    }
    const windows = [value.billing.personal.fiveHour, value.billing.personal.weekly,
      ...(value.billing.team ? [value.billing.team.fiveHour,value.billing.team.weekly,value.billing.team.total] : []),
      ...(value.billing.officialAccount ? [value.billing.officialAccount.quota.total] : [])];
    const exhausted = windows.some((window) => window.limitUsd !== null && compareUsd(window.usedUsd, window.limitUsd) >= 0);
    if (value.allowed !== value.billing.allowed || value.blockReason !== value.billing.blockReason || (value.allowed && exhausted)) {
      context.addIssue({
        code: "custom",
        message: "USD 额度与服务端的可用状态不一致。",
        path: ["allowed"],
      });
    }
  });

export type StartupGateResult = z.infer<typeof startupGateSchema>;
export type MeterVersion = z.infer<typeof meterVersionSchema>;
export type ServerConfig = z.infer<typeof serverConfigSchema>;
export type SessionVault = z.infer<typeof sessionVaultSchema>;
export type SystemUser = z.infer<typeof systemUserSchema>;
export type SystemSession = z.infer<typeof systemSessionSchema>;
export type SessionBinding = z.infer<typeof sessionBindingSchema>;
export type SessionBindingAttempt = z.infer<typeof sessionBindingAttemptSchema>;
export type SessionBindingCurrent = z.infer<typeof sessionBindingCurrentSchema>;
export type LoginAssistanceInfo = z.infer<typeof loginAssistanceInfoSchema>;
export type LoginAssistanceListener = z.infer<
  typeof loginAssistanceListenerSchema
>;
export type QuotaTeam = z.infer<typeof quotaTeamSchema>;
export type TeamMemberUsage = z.infer<typeof teamMemberUsageSchema>;
export type QuotaPolicy = z.infer<typeof quotaPolicySchema>;
export type QuotaUsage = z.infer<typeof quotaUsageSchema>;
export type QuotaOverview = z.infer<typeof quotaOverviewSchema>;
export type UsageStatus = z.infer<typeof usageStatusSchema>;
export type UsageEventPage = z.infer<typeof usageEventsSchema>;
export type UsageAnalytics = z.infer<typeof usageAnalyticsSchema>;
export type MeterRuntimeLogs = z.infer<typeof meterRuntimeLogsSchema>;
export type OfficialCodexRateLimits = z.infer<
  typeof officialCodexRateLimitsSchema
>;

const signedOutSession: SystemSession = { authenticated: false, user: null };
const authRequiredMessage = "请先登录系统账户。";
const deviceIdentityUnavailableMessage =
  "本设备绑定信息不可用，请检查统计程序设备登记或联系管理员。";
const unauthorizedError = () => new Error(authRequiredMessage);

type RequestController = {
  controller: AbortController;
  release: () => void;
};

type ActiveSession = {
  accessToken: string;
  accessExpiresAt: number;
  refreshToken: string;
  refreshExpiresAt: number;
  localExpiresAt: number;
  loginAtEpochSeconds: number;
  origin: string;
  user: SystemUser;
  epoch: number;
};

let sessionEpoch = 0;
let activeSession: ActiveSession | null = null;
let currentConfig: ServerConfig | null = null;
let identityPromise: Promise<z.infer<typeof deviceIdentitySchema>> | null =
  null;
let enrollmentAttemptEpoch = -1;
let enrollmentStateEpoch = -1;
let enrollmentPermanentFailureEpoch = -1;
let enrollmentRetryAt = 0;
let enrollmentFailureCount = 0;
let enrollmentError:
  "PAIRING_ENROLL_FAILED" | "PAIRING_IDENTITY_MISMATCH" | null = null;
let enrollmentFlight: { epoch: number; promise: Promise<void> } | null = null;
let refreshFlight: { epoch: number; promise: Promise<string> } | null = null;
let restoreFlight: { epoch: number; promise: Promise<SystemSession> } | null =
  null;
let vaultOperations = Promise.resolve();
let configRevision = 0;
let configMutationQueue = Promise.resolve();
let configMutationPromise: Promise<ServerConfig> | null = null;
let sessionExpiryTimer: number | undefined;
const activeControllers = new Set<AbortController>();
const localLoginLifetimeSeconds = 7 * 86400;
const maxSseBufferChars = 64 * 1024;
const maxSseFrameChars = 16 * 1024;

function serverOrigin(config: ServerConfig): string {
  const parsed = new URL(config.url);
  if (parsed.protocol !== "http:" && parsed.protocol !== "https:") {
    throw new Error("服务器地址须以 http:// 或 https:// 开头。");
  }
  return parsed.origin;
}

function assertEpoch(epoch: number) {
  if (epoch !== sessionEpoch) throw new axios.CanceledError("Session changed");
}

async function assertSessionLifetime(epoch: number) {
  assertEpoch(epoch);
  if (
    activeSession?.epoch === epoch &&
    activeSession.localExpiresAt <= Date.now()
  ) {
    const expiredEpoch = invalidateRendererSession();
    await clearVault(expiredEpoch);
    window.dispatchEvent(new Event("quota-guard:session-expired"));
    throw unauthorizedError();
  }
}

function invalidateRendererSession() {
  sessionEpoch += 1;
  window.clearTimeout(sessionExpiryTimer);
  sessionExpiryTimer = undefined;
  activeSession = null;
  refreshFlight = null;
  restoreFlight = null;
  for (const controller of activeControllers) controller.abort();
  activeControllers.clear();
  window.dispatchEvent(new Event("quota-guard:assistance-clear"));
  return sessionEpoch;
}

function enqueueVaultOperation<T>(operation: () => Promise<T>): Promise<T> {
  const result = vaultOperations.then(operation, operation);
  vaultOperations = result.then(
    () => undefined,
    () => undefined,
  );
  return result;
}

async function clearVault(epoch?: number) {
  await enqueueVaultOperation(async () => {
    if (epoch !== undefined && epoch !== sessionEpoch) return;
    await invoke("session_vault_clear");
  });
}

function createRequestController(
  externalSignal?: AbortSignal,
): RequestController {
  const controller = new AbortController();
  const abortFromExternal = () => controller.abort();
  if (externalSignal?.aborted) controller.abort();
  else
    externalSignal?.addEventListener("abort", abortFromExternal, {
      once: true,
    });
  activeControllers.add(controller);
  let released = false;
  return {
    controller,
    release: () => {
      if (released) return;
      released = true;
      externalSignal?.removeEventListener("abort", abortFromExternal);
      activeControllers.delete(controller);
    },
  };
}

async function readNativeConfig(): Promise<ServerConfig> {
  const payload: unknown = await invoke("server_config_get");
  return serverConfigSchema.parse(payload);
}

async function adoptServerConfig(
  config: ServerConfig,
  expectedRevision: number,
): Promise<ServerConfig> {
  if (expectedRevision !== configRevision) return currentConfig ?? config;
  const previousOrigin = currentConfig ? serverOrigin(currentConfig) : null;
  const nextOrigin = serverOrigin(config);
  if (previousOrigin !== null && previousOrigin !== nextOrigin) {
    const epoch = invalidateRendererSession();
    await clearVault(epoch);
  }
  if (expectedRevision !== configRevision) return currentConfig ?? config;
  currentConfig = config;
  return config;
}

async function getServerConfig(force = false): Promise<ServerConfig> {
  if (configMutationPromise) {
    await configMutationPromise.catch(() => undefined);
    if (currentConfig) return currentConfig;
  }
  if (!force && currentConfig) return currentConfig;
  const revision = configRevision;
  const config = await readNativeConfig();
  if (revision !== configRevision) {
    if (configMutationPromise) {
      await configMutationPromise.catch(() => undefined);
    }
    return currentConfig ?? config;
  }
  return adoptServerConfig(config, revision);
}

async function deviceIdentity() {
  identityPromise ??= (async () => {
    const payload: unknown = await invoke("device_identity");
    return deviceIdentitySchema.parse(payload);
  })();
  return identityPromise;
}

async function deviceHeaders(): Promise<Record<string, string>> {
  try {
    const identity = await deviceIdentity();
    return identity.deviceId
      ? { "X-Quota-Guard-Device-ID": identity.deviceId }
      : {};
  } catch {
    return {};
  }
}

function isUnauthorized(error: unknown): boolean {
  return axios.isAxiosError(error) && error.response?.status === 401;
}

function isBindingRequired(error: unknown): boolean {
  if (!axios.isAxiosError(error) || error.response?.status !== 403)
    return false;
  const data: unknown = error.response.data;
  return (
    typeof data === "object" &&
    data !== null &&
    "code" in data &&
    data.code === "SESSION_BINDING_REQUIRED"
  );
}

function rethrowSafeCentralError(error: unknown): never {
  if (
    axios.isAxiosError(error) &&
    error.response?.status === 403 &&
    error.response.data?.code === "LOGIN_ASSISTANCE_NOT_ALLOWED"
  ) {
    window.dispatchEvent(new Event(assistanceAccessRevokedEvent));
    throw new Error("登录辅助仅在取得本次绑定名额后开放，绑定完成后自动关闭。");
  }
  if (isBindingRequired(error)) {
    window.dispatchEvent(new Event(bindingGateEvent));
    throw new Error(bindingRequiredMessage);
  }
  throw error;
}

async function sendOnce(
  config: ServerConfig,
  request: AxiosRequestConfig,
  token: string | null,
  epoch: number,
  externalSignal?: AbortSignal,
  keepController = false,
): Promise<{
  data: unknown;
  release: () => void;
  contentType: string | null;
}> {
  assertEpoch(epoch);
  const tracked = createRequestController(externalSignal);
  try {
    const headers = {
      ...(request.headers as Record<string, string> | undefined),
      ...(await deviceHeaders()),
      ...(token ? { Authorization: `Bearer ${token}` } : {}),
    };
    const response = await axios.request<unknown>({
      ...request,
      baseURL: serverOrigin(config),
      headers,
      signal: tracked.controller.signal,
      timeout: request.timeout ?? 10_000,
    });
    assertEpoch(epoch);
    if (token) await assertSessionLifetime(epoch);
    if (!keepController) tracked.release();
    return {
      data: response.data,
      release: tracked.release,
      contentType:
        typeof (response.headers as Record<string, unknown>)["content-type"] ===
        "string"
          ? ((response.headers as Record<string, string>)["content-type"] ??
            null)
          : null,
    };
  } catch (error) {
    tracked.release();
    if (isBindingRequired(error)) rethrowSafeCentralError(error);
    throw error;
  }
}

async function loadVaultForOrigin(
  config: ServerConfig,
  epoch: number,
): Promise<SessionVault | null> {
  assertEpoch(epoch);
  const payload: unknown = await invoke("session_vault_load");
  assertEpoch(epoch);
  if (payload === null) return null;
  const vault = sessionVaultSchema.parse(payload);
  const now = Math.floor(Date.now() / 1000);
  if (
    vault.serverOrigin !== serverOrigin(config) ||
    vault.expiresAtEpochSeconds <= now ||
    vault.loginAtEpochSeconds + localLoginLifetimeSeconds <= now ||
    vault.expiresAtEpochSeconds - now > 365 * 86400
  ) {
    await clearVault(epoch);
    return null;
  }
  return vault;
}

async function persistAuthResponse(
  response: z.infer<typeof authResponseSchema>,
  config: ServerConfig,
  epoch: number,
  loginAtEpochSeconds: number,
): Promise<ActiveSession> {
  assertEpoch(epoch);
  const now = Math.floor(Date.now() / 1000);
  if (loginAtEpochSeconds + localLoginLifetimeSeconds <= now) {
    await clearVault(epoch);
    throw unauthorizedError();
  }
  const vault: SessionVault = {
    serverOrigin: serverOrigin(config),
    refreshToken: response.refreshToken,
    userId: response.user.id,
    loginAtEpochSeconds,
    expiresAtEpochSeconds: now + response.refreshExpiresIn,
  };
  await enqueueVaultOperation(async () => {
    assertEpoch(epoch);
    await invoke("session_vault_save", { session: vault });
  });
  assertEpoch(epoch);
  const session: ActiveSession = {
    accessToken: response.accessToken,
    accessExpiresAt: Date.now() + response.expiresIn * 1000,
    refreshToken: response.refreshToken,
    refreshExpiresAt: vault.expiresAtEpochSeconds * 1000,
    localExpiresAt: (loginAtEpochSeconds + localLoginLifetimeSeconds) * 1000,
    loginAtEpochSeconds,
    origin: vault.serverOrigin,
    user: response.user,
    epoch,
  };
  activeSession = session;
  window.clearTimeout(sessionExpiryTimer);
  sessionExpiryTimer = window.setTimeout(
    () => {
      if (
        activeSession?.epoch === epoch &&
        activeSession.localExpiresAt <= Date.now()
      ) {
        const expiredEpoch = invalidateRendererSession();
        void clearVault(expiredEpoch);
        window.dispatchEvent(new Event("quota-guard:session-expired"));
      }
    },
    Math.max(0, session.localExpiresAt - Date.now()),
  );
  return session;
}

async function refreshSession(
  config: ServerConfig,
  epoch: number,
): Promise<string> {
  if (refreshFlight?.epoch === epoch) return refreshFlight.promise;
  const promise = (async () => {
    assertEpoch(epoch);
    const existing = activeSession;
    const vault =
      existing?.epoch === epoch && existing.origin === serverOrigin(config)
        ? {
            serverOrigin: existing.origin,
            refreshToken: existing.refreshToken,
            userId: existing.user.id,
            loginAtEpochSeconds: existing.loginAtEpochSeconds,
            expiresAtEpochSeconds: Math.floor(existing.refreshExpiresAt / 1000),
          }
        : await loadVaultForOrigin(config, epoch);
    if (!vault) throw unauthorizedError();
    if (
      vault.loginAtEpochSeconds + localLoginLifetimeSeconds <=
      Math.floor(Date.now() / 1000)
    ) {
      const expiredEpoch = invalidateRendererSession();
      await clearVault(expiredEpoch);
      window.dispatchEvent(new Event("quota-guard:session-expired"));
      throw unauthorizedError();
    }
    try {
      const { data } = await sendOnce(
        config,
        {
          method: "POST",
          url: "/api/v1/auth/refresh",
          data: { refreshToken: vault.refreshToken },
        },
        null,
        epoch,
      );
      const response = authResponseSchema.parse(data);
      if (response.user.id !== vault.userId) throw unauthorizedError();
      const refreshed = await persistAuthResponse(
        response,
        config,
        epoch,
        vault.loginAtEpochSeconds,
      );
      return refreshed.accessToken;
    } catch (error) {
      if (isUnauthorized(error)) {
        activeSession = null;
        await clearVault(epoch);
        throw unauthorizedError();
      }
      throw error;
    }
  })();
  refreshFlight = { epoch, promise };
  try {
    return await promise;
  } finally {
    if (refreshFlight?.promise === promise) refreshFlight = null;
  }
}

async function ensureAccessToken(
  config: ServerConfig,
  epoch: number,
  forceRefresh = false,
): Promise<string> {
  assertEpoch(epoch);
  const session = activeSession;
  if (
    !forceRefresh &&
    session?.epoch === epoch &&
    session.origin === serverOrigin(config) &&
    session.localExpiresAt > Date.now() &&
    session.accessExpiresAt > Date.now() + 30_000
  ) {
    return session.accessToken;
  }
  return refreshSession(config, epoch);
}

async function refreshAfterUnauthorized(
  config: ServerConfig,
  epoch: number,
  usedToken: string,
): Promise<string> {
  const current = activeSession;
  if (current?.epoch === epoch && current.localExpiresAt <= Date.now()) {
    const expiredEpoch = invalidateRendererSession();
    await clearVault(expiredEpoch);
    window.dispatchEvent(new Event("quota-guard:session-expired"));
    throw unauthorizedError();
  }
  if (
    current?.epoch === epoch &&
    current.accessToken !== usedToken &&
    current.localExpiresAt > Date.now() &&
    current.accessExpiresAt > Date.now()
  ) {
    return current.accessToken;
  }
  return ensureAccessToken(config, epoch, true);
}

async function centralRequest(
  request: AxiosRequestConfig,
  requiresAuth = true,
  expectedEpoch = sessionEpoch,
  externalSignal?: AbortSignal,
): Promise<unknown> {
  const config = await getServerConfig();
  assertEpoch(expectedEpoch);
  const token = requiresAuth
    ? await ensureAccessToken(config, expectedEpoch)
    : null;
  try {
    return (
      await sendOnce(config, request, token, expectedEpoch, externalSignal)
    ).data;
  } catch (error) {
    if (requiresAuth && isUnauthorized(error)) {
      try {
        const refreshed = await refreshAfterUnauthorized(
          config,
          expectedEpoch,
          token!,
        );
        return (
          await sendOnce(
            config,
            request,
            refreshed,
            expectedEpoch,
            externalSignal,
          )
        ).data;
      } catch (retryError) {
        if (isUnauthorized(retryError)) {
          activeSession = null;
          await clearVault(expectedEpoch);
          throw unauthorizedError();
        }
        rethrowSafeCentralError(retryError);
      }
    }
    rethrowSafeCentralError(error);
  }
}

async function centralJson<T>(
  request: AxiosRequestConfig,
  schema: z.ZodType<T>,
  requiresAuth = true,
  externalSignal?: AbortSignal,
): Promise<T> {
  return schema.parse(
    await centralRequest(request, requiresAuth, sessionEpoch, externalSignal),
  );
}

async function enrollMeterForSession(
  config: ServerConfig,
  userId: string,
  epoch: number,
): Promise<void> {
  if (enrollmentStateEpoch !== epoch) {
    enrollmentStateEpoch = epoch;
    enrollmentAttemptEpoch = -1;
    enrollmentPermanentFailureEpoch = -1;
    enrollmentRetryAt = 0;
    enrollmentFailureCount = 0;
    enrollmentError = null;
  }
  if (enrollmentAttemptEpoch === epoch) return;
  if (
    enrollmentPermanentFailureEpoch === epoch ||
    Date.now() < enrollmentRetryAt
  )
    return;
  if (enrollmentFlight?.epoch === epoch) return enrollmentFlight.promise;
  const promise = (async () => {
    try {
      const identity = await deviceIdentity();
      assertEpoch(epoch);
      if (!identity.deviceId || identity.enrollmentId !== identity.deviceId) {
        enrollmentError = "PAIRING_IDENTITY_MISMATCH";
        enrollmentPermanentFailureEpoch = epoch;
        return;
      }
      const enrollment = z
        .strictObject({
          deviceId: z.string().uuid(),
          deviceToken: z.string().min(1).max(500),
          expiresAt: z.iso.datetime({ offset: true }),
          userId: z.string().uuid(),
        })
        .parse(
          await centralRequest(
            {
              method: "POST",
              url: "/api/v1/client/meter/enroll",
              data: { deviceId: identity.deviceId },
            },
            true,
            epoch,
          ),
        );
      assertEpoch(epoch);
      if (
        enrollment.userId !== userId ||
        enrollment.deviceId !== identity.deviceId
      ) {
        enrollmentError = "PAIRING_IDENTITY_MISMATCH";
        enrollmentPermanentFailureEpoch = epoch;
        return;
      }
      await invoke("meter_pairing_write", {
        pairing: {
          deviceId: enrollment.deviceId,
          deviceToken: enrollment.deviceToken,
          userId: enrollment.userId,
          serverOrigin: serverOrigin(config),
        },
      });
      assertEpoch(epoch);
      enrollmentAttemptEpoch = epoch;
      enrollmentRetryAt = 0;
      enrollmentFailureCount = 0;
      enrollmentError = null;
    } catch {
      if (epoch !== sessionEpoch) return;
      enrollmentError = "PAIRING_ENROLL_FAILED";
      const delay = Math.min(
        5 * 60_000,
        5_000 * 2 ** Math.min(enrollmentFailureCount, 6),
      );
      enrollmentFailureCount += 1;
      enrollmentRetryAt = Date.now() + delay;
    }
  })();
  enrollmentFlight = { epoch, promise };
  try {
    await promise;
  } finally {
    if (enrollmentFlight?.promise === promise) enrollmentFlight = null;
  }
}

async function restoreSystemSession(): Promise<SystemSession> {
  const config = await getServerConfig();
  const epoch = sessionEpoch;
  const data = await centralJson(
    { method: "GET", url: "/api/v1/users/me" },
    systemUserSchema,
  );
  assertEpoch(epoch);
  if (activeSession?.user.id !== data.id) {
    activeSession = null;
    await clearVault(epoch);
    return signedOutSession;
  }
  await enrollMeterForSession(config, data.id, epoch);
  return { authenticated: true, user: data };
}

async function systemSession(): Promise<SystemSession> {
  const epoch = sessionEpoch;
  if (restoreFlight?.epoch === epoch) return restoreFlight.promise;
  const promise = (async () => {
    try {
      return await restoreSystemSession();
    } catch (error) {
      if (isAuthRequiredError(error)) return signedOutSession;
      throw error;
    }
  })();
  restoreFlight = { epoch, promise };
  try {
    return await promise;
  } finally {
    if (restoreFlight?.promise === promise) restoreFlight = null;
  }
}

function isAuthRequiredError(error: unknown): boolean {
  return error instanceof Error && error.message === authRequiredMessage;
}

async function login(email: string, password: string): Promise<SystemSession> {
  const config = await getServerConfig();
  const epoch = invalidateRendererSession();
  await clearVault(epoch);
  let response: z.infer<typeof authResponseSchema>;
  try {
    response = await centralJson(
      {
        method: "POST",
        url: "/api/v1/auth/login",
        data: { email, password },
      },
      authResponseSchema,
      false,
    );
  } catch (error) {
    if (isUnauthorized(error)) {
      throw new Error("邮箱或密码不正确，请检查后重试。");
    }
    throw error;
  }
  const session = await persistAuthResponse(
    response,
    config,
    epoch,
    Math.floor(Date.now() / 1000),
  );
  await enrollMeterForSession(config, session.user.id, epoch);
  return { authenticated: true, user: session.user };
}

async function logout(): Promise<void> {
  const oldSession = activeSession;
  const epoch = invalidateRendererSession();
  const clearPromise = clearVault(epoch);
  const config = currentConfig ?? (await getServerConfig());
  let remoteError: unknown;
  if (oldSession) {
    try {
      let token = oldSession.accessToken;
      let refreshToken = oldSession.refreshToken;
      let response: { data: unknown };
      try {
        response = await sendOnce(
          config,
          {
            method: "POST",
            url: "/api/v1/auth/logout",
            data: { refreshToken },
          },
          token,
          epoch,
        );
      } catch (error) {
        if (!isUnauthorized(error)) throw error;
        const renewed = authResponseSchema.parse(
          await centralRequest(
            {
              method: "POST",
              url: "/api/v1/auth/refresh",
              data: { refreshToken },
            },
            false,
            epoch,
          ),
        );
        token = renewed.accessToken;
        refreshToken = renewed.refreshToken;
        response = await sendOnce(
          config,
          {
            method: "POST",
            url: "/api/v1/auth/logout",
            data: { refreshToken },
          },
          token,
          epoch,
        );
      }
      void response;
    } catch (error) {
      remoteError = error;
    }
  }
  await clearPromise;
  if (remoteError) rethrowSafeCentralError(remoteError);
}

async function currentDeviceIdentity() {
  const identity = await deviceIdentity();
  if (!identity.deviceId || !identity.enrollmentId) {
    throw new Error(deviceIdentityUnavailableMessage);
  }
  return identity;
}

export type AssistanceEvent = "connected" | "state_changed" | "disconnected";

async function streamLoginAssistanceEvents(
  handler: (event: AssistanceEvent) => void,
  signal: AbortSignal,
): Promise<void> {
  const config = await getServerConfig();
  const epoch = sessionEpoch;
  let token = await ensureAccessToken(config, epoch);
  let response: {
    data: unknown;
    release: () => void;
    contentType: string | null;
  };
  const request: AxiosRequestConfig = {
    method: "GET",
    url: "/api/v1/client/login-assistance/events",
    adapter: "fetch",
    responseType: "stream",
    timeout: 0,
    headers: {
      Accept: "text/event-stream",
    },
  };
  try {
    response = await sendOnce(config, request, token, epoch, signal, true);
  } catch (error) {
    if (!isUnauthorized(error)) rethrowSafeCentralError(error);
    token = await refreshAfterUnauthorized(config, epoch, token);
    response = await sendOnce(config, request, token, epoch, signal, true);
  }
  const stream = response.data instanceof ReadableStream ? response.data : null;
  let reader: ReadableStreamDefaultReader<Uint8Array> | null = null;
  try {
    if (
      response.contentType?.split(";", 1)[0].trim().toLowerCase() !==
      "text/event-stream"
    ) {
      throw new Error("中央后台事件流格式无效。");
    }
    if (!stream) {
      throw new Error("中央后台事件流格式无效。");
    }
    reader = stream.getReader();
    const decoder = new TextDecoder();
    let buffer = "";
    while (!signal.aborted) {
      await assertSessionLifetime(epoch);
      const { done, value } = await reader.read();
      if (done) break;
      buffer += decoder.decode(value, { stream: true });
      if (buffer.length > maxSseBufferChars) {
        throw new Error("中央后台事件流数据超出限制。");
      }
      const frames = buffer.split(/\r?\n\r?\n/);
      buffer = frames.pop() ?? "";
      for (const frame of frames) {
        await assertSessionLifetime(epoch);
        if (frame.length > maxSseFrameChars) {
          throw new Error("中央后台事件超出限制。");
        }
        const eventName = frame
          .split(/\r?\n/)
          .map((line) => line.match(/^event:\s*(.+)$/)?.[1]?.trim())
          .find(Boolean);
        if (eventName === "connected" || eventName === "state_changed") {
          await assertSessionLifetime(epoch);
          handler(eventName);
        }
      }
    }
  } finally {
    if (reader) await reader.cancel().catch(() => undefined);
    else if (stream) await stream.cancel().catch(() => undefined);
    response.release();
  }
}

async function saveServerConfigNow(
  config: ServerConfig,
): Promise<ServerConfig> {
  const normalized = serverConfigSchema.parse(config);
  const previous = currentConfig ?? (await readNativeConfig());
  const revision = ++configRevision;
  if (serverOrigin(previous) !== serverOrigin(normalized)) {
    const epoch = invalidateRendererSession();
    await clearVault(epoch);
  }
  const payload: unknown = await invoke("server_config_save", {
    config: { url: normalized.url },
  });
  const saved = serverConfigSchema.parse(payload);
  if (revision !== configRevision) return currentConfig ?? saved;
  currentConfig = saved;
  window.dispatchEvent(new Event("quota-guard:server-changed"));
  return saved;
}

function saveServerConfig(config: ServerConfig): Promise<ServerConfig> {
  const normalized = serverConfigSchema.parse(config);
  const operation = configMutationQueue.then(() =>
    saveServerConfigNow(normalized),
  );
  configMutationQueue = operation.then(
    () => undefined,
    () => undefined,
  );
  configMutationPromise = operation;
  void operation.then(
    () => {
      if (configMutationPromise === operation) configMutationPromise = null;
    },
    () => {
      if (configMutationPromise === operation) configMutationPromise = null;
    },
  );
  return operation;
}

async function invokeValidated<T>(
  command: string,
  schema: z.ZodType<T>,
  args?: Record<string, unknown>,
): Promise<T> {
  try {
    const payload: unknown =
      args === undefined ? await invoke(command) : await invoke(command, args);
    return schema.parse(payload);
  } catch (error) {
    throw error;
  }
}

export const api = {
  autostartStatus: () => invokeValidated("autostart_status", autostartSchema),
  setAutostart: (enabled: boolean) =>
    invokeValidated("autostart_set", autostartSchema, { enabled }),
  launchPreferences: () => invokeValidated("launch_preferences_get", launchPreferencesSchema),
  setSilentStartup: (silentStartup: boolean) =>
    invokeValidated("launch_preferences_set", launchPreferencesSchema, { silentStartup }),
  desktopUpdateCheck: async (signal?: AbortSignal) => {
    const local = desktopVersionSchema.parse(await invoke("desktop_update_info"));
    const config = await getServerConfig(true);
    const origin = serverOrigin(config);
    const response = await axios.get<unknown>(`${origin}/api/v1/desktop-updates/info/${local.target}/${local.arch}/${local.currentVersion}`, {
      signal, timeout: 20_000, withCredentials: false, headers: { Accept: "application/json" },
    });
    const remote = desktopUpdateSchema.parse(response.data);
    if (origin !== serverOrigin(await getServerConfig(true))) throw new Error("服务器地址已变化，请重新检查更新。");
    if (remote.currentVersion !== local.currentVersion) throw new Error("更新信息与当前客户端版本不匹配。");
    return { ...remote, ...local, origin };
  },
  meterUpdateCheck: async (currentVersion: string) => {
    if (!/^\d+\.\d+\.\d+$/.test(currentVersion)) throw new Error("统计程序版本号无效。");
    const local = desktopVersionSchema.parse(await invoke("desktop_update_info"));
    if (local.target !== "windows") return null;
    const config = await getServerConfig(true); const origin = serverOrigin(config);
    const response = await axios.get<unknown>(`${origin}/api/v1/meter-updates/info/windows/${local.arch}/${currentVersion}`, { timeout: 20_000, withCredentials: false });
    if (origin !== serverOrigin(await getServerConfig(true))) throw new Error("服务器地址已变化，请重新检查更新。");
    return desktopUpdateSchema.extend({ mandatory: z.literal(true) }).parse(response.data);
  },
  startupGateCheck: () =>
    invokeValidated("startup_gate_check", startupGateSchema),
  startupGuardStatus: () =>
    invokeValidated("startup_guard_status", startupGateSchema),
  meterServiceVersion: () =>
    invokeValidated("meter_service_version", meterVersionSchema),
  meterServiceDiagnose: () =>
    invokeValidated("meter_service_diagnose", meterDiagnosticsSchema),
  meterServiceRepair: () =>
    invokeValidated("meter_service_repair", meterDiagnosticsSchema),
  meterStatus: () => invokeValidated("meter_status", usageStatusSchema),
  usageStatus: () => invokeValidated("meter_status", usageStatusSchema),
  meterPairingError: () => enrollmentError,
  serverConfig: () => getServerConfig(true),
  saveServerConfig,
  deviceIdentity,
  systemSession,
  clientAccess: () =>
    centralJson(
      { method: "GET", url: "/api/v1/client/access" },
      clientAccessSchema,
    ),
  systemLogin: login,
  systemLogout: logout,
  sessionBindingCurrent: async () => {
    const identity = await currentDeviceIdentity();
    return centralJson(
      {
        method: "GET",
        url: "/api/v1/client/session-bindings/current",
        params: {
          deviceId: identity.deviceId,
          enrollmentId: identity.enrollmentId,
        },
        headers: { "X-CQG-Queue-Version": "2" },
      },
      sessionBindingCurrentSchema.refine(({ attempt, binding }) => {
        const owns = (value: SessionBinding | null) =>
          value === null ||
          (value.deviceId === identity.deviceId &&
            value.enrollmentId === identity.enrollmentId);
        return (
          (!attempt ||
            (attempt.deviceId === identity.deviceId &&
              attempt.enrollmentId === identity.enrollmentId)) &&
          owns(binding) &&
          owns(attempt?.binding ?? null)
        );
      }),
    );
  },
  sessionBindingStart: async () => {
    const identity = await currentDeviceIdentity();
    return centralJson(
      {
        method: "POST",
        url: "/api/v1/client/session-bindings/attempts",
        data: {
          deviceId: identity.deviceId,
          enrollmentId: identity.enrollmentId,
        },
        headers: { "X-CQG-Queue-Version": "2" },
      },
      sessionBindingAttemptSchema.refine(
        (attempt) =>
          attempt.deviceId === identity.deviceId &&
          attempt.enrollmentId === identity.enrollmentId,
      ),
    );
  },
  sessionBindingAction: async (
    attemptId: string,
    action: "status" | "prepare" | "detect" | "confirm" | "cancel",
  ) => {
    z.string().uuid().parse(attemptId);
    z.enum(["status", "prepare", "detect", "confirm", "cancel"]).parse(action);
    const identity = await currentDeviceIdentity();
    let body: { sessionId: string } | undefined;
    if (action !== "status") {
      const previous = await centralJson(
        {
          method: "GET",
          url: `/api/v1/client/session-bindings/attempts/${attemptId}`,
          headers: { "X-CQG-Queue-Version": "2" },
        },
        sessionBindingAttemptSchema.refine(
          (result) =>
            result.id === attemptId &&
            result.deviceId === identity.deviceId &&
            result.enrollmentId === identity.enrollmentId,
        ),
      );
      if (
        action === "detect" &&
        (previous.status !== "READY_FOR_LOGIN" || !previous.loginAllowed)
      ) {
        throw new Error("绑定任务当前不允许检测，请重新读取状态。");
      }
      if (action === "confirm") {
        if (
          previous.status !== "AWAITING_CONFIRMATION" ||
          !previous.candidateSessionId
        ) {
          throw new Error("绑定确认信息无效，请重新读取状态。");
        }
        body = { sessionId: previous.candidateSessionId };
      }
    }
    return centralJson(
      {
        method: action === "status" ? "GET" : "POST",
        url: `/api/v1/client/session-bindings/attempts/${attemptId}${action === "status" ? "" : `/${action}`}`,
        data: body,
        headers: { "X-CQG-Queue-Version": "2" },
      },
      sessionBindingAttemptSchema.refine(
        (result) =>
          result.id === attemptId &&
          result.deviceId === identity.deviceId &&
          result.enrollmentId === identity.enrollmentId,
      ),
    );
  },
  loginAssistanceInfo: () =>
    centralJson(
      { method: "GET", url: "/api/v1/client/login-assistance" },
      loginAssistanceInfoSchema,
    ),
  loginAssistanceContext: (signal?: AbortSignal) =>
    centralJson(
      { method: "GET", url: "/api/v1/client/login-assistance/context" },
      loginAssistanceContextSchema,
      true,
      signal,
    ),
  authenticatorInfo: (signal?: AbortSignal) =>
    centralJson(
      { method: "GET", url: "/api/v1/client/login-assistance/authenticator" },
      authenticatorInfoSchema,
      true,
      signal,
    ),
  authenticatorCode: (signal?: AbortSignal) =>
    centralJson(
      {
        method: "POST",
        url: "/api/v1/client/login-assistance/authenticator/code",
      },
      authenticatorCodeSchema,
      true,
      signal,
    ),
  loginAssistanceCurrent: () =>
    centralJson(
      {
        method: "GET",
        url: "/api/v1/client/login-assistance/listeners/current",
      },
      z.strictObject({ listener: loginAssistanceListenerSchema.nullable() }),
    ),
  loginAssistanceStart: () =>
    centralJson(
      { method: "POST", url: "/api/v1/client/login-assistance/listeners" },
      loginAssistanceListenerSchema,
    ),
  loginAssistanceStatus: async (listenerId: string) => {
    z.string().uuid().parse(listenerId);
    return centralJson(
      {
        method: "GET",
        url: `/api/v1/client/login-assistance/listeners/${listenerId}`,
      },
      loginAssistanceListenerSchema.refine(
        (listener) => listener.id === listenerId,
      ),
    );
  },
  loginAssistanceCancel: async (listenerId: string) => {
    z.string().uuid().parse(listenerId);
    return centralJson(
      {
        method: "DELETE",
        url: `/api/v1/client/login-assistance/listeners/${listenerId}`,
      },
      loginAssistanceListenerSchema.refine(
        (listener) => listener.id === listenerId,
      ),
    );
  },
  streamLoginAssistanceEvents,
  quotaOverview: () =>
    centralJson(
      {
        method: "GET",
        url: "/api/v1/client/quota/overview",
        params: { includeTeamMembers: true },
      },
      quotaOverviewSchema,
    ),
  usageEvents: async (page = 0, size = 10) => {
    z.number().int().min(0).max(100000).parse(page);
    z.number().int().min(1).max(100).parse(size);
    return centralJson(
      {
        method: "GET",
        url: "/api/v1/client/usage/events",
        params: {
          page,
          size,
          includeMetadata: true,
          includeTokenBreakdown: true,
        },
      },
      usageEventsSchema,
    );
  },
  usageAnalytics: async (days: 1 | 7 | 30 = 7) => {
    z.union([z.literal(1), z.literal(7), z.literal(30)]).parse(days);
    return centralJson(
      {
        method: "GET",
        url: "/api/v1/client/usage/analytics",
        params: { days },
      },
      usageAnalyticsSchema,
    );
  },
  meterRuntimeLogs: () =>
    invokeValidated("meter_runtime_logs", meterRuntimeLogsSchema),
  codexRateLimits: () => centralJson(
    { method: "GET", url: "/api/v1/client/official-quota" },
    officialCodexRateLimitsSchema,
  ),
};

const safeBackendMessages = new Set([
  "无法读取静默启动设置，请检查本机客户端设置目录。",
  "无法保存静默启动设置，请检查本机客户端设置目录权限。",
  "无法读取静默启动设置。", "无法保存静默启动设置。",
  "服务器地址已变化，请重新检查更新。", "更新信息与当前客户端版本不匹配。",
  "更新提醒记录无法读取。", "更新提醒目录不可用。", "更新提醒目录不可写。", "更新提醒保存失败。", "更新提醒暂不可用。",
  "系统未能显示更新通知，请在设置中查看更新。",
  "更新版本号无效。", "客户端版本号无效。", "该版本不高于当前版本，请重新检查更新。",
  "已有更新正在进行。", "更新地址无效。", "无法配置更新检查。", "无法初始化更新程序。",
  "无法获取更新信息，请检查网络或稍后重试。", "该更新已撤回或当前已是最新版本，请重新检查。",
  "后台版本信息已变化，请重新检查后更新。", "更新包地址无效，请联系管理员重新发布。", "更新包地址无效。",
  "更新包大小信息无效。", "更新包缺少校验信息。", "无法创建更新下载。", "下载连接失败，请检查网络。",
  "下载请求未成功或更新包已变化，请重新检查。", "下载中断，请重试。", "下载超过预期大小，已停止更新。",
  "内存不足，请关闭其他程序后重试。", "更新包大小不符，请重新下载。", "更新包校验失败，请重新下载。",
  "更新包签名或版本校验失败，已停止更新。", "无法启动更新程序，请重试或手动安装。",
  "更新程序未能启动，已恢复主窗口。请重新启动客户端后重试。",
  "登录辅助仅在取得本次绑定名额后开放，绑定完成后自动关闭。",
  bindingRequiredMessage,
  authRequiredMessage,
  deviceIdentityUnavailableMessage,
  "绑定任务无效或已失效，请重新读取状态。",
  "本设备绑定请求被拒绝，请联系管理员检查设备归属、官方账户与队列状态。",
  "系统会话有效期超出允许范围。",
  "绑定确认信息无效，请重新读取状态。",
  "服务器地址须以 http:// 或 https:// 开头。",
  "流水分页参数无效，请刷新后重试。",
  "统计范围须为 1、7 或 30 天。",
  "邮箱或密码不正确，请检查后重试。",
  "请先登录系统账户。",
  "中央后台拒绝请求，请检查请求内容后重试。",
  "该账户已有用户正在监听，请稍后重试。",
  "操作过于频繁，请稍后重试。",
  "无法使用登录辅助，请检查团队与邮箱配置后重试。",
  "监听任务编号无效，请重新开始监听。",
  "请输入完整的服务器地址，例如 http://192.168.0.102:8080。",
  "服务器地址缺少主机名。",
  "服务器地址只能填写协议、主机和端口。",
  "服务器地址须以 http:// 或 https:// 开头。",
  "无法读取客户端设置目录。",
  "无法保存服务器设置，请检查目录权限。",
  "无法保存服务器设置，请稍后重试。",
  "已取消管理员授权，服务器地址未保存。",
  "无法启动统计程序地址更新，请检查管理员授权。",
  "统计程序地址更新超时，服务器地址未保存。",
  "统计程序地址更新失败，服务器地址未保存。",
  "统计程序地址更新脚本未随安装包提供。",
  "无法保存服务器设置，请检查目录权限后重试。",
  "无法同步统计程序地址，请重启客户端后重试。",
  "无法保存服务器地址，请重试。",
  "无法读取开机自启动状态，请检查 Windows 任务计划程序。",
  "无法读取 Windows 开机自启动状态，请检查当前用户的启动应用设置。",
  "Windows 启动应用的审批数据无法识别，已保留原设置，请在任务管理器中检查。",
  "开机自启动项与当前安装目录不匹配，已阻止自动修改，请检查旧版本残留启动项。",
  "客户端安装路径无法用于 Windows 启动应用，请安装到较短的本地路径后重试。",
  "开机自启动迁移未完成且恢复失败，请在任务管理器和任务计划程序中检查本软件的启动项。",
  "普通启动项已保存，但无法确认旧启动任务已清理，请重新启动客户端后重试。",
  "Windows 启动应用设置在操作期间发生变化，已停止修改，请重新读取后重试。",
  "无法设置开机自启动，请以管理员身份运行客户端后重试。",
  "无法设置开机自启动，请检查当前 Windows 用户的启动应用权限。",
  "Windows 未保存开机自启动设置，请重试。",
  "开机自启动仅支持当前登录的管理员用户；请勿使用其他账户的管理员凭据设置。",
  "开机自启动仅支持当前登录的 Windows 用户。",
  "无法读取客户端安装路径，请检查安装文件。",
  "检测到其他安装版本或不匹配的自启动任务，请先卸载原版本。",
  "开机自启动任务与当前用户或安装目录不匹配，已阻止自动修改，请检查旧版本残留任务。",
  "当前系统暂不支持开机自启动设置。",
  "无法读取开机自启动状态，请稍后重试。",
  "无法设置开机自启动，请稍后重试。",
  "无法保存开机自启动安装偏好，请检查 Windows 用户设置权限。",
]);

/** Never render an arbitrary transport error, URL, or credential-like value. */
export function getApiErrorMessage(error: unknown): string {
  if (error instanceof z.ZodError) {
    return "服务返回的数据格式不完整，请重试或联系管理员。";
  }
  if (axios.isCancel(error)) return "请求已取消。";
  if (axios.isAxiosError(error)) {
    const status = error.response?.status;
    const data: unknown = error.response?.data;
    const code =
      data && typeof data === "object" && "code" in data ? data.code : null;
    if (code === "AUTHENTICATOR_UNAVAILABLE")
      return "团队验证器不可用，请重新读取配置或联系管理员。";
    if (
      code === "AUTHENTICATOR_SECRET_UNAVAILABLE" ||
      code === "AUTHENTICATOR_ENCRYPTION_UNAVAILABLE"
    )
      return "后台验证器密钥不可用，请联系管理员重新配置。";
    if (code === "AUTHENTICATOR_RATE_LIMITED")
      return "请稍后再获取验证器验证码。";
    if (status === 400) return "请求内容无效，请刷新后重试。";
    if (status === 401) return authRequiredMessage;
    if (status === 403) return "中央后台拒绝请求，请检查本设备绑定状态。";
    if (status === 404) return "中央后台不支持此功能，请确认服务器版本。";
    if (status === 409) return "该账户当前已有任务正在处理，请稍后重试。";
    if (status === 429) return "操作过于频繁，请稍后重试。";
    if (status && status >= 500) return "中央后台暂不可用，请稍后重试。";
    return "无法连接中央服务器，请检查地址、网络及服务器是否允许桌面访问。";
  }
  const message =
    typeof error === "string"
      ? error
      : error instanceof Error
        ? error.message
        : null;
  if (message?.startsWith("服务器地址未保存。")) {
    if (message.includes("已取消管理员授权")) {
      return "已取消管理员授权，服务器地址未保存。";
    }
    if (message.includes("统计程序地址更新超时")) {
      return "统计程序地址更新超时，服务器地址未保存。";
    }
    if (message.includes("统计程序地址更新失败")) {
      return "统计程序地址更新失败，服务器地址未保存。";
    }
    if (message.includes("无法启动统计程序地址更新")) {
      return "无法启动统计程序地址更新，请检查管理员授权。";
    }
    if (message.includes("无法读取 Windows 统计服务状态")) {
      return "无法读取 Windows 统计服务状态，请稍后重试。";
    }
    if (message.includes("脚本未随安装包提供")) {
      return "安装包缺少统计程序地址更新脚本，请重新运行完整安装器。";
    }
    return "服务器地址未保存，请检查统计服务配置后重试。";
  }
  if (
    message !== null &&
    [
      "无法安全保存登录状态",
      "无法安全读取登录状态",
      "无法安全保存登录状态。",
      "无法读取本机登录状态。",
      "无法访问安全登录状态目录。",
      "无法安全访问登录状态目录。",
      "无法创建登录状态目录。",
      "无法保存登录状态。",
      "无法保存本机登录状态。",
      "无法提交本机登录状态。",
      "无法清除本机登录状态。",
    ].includes(message)
  ) {
    return "本机加密登录存储不可用，请检查用户目录权限后重试。";
  }
  if (message !== null && safeBackendMessages.has(message.trim())) {
    return message.trim();
  }
  return "无法连接中央服务器，请检查地址、网络及服务器是否允许桌面访问。";
}

export const schemas = {
  systemSession: systemSessionSchema,
  quotaOverview: quotaOverviewSchema,
  usageStatus: usageStatusSchema,
  officialCodexRateLimits: officialCodexRateLimitsSchema,
};
