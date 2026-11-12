import { clearReauth, executeReauth, loadReauth, saveReauth, type ReauthAction, type PendingReauth } from './accountReauthFlow'
import { AuthDialog } from './AuthDialog'
import { useEffect, useState, type FormEvent } from 'react'
import { accountRequest, notifyAuthChanged, requestAuth, type AuthSession } from './auth'

export function AccountSecurity({ session, onSessionChange }: { session: AuthSession; onSessionChange: (session: AuthSession, reason: 'profile' | 'logout') => void }) {
  const [password, setPassword] = useState(''), [next, setNext] = useState(''), [email, setEmail] = useState('')
  const [notice, setNotice] = useState(''), [busy, setBusy] = useState(false)
  const passwordLogin = session.loginMethods?.includes('PASSWORD') ?? !!session.email
  async function act(work: () => Promise<string>) { if (busy) return; setBusy(true); setNotice(''); try { setNotice(await work()); setPassword(''); setNext('') } catch (e) { setNotice(e instanceof Error ? e.message : '操作未完成。') } finally { setBusy(false) } }
  async function reauth(intent: ReauthAction) { const result = await accountRequest<{ redirectUrl?: string }>('/auth/reauth', { password }); if (result.redirectUrl) { if (!session.userId) throw new Error('登录状态已失效。'); saveReauth(session.userId, intent); location.assign(result.redirectUrl); throw new Error('正在前往 GitHub 验证…') } }
  function submit(event: FormEvent, work: () => Promise<string>) { event.preventDefault(); void act(work) }
  return <section aria-label="登录与安全"><p>账户 ID：{session.userId}</p><p>联系邮箱：{session.email || '尚未添加'} · {session.emailVerified ? '已验证' : '未验证'}</p>
    <p>登录方式：{passwordLogin ? '邮箱密码' : 'GitHub'}</p>
    <label>当前密码{passwordLogin ? <input type="password" value={password} autoComplete="current-password" maxLength={128} onChange={e => setPassword(e.target.value)} disabled={busy} /> : <span>敏感操作将前往 GitHub 重新验证。</span>}</label>
    {!session.emailVerified && session.email && <button type="button" disabled={busy} onClick={() => void act(async () => { await reauth({ action: 'VERIFY_EMAIL' }); return (await accountRequest('/auth/email-verification/request')).message })}>验证当前邮箱</button>}
    {passwordLogin && <form onSubmit={e => submit(e, async () => { const r = await accountRequest('/me/password', { currentPassword: password, password: next }); await requestAuth('session'); notifyAuthChanged(); return r.message })}><label>新密码<input type="password" required minLength={10} maxLength={128} autoComplete="new-password" value={next} onChange={e => setNext(e.target.value)} disabled={busy} /></label><small>至少 10 个字符，最多 72 个 UTF-8 字节。</small><button disabled={busy || !password}>修改密码并退出其他设备</button></form>}
    <form onSubmit={e => submit(e, async () => { await reauth({ action: 'CHANGE_EMAIL', email }); return (await accountRequest('/me/email-change/request', { email })).message })}><label>{session.email ? '新邮箱' : '添加联系邮箱'}<input type="email" required maxLength={320} value={email} onChange={e => setEmail(e.target.value)} disabled={busy} /></label><button disabled={busy}>发送邮箱确认邮件</button></form>
    <button type="button" disabled={busy} onClick={() => { if (confirm('确认退出全部设备？当前设备也需要重新登录。')) void act(async () => { await reauth({ action: 'LOGOUT_ALL' }); await accountRequest('/auth/logout-all'); const value = await requestAuth('session'); onSessionChange(value, 'logout'); notifyAuthChanged(); return '全部设备已退出。' }) }}>退出全部设备</button>
    <p>账号停用请联系本站维护者，处理前会核实身份与数据范围。当前版本不提供数据物理删除。</p>
    {notice && <p role="status">{notice}</p>}
  </section>
}

