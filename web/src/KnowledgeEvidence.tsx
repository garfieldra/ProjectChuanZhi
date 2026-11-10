import { useState } from 'react'
import { apiFetch } from './apiClient'

export type KnowledgeSource = { ref?: string; title?: string; url?: string; summary?: string; entryId?: string; revision?: number; knowledgeType?: string; boundary?: string; applicability?: string; support?: { sourceTitle?: string; sourceUrl?: string; publisher?: string; publishedAt?: string; accessedAt?: string; excerpt?: string; locator?: string; relation?: string }[] }

export function KnowledgeEvidence({ source }: { source: KnowledgeSource }) {
  const [state, setState] = useState(''), [opened, setOpened] = useState(false)
  async function toggle() {
    setOpened(value => !value)
    if (!opened && source.entryId && source.revision) {
      try { const response = await apiFetch(`/api/v1/evidence/${encodeURIComponent(source.entryId)}/${source.revision}`); if (!response.ok) throw new Error(); const value = await response.json(); setState(value.status === 'active' ? '当前资料有效' : '资料已更新或撤回，不能继续作为新结论的依据') }
      catch { setState('暂时无法核对资料状态') }
    }
  }
  return <span className="knowledge-evidence"><button onClick={() => void toggle()} aria-expanded={opened}>{source.ref || '查看依据'} · {source.title || '维护者整理'}</button>{opened && <span className="knowledge-evidence-detail"><small>{state}</small><strong>{source.knowledgeType === 'THEME_CARD' ? '主题与听感解释' : '人工维护的来源证据'}</strong>{source.applicability && <p>适用范围：{source.applicability}</p>}{source.boundary && <p>{source.boundary}</p>}{state === '当前资料有效' && source.support?.map((item, index) => <span key={index}><strong>{item.sourceTitle}</strong><small>{item.publisher} · {item.locator} · {item.relation === 'CONTRADICTS' ? '冲突材料' : item.relation === 'CONTEXT_ONLY' ? '背景材料' : '支持材料'}</small><small>{item.publishedAt ? `发布于 ${item.publishedAt}` : item.accessedAt ? `收录于 ${item.accessedAt}` : ''}</small><blockquote>{item.excerpt}</blockquote>{item.sourceUrl && /^https?:\/\//.test(item.sourceUrl) && <a href={item.sourceUrl} target="_blank" rel="noreferrer">打开原始来源</a>}</span>)}</span>}</span>
}

export function KnowledgeReferences({ sources, payload }: { sources?: KnowledgeSource[]; payload?: string | null }) {
  let items = sources || []
  if (payload) { try { const value = JSON.parse(payload); items = value.knowledgeSources || value.report?.knowledgeSources || [] } catch { /* old cards may not carry evidence */ } }
  if (!items.length) return null
  return <details className="music-analysis-details"><summary>查看本轮取得的本地审核资料</summary>{items.map((source, index) => <KnowledgeEvidence key={`${source.entryId}-${source.revision}-${index}`} source={source} />)}</details>
}
