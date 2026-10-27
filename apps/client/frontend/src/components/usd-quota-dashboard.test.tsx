import { act, fireEvent, render, screen, within } from '@testing-library/react';
import { beforeEach, describe, expect, it } from 'vitest';
import { UsdQuotaDashboard } from './usd-quota-dashboard';
import { setQuotaDisplayMode } from '@/lib/quota-display';
import { moneyScope, moneyWindow, usdFixture } from '@/test/usd-fixtures';
import type { UsdOverview } from '@/lib/usd';

const example = (): UsdOverview => {
  const personal = { currency: 'USD' as const, fiveHour: moneyWindow('100','25','75'),weekly:moneyWindow('200','50','150'),total:moneyWindow(null,'50') };
  const team = { currency:'USD' as const,fiveHour:moneyWindow('1000','100','900'),weekly:moneyWindow('2000','200','1800'),total:moneyWindow('5000','500','4500') };
  return usdFixture({personal,team,officialAccount:{id:'official-1',label:'研发账户',quota:team},teamMembers:[
    {userId:'member-1',displayName:'Alex',status:'ACTIVE',personal:{...personal,fiveHour:moneyWindow('100','60','40')},contribution:{...moneyScope(),fiveHour:moneyWindow(null,'10'),weekly:moneyWindow(null,'20'),total:moneyWindow(null,'50')}},
    {userId:'member-2',displayName:'Alex',status:'DISABLED',personal:moneyScope(),contribution:moneyScope()},
    {userId:'member-3',displayName:'Zero',status:'ACTIVE',personal:moneyScope(),contribution:moneyScope()},
  ]});
};
beforeEach(() => setQuotaDisplayMode('remaining'));
describe('USD quota dashboard', () => {
  it('marks the signed-in user by ID even when two members have the same name', () => {
    const { rerender } = render(<UsdQuotaDashboard billing={example()} currentUserId="member-2" />);
    const rows = screen.getAllByRole('article', { name: 'Alex成员用量' });
    expect(within(rows[0]).queryByText('我')).not.toBeInTheDocument();
    expect(within(rows[1]).getByText('我')).toHaveAccessibleName('当前登录用户');
    rerender(<UsdQuotaDashboard billing={example()} currentUserId="member-1" />);
    expect(within(rows[0]).getByText('我')).toBeVisible();
    expect(within(rows[1]).queryByText('我')).not.toBeInTheDocument();
  });
  it('separates personal, team, and official dollar budgets', () => {
    render(<UsdQuotaDashboard billing={example()} />);
    for(const name of ['个人额度（USD）','团队额度（USD）','官方账户额度（USD）','成员用量（USD）'])expect(screen.getByRole('heading',{name})).toBeVisible();
    expect(screen.getByRole('progressbar',{name:'个人 5 小时剩余美元额度'})).toHaveAttribute('aria-valuenow','75');
    expect(screen.queryByText(/Token/)).not.toBeInTheDocument();
  });
  it('shows each personal balance separately from dollar contribution percentages', () => {
    render(<UsdQuotaDashboard billing={example()} />);
    const row=screen.getAllByRole('article',{name:'Alex成员用量'})[0];
    expect(within(row).getByRole('progressbar',{name:'5 小时个人剩余美元额度'})).toHaveAttribute('aria-valuenow','40');
    expect(within(row).getByRole('progressbar',{name:'5 小时团队美元用量占比'})).toHaveAttribute('aria-valuenow','10');
  });
  it('uses the saved display mode for all quota windows while retaining contribution shares', () => {
    render(<UsdQuotaDashboard billing={example()} />);
    act(() => setQuotaDisplayMode('used'));
    expect(screen.getByRole('progressbar',{name:'个人 5 小时已用美元额度'})).toHaveAttribute('aria-valuenow','25');
    const row=screen.getAllByRole('article',{name:'Alex成员用量'})[0];
    expect(within(row).getByRole('progressbar',{name:'5 小时个人已用美元额度'})).toHaveAttribute('aria-valuenow','60');
    expect(within(row).getByRole('progressbar',{name:'5 小时团队美元用量占比'})).toHaveAttribute('aria-valuenow','10');
  });
  it('keeps duplicate names, disabled members and zero use visible', () => {
    render(<UsdQuotaDashboard billing={example()} />);
    expect(screen.getAllByRole('heading',{name:'Alex'})).toHaveLength(2);
    expect(screen.getByText('已停用')).toBeVisible();
    const row=screen.getByRole('article',{name:'Zero成员用量'});
    expect(within(row).getByRole('progressbar',{name:'累计团队美元用量占比'})).toHaveAttribute('aria-valuenow','0');
    expect(within(row).getAllByText('无限制').length).toBeGreaterThan(0);
  });
  it('renders all team members and filters them locally', () => {
    const billing=example();billing.teamMembers=Array.from({length:100},(_,index)=>({...billing.teamMembers[0],userId:`member-${index}`,displayName:`成员${index}`}));
    render(<UsdQuotaDashboard billing={billing} />);
    expect(screen.getAllByRole('heading',{level:3})).toHaveLength(100);
    fireEvent.change(screen.getByLabelText('搜索成员姓名'),{target:{value:'成员99'}});
    expect(screen.getAllByRole('heading',{level:3})).toHaveLength(1);
  });
  it.each(['0','10','15'])('clamps exhausted balances for used USD %s', (used) => {
    const billing=usdFixture();billing.personal.fiveHour=moneyWindow('10',used,Number(used)>=10?'0':'10');
    render(<UsdQuotaDashboard billing={billing} />);
    expect(screen.getByRole('progressbar',{name:'个人 5 小时剩余美元额度'})).toHaveAttribute('aria-valuenow',Number(used)>=10?'0':'100');
    expect(screen.queryByText('额度已用尽')!==null).toBe(Number(used)>=10);
  });
  it('treats an explicit zero budget as exhausted, even without usage', () => {
    const billing=usdFixture();billing.personal.fiveHour=moneyWindow('0','0','0');
    render(<UsdQuotaDashboard billing={billing} />);
    expect(screen.getByText('额度已用尽')).toBeVisible();
  });
  it('does not manufacture a remaining balance when pricing is incomplete', () => {
    const billing=usdFixture();billing.personal.fiveHour={...moneyWindow('100','0',null),pendingEvents:2};
    render(<UsdQuotaDashboard billing={billing} />);
    expect(screen.getByText('待核算')).toBeVisible();
    expect(screen.queryByRole('progressbar',{name:'个人 5 小时剩余美元额度'})).not.toBeInTheDocument();
    expect(screen.getByText(/2 笔待核算/)).toBeVisible();
  });
  it.each(['member', 'team'])('does not display a zero contribution percentage when %s costs are pending', (scope) => {
    const billing=example();
    if (scope === 'member') billing.teamMembers[0].contribution.fiveHour={...moneyWindow(null,'0'),pendingEvents:1};
    else billing.team!.fiveHour={...moneyWindow(null,'0'),pendingEvents:1};
    render(<UsdQuotaDashboard billing={billing} />);
    const row=screen.getAllByRole('article',{name:'Alex成员用量'})[0];
    expect(within(row).queryByRole('progressbar',{name:'5 小时团队美元用量占比'})).not.toBeInTheDocument();
    expect(within(row).getByText('待核算')).toBeVisible();
    expect(within(row).getByText('费用尚未核算完整，暂不计算占比')).toBeVisible();
  });
  it.each(['0','5'])('flags inconsistent team dollar totals %s rather than inventing a percentage', (total) => {
    const billing=example();billing.team!.fiveHour=moneyWindow(null,total);
    render(<UsdQuotaDashboard billing={billing} />);
    const row=screen.getAllByRole('article',{name:'Alex成员用量'})[0];
    expect(within(row).queryByRole('progressbar',{name:'5 小时团队美元用量占比'})).not.toBeInTheDocument();
    expect(within(row).getByText('待刷新')).toBeVisible();
  });
  it('keeps personal money and missing team reference data separate', () => {
    const billing=example();billing.team=null;billing.officialAccount=null;
    render(<UsdQuotaDashboard billing={billing} />);
    expect(screen.getByRole('progressbar',{name:'个人 5 小时剩余美元额度'})).toBeVisible();
    expect(screen.getByText('未加入团队')).toBeVisible();
    expect(screen.getAllByText('团队用量暂不可得，暂无占比')).toHaveLength(3);
  });
});
