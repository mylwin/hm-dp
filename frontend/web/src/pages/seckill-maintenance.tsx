import { useEffect, useRef, useState, type FormEvent } from 'react';
import { ArrowClockwise, CheckCircle, Key, ShieldCheck, WarningCircle } from '@phosphor-icons/react';
import { useApp } from '../app-context';
import { Empty, Modal } from '../components/ui';
import {
  queryQuarantinedMessages, querySeckillHealth, submitMaintenanceAction,
  type HealthMetric, type MaintenanceAction, type QuarantinedMessage, type SeckillHealth,
} from '../api/seckill-maintenance';
import '../seckill-maintenance.css';

type Resource<T> = { data: T | null; loading: boolean; error: string; updatedAt: string };
const emptyResource = <T,>(): Resource<T> => ({ data: null, loading: false, error: '', updatedAt: '' });
const metricGroups: { title: string; metrics: [HealthMetric, string, string?][] }[] = [
  { title: '消息与预占任务', metrics: [
    ['pending_tasks', '未完成任务总数'], ['pending_create_events', '待投递建单消息'],
    ['pending_confirmations', '待确认预占'], ['pending_releases', '待释放预占'],
    ['expired_leases', '已过期租约'], ['max_attempts', '最大尝试次数'], ['oldest_task_seconds', '最老任务等待', '秒'],
  ] },
  { title: '抢购请求', metrics: [
    ['unfinished_requests', '未终态请求'], ['preparing_requests', '受理准备中'],
    ['accepted_requests', '已受理待建单'], ['oldest_request_seconds', '最老请求等待', '秒'],
  ] },
  { title: '活动与异常', metrics: [
    ['pending_campaigns', '待发布活动'], ['oldest_campaign_seconds', '最老活动等待', '秒'],
    ['blocked_campaigns', '因冲突阻止发布'], ['recovery_errors', '活跃资源恢复错误'], ['quarantine_pending', '未处置隔离消息'],
  ] },
];
const actionLabels = { replay: '重投原消息', close: '关闭抢购请求', dismiss: '标记隔离消息已处置' };
const actionDescriptions = {
  replay: '沿用原订单号和原建单消息。后端将核对请求、预占及发送租约，提交成功后仍需等待消费者建单。',
  close: '仅关闭尚未建单的抢购请求。成功表示关闭及释放任务已提交，Redis 库存由后台任务随后回补。',
  dismiss: '仅标记这条隔离消息已处置，保留原文和审计，不改变抢购请求、订单或库存。请先确认无需重投。',
};
const successMessages = {
  replay: '重投已提交，仍需等待消息投递及消费者建单。',
  close: '请求已关闭，释放任务已提交；Redis 库存由后台任务随后回补。',
  dismiss: '隔离消息已标记为已处置，订单与库存未改变。',
};
function errorMessage(error: unknown) { return error instanceof Error ? error.message : '操作失败，请稍后重试。'; }
function timeLabel(value: string) { return value ? new Date(value).toLocaleTimeString('zh-CN', { hour12: false }) : '尚未查询'; }

export default function SeckillMaintenance() {
  const { mode, setMode, user, authLoading, setLoginOpen } = useApp();
  useEffect(() => { document.title = '秒杀维护 · 本地测试 · 黑马点评'; }, []);
  return <div className="container ops-page">
    <header className="ops-heading"><div className="ops-kicker"><ShieldCheck size={18} />本地测试工具</div>
      <h1>秒杀维护</h1><p>查看后台积压，核对异常消息，按原订单执行受控处置。</p>
    </header>
    {mode === 'preview'
      ? <Empty title="请切换到实时数据" description="维护页面使用真实后端接口，设计预览不查询或修改秒杀数据。"><button className="button primary" onClick={() => setMode('live')}>切换实时数据</button></Empty>
      : authLoading ? <p className="ops-notice" role="status">正在恢复登录状态…</p>
        : !user ? <Empty title="登录后使用维护工具" description="仍需填写独立运维令牌。后端会记录当前登录用户的操作审计。"><button className="button primary" onClick={() => setLoginOpen(true)}>登录 / 注册</button></Empty>
          : <MaintenanceWorkspace key={String(user.id)} operatorName={user.nickName} />}
  </div>;
}

