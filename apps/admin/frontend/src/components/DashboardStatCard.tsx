import { ProCard } from '@ant-design/pro-components';
import { Statistic, Typography } from 'antd';
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
    <ProCard className="cqg-stat-card">
      <div className="cqg-stat-card-heading">
        <Typography.Text className="cqg-stat-card-label">{title}</Typography.Text>
        <span
          aria-hidden="true"
          className="cqg-stat-card-icon"
          style={{ color, backgroundColor: `${color}1a` }}
        >
          {icon}
        </span>
      </div>
      <div className="cqg-stat-card-value">
        <Statistic
          className="cqg-statistic"
          styles={{ content: { color: '#373b38', fontWeight: 650 } }}
          value={value}
        />
      </div>
    </ProCard>
  );
}
