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
  createUser,
  deleteUser,
  forceLogoutUser,
  getSession,
  getUserOfficialQuota,
  getUserMeterDevices,
  getUserQuotaOverview,
  listTeams,
  listUsers,
  updateUserQuotaPolicy,
  updateUserTokenLimits,
  updateUser,
} from '../../services/api';
import type {
  TeamResponse,
  OfficialQuotaSnapshot,
  MeterDeviceStatus,
  UserQuotaOverview,
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
const MAX_TOKEN_LIMIT = 1_000_000_000_000;
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
  fiveHourTokenLimit: number | null;
  weeklyTokenLimit: number | null;
}

function formatDate(value: string): string {
  const date = new Date(value);
  if (Number.isNaN(date.getTime())) return '—';
  return new Intl.DateTimeFormat('zh-CN', {
    dateStyle: 'medium',
    timeStyle: 'short',
  }).format(date);
}

function tokenLimitRule() {
  return {
    validator: (_: unknown, value: unknown) => {
      if (value === null || value === undefined || value === '') return Promise.resolve();
      if (
        typeof value !== 'number' ||
        !Number.isFinite(value) ||
        value < 0 ||
        value > MAX_TOKEN_LIMIT ||
        !Number.isInteger(value)
      ) {
        return Promise.reject(
          new Error('请输入 0 到 1,000,000,000,000 的整数，或留空继承团队 Token 上限'),
        );
      }
      return Promise.resolve();
    },
  };
}

function formatTokenLimit(value: number): string {
  return new Intl.NumberFormat('zh-CN').format(value);
}

function formatEffectiveTokenLimit(
  personalValue: number | null,
  teamValue: number | null | undefined,
): string {
  if (personalValue !== null) return `${formatTokenLimit(personalValue)} Token（个人上限）`;
  if (teamValue !== null && teamValue !== undefined) {
    return `${formatTokenLimit(teamValue)} Token（团队上限）`;
  }
  return '未配置';
}

