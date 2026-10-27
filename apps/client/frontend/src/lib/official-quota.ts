import type { OfficialCodexRateLimits } from "./api";

export function officialQuotaStatus(quota: OfficialCodexRateLimits): string {
  switch (quota.status) {
    case "NOT_LINKED":
      return "团队尚未关联官方账户";
    case "AUTH_REQUIRED":
      return "官方授权需要更新，请联系管理员";
    case "DISABLED":
      return "服务端已暂停套餐检测";
    case "PENDING":
      return "等待服务端首次检测";
    default: {
      const time = quota.fetchedAt
        ? new Date(quota.fetchedAt).toLocaleString("zh-CN", { hour12: false })
        : null;
      const prefix = quota.stale ? "上次成功数据，更新延迟" : "服务端定时同步";
      return time ? `${prefix} · ${time}` : "等待服务端套餐数据";
    }
  }
}
