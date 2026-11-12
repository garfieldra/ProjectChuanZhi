import { AccountSecurity } from './AccountSecurity'
import { useEffect, useLayoutEffect, useId, useRef, useState, type FormEvent, type RefObject } from 'react'
import { requestAuth, type AuthSession } from './auth'
import { savePlaybackSettings, usePlaybackSettings, type PlaybackSettings } from './playbackSettings'
import { settingsRequest, type PreferenceMemory } from './settingsApi'
import './settings.css'

type Props = { session: AuthSession; onClose: () => void; onSessionChange: (session: AuthSession, reason: 'profile' | 'logout') => void; returnFocusRef?: RefObject<HTMLElement> }
const sections = ['个人资料', '音乐偏好与记忆', '播放与外观', '账户与数据', '登录与安全']
export function AccountMenu({ session, onClose, onSessionChange, returnFocusRef }: Props) {
  const [section, updateSection] = useState(-1)
  const [direction, setDirection] = useState('forward')
  function setSection(next: number) {
    const depth = (value: number) => value === -1 ? 0 : value === -2 ? 1 : 2
    setDirection(depth(next) < depth(section) ? 'back' : 'forward')
    updateSection(next)
  }
  const [name, setName] = useState(session.displayName || '')
  const [savedName, setSavedName] = useState(session.displayName || '')
  const [busy, setBusy] = useState(false)
  const [notice, setNotice] = useState('')
  const [error, setError] = useState('')
  const [memories, setMemories] = useState<PreferenceMemory[]>([])
  const [loading, setLoading] = useState(false)
  const [memoryError, setMemoryError] = useState('')
  const [retry, setRetry] = useState(0)
  const [confirmId, setConfirmId] = useState<string | null>(null)
  const preferences = usePlaybackSettings()
  const modal = useRef<HTMLElement>(null)
  const inFlight = useRef(false)
  const titleId = useId()
  const anchor = useRef<HTMLElement | null>(returnFocusRef?.current || document.activeElement as HTMLElement)
  const restoreFocus = useRef(true)
  useLayoutEffect(() => {
    const position = () => {
      const panel = modal.current!, button = anchor.current?.getBoundingClientRect()
      if (!button) return
      const bounds = panel.getBoundingClientRect()
      const left = Math.max(12, Math.min(button.left < innerWidth / 2 ? button.left : button.right - bounds.width, innerWidth - bounds.width - 12))
      const below = innerHeight - button.bottom - 12
      const top = below >= bounds.height ? button.bottom + 8 : Math.max(12, button.top - bounds.height - 8)
      panel.style.left = `${left}px`; panel.style.top = `${top}px`
    }
    position(); window.addEventListener('resize', position); window.addEventListener('scroll', position, true)
    return () => { window.removeEventListener('resize', position); window.removeEventListener('scroll', position, true) }
  }, [section, memories.length, loading, error, notice, confirmId])
  useEffect(() => {
    const outside = (event: PointerEvent) => {
      if (!inFlight.current && !modal.current?.contains(event.target as Node) && !anchor.current?.contains(event.target as Node)) { restoreFocus.current = false; onClose() }
    }
    document.addEventListener('pointerdown', outside)
    return () => { document.removeEventListener('pointerdown', outside); if (restoreFocus.current) anchor.current?.focus() }
  }, [])
  useEffect(() => { modal.current?.querySelector<HTMLButtonElement>('button')?.focus({ preventScroll: true }) }, [section])
  useEffect(() => {
    if (section !== 1) return
    const controller = new AbortController()
    setLoading(true); setMemoryError(''); setConfirmId(null)
    void settingsRequest<PreferenceMemory[]>('/music-preference-memories', { signal: controller.signal }).then(value => { if (!controller.signal.aborted) setMemories(value) }).catch(reason => { if (!controller.signal.aborted) setMemoryError(reason instanceof Error ? reason.message : '暂时无法读取音乐记忆。') }).finally(() => { if (!controller.signal.aborted) setLoading(false) })
    return () => controller.abort()
  }, [section, retry])
  async function act(work: () => Promise<void>) {
    if (inFlight.current) return
    inFlight.current = true; setBusy(true); setError(''); setNotice('')
    try { await work() } catch (reason) { setError(reason instanceof Error ? reason.message : '操作没有完成，请重试。') }
    finally { inFlight.current = false; setBusy(false) }
  }
  function saveProfile(event: FormEvent) {
    event.preventDefault()
    void act(async () => {
      const value = name.trim()
      if (!value || Array.from(value).length > 80) throw new Error('称呼需要填写 1–80 个字符。')
      const updated = await settingsRequest<AuthSession>('/auth/profile', { method: 'PATCH', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({ displayName: value }) })
      setSavedName(updated.displayName || value); setName(updated.displayName || value); onSessionChange(updated, 'profile'); setNotice('称呼已保存。')
    })
  }
  function preference(patch: Partial<PlaybackSettings>) {
    setError(''); setNotice('')
    try { savePlaybackSettings(patch); setNotice('已保存到当前浏览器，并立即生效。') } catch (reason) { setError((reason as Error).message) }
  }
  return <section ref={modal} className={`account-popover ${section >= 0 ? 'has-detail' : ''}`} aria-label="账户菜单" onKeyDown={event => {
    if (event.key === 'Escape') { event.preventDefault(); event.stopPropagation(); if (inFlight.current) return; if (section >= 0) setSection(-2); else if (section === -2) setSection(-1); else onClose() }
    if (section < 0 && ['ArrowDown', 'ArrowUp', 'Home', 'End'].includes(event.key)) {
      const buttons = Array.from(modal.current!.querySelectorAll<HTMLButtonElement>('button:not(:disabled)'))
      const index = buttons.indexOf(document.activeElement as HTMLButtonElement)
      const next = event.key === 'Home' ? 0 : event.key === 'End' ? buttons.length - 1 : (index + (event.key === 'ArrowDown' ? 1 : -1) + buttons.length) % buttons.length
      event.preventDefault(); buttons[next]?.focus()
    }
  }}>
    <div key={section} className="account-menu-stage" data-direction={direction}>
    {section === -1 ? <>
      <div className="account-menu-identity"><span className="account-menu-avatar">{session.avatarUrl ? <img src={session.avatarUrl} alt="" /> : (savedName || '声').slice(0, 1)}</span><span><strong>{savedName}</strong><small>{session.email?.endsWith('@users.soundseek.local') ? 'GitHub 账户' : session.email}</small></span></div>
      <div className="account-menu-items">
        <button type="button" onClick={() => setSection(-2)}><SettingsIcon index={2} /><span>设置</span><svg viewBox="0 0 24 24" aria-hidden="true"><path d="m9 6 6 6-6 6" /></svg></button>
        <button type="button" onClick={() => setSection(1)}><SettingsIcon index={1} /><span>音乐偏好与记忆</span><svg viewBox="0 0 24 24" aria-hidden="true"><path d="m9 6 6 6-6 6" /></svg></button>
        <div className="account-menu-separator" />
        <button type="button" disabled={busy} onClick={() => void act(async () => { const updated = await requestAuth('logout'); onSessionChange(updated, 'logout') })}><svg viewBox="0 0 24 24" aria-hidden="true"><path d="M10 4H5v16h5M9 12h12m-4-4 4 4-4 4" /></svg><span>{busy ? '正在退出…' : '退出登录'}</span></button>
        {error && <p className="settings-error" role="alert">{error}</p>}
      </div>
    </> : <>
      <header className="account-submenu-header"><button type="button" aria-label="返回上一级菜单" disabled={busy} onClick={() => { setSection(section >= 0 ? -2 : -1); setNotice(''); setError('') }}><svg viewBox="0 0 24 24" aria-hidden="true"><path d="m14 6-6 6 6 6" /></svg></button><strong id={titleId}>{section === -2 ? '设置' : sections[section]}</strong><button type="button" aria-label="关闭账户菜单" disabled={busy} onClick={onClose}><svg viewBox="0 0 24 24" aria-hidden="true"><path d="m6 6 12 12M18 6 6 18" /></svg></button></header>
      {section === -2 ? <nav className="account-menu-items" aria-label="设置分类">{sections.map((title, i) => <button key={title} type="button" onClick={() => { setSection(i); setError(''); setNotice(''); setConfirmId(null) }}><SettingsIcon index={i} /><span>{title}</span><svg viewBox="0 0 24 24" aria-hidden="true"><path d="m9 6 6 6-6 6" /></svg></button>)}</nav> : <div className="settings-content">
        {section === 0 && <><p className="settings-description">更新你在寻声中的称呼。</p><div className="settings-profile-avatar">{session.avatarUrl ? <img src={session.avatarUrl} alt="账户头像" referrerPolicy="no-referrer" /> : (savedName || '声').slice(0, 1)}</div><p className="settings-avatar-hint">头像沿用登录账户。</p><form onSubmit={saveProfile}><label>称呼<input value={name} required maxLength={80} disabled={busy} onChange={event => setName(event.target.value)} autoComplete="nickname" /></label><label>邮箱<input value={session.email?.endsWith('@users.soundseek.local') ? 'GitHub 账户（未提供邮箱）' : session.email || '未提供邮箱'} readOnly /></label><button className="settings-primary" disabled={busy || !name.trim() || name.trim() === savedName} type="submit">{busy ? '正在保存…' : '保存更改'}</button></form></>}
        {section === 1 && <><p className="settings-description">查看寻声记住的音乐偏好。当前对话的要求始终优先。</p>{loading ? <p role="status">正在读取音乐记忆…</p> : memoryError ? <div className="settings-empty"><p role="alert">{memoryError}</p><button type="button" onClick={() => setRetry(value => value + 1)}>重新读取</button></div> : memories.length ? <ul className="settings-memories">{memories.map(memory => <li key={memory.id}><p>{memory.content}</p><div>{memory.sourceConversationId && <a href={`/explore?conversation=${encodeURIComponent(memory.sourceConversationId)}`} target="_blank" rel="noreferrer">查看来源对话</a>}{confirmId === memory.id ? <><span>移除这条记忆？</span><button disabled={busy} type="button" onClick={() => setConfirmId(null)}>取消</button><button disabled={busy} type="button" className="settings-danger" onClick={() => void act(async () => { await settingsRequest(`/music-preference-memories/${encodeURIComponent(memory.id)}`, { method: 'DELETE' }); setMemories(items => items.filter(item => item.id !== memory.id)); setConfirmId(null); setNotice('已移除这条音乐记忆，原始对话仍保留。') })}>确认移除</button></> : <button disabled={busy} type="button" onClick={() => setConfirmId(memory.id)}>移除记忆</button>}</div></li>)}</ul> : <div className="settings-empty"><strong>还在了解你的音乐口味</strong><p>继续聊聊喜欢的歌，确认的长期偏好会出现在这里。</p></div>}</>}
        {section === 2 && <><p className="settings-description">偏好保存在当前浏览器，刷新后仍然保留。</p><div className="settings-row"><label htmlFor="settings-volume">试听音量<small>调整后同步到正在播放的试听。</small></label><div><input id="settings-volume" type="range" min="0" max="100" value={Math.round(preferences.volume * 100)} onChange={event => preference({ volume: Number(event.target.value) / 100 })} /><output>{Math.round(preferences.volume * 100)}%</output></div></div><div className="settings-row"><label htmlFor="settings-spectrum">声音频谱<small>显示随真实音频变化的环形频谱。</small></label><input id="settings-spectrum" type="checkbox" role="switch" checked={preferences.spectrum} onChange={event => preference({ spectrum: event.target.checked })} /></div><div className="settings-row"><label htmlFor="settings-motion">减少动态效果<small>关闭唱片旋转与页面过渡；未开启时跟随系统。</small></label><input id="settings-motion" type="checkbox" role="switch" checked={preferences.reduceMotion} onChange={event => preference({ reduceMotion: event.target.checked })} /></div></>}
        {section === 4 && <AccountSecurity session={session} onSessionChange={onSessionChange} />}
        {section === 3 && <><p className="settings-description">管理你的账户与已保存内容。</p><dl className="settings-account"><div><dt>账户</dt><dd>{savedName}</dd></div><div><dt>登录身份</dt><dd>{session.email?.endsWith('@users.soundseek.local') ? 'GitHub 账户' : session.email}</dd></div><div><dt>对话与音乐记忆</dt><dd>随账户保留；音乐记忆可在对应分类中逐条管理。</dd></div><div><dt>播放与动效偏好</dt><dd>保存在当前浏览器。</dd></div></dl><button type="button" className="settings-secondary" disabled={busy} onClick={() => void act(async () => { const updated = await requestAuth('logout'); onSessionChange(updated, 'logout') })}>{busy ? '正在退出…' : '退出登录'}</button></>}
        <div className="settings-feedback" aria-live="polite">{notice && <p role="status">{notice}</p>}{error && <p className="settings-error" role="alert">{error}</p>}</div>
      </div>}
    </>}
    </div>
  </section>
}
function SettingsIcon({ index }: { index: number }) { const paths = ['M12 12a4 4 0 1 0 0-8 4 4 0 0 0 0 8ZM4 21v-2a8 6 0 0 1 16 0v2', 'M12 21 3 12a5 5 0 0 1 9-6 5 5 0 0 1 9 6Z', 'M4 6h16M4 12h16M4 18h16M8 3v6M16 9v6M10 15v6', 'M20 6c0 2-4 3-8 3S4 8 4 6s4-3 8-3 8 1 8 3ZM4 6v12c0 2 4 3 8 3s8-1 8-3V6M4 12c0 2 4 3 8 3s8-1 8-3']; return <svg viewBox="0 0 24 24" aria-hidden="true"><path d={paths[index % paths.length]} /></svg> }
