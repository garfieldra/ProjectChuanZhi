import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { memoryContent, memorySourceHref, savePreferenceMemory } from './musicPreferenceMemory'
describe('confirmed music preferences', () => {
  beforeEach(() => vi.stubGlobal('document', { cookie: 'XSRF-TOKEN=memory-csrf' }))
  afterEach(() => vi.unstubAllGlobals())
  it('saves confirmed text with provenance and CSRF, while edits keep the original provenance', async () => {
    const fetch=vi.fn().mockImplementation(async () => new Response(JSON.stringify({ id:'one', content:'喜欢梦幻流行' })))
    vi.stubGlobal('fetch',fetch)
    const source={sourceConversationId:'conversation',sourceMessageId:'message'}
    await savePreferenceMemory(' 喜欢梦幻流行 ',source)
    await savePreferenceMemory('改为偏爱爵士',source,'one')
    expect(fetch.mock.calls[0][0]).toBe('/api/v1/music-preference-memories')
    expect(fetch.mock.calls[0][1].headers.get('X-XSRF-TOKEN')).toBe('memory-csrf')
    expect(JSON.parse(fetch.mock.calls[0][1].body)).toEqual({content:'喜欢梦幻流行',...source})
    expect(fetch.mock.calls[1][1].method).toBe('PATCH')
    expect(JSON.parse(fetch.mock.calls[1][1].body)).toEqual({content:'改为偏爱爵士'})
  })
  it('does not submit blank or oversized preferences, and never silently truncates', () => {
    const fetch=vi.fn();vi.stubGlobal('fetch',fetch)
    expect(() => savePreferenceMemory('  ')).toThrow('1–800')
    expect(() => savePreferenceMemory('中'.repeat(801))).toThrow('1–800')
    expect(memoryContent('中'.repeat(800))).toHaveLength(800)
    expect(fetch).not.toHaveBeenCalled()
  })
  it('keeps source navigation within the app and supports memories entered without a conversation', () => {
    expect(memorySourceHref({id:'a',content:'偏好'})).toBeNull()
    expect(memorySourceHref({id:'a',content:'偏好',sourceConversationId:'deleted',sourceAvailable:false})).toBeNull()
    expect(memorySourceHref({id:'a',content:'偏好',sourceConversationId:'c',sourceMessageId:'m'})).toBe('/explore?conversation=c#message-m')
    expect(memorySourceHref({id:'a',content:'偏好',sourceConversationId:'c&evil=1'})).toBe('/explore?conversation=c%26evil%3D1')
  })
  it('shows actionable limit and duplicate errors from the server', async () => {
    vi.stubGlobal('fetch',vi.fn().mockResolvedValue(new Response(JSON.stringify({code:'MEMORY_LIMIT_REACHED',message:'最多保留 20 条音乐记忆，请先整理或移除旧条目。'}),{status:409})))
    await expect(savePreferenceMemory('新的偏好')).rejects.toThrow('最多保留 20 条')
  })
})
