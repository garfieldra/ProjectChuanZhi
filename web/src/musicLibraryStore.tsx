import { createContext, useCallback, useContext, useRef, useState, type ReactNode } from 'react'
import { apiFetch } from './apiClient'
import { useListeningPreview } from './listening'

export type SavedSong = { recordingId: string; title: string; artistName: string; albumTitle?: string; versionLabel?: string; coverUrl?: string; musicbrainzMbid?: string; reason?: string; savedAt: string; sourceConversationId?: string; sourceMessageId?: string; sourceAvailable: boolean }
export type PlayableSong = Pick<SavedSong, 'recordingId' | 'title' | 'artistName' | 'coverUrl'>
export type SavedPage = { items: SavedSong[]; nextCursor: string | null }
export async function libraryRequest<T>(path = '', options: RequestInit = {}): Promise<T> {
  const controller = new AbortController()
  const abort = () => controller.abort()
  if (options.signal?.aborted) controller.abort()
  options.signal?.addEventListener('abort', abort, {once:true})
  const timeout = setTimeout(abort, 15000)
  try {
    const response = await apiFetch(`/api/v1/me/saved-songs${path}`, {...options,signal:controller.signal})
    if (!response.ok) throw new Error(response.status === 404 ? '歌曲或来源已不可访问，请刷新后重试。' : '音乐库暂时未能更新，请重试。')
    return response.status === 204 ? undefined as T : await response.json() as T
  } catch (reason) {
    if (controller.signal.aborted && !options.signal?.aborted) throw new Error('音乐库连接超时，请重试。')
    throw reason
  } finally { clearTimeout(timeout);options.signal?.removeEventListener('abort',abort) }
}
function useLibraryState() {
  const [saved, setSaved] = useState<Record<string, boolean>>({})
  const known = useRef(new Set<string>()), fetching = useRef(new Set<string>()), mutating = useRef(new Set<string>())
  const generations = useRef<Record<string, number>>({})
  const [busy, setBusy] = useState<Record<string, boolean>>({})
  const [error, setError] = useState('')
  const [revision, setRevision] = useState(0)
  const clearError = useCallback(() => setError(''), [])
  const listening = useListeningPreview()
  const [playingSong, setPlayingSong] = useState<PlayableSong | null>(null)
  const prime = useCallback((ids: string[]) => {
    ids.forEach(id => { known.current.add(id); generations.current[id] = (generations.current[id] || 0) + 1 })
    setSaved(previous => ({...previous, ...Object.fromEntries(ids.map(id => [id, true]))}))
  }, [])
  const ensure = useCallback(async (ids: string[], refresh = false) => {
    const missing = [...new Set(ids)].filter(id => (refresh || !known.current.has(id)) && !fetching.current.has(id))
    setError('')
    if (!missing.length) return
    missing.forEach(id => fetching.current.add(id))
    const versions = {...generations.current}
    try {
      // Bound the batch size even when several recommendation cards arrive together.
      for (let offset = 0; offset < missing.length; offset += 100) {
        const batch = missing.slice(offset, offset + 100)
        const result = await libraryRequest<string[]>(`/status?recordingIds=${batch.map(encodeURIComponent).join(',')}`)
        const found = new Set(result)
        const updates: Record<string, boolean> = {}
        batch.forEach(id => { if ((versions[id] || 0) === (generations.current[id] || 0)) { known.current.add(id); updates[id] = found.has(id) } })
        setSaved(previous => ({...previous, ...updates}))
      }
    } catch (reason) { setError((reason as Error).message) }
    finally { missing.forEach(id => fetching.current.delete(id)) }
  }, [])
  async function toggle(id: string, sourceMessageId?: string, remove = saved[id] === true) {
    if (mutating.current.has(id)) return
    mutating.current.add(id); setBusy(previous => ({...previous,[id]:true})); setError('')
    generations.current[id] = (generations.current[id] || 0) + 1
    try {
      if (!remove && !sourceMessageId) throw new Error('这首歌缺少推荐来源，请从原推荐卡保存。')
      await libraryRequest(`/${encodeURIComponent(id)}`, {method: remove ? 'DELETE' : 'PUT', headers:{'Content-Type':'application/json'}, ...(remove ? {} : {body:JSON.stringify({sourceMessageId})})})
      known.current.add(id); setSaved(previous => ({...previous,[id]:!remove})); setRevision(value => value + 1)
    } catch(reason) { setError((reason as Error).message) }
    finally { mutating.current.delete(id); setBusy(previous => ({...previous,[id]:false})) }
  }
  function play(song: PlayableSong) { setPlayingSong(song); void listening.toggle(song.recordingId) }
  return {saved,busy,error,revision,clearError,ensure,prime,toggle,listening,playingSong,play}
}
const LibraryContext = createContext<ReturnType<typeof useLibraryState> | null>(null)
export function MusicLibraryProvider({children}:{children:ReactNode}) { const state=useLibraryState(); return <LibraryContext.Provider value={state}>{children}</LibraryContext.Provider> }
export function useMusicLibrary() { const value=useContext(LibraryContext); if(!value) throw new Error('Music library provider missing'); return value }
export function SaveSongButton({id,title,sourceMessageId}:{id:string;title:string;sourceMessageId:string}) {
  const library=useMusicLibrary(), saved=library.saved[id]
  return <button className="save-song-button" type="button" aria-label={`${saved ? '取消保存' : '保存'}《${title}》`} aria-pressed={saved === true} disabled={library.busy[id] || saved === undefined} onClick={() => void library.toggle(id,sourceMessageId)}><svg viewBox="0 0 24 24" aria-hidden="true" fill={saved ? 'currentColor':'none'}><path d="M6 4h12v17l-6-4-6 4V4Z" /></svg>{library.busy[id] ? '正在更新…' : saved === undefined ? '读取中…' : saved ? '已保存' : '保存'}</button>
}
