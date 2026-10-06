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
import {
  createTeam,
  deleteTeam,
  listTeams,
  updateTeam,
} from '../../services/api';
import type {
  TeamCreate,
  TeamResponse,
  TeamStatus,
  TeamUpdate,
} from '../../services/api';

const DEFAULT_QUOTA_PERCENT = 100;

interface TeamFormValues {
  name: string;
  description: string;
  status: TeamStatus;
  fiveHourQuotaPercent: number;
  weeklyQuotaPercent: number;
}

function formatDate(value: string): string {
  const date = new Date(value);
  return Number.isNaN(date.getTime()) ? '—' : date.toLocaleString('zh-CN');
}

function formatQuota(value: number): string {
  return `${value}%`;
}

function quotaRule() {
  return {
    validator: (_: unknown, value: unknown) => {
      if (
        typeof value !== 'number' ||
        !Number.isFinite(value) ||
        value < 0 ||
        value > 100
      ) {
        return Promise.reject(new Error('请输入 0 到 100 的数字'));
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
  const [editing, setEditing] = useState<TeamResponse | null>(null);
  const [error, setError] = useState('');
  const [formOpen, setFormOpen] = useState(false);
  const [loading, setLoading] = useState(true);
  const [saving, setSaving] = useState(false);

  const load = useCallback(async () => {
    setLoading(true);
    setError('');
    try {
      setData(await listTeams());
    } catch (requestError) {
      setError(getErrorMessage(requestError));
    } finally {
      setLoading(false);
    }
  }, []);

  useEffect(() => {
    void load();
  }, [load]);

  const openCreate = useCallback(() => {
    setEditing(null);
    form.resetFields();
    form.setFieldsValue({
      description: '',
      fiveHourQuotaPercent: DEFAULT_QUOTA_PERCENT,
      name: '',
      status: 'ACTIVE',
      weeklyQuotaPercent: DEFAULT_QUOTA_PERCENT,
    });
    setFormOpen(true);
  }, [form]);

  const openEdit = useCallback(
    (team: TeamResponse) => {
      setEditing(team);
      form.setFieldsValue({
        description: team.description,
        fiveHourQuotaPercent: team.fiveHourQuotaPercent,
        name: team.name,
        status: team.status,
        weeklyQuotaPercent: team.weeklyQuotaPercent,
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
        ? '保存后会影响该团队成员的额度策略，并使相关会话失效。确定继续吗？'
        : '创建团队后，后续分配到该团队的用户将继承这里的额度策略。确定创建吗？',
      okText: editing ? '确认保存' : '确认创建',
      onOk: async () => {
        setSaving(true);
        try {
          if (editing) {
            const update: TeamUpdate = {
              ...values,
              version: editing.version,
            };
            await updateTeam(editing.id, update);
            message.success('团队与额度策略已更新');
          } else {
            const create: TeamCreate = { ...values };
            await createTeam(create);
            message.success('团队已创建');
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
        dataIndex: 'fiveHourQuotaPercent',
        render: (value: number) => formatQuota(value),
        title: '5 小时默认额度',
        width: 150,
      },
      {
        dataIndex: 'weeklyQuotaPercent',
        render: (value: number) => formatQuota(value),
        title: '7 天默认额度',
        width: 140,
      },
      {
        dataIndex: 'memberCount',
        render: (value: number) => `${value} 人`,
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
        width: 180,
      },
    ],
    [busyId, confirmDelete, openEdit, toggleTeam],
  );

  return (
    <PageContainer
      content="配置团队成员共享的默认额度与状态；页面只显示策略，不展示真实用量。"
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
      title="团队与额度策略"
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
          scroll={{ x: 1000 }}
        />
      </ProCard>

      <Modal
        forceRender
        okButtonProps={{ loading: saving }}
        okText={editing ? '保存' : '创建'}
        onCancel={closeForm}
        onOk={() => void saveTeam()}
        open={formOpen}
        title={editing ? '编辑团队与额度策略' : '新建团队与额度策略'}
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
          <Space align="start" size={16}>
            <Form.Item
              label="5 小时默认额度"
              name="fiveHourQuotaPercent"
              rules={[quotaRule()]}
            >
              <InputNumber
                suffix="%"
                style={{ width: 180 }}
              />
            </Form.Item>
            <Form.Item
              label="7 天默认额度"
              name="weeklyQuotaPercent"
              rules={[quotaRule()]}
            >
              <InputNumber
                suffix="%"
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
          额度为策略百分比（0–100），不是 Codex 的实时剩余额度。修改策略后，相关用户会话需要重新建立。
        </Typography.Paragraph>
      </Modal>
    </PageContainer>
  );
}
