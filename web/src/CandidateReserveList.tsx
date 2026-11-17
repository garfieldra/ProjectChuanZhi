import { useState } from 'react'
import type { CandidateItem } from './candidatePool'

const PAGE_SIZE = 20

export function CandidateReserveList({ items }: {
  items: Pick<CandidateItem, 'recordingId' | 'title' | 'artistName'>[]
}) {
  const [page, setPage] = useState(0)
  const pageCount = Math.ceil(items.length / PAGE_SIZE)
  if (pageCount === 0) return null
  const currentPage = Math.min(page, pageCount - 1)
  const first = currentPage * PAGE_SIZE
  return <details>
    <summary>查看 {items.length} 首候补</summary>
    <ol start={first + 1}>
      {items.slice(first, first + PAGE_SIZE).map(item => <li key={item.recordingId}>{item.title} · {item.artistName}</li>)}
    </ol>
    {pageCount > 1 && <div>
      <button type="button" onClick={() => setPage(currentPage - 1)} disabled={currentPage === 0}>上一页</button>
      <span aria-live="polite">第 {currentPage + 1} / {pageCount} 页</span>
      <button type="button" onClick={() => setPage(currentPage + 1)} disabled={currentPage + 1 === pageCount}>下一页</button>
    </div>}
  </details>
}
