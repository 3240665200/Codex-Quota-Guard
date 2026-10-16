import type { OfficialConnectionInput } from '../../services/api';

export const LOCAL_PREVIEW_PARAMETER_MAX_BYTES = 64 * 1024;

type LocalPreviewParameters = Pick<OfficialConnectionInput, 'requestedAccountId' | 'authorization' | 'cookie'>;
type ParameterField = keyof LocalPreviewParameters;

const fields = new Map<string, ParameterField>([
  ['[Official Account ID]', 'requestedAccountId'],
  ['[Official Authorization - include Bearer]', 'authorization'],
  ['[Official Cookie - optional]', 'cookie'],
]);

const invalid = () => new Error('本机参数文件格式不正确，请检查 JSON 字段或三段标签及对应参数。');

/** Parse a local draft only. Never include file content in errors or logs. */
export function parseLocalPreviewParameters(text: string): LocalPreviewParameters {
  if (new TextEncoder().encode(text).byteLength > LOCAL_PREVIEW_PARAMETER_MAX_BYTES) throw invalid();
  const contents = text.replace(/^\uFEFF/, '');
  const values: Partial<Record<ParameterField, string>> = {};
  if (contents.trimStart().startsWith('{')) {
    let parsed: unknown;
    try { parsed = JSON.parse(contents); } catch { throw invalid(); }
    if (!parsed || typeof parsed !== 'object' || Array.isArray(parsed)
      || Object.keys(parsed).some((key) => !['requestedAccountId', 'authorization', 'cookie'].includes(key))) throw invalid();
    const record = parsed as Record<string, unknown>;
    if (typeof record.requestedAccountId !== 'string' || typeof record.authorization !== 'string'
      || (record.cookie !== undefined && typeof record.cookie !== 'string')) throw invalid();
    values.requestedAccountId = record.requestedAccountId;
    values.authorization = record.authorization;
    values.cookie = record.cookie as string | undefined ?? '';
  } else {
    const lines = contents.split(/\r?\n/);
    for (let index = 0; index < lines.length; index += 1) {
      const label = lines[index].trim();
      if (!label.startsWith('[')) continue;
      const field = fields.get(label);
      if (!field || Object.hasOwn(values, field)) throw invalid();
      // An empty immediate line is an empty value, not permission to consume later instructions.
      const value = lines[index + 1] ?? '';
      if (lines[index + 2]?.trim()) throw invalid();
      values[field] = value;
      index += 1;
    }
  }

  const { requestedAccountId, authorization, cookie } = values;
  if (Object.values(values).some((value) => /[\x00-\x1f\x7f\u2028\u2029]/.test(value))
      || !requestedAccountId || !/^[A-Za-z0-9_-]{1,160}$/.test(requestedAccountId)
      || !authorization || authorization.length > 16_384
      || !/^Bearer [A-Za-z0-9._~+/-]+=*$/.test(authorization)
      || cookie === undefined || cookie.length > 32_768) throw invalid();

  if (cookie) {
    const names = new Set<string>();
    for (const part of cookie.split(';')) {
      const entry = part.trim();
      if (!/^__Secure-next-auth\.session-token(?:\.[0-9]{1,3})?=[^;\s\x00-\x1f\x7f]+$/.test(entry)) throw invalid();
      const name = entry.slice(0, entry.indexOf('='));
      if (names.has(name)) throw invalid();
      names.add(name);
    }
  }

  return { requestedAccountId, authorization, cookie };
}
