import { invoke } from "@tauri-apps/api/core";
import { z } from "zod";

const isoInstant = z.string().min(1);

const statusSchema = z.object({
  service: z.string(),
  state: z.string(),
});

const rateLimitWindowSchema = z.object({
  usedPercent: z.number().finite().min(0).max(100),
  remainingPercent: z.number().finite().min(0).max(100),
  windowDurationMins: z.number().finite().int().nonnegative(),
  resetsAt: isoInstant,
});

const rateLimitBucketSchema = z.object({
  limitId: z.string(),
  limitName: z.string(),
  primary: rateLimitWindowSchema.nullable(),
  secondary: rateLimitWindowSchema.nullable(),
  planType: z.string(),
  spendControlReached: z.boolean(),
});

const quotaSnapshotSchema = z.object({
  schemaVersion: z.number().int().nonnegative(),
  roomId: z.string(),
  memberId: z.string(),
  displayName: z.string(),
  capturedAt: isoInstant,
  publishedAt: isoInstant,
  source: z.string(),
  authenticated: z.boolean(),
  planType: z.string(),
  buckets: z.array(rateLimitBucketSchema),
  managedLogin: z.boolean(),
});

const roomMemberSchema = z.object({
  memberId: z.string(),
  displayName: z.string(),
  managedLogin: z.boolean(),
  enforcementConsent: z.boolean(),
  snapshot: quotaSnapshotSchema.nullable(),
});

const roomOverviewSchema = z.object({
  configured: z.boolean(),
  roomId: z.string(),
  name: z.string(),
  members: z.array(roomMemberSchema),
  warnings: z.array(z.string()),
});

const accountOverviewSchema = z.object({
  authenticated: z.boolean(),
  planType: z.string(),
  snapshot: quotaSnapshotSchema,
});

const deviceLoginSchema = z.object({
  type: z.string(),
  loginId: z.string(),
  verificationUrl: z.string().url(),
  userCode: z.string(),
});

export type StatusResponse = z.infer<typeof statusSchema>;
export type RateLimitWindow = z.infer<typeof rateLimitWindowSchema>;
export type RateLimitBucket = z.infer<typeof rateLimitBucketSchema>;
export type QuotaSnapshot = z.infer<typeof quotaSnapshotSchema>;
export type RoomMember = z.infer<typeof roomMemberSchema>;
export type RoomOverview = z.infer<typeof roomOverviewSchema>;
export type AccountOverview = z.infer<typeof accountOverviewSchema>;
export type DeviceLogin = z.infer<typeof deviceLoginSchema>;

async function invokeValidated<T>(
  command: string,
  schema: z.ZodType<T>,
): Promise<T> {
  const payload: unknown = await invoke(command);
  return schema.parse(payload);
}

export const api = {
  status: () => invokeValidated("status", statusSchema),
  roomOverview: () => invokeValidated("room_overview", roomOverviewSchema),
  accountOverview: () =>
    invokeValidated("account_overview", accountOverviewSchema),
  startDeviceLogin: () =>
    invokeValidated("start_device_login", deviceLoginSchema),
  logout: async () => {
    await invoke("logout");
  },
};

export function getApiErrorMessage(error: unknown): string {
  if (error instanceof z.ZodError) {
    return "本地接口返回的数据格式不完整，请重启应用后重试。";
  }
  if (error instanceof Error && error.message.trim()) {
    return error.message.replace(/bearer|token|authorization/gi, "认证信息");
  }
  return "暂时无法连接本地服务，请确认应用仍在运行。";
}

export const schemas = {
  status: statusSchema,
  roomOverview: roomOverviewSchema,
  accountOverview: accountOverviewSchema,
  deviceLogin: deviceLoginSchema,
};
