import { useEffect, useRef, useState } from 'react'
import { libraryRequest, useMusicLibrary, type SavedPage, type SavedSong } from './musicLibraryStore'
import { VinylPlayer } from './VinylPlayer'
import { playbackLabel } from './listening'
import './musicLibrary.css'

export function MusicLibrary({guest,onBack,onTalk,onSource,onMenu,conversationId}:{conversationId?:string;onMenu:()=>void;guest:boolean;onBack:()=>void;onTalk:(content:string)=>void;onSource:(song:SavedSong)=>void}) {
  const library=useMusicLibrary()
  const [input,setInput]=useState(''),[search,setSearch]=useState('')
  const [items,setItems]=useState<SavedSong[]>([]),[cursor,setCursor]=useState<string|null>(null)
  const [loading,setLoading]=useState(true),[more,setMore]=useState(false),[error,setError]=useState(''),[attempt,setAttempt]=useState(0)
  const [talking,setTalking]=useState<string|null>(null),[talkError,setTalkError]=useState('')
  const request=useRef<AbortController|null>(null), talkRequest=useRef<AbortController|null>(null)
  useEffect(()=>{const timer=setTimeout(()=>setSearch(input.trim()),250);return()=>clearTimeout(timer)},[input])
  useEffect(()=>{
    const controller=new AbortController();request.current?.abort();request.current=controller
    library.clearError();setLoading(true);setMore(false);setError('');setItems([]);setCursor(null)
    void libraryRequest<SavedPage>(`?q=${encodeURIComponent(search)}`,{signal:controller.signal}).then(page=>{
      if(controller.signal.aborted)return;setItems(page.items);setCursor(page.nextCursor);library.prime(page.items.map(song=>song.recordingId))
    }).catch(reason=>{if(!controller.signal.aborted)setError((reason as Error).message)}).finally(()=>{if(!controller.signal.aborted)setLoading(false)})
    return()=>request.current?.abort()
  },[search,library.revision,attempt,library.prime,library.clearError])
  useEffect(()=>()=>talkRequest.current?.abort(),[])
  async function loadMore() {
    if(!cursor||more||loading)return
    const controller=new AbortController();request.current=controller;setMore(true);setError('')
    try {const page=await libraryRequest<SavedPage>(`?q=${encodeURIComponent(search)}&cursor=${encodeURIComponent(cursor)}`,{signal:controller.signal});if(controller.signal.aborted)return
      setItems(previous=>[...previous,...page.items.filter(song=>!previous.some(item=>item.recordingId===song.recordingId))]);setCursor(page.nextCursor);library.prime(page.items.map(song=>song.recordingId))
    }catch(reason){if(!controller.signal.aborted)setError((reason as Error).message)}finally{if(!controller.signal.aborted)setMore(false)}
  }
  async function talk(song:SavedSong) {
    if(talking)return
    const controller=new AbortController();talkRequest.current=controller;setTalking(song.recordingId);setTalkError('')
    try{const value=await libraryRequest<{content:string}>(`/${song.recordingId}/context`,{signal:controller.signal});if(!controller.signal.aborted)onTalk(value.content)}
    catch(reason){if(!controller.signal.aborted)setTalkError((reason as Error).message)}finally{if(!controller.signal.aborted)setTalking(null)}
  }
  const active=library.listening.active,song=library.playingSong,preview=active ? library.listening.stateFor(active.id).options?.preview : null
  return <section className="music-library" aria-labelledby="library-title">
    <header className="library-header"><button className="library-menu-toggle" type="button" aria-label="打开会话列表" onClick={onMenu}><svg viewBox="0 0 24 24" aria-hidden="true"><path d="M4 6h16M4 12h16M4 18h16" /></svg></button><div><h1 id="library-title">我的音乐</h1><p>把偶然遇见的声音，留给下一次探索。</p></div><button type="button" onClick={onBack}>返回对话</button></header>
    <div className="library-body">
      {guest && <p className="library-guest-note">歌曲随当前访客身份保存。登录后可同步到账户；清除浏览器身份后可能无法找回。</p>}
      <label className="library-search">搜索保存的歌曲<input type="search" value={input} maxLength={120} placeholder="搜索歌名或艺人" onChange={event=>setInput(event.target.value)} /></label>
      {active && song && <VinylPlayer title={song.title} artist={song.artistName} coverUrl={song.coverUrl} state={active} analyser={library.listening.analyser} onToggle={()=>void library.listening.toggle(active.id)} onSeek={library.listening.seek} attribution={preview?.attribution} trackUrl={preview?.providerTrackUrl} />}
      {library.error && <p role="alert" className="library-error">{library.error}</p>}
      {talkError && <p role="alert" className="library-error">{talkError}</p>}
      {loading ? <p className="library-empty" role="status">正在翻开你的音乐收藏…</p> : !items.length && !error ? <div className="library-empty"><h2>{search ? '没有找到匹配的歌曲' : '从一首想留下的歌开始'}</h2><p>{search ? '试试其他歌名或艺人。' : '在推荐卡上点击“保存”，歌曲就会出现在这里。'}</p><button type="button" onClick={search ? ()=>setInput('') : onBack}>{search ? '清除搜索' : '去探索音乐'}</button></div> : null}
      <ol className="saved-song-list">{items.map(item=>{
        const playback=library.listening.stateFor(item.recordingId)
        return <li key={item.recordingId} className={active?.id===item.recordingId ? 'is-playing':''}>
          <button type="button" className="saved-song-play" aria-label={`试听《${item.title}》`} onClick={()=>library.play(item)}><Cover song={item} /><span><strong>{item.title}</strong><small>{item.artistName}{item.albumTitle ? ` · ${item.albumTitle}`:''}{item.versionLabel ? ` · ${item.versionLabel}`:''}</small><em>{playbackLabel(playback.phase)}</em></span></button>
          {item.reason && <p className="saved-song-reason">{item.reason}</p>}
          <div className="saved-song-actions"><button type="button" disabled={talking!==null} onClick={()=>void talk(item)}>{talking===item.recordingId ? '正在准备…':'聊聊这首歌'}</button>{item.sourceAvailable ? <a href={`/explore?conversation=${encodeURIComponent(item.sourceConversationId!)}#message-${item.sourceMessageId}`} target="_blank" rel="noopener noreferrer" onClick={event=>{if(item.sourceConversationId===conversationId){event.preventDefault();onSource(item)}}}>来源对话</a>:<span>来源对话已不可用</span>}<a href={`https://music.163.com/#/search/m/?s=${encodeURIComponent(item.artistName+' '+item.title)}&type=1`} target="_blank" rel="noreferrer">去网易云搜索</a><button type="button" disabled={library.busy[item.recordingId]} onClick={()=>void library.toggle(item.recordingId,undefined,true)} aria-label={`移除《${item.title}》`}>{library.busy[item.recordingId] ? '正在移除…':'移除'}</button></div>
          <small className="saved-song-date">保存于 {new Date(item.savedAt).toLocaleDateString('zh-CN')}</small>
        </li>
      })}</ol>
      {error && <div className="library-error" role="alert"><p>{error}</p><button type="button" onClick={()=>cursor&&items.length ? void loadMore() : setAttempt(value=>value+1)}>重新读取</button></div>}
      {cursor && !loading && !error && <button type="button" className="library-load-more" disabled={more} onClick={()=>void loadMore()}>{more?'正在读取…':'加载更多'}</button>}
    </div>
  </section>
}
function Cover({song}:{song:SavedSong}) {const [failed,setFailed]=useState(false);return song.coverUrl&&!failed ? <img src={song.coverUrl} alt="" loading="lazy" onError={()=>setFailed(true)} />:<span className="saved-song-cover" aria-hidden="true">{song.title.slice(0,2)}</span>}
