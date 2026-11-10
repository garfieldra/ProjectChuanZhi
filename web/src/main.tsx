import { StrictMode, useEffect, useState } from 'react'
import { createRoot } from 'react-dom/client'
import { SoundSeekApp } from './SoundSeekApp'
import './designTokens.css'
import './styles.css'
import { KnowledgeAdmin } from './KnowledgeAdmin'
import { apiFetch } from './apiClient'
import { AUTH_SESSION_CHANGED_EVENT } from './auth'

function Application() {
  const [page, setPage] = useState(location.hash)
  const [allowed, setAllowed] = useState(false)
  useEffect(() => {
    let revision = 0
    const refresh = () => {
      const current = ++revision
      setPage(location.hash)
      void apiFetch('/api/v1/admin/knowledge/access').then(response => {
        if (current === revision) setAllowed(response.ok)
      }).catch(() => { if (current === revision) setAllowed(false) })
    }
    refresh(); window.addEventListener('hashchange', refresh); window.addEventListener('focus', refresh); window.addEventListener(AUTH_SESSION_CHANGED_EVENT, refresh)
    return () => { revision++; window.removeEventListener('hashchange', refresh); window.removeEventListener('focus', refresh); window.removeEventListener(AUTH_SESSION_CHANGED_EVENT, refresh) }
  }, [])
  if (page === '#knowledge') return allowed ? <KnowledgeAdmin onClose={() => { location.hash = '' }} /> : <main className="knowledge-admin"><h1>音乐资料维护</h1><p>请使用已获知识维护权限的账号登录。</p><a href="#">返回音乐探索</a></main>
  return <><SoundSeekApp />{allowed && <a style={{ position: 'fixed', bottom: 12, left: 16, zIndex: 20 }} href="#knowledge">维护音乐资料</a>}</>
}

createRoot(document.getElementById('root')!).render(
  <StrictMode>
    <Application />
  </StrictMode>,
)
