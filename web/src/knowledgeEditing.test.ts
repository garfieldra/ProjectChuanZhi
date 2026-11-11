import { describe, expect, it } from 'vitest'
import { applyKnowledgeJson, knowledgeRevisionStatus } from './knowledgeEditing'

describe('knowledge JSON editing', () => {
  const item = { summary: '表单填写的新内容', entities: [{ type: 'WORK', id: 'work' }], support: [{ excerpt: '依据' }], aliases: [] as string[] }
  it('preserves current fields when adding aliases', () => {
    const result = applyKnowledgeJson(item, JSON.stringify(item), JSON.stringify({ ...item, aliases: ['别名'] }))
    expect(result.summary).toBe(item.summary)
    expect(result.aliases).toEqual(['别名'])
  })
  it('rejects a stale JSON draft after another form edit', () => {
    const current = { ...item, summary: '后续在表单中的修改' }
    expect(() => applyKnowledgeJson(current, JSON.stringify(item), JSON.stringify({ ...item, aliases: ['别名'] }))).toThrow('表单已更新')
    expect(current.summary).toBe('后续在表单中的修改')
  })
  it('rejects malformed content without changing the form', () => {
    expect(() => applyKnowledgeJson(item, JSON.stringify(item), '{')).toThrow()
    expect(() => applyKnowledgeJson(item, JSON.stringify(item), '{}')).toThrow('完整的实体')
  })
})

describe('published knowledge revisions', () => {
  it('distinguishes the published version from a newer draft', () => {
    expect(knowledgeRevisionStatus({ status: 'PUBLISHED', published_revision: 1, current_revision: 2, review_status: 'DRAFT' })).toBe('第 1 版已发布 · 第 2 版待审核')
  })
  it('keeps approval separate from publication', () => {
    expect(knowledgeRevisionStatus({ status: 'PUBLISHED', published_revision: 1, current_revision: 2, review_status: 'APPROVED' })).toBe('第 1 版已发布 · 第 2 版审核通过')
  })
  it('leaves fully published and retracted rows to their normal status labels', () => {
    expect(knowledgeRevisionStatus({ status: 'PUBLISHED', published_revision: 2, current_revision: 2 })).toBeUndefined()
    expect(knowledgeRevisionStatus({ status: 'RETRACTED', published_revision: 1, current_revision: 2 })).toBeUndefined()
  })
})
