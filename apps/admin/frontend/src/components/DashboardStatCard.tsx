import { ProCard } from '@ant-design/pro-components';
import { Col, Statistic } from 'antd';
import type { ReactNode } from 'react';

export function DashboardStatCard({
  color,
  icon,
  title,
  value,
}: {
  color: string;
  icon: ReactNode;
  title: string;
  value: number;
}) {
  return (
    <Col lg={8} md={12} sm={12} xs={24}>
      <ProCard className="cqg-stat-card">
        <Statistic
          prefix={<span style={{ color }}>{icon}</span>}
          styles={{ content: { color: '#172033', fontWeight: 700 } }}
          title={title}
          value={value}
        />
      </ProCard>
    </Col>
  );
}
