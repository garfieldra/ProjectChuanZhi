import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { settingsRequest } from './settingsApi'
describe('settings API',()=>{
 beforeEach(()=>vi.stubGlobal('document',{cookie:'XSRF-TOKEN=settings-token'}))
 afterEach(()=>{vi.unstubAllGlobals();vi.useRealTimers()})
 it('persists profile with CSRF and accepts a bodyless memory removal response',async()=>{
  const fetch=vi.fn().mockResolvedValueOnce(new Response(JSON.stringify({displayName:'听友'}))).mockResolvedValueOnce(new Response(null,{status:204}));vi.stubGlobal('fetch',fetch)
  await settingsRequest('/auth/profile',{method:'PATCH',body:JSON.stringify({displayName:'听友'})});await settingsRequest('/music-preference-memories/one',{method:'DELETE'})
  expect(fetch.mock.calls[0][1].headers.get('X-XSRF-TOKEN')).toBe('settings-token');expect(fetch.mock.calls[1][1].headers.get('X-XSRF-TOKEN')).toBe('settings-token')
 })
 it('reports expired sessions and cancels pending loads',async()=>{
  vi.stubGlobal('fetch',vi.fn().mockResolvedValue(new Response(null,{status:401})))
  await expect(settingsRequest('/music-preference-memories')).rejects.toThrow('登录状态已失效')
  vi.useFakeTimers();vi.stubGlobal('fetch',vi.fn().mockImplementation((_url,opts)=>new Promise((_resolve,reject)=>opts.signal.addEventListener('abort',()=>reject(new DOMException('abort','AbortError'))))))
  const result=expect(settingsRequest('/auth/profile')).rejects.toThrow('连接超时');await vi.advanceTimersByTimeAsync(15000);await result;expect(vi.getTimerCount()).toBe(0)
 })
})
