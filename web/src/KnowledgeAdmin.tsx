import { useEffect, useState } from 'react'
import { apiFetch } from './apiClient'
import './knowledge.css'
import { applyKnowledgeJson, knowledgeRevisionStatus } from './knowledgeEditing'

const kindLabels: Record<string, string> = { FACT: '可核验事实', ATTRIBUTED_STATEMENT: '采访与归因评价', EDITORIAL_CONTEXT: '历史与文化语境', THEME_CARD: '主题与听感解释', ARTIST: '艺人', WORK: '作品', RECORDING: '录音版本', RELEASE: '具体发行', RELEASE_GROUP: '专辑作品' }
const statusLabels: Record<string, string> = { DRAFT: '待审核', APPROVED: '审核通过', REJECTED: '已退回', UNPUBLISHED: '尚未发布', PUBLISHED: '已发布', RETRACTED: '已撤回', PENDING: '等待索引', RUNNING: '处理中', SUCCEEDED: '已完成', RETRY_WAIT: '等待重试', DEAD: '需要处理', BUILDING: '重建中', ACTIVE: '使用中', STANDBY: '可回退' }
type Row = Record<string, unknown>
type Content = {
  knowledgeType: string; summary: string; boundary: string; attribution: string; applicability: string; aliases: string[]
  entities: { type: string; id: string; role: string }[]
  support: { source: { identifier: string; title: string; url: string; publisher: string; language: string; rightsNote: string; materialType: string }; excerpt: string; locator: string; relation: string }[]
}
const blank = (): Content => ({ knowledgeType: 'FACT', summary: '', boundary: '', attribution: '', applicability: '', aliases: [], entities: [{ type: 'ARTIST', id: '', role: 'SUBJECT' }], support: [{ source: { identifier: '', title: '', url: '', publisher: '', language: 'zh', rightsNote: '', materialType: 'INTERVIEW' }, excerpt: '', locator: '', relation: 'SUPPORTS' }] })
const pendingMutationKeys = new Map<string, string>()
async function request(path: string, body?: unknown, method = 'POST') {
  const signature = method + path + JSON.stringify(body)
  if (body !== undefined && !pendingMutationKeys.has(signature)) pendingMutationKeys.set(signature, crypto.randomUUID())
  const response = await apiFetch('/api/v1/admin/knowledge' + path, body === undefined ? {} : { method, headers: { 'Content-Type': 'application/json', 'Idempotency-Key': pendingMutationKeys.get(signature) || crypto.randomUUID() }, body: JSON.stringify(body) })
  if (!response.ok) { const error = await response.json().catch(() => ({})); const message = error.message || error.detail || `操作失败（${response.status}）`; throw new Error(message === 'APPROVAL_REQUIRED' ? '请先审核通过当前版本，再发布。' : message) }
  pendingMutationKeys.delete(signature)
  const text = await response.text(); return text ? JSON.parse(text) : null
}

