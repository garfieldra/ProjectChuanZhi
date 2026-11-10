import { useEffect, useRef, useState, type MouseEvent } from 'react'
import { BrandMark } from './BrandMark'
import { AccountDialog } from './AccountDialog'
import { requestAuth, type AuthSession } from './auth'

const explorationModes = [
  { title: '发现音乐', detail: '从心情和聆听场景，找到新的声音', prompt: '想找一些适合夜晚散步、克制但不阴郁的音乐。' },
  { title: '了解作品', detail: '聊聊一首歌的表达、背景与故事', prompt: '我想深入了解一首喜欢的歌，可以从哪些角度开始？' },
  { title: '探索偏好', detail: '从喜欢的作品里，看见自己的口味', prompt: '我想聊聊最近反复听的几首歌，梳理自己的音乐偏好。' },
]

type Props = {
  onSelectPrompt: (prompt: string) => void
  onExplore: (event: MouseEvent<HTMLAnchorElement>) => void
  preloadWorkspace: () => Promise<unknown>
  busy: boolean
}

export function HomeHeader({ onSelectPrompt, onExplore, preloadWorkspace, busy }: Props) {
  const [panel, setPanel] = useState<'explore' | 'account' | null>(null)
  const [mobileOpen, setMobileOpen] = useState(false)
  const [session, setSession] = useState<AuthSession | null>(null)
  const [sessionLoading, setSessionLoading] = useState(true)
  const [sessionError, setSessionError] = useState('')
  const [authOpen, setAuthOpen] = useState(false)
  const [sessionAttempt, setSessionAttempt] = useState(0)
  const header = useRef<HTMLElement>(null)
  const exploreTrigger = useRef<HTMLButtonElement>(null)
  const accountTrigger = useRef<HTMLButtonElement>(null)
  const mobileTrigger = useRef<HTMLButtonElement>(null)

  useEffect(() => {
    let active = true
    setSessionLoading(true); setSessionError('')
    void requestAuth('session').then(value => { if (active) setSession(value) }).catch(error => {
      if (active) setSessionError(error instanceof Error ? error.message : '账户连接暂时不可用。')
    }).finally(() => { if (active) setSessionLoading(false) })
    return () => { active = false }
  }, [sessionAttempt])

  useEffect(() => {
    if (authOpen || (!panel && !mobileOpen)) return
    const pointer = (event: PointerEvent) => {
      if (!header.current?.contains(event.target as Node)) { setPanel(null); setMobileOpen(false) }
    }
    const keyboard = (event: KeyboardEvent) => {
      if (event.key !== 'Escape') return
      event.preventDefault()
      if (panel) { (panel === 'explore' ? exploreTrigger : accountTrigger).current?.focus(); setPanel(null) }
      else { setMobileOpen(false); mobileTrigger.current?.focus() }
    }
    document.addEventListener('pointerdown', pointer)
    document.addEventListener('keydown', keyboard)
    return () => { document.removeEventListener('pointerdown', pointer); document.removeEventListener('keydown', keyboard) }
  }, [panel, mobileOpen, authOpen])

  function closeNavigation() { setPanel(null); setMobileOpen(false) }
  function openAuth() { setPanel(null); setAuthOpen(value => !value) }

  return <>
    <header ref={header} className="home-header" onBlur={event => {
      if (!authOpen && event.relatedTarget && !event.currentTarget.contains(event.relatedTarget as Node)) closeNavigation()
    }}>
      <a className="home-brand" href="/" aria-label="寻声 SoundSeek 首页"><BrandMark /><span>寻声</span><small>SoundSeek</small></a>
      <nav id="home-navigation" className={`home-navigation ${mobileOpen ? 'is-open' : ''}`} aria-label="主导航">
        <div className="home-nav-disclosure">
          <button ref={exploreTrigger} type="button" className="home-nav-link" aria-expanded={panel === 'explore'} aria-controls="home-exploration-modes" onClick={() => setPanel(value => value === 'explore' ? null : 'explore')}>探索方式<Chevron /></button>
          {panel === 'explore' && <div className="home-nav-popover home-modes" id="home-exploration-modes">
            {explorationModes.map((mode, index) => <button key={mode.title} type="button" disabled={busy} onClick={() => { closeNavigation(); onSelectPrompt(mode.prompt) }}><span className="home-mode-index">0{index + 1}</span><span><strong>{mode.title}</strong><small>{mode.detail}</small></span><Arrow /></button>)}
          </div>}
        </div>
        <a className="home-nav-link" href="#about" onClick={closeNavigation}>关于寻声</a>
        <div className="home-nav-disclosure home-account">
          <button ref={accountTrigger} type="button" className={`home-nav-link ${session?.authenticated ? 'home-account-trigger' : ''}`} disabled={sessionLoading || busy} aria-haspopup={session?.authenticated ? undefined : "dialog"} aria-expanded={authOpen} aria-label={sessionLoading ? '正在连接账户' : sessionError ? '重试账户连接' : session?.authenticated ? `账户：${session.displayName || '寻声听友'}` : '登录账户'} title={sessionError || undefined} onClick={() => {
            if (sessionError) { setSessionAttempt(value => value + 1); return }
            openAuth()
          }}>{sessionLoading ? '账户…' : sessionError ? '重试连接' : session?.authenticated ? <><span className="home-avatar">{session.avatarUrl ? <img src={session.avatarUrl} alt="" referrerPolicy="no-referrer" /> : (session.displayName || '寻').slice(0, 1)}</span><span className="home-account-name">{session.displayName || '寻声听友'}</span><Chevron /></> : '登录'}</button>

        </div>
      </nav>
      <div className="home-header-actions">
        <a href="/explore" className="home-enter" onClick={event => { closeNavigation(); onExplore(event) }} onMouseEnter={() => { void preloadWorkspace().catch(() => {}) }} onFocus={() => { void preloadWorkspace().catch(() => {}) }}>进入探索<Arrow /></a>
        <button ref={mobileTrigger} className="home-menu-toggle" type="button" aria-label={mobileOpen ? '关闭导航菜单' : '打开导航菜单'} aria-expanded={mobileOpen} aria-controls="home-navigation" onClick={() => { setMobileOpen(value => !value); setPanel(null) }}><svg viewBox="0 0 24 24" aria-hidden="true">{mobileOpen ? <path d="m6 6 12 12M18 6 6 18" /> : <path d="M4 7h16M4 12h16M4 17h16" />}</svg></button>
      </div>
      {sessionError && <p className="home-account-error" role="status">{sessionError}</p>}
    </header>
    {authOpen && <AccountDialog session={session} returnFocusRef={mobileOpen ? mobileTrigger : accountTrigger} onClose={() => setAuthOpen(false)} onSessionChange={(value, reason) => { setSession(value); setSessionError(''); if (reason !== 'profile') { setAuthOpen(false); closeNavigation() } }} />}
  </>
}

function Chevron() { return <svg className="home-chevron" viewBox="0 0 16 16" aria-hidden="true"><path d="m4 6 4 4 4-4" /></svg> }
function Arrow() { return <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.6" aria-hidden="true"><path d="M4 12h15M13 6l6 6-6 6" /></svg> }
