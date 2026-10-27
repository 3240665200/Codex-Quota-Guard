// Google Authenticator's migration payload uses protobuf wire encoding. Only a
// single explicitly exported account is accepted to avoid attaching another key.
type Field = { wire: number; value: bigint | Uint8Array };
const invalid = () => new Error('验证器二维码或迁移链接无效，请重新导出对应的 GPT 账户。');

function fields(bytes: Uint8Array): Map<number, Field[]> {
  let offset = 0;
  const result = new Map<number, Field[]>();
  const varint = () => {
    let value = 0n;
    for (let i = 0; i < 10 && offset < bytes.length; i += 1) {
      const byte = bytes[offset++];
      if (i === 9 && byte > 1) throw invalid();
      value |= BigInt(byte & 127) << BigInt(i * 7);
      if (byte < 128) return value;
    }
    throw invalid();
  };
  let count = 0;
  while (offset < bytes.length) {
    if (++count > 64) throw invalid();
    const tag = varint();
    const number = Number(tag >> 3n), wire = Number(tag & 7n);
    if (number < 1 || number > 536_870_911) throw invalid();
    let value: bigint | Uint8Array;
    if (wire === 0) value = varint();
    else {
      const length = wire === 2 ? Number(varint()) : wire === 1 ? 8 : wire === 5 ? 4 : -1;
      if (!Number.isSafeInteger(length) || length < 0 || length > bytes.length - offset) throw invalid();
      value = bytes.subarray(offset, offset + length);
      offset += length;
    }
    result.set(number, [...(result.get(number) || []), { wire, value }]);
  }
  return result;
}

function one(message: Map<number, Field[]>, number: number, wire: number): Field | undefined {
  const values = message.get(number);
  if (!values) return undefined;
  if (values.length !== 1 || values[0].wire !== wire) throw invalid();
  return values[0];
}

function integer(message: Map<number, Field[]>, number: number, fallback = 0n): bigint {
  return (one(message, number, 0)?.value as bigint | undefined) ?? fallback;
}

function text(message: Map<number, Field[]>, number: number): string {
  const bytes = one(message, number, 2)?.value as Uint8Array | undefined;
  if (!bytes) return '';
  if (bytes.length > 512) throw invalid();
  const value = new TextDecoder('utf-8', { fatal: true }).decode(bytes);
  if (/[\u0000-\u001f\u007f]/.test(value)) throw invalid();
  return value;
}

function base32(bytes: Uint8Array): string {
  let value = 0, bits = 0, output = '';
  for (const byte of bytes) {
    value = (value << 8) | byte;
    bits += 8;
    while (bits >= 5) {
      bits -= 5;
      output += 'ABCDEFGHIJKLMNOPQRSTUVWXYZ234567'[(value >>> bits) & 31];
    }
  }
  if (bits) output += 'ABCDEFGHIJKLMNOPQRSTUVWXYZ234567'[(value << (5 - bits)) & 31];
  return output;
}

export interface ImportedAuthenticator { label: string; uri: string }

/** Pure local conversion: never logs, uploads, or stores the source payload. */
export function importAuthenticator(input: string): ImportedAuthenticator {
  let bytes: Uint8Array | undefined;
  try {
    if (!input || input.length > 8192) throw invalid();
    const uri = new URL(input.trim());
    if (uri.username || uri.password || uri.port || uri.hash) throw invalid();
    if (uri.protocol === 'otpauth:') {
      if (uri.hostname !== 'totp' || !uri.searchParams.get('secret') || uri.pathname.length < 2 || input.length > 4096) throw invalid();
      return { uri: input.trim(), label: decodeURIComponent(uri.pathname.slice(1)).slice(0, 160) };
    }
    if (uri.protocol !== 'otpauth-migration:' || uri.hostname !== 'offline' || !['', '/'].includes(uri.pathname)
      || [...uri.searchParams.keys()].length !== 1 || uri.searchParams.getAll('data').length !== 1) throw invalid();
    const encoded = uri.searchParams.get('data')!.replace(/ /g, '+');
    if (!/^[A-Za-z0-9+/_-]+={0,2}$/.test(encoded) || encoded.length % 4 === 1) throw invalid();
    const binary = atob(encoded.replace(/-/g, '+').replace(/_/g, '/'));
    if (binary.length > 6000) throw invalid();
    bytes = Uint8Array.from(binary, (char) => char.charCodeAt(0));
    const payload = fields(bytes);
    const entries = payload.get(1) || [];
    if (entries.length !== 1 || integer(payload, 3, 1n) !== 1n || integer(payload, 4) !== 0n) {
      throw new Error('请在 Google Authenticator 导出时只勾选当前 GPT 账户，一次导入一个账户。');
    }
    // Google migration v2 adds metadata while retaining the validated TOTP core fields.
    if (entries[0].wire !== 2 || ![0n, 1n, 2n].includes(integer(payload, 2))) throw invalid();
    const account = fields(entries[0].value as Uint8Array);
    const secret = one(account, 1, 2)?.value as Uint8Array | undefined;
    if (!secret || secret.length < 10 || secret.length > 128) throw invalid();
    if (integer(account, 6) !== 2n || integer(account, 7) !== 0n) throw new Error('只支持基于时间的 TOTP 验证器，不能导入 HOTP 账户。');
    const algorithm = ({ '1': 'SHA1', '2': 'SHA256', '3': 'SHA512' } as Record<string, string>)[integer(account, 4).toString()];
    const digits = ({ '1': '6', '2': '8' } as Record<string, string>)[integer(account, 5).toString()];
    if (!algorithm || !digits) throw new Error('此验证器的算法或验证码位数不受支持。');
    const name = text(account, 2), issuer = text(account, 3);
    const label = name || issuer || 'Imported account';
    const output = new URL(`otpauth://totp/${encodeURIComponent(label)}`);
    output.searchParams.set('secret', base32(secret));
    if (issuer) output.searchParams.set('issuer', issuer);
    output.searchParams.set('algorithm', algorithm);
    output.searchParams.set('digits', digits);
    output.searchParams.set('period', '30');
    const result = output.toString();
    if (result.length > 4096) throw invalid();
    return { uri: result, label: label.slice(0, 160) };
  } catch (error) {
    if (error instanceof Error && ['请在 Google Authenticator', '只支持基于时间', '此验证器的算法'].some((prefix) => error.message.startsWith(prefix))) throw error;
    throw invalid();
  } finally { bytes?.fill(0); }
}
