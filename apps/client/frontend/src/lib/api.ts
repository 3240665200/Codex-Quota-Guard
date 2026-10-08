import { invoke } from "@tauri-apps/api/core";
import { z } from "zod";

const isoInstant = z.string().min(1);
const tokenCount = z
  .number()
  .finite()
  .int()
  .min(0)
  .max(Number.MAX_SAFE_INTEGER);

const statusSchema = z.object({
  service: z.string(),
  state: z.string(),
});

const meterStartSchema = z.enum([
  "RUNNING",
  "STARTED",
  "INSTALLED",
  "NOT_INSTALLED",
  "NEEDS_ADMIN",
  "SERVER_UNAVAILABLE",
  "INSTALL_FAILED",
  "FAILED",
  "UNSUPPORTED",
]);

const serverConfigSchema = z.object({
  url: z.string().url(),
});

const systemUserSchema = z.object({
  id: z.string().min(1),
  email: z.string().email(),
  displayName: z.string().min(1),
  role: z.string().min(1),
  status: z.string().min(1),
});

/**
 * The local bridge returns this safe projection only. Access and refresh
 * tokens stay inside the bridge/backend process and are never sent to the
 * renderer.
 */
const systemSessionSchema = z.object({
  authenticated: z.boolean(),
  user: systemUserSchema.nullable(),
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

const usageStatusSchema = z.object({
  fallback: z.object({
    state: z.enum([
      "SIGNED_OUT",
      "WAITING_FOR_SESSIONS",
      "BASELINE_READY",
      "UP_TO_DATE",
      "REPORTED",
      "REPORT_FAILED",
      "SCAN_FAILED",
    ]),
    lastScannedAt: isoInstant.nullable(),
    lastReportedAt: isoInstant.nullable(),
    lastReportedEvents: tokenCount,
    issues: z.array(z.string().min(1)),
  }),
  service: z.object({
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
    errorCode: z.string().nullable(),
  }),
  pairingState: z.enum(["NOT_INSTALLED", "PAIRING_SENT", "PAIRING_FAILED"]),
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
});

/** The fixed central response for the currently signed-in product user. */
const quotaOverviewSchema = z
  .object({
    user: systemUserSchema,
    team: quotaTeamSchema.nullable(),
    teamQuota: teamQuotaSchema.nullable().optional(),
    policy: quotaPolicySchema,
    usage: quotaUsageSchema,
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
    const personalExhausted =
      (value.usage.fiveHourRemainingTokens !== null &&
        value.usage.fiveHourRemainingTokens <= 0) ||
      (value.usage.weeklyRemainingTokens !== null &&
        value.usage.weeklyRemainingTokens <= 0);
    const teamExhausted =
      value.teamQuota &&
      [
        value.teamQuota.fiveHourRemainingTokens,
        value.teamQuota.weeklyRemainingTokens,
        value.teamQuota.totalRemainingTokens,
      ].some((remaining) => remaining !== null && remaining <= 0);
    if (
      value.allowed &&
      (personalExhausted ||
        teamExhausted ||
        (value.teamQuota
          ? value.teamQuota.weeklyTokenLimit === null
          : unconfiguredWeekly))
    ) {
      context.addIssue({
        code: "custom",
        message: "可用状态必须具有尚有余量的必需团队或个人额度。",
        path: ["allowed"],
      });
    }
  });

export type StatusResponse = z.infer<typeof statusSchema>;
export type MeterStartOutcome = z.infer<typeof meterStartSchema>;
export type ServerConfig = z.infer<typeof serverConfigSchema>;
export type SystemUser = z.infer<typeof systemUserSchema>;
export type SystemSession = z.infer<typeof systemSessionSchema>;
export type QuotaTeam = z.infer<typeof quotaTeamSchema>;
export type QuotaPolicy = z.infer<typeof quotaPolicySchema>;
export type QuotaUsage = z.infer<typeof quotaUsageSchema>;
export type QuotaOverview = z.infer<typeof quotaOverviewSchema>;
export type UsageStatus = z.infer<typeof usageStatusSchema>;
export type OfficialCodexRateLimits = z.infer<
  typeof officialCodexRateLimitsSchema
>;

// StrictMode and a fast renderer remount must not trigger duplicate UAC prompts.
// An explicit dashboard retry uses a separate native command.
let meterStartupPromise: Promise<MeterStartOutcome> | null = null;

async function invokeValidated<T>(
  command: string,
  schema: z.ZodType<T>,
  args?: Record<string, unknown>,
): Promise<T> {
  const payload: unknown =
    args === undefined ? await invoke(command) : await invoke(command, args);
  return schema.parse(payload);
}

export const api = {
  ensureMeterService: () => {
    meterStartupPromise ??= invokeValidated(
      "meter_service_ensure",
      meterStartSchema,
    );
    return meterStartupPromise;
  },
  retryMeterService: () => {
    meterStartupPromise = invokeValidated(
      "meter_service_retry",
      meterStartSchema,
    );
    return meterStartupPromise;
  },
  status: () => invokeValidated("status", statusSchema),
  serverConfig: () => invokeValidated("server_config_get", serverConfigSchema),
  saveServerConfig: (config: ServerConfig) =>
    invokeValidated("server_config_save", serverConfigSchema, { config }),
  systemSession: () => invokeValidated("system_current", systemSessionSchema),
  systemLogin: (email: string, password: string) =>
    invokeValidated("system_login", systemSessionSchema, { email, password }),
  systemLogout: async () => {
    await invoke("system_logout");
  },
  quotaOverview: () => invokeValidated("quota_overview", quotaOverviewSchema),
  usageStatus: () => invokeValidated("usage_status", usageStatusSchema),
  scanUsage: () => invokeValidated("usage_scan", usageStatusSchema),
  pairMeter: () => invokeValidated("usage_pair", usageStatusSchema),
  codexRateLimits: () =>
    invokeValidated("codex_rate_limits", officialCodexRateLimitsSchema),
};

const safeBackendMessages = new Set([
  "本地服务启动失败，请确认已安装 Java 17 后重试。",
  "本地服务暂时不可用，请稍后重试。",
  "中央后台暂不可用，请确认后台已启动且服务器地址正确。",
  "本地服务返回了无效的数据。",
  "邮箱或密码不正确，请检查后重试。",
  "请先登录系统账户。",
  "请输入完整的服务器地址，例如 http://192.168.0.102:8080。",
  "服务器地址缺少主机名。",
  "服务器地址只能填写协议、主机和端口。",
  "服务器地址须以 http:// 或 https:// 开头。",
  "无法读取客户端设置目录。",
  "无法保存服务器设置，请检查目录权限。",
  "无法保存服务器设置，请稍后重试。",
]);

/** Never render an arbitrary transport error, URL, or credential-like value. */
export function getApiErrorMessage(error: unknown): string {
  if (error instanceof z.ZodError) {
    return "本地接口返回的数据格式不完整，请重启应用后重试。";
  }
  const message =
    typeof error === "string"
      ? error
      : error instanceof Error
        ? error.message
        : null;
  if (message !== null && safeBackendMessages.has(message.trim())) {
    return message.trim();
  }
  return "暂时无法连接本地服务，请确认应用仍在运行。";
}

export const schemas = {
  status: statusSchema,
  systemSession: systemSessionSchema,
  quotaOverview: quotaOverviewSchema,
  usageStatus: usageStatusSchema,
  officialCodexRateLimits: officialCodexRateLimitsSchema,
};
