import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { beginExploration, type ExplorationEntry } from './landingEntry'

const entry = (): ExplorationEntry => ({ content: '  推荐一些夜晚散步的歌  ', messageKey: 'stable-message-key' })
const json = (body: object, status = 200) => new Response(JSON.stringify(body), { status, headers: { 'Content-Type': 'application/json' } })

describe('homepage to Agent handoff', () => {
  beforeEach(() => vi.stubGlobal('document', { cookie: 'XSRF-TOKEN=guest-csrf-token' }))
  afterEach(() => vi.unstubAllGlobals())

  it('establishes cookies before writing, then sends the actual input to the new conversation', async () => {
    const fetch = vi.fn().mockResolvedValueOnce(json({ authenticated: false })).mockResolvedValueOnce(json({ id: 'new-conversation' }, 201)).mockResolvedValueOnce(json({ runId: 'new-run' }, 202))
    vi.stubGlobal('fetch', fetch)
    expect(await beginExploration(entry())).toBe('new-conversation')
    expect(fetch.mock.calls.map(call => call[0])).toEqual(['/api/v1/auth/session', '/api/v1/conversations', '/api/v1/conversations/new-conversation/messages'])
    const request = fetch.mock.calls[2][1]
    expect(JSON.parse(request.body)).toEqual({ content: '推荐一些夜晚散步的歌' })
    expect(request.headers.get('Idempotency-Key')).toBe('stable-message-key')
    expect(request.headers.get('X-XSRF-TOKEN')).toBe('guest-csrf-token')
  })

  it('reuses the conversation and message key when an uncertain send is retried', async () => {
    const pending = entry()
    const fetch = vi.fn().mockResolvedValueOnce(json({})).mockResolvedValueOnce(json({ id: 'same-conversation' }, 201)).mockRejectedValueOnce(new TypeError('Failed to fetch')).mockResolvedValueOnce(json({})).mockResolvedValueOnce(json({ runId: 'same-run' }, 202))
    vi.stubGlobal('fetch', fetch)
    await expect(beginExploration(pending)).rejects.toThrow('Failed to fetch')
    expect(pending.conversationId).toBe('same-conversation')
    expect(await beginExploration(pending)).toBe('same-conversation')
    expect(fetch.mock.calls.filter(call => call[0] === '/api/v1/conversations')).toHaveLength(1)
    const sends = fetch.mock.calls.filter(call => String(call[0]).endsWith('/messages'))
    expect(sends.map(call => call[1].headers.get('Idempotency-Key'))).toEqual(['stable-message-key', 'stable-message-key'])
    await beginExploration(pending)
    expect(fetch).toHaveBeenCalledTimes(5)
  })

  it('never creates a conversation when cookie/session setup fails', async () => {
    const fetch = vi.fn().mockResolvedValue(json({}, 503))
    vi.stubGlobal('fetch', fetch)
    await expect(beginExploration(entry())).rejects.toThrow('输入已保留')
    expect(fetch).toHaveBeenCalledTimes(1)
  })

  it('rejects empty and oversized messages before making requests', async () => {
    const fetch = vi.fn(); vi.stubGlobal('fetch', fetch)
    await expect(beginExploration({ content: '   ', messageKey: 'x' })).rejects.toThrow('1–2000')
    await expect(beginExploration({ content: '歌'.repeat(2001), messageKey: 'x' })).rejects.toThrow('1–2000')
    expect(fetch).not.toHaveBeenCalled()
  })
})
