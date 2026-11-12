import { accountRequest, requestAuth, type AuthSession } from './auth'
export type ReauthAction = { action: 'VERIFY_EMAIL' | 'LOGOUT_ALL' } | { action: 'CHANGE_EMAIL'; email: string }
export type PendingReauth = ReauthAction & { userId: string; createdAt: number }
const key = 'soundseek:account-reauth'
export function clearReauth(storage: Storage = sessionStorage) { storage.removeItem(key) }
export function saveReauth(userId: string, intent: ReauthAction, storage: Storage = sessionStorage, now = Date.now()) {
  // Copy known fields only. Passwords and confirmation tokens must never be stored here.
  storage.setItem(key, JSON.stringify({ userId, createdAt: now, action: intent.action,
    ...(intent.action === 'CHANGE_EMAIL' ? { email: intent.email } : {}) }))
}
export function loadReauth(userId: string, storage: Storage = sessionStorage, now = Date.now()): PendingReauth | null {
  try {
    const value = JSON.parse(storage.getItem(key) || 'null')
    if (!value || value.userId !== userId || !Number.isFinite(value.createdAt) || now < value.createdAt || now - value.createdAt > 600_000 ||
      !['VERIFY_EMAIL', 'CHANGE_EMAIL', 'LOGOUT_ALL'].includes(value.action) ||
      (value.action === 'CHANGE_EMAIL' && (typeof value.email !== 'string' || value.email.length > 320 || !/^[^\s@]+@[^\s@]+\.[^\s@]+$/.test(value.email)))) {
      clearReauth(storage); return null
    }
    return { userId, createdAt: value.createdAt, action: value.action, ...(value.action === 'CHANGE_EMAIL' ? { email: value.email } : {}) }
  } catch { clearReauth(storage); return null }
}
export async function executeReauth(userId: string, storage: Storage = sessionStorage, send: typeof accountRequest = accountRequest, currentSession: () => Promise<Pick<AuthSession, 'authenticated' | 'userId'>> = () => requestAuth('session')) {
  const intent = loadReauth(userId, storage)
  if (!intent) throw new Error('待办操作已失效，请返回设置重新操作。')
  const current = await currentSession()
  if (!current.authenticated || current.userId !== userId) { clearReauth(storage); throw new Error('登录账号已变化，请返回设置重新操作。') }
  const path = { VERIFY_EMAIL: '/auth/email-verification/request', CHANGE_EMAIL: '/me/email-change/request', LOGOUT_ALL: '/auth/logout-all' }[intent.action]
  await send(path, intent.action === 'CHANGE_EMAIL' ? { email: intent.email } : undefined)
  clearReauth(storage)
  return intent.action === 'LOGOUT_ALL' ? '全部设备已退出。' : '确认邮件已申请，请检查收件箱。'
}
