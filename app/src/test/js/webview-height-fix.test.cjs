const {test}=require('node:test');
const assert=require('node:assert/strict');
const fs=require('node:fs');
const path=require('node:path');
const vm=require('node:vm');
const file=path.resolve(__dirname,'../../main/assets/webview-height-fix.js');

// Geometry is an input double here: real CSS rendering is checked on the user's phone.
function fixture({nativeReady=true,collapsed=true,rootCanGrow=true,origin='https://chat.deepseek.com',route='/sign_in'}={}) {
  const writes=[];
  function node(tag,parent=null,initialHeight=0) {
    const values=new Map(), priorities=new Map();
    const n={tagName:tag.toUpperCase(),parentElement:parent,hidden:false,children:[],
      style:{getPropertyValue:k=>values.get(k)||'',getPropertyPriority:k=>priorities.get(k)||'',
        setProperty(k,v,p){writes.push({node:n,key:k,value:v});values.set(k,v);priorities.set(k,p||'');},
        removeProperty(k){values.delete(k);priorities.delete(k);}},
      getAttribute(k){return k==='type' && tag==='input'?'password':null;},
      contains(other){for(let e=other;e;e=e.parentElement)if(e===n)return true;return false;},
      getBoundingClientRect(){let h=initialHeight;
        if(rootCanGrow && values.get('height')==='100%' && (!parent || parent.getBoundingClientRect().height>0))h=parent?parent.getBoundingClientRect().height:600;
        return {left:0,top:0,right:360,bottom:h,width:360,height:h};},
    };
    if(parent)parent.children.push(n);return n;
  }
  const html=node('html',null,collapsed?0:600),body=node('body',html,collapsed?0:600),root=node('div',body,collapsed?0:600),input=node('input',root,40);
  const document={documentElement:html,body,readyState:'complete',querySelectorAll:()=>[input]};
  const window={location:{origin,pathname:route},innerHeight:600,innerWidth:360};window.top=window;window.window=window;
  const ctx=vm.createContext({window,document,JSON,WeakSet,getComputedStyle:e=>({display:'block',visibility:'visible',opacity:'1',overflowY:'hidden'})});
  vm.runInContext(fs.existsSync(file)?fs.readFileSync(file,'utf8'):'',ctx);
  assert.equal(typeof window.__snowseekRepairHeight,'function','Height correction entry point must exist');
  const native={ready:nativeReady,webWidth:1080,webHeight:1800,hostWidth:1080,hostHeight:1800};
  return {run:()=>window.__snowseekRepairHeight(native),html,body,root,input,writes,window,native,document};
}

test('positive native viewport with collapsed page repairs only html/body/app-root height',()=>{
  const f=fixture();const result=f.run();
  assert.equal(result.status,'applied');
  assert.equal(result.before.html.height,0);assert.equal(result.after.html.height,600);
  assert.ok(result.after.body.height>0);assert.ok(result.after.root.height>0);
  assert.equal(f.writes.length,6);
  assert.ok(f.writes.every(w=>['height','min-height'].includes(w.key) && [f.html,f.body,f.root].includes(w.node)));
  assert.equal(f.input.style.getPropertyValue('height'),'');
  const count=f.writes.length;assert.notEqual(f.run().status,'applied');assert.equal(f.writes.length,count);
});

test('zero native or JavaScript viewport never receives a CSS workaround',()=>{
  const f=fixture({nativeReady:false});assert.equal(f.run().status,'native-not-ready');assert.equal(f.writes.length,0);
  const g=fixture();g.window.innerHeight=0;assert.equal(g.run().status,'viewport-not-ready');assert.equal(g.writes.length,0);
});

test('already sized documents and unrelated routes are unchanged',()=>{
  const f=fixture({collapsed:false});assert.equal(f.run().status,'not-collapsed');assert.equal(f.writes.length,0);
  const g=fixture({route:'/a/chat-id'});assert.equal(g.run().status,'not-login-route');assert.equal(g.writes.length,0);
  const h=fixture({origin:'https://external.example'});assert.equal(h.run().status,'wrong-origin');assert.equal(h.writes.length,0);
});

test('unsuccessful correction restores original declarations and stops retrying the same root',()=>{
  const f=fixture({rootCanGrow:false});f.html.style.setProperty('height','0px','important');f.writes.length=0;
  assert.equal(f.run().status,'rolled-back');
  assert.equal(f.html.style.getPropertyValue('height'),'0px');assert.equal(f.html.style.getPropertyPriority('height'),'important');
  assert.equal(f.body.style.getPropertyValue('height'),'');assert.equal(f.root.style.getPropertyValue('min-height'),'');
  const count=f.writes.length;assert.equal(f.run().status,'already-attempted');assert.equal(f.writes.length,count);
});

test('successful login correction is removed on SPA navigation and preserves later site changes',()=>{
  const f=fixture();assert.equal(f.run().status,'applied');
  f.root.style.setProperty('height','auto','');
  f.window.location.pathname='/a/chat-id';
  assert.equal(f.run().status,'restored-after-login');
  assert.equal(f.html.style.getPropertyValue('height'),'');assert.equal(f.body.style.getPropertyValue('min-height'),'');
  assert.equal(f.root.style.getPropertyValue('height'),'auto');
  assert.equal(f.root.style.getPropertyValue('min-height'),'');
});

test('successful correction is removed when login controls disappear without a route change',()=>{
  const f=fixture();assert.equal(f.run().status,'applied');
  f.document.querySelectorAll=()=>[];
  assert.equal(f.run().status,'restored-after-login');
  assert.equal(f.html.style.getPropertyValue('height'),'');
});
