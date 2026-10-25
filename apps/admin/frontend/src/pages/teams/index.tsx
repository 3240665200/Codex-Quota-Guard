import {
  CheckCircleOutlined,
  DeleteOutlined,
  EditOutlined,
  PlusOutlined,
  ReloadOutlined,
  StopOutlined,
  TeamOutlined,
} from '@ant-design/icons';
import { PageContainer, ProCard } from '@ant-design/pro-components';
import {
  Alert,
  App as AntApp,
  Button,
  Empty,
  Form,
  Input,
  InputNumber,
  Modal,
  Select,
  Space,
  Table,
  Tag,
  Tooltip,
  Typography,
} from 'antd';
import type { TableColumnsType } from 'antd';
import { useCallback, useEffect, useMemo, useState } from 'react';

import { getErrorMessage } from '../../components/AdminState';
import { TokenAmount } from '../../components/TokenAmount';
import TeamMembersModal from './TeamMembersModal';
import {
  createTeam,
  deleteTeam,
  getTeamQuotaOverview,
  listOfficialAccounts,
  listTeams,
  updateTeam,
  updateTeamTokenLimits,
} from '../../services/api';
import type {
  TeamCreate,
  TeamResponse,
  TeamQuotaOverview,
  TeamStatus,
  TeamUpdate,
  OfficialAccountResponse,
} from '../../services/api';

const LEGACY_DEFAULT_QUOTA_PERCENT = 100;
const MAX_TOKEN_LIMIT = 1_000_000_000_000;

interface TeamFormValues {
  name: string;
  description: string;
  status: TeamStatus;
  fiveHourTokenLimit: number | null;
  weeklyTokenLimit: number | null;
  totalTokenLimit: number | null;
  officialAccountId: string | null;
}

function formatDate(value: string): string {
  const date = new Date(value);
  return Number.isNaN(date.getTime()) ? '—' : date.toLocaleString('zh-CN');
}

function describeTokenLimitSaveFailure(error: unknown): string {
  const status = error && typeof error === 'object' && 'status' in error
    ? (error as { status: unknown }).status
    : null;
  switch (status) {
    case 400:
      return '请求格式无效，请刷新后重试';
    case 401:
      return '管理员登录已失效，请重新登录';
    case 403:
      return '当前账户没有管理员权限';
    case 404:
      return '后台缺少 Token 上限接口，请重启最新后台';
    case 409:
      return '团队版本冲突，请刷新后重试';
    default:
      return '后台或网络异常，请稍后重试';
  }
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
          new Error('请输入 0 到 1,000,000,000,000 的整数，或留空表示不设限'),
        );
      }
      return Promise.resolve();
    },
  };
}

