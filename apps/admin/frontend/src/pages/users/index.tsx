import {
  CheckCircleOutlined,
  DeleteOutlined,
  EditOutlined,
  MoreOutlined,
  PlusOutlined,
  PoweroffOutlined,
  SearchOutlined,
  SlidersOutlined,
  StopOutlined,
  UserOutlined,
} from '@ant-design/icons';
import { PageContainer, ProCard } from '@ant-design/pro-components';
import {
  Alert,
  App as AntApp,
  Avatar,
  Button,
  Dropdown,
  Empty,
  Form,
  Input,
  InputNumber,
  Modal,
  Select,
  Space,
  Table,
  Tag,
  Typography,
  theme,
} from 'antd';
import type { TableColumnsType } from 'antd';
import { useCallback, useEffect, useMemo, useState } from 'react';

import { getErrorMessage } from '../../components/AdminState';
import {
  assignCodexAccountToUser,
  createUser,
  deleteUser,
  forceLogoutUser,
  getSession,
  listCodexAccounts,
  listTeams,
  listUsers,
  unassignCodexAccountFromUser,
  updateUserQuotaPolicy,
  updateUser,
} from '../../services/api';
import type {
  CodexAccount,
  TeamResponse,
  UserCreate,
  UserListParams,
  UserListResponse,
  UserResponse,
  UserRole,
  UserStatus,
  UserUpdate,
} from '../../services/api';
import { generateRandomPassword } from './generatePassword';

const PAGE_SIZE = 20;
const EMPTY_FILTERS: Required<Pick<UserListParams, 'q' | 'status' | 'role'>> = {
  q: '',
  role: '',
  status: '',
};

interface UserFormValues {
  displayName: string;
  email: string;
  password?: string;
  role: UserRole;
  status: UserStatus;
}

interface QuotaPolicyFormValues {
  teamId: string | null;
  fiveHourQuotaPercent: number | null;
  weeklyQuotaPercent: number | null;
}

function formatDate(value: string): string {
  const date = new Date(value);
  if (Number.isNaN(date.getTime())) return '—';
  return new Intl.DateTimeFormat('zh-CN', {
    dateStyle: 'medium',
    timeStyle: 'short',
  }).format(date);
}

function quotaRule() {
  return {
    validator: (_: unknown, value: unknown) => {
      if (
        value !== null &&
        value !== undefined &&
        (typeof value !== 'number' || !Number.isFinite(value) || value < 0 || value > 100)
      ) {
        return Promise.reject(new Error('请输入 0 到 100 的数字，或留空继承团队默认值'));
      }
      return Promise.resolve();
    },
  };
}

function formatEffectiveQuota(
  personalValue: number | null,
  teamValue: number | undefined,
): string {
  if (personalValue !== null) return `${personalValue}%（个人覆盖）`;
  if (teamValue !== undefined) return `${teamValue}%（团队默认）`;
  return '100%（系统默认）';
}

