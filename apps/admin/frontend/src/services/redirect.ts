export function getSafeRedirectUrl(
  value: string | null,
  origin = window.location.origin,
): string {
  if (!value?.startsWith('/') || value.startsWith('//')) return '/dashboard';
  try {
    const parsed = new URL(value, origin);
    if (parsed.origin !== origin) return '/dashboard';
    return `${parsed.pathname}${parsed.search}${parsed.hash}`;
  } catch {
    return '/dashboard';
  }
}
