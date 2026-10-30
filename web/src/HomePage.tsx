import { Fragment, useRef, useState, type FormEvent, type MouseEvent } from 'react'
import { BrandMark } from './BrandMark'
import { beginExploration, readHomeDraft, saveHomeDraft, type ExplorationEntry } from './landingEntry'
import './home.css'

type Props = {
  onExplore: (conversationId?: string, content?: string) => Promise<void>
  preloadWorkspace: () => Promise<unknown>
}

function ArrowIcon() {
  return <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.6" aria-hidden="true"><path d="M4 12h15M13 6l6 6-6 6" /></svg>
}

export function HomePage({ onExplore, preloadWorkspace }: Props) {
  const [draft, setDraft] = useState(readHomeDraft)
  const [connecting, setConnecting] = useState(false)
  const [error, setError] = useState('')
  const textarea = useRef<HTMLTextAreaElement>(null)
  const attempt = useRef<ExplorationEntry | null>(null)
  const submitting = useRef(false)

  function updateDraft(value: string) {
    setDraft(value); saveHomeDraft(value); setError('')
  }

  async function submit(event: FormEvent) {
    event.preventDefault()
    const content = draft.trim()
    if (!content || submitting.current) return
    submitting.current = true; setConnecting(true); setError('')
    if (!attempt.current || attempt.current.content !== content) attempt.current = { content, messageKey: crypto.randomUUID() }
    try {
      const [entryResult, workspaceResult] = await Promise.allSettled([beginExploration(attempt.current), preloadWorkspace()])
      if (entryResult.status === 'rejected') throw entryResult.reason
      if (workspaceResult.status === 'rejected') throw new Error('探索页面暂时没有打开，你的输入已保留，请再试一次。')
      await onExplore(entryResult.value, content)
      saveHomeDraft('')
    } catch (caught) {
      setError(caught instanceof Error && !/fetch|network|load failed/i.test(caught.message) ? caught.message : '暂时没有连接上寻声，你的输入已保留，请稍后重试。')
    } finally { submitting.current = false; setConnecting(false) }
  }

  function continueExploring(event: MouseEvent<HTMLAnchorElement>) {
    if (event.metaKey || event.ctrlKey || event.shiftKey || event.altKey) return
    event.preventDefault()
    if (submitting.current) return
    submitting.current = true
    void onExplore().catch(() => setError('页面暂时没有打开，请再试一次。')).finally(() => { submitting.current = false })
  }

  return <div className="home-page">
    <a className="home-skip-link" href="#home-prompt">跳到音乐探索</a>
    <header className="home-header">
      <a className="home-brand" href="/" aria-label="寻声 SoundSeek 首页"><BrandMark /><span>寻声</span><small>SoundSeek</small></a>
      <nav aria-label="主导航">
        <a href="#about">关于寻声</a>
        <a href="/explore" className="home-enter" onClick={continueExploring} onMouseEnter={() => { void preloadWorkspace().catch(() => {}) }} onFocus={() => { void preloadWorkspace().catch(() => {}) }}>进入探索<ArrowIcon /></a>
      </nav>
    </header>
    <main className="home-main">
      <section className="home-hero" aria-labelledby="home-heading">
        <div className="home-intro">
          <h1 id="home-heading"><span>总有一种声音，</span><span>与你<em>不期而遇。</em></span></h1>
          <p>从一首喜欢的歌出发，发现新的作品，<br className="home-desktop-break" />也更懂自己的音乐偏好。</p>
        </div>
        <figure className="home-artwork"><img src="/images/soundseek-vinyl.webp" width="1612" height="976" alt="砖红色寻声唱片封套，搭配黑胶唱片与一枝干花" fetchPriority="high" decoding="async" /></figure>
      </section>
      <section className="home-entry" aria-label="开始一次音乐探索">
        <form onSubmit={event => void submit(event)} aria-busy={connecting}>
          <div className="home-composer-frame">
            <label className="home-visually-hidden" htmlFor="home-prompt">告诉寻声，你最近想听什么？</label>
            <textarea id="home-prompt" ref={textarea} value={draft} onChange={event => updateDraft(event.target.value)} maxLength={2000} readOnly={connecting} placeholder="告诉寻声，你最近想听什么？" onFocus={() => { void preloadWorkspace().catch(() => {}) }} onKeyDown={event => {
              if (event.key === 'Enter' && !event.shiftKey && !event.nativeEvent.isComposing) { event.preventDefault(); event.currentTarget.form?.requestSubmit() }
            }} aria-describedby={error ? 'home-entry-error' : 'home-entry-hint'} />
            <div className="home-composer-toolbar">
              <p id="home-entry-hint">从喜欢的歌、某位艺人，或此刻的心情开始</p>
              <button className="home-submit" type="submit" disabled={!draft.trim() || connecting} aria-label={connecting ? '正在开启探索' : '开始探索'}>{connecting ? <><span className="home-connecting-mark" aria-hidden="true" />正在开启探索</> : <>开始探索<ArrowIcon /></>}</button>
            </div>
          </div>
          {error ? <p className="home-entry-error" id="home-entry-error" role="alert">{error}</p> : null}
          <span className="home-visually-hidden" role="status">{connecting ? '正在为你开启音乐探索，请稍候。' : ''}</span>
        </form>
        <div className="home-prompts" aria-label="试试这些音乐话题">
          {['夜晚散步时听什么', '想了解一首歌的故事'].map((text, index) => <Fragment key={text}>{index > 0 ? <span className="home-prompt-divider" aria-hidden="true">/</span> : null}<button type="button" disabled={connecting} onClick={() => { updateDraft(text); textarea.current?.focus() }}>{text}</button></Fragment>)}
        </div>
      </section>
      <section className="home-about" id="about" aria-label="关于寻声">
        <ol>{['发现新的音乐', '听懂喜欢的作品', '在选择中认识自己'].map((text, index) => <li key={text}><span aria-hidden="true">0{index + 1}</span><p>{text}</p></li>)}</ol>
      </section>
    </main>
    <footer className="home-footer">寻声 <span>SoundSeek</span></footer>
  </div>
}
