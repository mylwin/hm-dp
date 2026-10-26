import { useCallback, useEffect, useRef, useState } from 'react';
import { CheckCircle, Clock, Copy, Info } from '@phosphor-icons/react';
import { querySeckillRequest, submitReliableSeckill, type SeckillRequestResult } from '../api/vouchers';
import { ApiError } from '../api/http';
import { money, voucherState } from '../lib/utils';
import type { Id, Voucher } from '../types';
import { Modal } from './ui';

type SavedRequest = { requestId: string; result?: SeckillRequestResult };
const isTerminal = (result?: SeckillRequestResult) => !!result && ['SUCCEEDED', 'REJECTED', 'CLOSED'].includes(result.status);

// 请求按用户和券隔离，刷新或重新打开弹窗后复用。
function loadRequest(key: string): SavedRequest | null {
  const raw = sessionStorage.getItem(key);
  if (!raw) return null;
  const value = JSON.parse(raw) as SavedRequest;
  if (!value || typeof value.requestId !== 'string' || !/^[A-Za-z0-9_-]{1,128}$/.test(value.requestId)) {
    throw new Error('保存的请求编号无法识别');
  }
  return value;
}

export function hasSeckillRequest(userId: Id, voucherId: Id): boolean {
  try { return sessionStorage.getItem(`hmdp-seckill-request:${userId}:${voucherId}`) !== null; }
  catch { return false; }
}

function pause(signal: AbortSignal): Promise<void> {
  return new Promise((resolve) => {
    const done = () => { clearTimeout(timer); signal.removeEventListener('abort', done); resolve(); };
    const timer = setTimeout(done, 1500);
    signal.addEventListener('abort', done, { once: true });
    if (signal.aborted) done();
  });
}

