import { describe, expect, it } from 'vitest';
import { managedErrorMessage } from './managedErrors';

it.each([
  ['PROXY_SUBSCRIPTION_FORMAT_UNSUPPORTED', /Clash YAML.*URI.*Base64/],
  ['PROXY_SUBSCRIPTION_FAILED', /HTTPS.*443/],
  ['PROXY_NODE_SOURCE_MISSING', /订阅.*移除/],
  ['PROXY_NODE_SUBSCRIPTION_MANAGED', /订阅.*更新/],
  ['PROXY_SUBSCRIPTION_VERSION_CONFLICT', /刷新.*重试/],
])('explains subscription constraint %s without rendering private URL details', (code, expected) => {
  const message = managedErrorMessage({ code, message: 'https://private.example/?token=fixture-private' });
  expect(message).toMatch(expected);
  expect(message).not.toContain('fixture-private');
});

it.each([
  ['OFFICIAL_PROXY_REQUIRED', '请为当前官方账户选择一个已启用的代理节点并重新保存授权，所有官方请求均禁止直连。'],
  ['PROXY_NODE_INVALID', '节点信息无效，请检查名称、协议、服务器、端口及用户名和密码。'],
  ['PROXY_NODE_UNAVAILABLE', '此账户保存的代理节点不可用，请检查节点状态或重新选择并保存。'],
  ['PROXY_NODE_DISABLED', '此代理节点已停用，请启用节点后再测试或使用。'],
  ['PROXY_CONNECT_TIMEOUT', '代理连接超时，请检查服务器地址、端口和节点网络后重试。'],
  ['PROXY_TEST_RESPONSE_INVALID', '未能验证节点测试返回的出口信息，请检查节点后重新测试。'],
])('explains proxy failure %s without exposing server details', (code, message) => {
  expect(managedErrorMessage({ code, message: 'fixture-private-proxy-password' })).toBe(message);
});

it.each(['OFFICIAL_HTTPS_REQUIRED', 'PROXY_HTTPS_REQUIRED', 'SESSION_BINDING_HTTPS_REQUIRED'])(
  'explains the server transport policy for %s without claiming HTTP is always forbidden', (code) => {
    const message = managedErrorMessage({ code, status: 403, message: 'synthetic-private-detail' });
    expect(message).toMatch(/服务器.*传输配置/);
    expect(message).toContain('APP_REQUIRE_HTTPS=false');
    expect(message).not.toContain('synthetic-private-detail');
  },
);

describe('官方网页登录安全错误提示', () => {
  it.each([
    ['BROWSER_LOGIN_BUSY', '此账户已有进行中的官方网页登录任务，请先完成或取消该任务。'],
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
