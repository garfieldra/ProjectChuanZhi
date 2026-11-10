import { describe, expect, it } from 'vitest'
import {
  thumbnailLayout,
  validateImageFiles,
  MAX_IMAGE_BYTES,
} from './imageAttachmentApi'
describe('image attachment selection', () => {
  it('shows the third actual image with only additional images counted', () => {
    expect(thumbnailLayout([])).toEqual({ visible: [], hidden: 0 })
    expect(thumbnailLayout([1])).toEqual({ visible: [1], hidden: 0 })
    expect(thumbnailLayout(Array.from({ length: 10 }, (_, i) => i))).toEqual({
      visible: [0, 1, 2],
      hidden: 7,
    })
    expect(thumbnailLayout([1, 2, 3])).toEqual({
      visible: [1, 2, 3],
      hidden: 0,
    })
    expect(thumbnailLayout([1, 2, 3, 4])).toEqual({
      visible: [1, 2, 3],
      hidden: 1,
    })
    expect(thumbnailLayout([1, 2, 3, 4, 5])).toEqual({
      visible: [1, 2, 3],
      hidden: 2,
    })
    expect(thumbnailLayout([1, 2, 3, 4, 5], true)).toEqual({
      visible: [1, 2, 3, 4, 5],
      hidden: 0,
    })
  })
  it('rejects unsupported, oversized and excessive attachments before uploading', () => {
    const file = (type: string, size = 100) => ({ type, size }) as File
    expect(() => validateImageFiles([file('image/svg+xml')], [])).toThrow('JPG')
    expect(() =>
      validateImageFiles([file('image/png', MAX_IMAGE_BYTES + 1)], []),
    ).toThrow('8MB')
    expect(() =>
      validateImageFiles(
        Array.from({ length: 11 }, () => file('image/png')),
        [],
      ),
    ).toThrow('10张')
  })
})