export default function TeamsPage() {
  const { message, modal } = AntApp.useApp();
  const [form] = Form.useForm<TeamFormValues>();
  const [busyId, setBusyId] = useState<string | null>(null);
  const [data, setData] = useState<TeamResponse[] | null>(null);
  const [officialAccounts, setOfficialAccounts] = useState<OfficialAccountResponse[]>([]);
  const [quotaByTeam, setQuotaByTeam] = useState<Record<string, TeamQuotaOverview>>({});
  const [editing, setEditing] = useState<TeamResponse | null>(null);
  const [managingMembers, setManagingMembers] = useState<TeamResponse | null>(null);
  const [error, setError] = useState('');
  const [formOpen, setFormOpen] = useState(false);
  const [loading, setLoading] = useState(true);
  const [saving, setSaving] = useState(false);
  const selectedOfficialAccountId = Form.useWatch('officialAccountId', form);

  const load = useCallback(async (quiet = false) => {
    if (!quiet) setLoading(true);
    setError('');
    try {
      const [teams, accounts] = await Promise.all([listTeams(), listOfficialAccounts()]);
      const quotas = await Promise.all(teams.map((team) => getTeamQuotaOverview(team.id)));
      setData(teams);
      setOfficialAccounts(accounts);
      setQuotaByTeam(Object.fromEntries(quotas.map((quota) => [quota.id, quota])));
    } catch (requestError) {
      setError(getErrorMessage(requestError));
    } finally {
      if (!quiet) setLoading(false);
    }
  }, []);

  useEffect(() => {
    void load();
    const timer = window.setInterval(() => { void load(true); }, 30_000);
    return () => window.clearInterval(timer);
  }, [load]);

  const openCreate = useCallback(() => {
    setEditing(null);
    form.resetFields();
    form.setFieldsValue({
      description: '',
      fiveHourTokenLimit: null,
      name: '',
      officialAccountId: null,
      status: 'ACTIVE',
      totalTokenLimit: null,
      weeklyTokenLimit: null,
    });
    setFormOpen(true);
  }, [form]);

  const openEdit = useCallback(
    (team: TeamResponse) => {
      setEditing(team);
      form.setFieldsValue({
        description: team.description,
        fiveHourTokenLimit: team.fiveHourTokenLimit,
        name: team.name,
        officialAccountId: team.officialAccountId,
        status: team.status,
        totalTokenLimit: team.totalTokenLimit,
        weeklyTokenLimit: team.weeklyTokenLimit,
      });
      setFormOpen(true);
    },
    [form],
  );

  const closeForm = useCallback(() => {
    setFormOpen(false);
    setEditing(null);
    form.resetFields();
  }, [form]);

  const saveTeam = async () => {
    let values: TeamFormValues;
    try {
      values = await form.validateFields();
    } catch {
      return;
    }

    modal.confirm({
      cancelText: '取消',
      content: editing
        ? '保存后会更新团队 Token 上限，并使相关成员系统会话失效。确定继续吗？'
        : '创建团队后会立即保存 Token 上限。确定创建吗？',
      okText: editing ? '确认保存' : '确认创建',
      onOk: async () => {
        setSaving(true);
        try {
          const { fiveHourTokenLimit, weeklyTokenLimit, totalTokenLimit, officialAccountId, ...teamValues } = values;
          const account = officialAccounts.find((item) => item.id === officialAccountId);
          const effectiveTotalLimit = account ? account.estimatedTotalTokens : totalTokenLimit ?? null;
          if (editing) {
            const update: TeamUpdate = {
              ...teamValues,
              version: editing.version,
            };
            const updatedTeam = await updateTeam(editing.id, update);
            try {
              await updateTeamTokenLimits(editing.id, {
                fiveHourTokenLimit: fiveHourTokenLimit ?? null,
                totalTokenLimit: effectiveTotalLimit,
                officialAccountId: officialAccountId ?? null,
                version: updatedTeam.version,
                weeklyTokenLimit: weeklyTokenLimit ?? null,
              });
            } catch (tokenLimitError) {
              message.warning(`团队基本信息已保存，但 Token 上限保存失败：${describeTokenLimitSaveFailure(tokenLimitError)}。`);
              closeForm();
              await load();
              return;
            }
            message.success('团队与 Token 上限已更新');
          } else {
            const create: TeamCreate = {
              ...teamValues,
              fiveHourQuotaPercent: LEGACY_DEFAULT_QUOTA_PERCENT,
              weeklyQuotaPercent: LEGACY_DEFAULT_QUOTA_PERCENT,
            };
            const createdTeam = await createTeam(create);
            try {
              await updateTeamTokenLimits(createdTeam.id, {
                fiveHourTokenLimit: fiveHourTokenLimit ?? null,
                totalTokenLimit: effectiveTotalLimit,
                officialAccountId: officialAccountId ?? null,
                version: createdTeam.version,
                weeklyTokenLimit: weeklyTokenLimit ?? null,
              });
            } catch (tokenLimitError) {
              message.warning('团队已创建，但 Token 上限保存失败，请在编辑团队中重试。');
              closeForm();
              await load();
              return;
            }
            message.success('团队与 Token 上限已创建');
          }
          closeForm();
          await load();
        } catch (requestError) {
          message.error(getErrorMessage(requestError));
          throw requestError;
        } finally {
          setSaving(false);
        }
      },
      title: editing ? '确认保存团队策略？' : '确认创建团队？',
    });
  };

  const toggleTeam = useCallback(
    (team: TeamResponse) => {
      const nextStatus: TeamStatus = team.status === 'ACTIVE' ? 'DISABLED' : 'ACTIVE';
      modal.confirm({
        cancelText: '取消',
        content:
          nextStatus === 'DISABLED'
            ? `停用后 ${team.memberCount} 位成员将不能继续使用该团队策略，相关会话会失效。`
            : '启用后该团队成员将恢复使用团队策略。',
        okText: nextStatus === 'ACTIVE' ? '启用团队' : '停用团队',
        onOk: async () => {
          setBusyId(team.id);
          try {
            await updateTeam(team.id, { status: nextStatus, version: team.version });
            message.success(nextStatus === 'ACTIVE' ? '团队已启用' : '团队已停用');
            await load();
          } catch (requestError) {
            message.error(getErrorMessage(requestError));
            throw requestError;
          } finally {
            setBusyId(null);
          }
        },
        title: nextStatus === 'ACTIVE' ? '启用团队？' : '停用团队？',
      });
    },
    [load, message, modal],
  );

  const confirmDelete = useCallback(
    (team: TeamResponse) => {
      modal.confirm({
        cancelText: '取消',
        content:
          team.memberCount > 0
            ? `该团队仍有 ${team.memberCount} 位成员。删除后将清除团队关联，且无法恢复，确定继续吗？`
            : `删除团队「${team.name}」后无法恢复，确定继续吗？`,
        okButtonProps: { danger: true },
        okText: '删除团队',
        onOk: async () => {
          setBusyId(team.id);
          try {
            await deleteTeam(team.id);
            message.success('团队已删除');
            await load();
          } catch (requestError) {
            message.error(getErrorMessage(requestError));
            throw requestError;
          } finally {
            setBusyId(null);
          }
        },
        title: '删除团队？',
      });
    },
    [load, message, modal],
  );

  const columns = useMemo<TableColumnsType<TeamResponse>>(
    () => [
      {
        dataIndex: 'name',
        render: (value: string, team) => (
          <Space size={10}>
            <TeamOutlined style={{ color: '#2563eb', fontSize: 20 }} />
            <span className="cqg-user-cell">
              <Typography.Text strong>{value}</Typography.Text>
              <Typography.Text ellipsis type="secondary">
                {team.description || '暂无描述'}
              </Typography.Text>
            </span>
          </Space>
        ),
        title: '团队',
      },
      {
        dataIndex: 'status',
        render: (value: TeamStatus) =>
          value === 'ACTIVE' ? (
            <Tag color="success" icon={<CheckCircleOutlined />}>
              启用
            </Tag>
          ) : (
            <Tag icon={<StopOutlined />}>停用</Tag>
          ),
        title: '状态',
        width: 100,
      },
      {
        dataIndex: 'fiveHourTokenLimit',
        render: (value: number | null, team) => <Space orientation="vertical" size={0}>
          <span>上限 {value === null ? '无限制' : <TokenAmount value={value} />}</span>
          <Typography.Text type="secondary">已用 <TokenAmount value={quotaByTeam[team.id]?.fiveHourUsedTokens} /></Typography.Text>
          <Typography.Text type={quotaByTeam[team.id]?.fiveHourRemainingTokens === 0 ? 'danger' : 'secondary'}>
            剩余 {value === null ? '无限制' : <TokenAmount value={quotaByTeam[team.id]?.fiveHourRemainingTokens} />}
          </Typography.Text>
        </Space>,
        title: '5 小时团队共享额度',
        width: 185,
      },
      {
        dataIndex: 'weeklyTokenLimit',
        render: (value: number | null, team) => <Space orientation="vertical" size={0}>
          <span>上限 {value === null ? '无限制' : <TokenAmount value={value} />}</span>
          <Typography.Text type="secondary">已用 <TokenAmount value={quotaByTeam[team.id]?.weeklyUsedTokens} /></Typography.Text>
          <Typography.Text type={quotaByTeam[team.id]?.weeklyRemainingTokens === 0 ? 'danger' : 'secondary'}>
            剩余 {value === null ? '无限制' : <TokenAmount value={quotaByTeam[team.id]?.weeklyRemainingTokens} />}
          </Typography.Text>
        </Space>,
        title: '7 天团队共享额度',
        width: 185,
      },
      {
        dataIndex: 'totalTokenLimit',
        render: (value: number | null, team) => <Space orientation="vertical" size={0}>
          <span>上限 {value === null ? '无限制' : <TokenAmount value={value} />}</span>
          <Typography.Text type="secondary">已用 <TokenAmount value={quotaByTeam[team.id]?.totalUsedTokens} /></Typography.Text>
          <Typography.Text type={quotaByTeam[team.id]?.totalRemainingTokens === 0 ? 'danger' : 'secondary'}>
            剩余 {value === null ? '无限制' : <TokenAmount value={quotaByTeam[team.id]?.totalRemainingTokens} />}
          </Typography.Text>
        </Space>,
        title: '团队累计总额',
        width: 185,
      },
      {
        dataIndex: 'officialAccountId',
        render: (value: string | null) => officialAccounts.find((account) => account.id === value)?.label ?? '未关联',
        title: '官方账户',
        width: 150,
      },
      {
        dataIndex: 'memberCount',
        render: (value: number, team) => (
          <Button
            aria-label={`${team.name} 成员 ${value} 人`}
            onClick={() => setManagingMembers(team)}
            type="link"
            style={{ padding: 0 }}
          >
            {value} 人
          </Button>
        ),
        title: '成员数',
        width: 100,
      },
      {
        dataIndex: 'updatedAt',
        render: (value: string) => formatDate(value),
        title: '更新时间',
        width: 180,
      },
      {
        render: (_, team) => (
          <Space size={2}>
            <Button
              aria-label={`管理成员 ${team.name}`}
              onClick={() => setManagingMembers(team)}
              size="small"
            >
              管理成员
            </Button>
            <Tooltip title="编辑">
              <Button
                aria-label={`编辑 ${team.name}`}
                icon={<EditOutlined />}
                onClick={() => openEdit(team)}
                type="text"
              />
            </Tooltip>
            <Button
              loading={busyId === team.id}
              onClick={() => toggleTeam(team)}
              size="small"
            >
              {team.status === 'ACTIVE' ? '停用' : '启用'}
            </Button>
            <Tooltip title="删除">
              <Button
                aria-label={`删除 ${team.name}`}
                danger
                icon={<DeleteOutlined />}
                onClick={() => confirmDelete(team)}
                type="text"
              />
            </Tooltip>
          </Space>
        ),
        title: '操作',
        width: 260,
      },
    ],
    [busyId, confirmDelete, officialAccounts, openEdit, quotaByTeam, toggleTeam],
  );

  return (
    <PageContainer
      content="团队总额累计成员用量，直到管理员重置；个人限额与团队额度分别生效。"
      extra={[
        <Button
          icon={<PlusOutlined />}
          key="create"
          onClick={openCreate}
          type="primary"
        >
          新建团队
        </Button>,
      ]}
      title="团队与 Token 配额"
    >
      {error ? (
        <Alert
          action={<Button onClick={() => void load()}>重试</Button>}
          className="cqg-inline-alert"
          description={error}
          title="团队列表加载失败"
          showIcon
          type="error"
        />
      ) : null}
      <ProCard
        className="cqg-users-card"
        extra={
          <Tooltip title="刷新">
            <Button
              aria-label="刷新团队列表"
              icon={<ReloadOutlined />}
              loading={loading}
              onClick={() => void load()}
              type="text"
            />
          </Tooltip>
        }
      >
        <Table<TeamResponse>
          columns={columns}
          dataSource={data ?? []}
          loading={loading}
          locale={{
            emptyText: (
              <Empty
                description={error ? '暂时无法显示团队' : '暂无团队'}
                image={Empty.PRESENTED_IMAGE_SIMPLE}
              />
            ),
          }}
          pagination={false}
          rowKey="id"
          scroll={{ x: 1600 }}
        />
      </ProCard>
      {managingMembers && (
        <TeamMembersModal
          team={managingMembers}
          teams={data ?? []}
          onCancel={() => setManagingMembers(null)}
          onSaved={() => {
            setManagingMembers(null);
            message.success('团队成员已更新');
            void load(true);
          }}
        />
      )}

      <Modal
        forceRender
        okButtonProps={{ loading: saving }}
        okText={editing ? '保存' : '创建'}
        onCancel={closeForm}
        onOk={() => void saveTeam()}
        open={formOpen}
        title={editing ? '编辑团队与 Token 配额' : '新建团队与 Token 配额'}
      >
        <Form form={form} layout="vertical" preserve={false}>
          <Form.Item
            label="团队名称"
            name="name"
            rules={[{ message: '请输入团队名称', required: true }]}
          >
            <Input maxLength={120} placeholder="例如：研发团队" />
          </Form.Item>
          <Form.Item label="描述" name="description">
            <Input.TextArea maxLength={500} placeholder="说明团队用途（可选）" rows={3} />
          </Form.Item>
          <Form.Item
            extra="仅用于关联额度快照，不参与官方请求转发。关联后，团队累计总额使用该账户登记的估算 Token。"
            label="官方账户"
            name="officialAccountId"
          >
            <Select allowClear placeholder="选择已登记的官方账户" options={officialAccounts.map((account) => ({
              label: account.label, value: account.id,
            }))} onChange={(value) => {
              const account = officialAccounts.find((item) => item.id === value);
              form.setFieldValue('totalTokenLimit', account?.estimatedTotalTokens ?? null);
            }} />
          </Form.Item>
          <Form.Item
            extra={selectedOfficialAccountId ? '由关联官方账户的估算总量同步' : '团队全体成员累计消耗；留空表示不设累计上限。'}
            label="团队累计总 Token"
            name="totalTokenLimit"
            rules={[tokenLimitRule()]}
          >
            <InputNumber disabled={Boolean(selectedOfficialAccountId)} max={MAX_TOKEN_LIMIT} min={0} precision={0} suffix="Token" style={{ width: '100%' }} />
          </Form.Item>
          <Space align="start" size={16}>
            <Form.Item
              extra="留空表示不设 5 小时限制"
              label="5 小时 Token 上限"
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
              extra="留空表示不设 7 天限制"
              label="7 天 Token 上限"
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
          <Form.Item label="状态" name="status" rules={[{ message: '请选择状态', required: true }]}>
            <Select
              options={[
                { label: '启用', value: 'ACTIVE' },
                { label: '停用', value: 'DISABLED' },
              ]}
            />
          </Form.Item>
        </Form>
        <Typography.Paragraph type="secondary" style={{ marginBottom: 0 }}>
          团队额度与个人额度独立。官方百分比来自客户端上报，不会自动换算为 Token；修改配额后，相关系统会话需要重新建立。
        </Typography.Paragraph>
      </Modal>
    </PageContainer>
  );
}