function MaintenanceWorkspace({ operatorName }: { operatorName: string }) {
  const [opsToken, setOpsToken] = useState('');
  const [health, setHealth] = useState<Resource<SeckillHealth>>(emptyResource);
  const [quarantine, setQuarantine] = useState<Resource<QuarantinedMessage[]>>(emptyResource);
  const [orderId, setOrderId] = useState('');
  const [quarantineId, setQuarantineId] = useState('');
  const [formError, setFormError] = useState('');
  const [action, setAction] = useState<MaintenanceAction | null>(null);
  const [reason, setReason] = useState('');
  const [actionError, setActionError] = useState('');
  const [submitting, setSubmitting] = useState(false);
  const [notice, setNotice] = useState('');
  const readController = useRef<AbortController | null>(null);
  const mutationPending = useRef(false);
  const mounted = useRef(true);
  const orderInput = useRef<HTMLInputElement>(null);
  const loading = health.loading || quarantine.loading;
  const tokenValid = opsToken.length >= 32 && !/[\r\n]/.test(opsToken);

  useEffect(() => {
    mounted.current = true;
    return () => { mounted.current = false; readController.current?.abort(); };
  }, []);

  function changeToken(value: string) {
    readController.current?.abort();
    setOpsToken(value);
    setHealth(emptyResource()); setQuarantine(emptyResource()); setNotice('');
  }

  async function refresh() {
    if (!tokenValid) return;
    readController.current?.abort();
    const controller = new AbortController();
    readController.current = controller;
    setHealth({ ...emptyResource(), loading: true });
    setQuarantine({ ...emptyResource(), loading: true });
    // 两个查询分别落状态，一个接口失败不遮住另一个接口的结果。
    await Promise.allSettled([
      querySeckillHealth(opsToken, controller.signal).then((data) => {
        if (!controller.signal.aborted) setHealth({ data, loading: false, error: '', updatedAt: new Date().toISOString() });
      }).catch((error) => {
        if (!controller.signal.aborted) setHealth({ ...emptyResource(), error: errorMessage(error) });
      }),
      queryQuarantinedMessages(opsToken, controller.signal).then((data) => {
        if (!controller.signal.aborted) setQuarantine({ data, loading: false, error: '', updatedAt: new Date().toISOString() });
      }).catch((error) => {
        if (!controller.signal.aborted) setQuarantine({ ...emptyResource(), error: errorMessage(error) });
      }),
    ]);
  }

  function openOrderAction(kind: 'replay' | 'close') {
    const originalOrderId = orderId.trim();
    const linkedQuarantineId = quarantineId.trim();
    if (!/^[1-9]\d{0,18}$/.test(originalOrderId) || BigInt(originalOrderId) > 9223372036854775807n) {
      setFormError('请输入有效的正数订单号，最大为 9223372036854775807。'); return;
    }
    if (kind === 'replay' && linkedQuarantineId && !/^[a-f0-9]{64}$/.test(linkedQuarantineId)) {
      setFormError('关联隔离编号必须为 64 位小写十六进制字符串。'); return;
    }
    setFormError('');
    openAction(kind === 'replay' ? { kind, orderId: originalOrderId, quarantineId: linkedQuarantineId || undefined } : { kind, orderId: originalOrderId });
  }

  function openAction(nextAction: MaintenanceAction) {
    setAction(nextAction); setReason(''); setActionError(''); setNotice('');
  }

  async function confirmAction(event: FormEvent) {
    event.preventDefault();
    if (!action || mutationPending.current || !tokenValid) return;
    if (!reason.trim() || reason.length > 256) { setActionError('请填写 1 到 256 字符的操作原因。'); return; }
    mutationPending.current = true;
    setSubmitting(true); setActionError('');
    try {
      await submitMaintenanceAction(action, reason.trim(), opsToken);
      if (!mounted.current) return;
      setNotice(successMessages[action.kind]); setAction(null); setReason('');
      await refresh();
    } catch (error) {
      if (mounted.current) setActionError(`${errorMessage(error)} 请核对最新状态后再决定是否重试。`);
    } finally {
      mutationPending.current = false;
      if (mounted.current) setSubmitting(false);
    }
  }

  return <>
    <section className="ops-panel ops-access" aria-labelledby="ops-access-heading">
      <div><h2 id="ops-access-heading"><Key size={20} />连接维护接口</h2>
        <p>操作者：{operatorName}。令牌仅保留在当前页面内存中，刷新或离开后需重新输入。</p></div>
      <form className="ops-access-form" onSubmit={(event) => { event.preventDefault(); void refresh(); }}>
        <label htmlFor="ops-token">独立运维令牌</label>
        <div className="ops-controls"><input id="ops-token" type="password" autoComplete="off" spellCheck={false} value={opsToken} disabled={submitting} onChange={(event) => changeToken(event.target.value)} placeholder="与后端配置一致，至少 32 字符" />
          <button className="button primary" type="submit" disabled={!tokenValid || loading || submitting}><ArrowClockwise size={17} />{loading ? '查询中…' : '连接 / 刷新'}</button>
          <button className="button secondary" type="button" disabled={!opsToken || submitting} onClick={() => changeToken('')}>清除令牌</button></div>
      </form>
      <details className="ops-setup"><summary>本地连接条件</summary><p>后端开启 <code>SECKILL_OPERATIONS_ENABLED=true</code>，设置至少 32 字符的 <code>SECKILL_OPERATIONS_TOKEN</code>，并已完成第四阶段数据库迁移。令牌在此手动输入。</p></details>
    </section>

    {notice && <p className="ops-notice ops-success" role="status"><CheckCircle size={20} />{notice}</p>}

    <section className="ops-section" aria-labelledby="ops-health-heading" aria-busy={health.loading}>
      <div className="ops-section-heading"><h2 id="ops-health-heading">运行概况</h2><span>最近成功查询：{timeLabel(health.updatedAt)}</span></div>
      <p className="ops-caption">以下是数据库待办快照，不代表 Broker 的精确队列深度。手动刷新查看变化。</p>
      {health.error && <p className="ops-notice ops-error" role="alert"><WarningCircle size={20} />{health.error}</p>}
      {!health.data && !health.error && <p className="ops-caption" role="status">{health.loading ? '正在查询健康指标…' : '填写令牌并连接后显示指标；尚未查询的指标以 — 显示。'}</p>}
      <div className="ops-metric-groups">{metricGroups.map((group) => <div className="ops-panel" key={group.title}><h3>{group.title}</h3><dl className="ops-metrics">{group.metrics.map(([metric, label, unit]) => <div key={metric}><dt>{label}</dt><dd data-metric={metric}>{health.data?.[metric] ?? '—'}{health.data?.[metric] !== undefined && unit && <small>{unit}</small>}</dd></div>)}</dl></div>)}</div>
    </section>

    <section className="ops-panel ops-order" aria-labelledby="ops-order-heading">
      <div><h2 id="ops-order-heading">按原订单处置</h2><p>从抢购结果或数据库核对原订单号。消息摘要没有订单号，请勿把消息编号当成订单号。</p>
        <p className="ops-caption">关闭仅适用于尚未建单的请求。已创建订单的取消、支付超时和退款不在此入口处理。</p></div>
      <div className="ops-order-form">
        <label htmlFor="ops-order-id">原订单号</label><input ref={orderInput} id="ops-order-id" type="text" inputMode="numeric" value={orderId} onChange={(event) => setOrderId(event.target.value)} placeholder="输入完整订单号" disabled={submitting} />
        <label htmlFor="ops-quarantine-id">关联隔离编号 <span className="muted">（选填，仅重投使用）</span></label><input id="ops-quarantine-id" value={quarantineId} onChange={(event) => setQuarantineId(event.target.value)} placeholder="64 位小写十六进制编号" spellCheck={false} disabled={submitting} />
        {formError && <p className="ops-error-text" role="alert">{formError}</p>}
        <div className="ops-controls"><button className="button primary" disabled={!tokenValid || submitting} onClick={() => openOrderAction('replay')}>重投原消息</button><button className="button secondary" disabled={!tokenValid || submitting} onClick={() => openOrderAction('close')}>关闭抢购请求</button></div>
      </div>
    </section>

    <section className="ops-panel" aria-labelledby="ops-quarantine-heading" aria-busy={quarantine.loading}>
      <div className="ops-section-heading"><h2 id="ops-quarantine-heading">隔离消息</h2><span>最近成功查询：{timeLabel(quarantine.updatedAt)}</span></div>
      <p className="ops-caption">按时间列出最早尚未处置的一批摘要，上限由后端批次配置决定。处置后刷新可看到后续记录；不展示消息原文。</p>
      {quarantine.error ? <p className="ops-notice ops-error" role="alert"><WarningCircle size={20} />{quarantine.error}</p>
        : !quarantine.data ? <p className="ops-blank" role="status">{quarantine.loading ? '正在查询隔离消息…' : '连接后查看未处置消息。'}</p>
          : quarantine.data.length === 0 ? <p className="ops-blank" role="status">当前没有未处置的隔离消息。</p>
            : <div className="ops-table-scroll"><table className="ops-table"><caption className="sr-only">未处置隔离消息摘要</caption><thead><tr><th scope="col">消息 / 来源</th><th scope="col">错误 / 重试</th><th scope="col">记录时间</th><th scope="col">处置</th></tr></thead><tbody>{quarantine.data.map((message) => <tr key={message.quarantine_id}>
              <td><strong className="ops-code">{message.message_id}</strong><span className="ops-source">{message.source} · {message.topic}</span><details className="ops-message-details"><summary>编号与摘要</summary><dl><dt>隔离编号</dt><dd>{message.quarantine_id}</dd><dt>正文 SHA-256</dt><dd>{message.body_hash}</dd></dl></details></td>
              <td><span>{message.error_type}</span><span className="ops-source">已重试 {message.reconsume_times} 次</span></td><td><time>{message.created_at}</time></td>
              <td><div className="ops-row-actions"><button className="button secondary" disabled={!tokenValid || submitting} onClick={() => {
                setQuarantineId(message.quarantine_id); setOrderId(''); setFormError('请填写并核对这条隔离消息对应的原订单号。');
                orderInput.current?.focus(); orderInput.current?.scrollIntoView({ block: 'center', behavior: 'smooth' });
              }}>关联重投</button><button className="button secondary" disabled={!tokenValid || submitting} onClick={() => openAction({ kind: 'dismiss', quarantineId: message.quarantine_id })}>标记已处置</button></div></td>
            </tr>)}</tbody></table></div>}
    </section>

    <Modal open={!!action} onOpenChange={(open) => { if (!open && !mutationPending.current) setAction(null); }} title={action ? actionLabels[action.kind] : '确认处置'} description={action ? actionDescriptions[action.kind] : '核对处置对象和操作原因。'}>
      {action && <form className="ops-confirm" onSubmit={confirmAction} aria-busy={submitting}>
        <dl className="ops-target"><dt>{action.kind === 'dismiss' ? '隔离编号' : '原订单号'}</dt><dd>{action.kind === 'dismiss' ? action.quarantineId : action.orderId}</dd>{action.kind === 'replay' && action.quarantineId && <><dt>关联隔离编号</dt><dd>{action.quarantineId}</dd></>}</dl>
        <label htmlFor="ops-reason">操作原因</label><textarea id="ops-reason" value={reason} onChange={(event) => setReason(event.target.value)} maxLength={256} rows={3} required disabled={submitting} placeholder="填写已核对的事实和处置依据" />
        <p className="ops-caption">{reason.length}/256 字符，操作者由后端登录上下文记录。</p>
        {actionError && <p className="ops-notice ops-error" role="alert">{actionError}</p>}
        <div className="ops-controls"><button className="button secondary" type="button" disabled={submitting} onClick={() => setAction(null)}>取消</button><button className="button primary" type="submit" disabled={submitting || !reason.trim()}>{submitting ? '提交中…' : `确认${actionLabels[action.kind]}`}</button></div>
      </form>}
    </Modal>
  </>;
}
