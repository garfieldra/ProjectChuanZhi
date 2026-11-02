import { readPlaybackSettings, syncMotionPreference } from './playbackSettings'
import { useEffect, useRef, useState, type ComponentType } from 'react'
import { flushSync } from 'react-dom'
import { HomePage } from './HomePage'
import type { ConversationAppProps } from './ConversationApp'

type Workspace = ComponentType<ConversationAppProps>
let workspacePromise: Promise<Workspace> | undefined
const loadWorkspace = () => workspacePromise ??= import('./ConversationApp').then(module => module.ConversationApp).catch(error => { workspacePromise = undefined; throw error })

function locationState() {
  return { explore: window.location.pathname.replace(/\/$/, '') === '/explore', conversationId: new URLSearchParams(window.location.search).get('conversation') || undefined }
}

type TransitionDocument = Document & { startViewTransition?: (update: () => void) => { finished: Promise<void>; updateCallbackDone: Promise<void> } }
async function transition(update: () => void) {
  const doc = document as TransitionDocument
  if (readPlaybackSettings().reduceMotion || window.matchMedia('(prefers-reduced-motion: reduce)').matches) { update(); return }
  if (doc.startViewTransition) {
    const result = doc.startViewTransition(() => flushSync(update))
    void result.finished.catch(() => {})
    await result.updateCallbackDone
    return
  }
  // Safari/WebViews without shared-element transitions still get a short,
  // deliberate handoff. The message is already safely queued at this point.
  const root = document.getElementById('root')
  if (!root?.animate) { update(); return }
  const outgoing = root.animate([{ opacity: 1, transform: 'translateY(0)' }, { opacity: 0, transform: 'translateY(-6px)' }], { duration: 140, easing: 'ease-in', fill: 'forwards' })
  try {
    await outgoing.finished
    flushSync(update)
  } finally { outgoing.cancel() }
  const incoming = root.animate([{ opacity: 0, transform: 'translateY(6px)' }, { opacity: 1, transform: 'translateY(0)' }], { duration: 220, easing: 'ease-out' })
  await incoming.finished.catch(() => {})
}

export function SoundSeekApp() {
  useEffect(() => { syncMotionPreference() }, [])
  const [route, setRoute] = useState(locationState)
  const routeLocation = useRef(route)
  const [WorkspaceView, setWorkspace] = useState<Workspace | null>(null)
  const [initialContent, setInitialContent] = useState('')
  const [loadError, setLoadError] = useState(false)

  useEffect(() => {
    const onPopState = () => {
      const next = locationState()
      // Fragment navigation belongs to the browser. A page transition here
      // would cancel the anchor scroll and move controls under the pointer.
      if (next.explore === routeLocation.current.explore && next.conversationId === routeLocation.current.conversationId) return
      routeLocation.current = next
      setInitialContent(''); void transition(() => { if (routeLocation.current === next) setRoute(next) }); window.scrollTo(0, 0)
    }
    window.addEventListener('popstate', onPopState)
    return () => window.removeEventListener('popstate', onPopState)
  }, [])

  useEffect(() => {
    if (!route.explore || WorkspaceView) return
    let cancelled = false
    void loadWorkspace().then(view => { if (!cancelled) setWorkspace(() => view) }).catch(() => { if (!cancelled) setLoadError(true) })
    return () => { cancelled = true }
  }, [route.explore, WorkspaceView])

  useEffect(() => { document.title = route.explore ? '音乐探索 · 寻声 SoundSeek' : '寻声 SoundSeek · AI 音乐探索' }, [route.explore])

  async function explore(conversationId?: string, content = '') {
    const view = await loadWorkspace()
    const url = conversationId ? `/explore?conversation=${encodeURIComponent(conversationId)}` : '/explore'
    await transition(() => {
      window.history.pushState({}, '', url)
      setWorkspace(() => view); setInitialContent(content); routeLocation.current = locationState(); setRoute(routeLocation.current); setLoadError(false)
    })
    window.scrollTo(0, 0)
  }

  function home() {
    void transition(() => { window.history.pushState({}, '', '/'); routeLocation.current = locationState(); setRoute(routeLocation.current); setInitialContent('') })
    window.scrollTo(0, 0)
  }

  if (!route.explore) return <HomePage onExplore={explore} preloadWorkspace={loadWorkspace} />
  if (WorkspaceView) return <WorkspaceView key={route.conversationId || "recent"} initialConversationId={route.conversationId} initialContent={initialContent} onHome={home} />
  return <main className="workspace-loading" aria-live="polite"><p>{loadError ? '探索页面暂时没有打开。' : '正在打开音乐探索…'}</p><button type="button" onClick={home}>返回寻声首页</button></main>
}
