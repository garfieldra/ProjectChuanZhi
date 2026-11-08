import { useEffect, useId, useRef, useState, type FormEvent, type RefObject } from 'react'
import { requestAuth, type AuthSession } from './auth'
import './auth.css'

type Props = {
  session: AuthSession | null
  onClose: () => void
  onSessionChange: (session: AuthSession) => void
  returnFocusRef?: RefObject<HTMLElement>
}

export function AuthDialog({ session, onClose, onSessionChange, returnFocusRef }: Props) {
  const [registering, setRegistering] = useState(false)
  const [email, setEmail] = useState('')
  const [password, setPassword] = useState('')
  const [displayName, setDisplayName] = useState('')
  const [error, setError] = useState('')
  const [submitting, setSubmitting] = useState(false)
  const dialog = useRef<HTMLDialogElement>(null)
  const inFlight = useRef(false)
  const titleId = useId()
  const descriptionId = useId()

  useEffect(() => {
    const element = dialog.current!
    const previousFocus = document.activeElement as HTMLElement | null
    const previousOverflow = document.body.style.overflow
    element.showModal()
    element.querySelector('input')?.focus()
    document.body.style.overflow = 'hidden'
    return () => { element.close(); document.body.style.overflow = previousOverflow; (returnFocusRef?.current || previousFocus)?.focus() }
  }, [])

  async function submit(event?: FormEvent) {
    event?.preventDefault()
    if (inFlight.current) return
    inFlight.current = true; setSubmitting(true); setError('')
    try {
      const action = session?.authenticated ? 'logout' : registering ? 'register' : 'login'
      const body = session?.authenticated ? undefined : registering ? { email, password, displayName } : { email, password }
      const updated = await requestAuth(action, body)
      if (action !== 'logout' && !updated.authenticated) throw new Error('登录没有完成，请再试一次。')
      onSessionChange(updated)
    } catch (reason) { setError(reason instanceof Error ? reason.message : '账户操作没有完成，请重试。') }
    finally { inFlight.current = false; setSubmitting(false) }
  }

  return <dialog ref={dialog} className="auth-dialog" aria-labelledby={titleId} aria-describedby={descriptionId} onCancel={event => { event.preventDefault(); if (!inFlight.current) onClose() }} onClick={event => {
    if (event.target !== event.currentTarget || inFlight.current) return
    const bounds = event.currentTarget.getBoundingClientRect()
    if (event.clientX < bounds.left || event.clientX > bounds.right || event.clientY < bounds.top || event.clientY > bounds.bottom) onClose()
  }}>
    <button className="auth-dialog-close" type="button" onClick={onClose} disabled={submitting} aria-label="关闭账户窗口"><svg viewBox="0 0 24 24" aria-hidden="true"><path d="m6 6 12 12M18 6 6 18" /></svg></button>
    <p className="eyebrow">{session?.authenticated ? '我的账户' : '同步音乐探索'}</p>
    <h2 id={titleId}>{session?.authenticated ? session.displayName || '寻声听友' : registering ? '创建账户' : '欢迎回来'}</h2>
    <p id={descriptionId}>{session?.authenticated ? session.email : '登录后，当前访客对话会自动保留并同步到你的账户。'}</p>
    {session?.authenticated ? <>{error && <p className="auth-error" role="alert">{error}</p>}<button className="auth-secondary" type="button" onClick={() => void submit()} disabled={submitting}>{submitting ? '正在退出…' : '退出登录'}</button></> : <>
      <form onSubmit={event => void submit(event)} aria-busy={submitting}>
        <fieldset disabled={submitting}>
          {registering && <label>称呼<input value={displayName} maxLength={80} onChange={event => setDisplayName(event.target.value)} autoComplete="name" /></label>}
          <label>邮箱<input type="email" required maxLength={320} value={email} onChange={event => setEmail(event.target.value)} autoComplete="email" /></label>
          <label>密码<input type="password" required minLength={registering ? 10 : undefined} maxLength={128} value={password} onChange={event => setPassword(event.target.value)} autoComplete={registering ? 'new-password' : 'current-password'} /></label>
          {registering && <small className="auth-password-hint">密码至少 10 位。</small>}
          {error && <p className="auth-error" role="alert">{error}</p>}
          <button className="auth-primary" type="submit">{submitting ? '正在处理…' : registering ? '创建并登录' : '登录'}</button>
        </fieldset>
      </form>
      {session?.githubEnabled && !submitting && <a className="auth-github" href="/oauth2/authorization/github">使用 GitHub 登录</a>}
      <button className="auth-switch" type="button" disabled={submitting} onClick={() => { setRegistering(value => !value); setError(''); setPassword('') }}>{registering ? '已有账户？直接登录' : '还没有账户？创建一个'}</button>
    </>}
  </dialog>
}
