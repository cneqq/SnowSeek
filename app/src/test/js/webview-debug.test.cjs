const {test} = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');
const file = path.resolve(__dirname, '../../main/assets/webview-debug.js');

// Unit fixtures provide measured layout/style inputs; they do not simulate browser rendering.
function fixture() {
  let serial = 0;
  const clock = {now:10000};
  class ClockDate extends Date {static now(){return clock.now;}}
  const all = [], listeners = {}, timers = [];
  function element(tag, parent = null, attrs = {}, style = {}, rect = {}) {
    const node = {
      tagName: tag.toUpperCase(), parentElement: parent, children: [],
      textContent: '', isConnected: true, hidden: false,
      offsetWidth: 300, offsetHeight: 50, clientWidth: 300, clientHeight: 50,
      scrollWidth: 300, scrollHeight: 50,
      _serial: ++serial, _style: {display:'block',visibility:'visible',opacity:'1',position:'static',
        pointerEvents:'auto',zIndex:'auto',overflowX:'visible',overflowY:'visible',
        contentVisibility:'visible',transform:'none',filter:'none',clipPath:'none',
        backgroundColor:'rgba(0, 0, 0, 0)',color:'rgb(255, 255, 255)',...style},
      getBoundingClientRect() {return {x:20,y:20,left:20,top:20,right:320,bottom:70,width:300,height:50,...rect};},
      getAttribute(name) {return attrs[name] ?? null;},
      contains(other) {for(let n=other;n;n=n.parentElement) if(n===this)return true;return false;},
      querySelectorAll() {return all.filter(n=>n!==this && this.contains(n) && n.isConnected);},
    };
    if(parent)parent.children.push(node);
    all.push(node); return node;
  }
  const html = element('html', null, {}, {}, {left:0,top:0,right:400,bottom:800,width:400,height:800});
  const body = element('body', html, {}, {}, {left:0,top:0,right:400,bottom:800,width:400,height:800});
  const root = element('div', body, {id:'root'});
  const form = element('form', root);
  const input = element('input', form, {type:'password',value:'PRIVATE-PASSWORD'});
  const cookie = element('div', body, {role:'dialog'});
  cookie.textContent='Настройки Cookie Только необходимые файлы cookie';
  const button = element('button', cookie);
  button.textContent='Принять все файлы cookie';
  const document = {
    documentElement:html, body, readyState:'complete',
    querySelectorAll:()=>all.filter(n=>n.isConnected),
    getElementById:id=>all.find(n=>n.getAttribute('id')===id) || null,
    elementsFromPoint:()=>[input,form,root,body,html],
    addEventListener(name, handler) {(listeners['document:'+name] ||= []).push(handler);},
  };
  const window = {
    innerWidth:400,innerHeight:800,scrollX:0,scrollY:0,
    location:{origin:'https://chat.deepseek.com',href:'https://chat.deepseek.com/sign_in'},
    addEventListener(name, handler) {(listeners[name] ||= []).push(handler);},
    getComputedStyle:n=>n._style,
    setTimeout:fn=>{timers.push(fn);return timers.length;},
  };
  window.window=window;window.top=window;window.document=document;
  const context=vm.createContext({...window,window,document,console,URL,WeakMap,WeakSet,Date:ClockDate,
    getComputedStyle:window.getComputedStyle,setTimeout:window.setTimeout});
  const source=fs.existsSync(file)?fs.readFileSync(file,'utf8'):'';
  vm.runInContext(source,context);
  const api=window.__snowseekDebugV2;
  assert.ok(api,'Document-start diagnostic hook must be installed');
  const fire=(name,event)=>{for(const handler of listeners[name]||[])handler(event);};
  return {api,window,document,all,html,body,root,form,input,cookie,button,element,fire,timers,reinject:()=>vm.runInContext(source,context),advance:ms=>{clock.now+=ms;}};
}

test('uncaught exceptions and promise rejections keep full stack and sanitize before queueing',()=>{
  const f=fixture();f.api.drain();
  const stack='Error: TIP Error\n'+Array.from({length:250},(_,i)=>`at frame${i} (https://chat.deepseek.com/static/fp.min.js:${i}:9)`).join('\n');
  f.fire('error',{message:'TIP Error token=private-token',error:{name:'Error',message:'TIP Error',stack},
    filename:'https://chat.deepseek.com/static/fp.min.js?code=private-code',lineno:9,colno:11});
  f.fire('unhandledrejection',{reason:{name:'TypeError',message:'password=private-pass',stack:'TypeError at promise LAST_PROMISE_FRAME'}});
  const events=f.api.drain(), text=JSON.stringify(events);
  assert.match(text,/frame249/);assert.match(text,/LAST_PROMISE_FRAME/);assert.match(text,/UNHANDLED_REJECTION/);
  assert.doesNotMatch(text,/private-token|private-code|private-pass|PRIVATE-PASSWORD/);
});

