(() => {
  'use strict';
  const prefix='/api/games/gomoku/rooms';
  function newClientId() {
    if(typeof crypto.randomUUID==='function')return crypto.randomUUID();
    const bytes=crypto.getRandomValues(new Uint8Array(16));bytes[6]=(bytes[6]&15)|64;bytes[8]=(bytes[8]&63)|128;
    const hex=Array.from(bytes,value=>value.toString(16).padStart(2,'0')).join('');
    return `${hex.slice(0,8)}-${hex.slice(8,12)}-${hex.slice(12,16)}-${hex.slice(16,20)}-${hex.slice(20)}`;
  }
  class OnlineGame {
    constructor(callbacks) {
      this.callbacks=callbacks;this.room=null;this.busy=false;this.active=false;this.connection='paused';
      this.epoch=0;this.pollController=null;this.retryTimer=null;this.retry=0;this.storageKey=null;this.clientId=null;
      this.ready=this.initialize();
      window.addEventListener('offline',()=>{if(this.active&&this.room){this.stopPolling();this.setConnection('reconnecting');}});
      window.addEventListener('online',()=>{if(this.active&&this.room)this.resume();});
    }
    async initialize() {
      const user=await window.gameSessionReady;
      if(!user)throw new Error('请先登录网站');
      this.storageKey=`bi-gomoku-online-v1:${user.id??user.username}`;
      let saved;try{saved=JSON.parse(sessionStorage.getItem(this.storageKey)||'null');}catch{}
      this.clientId=/^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i.test(saved?.clientId||'')?saved.clientId:newClientId();
      this.savedCode=/^[A-Z0-9]{6}$/.test(saved?.code||'')?saved.code:null;
      this.save();return this.savedCode;
    }
    save() {try{sessionStorage.setItem(this.storageKey,JSON.stringify({clientId:this.clientId,code:this.room?.code||this.savedCode||null}));}catch{}}
    setConnection(value) {this.connection=value;this.callbacks.onConnection?.(value);}
    setBusy(value) {this.busy=value;this.callbacks.onBusy?.(value);}
    apply(snapshot) {
      if(!snapshot||!Number.isSafeInteger(snapshot.version)||![1,2].includes(snapshot.seat)||!Array.isArray(snapshot.game?.board)||snapshot.game.board.length!==15||!Array.isArray(snapshot.game.moves))throw new Error('房间数据异常，请重新连接');
      if(this.room?.code===snapshot.code&&snapshot.version<this.room.version)return;
      this.room=snapshot;this.savedCode=snapshot.code;this.save();this.callbacks.onUpdate?.(snapshot);
    }
    async request(path,method='GET',body,signal) {
      const controller=new AbortController(),timeout=setTimeout(()=>controller.abort(),method==='GET'?27000:10000);
      const abort=()=>controller.abort();signal?.addEventListener('abort',abort,{once:true});if(signal?.aborted)abort();
      try{
        const response=await fetch(prefix+path,{method,cache:'no-store',headers:{'X-Game-Client':this.clientId,...(body!==undefined?{'Content-Type':'application/json'}:{})},...(body!==undefined?{body:JSON.stringify(body)}:{}),signal:controller.signal});
        const result=await response.json();
        if(!response.ok){const error=new Error(result.error||'房间请求失败');error.status=response.status;if(response.status===401)location.replace('/login');throw error;}
        return result;
      }finally{clearTimeout(timeout);signal?.removeEventListener('abort',abort);}
    }
    stopPolling() {this.epoch++;this.pollController?.abort();this.pollController=null;clearTimeout(this.retryTimer);this.retryTimer=null;}
    pause() {this.active=false;this.stopPolling();this.setConnection('paused');}
    async connect(kind,code) {
      if(this.busy)return;this.stopPolling();this.active=true;this.setConnection('connecting');this.setBusy(true);
      try{
        await this.ready;
        const result=kind==='create'?await this.request('','POST',{}):await this.request(`/${encodeURIComponent(code)}${kind==='join'?'/join':''}`,kind==='join'?'POST':'GET',kind==='join'?{}:undefined);
        this.apply(result);this.retry=0;this.setConnection(this.active?'connected':'paused');this.callbacks.onError?.('');
      }catch(error){
        if(kind==='resume'&&[403,404].includes(error.status)){this.room=null;this.savedCode=null;this.save();this.callbacks.onUpdate?.(null);}
        this.setConnection(this.active&&this.room?'reconnecting':'paused');this.callbacks.onError?.(error.message);
      }finally{this.setBusy(false);if(this.active&&this.room&&this.room.phase!=='closed')this.poll(this.epoch,false);}
    }
    async resume() {
      if(!this.room)return;
      this.active=true;this.stopPolling();this.setConnection('connecting');this.retry=0;
      this.poll(this.epoch,false);
    }
    async poll(token,wait) {
      if(!this.active||token!==this.epoch||!this.room||this.room.phase==='closed')return;
      const controller=new AbortController();this.pollController=controller;
      try{
        const room=await this.request(`/${this.room.code}?since=${this.room.version}&wait=${wait}`, 'GET',undefined,controller.signal);
        if(token!==this.epoch||!this.active)return;
        this.apply(room);this.retry=0;this.setConnection('connected');
        if(room.phase!=='closed')this.poll(token,true);
      }catch(error){
        if(token!==this.epoch||!this.active)return;
        if([403,404].includes(error.status)){this.room=null;this.savedCode=null;this.save();this.callbacks.onUpdate?.(null);this.setConnection('paused');this.callbacks.onError?.(error.message);return;}
        this.setConnection('reconnecting');
        if(error.status)this.callbacks.onError?.(error.message);
        const delay=Math.min(8000,1000*2**this.retry++);
        this.retryTimer=setTimeout(()=>this.poll(token,false),delay);
      }
    }
    async action(path,payload={}) {
      if(this.busy||!this.room||this.connection!=='connected')return;
      const code=this.room.code,token=this.epoch;this.setBusy(true);
      try{
        const result=await this.request(`/${code}/${path}`,'POST',{...payload,version:this.room.version});
        if(token===this.epoch){this.apply(result);this.callbacks.onError?.('');}
      }catch(error){
        this.callbacks.onError?.(error.message);
        if(!error.status)this.setConnection('reconnecting');
        if(token===this.epoch&&this.active)this.resume();
      }finally{this.setBusy(false);}
    }
    async leave() {
      if(this.busy||!this.room)return;
      const code=this.room.code;this.setBusy(true);
      try{
        await this.request(`/${code}/leave`,'POST',{});this.stopPolling();this.room=null;this.savedCode=null;this.save();this.setConnection('paused');this.callbacks.onUpdate?.(null);this.callbacks.onError?.('');
        history.replaceState(null,'',location.pathname+'#gomoku');
      }catch(error){this.callbacks.onError?.(error.message);}finally{this.setBusy(false);}
    }
    inviteLink() {const url=new URL('/games.html',location.origin);url.searchParams.set('room',this.room.code);url.hash='gomoku';return url.href;}
  }
  window.BIGomokuOnline=OnlineGame;
})();