export function SeckillDialog({ userId, voucher, shopName, onClose, onSuccess }: {
  userId: Id; voucher: Voucher; shopName: string; onClose: () => void; onSuccess: () => void;
}) {
  const key = `hmdp-seckill-request:${userId}:${voucher.id}`;
  const [initial] = useState(() => {
    try { return { saved: loadRequest(key), error: '' }; }
    catch { return { saved: null, error: '暂时无法读取已有抢购进度，请检查浏览器设置后重试。' }; }
  });
  const [saved, setSaved] = useState<SavedRequest | null>(initial.saved);
  const savedRef = useRef(saved);
  const [busy, setBusy] = useState(false);
  const [checking, setChecking] = useState(false);
  const [notice, setNotice] = useState(initial.error);
  const [copyNotice, setCopyNotice] = useState('');
  const active = useRef(true);
  const inFlight = useRef(false);
  const cancellation = useRef<AbortController | null>(null);
  const onSuccessRef = useRef(onSuccess);
  onSuccessRef.current = onSuccess;

  const save = useCallback((next: SavedRequest) => {
    // 先保存请求编号再发送，避免刷新后误发新请求。
    sessionStorage.setItem(key, JSON.stringify(next));
    savedRef.current = next;
    if (active.current) setSaved(next);
  }, [key]);

  const acceptResult = useCallback((result: SeckillRequestResult) => {
    if (result.requestId !== savedRef.current?.requestId || result.voucherId !== String(voucher.id)) {
      throw new Error('返回的抢购信息不匹配，请继续确认原请求');
    }
    save({ requestId: result.requestId, result });
    if (result.status === 'SUCCEEDED') onSuccessRef.current();
  }, [save, voucher.id]);

  const check = useCallback(async () => {
    if (inFlight.current || !savedRef.current || isTerminal(savedRef.current.result)) return;
    inFlight.current = true;
    const controller = new AbortController();
    cancellation.current = controller;
    setChecking(true); setNotice('正在查询抢购结果…');
    try {
      for (let attempt = 0; attempt < 20 && !controller.signal.aborted; attempt++) {
        try {
          const result = await querySeckillRequest(savedRef.current.requestId, controller.signal);
          if (controller.signal.aborted) return;
          acceptResult(result);
          if (isTerminal(result)) { setNotice(''); return; }
        } catch (error) {
          if (controller.signal.aborted) return;
          // 暂时查不到不能证明提交未执行，继续使用原编号。
          if (!(error instanceof ApiError && error.status === 404)) throw error;
        }
        await pause(controller.signal);
      }
      if (active.current) setNotice('结果仍待确认，可继续查询或重试原请求。');
    } catch {
      if (active.current && !controller.signal.aborted) setNotice('暂时无法确认结果，请稍后继续查询；原请求编号已保留。');
    } finally {
      inFlight.current = false;
      if (active.current) setChecking(false);
    }
  }, [acceptResult]);

  useEffect(() => {
    active.current = true;
    let disposed = false;
    // 延后启动，兼容开发模式的 effect 挂载检查，避免恢复查询被立即取消。
    queueMicrotask(() => {
      if (!disposed && savedRef.current && !isTerminal(savedRef.current.result)) void check();
    });
    return () => { disposed = true; active.current = false; cancellation.current?.abort(); };
  }, [check]);

  const submit = async () => {
    if (initial.error || inFlight.current || isTerminal(savedRef.current?.result)) return;
    if (!savedRef.current && voucherState(voucher, Date.now()) !== '立即抢购') {
      setNotice('当前优惠券不可抢购，请刷新后查看最新状态。'); return;
    }
    inFlight.current = true; setBusy(true); setNotice('');
    const controller = new AbortController();
    cancellation.current = controller;
    try {
      if (!savedRef.current) save({ requestId: crypto.randomUUID() });
      const result = await submitReliableSeckill(voucher.id, savedRef.current!.requestId, controller.signal);
      if (!controller.signal.aborted) acceptResult(result);
    } catch {
      if (active.current) setNotice(savedRef.current
        ? '提交结果待确认，请使用已保留的请求继续查询或重试。'
        : '暂时无法保存抢购进度，请检查浏览器设置后重试。');
    } finally {
      inFlight.current = false;
      if (active.current) {
        setBusy(false);
        if (savedRef.current && !isTerminal(savedRef.current.result)) void check();
      }
    }
  };

  const newAttempt = () => {
    if (!isTerminal(savedRef.current?.result) || savedRef.current?.result?.status === 'SUCCEEDED') return;
    try {
      sessionStorage.removeItem(key);
      savedRef.current = null; setSaved(null); setNotice('');
    } catch { setNotice('暂时无法保存抢购进度，请检查浏览器设置后重试。'); }
  };

  const result = saved?.result;
  const succeeded = result?.status === 'SUCCEEDED';
  const terminal = isTerminal(result);
  const title = succeeded ? '订单创建成功' : terminal ? '本次抢购未成功' : saved ? '抢购结果确认中' : '确认抢购';
  return <Modal open onOpenChange={(open) => { if (!open && !busy) onClose(); }} title={title}
    description={succeeded ? '订单已创建，尚未支付。' : '请保留请求编号，提交超时也可继续确认原请求。'}>
    {!saved ? <div className="confirm-order"><strong>{shopName}</strong><p>{voucher.title}</p>
      <div className="confirm-price"><span>抢购价格</span><strong>¥{money(voucher.payValue)}</strong></div>
      {notice && <p role="alert" className="form-error">{notice}</p>}
      <button className="button primary full-width" onClick={submit} disabled={busy || !!initial.error}>{busy ? '正在提交，请稍候' : '确认抢购'}</button>
    </div> : <div className="order-result">
      {succeeded ? <CheckCircle size={42} /> : terminal ? <Info size={42} /> : <Clock size={42} />}
      <p role="status">{result?.statusDescription || '提交结果待确认'}</p>
      {result?.reasonDescription && <p>{result.reasonDescription}</p>}
      {result?.orderId && <><p>订单编号</p><strong className="order-id">{result.orderId}</strong></>}
      <p>请求编号</p><code className="request-id" style={{ overflowWrap: 'anywhere' }}>{saved.requestId}</code>
      <button className="button secondary full-width" onClick={async () => {
        try { await navigator.clipboard.writeText(result?.orderId || saved.requestId); setCopyNotice('已复制'); }
        catch { setCopyNotice('复制失败，请手动选择文字复制'); }
      }}><Copy size={17} />复制编号</button>
      {copyNotice && <p role="status">{copyNotice}</p>}
      {notice && <p role="status">{notice}</p>}
      {!terminal && <><button className="button primary full-width" onClick={() => void check()} disabled={busy || checking}>
        {checking ? '正在确认，请稍候' : '继续查询'}</button>
        <button className="button secondary full-width" onClick={submit} disabled={busy || checking}>
          {busy ? '正在提交，请稍候' : '重试原请求'}</button></>}
      {terminal && !succeeded && <button className="button secondary full-width" onClick={newAttempt}>重新发起抢购</button>}
      <p className="muted">{succeeded ? '订单创建成功不代表支付完成。' : terminal ? '本次请求已结束。' : '结果未确认前，请继续使用此请求，避免重复抢购。'}</p>
    </div>}
  </Modal>;
}
