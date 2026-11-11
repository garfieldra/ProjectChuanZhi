import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { accountRequest, requireNewPassword } from './auth'
const response = (body: object, status = 200) => new Response(JSON.stringify(body), { status, headers: { 'Content-Type': 'application/json' } })
describe('account recovery transport', () => {
  beforeEach(() => vi.stubGlobal('document', { cookie: 'XSRF-TOKEN=test-csrf' }))
  afterEach(() => { vi.unstubAllGlobals(); vi.useRealTimers() })
  it('keeps the submission key on network failure and renews it after acceptance', async () => {
    const fetch = vi.fn().mockRejectedValueOnce(new TypeError('offline')).mockImplementation(async () => response({ message: 'accepted' }, 202)); vi.stubGlobal('fetch', fetch)
    const body = { email: 'retry@example.invalid' }
    await expect(accountRequest('/auth/password-reset/request', body)).rejects.toThrow('offline')
    await accountRequest('/auth/password-reset/request', body)
    await accountRequest('/auth/password-reset/request', body)
    expect(fetch.mock.calls[0][1].headers.get('Idempotency-Key')).toBe(fetch.mock.calls[1][1].headers.get('Idempotency-Key'))
    expect(fetch.mock.calls[3][1].headers.get('Idempotency-Key')).not.toBe(fetch.mock.calls[1][1].headers.get('Idempotency-Key'))
    expect(fetch.mock.calls[2][0]).toBe('/api/v1/auth/session')
    expect(fetch.mock.calls[1][1].headers.get('X-XSRF-TOKEN')).toBe('test-csrf')
  })
  it('rejects too-long multibyte passwords without sending the request', async () => {
    const fetch=vi.fn();vi.stubGlobal('fetch',fetch)
    expect(() => requireNewPassword('中'.repeat(25))).toThrow('72 字节')
    expect(() => requireNewPassword('中'.repeat(24))).not.toThrow()
    await expect(accountRequest('/auth/register', { password: 'short' })).rejects.toThrow('10 个字符')
    expect(fetch).not.toHaveBeenCalled()
  })
  it('does not refresh identity after rejected recovery and shows the server error', async () => {
    const fetch=vi.fn().mockResolvedValue(response({ message: '链接已失效' },400));vi.stubGlobal('fetch',fetch)
    await expect(accountRequest('/auth/password-reset/confirm',{token:'expired',password:'A-new-password-123'})).rejects.toThrow('链接已失效')
    expect(fetch).toHaveBeenCalledTimes(1)
  })
  it('bounds stalled recovery requests', async () => {
    vi.useFakeTimers();vi.stubGlobal('fetch',vi.fn().mockImplementation((_url,options)=>new Promise((_resolve,reject)=>options.signal.addEventListener('abort',()=>reject(new DOMException('aborted','AbortError'))))))
    const pending=expect(accountRequest('/auth/password-reset/request',{email:'timeout@example.invalid'})).rejects.toThrow('账号连接超时')
    await vi.advanceTimersByTimeAsync(15000);await pending;expect(vi.getTimerCount()).toBe(0)
  })
})
