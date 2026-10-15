import type { OfficialConnectionInput } from '../../services/api';

export const LOCAL_PREVIEW_PARAMETER_MAX_BYTES = 64 * 1024;

type LocalPreviewParameters = Pick<OfficialConnectionInput, 'requestedAccountId' | 'authorization' | 'cookie'>;
type ParameterField = keyof LocalPreviewParameters;

const fields = new Map<string, ParameterField>([
  ['[Official Account ID]', 'requestedAccountId'],
  ['[Official Authorization - include Bearer]', 'authorization'],
  ['[Official Cookie - optional]', 'cookie'],
]);

const invalid = () => new Error('本机参数文件格式不正确，请检查三段标签和各自紧接下一行的参数。');

/** Parse a local draft only. Never include file content in errors or logs. */
export function parseLocalPreviewParameters(text: string): LocalPreviewParameters {
  if (new TextEncoder().encode(text).byteLength > LOCAL_PREVIEW_PARAMETER_MAX_BYTES) throw invalid();
  const lines = text.replace(/^\uFEFF/, '').split(/\r?\n/);
  const values: Partial<Record<ParameterField, string>> = {};

  for (let index = 0; index < lines.length; index += 1) {
    const label = lines[index].trim();
    if (!label.startsWith('[')) continue;
    const field = fields.get(label);
    if (!field || Object.hasOwn(values, field)) throw invalid();
    // An empty immediate line is an empty value, not permission to consume later instructions.
    const value = lines[index + 1] ?? '';
    if (/[\x00-\x1f\x7f\u2028\u2029]/.test(value)) throw invalid();
    if (lines[index + 2]?.trim()) throw invalid();
    values[field] = value;
    index += 1;
  }

  const { requestedAccountId, authorization, cookie } = values;
  if (!requestedAccountId || !/^[A-Za-z0-9_-]{1,160}$/.test(requestedAccountId)
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
