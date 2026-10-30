import { apiFetch } from './apiClient'

export type ExplorationEntry = {
  content: string
  messageKey: string
  conversationId?: string
  runId?: string
}

async function request(path: string, options: RequestInit = {}) {
  const controller = new AbortController()
  const timeout = setTimeout(() => controller.abort(), 20_000)
  try {
    const response = await apiFetch(`/api/v1${path}`, { ...options, signal: controller.signal })
    if (!response.ok) {
      if (response.status === 429) throw new Error('探索请求有些频繁，请稍等片刻再试。')
      throw new Error('暂时没有连接上寻声，你的输入已保留，请稍后重试。')
    }
    return await response.json()
  } catch (error) {
    if (error instanceof Error && error.name === 'AbortError') throw new Error('连接比预期久一些，你的输入已保留，可以再次尝试。')
    throw error
  } finally { clearTimeout(timeout) }
}

// Keep the same conversation and message key after a failed/uncertain response.
// The existing message endpoint deduplicates by Idempotency-Key.
export async function beginExploration(entry: ExplorationEntry): Promise<string> {
  const content = entry.content.trim()
  if (!content || content.length > 2000) throw new Error('请写下 1–2000 字的音乐想法。')
  if (entry.conversationId && entry.runId) return entry.conversationId
  // Establish the guest and CSRF cookies before the first write from the homepage.
  await request('/auth/session')
  if (!entry.conversationId) {
    const conversation = await request('/conversations', { method: 'POST' })
    if (typeof conversation.id !== 'string' || !conversation.id) throw new Error('探索暂时没有开启成功，请再试一次。')
    entry.conversationId = conversation.id
  }
  const conversationId = entry.conversationId
  if (!conversationId) throw new Error('探索暂时没有开启成功，请再试一次。')
  const queued = await request(`/conversations/${encodeURIComponent(conversationId)}/messages`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json', 'Idempotency-Key': entry.messageKey },
    body: JSON.stringify({ content }),
  })
  if (typeof queued.runId !== 'string' || !queued.runId) throw new Error('探索暂时没有开启成功，请再试一次。')
  entry.runId = queued.runId
  return conversationId
}

export function readHomeDraft() {
  try { return sessionStorage.getItem('soundseek-home-draft')?.slice(0, 2000) || '' } catch { return '' }
}

export function saveHomeDraft(content: string) {
  try {
    if (content) sessionStorage.setItem('soundseek-home-draft', content)
    else sessionStorage.removeItem('soundseek-home-draft')
  } catch { /* Browsing without storage must remain usable. */ }
}
