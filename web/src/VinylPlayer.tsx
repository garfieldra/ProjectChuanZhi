import { usePlaybackSettings } from './playbackSettings'
import { useEffect, useRef } from 'react'
import type { AudioSnapshot } from './previewAudio'
import './vinyl.css'

type Props = { title: string; artist?: string; coverUrl?: string; state: AudioSnapshot; analyser: () => AnalyserNode | null; onToggle: () => void; onSeek: (time: number) => void; attribution?: string; trackUrl?: string }
const time = (value: number) => `${Math.floor(value / 60)}:${String(Math.floor(value % 60)).padStart(2, '0')}`

export function VinylPlayer({ title, artist, coverUrl, state, analyser, onToggle, onSeek, attribution, trackUrl }: Props) {
  const preferences = usePlaybackSettings()
  const canvas = useRef<HTMLCanvasElement>(null)
  const disc = useRef<SVGSVGElement>(null)
  const motion = useRef({ angle: 0, speed: 0 })
  const playing = state.phase === 'playing'
  useEffect(() => {
    const surface = canvas.current!
    const ctx = surface.getContext('2d')
    if (!ctx) return
    const reduced = matchMedia('(prefers-reduced-motion: reduce)')
    let frame = 0, last = 0, stopped = false
    const bytes = new Uint8Array(128)
    const draw = (now: number) => {
      if (stopped) return
      const dt = Math.min((now - (last || now)) / 1000, .05); last = now
      const moving = playing && !document.hidden && !reduced.matches && !preferences.reduceMotion
      if (document.hidden || reduced.matches || preferences.reduceMotion) motion.current.speed = 0
      const target = moving ? 100 : 0
      motion.current.speed += (target - motion.current.speed) * (1 - Math.exp(-dt * 4))
      motion.current.angle = (motion.current.angle + motion.current.speed * dt) % 360
      disc.current?.style.setProperty('transform', `rotate(${motion.current.angle}deg)`)
      const size = 240, scale = Math.min(devicePixelRatio || 1, 2)
      if (surface.width !== size * scale) { surface.width = size * scale; surface.height = size * scale }
      ctx.setTransform(scale, 0, 0, scale, 0, 0); ctx.clearRect(0, 0, size, size)
      const node = analyser()
      const live = preferences.spectrum && moving && state.analysis === 'live' && node
      if (live) node.getByteFrequencyData(bytes)
      ctx.strokeStyle = '#a64b3c'; ctx.lineWidth = 1.4; ctx.lineCap = 'round'
      for (let i = 0; i < 72; i++) {
        const angle = i / 72 * Math.PI * 2 - Math.PI / 2
        const amplitude = live ? bytes[Math.floor(i / 72 * 90)] / 255 : 0
        const start = 100, end = start + 2 + amplitude * 16
        ctx.beginPath(); ctx.moveTo(120 + Math.cos(angle) * start, 120 + Math.sin(angle) * start); ctx.lineTo(120 + Math.cos(angle) * end, 120 + Math.sin(angle) * end); ctx.stroke()
      }
      if (moving || motion.current.speed > .1) frame = requestAnimationFrame(draw)
    }
    const restart = () => { cancelAnimationFrame(frame); last = 0; frame = requestAnimationFrame(draw) }
    restart(); document.addEventListener('visibilitychange', restart); reduced.addEventListener('change', restart)
    return () => { stopped = true; cancelAnimationFrame(frame); document.removeEventListener('visibilitychange', restart); reduced.removeEventListener('change', restart) }
  }, [playing, state.analysis, analyser, preferences.spectrum, preferences.reduceMotion])

  return <section className="vinyl-player" aria-label={`黑胶试听：${title}`} data-phase={state.phase} data-analysis={state.analysis}>
    <div className="vinyl-art" aria-hidden="true">
      <canvas ref={canvas} hidden={!preferences.spectrum} />
      <svg ref={disc} viewBox="0 0 200 200" className="vinyl-disc">
        <circle cx="100" cy="100" r="98" fill="#242320" />
        {Array.from({ length: 23 }, (_, i) => <circle key={i} cx="100" cy="100" r={45 + i * 2.2} fill="none" stroke="#44423e" strokeWidth=".6" />)}
        <circle cx="100" cy="100" r="40" fill="#a64b3c" />
        {coverUrl && <svg x="61" y="61" width="78" height="78" viewBox="0 0 78 78"><defs><clipPath id={`cover-${state.id}`}><circle cx="39" cy="39" r="39" /></clipPath></defs><image href={coverUrl} width="78" height="78" preserveAspectRatio="xMidYMid slice" clipPath={`url(#cover-${state.id})`} /></svg>}
        <circle cx="100" cy="100" r="6" fill="#f8f7f4" /><circle cx="100" cy="66" r="2" fill="#f8f7f4" />
      </svg>
    </div>
    <div className="vinyl-details" key={state.id}>
      <h3>{title}</h3><p>{artist}</p>
      <small>{attribution || '试听片段'}{trackUrl && <> · <a href={trackUrl} target="_blank" rel="noreferrer">Apple iTunes</a></>}</small>
      <input className="vinyl-progress" type="range" aria-label="试听进度" min="0" max={state.duration || 1} step="0.1" value={Math.min(state.currentTime, state.duration || 1)} disabled={!state.duration || state.phase === 'loading' || state.phase === 'error'} onChange={event => onSeek(Number(event.target.value))} />
      <div className="vinyl-controls"><button type="button" onClick={onToggle} disabled={state.phase === 'loading'} aria-label={playing ? '暂停试听' : state.phase === 'error' ? '重试试听' : '播放试听'}><svg viewBox="0 0 24 24" aria-hidden="true">{playing ? <path d="M8 5v14M16 5v14" /> : <path d="m9 5 10 7-10 7Z" />}</svg></button><span>{time(state.currentTime)} / {state.duration ? time(state.duration) : '—:—'}</span><small role="status">{state.phase === 'loading' ? '正在准备试听…' : state.phase === 'error' ? '试听暂不可用，可重试' : state.analysis === 'unavailable' ? '普通试听 · 频谱暂不可用' : playing ? '正在试听' : state.phase === 'paused' ? '已暂停' : '试听已结束'}</small></div>
    </div>
  </section>
}
