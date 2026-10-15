import { describe, expect, it } from 'vitest';
import { managedErrorMessage } from './managedErrors';

describe('官方网页登录安全错误提示', () => {
  it.each([
    ['BROWSER_LOGIN_BUSY', '此账户已有进行中的官方网页登录任务，请先在桌面助手完成或取消该任务。'],
    ['BROWSER_LOGIN_NOT_FOUND', '登录任务已不存在，请重新开始官方网页登录。'],
    ['BROWSER_LOGIN_NOT_ACTIVE', '此登录任务已结束，请重新检查任务状态或开始新任务。'],
    ['BROWSER_LOGIN_SAVING', '授权正在加密保存，请等待结果后再操作。'],
    ['BROWSER_LOGIN_EXPIRED', '登录任务已过期，请重新开始并在有效期内完成登录。'],
  ])('explains %s without exposing upstream content', (code, message) => {
    expect(managedErrorMessage({ code, message: 'fixture-private-message' })).toBe(message);
  });

  it('never renders an unknown browser login error message', () => {
    expect(managedErrorMessage({ code: 'UNKNOWN', message: 'fixture-private-message' }, '安全说明')).toBe('安全说明');
  });
});
