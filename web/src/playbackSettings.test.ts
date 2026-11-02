import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { readPlaybackSettings, savePlaybackSettings } from './playbackSettings'
describe('playback settings persistence', () => {
  let saved: string | null
  beforeEach(() => { saved=null;vi.stubGlobal('localStorage',{getItem:()=>saved,setItem:(_key:string,value:string)=>{saved=value}});vi.stubGlobal('document',{documentElement:{dataset:{}}});vi.stubGlobal('window',new EventTarget()) })
  afterEach(()=>vi.unstubAllGlobals())
  it('survives a re-read and publishes changes for active players',()=>{
    const listener=vi.fn();window.addEventListener('soundseek-settings-change',listener)
    savePlaybackSettings({volume:.25,spectrum:false,reduceMotion:true})
    expect(readPlaybackSettings()).toEqual({version:1,volume:.25,spectrum:false,reduceMotion:true});expect(listener).toHaveBeenCalledTimes(1);expect(document.documentElement.dataset.reduceMotion).toBe('true')
  })
  it('recovers from corrupt storage and rejects out-of-range volume',()=>{
    saved='{broken';expect(readPlaybackSettings().volume).toBe(.7)
    expect(()=>savePlaybackSettings({volume:NaN})).toThrow('0–100%')
  })
  it('does not pretend a failed write was saved',()=>{
    vi.stubGlobal('localStorage',{getItem:()=>null,setItem:()=>{throw new Error('blocked')}})
    expect(()=>savePlaybackSettings({spectrum:false})).toThrow('未允许保存');expect(readPlaybackSettings().spectrum).toBe(true)
  })
})
