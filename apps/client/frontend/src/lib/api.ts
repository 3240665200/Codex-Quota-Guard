import { invoke } from "@tauri-apps/api/core";
import { z } from "zod";

const isoInstant = z.string().min(1);

const statusSchema = z.object({
  service: z.string(),
  state: z.string(),
});

const serverConfigSchema = z.object({
  url: z.string().url(),
  allowInsecureLanHttp: z.boolean(),
});

const systemUserSchema = z.object({
  id: z.string().min(1),
  email: z.string().email(),
  displayName: z.string().min(1),
  role: z.string().min(1),
  status: z.string().min(1),
});

const managedCodexAccountSchema = z.object({
  id: z.string().min(1),
  displayName: z.string().min(1),
  status: z.string().min(1),
  updatedAt: isoInstant,
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

const quotaPolicySchema = z.object({
  fiveHourLimitPercent: z.number().finite().min(0).max(100),
  weeklyLimitPercent: z.number().finite().min(0).max(100),
  fiveHourSource: z.string().min(1),
  weeklySource: z.string().min(1),
});

const quotaUsageSchema = z.object({
  measurementStatus: z.enum(["CURRENT", "NOT_MEASURED"]),
  fiveHourUsedPercent: z.number().finite().min(0).max(100).nullable(),
  weeklyUsedPercent: z.number().finite().min(0).max(100).nullable(),
  fiveHourRemainingPercent: z.number().finite().min(0).max(100).nullable(),
  weeklyRemainingPercent: z.number().finite().min(0).max(100).nullable(),
  fiveHourResetsAt: isoInstant.nullable(),
  weeklyResetsAt: isoInstant.nullable(),
  measuredAt: isoInstant.nullable(),
});

/** The fixed central response for the currently signed-in product user. */
const quotaOverviewSchema = z
  .object({
    assigned: z.boolean(),
    user: systemUserSchema,
    codexAccount: managedCodexAccountSchema.nullable(),
    team: quotaTeamSchema.nullable(),
    policy: quotaPolicySchema,
    usage: quotaUsageSchema,
    allowed: z.boolean(),
    blockReason: z.string().nullable(),
  })
  .superRefine((value, context) => {
    if (value.assigned !== (value.codexAccount !== null)) {
      context.addIssue({
        code: "custom",
        message: "账户分配状态不一致。",
        path: ["codexAccount"],
      });
    }
    const usageValues = [
      value.usage.fiveHourUsedPercent,
      value.usage.weeklyUsedPercent,
      value.usage.fiveHourRemainingPercent,
      value.usage.weeklyRemainingPercent,
    ];
    if (
      value.usage.measurementStatus === "NOT_MEASURED" &&
      (usageValues.some((measurement) => measurement !== null) || value.allowed)
    ) {
      context.addIssue({
        code: "custom",
        message: "未采集用量不能被标记为可用。",
        path: ["usage"],
      });
    }
    if (
      value.usage.measurementStatus === "CURRENT" &&
      usageValues.some((measurement) => measurement === null)
    ) {
      context.addIssue({
        code: "custom",
        message: "当前用量必须包含已用和剩余额度。",
        path: ["usage"],
      });
    }
  });

export type StatusResponse = z.infer<typeof statusSchema>;
export type ServerConfig = z.infer<typeof serverConfigSchema>;
export type SystemUser = z.infer<typeof systemUserSchema>;
export type ManagedCodexAccount = z.infer<typeof managedCodexAccountSchema>;
export type SystemSession = z.infer<typeof systemSessionSchema>;
export type QuotaTeam = z.infer<typeof quotaTeamSchema>;
export type QuotaPolicy = z.infer<typeof quotaPolicySchema>;
export type QuotaUsage = z.infer<typeof quotaUsageSchema>;
export type QuotaOverview = z.infer<typeof quotaOverviewSchema>;

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
};

const safeBackendMessages = new Set([
  "本地服务启动失败，请确认已安装 Java 17 后重试。",
  "本地服务暂时不可用，请稍后重试。",
  "本地服务返回了无效的数据。",
  "邮箱或密码不正确，请检查后重试。",
  "请先登录系统账户。",
  "请输入完整的服务器地址，例如 http://192.168.0.102:8080。",
  "服务器地址缺少主机名。",
  "服务器地址只能填写协议、主机和端口。",
  "局域网 HTTP 需要先勾选风险确认。",
  "远程服务器须使用 HTTPS；临时 HTTP 仅允许私有局域网 IP。",
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
};
