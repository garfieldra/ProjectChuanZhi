import { settingsRequest, type PreferenceMemory } from './settingsApi'
export const MEMORY_LIMIT = 20
export const MEMORY_MAX_LENGTH = 800
export type MemorySource = { sourceConversationId: string; sourceMessageId: string }
export function memoryContent(value: string) {
  if (!value.trim() || value.length > MEMORY_MAX_LENGTH) throw new Error('音乐记忆需要填写 1–800 个字符。')
  return value.trim()
}
export function savePreferenceMemory(content: string, source?: MemorySource, id?: string) {
  return settingsRequest<PreferenceMemory>(`/music-preference-memories${id ? `/${encodeURIComponent(id)}` : ''}`, {
    method: id ? 'PATCH' : 'POST', headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ content: memoryContent(content), ...(!id && source ? source : {}) }),
  })
}
export function memorySourceHref(memory: PreferenceMemory) {
  return memory.sourceConversationId && memory.sourceAvailable !== false ? `/explore?conversation=${encodeURIComponent(memory.sourceConversationId)}${memory.sourceMessageId ? `#message-${encodeURIComponent(memory.sourceMessageId)}` : ''}` : null
}
