/** Applying JSON must never overwrite form edits made since JSON editing began. */
export function applyKnowledgeJson<T>(current: T, base: string | null, draft: string): T {
  if (base !== JSON.stringify(current)) throw new Error('表单已更新，请先载入最新内容，再编辑并应用 JSON。')
  const value = JSON.parse(draft)
  if (!value || !Array.isArray(value.entities) || !value.entities.length || !Array.isArray(value.support) || !value.support.length) {
    throw new Error('请提供完整的实体与支持材料。')
  }
  return value as T
}

/** A new draft does not move the published revision pointer. */
export function knowledgeRevisionStatus(row: { status?: unknown; current_revision?: unknown; published_revision?: unknown; review_status?: unknown }): string | undefined {
  const current = Number(row.current_revision), published = Number(row.published_revision)
  if (row.status !== 'PUBLISHED' || !Number.isInteger(published) || published < 1 || current <= published) return undefined
  const labels: Record<string, string> = { DRAFT: '待审核', APPROVED: '审核通过', REJECTED: '已退回' }
  return `第 ${published} 版已发布 · 第 ${current} 版${labels[String(row.review_status)] || '待审核'}`
}
