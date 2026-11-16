import { apiFetch } from './apiClient'

export type RunFollowUp = { id: string; clientMessageId: string; content: string; attachmentIds?:string[]; status: 'WAITING' | 'DISPATCHED' | 'INTERVENED' | 'CANCELLED'; nextRunId?: string | null; createdAt: string }
export async function submitRunFollowUp(runId: string, content: string, clientId: string, attachmentIds:string[]=[]) {
  const path = `/api/v1/agent-runs/${encodeURIComponent(runId)}/next-message`
  const response = await apiFetch(path, { method: 'POST', headers: { 'Content-Type': 'application/json', 'Idempotency-Key': clientId }, body: JSON.stringify({ content, ...(attachmentIds.length ? {attachmentIds} : {}) }) })
  if (response.ok) return { queued: await response.json() as RunFollowUp, accepted: true, notice: '' }
  if (response.status !== 409) throw new Error('下一条消息暂时未能排队，你的输入已保留，请重试。')
  const existing = await apiFetch(path)
  if (!existing.ok) throw new Error('暂时无法确认排队状态，你的输入已保留，请稍后再试。')
  const queued = existing.status === 204 ? null : await existing.json() as RunFollowUp
  const accepted = Boolean(queued && queued.content === content && JSON.stringify(queued.attachmentIds||[])===JSON.stringify(attachmentIds) && queued.status !== 'CANCELLED')
  const notice = queued?.status === 'WAITING'
    ? accepted ? '已恢复排队消息，将在本轮完成后自动发送。' : '已有一条消息排队，当前输入已保留。'
    : accepted ? '这条消息已开始处理，可在对话中查看结果。' : '本轮已结束或排队状态已变化，当前输入已保留，请重新发送。'
  return { queued, accepted, notice }
}
