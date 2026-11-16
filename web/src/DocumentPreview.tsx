import { useEffect, useRef, useState } from 'react'
import { createPortal } from 'react-dom'
import { apiFetch } from './apiClient'
import type { ImageAttachment } from './imageAttachmentApi'

export function useAttachmentMetadata(id: string) {
  const [metadata, setMetadata] = useState<ImageAttachment | null>(null)
  const [error, setError] = useState('')
  useEffect(() => {
    const controller = new AbortController()
    setMetadata(null)
    setError('')
    void apiFetch(`/api/v1/attachments/${id}`, { signal: controller.signal })
      .then(async (response) => {
        if (!response.ok) throw new Error('附件不可访问或已失效。')
        const data = await response.json()
        if (!controller.signal.aborted) setMetadata(data)
      })
      .catch(() => {
        if (!controller.signal.aborted) setError('附件不可访问或已失效。')
      })
    return () => controller.abort()
  }, [id])
  return { metadata, error }
}

type Page = {
  number: number
  text: string
  startLine?: number
  endLine?: number
}
type Slice = { document: ImageAttachment; pages: Page[] }

export function DocumentPreview({
  id,
  initialPage = 1,
  onClose,
}: {
  id: string
  initialPage?: number
  onClose: () => void
}) {
  const dialog = useRef<HTMLDialogElement>(null)
  const [page, setPage] = useState(initialPage)
  const [data, setData] = useState<Slice | null>(null)
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState('')
  const [attempt, setAttempt] = useState(0)
  useEffect(() => {
    const element = dialog.current
    const trigger = document.activeElement instanceof HTMLElement ? document.activeElement : null
    element?.showModal()
    return () => {
      element?.close()
      requestAnimationFrame(() => { if (!element?.open && trigger?.isConnected) trigger.focus({ preventScroll: true }) })
    }
  }, [])
  useEffect(() => {
    const controller = new AbortController()
    setLoading(true)
    setError('')
    void apiFetch(`/api/v1/attachments/${id}/document?page=${page}`, {
      signal: controller.signal,
    })
      .then(async (response) => {
        if (!response.ok) throw new Error('文档暂时无法读取，请重试。')
        const result = await response.json()
        if (!controller.signal.aborted) {
          setData(result)
          setLoading(false)
        }
      })
      .catch(() => {
        if (!controller.signal.aborted) {
          setError('文档暂时无法读取，请重试。')
          setLoading(false)
        }
      })
    return () => controller.abort()
  }, [id, page, attempt])
  const pdf = data?.document.kind === 'PDF'
  const position = data?.pages[0]
  return createPortal(
    <dialog
      ref={dialog}
      className="document-preview"
      aria-label="文档预览"
      onCancel={onClose}
      onClick={(event) => {
        if (event.target === event.currentTarget) onClose()
      }}
    >
      <div className="document-preview-layout">
        <header>
          <div>
            <small>{pdf ? 'PDF' : '文字资料'}</small>
            <h2>{data?.document.fileName || '正在读取文档…'}</h2>
          </div>
          <button type="button" onClick={onClose} aria-label="关闭文档预览">
            ×
          </button>
        </header>
        {data?.document.warning && (
          <p className="attachment-warning" role="status">
            {data.document.warning}
          </p>
        )}
        <div className="document-page-body" aria-busy={loading}>
          {error ? (
            <div role="alert">
              <p>{error}</p>
              <button
                type="button"
                onClick={() => setAttempt((value) => value + 1)}
              >
                重试读取
              </button>
            </div>
          ) : loading ? (
            <p role="status">正在读取…</p>
          ) : (
            <>
              <p className="document-position">
                {pdf
                  ? `第 ${page} / ${data?.document.pageCount} 页`
                  : `片段 ${page} / ${data?.document.pageCount} · 第 ${position?.startLine}–${position?.endLine} 行`}
              </p>
              <pre tabIndex={0}>
                {position?.text.trim()
                  ? position.text
                  : '此页未提取到文字，可能为空白或扫描页。'}
              </pre>
            </>
          )}
        </div>
        <footer>
          <button
            type="button"
            disabled={loading || page <= 1}
            onClick={() => { setLoading(true); setPage((value) => value - 1) }}
          >
            上一{pdf ? '页' : '段'}
          </button>
          <span>
            {page} / {data?.document.pageCount || '…'}
          </span>
          <button
            type="button"
            disabled={
              loading || !data || page >= (data.document.pageCount || 0)
            }
            onClick={() => { setLoading(true); setPage((value) => value + 1) }}
          >
            下一{pdf ? '页' : '段'}
          </button>
        </footer>
      </div>
    </dialog>, document.body
  )
}

export function DocumentCitation({ token }: { token: string }) {
  const match = token.match(/^\[文档:([0-9a-fA-F-]{36}):(\d+)\]$/)
  const id = match?.[1] || '',
    page = Number(match?.[2] || 1)
  const { metadata, error } = useAttachmentMetadata(id)
  const [open, setOpen] = useState(false)
  if (!match) return null
  const valid =
    metadata &&
    metadata.kind !== 'IMAGE' &&
    page >= 1 &&
    page <= (metadata.pageCount || 0)
  if (error || (metadata && !valid))
    return (
      <span className="document-citation-unavailable">（文档引用不可用）</span>
    )
  return (
    <>
      <button
        className="document-citation"
        type="button"
        disabled={!valid}
        onClick={() => setOpen(true)}
      >
        {metadata
          ? `《${metadata.fileName}》${metadata.kind === 'PDF' ? '第' : '片段'}${page}${metadata.kind === 'PDF' ? '页' : ''}`
          : '读取来源…'}
      </button>
      {open && (
        <DocumentPreview
          id={id}
          initialPage={page}
          onClose={() => setOpen(false)}
        />
      )}
    </>
  )
}
