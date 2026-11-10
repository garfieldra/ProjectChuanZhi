import { useCallback, useEffect, useRef, useState } from 'react'
import { apiFetch } from './apiClient'
import {
  thumbnailLayout,
  uploadImage,
  validateImageFiles,
  type ImageAttachment,
} from './imageAttachmentApi'
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
            ? { ...image, id: result.id, status: 'ready' }
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
                    ? '图片上传超时，请重试。'
                    : reason instanceof Error
                      ? reason.message
                      : '图片上传失败。',
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
        throw new Error('暂时无法连接图片上传服务。')
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
                    error: '暂时无法连接图片上传服务，请重试。',
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
        setError(reason instanceof Error ? reason.message : '无法添加图片。')
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
        accept="image/jpeg,image/png,image/webp"
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
        aria-label="添加图片"
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
  useEffect(() => {
    if (preview) {
      dialog.current?.showModal()
      return () => dialog.current?.close()
    }
  }, [preview])
  useEffect(() => {
    if (images.length === 0) { setExpanded(false); setPreview(null) }
  }, [images.length])
  const layout = thumbnailLayout(images, expanded)
  if (!images.length) return null
  return (
    <div className="image-attachments">
      <div
        className="image-thumbnail-row"
        aria-label={`已添加${images.length}张图片`}
      >
        {layout.visible.map((image, index) => (
          <div
            className="image-thumbnail"
            key={image.id}
            data-status={image.status}
          >
            <button
              type="button"
              className="image-thumbnail-open"
              aria-label={
                index === 2 && layout.hidden
                  ? `展开全部${images.length}张图片`
                  : `预览图片：${image.fileName}`
              }
              onClick={() =>
                index === 2 && layout.hidden
                  ? setExpanded(true)
                  : setPreview(image)
              }
            >
              <img src={image.url} alt={image.fileName} />
              {index === 2 && layout.hidden > 0 && (
                <span className="image-overflow-count">+{layout.hidden}</span>
              )}
            </button>
            {onRemove && (
              <button
                className="image-remove"
                type="button"
                disabled={disabled}
                aria-label={`移除图片：${image.fileName}`}
                onClick={() => onRemove(image.id)}
              >
                ×
              </button>
            )}
            {image.status === 'uploading' && (
              <span className="image-upload-state" role="status">
                上传中
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
          收起图片
        </button>
      )}
      {images.some((image) => image.status === 'error') && (
        <p className="image-error" role="alert">
          有图片上传失败，请重试或移除后发送。
        </p>
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
  return (
    <ImageAttachments
      images={(ids || []).map((id, index) => ({
        id,
        fileName: `图片${index + 1}`,
        url: `/api/v1/attachments/${id}/content`,
        status: 'ready',
        byteSize: 0,
      }))}
    />
  )
}
