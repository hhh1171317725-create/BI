const {test}=require('node:test');
const assert=require('node:assert/strict');
const fs=require('node:fs'),path=require('node:path'),vm=require('node:vm');
const source=fs.readFileSync(path.join(__dirname,'../frontend/bid-monitor-filter-input.js'),'utf8');

function harness(){
  let now=0,nextId=0;
  const active=new Map(),callbacks=new Map(),states=[];
  const context=vm.createContext({
    setTimeout(callback,delay){const id=++nextId;active.set(id,{callback,at:now+delay});callbacks.set(id,callback);return id;},
    clearTimeout(id){active.delete(id);}
  });
  vm.runInContext(source,context);
  const applied=[];
  const scheduler=context.BIBidFilterInput.create({apply:()=>applied.push(now),onPending:state=>states.push(state)});
  function tick(duration){
    const target=now+duration;
    for(;;){
      const due=[...active].filter(([,timer])=>timer.at<=target).sort((a,b)=>a[1].at-b[1].at)[0];
      if(!due)break;
      now=due[1].at;active.delete(due[0]);due[1].callback();
    }
    now=target;
  }
  return{scheduler,tick,applied,states,callbacks,get lastTimer(){return nextId;}};
}

test('module exports the same factory for Node and the browser',()=>{
  const api=require('../frontend/bid-monitor-filter-input.js');
  assert.equal(typeof api.create,'function');
  const context=vm.createContext({});vm.runInContext(source,context);
  assert.equal(typeof context.BIBidFilterInput.create,'function');
});

test('a burst applies once 140 ms after its last input and reports pending transitions once',()=>{
  const {scheduler,tick,applied,states}=harness();
  assert.equal(scheduler.pending,false);
  scheduler.schedule();tick(80);scheduler.schedule();tick(80);scheduler.schedule();
  assert.equal(scheduler.pending,true);assert.deepEqual(states,[true]);
  tick(139);assert.deepEqual(applied,[]);
  tick(1);assert.deepEqual(applied,[300]);assert.equal(scheduler.pending,false);
  assert.deepEqual(states,[true,false]);
  tick(1000);assert.equal(applied.length,1);
});

test('flush applies exactly once and does nothing when no input is pending',()=>{
  const {scheduler,tick,applied,states}=harness();
  scheduler.flush();assert.deepEqual(applied,[]);
  scheduler.schedule();tick(20);scheduler.flush();scheduler.flush();
  assert.deepEqual(applied,[20]);assert.equal(scheduler.pending,false);
  assert.deepEqual(states,[true,false]);
  tick(1000);assert.equal(applied.length,1);
});

test('cancel removes queued work and leaves no pending notification when already idle',()=>{
  const {scheduler,tick,applied,states}=harness();
  scheduler.cancel();assert.deepEqual(states,[]);
  scheduler.schedule();tick(20);scheduler.cancel();scheduler.cancel();
  assert.equal(scheduler.pending,false);assert.deepEqual(states,[true,false]);
  tick(1000);assert.deepEqual(applied,[]);
});

test('stale callbacks cannot apply, clear, or complete a newer pending input',()=>{
  const h=harness(),{scheduler,tick,applied,states,callbacks}=h;
  scheduler.schedule();const restarted=h.lastTimer;
  tick(20);scheduler.schedule();callbacks.get(restarted)();
  assert.deepEqual(applied,[]);assert.equal(scheduler.pending,true);
  const cancelled=h.lastTimer;scheduler.cancel();scheduler.schedule();callbacks.get(cancelled)();
  assert.deepEqual(applied,[]);assert.equal(scheduler.pending,true);
  const flushed=h.lastTimer;scheduler.flush();scheduler.schedule();callbacks.get(flushed)();
  assert.deepEqual(applied,[20]);assert.equal(scheduler.pending,true);
  tick(140);assert.deepEqual(applied,[20,160]);assert.equal(scheduler.pending,false);
  assert.deepEqual(states,[true,false,true,false,true,false]);
});

test('configured delay is honored and apply may schedule a later input',()=>{
  const h=harness();let calls=0,scheduler;
  const context=vm.createContext({
    setTimeout(callback,delay){assert.equal(delay,25);h.callbacks.set('custom',callback);return 0;},
    clearTimeout(){}
  });
  vm.runInContext(source,context);
  scheduler=context.BIBidFilterInput.create({delay:25,apply(){calls++;if(calls===1)scheduler.schedule();}});
  scheduler.schedule();assert.equal(scheduler.pending,true);
  h.callbacks.get('custom')();assert.equal(calls,1);assert.equal(scheduler.pending,true);
  h.callbacks.get('custom')();assert.equal(calls,2);assert.equal(scheduler.pending,false);
});
