import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { PreviewAudio, type AudioSnapshot } from './previewAudio'
class AudioMock {
  static all: AudioMock[] = []
  paused = true; ended = false; currentTime = 0; duration = 30; src = ''; crossOrigin = ''; preload = ''
  onplaying: (() => void) | null = null; onpause: (() => void) | null = null; onended: (() => void) | null = null; onerror: (() => void) | null = null; ontimeupdate: (() => void) | null = null; onloadedmetadata: (() => void) | null = null
  constructor() { AudioMock.all.push(this) }
  play = vi.fn(async () => { this.paused = false; this.onplaying?.() })
  pause() { this.paused = true; this.onpause?.() }
  removeAttribute() { this.src = '' }
  load() {}
}
class ContextMock {
  state = 'running'; destination = {}; resume = vi.fn(async () => {}); close = vi.fn(async () => {})
  createMediaElementSource = vi.fn(() => ({ connect: vi.fn(), disconnect: vi.fn() }))
  createAnalyser = vi.fn(() => ({ connect: vi.fn(), disconnect: vi.fn(), fftSize: 0, smoothingTimeConstant: 0 }))
}
const players: PreviewAudio[] = []
function player() { const events: AudioSnapshot[] = []; const audio = new PreviewAudio(value => events.push(value)); players.push(audio); return { audio, events } }
describe('preview audio lifecycle', () => {
  beforeEach(() => { AudioMock.all = []; vi.stubGlobal('window', new EventTarget()); vi.stubGlobal('Audio', AudioMock); vi.stubGlobal('AudioContext', ContextMock) })
  afterEach(() => { players.splice(0).forEach(item => item.dispose()); vi.unstubAllGlobals() })
  it('uses anonymous CORS before loading and publishes real play/pause/progress events', async () => {
    const {audio,events}=player();await audio.play('a','/preview.wav');const media=AudioMock.all[0]
    expect(media.crossOrigin).toBe('anonymous');expect(events[events.length - 1]?.analysis).toBe('live')
    media.currentTime=12;media.ontimeupdate?.();expect(events[events.length - 1]?.currentTime).toBe(12)
    await audio.toggle();expect(events[events.length - 1]?.phase).toBe('paused')
    await audio.toggle();expect(events[events.length - 1]?.phase).toBe('playing')
    audio.seek(99);expect(media.currentTime).toBe(30)
  })
  it('stops another card and invalidates its ownership when a new card claims playback', async () => {
    const a=player(),b=player();await a.audio.play('a','/a');b.audio.prepare()
    expect(a.audio.isOwner()).toBe(false);expect(AudioMock.all[0].paused).toBe(true);expect(a.events[a.events.length - 1]?.phase).toBe('idle')
  })
  it('clears pending playback when another card claims the player before its URL arrives', () => {
    const a=player(),b=player();a.audio.prepare('pending');expect(a.events[a.events.length-1]?.phase).toBe('loading');b.audio.prepare('new');expect(a.events[a.events.length-1]?.phase).toBe('idle');expect(a.audio.isOwner()).toBe(false)
  })
  it('falls back once to ordinary playback when anonymous CORS loading fails', async () => {
    const {audio,events}=player();await audio.play('a','/cross-origin');AudioMock.all[0].onerror?.()
    await Promise.resolve();expect(AudioMock.all).toHaveLength(2);expect(AudioMock.all[1].crossOrigin).toBe('');expect(events[events.length - 1]).toMatchObject({phase:'playing',analysis:'unavailable'});expect(audio.analyser).toBeNull()
    AudioMock.all[1].onerror?.();expect(events[events.length - 1]?.phase).toBe('error');expect(AudioMock.all).toHaveLength(2)
  })
  it('ignores stale callbacks after switching and cleans up the audio graph', async () => {
    const {audio,events}=player();await audio.play('a','/a');const stale=AudioMock.all[0].onplaying
    await audio.play('b','/b');const count=events.length;stale?.();expect(events).toHaveLength(count)
    audio.dispose();expect(audio.audio).toBeNull();expect(audio.analyser).toBeNull();expect(AudioMock.all.every(item=>item.paused)).toBe(true)
  })
  it('applies saved volume to new audio and to active audio when settings change', async () => {
    let volume=.25
    vi.stubGlobal('localStorage',{getItem:()=>JSON.stringify({volume})})
    const {audio}=player();await audio.play('a','/a')
    expect(audio.audio?.volume).toBe(.25)
    volume=.8;window.dispatchEvent(new Event('soundseek-settings-change'))
    expect(audio.audio?.volume).toBe(.8)
    audio.dispose();volume=.1;window.dispatchEvent(new Event('soundseek-settings-change'));expect(audio.audio).toBeNull()
  })
  it('supports ordinary playback when Web Audio is unavailable',async()=>{
    vi.stubGlobal('AudioContext',undefined);const {audio,events}=player();await audio.play('a','/a');expect(events[events.length - 1]).toMatchObject({phase:'playing',analysis:'unavailable'})
  })
})