export function AccountConfirmation({ initialHash }: { initialHash: string }) {
  const params = new URLSearchParams(initialHash.split('?')[1] || '')
  const [token] = useState(params.get('token') || ''), [purpose] = useState(params.get('purpose') || '')
  const [password, setPassword] = useState(''), [notice, setNotice] = useState(''), [busy, setBusy] = useState(false), [done, setDone] = useState(false)
  const [session, setSession] = useState<AuthSession | null>(null), [loginOpen, setLoginOpen] = useState(false)
  useEffect(() => { void requestAuth('session').then(setSession).catch(() => setNotice('暂时无法读取登录状态，请稍后重试。')) }, [])
  // Remove the secret from history before loading account state or other application assets.
  useEffect(() => { if (location.hash.startsWith('#auth-confirm?')) history.replaceState(null, '', location.pathname + location.search + '#auth-confirm') }, [])
  async function confirmAction(e: FormEvent) { e.preventDefault(); if (busy) return; setBusy(true); try {
    await requestAuth('session')
    if (purpose === 'CHANGE_EMAIL') {
      const auth = await accountRequest<{ redirectUrl?: string }>('/auth/reauth', { password, returnToken: token })
      if (auth.redirectUrl) { setNotice('正在前往 GitHub 验证，完成后将返回本次邮箱确认。'); location.assign(auth.redirectUrl); return }
    }
    const path = ({ REGISTER: '/auth/registration/confirm', RESET: '/auth/password-reset/confirm', VERIFY: '/auth/email-verification/confirm', CHANGE_EMAIL: '/me/email-change/confirm' } as Record<string,string>)[purpose]
    if (!path || !token) throw new Error('链接无效，请重新申请。')
    const value = await accountRequest<{ message?: string; authenticated?: boolean }>(path, { token, password })
    setPassword(''); setDone(true); setNotice(value.message || '邮箱确认成功。'); notifyAuthChanged()
  } catch (error) { setNotice(error instanceof Error ? error.message : '确认失败。') } finally { setBusy(false) } }
  return <main className="account-confirmation"><h1>{purpose === 'RESET' ? '设置新密码' : '确认账号邮箱'}</h1><p>请核对本次操作。链接到期或已使用时需要重新申请。</p>{['VERIFY', 'CHANGE_EMAIL'].includes(purpose) && !session?.authenticated && <><p>请先登录本次操作对应的账户；链接会保留在当前页面中。</p><button onClick={() => setLoginOpen(true)}>登录以确认邮箱</button></>}{loginOpen && <AuthDialog session={session} onClose={() => setLoginOpen(false)} onSessionChange={value => { setSession(value); setLoginOpen(false) }} />}<form onSubmit={e => void confirmAction(e)}>{!done && <>
    {purpose !== 'VERIFY' && <label>{purpose === 'RESET' ? '新密码' : purpose === 'REGISTER' ? '注册时的密码（跨设备确认需要）' : '当前密码（GitHub 用户可留空）'}<input type="password" autoComplete={purpose === 'RESET' ? 'new-password' : 'current-password'} required={purpose === 'RESET'} minLength={purpose === 'RESET' ? 10 : undefined} maxLength={128} value={password} onChange={e => setPassword(e.target.value)} /></label>}
    <button disabled={busy || (['VERIFY', 'CHANGE_EMAIL'].includes(purpose) && !session?.authenticated)}>{busy ? '正在确认…' : '确认操作'}</button></>}{notice && <p role="status">{notice}</p>}</form><a href="/">返回寻声</a></main>
}

export function AccountReauthReturn() {
  const [session, setSession] = useState<AuthSession | null>(null)
  const [pending, setPending] = useState<PendingReauth | null>(null)
  const [notice, setNotice] = useState('正在读取登录状态…'), [busy, setBusy] = useState(false)
  useEffect(() => { let active = true
    void requestAuth('session').then(value => { if (!active) return
      setSession(value)
      const intent = value.authenticated && value.userId ? loadReauth(value.userId) : (clearReauth(), null)
      setPending(intent); setNotice(intent ? '身份验证完成，请核对并继续本次操作。' : '没有可继续的操作，请返回设置。')
    }).catch(() => { if (active) setNotice('无法读取登录状态，请稍后重试。') })
    return () => { active = false }
  }, [])
  async function proceed() {
    if (busy || !session?.userId) return
    setBusy(true)
    try { setNotice(await executeReauth(session.userId)); setPending(null); notifyAuthChanged() }
    catch (e) { setNotice(e instanceof Error ? e.message : '操作未完成。') }
    finally { setBusy(false) }
  }
  return <main className="account-confirmation"><h1>继续账号安全操作</h1>
    {pending && <><p>{pending.action === 'CHANGE_EMAIL' ? `发送确认邮件到：${pending.email}` : pending.action === 'LOGOUT_ALL' ? '退出全部设备，包括当前设备。' : '发送当前邮箱的验证邮件。'}</p>
      <button disabled={busy} onClick={() => void proceed()}>确认继续</button>
      <button disabled={busy} onClick={() => { clearReauth(); setPending(null); setNotice('本次操作已取消。') }}>取消操作</button></>}
    <p role="status">{notice}</p><a href="/">返回寻声</a></main>
}
