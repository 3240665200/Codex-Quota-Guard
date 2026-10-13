import { fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import { App as AntApp } from 'antd';
import { beforeEach, describe, expect, it, vi } from 'vitest';

import OfficialAccountsPage from './index';

const mocks = vi.hoisted(() => ({
  createOfficialAccount: vi.fn(), deleteOfficialAccount: vi.fn(),
  listOfficialAccounts: vi.fn(), updateOfficialAccount: vi.fn(),
  listMailboxProviders: vi.fn(), getOfficialAccountMailbox: vi.fn(),
  saveOfficialAccountMailbox: vi.fn(), deleteOfficialAccountMailbox: vi.fn(),
  testOfficialAccountMailbox: vi.fn(),
}));
vi.mock('../../services/api', () => mocks);

const providers = [
  ['QQ', 'QQ 邮箱', 'imap.qq.com'], ['NETEASE_163', '163 邮箱', 'imap.163.com'],
  ['NETEASE_126', '126 邮箱', 'imap.126.com'], ['YEAH', 'yeah.net 邮箱', 'imap.yeah.net'],
  ['GOOGLE', 'Gmail', 'imap.gmail.com'], ['MICROSOFT', 'Outlook / Microsoft 365', 'outlook.office365.com'],
  ['ICLOUD', 'iCloud 邮箱', 'imap.mail.me.com'], ['YAHOO', 'Yahoo 邮箱', 'imap.mail.yahoo.com'],
  ['ZOHO', 'Zoho 邮箱', 'imap.zoho.com'], ['TENCENT_ENTERPRISE', '腾讯企业邮箱', 'imap.exmail.qq.com'],
  ['ALI_ENTERPRISE', '阿里企业邮箱', 'imap.qiye.aliyun.com'], ['CUSTOM', '自定义 IMAP', ''],
].map(([id, label, host]) => ({ id, label, host, port: 993,
  authModes: id === 'GOOGLE' ? ['GOOGLE_OAUTH2'] : id === 'MICROSOFT'
    ? ['MICROSOFT_OAUTH2'] : ['PASSWORD'], help: `${label}的官方 IMAP 设置` }));
const saved = {
  configured: true, enabled: true, provider: 'QQ', host: 'imap.qq.com', port: 993,
  username: 'owner@qq.com', recipientEmail: 'owner@qq.com', folder: 'INBOX',
  senderEmail: 'actual-sender@example.com', authMode: 'PASSWORD', oauthClientId: '',
  oauthTenant: '', hasPassword: true, hasClientSecret: false, hasRefreshToken: false,
  encryptionReady: true,
};

async function openMailbox() {
  render(<AntApp><OfficialAccountsPage /></AntApp>);
  fireEvent.click(await screen.findByRole('button', { name: /邮箱配置.*研发账户|研发账户.*邮箱配置/ }));
  return await screen.findByRole('dialog', { name: /邮箱配置/ });
}

describe('官方账户的用户自有邮箱配置', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    mocks.listOfficialAccounts.mockResolvedValue([{ id: 'account-1', label: '研发账户',
      estimatedTotalTokens: 1_000_000, reporterEmail: 'reporter@example.com', quota: null,
      createdAt: '2026-10-12T00:00:00Z', updatedAt: '2026-10-12T00:00:00Z', version: 0 }]);
    mocks.listMailboxProviders.mockResolvedValue(providers);
    mocks.getOfficialAccountMailbox.mockResolvedValue(saved);
    mocks.saveOfficialAccountMailbox.mockResolvedValue(saved);
    mocks.deleteOfficialAccountMailbox.mockResolvedValue(undefined);
    mocks.testOfficialAccountMailbox.mockResolvedValue({ success: true, message: '连接成功' });
  });

  it('loads the selected account and all server-provided domestic and overseas presets', async () => {
    const dialog = await openMailbox();
    await waitFor(() => expect(mocks.getOfficialAccountMailbox).toHaveBeenCalledWith('account-1'));
    expect(mocks.listMailboxProviders).toHaveBeenCalledOnce();
    await waitFor(() => expect(within(dialog).getByLabelText('邮箱登录用户名')).toHaveValue('owner@qq.com'));
    fireEvent.mouseDown(within(dialog).getByRole('combobox', { name: '邮箱服务商' }));
    for (const provider of providers) {
      expect(await screen.findByRole('option', { name: provider.label })).toBeInTheDocument();
    }
    expect(within(dialog).getAllByText(/TLS/).length).toBeGreaterThan(0);
    expect(screen.queryByRole('button', { name: /一键授权/ })).not.toBeInTheDocument();
  });

  it('keeps saved credentials out of the form and omits blank secrets when updating', async () => {
    const dialog = await openMailbox();
    await within(dialog).findByDisplayValue('actual-sender@example.com');
    expect(within(dialog).getByLabelText('邮箱密码 / 授权码')).toHaveValue('');
    fireEvent.click(within(dialog).getByRole('button', { name: /^保\s*存$/ }));
    await waitFor(() => expect(mocks.saveOfficialAccountMailbox).toHaveBeenCalledOnce());
    const [id, body] = mocks.saveOfficialAccountMailbox.mock.calls[0];
    expect(id).toBe('account-1');
    expect(body).toMatchObject({ host: 'imap.qq.com', port: 993, folder: 'INBOX',
      recipientEmail: 'owner@qq.com', senderEmail: 'actual-sender@example.com', authMode: 'PASSWORD' });
    expect(body).not.toHaveProperty('password');
    expect(body).not.toHaveProperty('oauthClientSecret');
    expect(body).not.toHaveProperty('oauthRefreshToken');
  });

  it('does not infer a sender from a display name and validates the real sender address', async () => {
    mocks.getOfficialAccountMailbox.mockResolvedValue({ ...saved, senderEmail: '' });
    const dialog = await openMailbox();
    await waitFor(() => expect(within(dialog).getByLabelText('邮箱登录用户名')).toHaveValue('owner@qq.com'));
    expect(within(dialog).getByLabelText('发件地址 1')).toHaveValue('');
    fireEvent.click(within(dialog).getByRole('button', { name: /^保\s*存$/ }));
    expect(await within(dialog).findByText('请输入真实发件邮箱')).toBeInTheDocument();
    expect(mocks.saveOfficialAccountMailbox).not.toHaveBeenCalled();
  });

  it('saves two sender fields without clearing authorization and displays the normalized response', async () => {
    const normalized = 'first@example.com,second@example.com';
    mocks.saveOfficialAccountMailbox.mockResolvedValue({ ...saved, senderEmail: normalized });
    const dialog = await openMailbox();
    await within(dialog).findByDisplayValue('actual-sender@example.com');
    const first = within(dialog).getByLabelText('发件地址 1');
    const second = within(dialog).getByLabelText('发件地址 2');
    expect(first.tagName).toBe('INPUT');
    expect(second).toHaveValue('');
    expect(within(dialog).getByText(/两个地址.*同时生效.*精确匹配/)).toBeInTheDocument();
    fireEvent.change(first, { target: { value: 'first@example.com' } });
    fireEvent.change(second, { target: { value: 'SECOND@example.com' } });
    fireEvent.click(within(dialog).getByRole('button', { name: /^保\s*存$/ }));
    await waitFor(() => expect(mocks.saveOfficialAccountMailbox).toHaveBeenCalledOnce());
    expect(mocks.saveOfficialAccountMailbox.mock.calls[0][1]).toMatchObject({
      senderEmail: 'first@example.com,SECOND@example.com',
    });
    expect(mocks.saveOfficialAccountMailbox.mock.calls[0][1]).not.toHaveProperty('password');
    await waitFor(() => expect(first).toHaveValue('first@example.com'));
    expect(second).toHaveValue('second@example.com');
  });

  it('keeps a saved sender list editable and compatible with the existing string API', async () => {
    const senderEmail = 'first@example.com,second@example.com';
    mocks.getOfficialAccountMailbox.mockResolvedValue({ ...saved, senderEmail });
    mocks.saveOfficialAccountMailbox.mockResolvedValue({ ...saved, senderEmail });
    const dialog = await openMailbox();
    await within(dialog).findByDisplayValue('first@example.com');
    expect(within(dialog).getByLabelText('发件地址 2')).toHaveValue('second@example.com');
    fireEvent.click(within(dialog).getByRole('button', { name: /^保\s*存$/ }));
    await waitFor(() => expect(mocks.saveOfficialAccountMailbox).toHaveBeenCalledOnce());
    expect(mocks.saveOfficialAccountMailbox.mock.calls[0][1].senderEmail).toBe(senderEmail);
  });

  it.each([
    '*@example.com', 'example.com', 'first@example.com,broken', 'ChatGPT <first@example.com>',
    'first@example.com,second@example.com,third@example.com',
    'a'.repeat(310) + '@example.com',
  ])('rejects invalid, wildcard or oversized sender lists: %s', async (senderEmail) => {
    const dialog = await openMailbox();
    await within(dialog).findByDisplayValue('actual-sender@example.com');
    fireEvent.change(within(dialog).getByLabelText('发件地址 1'), { target: { value: senderEmail } });
    fireEvent.click(within(dialog).getByRole('button', { name: /^保\s*存$/ }));
    expect(await within(dialog).findByText(/请输入完整发件邮箱|发件地址总长度/)).toBeInTheDocument();
    expect(mocks.saveOfficialAccountMailbox).not.toHaveBeenCalled();
  });

  it('clears typed secrets immediately on close and never writes them to browser storage', async () => {
    const storage = vi.spyOn(Storage.prototype, 'setItem');
    const dialog = await openMailbox();
    await within(dialog).findByDisplayValue('actual-sender@example.com');
    fireEvent.change(within(dialog).getByLabelText('邮箱密码 / 授权码'), { target: { value: 'private-mail-secret' } });
    fireEvent.click(within(dialog).getByRole('button', { name: /^取\s*消$/ }));
    await waitFor(() => expect(screen.queryByDisplayValue('private-mail-secret')).not.toBeInTheDocument());
    fireEvent.click(screen.getByRole('button', { name: /邮箱配置.*研发账户|研发账户.*邮箱配置/ }));
    const reopened = await screen.findByRole('dialog', { name: /邮箱配置/ });
    await within(reopened).findByDisplayValue('actual-sender@example.com');
    expect(within(reopened).getByLabelText('邮箱密码 / 授权码')).toHaveValue('');
    expect(storage.mock.calls.some((args) => args.some((value) => String(value).includes('private-mail-secret')))).toBe(false);
  });

  it('disables enabled saves when the server has no encryption key', async () => {
    mocks.getOfficialAccountMailbox.mockResolvedValue({ ...saved, encryptionReady: false });
    const dialog = await openMailbox();
    expect(await within(dialog).findByText(/服务端.*加密.*密钥/)).toBeInTheDocument();
    expect(within(dialog).getByRole('button', { name: /^保\s*存$/ })).toBeDisabled();
    expect(mocks.saveOfficialAccountMailbox).not.toHaveBeenCalled();
  });

  it('tests the saved configuration only, without submitting typed unsaved secrets', async () => {
    const dialog = await openMailbox();
    await within(dialog).findByDisplayValue('actual-sender@example.com');
    fireEvent.change(within(dialog).getByLabelText('邮箱密码 / 授权码'), { target: { value: 'unsaved-secret' } });
    fireEvent.click(within(dialog).getByRole('button', { name: '测试已保存配置' }));
    await waitFor(() => expect(mocks.testOfficialAccountMailbox).toHaveBeenCalledWith('account-1'));
    expect(mocks.testOfficialAccountMailbox.mock.calls[0]).toHaveLength(1);
    expect(mocks.saveOfficialAccountMailbox).not.toHaveBeenCalled();
    expect(await within(dialog).findByText('连接成功')).toBeInTheDocument();
  });

  it('requires confirmation before deleting the saved mailbox connection', async () => {
    const dialog = await openMailbox();
    await within(dialog).findByDisplayValue('actual-sender@example.com');
    fireEvent.click(within(dialog).getByRole('button', { name: '删除邮箱配置' }));
    expect(mocks.deleteOfficialAccountMailbox).not.toHaveBeenCalled();
    fireEvent.click(await screen.findByRole('button', { name: '确认删除' }));
    await waitFor(() => expect(mocks.deleteOfficialAccountMailbox).toHaveBeenCalledWith('account-1'));
  });

  it('shows only Microsoft OAuth for Outlook and retains saved OAuth secrets when left blank', async () => {
    const outlook = { ...saved, provider: 'MICROSOFT', host: 'outlook.office365.com',
      authMode: 'MICROSOFT_OAUTH2', oauthClientId: 'admin-owned-client', oauthTenant: 'common',
      hasPassword: false, hasClientSecret: true, hasRefreshToken: true };
    mocks.getOfficialAccountMailbox.mockResolvedValue(outlook);
    mocks.saveOfficialAccountMailbox.mockResolvedValue(outlook);
    const dialog = await openMailbox();
    expect(await within(dialog).findByDisplayValue('admin-owned-client')).toBeInTheDocument();
    expect(within(dialog).getByLabelText('Microsoft Tenant')).toHaveValue('common');
    expect(within(dialog).queryByLabelText('邮箱密码 / 授权码')).not.toBeInTheDocument();
    fireEvent.mouseDown(within(dialog).getByRole('combobox', { name: '认证方式' }));
    expect(await screen.findByRole('option', { name: 'Microsoft OAuth 2.0' })).toBeInTheDocument();
    expect(screen.queryByRole('option', { name: '邮箱密码 / 授权码' })).not.toBeInTheDocument();
    fireEvent.click(within(dialog).getByRole('button', { name: /^保\s*存$/ }));
    await waitFor(() => expect(mocks.saveOfficialAccountMailbox).toHaveBeenCalledOnce());
    expect(mocks.saveOfficialAccountMailbox.mock.calls[0][1]).toMatchObject({
      authMode: 'MICROSOFT_OAUTH2', oauthClientId: 'admin-owned-client', oauthTenant: 'common' });
    expect(mocks.saveOfficialAccountMailbox.mock.calls[0][1]).not.toHaveProperty('oauthClientSecret');
    expect(mocks.saveOfficialAccountMailbox.mock.calls[0][1]).not.toHaveProperty('oauthRefreshToken');
  });

  it('requires a refresh token for a new Google authorization and clears it after a failed save', async () => {
    mocks.getOfficialAccountMailbox.mockResolvedValue({ ...saved, provider: 'GOOGLE', host: 'imap.gmail.com',
      authMode: 'GOOGLE_OAUTH2', oauthClientId: 'google-owned-client', hasPassword: false,
      hasClientSecret: false, hasRefreshToken: false });
    mocks.saveOfficialAccountMailbox.mockRejectedValue(new Error('邮箱授权配置未保存'));
    const dialog = await openMailbox();
    await within(dialog).findByDisplayValue('google-owned-client');
    fireEvent.click(within(dialog).getByRole('button', { name: /^保\s*存$/ }));
    expect(await within(dialog).findByText('请输入 OAuth Refresh Token')).toBeInTheDocument();
    expect(mocks.saveOfficialAccountMailbox).not.toHaveBeenCalled();
    fireEvent.change(within(dialog).getByLabelText('OAuth Refresh Token'), { target: { value: 'private-refresh-fixture' } });
    fireEvent.click(within(dialog).getByRole('button', { name: /^保\s*存$/ }));
    expect(await within(dialog).findByText('邮箱授权配置未保存')).toBeInTheDocument();
    expect(within(dialog).getByLabelText('OAuth Refresh Token')).toHaveValue('');
  });

  it('does not carry typed authorization into another official account', async () => {
    const account = { id: 'account-1', label: '研发账户', estimatedTotalTokens: 1_000_000,
      reporterEmail: null, quota: null, createdAt: '2026-10-12T00:00:00Z',
      updatedAt: '2026-10-12T00:00:00Z', version: 0 };
    mocks.listOfficialAccounts.mockResolvedValue([account, { ...account, id: 'account-2', label: '第二账户' }]);
    mocks.getOfficialAccountMailbox.mockResolvedValueOnce(saved).mockResolvedValueOnce({
      ...saved, username: 'second@qq.com', recipientEmail: 'second@qq.com' });
    const dialog = await openMailbox();
    await within(dialog).findByDisplayValue('actual-sender@example.com');
    fireEvent.change(within(dialog).getByLabelText('邮箱密码 / 授权码'), { target: { value: 'first-account-secret' } });
    fireEvent.click(within(dialog).getByRole('button', { name: /^取\s*消$/ }));
    fireEvent.click(await screen.findByRole('button', { name: /邮箱配置.*第二账户/ }));
    const second = await screen.findByRole('dialog', { name: /邮箱配置.*第二账户/ });
    await waitFor(() => expect(within(second).getByLabelText('邮箱登录用户名')).toHaveValue('second@qq.com'));
    expect(within(second).getByLabelText('邮箱密码 / 授权码')).toHaveValue('');
    expect(screen.queryByDisplayValue('first-account-secret')).not.toBeInTheDocument();
  });
});
