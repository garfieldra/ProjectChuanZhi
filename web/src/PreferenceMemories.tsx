import { useEffect, useId, useRef, useState, type FormEvent } from 'react'
import { settingsRequest, type PreferenceMemory } from './settingsApi'
import { MEMORY_LIMIT, MEMORY_MAX_LENGTH, memorySourceHref, savePreferenceMemory, type MemorySource } from './musicPreferenceMemory'
import './preferenceMemories.css'

function MemoryEditor({ initial = '', busy, onSave, onCancel }: { initial?: string; busy: boolean; onSave: (value: string) => void; onCancel: () => void }) {
  const [content, setContent] = useState(initial)
  const id = useId()
  return <form className="preference-memory-editor" onSubmit={(event: FormEvent) => { event.preventDefault(); if (!busy) onSave(content) }}>
    <label htmlFor={id}>希望寻声长期记住的音乐偏好</label>
    <textarea id={id} value={content} onChange={event => setContent(event.target.value)} maxLength={MEMORY_MAX_LENGTH} disabled={busy} required placeholder="例如：我偏爱梦幻流行，人声与伴奏融合，避免太厚重的失真吉他。" />
    <small>仅保存你确认的偏好，用于后续对话；本轮新要求优先。{content.length}/{MEMORY_MAX_LENGTH}</small>
    <div><button className="settings-primary" type="submit" disabled={busy || !content.trim() || content.length > MEMORY_MAX_LENGTH}>{busy ? '正在保存…' : '确认保存'}</button><button type="button" disabled={busy} onClick={onCancel}>取消</button></div>
  </form>
}

