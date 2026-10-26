export type ReviewDates = { start: string; end: string };
const pad = (n: number) => String(n).padStart(2, '0');
export function localDateTime(date: Date) {
  return `${date.getFullYear()}-${pad(date.getMonth() + 1)}-${pad(date.getDate())}T${pad(date.getHours())}:${pad(date.getMinutes())}:${pad(date.getSeconds())}`;
}
export function reviewDateQuery(dates: ReviewDates): { from?: string; to?: string } {
  const from = dates.start ? new Date(dates.start) : null, end = dates.end ? new Date(dates.end) : null;
  if (from && !Number.isFinite(from.getTime()) || end && !Number.isFinite(end.getTime())) throw new Error('请输入有效的开始和结束时间。');
  if (from && end && from > end) throw new Error('开始时间不能晚于结束时间。');
  // The control uses whole seconds. Display an inclusive ending second while
  // querying a half-open interval, which also includes fractional event times.
  return { from: from?.toISOString(), to: end ? new Date(end.getTime() + 1000).toISOString() : undefined };
}
export function reviewDatePreset(preset: 'all' | 'today' | 'yesterday' | 'week' | 'month' | 'older', now = new Date()): ReviewDates {
  if (preset === 'all') return { start: '', end: '' };
  if (preset === 'older') return { start: '', end: localDateTime(new Date(now.getTime() - 30 * 86400_000 - 1000)) };
  const from = new Date(now); from.setHours(0, 0, 0, 0);
  const to = new Date(from); to.setDate(to.getDate() + 1);
  if (preset === 'yesterday') { from.setDate(from.getDate() - 1); to.setDate(to.getDate() - 1); }
  if (preset === 'week' || preset === 'month') from.setDate(from.getDate() - (preset === 'week' ? 6 : 29));
  return { start: localDateTime(from), end: localDateTime(new Date(to.getTime() - 1000)) };
}
