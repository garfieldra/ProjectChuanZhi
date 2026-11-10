import { apiFetch } from './apiClient'
export type ImageAttachment = {
  id: string
  fileName: string
  url: string
  byteSize: number
  status: 'uploading' | 'ready' | 'error'
  error?: string
}
export const MAX_IMAGES = 10,
  MAX_IMAGE_BYTES = 8 * 1024 * 1024,
  MAX_TOTAL_BYTES = 20 * 1024 * 1024
export function thumbnailLayout<T>(images: T[], expanded = false) {
  return {
    visible: expanded ? images : images.slice(0, 3),
    hidden: expanded ? 0 : Math.max(0, images.length - 3),
  }
}
export function validateImageFiles(files: File[], existing: ImageAttachment[]) {
  if (files.length + existing.length > MAX_IMAGES)
    throw new Error('每条消息最多添加10张图片。')
  if (
    files.some(
      (file) => !['image/jpeg', 'image/png', 'image/webp'].includes(file.type),
    )
  )
    throw new Error('请选择JPG、PNG或WebP图片。')
  if (files.some((file) => file.size > MAX_IMAGE_BYTES))
    throw new Error('每张图片不能超过8MB。')
  if (
    files.reduce((sum, file) => sum + file.size, 0) +
      existing.reduce((sum, image) => sum + image.byteSize, 0) >
    MAX_TOTAL_BYTES
  )
    throw new Error('每条消息的图片合计不能超过20MB。')
}
export async function uploadImage(file: File, signal: AbortSignal) {
  // WebP is decoded by the browser, so the Java service only needs the JDK's
  // JPEG/PNG codecs. Re-encoding also removes original photo metadata.
  const bitmap = await createImageBitmap(file)
  let blob: Blob | null = null
  try {
    const scale = Math.min(1, 1600 / Math.max(bitmap.width, bitmap.height))
    const canvas = document.createElement('canvas')
    canvas.width = Math.max(1, Math.round(bitmap.width * scale))
    canvas.height = Math.max(1, Math.round(bitmap.height * scale))
    const context = canvas.getContext('2d')
    if (!context) throw new Error('浏览器暂时无法处理这张图片。')
    context.drawImage(bitmap, 0, 0, canvas.width, canvas.height)
    blob = await new Promise<Blob | null>((resolve) =>
      canvas.toBlob(
        resolve,
        file.type === 'image/png' ? 'image/png' : 'image/jpeg',
        0.9,
      ),
    )
  } finally {
    bitmap.close()
  }
  if (!blob) throw new Error('这张图片无法读取，请换一张重试。')
  const data = new FormData()
  data.append('file', blob, file.name)
  const response = await apiFetch('/api/v1/attachments', {
    method: 'POST',
    body: data,
    signal,
  })
  if (!response.ok)
    throw new Error(
      response.status === 413
        ? '图片超过上传大小限制。'
        : '图片上传失败，请重试。',
    )
  return (await response.json()) as {
    id: string
    fileName: string
    byteSize: number
  }
}
