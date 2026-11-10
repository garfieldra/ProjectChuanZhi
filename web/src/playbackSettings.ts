import { useEffect, useState } from 'react'
export type PlaybackSettings = { version: 1; volume: number; spectrum: boolean; reduceMotion: boolean }
const key = 'soundseek-playback-settings-v1'
export const settingsEvent = 'soundseek-settings-change'
export function readPlaybackSettings(): PlaybackSettings {
  try {
    const value = JSON.parse(localStorage.getItem(key) || '{}')
    return { version: 1, volume: typeof value.volume === 'number' && Number.isFinite(value.volume) ? Math.max(0, Math.min(1, value.volume)) : .7, spectrum: value.spectrum !== false, reduceMotion: value.reduceMotion === true }
  } catch { return { version: 1, volume: .7, spectrum: true, reduceMotion: false } }
}
export function savePlaybackSettings(patch: Partial<PlaybackSettings>) {
  const next = { ...readPlaybackSettings(), ...patch, version: 1 as const }
  if (!Number.isFinite(next.volume) || next.volume < 0 || next.volume > 1) throw new Error('音量需要在 0–100% 之间。')
  try { localStorage.setItem(key, JSON.stringify(next)) } catch { throw new Error('浏览器未允许保存设置，请检查存储权限后重试。') }
  syncMotionPreference(); window.dispatchEvent(new Event(settingsEvent)); return next
}
export function syncMotionPreference() { document.documentElement.dataset.reduceMotion = String(readPlaybackSettings().reduceMotion) }
export function usePlaybackSettings() {
  const [value, setValue] = useState(readPlaybackSettings)
  useEffect(() => {
    const update = () => { setValue(readPlaybackSettings()); syncMotionPreference() }
    window.addEventListener(settingsEvent, update); window.addEventListener('storage', update)
    return () => { window.removeEventListener(settingsEvent, update); window.removeEventListener('storage', update) }
  }, [])
  return value
}