export default function UsersPage() {
  const { token } = theme.useToken();
  const { message, modal } = AntApp.useApp();
  const [form] = Form.useForm<UserFormValues>();
  const passwordValue = Form.useWatch('password', form);
  const [quotaForm] = Form.useForm<QuotaPolicyFormValues>();
  const [data, setData] = useState<UserListResponse | null>(null);
  const [draftFilters, setDraftFilters] = useState(EMPTY_FILTERS);
  const [editingUser, setEditingUser] = useState<UserResponse | null>(null);
  const [error, setError] = useState('');
  const [filters, setFilters] = useState(EMPTY_FILTERS);
  const [formOpen, setFormOpen] = useState(false);
  const [quotaEditingUser, setQuotaEditingUser] = useState<UserResponse | null>(null);
  const [quotaFormOpen, setQuotaFormOpen] = useState(false);
  const [quotaSaving, setQuotaSaving] = useState(false);
  const [quotaViewUser, setQuotaViewUser] = useState<UserResponse | null>(null);
  const [quotaViewLoading, setQuotaViewLoading] = useState(false);
  const [quotaViewError, setQuotaViewError] = useState('');
  const [officialQuota, setOfficialQuota] = useState<OfficialQuotaSnapshot | null>(null);
  const [personalQuota, setPersonalQuota] = useState<UserQuotaOverview | null>(null);
  const [meterDevices, setMeterDevices] = useState<MeterDeviceStatus[]>([]);
  const [teams, setTeams] = useState<TeamResponse[]>([]);
  const [teamsError, setTeamsError] = useState('');
  const [teamsLoading, setTeamsLoading] = useState(true);
  const [loading, setLoading] = useState(true);
  const [mutatingId, setMutatingId] = useState<string | null>(null);
  const [page, setPage] = useState(0);
  const [passwordVisible, setPasswordVisible] = useState(false);
  const [saving, setSaving] = useState(false);
  const currentUserId = getSession()?.user.id;

  const openQuotaView = useCallback(async (user: UserResponse) => {
    setQuotaViewUser(user);
    setQuotaViewLoading(true);
    setQuotaViewError('');
    setOfficialQuota(null);
    setPersonalQuota(null);
    setMeterDevices([]);
    try {
      const [official, personal, devices] = await Promise.all([
        getUserOfficialQuota(user.id),
        getUserQuotaOverview(user.id),
        getUserMeterDevices(user.id),
      ]);
      setOfficialQuota(official);
      setPersonalQuota(personal);
      setMeterDevices(devices);
    } catch (requestError) {
      setQuotaViewError(getErrorMessage(requestError));
    } finally {
      setQuotaViewLoading(false);
    }
  }, []);

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

  const openQuotaPolicy = useCallback(
    (user: UserResponse) => {
      setQuotaEditingUser(user);
      quotaForm.setFieldsValue({
        fiveHourTokenLimit: user.fiveHourTokenLimit,
        teamId: user.teamId,
        weeklyTokenLimit: user.weeklyTokenLimit,
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
      title: '确认保存 Token 配额？',
      content:
        '保存后会立即应用团队归属与个人 Token 上限，并使该用户现有会话失效。Token 上限是本系统配额，与 Codex 官方账户百分比无关。确定继续吗？',
      okText: '确认保存',
      onOk: async () => {
        setQuotaSaving(true);
        setMutatingId(user.id);
        let teamPolicySaved = false;
        try {
          await updateUserQuotaPolicy(user.id, {
            fiveHourQuotaPercent: null,
            teamId: values.teamId || null,
            weeklyQuotaPercent: null,
          });
          teamPolicySaved = true;
          await updateUserTokenLimits(user.id, {
            fiveHourTokenLimit: values.fiveHourTokenLimit ?? null,
            weeklyTokenLimit: values.weeklyTokenLimit ?? null,
          });
          message.success('用户团队归属与 Token 配额已保存');
          closeQuotaPolicy();
          await load();
        } catch (requestError) {
          if (teamPolicySaved) {
            message.warning('用户团队归属已保存，但个人 Token 上限保存失败；请刷新后重试。');
            closeQuotaPolicy();
            await load();
            return;
          }
          message.error(getErrorMessage(requestError));
          throw requestError;
        } finally {
          setQuotaSaving(false);
          setMutatingId(null);
        }
      },
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
                    ? '团队 Token 配额不可用'
                    : teamsError
                      ? '团队列表不可用'
                      : team?.name ?? '未分配团队'}
              </Typography.Text>
              <Typography.Text type="secondary">
                {teamPolicyLoading
                  ? '额度策略加载中…'
                  : teamPolicyUnavailable
                    ? 'Token 配额暂不可用'
                    : `5 小时 ${formatEffectiveTokenLimit(
                        user.fiveHourTokenLimit,
                        team?.fiveHourTokenLimit,
                      )} · 7 天 ${formatEffectiveTokenLimit(
                        user.weeklyTokenLimit,
                        team?.weeklyTokenLimit,
                      )}`}
              </Typography.Text>
              <Button
                aria-label={`配置 ${user.email} 的团队与 Token 配额`}
                disabled={mutatingId === user.id || teamsLoading}
                icon={<SlidersOutlined />}
                onClick={() => openQuotaPolicy(user)}
                size="small"
                type="link"
              >
                配置团队/个人 Token 配额
              </Button>
            </Space>
          );
        },
        title: '团队与 Token 配额',
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
                    label: '配置团队/个人 Token 配额',
                    onClick: () => openQuotaPolicy(user),
                  },
                  {
                    key: 'quota-view',
                    label: '查看额度',
                    onClick: () => void openQuotaView(user),
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
      confirmForceLogout,
      confirmUpdate,
      currentUserId,
      mutatingId,
      openEdit,
      openQuotaPolicy,
      openQuotaView,
      teamById,
      teamsLoading,
      teamsError,
      token.colorPrimary,
      token.colorPrimaryBg,
    ],
  );

  return (
    <PageContainer
      content="管理系统用户、团队与个人 Token 限额；官方 Codex 额度由各客户端本机登录后上报。"
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
            title="团队列表加载失败，暂时无法配置团队归属"
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
          scroll={{ x: 1160 }}
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
        okText="保存 Token 配额"
        onCancel={closeQuotaPolicy}
        onOk={() => void saveQuotaPolicy()}
        open={quotaFormOpen}
        title={quotaEditingUser ? `配置 ${quotaEditingUser.email} 的团队与 Token 配额` : '配置团队与 Token 配额'}
      >
        <Form form={quotaForm} layout="vertical" preserve={false}>
          <Form.Item
            extra={teamsError ? '团队列表暂不可用，请先重试。' : '用户会继承所选团队的 Token 上限。'}
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
              extra="留空表示继承团队 Token 上限"
              label="5 小时个人 Token 上限"
              name="fiveHourTokenLimit"
              rules={[tokenLimitRule()]}
            >
              <InputNumber
                max={MAX_TOKEN_LIMIT}
                min={0}
                precision={0}
                suffix="Token"
                style={{ width: 180 }}
              />
            </Form.Item>
            <Form.Item
              extra="留空表示继承团队 Token 上限"
              label="7 天个人 Token 上限"
              name="weeklyTokenLimit"
              rules={[tokenLimitRule()]}
            >
              <InputNumber
                max={MAX_TOKEN_LIMIT}
                min={0}
                precision={0}
                suffix="Token"
                style={{ width: 180 }}
              />
            </Form.Item>
          </Space>
        </Form>
        <Alert
          className="cqg-inline-alert"
          title="保存会使该用户当前会话失效"
          description="Token 上限是本系统的个人统计配额；不会退出用户的官方 Codex 应用或 CLI。"
          showIcon
          type="warning"
        />
      </Modal>

      <Modal
        footer={null}
        onCancel={() => setQuotaViewUser(null)}
        open={quotaViewUser !== null}
        title={quotaViewUser ? `${quotaViewUser.displayName} 的额度` : '用户额度'}
      >
        {quotaViewLoading ? <Typography.Text>正在读取额度…</Typography.Text> : null}
        {quotaViewError ? <Alert showIcon title="额度读取失败" description={quotaViewError} type="error" /> : null}
        {!quotaViewLoading && !quotaViewError ? (
          <Space align="start" orientation="vertical" size={12}>
            <Typography.Title level={5}>常驻计费服务</Typography.Title>
            <Typography.Text>
              Rust 常驻计费：{meterDevices.length === 0 ? '未配对'
                : meterDevices.some((device) => device.online) ? '在线' : '离线'}
            </Typography.Text>
            {meterDevices[0]?.lastSeenAt ? (
              <Typography.Text type="secondary">
                最近心跳：{formatDate(meterDevices[0].lastSeenAt)}
              </Typography.Text>
            ) : null}
            <Typography.Title level={5}>官方额度（客户端上报）</Typography.Title>
            {officialQuota ? (
              <>
                <Typography.Text>套餐：{officialQuota.planType ?? '未知'}</Typography.Text>
                <Typography.Text>
                  官方 5 小时：{officialQuota.windows.find((window) => window.windowDurationMins === 300)
                    ? `已使用 ${officialQuota.windows.find((window) => window.windowDurationMins === 300)!.usedPercent}%`
                    : '官方未提供此窗口'}
                </Typography.Text>
                <Typography.Text>
                  官方 7 天：{officialQuota.windows.find((window) => window.windowDurationMins === 10_080)
                    ? `已使用 ${officialQuota.windows.find((window) => window.windowDurationMins === 10_080)!.usedPercent}%`
                    : '官方未提供此窗口'}
                </Typography.Text>
                <Typography.Text type="secondary">
                  最近上报：{formatDate(officialQuota.receivedAt)}
                  {Date.now() - new Date(officialQuota.receivedAt).getTime() > 5 * 60_000
                    ? ' · 历史快照（非实时）' : ' · 近期上报'}
                </Typography.Text>
              </>
            ) : <Typography.Text type="secondary">暂无客户端上报的官方额度</Typography.Text>}
            <Typography.Title level={5}>个人 Token 用量（本机日志上报）</Typography.Title>
            {personalQuota ? (
              <>
                <Typography.Text>
                  {`个人 5 小时：已用 ${formatTokenLimit(personalQuota.usage.fiveHourUsedTokens)} Token，剩余 ${personalQuota.usage.fiveHourRemainingTokens === null
                    ? '未配置' : `${formatTokenLimit(personalQuota.usage.fiveHourRemainingTokens)} Token`}`}
                </Typography.Text>
                <Typography.Text>
                  {`个人 7 天：已用 ${formatTokenLimit(personalQuota.usage.weeklyUsedTokens)} Token，剩余 ${personalQuota.usage.weeklyRemainingTokens === null
                    ? '未配置' : `${formatTokenLimit(personalQuota.usage.weeklyRemainingTokens)} Token`}`}
                </Typography.Text>
              </>
            ) : <Typography.Text type="secondary">暂无个人额度</Typography.Text>}
            <Typography.Text type="secondary">数据为客户端报告，不能强制限制官方应用的使用。</Typography.Text>
          </Space>
        ) : null}
      </Modal>
    </PageContainer>
  );
}