export default function UsersPage() {
  const { token } = theme.useToken();
  const { message, modal } = AntApp.useApp();
  const [form] = Form.useForm<UserFormValues>();
  const passwordValue = Form.useWatch('password', form);
  const [quotaForm] = Form.useForm<QuotaPolicyFormValues>();
  const [codexAccounts, setCodexAccounts] = useState<CodexAccount[]>([]);
  const [codexAccountsLoading, setCodexAccountsLoading] = useState(true);
  const [data, setData] = useState<UserListResponse | null>(null);
  const [draftFilters, setDraftFilters] = useState(EMPTY_FILTERS);
  const [editingUser, setEditingUser] = useState<UserResponse | null>(null);
  const [error, setError] = useState('');
  const [filters, setFilters] = useState(EMPTY_FILTERS);
  const [formOpen, setFormOpen] = useState(false);
  const [quotaEditingUser, setQuotaEditingUser] = useState<UserResponse | null>(null);
  const [quotaFormOpen, setQuotaFormOpen] = useState(false);
  const [quotaSaving, setQuotaSaving] = useState(false);
  const [teams, setTeams] = useState<TeamResponse[]>([]);
  const [teamsError, setTeamsError] = useState('');
  const [teamsLoading, setTeamsLoading] = useState(true);
  const [loading, setLoading] = useState(true);
  const [mutatingId, setMutatingId] = useState<string | null>(null);
  const [page, setPage] = useState(0);
  const [passwordVisible, setPasswordVisible] = useState(false);
  const [saving, setSaving] = useState(false);
  const currentUserId = getSession()?.user.id;

  const load = useCallback(async () => {
    setLoading(true);
    setError('');
    try {
      setData(await listUsers({ ...filters, page, size: PAGE_SIZE }));
    } catch (requestError) {
      setError(getErrorMessage(requestError));
    } finally {
      setLoading(false);
    }
  }, [filters, page]);

  useEffect(() => {
    void load();
  }, [load]);

  const loadCodexAccounts = useCallback(async () => {
    setCodexAccountsLoading(true);
    try {
      const firstPage = await listCodexAccounts({ page: 0, size: 100 });
      const pages = [firstPage];
      for (let nextPage = 1; nextPage < firstPage.totalPages; nextPage += 1) {
        pages.push(await listCodexAccounts({ page: nextPage, size: 100 }));
      }
      setCodexAccounts(pages.flatMap((pageData) => pageData.items));
    } catch (requestError) {
      message.error(`Codex 账户列表加载失败：${getErrorMessage(requestError)}`);
    } finally {
      setCodexAccountsLoading(false);
    }
  }, [message]);

  useEffect(() => {
    void loadCodexAccounts();
  }, [loadCodexAccounts]);

  const loadTeams = useCallback(async () => {
    setTeamsLoading(true);
    setTeamsError('');
    try {
      setTeams(await listTeams());
    } catch (requestError) {
      setTeamsError(getErrorMessage(requestError));
    } finally {
      setTeamsLoading(false);
    }
  }, []);

  useEffect(() => {
    void loadTeams();
  }, [loadTeams]);

  const teamById = useMemo(
    () => new Map(teams.map((team) => [team.id, team])),
    [teams],
  );

  const applyFilters = (values = draftFilters) => {
    setPage(0);
    setFilters({
      q: values.q.trim(),
      role: values.role,
      status: values.status,
    });
  };

  const openCreate = useCallback(() => {
    setEditingUser(null);
    form.resetFields();
    setPasswordVisible(false);
    form.setFieldsValue({ role: 'USER', status: 'ACTIVE' });
    setFormOpen(true);
  }, [form]);

  const openEdit = useCallback(
    (user: UserResponse) => {
      setEditingUser(user);
      form.resetFields();
      setPasswordVisible(false);
      form.setFieldsValue({
        displayName: user.displayName,
        email: user.email,
        password: undefined,
        role: user.role,
        status: user.status,
      });
      setFormOpen(true);
    },
    [form],
  );

  const closeUserForm = () => {
    setFormOpen(false);
    setPasswordVisible(false);
    form.resetFields();
  };

  const fillRandomPassword = () => {
    try {
      form.setFieldValue('password', generateRandomPassword());
      setPasswordVisible(true);
      message.success('随机密码已生成，请复制并妥善保存');
    } catch {
      message.error('当前浏览器无法安全生成密码，请更换浏览器后重试');
    }
  };

  const copyPassword = async () => {
    const password = form.getFieldValue('password');
    if (typeof password !== 'string' || !password) return;
    try {
      if (!navigator.clipboard?.writeText) throw new Error('Clipboard unavailable');
      await navigator.clipboard.writeText(password);
      message.success('密码已复制，请妥善保管');
    } catch {
      message.error('复制失败，请显示密码后手动复制');
    }
  };

  const saveUser = async () => {
    const values = await form.validateFields();
    setSaving(true);
    try {
      if (editingUser) {
        const update: UserUpdate = {
          displayName: values.displayName,
          email: values.email,
          role: values.role,
          status: values.status,
        };
        if (values.password) update.password = values.password;
        await updateUser(editingUser.id, update);
        message.success(values.password ? '用户已更新，旧登录已失效' : '用户已更新');
      } else {
        await createUser(values as UserCreate);
        message.success('用户已创建');
      }
      closeUserForm();
      await load();
    } catch (requestError) {
      if (requestError && typeof requestError === 'object' && 'errorFields' in requestError) {
        return;
      }
      message.error(getErrorMessage(requestError));
    } finally {
      setSaving(false);
    }
  };

  const confirmUpdate = useCallback(
    (user: UserResponse, changes: UserUpdate, description: string) => {
      setMutatingId(user.id);
      modal.confirm({
        cancelText: '取消',
        content: description,
        okText: '确认修改',
        onCancel: () => setMutatingId(null),
        onOk: async () => {
          try {
            await updateUser(user.id, changes);
            message.success('用户已更新');
            await load();
          } catch (requestError) {
            message.error(getErrorMessage(requestError));
            throw requestError;
          } finally {
            setMutatingId(null);
          }
        },
        title: '确认修改？',
      });
    },
    [load, message, modal],
  );

  const confirmForceLogout = useCallback(
    (user: UserResponse) => {
      setMutatingId(user.id);
      modal.confirm({
        cancelText: '取消',
        content: `这会立即使 ${user.email} 的全部登录失效。`,
        okText: '强制下线',
        onCancel: () => setMutatingId(null),
        onOk: async () => {
          try {
            await forceLogoutUser(user.id);
            message.success('该用户已强制下线');
          } catch (requestError) {
            message.error(getErrorMessage(requestError));
            throw requestError;
          } finally {
            setMutatingId(null);
          }
        },
        title: '强制下线用户？',
      });
    },
    [message, modal],
  );

  const confirmCodexAccountAssignment = useCallback(
    (user: UserResponse, codexAccountId?: string) => {
      if ((codexAccountId ?? null) === user.assignedCodexAccountId) return;
      const account = codexAccounts.find((item) => item.id === codexAccountId);
      setMutatingId(user.id);
      modal.confirm({
        cancelText: '取消',
        content: codexAccountId
          ? `将 ${account?.displayName ?? '所选 Codex 账户'} 分配给 ${user.email}？该用户现有会话会立即失效。`
          : `取消 ${user.email} 的 Codex 账户分配？该用户现有会话会立即失效。`,
        okText: codexAccountId ? '确认分配' : '取消分配',
        onCancel: () => setMutatingId(null),
        onOk: async () => {
          try {
            if (codexAccountId) {
              await assignCodexAccountToUser(user.id, codexAccountId);
              message.success('Codex 账户已分配');
            } else {
              await unassignCodexAccountFromUser(user.id);
              message.success('Codex 账户分配已取消');
            }
            await load();
          } catch (requestError) {
            message.error(getErrorMessage(requestError));
            throw requestError;
          } finally {
            setMutatingId(null);
          }
        },
        title: codexAccountId ? '分配 Codex 账户？' : '取消账户分配？',
      });
    },
    [codexAccounts, load, message, modal],
  );

  const openQuotaPolicy = useCallback(
    (user: UserResponse) => {
      setQuotaEditingUser(user);
      quotaForm.setFieldsValue({
        fiveHourQuotaPercent: user.fiveHourQuotaPercent,
        teamId: user.teamId,
        weeklyQuotaPercent: user.weeklyQuotaPercent,
      });
      setQuotaFormOpen(true);
    },
    [quotaForm],
  );

  const closeQuotaPolicy = useCallback(() => {
    setQuotaFormOpen(false);
    setQuotaEditingUser(null);
    quotaForm.resetFields();
  }, [quotaForm]);

  const saveQuotaPolicy = async () => {
    if (!quotaEditingUser) return;
    let values: QuotaPolicyFormValues;
    try {
      values = await quotaForm.validateFields();
    } catch {
      return;
    }

    const user = quotaEditingUser;
    modal.confirm({
      cancelText: '取消',
      content:
        '保存后会立即应用团队与个人额度覆盖，并使该用户现有会话失效。页面不会展示或推算 Codex 的真实用量。确定继续吗？',
      okText: '确认保存',
      onOk: async () => {
        setQuotaSaving(true);
        setMutatingId(user.id);
        try {
          await updateUserQuotaPolicy(user.id, {
            fiveHourQuotaPercent: values.fiveHourQuotaPercent ?? null,
            teamId: values.teamId || null,
            weeklyQuotaPercent: values.weeklyQuotaPercent ?? null,
          });
          message.success('用户额度策略已保存');
          closeQuotaPolicy();
          await load();
        } catch (requestError) {
          message.error(getErrorMessage(requestError));
          throw requestError;
        } finally {
          setQuotaSaving(false);
          setMutatingId(null);
        }
      },
      title: '确认保存额度策略？',
    });
  };

  const confirmDelete = useCallback(
    (user: UserResponse) => {
      setMutatingId(user.id);
      modal.confirm({
        cancelText: '取消',
        content: `删除后无法恢复：${user.email}`,
        okButtonProps: { danger: true },
        okText: '删除',
        onCancel: () => setMutatingId(null),
        onOk: async () => {
          try {
            await deleteUser(user.id);
            message.success('用户已删除');
            await load();
          } catch (requestError) {
            message.error(getErrorMessage(requestError));
            throw requestError;
          } finally {
            setMutatingId(null);
          }
        },
        title: '删除用户？',
      });
    },
    [load, message, modal],
  );

  const columns = useMemo<TableColumnsType<UserResponse>>(
    () => [
      {
        dataIndex: 'email',
        render: (_, user) => (
          <Space size={10}>
            <Avatar
              icon={<UserOutlined />}
              style={{ backgroundColor: token.colorPrimaryBg, color: token.colorPrimary }}
            />
            <span className="cqg-user-cell">
              <Typography.Text strong>{user.displayName || '未命名用户'}</Typography.Text>
              <Typography.Text type="secondary">{user.email}</Typography.Text>
            </span>
          </Space>
        ),
        title: '用户',
      },
      {
        dataIndex: 'role',
        render: (role: UserRole, user) => (
          <Select<UserRole>
            aria-label={`修改 ${user.email} 的角色`}
            disabled={mutatingId === user.id}
            onChange={(nextRole) => {
              if (nextRole !== role) {
                confirmUpdate(
                  user,
                  { role: nextRole },
                  `将 ${user.email} 的角色修改为 ${nextRole}？`,
                );
              }
            }}
            options={[
              { label: '用户', value: 'USER' },
              { label: '管理员', value: 'ADMIN' },
            ]}
            value={role}
          />
        ),
        title: '角色',
        width: 140,
      },
      {
        dataIndex: 'status',
        render: (status: UserStatus, user) => {
          const active = status === 'ACTIVE';
          return (
            <Space size={6}>
              <Tag
                color={active ? 'success' : 'default'}
                icon={active ? <CheckCircleOutlined /> : <StopOutlined />}
              >
                {active ? '启用' : '禁用'}
              </Tag>
              <Button
                aria-label={`${active ? '禁用' : '启用'} ${user.email}`}
                disabled={mutatingId === user.id}
                onClick={() =>
                  confirmUpdate(
                    user,
                    { status: active ? 'DISABLED' : 'ACTIVE' },
                    `确定${active ? '禁用' : '启用'} ${user.email} 吗？`,
                  )
                }
                size="small"
                type="link"
              >
                {active ? '禁用' : '启用'}
              </Button>
            </Space>
          );
        },
        title: '状态',
        width: 170,
      },
      {
        dataIndex: 'assignedCodexAccountId',
        render: (accountId: string | null, user) => (
          <Select<string>
            allowClear
            aria-label={`分配 ${user.email} 的 Codex 账户`}
            disabled={mutatingId === user.id}
            loading={codexAccountsLoading}
            onChange={(nextAccountId) =>
              confirmCodexAccountAssignment(user, nextAccountId)
            }
            options={codexAccounts.map((account) => ({
              disabled: account.status !== 'ACTIVE' && account.id !== accountId,
              label: `${account.displayName} · ${account.email}${
                account.status === 'DISABLED' ? '（已停用）' : ''
              }`,
              value: account.id,
            }))}
            placeholder="未分配"
            showSearch
            optionFilterProp="label"
            style={{ minWidth: 230 }}
            value={accountId ?? undefined}
          />
        ),
        title: 'Codex 账户',
        width: 270,
      },
      {
        dataIndex: 'teamId',
        render: (teamId: string | null, user) => {
          const team = teamId ? teamById.get(teamId) : undefined;
          const teamPolicyLoading = Boolean(teamId) && teamsLoading;
          const teamPolicyUnavailable =
            Boolean(teamId) &&
            !teamsLoading &&
            (Boolean(teamsError) || !team);
          return (
            <Space align="start" orientation="vertical" size={2}>
              <Typography.Text>
                {teamPolicyLoading
                  ? '团队加载中…'
                  : teamPolicyUnavailable
                    ? '团队策略不可用'
                    : teamsError
                      ? '团队列表不可用'
                      : team?.name ?? '未分配团队'}
              </Typography.Text>
              <Typography.Text type="secondary">
                {teamPolicyLoading
                  ? '额度策略加载中…'
                  : teamPolicyUnavailable
                    ? '额度策略暂不可用'
                    : `5 小时 ${formatEffectiveQuota(
                        user.fiveHourQuotaPercent,
                        team?.fiveHourQuotaPercent,
                      )} · 7 天 ${formatEffectiveQuota(
                        user.weeklyQuotaPercent,
                        team?.weeklyQuotaPercent,
                      )}`}
              </Typography.Text>
              <Button
                aria-label={`配置 ${user.email} 的额度策略`}
                disabled={mutatingId === user.id || teamsLoading}
                icon={<SlidersOutlined />}
                onClick={() => openQuotaPolicy(user)}
                size="small"
                type="link"
              >
                配置团队/个人额度
              </Button>
            </Space>
          );
        },
        title: '团队与额度策略',
        width: 260,
      },
      {
        dataIndex: 'createdAt',
        render: (value: string) => formatDate(value),
        title: '创建时间',
        width: 180,
      },
      {
        fixed: 'right',
        render: (_, user) => {
          const self = user.id === currentUserId;
          return (
            <Dropdown
              menu={{
                items: [
                  {
                    icon: <EditOutlined />,
                    key: 'edit',
                    label: '编辑',
                    onClick: () => openEdit(user),
                  },
                  {
                    icon: <SlidersOutlined />,
                    key: 'quota',
                    label: '配置团队/个人额度',
                    onClick: () => openQuotaPolicy(user),
                  },
                  {
                    disabled: self,
                    icon: <PoweroffOutlined />,
                    key: 'offline',
                    label: '强制下线',
                    onClick: () => confirmForceLogout(user),
                  },
                  { type: 'divider' },
                  {
                    danger: true,
                    disabled: self,
                    icon: <DeleteOutlined />,
                    key: 'delete',
                    label: '删除',
                    onClick: () => confirmDelete(user),
                  },
                ],
              }}
              trigger={['click']}
            >
              <Button
                aria-label={`管理 ${user.email}`}
                icon={<MoreOutlined />}
                loading={mutatingId === user.id}
                type="text"
              />
            </Dropdown>
          );
        },
        title: '操作',
        width: 80,
      },
    ],
    [
      confirmDelete,
      confirmCodexAccountAssignment,
      confirmForceLogout,
      confirmUpdate,
      codexAccounts,
      codexAccountsLoading,
      currentUserId,
      mutatingId,
      openEdit,
      openQuotaPolicy,
      teamById,
      teamsLoading,
      teamsError,
      token.colorPrimary,
      token.colorPrimaryBg,
    ],
  );

  return (
    <PageContainer
      content="管理用户、登录会话、团队归属与个人额度覆盖。额度字段是策略百分比，不代表实时用量。"
      extra={[
        <Button icon={<PlusOutlined />} key="create" onClick={openCreate} type="primary">
          新增用户
        </Button>,
      ]}
      title="用户管理"
    >
      <ProCard className="cqg-users-card">
        <Form
          aria-label="用户筛选"
          className="cqg-filter-form"
          layout="inline"
          onFinish={() => applyFilters()}
        >
          <Form.Item label="搜索">
            <Input
              allowClear
              aria-label="搜索用户"
              onChange={(event) =>
                setDraftFilters((current) => ({ ...current, q: event.target.value }))
              }
              onPressEnter={() => applyFilters()}
              placeholder="邮箱或姓名"
              value={draftFilters.q}
            />
          </Form.Item>
          <Form.Item label="状态">
            <Select<UserStatus | ''>
              allowClear
              aria-label="按状态筛选"
              onChange={(value) =>
                setDraftFilters((current) => ({ ...current, status: value ?? '' }))
              }
              options={[
                { label: '启用', value: 'ACTIVE' },
                { label: '禁用', value: 'DISABLED' },
              ]}
              placeholder="全部状态"
              value={draftFilters.status || undefined}
            />
          </Form.Item>
          <Form.Item label="角色">
            <Select<UserRole | ''>
              allowClear
              aria-label="按角色筛选"
              onChange={(value) =>
                setDraftFilters((current) => ({ ...current, role: value ?? '' }))
              }
              options={[
                { label: '用户', value: 'USER' },
                { label: '管理员', value: 'ADMIN' },
              ]}
              placeholder="全部角色"
              value={draftFilters.role || undefined}
            />
          </Form.Item>
          <Space>
            <Button htmlType="submit" icon={<SearchOutlined />} type="primary">
              查询
            </Button>
            <Button
              onClick={() => {
                setDraftFilters(EMPTY_FILTERS);
                applyFilters(EMPTY_FILTERS);
              }}
            >
              重置
            </Button>
          </Space>
        </Form>
        {error ? (
          <Alert
            action={<Button onClick={() => void load()}>重试</Button>}
            className="cqg-inline-alert"
            closable
            title="用户列表加载失败"
            onClose={() => setError('')}
            showIcon
            type="error"
          />
        ) : null}
        {teamsError ? (
          <Alert
            action={<Button onClick={() => void loadTeams()}>重试</Button>}
            className="cqg-inline-alert"
            description={teamsError}
            title="团队列表加载失败，暂时无法配置额度策略"
            showIcon
            type="error"
          />
        ) : null}
        <Table<UserResponse>
          className="cqg-users-table"
          columns={columns}
          dataSource={data?.items ?? []}
          loading={loading}
          locale={{
            emptyText: (
              <Empty description="暂无用户" image={Empty.PRESENTED_IMAGE_SIMPLE} />
            ),
          }}
          pagination={
            data
              ? {
                  current: data.page + 1,
                  onChange: (nextPage) => setPage(nextPage - 1),
                  pageSize: data.size || PAGE_SIZE,
                  showSizeChanger: false,
                  total: data.totalElements,
                }
              : false
          }
          rowKey="id"
          scroll={{ x: 1430 }}
        />
      </ProCard>

      <Modal
        forceRender
        okButtonProps={{ loading: saving }}
        okText={editingUser ? '保存' : '创建'}
        onCancel={closeUserForm}
        onOk={() => void saveUser()}
        open={formOpen}
        title={editingUser ? '编辑用户' : '新增用户'}
      >
        <Form form={form} layout="vertical" preserve={false}>
          <Form.Item
            label="邮箱"
            name="email"
            rules={[
              { message: '请输入邮箱', required: true },
              { message: '邮箱格式不正确', type: 'email' },
            ]}
          >
            <Input autoComplete="off" maxLength={320} />
          </Form.Item>
          <Form.Item
            label="姓名"
            name="displayName"
            rules={[{ message: '请输入姓名', required: true }]}
          >
            <Input maxLength={120} />
          </Form.Item>
          <Form.Item
            extra={editingUser ? '留空则保持原密码；修改后该用户需要重新登录' : undefined}
            label="密码"
            name="password"
            rules={[
              { message: '请输入密码', required: !editingUser },
              { message: '密码至少 12 位', min: 12 },
              { max: 128, message: '密码不能超过 128 位' },
            ]}
          >
            <Input.Password
              autoComplete="new-password"
              visibilityToggle={{
                visible: passwordVisible,
                onVisibleChange: setPasswordVisible,
              }}
            />
          </Form.Item>
          {editingUser ? (
            <Space style={{ marginBottom: 20 }}>
              <Button htmlType="button" onClick={fillRandomPassword}>
                生成随机密码
              </Button>
              <Button
                disabled={!passwordValue}
                htmlType="button"
                onClick={() => void copyPassword()}
              >
                复制密码
              </Button>
            </Space>
          ) : null}
          <Space align="start" size={16}>
            <Form.Item
              label="角色"
              name="role"
              rules={[{ message: '请选择角色', required: true }]}
            >
              <Select
                options={[
                  { label: '用户', value: 'USER' },
                  { label: '管理员', value: 'ADMIN' },
                ]}
                style={{ width: 160 }}
              />
            </Form.Item>
            <Form.Item
              label="状态"
              name="status"
              rules={[{ message: '请选择状态', required: true }]}
            >
              <Select
                options={[
                  { label: '启用', value: 'ACTIVE' },
                  { label: '禁用', value: 'DISABLED' },
                ]}
                style={{ width: 160 }}
              />
            </Form.Item>
          </Space>
        </Form>
      </Modal>

      <Modal
        forceRender
        okButtonProps={{ loading: quotaSaving }}
        okText="保存额度策略"
        onCancel={closeQuotaPolicy}
        onOk={() => void saveQuotaPolicy()}
        open={quotaFormOpen}
        title={quotaEditingUser ? `配置 ${quotaEditingUser.email} 的额度策略` : '配置额度策略'}
      >
        <Form form={quotaForm} layout="vertical" preserve={false}>
          <Form.Item
            extra={teamsError ? '团队列表暂不可用，请先重试。' : '用户会继承所选团队的默认额度。'}
            label="所属团队"
            name="teamId"
          >
            <Select
              allowClear
              disabled={teamsLoading || Boolean(teamsError)}
              loading={teamsLoading}
              options={teams.map((team) => ({
                disabled: team.status !== 'ACTIVE' && team.id !== quotaEditingUser?.teamId,
                label: `${team.name}${team.status === 'DISABLED' ? '（已停用）' : ''}`,
                value: team.id,
              }))}
              placeholder="不分配团队"
              showSearch
              optionFilterProp="label"
            />
          </Form.Item>
          <Space align="start" size={16}>
            <Form.Item
              extra="留空表示继承团队默认"
              label="5 小时个人覆盖"
              name="fiveHourQuotaPercent"
              rules={[quotaRule()]}
            >
              <InputNumber
                suffix="%"
                style={{ width: 180 }}
              />
            </Form.Item>
            <Form.Item
              extra="留空表示继承团队默认"
              label="7 天个人覆盖"
              name="weeklyQuotaPercent"
              rules={[quotaRule()]}
            >
              <InputNumber
                suffix="%"
                style={{ width: 180 }}
              />
            </Form.Item>
          </Space>
        </Form>
        <Alert
          className="cqg-inline-alert"
          title="保存会使该用户当前会话失效"
          description="额度策略只描述允许的百分比窗口；后台不会假装显示 Codex 实时用量。"
          showIcon
          type="warning"
        />
      </Modal>
    </PageContainer>
  );
}
