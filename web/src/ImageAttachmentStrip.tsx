import { useCallback, useEffect, useRef, useState } from 'react'
import { apiFetch } from './apiClient'
import {
  thumbnailLayout,
  uploadImage,
  validateImageFiles,
  type ImageAttachment,
} from './imageAttachmentApi'
import { DocumentPreview } from './DocumentPreview'
import { attachmentKind } from './imageAttachmentApi'
import './imageAttachments.css'

export function useImageAttachments() {
  const [images, setImages] = useState<ImageAttachment[]>([])
  const [error, setError] = useState('')
  const current = useRef(images)
  current.current = images
  const disposed = useRef(false)
  const files = useRef(new Map<string, File>())
  const controllers = useRef(new Map<string, AbortController>())
  const urls = useRef(new Set<string>())

  useEffect(() => {
    disposed.current = false
    return () => {
      disposed.current = true
      controllers.current.forEach((controller) => controller.abort())
      urls.current.forEach((url) => URL.revokeObjectURL(url))
    }
  }, [])

  const send = useCallback(async (localId: string, file: File) => {
    // Selection may have been removed while the guest session was connecting.
    if (
      disposed.current ||
      !files.current.has(localId) ||
      controllers.current.has(localId)
    )
      return
    const controller = new AbortController()
    controllers.current.set(localId, controller)
    let timedOut = false
    const timeout = setTimeout(() => {
      timedOut = true
      controller.abort()
    }, 30000)
    setImages((previous) =>
      previous.map((image) =>
        image.id === localId
          ? { ...image, status: 'uploading', error: undefined }
          : image,
      ),
    )
    try {
      const result = await uploadImage(file, controller.signal)
      if (controller.signal.aborted || disposed.current) return
      setImages((previous) =>
        previous.map((image) =>
          image.id === localId
            ? { ...image, ...result, byteSize: image.byteSize, status: 'ready' }
            : image,
        ),
      )
      files.current.delete(localId)
    } catch (reason) {
      if (!disposed.current && (!controller.signal.aborted || timedOut)) {
        setImages((previous) =>
          previous.map((image) =>
            image.id === localId
              ? {
                  ...image,
                  status: 'error',
                  error: timedOut
                    ? '附件处理超时，请重试。'
                    : reason instanceof Error
                      ? reason.message
                      : '附件处理失败。',
                }
              : image,
          ),
        )
      }
    } finally {
      clearTimeout(timeout)
      controllers.current.delete(localId)
    }
  }, [])

  async function connect() {
    const key = crypto.randomUUID()
    const controller = new AbortController()
    controllers.current.set(key, controller)
    const timeout = setTimeout(() => controller.abort(), 30000)
    try {
      const response = await apiFetch('/api/v1/auth/session', {
        signal: controller.signal,
      })
      if (!response.ok || controller.signal.aborted || disposed.current)
        throw new Error('暂时无法连接附件上传服务。')
    } finally {
      clearTimeout(timeout)
      controllers.current.delete(key)
    }
  }

  async function add(selected: File[]) {
    if (!selected.length) return
    try {
      validateImageFiles(selected, current.current)
      setError('')
      const added = selected.map((file) => {
        const id = crypto.randomUUID(),
          url = URL.createObjectURL(file)
        files.current.set(id, file)
        urls.current.add(url)
        return {
          id,
          url,
          fileName: file.name,
          byteSize: file.size,
          kind: attachmentKind(file),
          status: 'uploading' as const,
        }
      })
      const next = [...current.current, ...added]
      current.current = next
      setImages(next)
      // Establish the guest cookie and CSRF token before parallel uploads.
      try {
        await connect()
      } catch {
        if (!disposed.current)
          setImages((previous) =>
            previous.map((image) =>
              added.some((item) => item.id === image.id)
                ? {
                    ...image,
                    status: 'error',
                    error: '暂时无法连接附件上传服务，请重试。',
                  }
                : image,
            ),
          )
        return
      }
      await Promise.all(
        added.map((image, index) => send(image.id, selected[index])),
      )
    } catch (reason) {
      if (!disposed.current)
        setError(reason instanceof Error ? reason.message : '无法添加附件。')
    }
  }

  function remove(id: string) {
    controllers.current.get(id)?.abort()
    files.current.delete(id)
    const item = current.current.find((image) => image.id === id)
    if (item) {
      URL.revokeObjectURL(item.url)
      urls.current.delete(item.url)
    }
    current.current = current.current.filter((image) => image.id !== id)
    setImages(current.current)
    setError('')
  }

  function clear() {
    controllers.current.forEach((controller) => controller.abort())
    files.current.clear()
    urls.current.forEach((url) => URL.revokeObjectURL(url))
    urls.current.clear()
    current.current = []
    if (!disposed.current) {
      setImages([])
      setError('')
    }
  }

  async function retry(id: string) {
    const file = files.current.get(id)
    if (!file) return
    try {
      await connect()
      await send(id, file)
    } catch {
      if (!disposed.current) setError('暂时无法连接上传服务，请重试。')
    }
  }

  return {
    images,
    error,
    add,
    remove,
    clear,
    retry,
    restore: (value: ImageAttachment[]) => {
      current.current = value
      setImages(value)
    },
    ready: images.every((image) => image.status === 'ready'),
    ids: images
      .filter((image) => image.status === 'ready')
      .map((image) => image.id),
  }
}
export function ImageUploadButton({
  onFiles,
  disabled = false,
}: {
  onFiles: (files: File[]) => void
  disabled?: boolean
}) {
  const input = useRef<HTMLInputElement>(null)
  return (
    <>
      <input
        ref={input}
        type="file"
        accept="image/jpeg,image/png,image/webp,.txt,.md,.markdown,.pdf"
        multiple
        hidden
        onChange={(event) => {
          onFiles(Array.from(event.target.files || []))
          event.target.value = ''
        }}
      />
      <button
        className="image-upload-button"
        type="button"
        aria-label="添加附件"
        disabled={disabled}
        onClick={() => input.current?.click()}
      >
        <svg viewBox="0 0 24 24" aria-hidden="true">
          <path d="M12 5v14M5 12h14" />
        </svg>
      </button>
    </>
  )
}
export function ImageAttachments({
  images,
  onRemove,
  onRetry,
  disabled = false,
}: {
  images: ImageAttachment[]
  onRemove?: (id: string) => void
  onRetry?: (id: string) => void
  disabled?: boolean
}) {
  const [expanded, setExpanded] = useState(false),
    [preview, setPreview] = useState<ImageAttachment | null>(null)
  const dialog = useRef<HTMLDialogElement>(null)
  const [documentPreview, setDocumentPreview] =
    useState<ImageAttachment | null>(null)
  useEffect(() => {
    if (preview) {
      dialog.current?.showModal()
      return () => dialog.current?.close()
    }
  }, [preview])
  useEffect(() => {
    if (images.length === 0) {
      setExpanded(false)
      setPreview(null)
    }
  }, [images.length])
  const layout = thumbnailLayout(images, expanded)
  if (!images.length) return null
  return (
    <div className="image-attachments">
      <div
        className="image-thumbnail-row"
        aria-label={`已添加${images.length}个附件`}
      >
        {layout.visible.map((image, index) => (
          <div
            className={`image-thumbnail ${image.kind && image.kind !== 'IMAGE' ? 'file-thumbnail' : ''}`}
            key={image.id}
            data-status={image.status}
          >
            <button
              type="button"
              className="image-thumbnail-open"
              aria-label={
                index === 2 && layout.hidden
                  ? `展开全部${images.length}个附件`
                  : image.kind && image.kind !== 'IMAGE'
                    ? `预览文件：${image.fileName}`
                    : `预览图片：${image.fileName}`
              }
              onClick={() =>
                index === 2 && layout.hidden
                  ? setExpanded(true)
                  : image.kind && image.kind !== 'IMAGE'
                    ? image.status === 'ready' && setDocumentPreview(image)
                    : setPreview(image)
              }
            >
              {image.kind && image.kind !== 'IMAGE' ? (
                <span className="file-tile" title={image.fileName}>
                  <span className="file-type">
                    {image.kind === 'PDF'
                      ? 'PDF'
                      : /\.md|\.markdown$/i.test(image.fileName)
                        ? 'MD'
                        : 'TXT'}
                  </span>
                  <strong>{image.fileName}</strong>
                  <small>
                    {image.status === 'uploading'
                      ? '上传/解析中'
                      : image.pageCount
                        ? `${image.pageCount}${image.kind === 'PDF' ? '页' : '个片段'}`
                        : '文档'}
                  </small>
                </span>
              ) : (
                <img src={image.url} alt={image.fileName} />
              )}
              {index === 2 && layout.hidden > 0 && (
                <span className="image-overflow-count">+{layout.hidden}</span>
              )}
            </button>
            {onRemove && (
              <button
                className="image-remove"
                type="button"
                disabled={disabled}
                aria-label={`移除${image.kind && image.kind !== 'IMAGE' ? '附件' : '图片'}：${image.fileName}`}
                onClick={() => onRemove(image.id)}
              >
                ×
              </button>
            )}
            {image.status === 'uploading' && (
              <span className="image-upload-state" role="status">
                {image.kind && image.kind !== 'IMAGE' ? '解析中' : '上传中'}
              </span>
            )}
            {image.status === 'error' && (
              <button
                className="image-upload-state image-retry"
                type="button"
                title={image.error}
                onClick={() => onRetry?.(image.id)}
                disabled={disabled}
              >
                重试
              </button>
            )}
          </div>
        ))}
      </div>
      {expanded && images.length > 3 && (
        <button
          className="image-collapse"
          type="button"
          onClick={() => setExpanded(false)}
        >
          收起附件
        </button>
      )}
      {images.some((image) => image.status === 'error') && (
        <p className="image-error" role="alert">
          {images
            .filter((image) => image.status === 'error')
            .map(
              (image) =>
                `${image.fileName}：${image.error || '附件处理失败，请重试或移除后发送。'}`,
            )
            .join('；')}
        </p>
      )}
      {images.some((image) => image.warning) && (
        <p className="attachment-warning" role="status">
          {images
            .filter((image) => image.warning)
            .map((image) => `${image.fileName}：${image.warning}`)
            .join('；')}
        </p>
      )}
      {documentPreview && (
        <DocumentPreview
          id={documentPreview.id}
          onClose={() => setDocumentPreview(null)}
        />
      )}
      {preview && (
        <dialog
          ref={dialog}
          className="image-preview-dialog"
          aria-label={`图片预览：${preview.fileName}`}
          onClose={() => setPreview(null)}
          onClick={(event) => {
            if (event.target === event.currentTarget) setPreview(null)
          }}
        >
          <button
            type="button"
            onClick={() => setPreview(null)}
            aria-label="关闭图片预览"
          >
            ×
          </button>
          <img src={preview.url} alt={preview.fileName} />
        </dialog>
      )}
    </div>
  )
}
export function MessageImages({ ids }: { ids?: string[] }) {
  const [images, setImages] = useState<ImageAttachment[]>([])
  const [failed, setFailed] = useState(false)
  const key = (ids || []).join(',')
  useEffect(() => {
    const controller = new AbortController()
    setImages([])
    setFailed(false)
    if (!key) return () => controller.abort()
    void Promise.all(
      key.split(',').map(async (id) => {
        const response = await apiFetch(`/api/v1/attachments/${id}`, {
          signal: controller.signal,
        })
        if (!response.ok) throw new Error('附件不可用')
        const meta = await response.json()
        return {
          ...meta,
          url: `/api/v1/attachments/${id}/content`,
          status: 'ready',
        } as ImageAttachment
      }),
    )
      .then(setImages)
      .catch(() => {
        if (!controller.signal.aborted) setFailed(true)
      })
    return () => controller.abort()
  }, [key])
  if (failed) return <p className="image-error">附件不可用或已失效。</p>
  if (key && !images.length)
    return (
      <p className="attachment-loading" role="status">
        正在读取附件…
      </p>
    )
  return <ImageAttachments images={images} />
}
