import { PageContainer } from '@ant-design/pro-components';
import { Card, Radio, Space, Typography } from 'antd';
import { useQuotaDisplay } from '../../services/quota-display';

export default function SettingsPage() {
  const { mode, setMode } = useQuotaDisplay();
  return <PageContainer title="设置" content="调整当前浏览器的显示偏好。">
    <Card title="显示设置">
      <Space orientation="vertical" size={12}>
        <Typography.Text id="quota-display-label" strong>额度显示</Typography.Text>
        <Radio.Group aria-labelledby="quota-display-label" value={mode} onChange={(event) => setMode(event.target.value)}
          options={[{ label: '剩余', value: 'remaining' }, { label: '已用', value: 'used' }]} />
        <Typography.Text type="secondary">调整个人、团队和官方额度的显示数值与进度条，选择会保存在当前浏览器。</Typography.Text>
      </Space>
    </Card>
  </PageContainer>;
}
