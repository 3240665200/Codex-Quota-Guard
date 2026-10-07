import { clsx, type ClassValue } from "clsx";
import { twMerge } from "tailwind-merge";

export function cn(...inputs: ClassValue[]) {
  return twMerge(clsx(inputs));
}

export function formatWindowDuration(minutes: number): string {
  if (minutes < 60) return `${minutes} 分钟窗口`;
  const hours = minutes / 60;
  if (hours < 24) return `${hours} 小时窗口`;
  return `${Math.round(hours / 24)} 天窗口`;
}

export function formatResetTime(value: string): string {
  const date = new Date(value);
  if (Number.isNaN(date.valueOf())) return "重置时间未知";
  return `重置于 ${new Intl.DateTimeFormat("zh-CN", {
    month: "short",
    day: "numeric",
    hour: "2-digit",
    minute: "2-digit",
  }).format(date)}`;
}

export function formatPercent(value: number): string {
  return `${Math.round(value)}%`;
}

export function formatTokenCount(value: number): string {
  return new Intl.NumberFormat("zh-CN", { maximumFractionDigits: 0 }).format(
    value,
  );
}
