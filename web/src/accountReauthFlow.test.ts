import { beforeEach, describe, expect, it, vi } from 'vitest'
import { clearReauth, executeReauth, loadReauth, saveReauth, type ReauthAction } from './accountReauthFlow'
const values = new Map<string, string>()
const currentSession = async () => ({ authenticated: true, userId: 'alice' })
const storage = { getItem: (key: string) => values.get(key) ?? null, setItem: (key: string, value: string) => { values.set(key, value) }, removeItem: (key: string) => { values.delete(key) } } as Storage
beforeEach(() => values.clear())
describe('GitHub account action return', () => {
  it('restores the email and action for the same account without executing it', () => {
    saveReauth('alice', { action: 'CHANGE_EMAIL', email: 'new@example.com' }, storage)
    expect(loadReauth('alice', storage)).toMatchObject({ action: 'CHANGE_EMAIL', email: 'new@example.com' })
    expect(values.size).toBe(1)
  })
  it('rejects another account, expired and future intents', () => {
    saveReauth('alice', { action: 'LOGOUT_ALL' }, storage, 1000)
    expect(loadReauth('bob', storage, 1000)).toBeNull(); expect(values.size).toBe(0)
    saveReauth('alice', { action: 'LOGOUT_ALL' }, storage, 1000)
    expect(loadReauth('alice', storage, 601001)).toBeNull()
    saveReauth('alice', { action: 'LOGOUT_ALL' }, storage, 1000)
    expect(loadReauth('alice', storage, 999)).toBeNull()
  })
  it('stores only non-secret fields and rejects unsupported or malformed intents', () => {
    saveReauth('alice', { action: 'LOGOUT_ALL', password: 'secret', token: 'secret' } as ReauthAction, storage)
    expect([...values.values()][0]).not.toContain('secret')
    saveReauth('alice', { action: 'DELETE' } as unknown as ReauthAction, storage)
    expect(loadReauth('alice', storage)).toBeNull()
    saveReauth('alice', { action: 'CHANGE_EMAIL', email: 'bad' }, storage)
    expect(loadReauth('alice', storage)).toBeNull()
  })
  it.each([
    [{ action: 'VERIFY_EMAIL' }, '/auth/email-verification/request', undefined],
    [{ action: 'LOGOUT_ALL' }, '/auth/logout-all', undefined],
    [{ action: 'CHANGE_EMAIL', email: 'new@example.com' }, '/me/email-change/request', { email: 'new@example.com' }],
  ] as const)('executes only the explicitly continued action %j', async (intent, path, body) => {
    const send = vi.fn().mockResolvedValue({})
    saveReauth('alice', intent, storage)
    expect(send).not.toHaveBeenCalled()
    await executeReauth('alice', storage, send, currentSession)
    expect(send).toHaveBeenCalledWith(path, body); expect(values.size).toBe(0)
    await expect(executeReauth('alice', storage, send, currentSession)).rejects.toThrow('失效')
    expect(send).toHaveBeenCalledTimes(1)
  })
  it('rechecks the current server account before sending a pending action', async () => {
    const send = vi.fn()
    saveReauth('alice', { action: 'LOGOUT_ALL' }, storage)
    await expect(executeReauth('alice', storage, send, async () => ({ authenticated: true, userId: 'bob' }))).rejects.toThrow('账号已变化')
    expect(send).not.toHaveBeenCalled(); expect(values.size).toBe(0)
  })
  it('cancellation prevents any request, while failed requests can be retried', async () => {
    const send = vi.fn().mockRejectedValue(new Error('offline'))
    saveReauth('alice', { action: 'LOGOUT_ALL' }, storage)
    await expect(executeReauth('alice', storage, send, currentSession)).rejects.toThrow('offline')
    expect(loadReauth('alice', storage)).not.toBeNull()
    clearReauth(storage); send.mockClear()
    await expect(executeReauth('alice', storage, send, currentSession)).rejects.toThrow('失效')
    expect(send).not.toHaveBeenCalled()
  })
})
