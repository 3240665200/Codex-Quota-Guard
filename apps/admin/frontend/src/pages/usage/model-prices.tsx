import { DeleteOutlined, EditOutlined, PlusOutlined } from '@ant-design/icons';
import { Alert, App as AntApp, Button, Form, Input, InputNumber, Modal, Space, Table, Tooltip } from 'antd';
import type { TableColumnsType } from 'antd';
import { useState } from 'react';
import { getErrorMessage } from '../../components/AdminState';
import { formatUsd } from '../../components/UsageAmounts';
import { deleteUsageModelPrice, saveUsageModelPrice } from '../../services/api';
import type { UsageModelPrice, UsageModelPriceInput } from '../../services/api';

const priceFields = [
  { name: 'inputPrice', label: '非缓存输入' },
  { name: 'outputPrice', label: '输出（含思考）' },
  { name: 'cacheReadPrice', label: '缓存读' },
  { name: 'cacheWritePrice', label: '缓存写' },
] as const;

export function ModelPrices({ prices, loading, onChanged, models }: {
  prices: UsageModelPrice[];
  loading: boolean;
  onChanged: () => void;
  models: Array<{ value: string; label: string }>;
}) {
  const { message, modal } = AntApp.useApp();
  const [form] = Form.useForm<UsageModelPriceInput>();
  const [open, setOpen] = useState(false);
  const [editing, setEditing] = useState(false);
  const [saving, setSaving] = useState(false);
  const [error, setError] = useState('');
  const unpriced = models.filter((model) => !prices.some((price) => price.model === model.value));

  const edit = (price?: UsageModelPrice) => {
    setEditing(!!price);
    setError('');
    form.resetFields();
    if (price) form.setFieldsValue(price);
    setOpen(true);
  };
  const save = async () => {
    let values: UsageModelPriceInput;
    try { values = await form.validateFields(); } catch { return; }
    setSaving(true);
    setError('');
    try {
      await saveUsageModelPrice(values);
      setOpen(false);
      message.success('单价已保存');
      onChanged();
    } catch (reason) {
      setError(getErrorMessage(reason));
    } finally {
      setSaving(false);
    }
  };
  const remove = (price: UsageModelPrice) => {
    modal.confirm({
      title: `删除 ${price.model} 的定价？`,
      content: '对应流水将显示为未定价，Token 统计不变。',
      okText: '删除', cancelText: '取消', okButtonProps: { danger: true },
      onOk: async () => {
        try {
          await deleteUsageModelPrice(price.model);
          message.success('定价已删除');
          onChanged();
        } catch (reason) {
          message.error(getErrorMessage(reason));
          throw reason;
        }
      },
    });
  };
  const columns: TableColumnsType<UsageModelPrice> = [
    { title: '模型名称', dataIndex: 'model', width: 230, ellipsis: true },
    ...priceFields.map((field) => ({
      title: `${field.label} (USD / M)`, dataIndex: field.name, width: 170,
      render: (value: number) => formatUsd(value),
    })),
    { title: '价格来源', width: 190, render: (_, price) => price.priceSource === 'CC_SWITCH'
      ? price.sourceReference
        ? <a href={price.sourceReference} target="_blank" rel="noreferrer">CC Switch 快照 · {price.sourceCheckedAt}</a>
        : `CC Switch 快照 · ${price.sourceCheckedAt ?? '未知日期'}`
      : '管理员设置' },
    { title: '更新于', dataIndex: 'updatedAt', width: 180, render: (value: string) => new Date(value).toLocaleString('zh-CN') },
    { title: '操作', width: 90, render: (_, price) => <Space>
      <Tooltip title="编辑定价"><Button aria-label={`编辑 ${price.model} 定价`} type="text" icon={<EditOutlined />} onClick={() => edit(price)} /></Tooltip>
      <Tooltip title="删除定价"><Button aria-label={`删除 ${price.model} 定价`} type="text" danger icon={<DeleteOutlined />} onClick={() => remove(price)} /></Tooltip>
    </Space> },
  ];
  return <section className="cqg-usage-section" aria-label="模型定价">
    <div className="cqg-usage-section-head">
      <div><h2>模型定价</h2><span className="cqg-usage-muted">USD / 百万 Token · 内置 CC Switch 价格快照，可手动覆盖 · 历史流水按当前单价估算</span></div>
      <Button type="primary" icon={<PlusOutlined />} onClick={() => edit()}>添加定价</Button>
    </div>
    {unpriced.length ? <Alert className="cqg-inline-alert" type="warning" showIcon title={`${unpriced.length} 个已上报模型尚未定价`} /> : null}
    <Table rowKey="model" columns={columns} dataSource={prices} loading={loading}
      size="small" scroll={{ x: 1380 }} pagination={{ pageSize: 20, showSizeChanger: false }}
      locale={{ emptyText: '暂无模型单价' }} />
    <Modal title={editing ? '编辑模型单价' : '添加模型单价'} open={open} onCancel={() => { if (!saving) setOpen(false); }}
      onOk={() => void save()} confirmLoading={saving} okText="保存" cancelText="取消" destroyOnHidden>
      {error ? <Alert showIcon type="error" title={error} className="cqg-inline-alert" /> : null}
      <Form form={form} layout="vertical" preserve={false}>
        <Form.Item name="model" label="模型名称" rules={[{ required: true, message: '请输入模型名称' },
          { pattern: /^[A-Za-z0-9][A-Za-z0-9._:/-]{0,95}$/, message: '请输入有效的模型标识（最多 96 字符）' }]}>
          <Input disabled={editing} maxLength={96} list="usage-model-names" />
        </Form.Item>
        <datalist id="usage-model-names">{models.map((model) => <option key={model.value} value={model.value} />)}</datalist>
        {priceFields.map((field) => <Form.Item key={field.name} name={field.name} label={`${field.label} (USD / 百万 Token)`}
          rules={[{ required: true, message: '请输入单价，免费填 0' }, { type: 'number', min: 0, max: 1_000_000 },
            { validator: (_, value: unknown) => typeof value !== 'number' || Math.abs(value * 1_000_000 - Math.round(value * 1_000_000)) > 0.0001
              ? Promise.reject(new Error('单价最多 6 位小数')) : Promise.resolve() }]}>
          <InputNumber min={0} max={1_000_000} step={0.01} style={{ width: '100%' }} />
        </Form.Item>)}
      </Form>
    </Modal>
  </section>;
}
