import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { submitRunFollowUp } from './runFollowUp'
const queued = { id: 'q', content: '继续分析', status: 'WAITING' }
describe('follow-up conflict recovery', () => {
  beforeEach(() => vi.stubGlobal('document', { cookie: 'XSRF-TOKEN=test' }))
  afterEach(() => vi.unstubAllGlobals())
  it('recovers a previously accepted message without a second POST', async () => {
    const fetch = vi.fn().mockResolvedValueOnce(new Response(null, {status:409})).mockResolvedValueOnce(new Response(JSON.stringify(queued)))
    vi.stubGlobal('fetch', fetch)
    const value = await submitRunFollowUp('run', '继续分析', 'client')
    expect(value.accepted).toBe(true); expect(value.queued?.status).toBe('WAITING')
    expect(fetch.mock.calls.map(call => call[1].method || 'GET')).toEqual(['POST', 'GET'])
  })
  it('keeps a different draft when another message owns the waiting slot', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValueOnce(new Response(null, {status:409})).mockResolvedValueOnce(new Response(JSON.stringify(queued))))
    expect((await submitRunFollowUp('run', '换一批', 'client')).accepted).toBe(false)
  })
  it('handles completion racing with submission without claiming acceptance', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValueOnce(new Response(null, {status:409})).mockResolvedValueOnce(new Response(null,{status:204})))
    const value = await submitRunFollowUp('run','继续分析','client')
    expect(value.accepted).toBe(false); expect(value.queued).toBeNull(); expect(value.notice).toContain('本轮已结束')
  })
  it('reports transport failure without losing the draft', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(new Response(null,{status:503})))
    await expect(submitRunFollowUp('run','继续分析','client')).rejects.toThrow('输入已保留')
  })
})
