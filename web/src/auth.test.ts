import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { requestAuth } from './auth'

const json = (body: object, status = 200) => new Response(JSON.stringify(body), { status, headers: { 'Content-Type': 'application/json' } })

describe('shared account API contract', () => {
  beforeEach(() => vi.stubGlobal('document', { cookie: 'XSRF-TOKEN=account-csrf-token' }))
  afterEach(() => { vi.unstubAllGlobals(); vi.useRealTimers() })

  it('uses session cookies and CSRF for login, registration and logout', async () => {
    const account = { authenticated: true, githubEnabled: false, displayName: '听友' }
    const fetch = vi.fn().mockImplementation(async () => json(account))
    vi.stubGlobal('fetch', fetch)
    await requestAuth('session')
    await requestAuth('login', { email: 'listener@example.test', password: 'test-only-password' })
    await requestAuth('register', { email: 'new@example.test', password: 'test-only-password', displayName: '听友' })
    await requestAuth('logout')
    expect(fetch.mock.calls.map(call => call[0])).toEqual(['/api/v1/auth/session', '/api/v1/auth/login', '/api/v1/auth/register', '/api/v1/auth/logout'])
    expect(fetch.mock.calls[0][1]).toMatchObject({ method: 'GET', credentials: 'include' })
    for (const [, options] of fetch.mock.calls.slice(1)) {
      expect(options.credentials).toBe('include')
      expect(options.headers.get('X-XSRF-TOKEN')).toBe('account-csrf-token')
    }
    expect(JSON.parse(fetch.mock.calls[2][1].body)).toMatchObject({ displayName: '听友' })
    expect(fetch.mock.calls[3][1].body).toBeUndefined()
  })

  it('rejects unconfirmed identity instead of treating a malformed success as a guest', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(json({})))
    await expect(requestAuth('session')).rejects.toThrow('未能确认账户状态')
  })

  it('shows readable credential and non-JSON service errors', async () => {
    const fetch = vi.fn().mockResolvedValueOnce(json({}, 401)).mockResolvedValueOnce(new Response('<html>unavailable</html>', { status: 503 }))
    vi.stubGlobal('fetch', fetch)
    await expect(requestAuth('login', {})).rejects.toThrow('邮箱或密码不正确')
    await expect(requestAuth('session')).rejects.toThrow('账户服务暂时不可用')
  })

  it('aborts stalled requests and allows a later retry', async () => {
    vi.useFakeTimers()
    const fetch = vi.fn().mockImplementationOnce((_url, options) => new Promise((_resolve, reject) => {
      options.signal.addEventListener('abort', () => reject(new DOMException('aborted', 'AbortError')))
    })).mockResolvedValueOnce(json({ authenticated: false, githubEnabled: false }))
    vi.stubGlobal('fetch', fetch)
    const pending = expect(requestAuth('session')).rejects.toThrow('账户连接超时')
    await vi.advanceTimersByTimeAsync(15_000)
    await pending
    await expect(requestAuth('session')).resolves.toEqual({ authenticated: false, githubEnabled: false })
    expect(vi.getTimerCount()).toBe(0)
  })
})