export function KnowledgeAdmin({ onClose }: { onClose: () => void }) {
  const [tab, setTab] = useState('entries'), [rows, setRows] = useState<Row[]>([]), [selected, setSelected] = useState<Row | null>(null)
  const [content, setContent] = useState<Content>(blank), [message, setMessage] = useState(''), [busy, setBusy] = useState(false)
  const [advancedText, setAdvancedText] = useState<string | null>(null)
  const [advancedBase, setAdvancedBase] = useState<string | null>(null)
  const [candidates, setCandidates] = useState<Row[]>([])
  const [materialText, setMaterialText] = useState('')
  const [searchGeneration, setSearchGeneration] = useState('')
  const [query, setQuery] = useState(''), [searchResult, setSearchResult] = useState<unknown>(null), [note, setNote] = useState(''), [url, setUrl] = useState('')
  const refresh = async () => setRows(await request('/' + tab) as Row[])
  useEffect(() => { void refresh().catch(error => setMessage(error.message)) }, [tab])
  async function act(action: () => Promise<unknown>) { setBusy(true); setMessage(''); try { const result = await action(); setMessage(typeof result === 'string' ? result : '操作完成'); await refresh() } catch (error) { setMessage(error instanceof Error ? error.message : '操作失败') } finally { setBusy(false) } }
  function resetAdvanced() { setAdvancedText(null); setAdvancedBase(null) }
  async function open(id: string) { const item = await request('/entries/' + id); setSelected(item); resetAdvanced(); setContent((item.versions as Row[])[0].content as Content); setNote('') }
  const revision = Number(selected?.current_revision || 1)
  const updateSource = (field: string, value: string) => setContent(c => ({ ...c, support: c.support.map((s, i) => i ? s : { ...s, source: { ...s.source, [field]: value } }) }))
  return <main className="knowledge-admin">
    <header><div><p>音乐资料维护</p><h1>知识与证据</h1><small>资料经人工核对并发布后，才能用于音乐回答。</small></div><button onClick={onClose}>返回音乐探索</button></header>
    <nav>{[['entries', '知识条目'], ['sources', '来源'], ['jobs', '索引任务'], ['generations', '索引版本'], ['audit', '操作记录']].map(([key, label]) => <button key={key} aria-pressed={tab === key} onClick={() => setTab(key)}>{label}</button>)}</nav>
    <p role="status">{message}</p>
    <div className="knowledge-layout"><section>
      <h2>{tab === 'entries' ? '知识条目' : '维护记录'}</h2><button disabled={busy} onClick={() => void act(refresh)}>刷新</button>
      {tab === 'entries' && <button onClick={() => { setSelected(null); resetAdvanced(); setContent(blank()) }}>新建资料</button>}
      {rows.length === 0 && <p>暂无记录。</p>}
      <ul className="knowledge-records">{rows.map((row, i) => <li key={String(row.id || i)}>
        <strong>{String(row.summary || (row.content as Row | undefined)?.title || row.action || row.operation || row.id)}</strong>
        <small>{knowledgeRevisionStatus(row) || <>{statusLabels[String(row.status || row.review_status || '')] || String(row.status || row.review_status || '')} {row.current_revision ? ` · 第 ${row.current_revision} 版` : ''}</>}</small>
        {tab === 'entries' && <button onClick={() => void act(() => open(String(row.id)))}>查看与编辑</button>}
        {tab === 'sources' && <button onClick={() => { if (confirm('撤回来源后，相关资料将不能继续用于新回答。确认撤回？')) void act(() => request(`/sources/${row.id}/retract`, { reason: '维护者撤回来源' })) }}>撤回来源</button>}
        {tab === 'jobs' && <><small>{String(row.error_code || '')}</small><button disabled={busy || row.status === 'RUNNING'} onClick={() => void act(() => request(`/jobs/${row.id}/retry`, {}))}>重试</button></>}
        {tab === 'generations' && row.status !== 'ACTIVE' && <button onClick={() => { const reason = prompt('记录已完成的检索/答案评测及结果依据：'); if (reason?.trim()) void act(() => request(`/generations/${row.id}/validate`, { reason })) }}>记录质量验收</button>}
        {tab === 'generations' && row.status !== 'ACTIVE' && <button disabled={busy} onClick={() => { if (confirm('确认切换索引版本？系统会检查是否已追平发布资料。')) void act(() => request(`/generations/${row.id}/activate`, {})) }}>切换到此版本</button>}
        <details><summary>详细记录</summary><pre>{JSON.stringify(row, null, 2)}</pre></details>
      </li>)}</ul>
      {tab === 'jobs' && <button disabled={busy} onClick={() => void act(() => request('/reconcile', {}))}>检查并补齐任务</button>}
      {tab === 'generations' && <button disabled={busy} onClick={() => void act(() => request('/generations', { model: 'BAAI/bge-small-zh-v1.5', dimension: 512, modelRevision: '46fbe35fd4374a00fee7de77dfddaeb6dd6a2c59', templateVersion: '1' }))}>重建独立索引</button>}
    </section><section>
      <h2>{selected ? '修订与审核' : '录入候选资料'}</h2>
      <label>知识类型<select value={content.knowledgeType} onChange={e => setContent({ ...content, knowledgeType: e.target.value })}>{['FACT', 'ATTRIBUTED_STATEMENT', 'EDITORIAL_CONTEXT', 'THEME_CARD'].map(type => <option key={type} value={type}>{kindLabels[type] || type}</option>)}</select></label>
      <label>知识表述<textarea value={content.summary} onChange={e => setContent({ ...content, summary: e.target.value })} /></label>
      <label>适用范围<input value={content.applicability || ''} onChange={e => setContent({ ...content, applicability: e.target.value })} /></label>
      <label>资料边界<input value={content.boundary || ''} onChange={e => setContent({ ...content, boundary: e.target.value })} /></label>
      <label>发言或评价主体<input value={content.attribution || ''} onChange={e => setContent({ ...content, attribution: e.target.value })} /></label>
      <fieldset><legend>主要音乐实体</legend><select value={content.entities[0]?.type || 'ARTIST'} onChange={e => setContent(c => ({ ...c, entities: [{ ...c.entities[0], type: e.target.value }] }))}>{['ARTIST', 'RELEASE_GROUP', 'RELEASE', 'WORK', 'RECORDING'].map(type => <option key={type} value={type}>{kindLabels[type] || type}</option>)}</select><input aria-label="规范实体 ID 或 MBID" placeholder="已核验的实体 ID 或 MBID" value={content.entities[0]?.id || ''} onChange={e => setContent(c => ({ ...c, entities: [{ ...c.entities[0], id: e.target.value }] }))} /></fieldset>
      <fieldset><legend>支持材料</legend>
        <label>来源标题<input value={content.support[0]?.source.title || ''} onChange={e => updateSource('title', e.target.value)} /></label>
        <label>资料标识<input placeholder="稳定 URL 或维护者资料标识" value={content.support[0]?.source.identifier || ''} onChange={e => updateSource('identifier', e.target.value)} /></label>
        <label>公开链接<input value={content.support[0]?.source.url || ''} onChange={e => updateSource('url', e.target.value)} /></label>
        <label>发布者<input value={content.support[0]?.source.publisher || ''} onChange={e => updateSource('publisher', e.target.value)} /></label>
        <label>允许的使用方式<input value={content.support[0]?.source.rightsNote || ''} onChange={e => updateSource('rightsNote', e.target.value)} /></label>
        <label>必要短摘录<textarea maxLength={2000} value={content.support[0]?.excerpt || ''} onChange={e => setContent(c => ({ ...c, support: c.support.map((s, i) => i ? s : { ...s, excerpt: e.target.value }) }))} /></label>
        <label>段落、章节或时间戳<input value={content.support[0]?.locator || ''} onChange={e => setContent(c => ({ ...c, support: c.support.map((s, i) => i ? s : { ...s, locator: e.target.value }) }))} /></label>
      </fieldset>
      <details><summary>多实体、多来源及别名编辑</summary><textarea aria-label="资料 JSON" className="knowledge-json" value={advancedText ?? JSON.stringify(content, null, 2)} onChange={e => { if (advancedText === null) setAdvancedBase(JSON.stringify(content)); setAdvancedText(e.target.value) }} /><button disabled={advancedText === null} onClick={() => { try { setContent(applyKnowledgeJson(content, advancedBase, advancedText!)); resetAdvanced(); setMessage('编辑内容已应用，请保存为待审核版本。') } catch (error) { setMessage(error instanceof Error ? error.message : '请提供有效的 JSON。') } }}>应用编辑</button><button onClick={resetAdvanced}>载入最新表单内容</button></details>
      <button disabled={busy} onClick={() => void act(async () => { if (selected) { await request(`/entries/${selected.id}?expectedRevision=${revision}`, content, 'PUT'); await open(String(selected.id)) } else { const result = await request('/imports', { origin: 'MANUAL_EDIT', candidateItems: [content] }); const row = result.results[0]; if (!row.entryId) throw new Error(row.error); await open(row.entryId) } })}>保存为待审核版本</button>
      {selected && <><label>审核说明 / 撤回原因<textarea value={note} onChange={e => setNote(e.target.value)} /></label><div className="knowledge-actions">
        <button disabled={busy || !note.trim()} onClick={() => void act(async () => { await request(`/entries/${selected.id}/review`, { revision, status: 'APPROVED', note }); await open(String(selected.id)) })}>审核通过</button>
        <button disabled={busy || !note.trim()} onClick={() => void act(async () => { await request(`/entries/${selected.id}/review`, { revision, status: 'REJECTED', note }); await open(String(selected.id)) })}>退回修订</button>
        <button disabled={busy} onClick={() => void act(async () => { await request(`/entries/${selected.id}/publish`, { revision }); await open(String(selected.id)) })}>发布已审核版本</button>
        <button disabled={busy || !note.trim()} onClick={() => { if (confirm('确认撤回当前知识？')) void act(() => request(`/entries/${selected.id}/retract`, { reason: note })) }}>撤回</button>
      </div><details><summary>版本与审核历史</summary><pre>{JSON.stringify(selected.versions, null, 2)}</pre></details></>}
      <h3>导入资料文件</h3><input type="file" accept=".jsonl,.json,.md,.txt" disabled={busy} onChange={e => { const file = e.target.files?.[0]; if (!file) return; void act(async () => { if (file.size > 1024 * 1024) throw new Error('文件不能超过 1 MB'); const text = await file.text(); if (/\.(md|txt)$/i.test(file.name)) { if (text.length > 12000) throw new Error('辅助提取最多读取 12000 字，请先按章节整理。'); setMaterialText(text); if (text.length <= 3000) setContent(c => ({ ...c, summary: text })); return '资料已载入。请人工整理表述与短摘录，也可主动选择模型辅助提取。' } const items = file.name.endsWith('.jsonl') ? text.split(/\r?\n/).filter(line => line.trim()).map(line => JSON.parse(line)) : JSON.parse(text); const result = await request('/imports', { origin: 'MANUAL_FILE', candidateItems: Array.isArray(items) ? items : [items] }); setSearchResult(result); return '导入完成，逐条结果见下方。' }) }} />
      <h3>读取指定公开页面</h3><input aria-label="待读取网页" value={url} onChange={e => setUrl(e.target.value)} /><button disabled={busy || !url} onClick={() => void act(async () => { const result = await request('/read-source', { url }); updateSource('url', url); updateSource('identifier', url); setMaterialText(result.source.pageExcerpt); setSearchResult(result); return '已读取候选正文，请选择必要短摘录；模型辅助提取为可选操作。' })}>读取资料</button>
      {materialText && <section><details><summary>查看待整理的资料正文</summary><textarea readOnly value={materialText} /></details><p>可直接人工整理，也可使用服务端模型生成候选；候选仍需人工审核。</p><button disabled={busy} onClick={() => void act(async () => { const result = await request('/extract', { text: materialText }); setCandidates(result.candidates || []); return result.message })}>辅助提取候选（使用模型）</button></section>}
      {candidates.length > 0 && <section><h3>待确认的候选知识</h3><p>名称只是匹配建议，保存前请确认规范实体及来源。</p>{candidates.map((candidate, index) => <article key={index}><p>{String(candidate.summary)}</p><small>{String((candidate.entity_names as string[] || []).join('、'))}</small><button onClick={() => { setSelected(null); setContent(c => ({ ...c, knowledgeType: String(candidate.knowledge_type), summary: String(candidate.summary), boundary: String(candidate.boundary || ''), attribution: String(candidate.attribution || ''), support: c.support.map((item, i) => i ? item : { ...item, excerpt: String(candidate.excerpt), locator: String(candidate.locator) }) })); setMessage('候选已放入编辑区，请核对来源和实体后保存。') }}>选入编辑区</button></article>)}</section>}
      <h3>测试检索</h3><label>索引版本（留空使用当前版本）<input inputMode="numeric" value={searchGeneration} onChange={e => setSearchGeneration(e.target.value.replace(/[^0-9]/g, ''))} /></label><input aria-label="检索问题" value={query} onChange={e => setQuery(e.target.value)} /><button disabled={busy || !query} onClick={() => void act(async () => setSearchResult(await request('/search' + (searchGeneration ? `?generationId=${searchGeneration}` : ''), { query, entities: [], entityScope: 'RELATED_CONTEXT', knowledgeTypes: [], limit: 6 })))}>检索已发布资料</button>
      {searchResult != null && <pre>{JSON.stringify(searchResult, null, 2)}</pre>}
    </section></div>
  </main>
}
