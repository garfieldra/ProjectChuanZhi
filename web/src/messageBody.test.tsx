import { describe, expect, it } from 'vitest'
import { renderToStaticMarkup } from 'react-dom/server'
import { MessageBody } from './ConversationApp'

describe('long conversation answer rendering', () => {
  it('keeps text immediately after a heading in a paragraph', () => {
    const html = renderToStaticMarkup(<MessageBody content={'## 创作背景\n这是正文。\n这是同段的下一行。'} />)
    expect(html).toContain('<h3><span>创作背景</span></h3>')
    expect(html).toContain('<p><span>这是正文。\n这是同段的下一行。</span></p>')
  })
  it('recognizes a heading after prose without requiring an empty line', () => {
    const html = renderToStaticMarkup(<MessageBody content={'开场。\n### 编曲\n**钢琴**与人声。'} />)
    expect(html).toContain('<p><span>开场。</span></p><h3><span>编曲</span></h3>')
    expect(html).toContain('<strong>钢琴</strong>')
  })
  it('preserves the entire answer and escapes untrusted markup', () => {
    const content = '分析内容。'.repeat(2000) + '<script>unsafe()</script>'
    const html = renderToStaticMarkup(<MessageBody content={content} />)
    expect(html).toContain('分析内容。'.repeat(2000))
    expect(html).toContain('&lt;script&gt;unsafe()&lt;/script&gt;')
    expect(html).not.toContain('<script>')
  })
})
