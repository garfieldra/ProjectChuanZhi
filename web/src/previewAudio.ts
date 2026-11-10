import { readPlaybackSettings, settingsEvent } from './playbackSettings'
export type AudioPhase = 'idle' | 'loading' | 'playing' | 'paused' | 'error'
export type AudioSnapshot = { id: string; phase: AudioPhase; currentTime: number; duration: number; analysis: 'pending' | 'live' | 'unavailable' }
let owner: PreviewAudio | null = null

// One owner across recommendation cards: switching cards also stops the old audio.
export class PreviewAudio {
  audio: HTMLAudioElement | null = null
  analyser: AnalyserNode | null = null
  private context: AudioContext | null = null
  private source: MediaElementAudioSourceNode | null = null
  private generation = 0
  private snapshot: AudioSnapshot | null = null
  constructor(private notify: (state: AudioSnapshot) => void) {}

  private applyVolume = () => { if (this.audio) this.audio.volume = readPlaybackSettings().volume }
  prepare(id?: string) {
    window.addEventListener(settingsEvent, this.applyVolume)
    window.addEventListener('storage', this.applyVolume)
    if (owner && owner !== this) owner.stop()
    owner = this
    if (id) { this.snapshot = { id, phase: 'loading', currentTime: 0, duration: 0, analysis: 'pending' }; this.notify(this.snapshot) }
    try {
      this.context ??= new AudioContext()
      void this.context.resume().catch(() => {})
    } catch { /* Audio playback remains available without Web Audio. */ }
  }
  isOwner() { return owner === this }
  private emit(patch: Partial<AudioSnapshot>) {
    if (!this.snapshot) return
    this.snapshot = { ...this.snapshot, ...patch }
    this.notify(this.snapshot)
  }
  private detach() {
    if (this.audio) {
      this.audio.onended = this.audio.onerror = this.audio.ontimeupdate = this.audio.onloadedmetadata = this.audio.ondurationchange = this.audio.onplaying = this.audio.onpause = null
      this.audio.pause(); this.audio.removeAttribute('src'); this.audio.load()
    }
    this.source?.disconnect(); this.analyser?.disconnect()
    this.audio = null; this.source = null; this.analyser = null
  }
  stop() {
    this.generation++; this.detach(); this.emit({ phase: 'idle', currentTime: 0, analysis: 'unavailable' })
    if (owner === this) owner = null
  }
  async play(id: string, url: string) {
    this.stop(); this.prepare()
    const generation = this.generation
    this.snapshot = { id, phase: 'loading', currentTime: 0, duration: 0, analysis: 'pending' }
    this.notify(this.snapshot)
    const attempt = async (analyse: boolean) => {
      if (generation !== this.generation) return
      this.detach()
      const audio = new Audio()
      if (analyse) audio.crossOrigin = 'anonymous'
      audio.preload = 'metadata'; audio.src = url; this.audio = audio; this.applyVolume()
      let live = false
      if (analyse && this.context) {
        try {
          this.source = this.context.createMediaElementSource(audio)
          this.analyser = this.context.createAnalyser(); this.analyser.fftSize = 256; this.analyser.smoothingTimeConstant = .8
          this.source.connect(this.analyser); this.analyser.connect(this.context.destination)
          live = true
        } catch { await attempt(false); return }
      }
      let failed = false
      const fail = () => {
        if (failed || generation !== this.generation) return
        failed = true
        if (analyse) void attempt(false)
        else { this.detach(); this.emit({ phase: 'error', analysis: 'unavailable' }) }
      }
      audio.onerror = fail
      audio.onplaying = () => { if (!failed && generation === this.generation) this.emit({ phase: 'playing', analysis: live && this.context?.state === 'running' ? 'live' : 'unavailable' }) }
      audio.onpause = () => { if (!failed && generation === this.generation) this.emit({ phase: 'paused' }) }
      audio.onended = () => { if (generation === this.generation) this.emit({ phase: 'idle', currentTime: 0 }) }
      const progress = () => { if (!failed && generation === this.generation) this.emit({ currentTime: audio.currentTime, duration: Number.isFinite(audio.duration) ? audio.duration : 0 }) }
      audio.ontimeupdate = progress; audio.onloadedmetadata = progress; audio.ondurationchange = progress
      try { await audio.play() } catch (error) {
        if (generation !== this.generation || failed) return
        // Policy/user-gesture failures are not CORS failures; don't replay unexpectedly.
        if (error instanceof DOMException && error.name === 'NotAllowedError') { this.detach(); this.emit({ phase: 'error', analysis: 'unavailable' }); return }
        fail()
      }
    }
    await attempt(Boolean(this.context))
  }
  async toggle() {
    const audio = this.audio
    if (!audio) return
    if (!audio.paused && !audio.ended) audio.pause()
    else {
      const generation = this.generation
      this.prepare()
      try { await audio.play() } catch { if (generation === this.generation) this.emit({ phase: 'error' }) }
    }
  }
  seek(time: number) {
    if (this.audio && Number.isFinite(this.audio.duration)) { this.audio.currentTime = Math.max(0, Math.min(time, this.audio.duration)); this.emit({ currentTime: this.audio.currentTime }) }
  }
  dispose() { window.removeEventListener(settingsEvent, this.applyVolume); window.removeEventListener('storage', this.applyVolume); this.stop(); const context = this.context; this.context = null; if (context) void context.close().catch(() => {}) }
}
