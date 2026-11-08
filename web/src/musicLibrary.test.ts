import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { libraryRequest } from './musicLibraryStore'

describe('saved-song API boundaries', () => {
  beforeEach(() => vi.stubGlobal('document', {cookie:'XSRF-TOKEN=library-csrf'}))
  afterEach(() => {vi.unstubAllGlobals();vi.useRealTimers()})
  it('sends cookie identity and CSRF for saves and accepts an empty delete response', async () => {
    const fetch=vi.fn().mockResolvedValueOnce(new Response(JSON.stringify({recordingId:'song'}))).mockResolvedValueOnce(new Response(null,{status:204}))
    vi.stubGlobal('fetch',fetch)
    await expect(libraryRequest('/song',{method:'PUT',body:JSON.stringify({sourceMessageId:'card'})})).resolves.toEqual({recordingId:'song'})
    await expect(libraryRequest('/song',{method:'DELETE'})).resolves.toBeUndefined()
    for(const [path,options] of fetch.mock.calls){expect(path).toBe('/api/v1/me/saved-songs/song');expect(options.credentials).toBe('include');expect(options.headers.get('X-XSRF-TOKEN')).toBe('library-csrf')}
  })
  it('exposes inaccessible songs and service failures without accepting HTML as saved data', async () => {
    vi.stubGlobal('fetch',vi.fn().mockResolvedValueOnce(new Response(null,{status:404})).mockResolvedValueOnce(new Response('<html>error</html>',{status:503})))
    await expect(libraryRequest('/song/context')).rejects.toThrow('歌曲或来源已不可访问')
    await expect(libraryRequest()).rejects.toThrow('音乐库暂时未能更新')
  })
  it('bounds stalled requests and distinguishes caller cancellation from a timeout', async () => {
    vi.useFakeTimers()
    vi.stubGlobal('fetch',vi.fn().mockImplementation((_path,options)=>new Promise((_resolve,reject)=>options.signal.addEventListener('abort',()=>reject(new DOMException('aborted','AbortError'))))))
    const timeout=expect(libraryRequest()).rejects.toThrow('音乐库连接超时')
    await vi.advanceTimersByTimeAsync(15000);await timeout
    const controller=new AbortController(),cancelled=expect(libraryRequest('',{signal:controller.signal})).rejects.toMatchObject({name:'AbortError'})
    controller.abort();await cancelled
    expect(vi.getTimerCount()).toBe(0)
  })
})