test('geometry includes ancestor opacity, clipping, styles and hidden form despite positive size',()=>{
  const f=fixture();f.api.drain();f.root._style.opacity='0';f.root._style.overflowY='hidden';
  const s=f.api.snapshot('manual');
  assert.equal(s.forms.actualForms,1);
  const password=s.targets.find(t=>t.type==='password');
  assert.ok(password.rect.width>0);assert.equal(password.effectiveOpacity,0);assert.equal(password.rendered,false);
  assert.ok(password.ancestors.some(a=>a.styles.opacity==='0' && a.styles.overflowY==='hidden'));
  assert.ok(s.containers.length>=3);assert.ok(Number.isInteger(s.counts.rendered));
  assert.doesNotMatch(JSON.stringify(s),/PRIVATE-PASSWORD|Настройки|Принять/);
});

test('transparent hit-test overlay is recorded as obstruction without changing it',()=>{
  const f=fixture();f.api.drain();
  const overlay=f.element('div',f.body,{}, {opacity:'0',position:'fixed',zIndex:'999',pointerEvents:'auto'},
    {left:0,top:0,right:400,bottom:800,width:400,height:800});
  f.document.elementsFromPoint=()=>[overlay,f.input,f.form,f.root,f.body,f.html];
  const s=f.api.snapshot('manual'), password=s.targets.find(t=>t.type==='password');
  assert.ok(password.hitTests.some(p=>p.obscured));
  assert.ok(s.overlays.some(o=>o.styles.opacity==='0' && o.styles.pointerEvents==='auto'));
  assert.equal(overlay._style.opacity,'0');assert.equal(overlay.isConnected,true);
});

test('cookie click captures before and disappearance records after with comparison',()=>{
  const f=fixture();f.api.drain();f.api.sample('initial');
  f.fire('document:click',{target:f.button});
  f.cookie.isConnected=false;f.button.isConnected=false;f.root._style.opacity='0';
  f.api.sample('poll');
  const events=f.api.drain(), text=JSON.stringify(events);
  assert.match(text,/COOKIE_BEFORE_CLICK/);assert.match(text,/COOKIE_AFTER_DISAPPEAR/);assert.match(text,/COOKIE_DOM_DIFF/);
  const diff=events.find(e=>e.kind==='COOKIE_DOM_DIFF');
  assert.equal(diff.data.before.consent.visible,true);assert.equal(diff.data.after.consent.visible,false);
  assert.ok(diff.data.changes.length>0);
});

test('resource errors have no invented HTTP status and bootstrap is idempotent',()=>{
  const f=fixture();f.api.drain();
  f.reinject();
  const script=f.element('script',f.body);script.src='https://chat.deepseek.com/static/fp.min.js?token=private-token';
  f.fire('error',{target:script});
  const resourceEvents=f.api.drain().filter(e=>e.kind==='RESOURCE_ERROR');
  assert.equal(resourceEvents.length,1);
  const event=resourceEvents[0];
  assert.match(event.text,/HTTP \?/);assert.doesNotMatch(event.text,/200|private-token/);
  assert.equal(f.api.snapshot('manual').scope,'top-document; cross-origin frames not inspected');
});

test('consent dialog is recognized when its accept button does not repeat the word cookie',()=>{
  const f=fixture();f.button.textContent='Принять всё';
  assert.equal(f.api.snapshot('manual').consent.visible,true);
});

test('opaque promise objects do not expose payload, form values or response bodies',()=>{
  const f=fixture();f.api.drain();
  f.fire('unhandledrejection',{reason:{payload:'PRIVATE-BODY',token:'PRIVATE-TOKEN',toJSON(){throw new Error('must not stringify');}}});
  const events=f.api.drain(),text=JSON.stringify(events);
  assert.match(text,/object reason omitted/);assert.doesNotMatch(text,/PRIVATE-BODY|PRIVATE-TOKEN|PRIVATE-PASSWORD/);
});

test('throttling defers a stable DOM change instead of permanently losing it',()=>{
  const f=fixture();f.api.sample('initial');f.api.drain();
  f.advance(2000);f.root._style.opacity='0';f.api.sample('poll');
  assert.equal(f.api.drain().length,0);
  f.advance(6000);f.api.sample('poll');
  const event=f.api.drain().find(e=>e.kind==='DOM_SNAPSHOT');
  assert.ok(event,'The changed DOM must be recorded once the throttle expires');
  assert.equal(event.data.targets.find(t=>t.type==='password').effectiveOpacity,0);
});
