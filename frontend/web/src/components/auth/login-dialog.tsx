import { useEffect, useState, type FormEvent } from 'react';
import { ArrowRight, ShieldCheck } from '@phosphor-icons/react';
import { useApp } from '../../app-context';
import { auth } from '../../api/auth';
import { errorMessage } from '../../lib/utils';
import { Modal } from '../ui';

export function LoginDialog() {
  const { mode, loginOpen, setLoginOpen, setUser } = useApp();
  const [phone, setPhone] = useState('');
  const [code, setCode] = useState('');
  const [error, setError] = useState('');
  const [notice, setNotice] = useState('');
  const [busy, setBusy] = useState(false);
  const [sending, setSending] = useState(false);
  const [seconds, setSeconds] = useState(0);
  useEffect(() => {
    if (!seconds) return;
    const timer = setTimeout(() => setSeconds((value) => value - 1), 1000);
    return () => clearTimeout(timer);
  }, [seconds]);
  const validPhone = () => /^1[3-9]\d{9}$/.test(phone);
  const send = async () => {
    if (mode === 'preview' || sending || seconds > 0) return;
    setError(''); setNotice('');
    if (!validPhone()) { setError('请输入正确的11位手机号'); return; }
    setSending(true);
    try { await auth.code(phone); setSeconds(60); setNotice('验证码请求已发送，请查看已配置的接收渠道。'); }
    catch (error) { setError(errorMessage(error)); }
    finally { setSending(false); }
  };
  const submit = async (event: FormEvent) => {
    event.preventDefault(); setError('');
    if (mode === 'preview' || busy) return;
    if (!validPhone()) { setError('请输入正确的11位手机号'); return; }
    if (!/^\d{6}$/.test(code)) { setError('请输入6位验证码'); return; }
    setBusy(true);
    try {
      await auth.login(phone, code);
      const user = await auth.me();
      setUser(user); setLoginOpen(false); setCode('');
    } catch (error) { setError(errorMessage(error)); }
    finally { setBusy(false); }
  };
  return <Modal open={loginOpen} onOpenChange={setLoginOpen} title="登录，发现更多好生活" description={mode === 'preview' ? '当前为设计预览。连接后端后即可登录。' : '使用手机号验证码登录，新用户将自动注册。'}>
    <form onSubmit={submit} className="login-form">
      <label htmlFor="phone">手机号</label><input id="phone" type="tel" inputMode="numeric" autoComplete="tel" maxLength={11} value={phone} onChange={(e) => setPhone(e.target.value)} placeholder="请输入11位手机号" disabled={mode === 'preview'} />
      <label htmlFor="code">验证码</label><div className="code-input"><input id="code" inputMode="numeric" autoComplete="one-time-code" maxLength={6} value={code} onChange={(e) => setCode(e.target.value)} placeholder="请输入6位验证码" disabled={mode === 'preview'} /><button type="button" disabled={mode === 'preview' || sending || seconds > 0} onClick={send}>{seconds ? `${seconds} 秒后重试` : sending ? '正在发送' : '获取验证码'}</button></div>
      {error && <p className="form-error" role="alert">{error}</p>}{notice && <p className="form-notice" role="status">{notice}</p>}
      <button className="button primary full-width" type="submit" disabled={busy || mode === 'preview'}>{busy ? '正在登录' : '登录 / 注册'}<ArrowRight size={18} /></button>
      <p className="login-helper"><ShieldCheck size={16} />登录后7天内可自动恢复，请勿向他人透露验证码。</p>
    </form>
  </Modal>;
}
