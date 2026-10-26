import { useRef, useState } from 'react';
import { CheckCircle, Copy } from '@phosphor-icons/react';
import { purchaseOrdinaryVoucher } from '../api/vouchers';
import { ApiError } from '../api/http';
import { errorMessage, money } from '../lib/utils';
import type { Voucher } from '../types';
import { Modal } from './ui';

export function OrdinaryVoucherDialog({ voucher, shopName, onClose, onSuccess }: {
  voucher: Voucher; shopName: string; onClose: () => void; onSuccess: () => void;
}) {
  const [orderId, setOrderId] = useState('');
  const [busy, setBusy] = useState(false);
  const [purchaseError, setPurchaseError] = useState('');
  const [copyNotice, setCopyNotice] = useState('');
  const purchasePending = useRef(false);

  async function purchase() {
    if (purchasePending.current || orderId) return;
    purchasePending.current = true;
    setBusy(true); setPurchaseError('');
    try {
      const savedOrderId = await purchaseOrdinaryVoucher(voucher.id);
      setOrderId(savedOrderId);
      onSuccess();
    } catch (error) {
      const resultUncertain = !(error instanceof ApiError) || (error.status !== undefined && error.status >= 500);
      setPurchaseError(resultUncertain
        ? `${errorMessage(error)} 如提交结果未确认，可重试购买找回原订单。`
        : errorMessage(error));
    } finally {
      purchasePending.current = false;
      setBusy(false);
    }
  }

  return <Modal open onOpenChange={(open) => { if (!open && !purchasePending.current) onClose(); }}
    title={orderId ? '订单已确认' : '确认购买'}
    description={orderId ? '订单已保存，本次操作未执行扣款。' : '确认商家、价格与使用规则后，创建普通券订单。'}>
    {orderId ? <div className="order-result">
      <CheckCircle size={42} />
      <p>订单编号</p><strong className="order-id">{orderId}</strong>
      <button className="button secondary full-width" onClick={async () => {
        try { await navigator.clipboard.writeText(orderId); setCopyNotice('已复制'); }
        catch { setCopyNotice('复制失败，请手动选择文字复制'); }
      }}><Copy size={17} />复制编号</button>
      {copyNotice && <p role="status">{copyNotice}</p>}
      <p className="muted">创建订单不代表完成支付，重复购买会返回已有订单编号。</p>
    </div> : <div className="confirm-order">
      <strong>{shopName}</strong><p>{voucher.title}</p>
      <div className="confirm-price"><span>购买价格</span><strong>¥{money(voucher.payValue)}</strong></div>
      <p className="muted">{voucher.subTitle}</p>
      {purchaseError && <p role="alert" className="form-error">{purchaseError}</p>}
      <button className="button primary full-width" onClick={() => void purchase()} disabled={busy}>
        {busy ? '正在创建订单…' : '确认购买'}
      </button>
    </div>}
  </Modal>;
}
