import {
  CheckCircleOutlined,
  SafetyCertificateOutlined,
  StopOutlined,
  TeamOutlined,
  UserOutlined,
} from '@ant-design/icons';
import { Alert, Button, Row } from 'antd';
import { PageContainer } from '@ant-design/pro-components';
import { useCallback, useEffect, useState } from 'react';

import { DashboardStatCard } from '../../components/DashboardStatCard';
import { ErrorState, getErrorMessage, LoadingState } from '../../components/AdminState';
import { getDashboard } from '../../services/api';
import type { DashboardStats } from '../../services/api';

export default function DashboardPage() {
  const [data, setData] = useState<DashboardStats | null>(null);
  const [error, setError] = useState('');
  const [loading, setLoading] = useState(true);

  const load = useCallback(async () => {
    setLoading(true);
    setError('');
    try {
      setData(await getDashboard());
    } catch (requestError) {
      setError(getErrorMessage(requestError));
    } finally {
      setLoading(false);
    }
  }, []);

  useEffect(() => {
    void load();
  }, [load]);

  return (
    <PageContainer
      content="实时查看 Codex Quota Guard 的用户与管理员账号概况。"
      title="概览"
    >
      {loading && !data ? <LoadingState label="概览加载中" /> : null}
      {!loading && !data && error ? (
        <ErrorState description={error} onRetry={() => void load()} title="概览加载失败" />
      ) : null}
      {data ? (
        <>
          {error ? (
            <Alert
              action={<Button onClick={() => void load()}>重试</Button>}
              className="cqg-inline-alert"
              title="数据刷新失败"
              showIcon
              type="error"
            />
          ) : null}
          <Row gutter={[16, 16]}>
            <DashboardStatCard
              color="#2563eb"
              icon={<TeamOutlined />}
              title="用户总数"
              value={data.totalUsers}
            />
            <DashboardStatCard
              color="#16a34a"
              icon={<CheckCircleOutlined />}
              title="活跃用户"
              value={data.activeUsers}
            />
            <DashboardStatCard
              color="#dc2626"
              icon={<StopOutlined />}
              title="已禁用"
              value={data.disabledUsers}
            />
            <DashboardStatCard
              color="#7c3aed"
              icon={<SafetyCertificateOutlined />}
              title="管理员"
              value={data.adminUsers}
            />
            <DashboardStatCard
              color="#ea580c"
              icon={<UserOutlined />}
              title="近 7 天新增"
              value={data.newUsersLast7Days}
            />
          </Row>
        </>
      ) : null}
    </PageContainer>
  );
}
