import { apiFetch } from './apiClient'
export type PreferenceMemory = { id: string; content: string; sourceConversationId?: string }
export async function settingsRequest<T>(path: string, options: RequestInit = {}): Promise<T> {
  const controller = new AbortController()
  const cancel = () => controller.abort()
  if (options.signal?.aborted) controller.abort()
  options.signal?.addEventListener('abort', cancel, { once: true })
  const timer = setTimeout(cancel, 15_000)
  try {
    const response = await apiFetch(`/api/v1${path}`, { ...options, signal: controller.signal })
    if (!response.ok) throw new Error(response.status === 401 || response.status === 403 ? '登录状态已失效，请关闭菜单后重新登录。' : '暂时无法保存或读取设置，请稍后重试。')
    if (response.status === 204) return undefined as T
    return await response.json() as T
  } catch (error) {
    if (error instanceof Error && error.name === 'AbortError' && !options.signal?.aborted) throw new Error('连接超时，请重试；重新读取可确认最新保存状态。')
    if (error instanceof TypeError) throw new Error('连接意外中断，请检查网络后重试。')
    throw error
  } finally { clearTimeout(timer); options.signal?.removeEventListener('abort', cancel) }
}
