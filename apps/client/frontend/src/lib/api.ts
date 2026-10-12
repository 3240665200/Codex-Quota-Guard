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

const autostartSchema = z.object({
  supported: z.boolean(),
  enabled: z.boolean(),
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

const startupGateSchema = z.object({
  state: z.enum([
    "READY",
    "CHECKING",
    "ADMIN_REQUIRED",
    "METER_UNAVAILABLE",
    "GUARD_ERROR",
    "BACKEND_UNAVAILABLE",
    "UNSUPPORTED",
  ]),
  ccSwitchTerminated: z.boolean(),
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
      "SERVICE_ACTIVE",
    ]),
    lastScannedAt: isoInstant.nullable(),
    lastReportedAt: isoInstant.nullable(),
    lastReportedEvents: tokenCount,
    issues: z.array(z.string().min(1)),
    issueCounts: z
      .record(z.string().regex(/^[A-Z_]{1,64}$/), tokenCount.min(1))
      .optional(),
    errorCode: z
      .string()
      .regex(/^[A-Z_]{1,64}$/)
      .nullable()
      .optional(),
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
    version: z.string().min(1).max(64).nullable().optional(),
    errorCode: z
      .string()
      .regex(/^[A-Z_]{1,64}$/)
      .nullable(),
  }),
  pairingState: z.enum(["NOT_INSTALLED", "PAIRING_SENT", "PAIRING_FAILED"]),
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
        costStatus: z
          .enum([
            "PRICED",
            "MISSING_PRICE",
            "MISSING_BREAKDOWN",
            "INVALID_BREAKDOWN",
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
export type StartupGateResult = z.infer<typeof startupGateSchema>;
export type MeterVersion = z.infer<typeof meterVersionSchema>;
export type ServerConfig = z.infer<typeof serverConfigSchema>;
export type SystemUser = z.infer<typeof systemUserSchema>;
export type SystemSession = z.infer<typeof systemSessionSchema>;
export type QuotaTeam = z.infer<typeof quotaTeamSchema>;
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
  autostartStatus: () => invokeValidated("autostart_status", autostartSchema),
  setAutostart: (enabled: boolean) =>
    invokeValidated("autostart_set", autostartSchema, { enabled }),
  startupGateCheck: () =>
    invokeValidated("startup_gate_check", startupGateSchema),
  startupGuardStatus: () =>
    invokeValidated("startup_guard_status", startupGateSchema),
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
  meterSetupError: () =>
    invokeValidated("meter_setup_error", z.string().max(300).nullable()),
  meterServiceVersion: () =>
    invokeValidated("meter_service_version", meterVersionSchema),
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
  usageEvents: async (page = 0, size = 10) => {
    z.number().int().min(0).max(100000).parse(page);
    z.number().int().min(1).max(100).parse(size);
    return invokeValidated("usage_events", usageEventsSchema, { page, size });
  },
  usageAnalytics: async (days: 1 | 7 | 30 = 7) => {
    z.union([z.literal(1), z.literal(7), z.literal(30)]).parse(days);
    return invokeValidated("usage_analytics", usageAnalyticsSchema, { days });
  },
  meterRuntimeLogs: () =>
    invokeValidated("meter_runtime_logs", meterRuntimeLogsSchema),
  scanUsage: () => invokeValidated("usage_scan", usageStatusSchema),
  pairMeter: () => invokeValidated("usage_pair", usageStatusSchema),
  codexRateLimits: () =>
    invokeValidated("codex_rate_limits", officialCodexRateLimitsSchema),
};

const safeBackendMessages = new Set([
  "本地服务启动失败，安装包可能不完整，请重新安装。",
  "本地服务暂时不可用，请稍后重试。",
  "中央后台暂不可用，请确认后台已启动且服务器地址正确。",
  "本地服务返回了无效的数据。",
  "流水分页参数无效，请刷新后重试。",
  "统计范围须为 1、7 或 30 天。",
  "邮箱或密码不正确，请检查后重试。",
  "请先登录系统账户。",
  "中央后台拒绝请求，请检查请求内容后重试。",
  "请输入完整的服务器地址，例如 http://192.168.0.102:8080。",
  "服务器地址缺少主机名。",
  "服务器地址只能填写协议、主机和端口。",
  "服务器地址须以 http:// 或 https:// 开头。",
  "无法读取客户端设置目录。",
  "无法保存服务器设置，请检查目录权限。",
  "无法保存服务器设置，请稍后重试。",
  "无法读取开机自启动状态，请检查 Windows 任务计划程序。",
  "无法读取 Windows 开机自启动状态，请检查当前用户的启动应用设置。",
  "Windows 启动应用的审批数据无法识别，已保留原设置，请在任务管理器中检查。",
  "开机自启动项与当前安装目录不匹配，已阻止自动修改，请检查旧版本残留启动项。",
  "客户端安装路径无法用于 Windows 启动应用，请安装到较短的本地路径后重试。",
  "开机自启动迁移未完成且恢复失败，请在任务管理器和任务计划程序中检查本软件的启动项。",
  "普通启动项已保存，但无法确认旧启动任务已清理，请重新启动客户端后重试。",
  "Windows 启动应用设置在操作期间发生变化，已停止修改，请重新读取后重试。",
  "无法设置开机自启动，请以管理员身份运行客户端后重试。",
  "Windows 未保存开机自启动设置，请重试。",
  "开机自启动仅支持当前登录的管理员用户；请勿使用其他账户的管理员凭据设置。",
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
