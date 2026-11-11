import { apiFetch } from './apiClient'

export type AuthSession = {
  authenticated: boolean
  displayName?: string | null
  email?: string | null
  avatarUrl?: string | null
  githubEnabled: boolean
}

export const AUTH_SESSION_CHANGED_EVENT = 'soundseek:auth-session-changed'

type AuthAction = 'session' | 'login' | 'register' | 'logout'

export async function requestAuth(action: AuthAction, body?: object): Promise<AuthSession> {
  const controller = new AbortController()
  const timeout = setTimeout(() => controller.abort(), 15_000)
  try {
    const response = await apiFetch(`/api/v1/auth/${action}`, {
      method: action === 'session' ? 'GET' : 'POST',
      ...(body ? { headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(body) } : {}),
      signal: controller.signal,
    })
    const data = await response.json().catch(() => null) as (AuthSession & { message?: string }) | null
    if (!response.ok) {
      if (response.status === 401) throw new Error(action === 'login' ? '邮箱或密码不正确，请再试一次。' : '登录状态已失效，请重新登录。')
      if (response.status === 429) throw new Error('操作有些频繁，请稍后再试。')
      throw new Error(data?.message || '账户服务暂时不可用，请稍后重试。')
    }
    if (!data || typeof data.authenticated !== 'boolean' || typeof data.githubEnabled !== 'boolean') throw new Error('未能确认账户状态，请稍后重试。')
    if (action !== 'session' && typeof window !== 'undefined') window.dispatchEvent(new Event(AUTH_SESSION_CHANGED_EVENT))
    return data
  } catch (error) {
    if (error instanceof Error && error.name === 'AbortError') throw new Error('账户连接超时，请稍后重试。')
    if (error instanceof TypeError) throw new Error('暂时无法连接账户服务，请检查网络后重试。')
    throw error
  } finally { clearTimeout(timeout) }
}
