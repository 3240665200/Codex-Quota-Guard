const PASSWORD_ALPHABET = 'ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_';
const PASSWORD_LENGTH = 24;

export function generateRandomPassword(): string {
  if (!globalThis.crypto?.getRandomValues) {
    throw new Error('Secure random source unavailable');
  }

  const bytes = globalThis.crypto.getRandomValues(new Uint8Array(PASSWORD_LENGTH));
  // 64 symbols divide the 256 possible byte values evenly, so this has no modulo bias.
  return Array.from(bytes, (byte) => PASSWORD_ALPHABET[byte & 63]).join('');
}
