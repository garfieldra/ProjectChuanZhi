import { useCallback, useEffect, useRef, useState } from 'react'
import { PreviewAudio, type AudioSnapshot } from './previewAudio'
import { apiFetch } from './apiClient'

export type ListeningOptions = {
  recordingId: string
  status: 'AVAILABLE' | 'UNAVAILABLE'
  preview: null | {
    provider: 'APPLE_ITUNES'
    url: string
    providerTrackUrl: string
    attribution: string
  }
  platformLinks: { provider: string; label: string; url: string }[]
}

export type PlaybackPhase = 'idle' | 'loading' | 'playing' | 'paused' | 'unavailable' | 'error'
export type PlaybackState = { phase: PlaybackPhase; options?: ListeningOptions }

export function playbackLabel(phase: PlaybackPhase) {
  if (phase === 'loading') return '正在准备试听…'
  if (phase === 'playing') return '正在播放 · 点击暂停'
  if (phase === 'paused') return '已暂停 · 点击继续'
  if (phase === 'unavailable') return '暂无试听'
  if (phase === 'error') return '试听暂不可用'
  return '点击卡片试听'
}

export function useListeningPreview() {
  const [active, setActive] = useState<AudioSnapshot | null>(null)
  const playerRef = useRef<PreviewAudio | null>(null)
  const cacheRef = useRef(new Map<string, ListeningOptions>())
  const pendingRef = useRef(new Map<string, Promise<ListeningOptions>>())
  const requestGenerationRef = useRef(0)
  const [states, setStates] = useState<Record<string, PlaybackState>>({})

  function update(recordingId: string, state: PlaybackState) {
    setStates(current => ({ ...current, [recordingId]: state }))
  }

  if (!playerRef.current) playerRef.current = new PreviewAudio(snapshot => {
    setActive(snapshot)
    setStates(previous => ({ ...previous, [snapshot.id]: { ...previous[snapshot.id], phase: snapshot.phase } }))
  })
  const analyser = useCallback(() => playerRef.current?.analyser ?? null, [])
  function stop() { requestGenerationRef.current++; playerRef.current?.stop() }

  async function load(recordingId: string) {
    const cached = cacheRef.current.get(recordingId)
    if (cached) return cached
    const pending = pendingRef.current.get(recordingId)
    if (pending) return pending
    const request = apiFetch(`/api/v1/recordings/${recordingId}/listening-options`)
      .then(async response => {
        if (!response.ok) throw new Error(`试听资源请求失败（${response.status}）`)
        const options = await response.json() as ListeningOptions
        cacheRef.current.set(recordingId, options)
        return options
      })
      .finally(() => pendingRef.current.delete(recordingId))
    pendingRef.current.set(recordingId, request)
    return request
  }

  async function toggle(recordingId: string) {
    const player = playerRef.current!
    if (active?.id === recordingId && player.audio && active.phase !== 'error') { await player.toggle(); return }
    stop(); setActive(null)
    const generation = requestGenerationRef.current
    player.prepare(recordingId)
    update(recordingId, { ...states[recordingId], phase: 'loading' })
    try {
      const options = await load(recordingId)
      if (generation !== requestGenerationRef.current || !player.isOwner()) return
      if (!options.preview) { player.stop(); setActive(null); update(recordingId, { phase: 'unavailable', options }); return }
      update(recordingId, { phase: 'loading', options })
      await player.play(recordingId, options.preview.url)
    } catch {
      if (generation === requestGenerationRef.current && player.isOwner()) { player.stop(); setActive(null); update(recordingId, { phase: 'error', options: cacheRef.current.get(recordingId) }) }
    }
  }

  async function prefetch(recordingId: string) {
    if (cacheRef.current.has(recordingId)) return
    try {
      const options = await load(recordingId)
      setStates(previous => previous[recordingId] ? previous : { ...previous, [recordingId]: { phase: options.preview ? 'idle' : 'unavailable', options } })
    } catch {
      // Hover/focus prefetch is best-effort; an explicit click will retry visibly.
    }
  }

  useEffect(() => () => { requestGenerationRef.current++; playerRef.current?.dispose() }, [])

  return {
    active,
    analyser,
    seek: (time: number) => playerRef.current?.seek(time),
    stateFor: (recordingId: string): PlaybackState => states[recordingId] ?? { phase: 'idle' },
    toggle,
    prefetch,
    stop,
  }
}