export function PreferenceMemories({ onBusyChange }: { onBusyChange?: (busy: boolean) => void }) {
  const [memories, setMemories] = useState<PreferenceMemory[]>([])
  const [loading, setLoading] = useState(true)
  const [loadError, setLoadError] = useState('')
  const [error, setError] = useState('')
  const [notice, setNotice] = useState('')
  const [retry, setRetry] = useState(0)
  const [editing, setEditing] = useState<string | null>(null)
  const [removing, setRemoving] = useState<string | null>(null)
  const [busy, setBusy] = useState(false)
  const inFlight = useRef(false)
  useEffect(() => {
    const controller = new AbortController()
    setLoading(true); setLoadError(''); setEditing(null); setRemoving(null)
    void settingsRequest<PreferenceMemory[]>('/music-preference-memories', { signal: controller.signal })
      .then(value => { if (!controller.signal.aborted) setMemories(value) })
      .catch(reason => { if (!controller.signal.aborted) setLoadError(reason instanceof Error ? reason.message : '暂时无法读取音乐记忆。') })
      .finally(() => { if (!controller.signal.aborted) setLoading(false) })
    return () => controller.abort()
  }, [retry])
  async function act(work: () => Promise<void>) {
    if (inFlight.current) return
    inFlight.current = true; setBusy(true); onBusyChange?.(true); setError(''); setNotice('')
    try { await work() } catch (reason) { setError(reason instanceof Error ? reason.message : '操作失败，请重试。') }
    finally { inFlight.current = false; setBusy(false); onBusyChange?.(false) }
  }
  function edit(id: string) { setEditing(id); setRemoving(null); setError(''); setNotice('') }
  function save(value: string, id?: string) { void act(async () => {
    const saved = await savePreferenceMemory(value, undefined, id)
    setMemories(items => [saved, ...items.filter(item => item.id !== saved.id)])
    setEditing(null); setNotice('已保存，后续对话将参考这条音乐偏好。')
  }) }
  return <section className="preference-memories" aria-label="已确认的音乐偏好">
    <p className="settings-description">你确认保存的长期偏好会在这里展示，并用于后续对话。当前对话的要求始终优先。</p>
    {loading ? <p role="status">正在读取音乐记忆…</p> : loadError ? <div className="settings-empty"><p role="alert">{loadError}</p><button type="button" onClick={() => setRetry(value => value + 1)}>重新读取</button></div> : <>
      <div className="preference-memory-toolbar"><small>{memories.length}/{MEMORY_LIMIT} 条长期记忆</small><button className="settings-secondary" type="button" disabled={busy || memories.length >= MEMORY_LIMIT || editing !== null} onClick={() => edit('new')}>添加音乐偏好</button></div>
      {memories.length >= MEMORY_LIMIT && <p className="settings-description">记忆已满，请先合并或移除旧条目。</p>}
      {editing === 'new' && <MemoryEditor key="new" busy={busy} onSave={value => save(value)} onCancel={() => { setEditing(null); setError('') }} />}
      {memories.length ? <ul className="settings-memories">{memories.map(memory => <li key={memory.id}>
        {editing === memory.id ? <MemoryEditor key={memory.id} initial={memory.content} busy={busy} onSave={value => save(value, memory.id)} onCancel={() => { setEditing(null); setError('') }} /> : <p>{memory.content}</p>}
        <small className="preference-memory-meta">{memory.sourceConversationId ? (memory.sourceAvailable === false ? '来源对话已删除 · 记忆仍保留' : '来自对话 · 用户确认') : '手动添加'}{memory.updatedAt && ` · ${new Date(memory.updatedAt).toLocaleDateString('zh-CN')}`}</small>
        <div>{memorySourceHref(memory) && <a href={memorySourceHref(memory)!} target="_blank" rel="noreferrer">查看来源对话</a>}
          {removing === memory.id ? <><span>移除后不再用于新的对话请求。</span><button disabled={busy} type="button" onClick={() => setRemoving(null)}>取消</button><button disabled={busy} type="button" className="settings-danger" onClick={() => void act(async () => {
            await settingsRequest(`/music-preference-memories/${encodeURIComponent(memory.id)}`, { method: 'DELETE' })
            setMemories(items => items.filter(item => item.id !== memory.id)); setRemoving(null); setNotice('已移除这条音乐记忆，原始对话仍保留。')
          })}>确认移除</button></> : <><button disabled={busy || editing !== null} type="button" onClick={() => edit(memory.id)}>编辑</button><button disabled={busy || editing !== null} type="button" onClick={() => { setRemoving(memory.id); setError(''); setNotice('') }}>移除记忆</button></>}
        </div>
      </li>)}</ul> : editing !== 'new' && <div className="settings-empty"><strong>还在了解你的音乐口味</strong><p>添加一条偏好，或在对话消息下选择“记为音乐偏好”，确认后会显示在这里。</p></div>}
    </>}
    <div className="settings-feedback" aria-live="polite">{notice && <p role="status">{notice}</p>}{error && <p className="settings-error" role="alert">{error}</p>}</div>
  </section>
}

export function RememberPreference({ content, source }: { content: string; source: MemorySource }) {
  const [open, setOpen] = useState(false)
  const [busy, setBusy] = useState(false)
  const [notice, setNotice] = useState('')
  const [error, setError] = useState('')
  const inFlight = useRef(false)
  async function save(value: string) {
    if (inFlight.current) return
    inFlight.current = true; setBusy(true); setError('')
    try { await savePreferenceMemory(value, source); setOpen(false); setNotice('已保存到“音乐偏好与记忆”，后续对话会参考。') }
    catch (reason) { setError(reason instanceof Error ? reason.message : '保存失败，请重试。') }
    finally { inFlight.current = false; setBusy(false) }
  }
  return <div className="remember-preference">
    {!open && <button type="button" onClick={() => { setOpen(true); setNotice(''); setError('') }}>记为音乐偏好</button>}
    {open && <MemoryEditor initial={content} busy={busy} onSave={value => void save(value)} onCancel={() => { setOpen(false); setError('') }} />}
    {notice && <p role="status">{notice}</p>}{error && <p role="alert">{error}</p>}
  </div>
}
