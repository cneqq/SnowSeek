/* Debug-only observation. No field values, HTML dumps, storage, network hooks or DOM mutations. */
(function () {
  'use strict';
  if (window !== window.top || window.location.origin !== 'https://chat.deepseek.com' || window.__snowseekDebugV2) return;
  var events = [], eventBytes = 0, dropped = 0, ids = new WeakMap(), nextId = 0;
  var lastWithCookie = null, lastFingerprint = '', lastAuto = 0, styleCache = null, rectCache = null;
  var MAX_TEXT = 65536, MAX_NODES = 5000;
  var sensitive = /(["']?(?:password|passwd|access[_-]?token|refresh[_-]?token|userToken|token|secret|nonce|session[_-]?id|email|phone|prompt|authorization|cookie|set-cookie|otp|credential)["']?\s*[:=]\s*)("[^"\r\n]*"|'[^'\r\n]*'|[^\s,;}]+)/gi;
  function safeUrl(raw) {
    if (!raw) return '—';
    try {
      var u = new URL(String(raw), window.location.href);
      if (u.protocol !== 'https:' && u.protocol !== 'http:') return '[non-HTTP source]';
      return u.origin + u.pathname.replace(/[A-Za-z0-9_+/-]{48,}/g, '[redacted]') +
        (u.search ? '?[redacted]' : '') + (u.hash ? '#[redacted]' : '');
    } catch (_) { return '[redacted URL]'; }
  }
  function clean(value) {
    if (typeof value !== 'string') return '[non-string detail omitted]';
    var text = value.slice(0, MAX_TEXT * 3)
      .replace(/https?:\/\/[^\s<>"'`]+/gi, safeUrl)
      .replace(/data:[^\s<>"'`]+/gi, 'data:[redacted]')
      .replace(/\bBearer\s+[^\s"',;]+/gi, 'Bearer [redacted]')
      .replace(/\beyJ[A-Za-z0-9_-]+\.[A-Za-z0-9_-]+(?:\.[A-Za-z0-9_-]+)?/g, '[redacted]')
      .replace(/\b(?:set-cookie|cookie|authorization)\s*[:=]\s*[^\r\n]+/gi, '[redacted header]')
      .replace(sensitive, '$1[redacted]')
      .replace(/[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\.[A-Za-z]{2,}/g, '[redacted]')
      .replace(/[A-Za-z0-9_+/-]{48,}={0,2}/g, '[redacted]');
    return text.slice(0, MAX_TEXT) + (text.length > MAX_TEXT || value.length > MAX_TEXT * 3 ? ' [truncated at 65536 characters]' : '');
  }
  function emit(kind, text, data) {
    var event = {kind:kind, text:clean(text || ''), at:Date.now()};
    if (data) event.data = data;
    var size = JSON.stringify(event).length;
    if (size > 220000) { event = {kind:'DIAGNOSTIC_LIMIT',text:kind + ' exceeded 220000 characters',at:Date.now()}; size = JSON.stringify(event).length; }
    events.push(event); eventBytes += size;
    while (events.length > 64 || eventBytes > 750000) {eventBytes -= JSON.stringify(events.shift()).length; dropped++;}
  }
  function errorDetail(reason) {
    if (typeof reason === 'string') return clean(reason);
    if (reason == null || typeof reason === 'number' || typeof reason === 'boolean') return String(reason);
    // Never stringify arbitrary rejection objects: they may contain credentials or response bodies.
    var parts = [];
    ['name','message','stack'].forEach(function (key) {
      try { if (typeof reason[key] === 'string') parts.push(key + ': ' + clean(reason[key])); } catch (_) {}
    });
    return parts.length ? parts.join('\n') : '[object reason omitted; no string name/message/stack]';
  }
  window.addEventListener('error', function (e) {
    if (e.target && e.target !== window && !e.message) {
      emit('RESOURCE_ERROR', 'DOM resource error ' + String(e.target.tagName || 'unknown') + ' ' +
        safeUrl(e.target.src || e.target.href) + ' · HTTP ? (not exposed)');
    } else {
      emit('WINDOW_ERROR', clean(e.message || '') + '\n' + errorDetail(e.error) + '\nsource: ' +
        safeUrl(e.filename) + ' · line ' + Number(e.lineno || 0) + ' column ' + Number(e.colno || 0));
    }
  }, true);
  window.addEventListener('unhandledrejection', function (e) { emit('UNHANDLED_REJECTION', errorDetail(e.reason)); });
  window.addEventListener('rejectionhandled', function () { emit('REJECTION_HANDLED', 'Previously unhandled promise now has a handler'); });
  // Do not preventDefault, replace console, patch fetch/XHR, or change site error handling.
  function ref(e) {
    if (!ids.has(e)) ids.set(e, ++nextId);
    return String(e.tagName || 'node').toLowerCase() + '@' + ids.get(e);
  }
  function round(v) { return Math.round(Number(v || 0) * 10) / 10; }
  function rect(e) {
    if (rectCache && rectCache.has(e)) return rectCache.get(e);
    var r = e.getBoundingClientRect();
    var result = {x:round(r.left),y:round(r.top),width:round(r.width),height:round(r.height),right:round(r.right),bottom:round(r.bottom)};
    if (rectCache) rectCache.set(e,result);
    return result;
  }
  function styles(e) {
    if (styleCache && styleCache.has(e)) return styleCache.get(e);
    var s = getComputedStyle(e), result = {};
    ['display','visibility','opacity','position','zIndex','pointerEvents','overflowX','overflowY',
      'contentVisibility','transform','filter','clipPath','backgroundColor','color'].forEach(function (k) { result[k] = String(s[k] || '').slice(0, 160); });
    if (styleCache) styleCache.set(e,result);
    return result;
  }
  function measure(e, ancestors) {
    var r = rect(e), s = styles(e), effectiveOpacity = 1, suppressed = false, clips = [], chain = [];
    var clipped = {left:Math.max(0,r.x),top:Math.max(0,r.y),right:Math.min(window.innerWidth,r.right),bottom:Math.min(window.innerHeight,r.bottom)};
    var depth = 0;
    for (var n = e; n; n = n.parentElement) {
      var ns = n === e ? s : styles(n), nr = n === e ? r : rect(n);
      var opacity = parseFloat(ns.opacity); effectiveOpacity *= isNaN(opacity) ? 1 : opacity;
      if (ns.display === 'none' || ns.visibility === 'hidden' || ns.visibility === 'collapse' || ns.contentVisibility === 'hidden' || n.hidden) suppressed = true;
      if (n !== e) {
        if (/hidden|clip|scroll|auto/.test(ns.overflowX)) {clipped.left=Math.max(clipped.left,nr.x);clipped.right=Math.min(clipped.right,nr.right);clips.push(ref(n)+':x');}
        if (/hidden|clip|scroll|auto/.test(ns.overflowY)) {clipped.top=Math.max(clipped.top,nr.y);clipped.bottom=Math.min(clipped.bottom,nr.bottom);clips.push(ref(n)+':y');}
        if (ancestors && depth < 10) chain.push({ref:ref(n),rect:nr,styles:ns});
      }
      if (++depth >= 64) break;
    }
    var inViewport = clipped.right > clipped.left && clipped.bottom > clipped.top;
    return {ref:ref(e),rect:r,styles:s,effectiveOpacity:round(effectiveOpacity),suppressed:suppressed,
      rendered:r.width>0 && r.height>0 && !suppressed && effectiveOpacity>0.01,
      inViewport:inViewport,clippedBy:clips,ancestors:chain,ancestorScanLimited:depth>=64,
      client:{width:e.clientWidth,height:e.clientHeight},scroll:{width:e.scrollWidth,height:e.scrollHeight}};
  }
  function isInput(e) {
    if (e.tagName === 'TEXTAREA' || e.getAttribute('contenteditable') === 'true') return true;
    return e.tagName === 'INPUT' && !/^(hidden|checkbox|radio|submit|button|reset|range|color|file)$/i.test(e.getAttribute('type') || 'text');
  }
  function inputType(e) {
    var type = (e.getAttribute('type') || 'text').toLowerCase();
    return /^(password|email|tel|text|number|search|url)$/.test(type) ? type : e.tagName.toLowerCase();
  }
  function cookieNodes(nodes) {
    // Inspect consent labels locally only to identify a banner; never return their text/attributes.
    var result = [], cookieWord = /cookie|куки|файлы\s+cookie/i;
    nodes.forEach(function (e) {
      if (e.tagName !== 'BUTTON' && e.getAttribute('role') !== 'button') return;
      if (!/cookie|куки|accept|necessary|reject|принять|необходим|отклон/i.test(String(e.textContent || '').slice(0, 300))) return;
      for (var n=e.parentElement,depth=0;n && n!==document.body && depth<5;n=n.parentElement,depth++) {
        if (cookieWord.test(String(n.textContent || '').slice(0, 8000))) {
          var m=measure(n,false); if (m.rendered && m.inViewport && result.indexOf(n)<0) {result.push(n);break;}
        }
      }
    });
    return result;
  }
  function hitTests(e) {
    var r=e.getBoundingClientRect(), l=Math.max(0,r.left),t=Math.max(0,r.top),rr=Math.min(window.innerWidth,r.right),b=Math.min(window.innerHeight,r.bottom);
    if (rr<=l || b<=t) return [];
    return [[0.5,0.5],[0.15,0.15],[0.85,0.15],[0.15,0.85],[0.85,0.85]].map(function (point) {
      var x=l+(rr-l)*point[0],y=t+(b-t)*point[1];
      var stack=document.elementsFromPoint ? document.elementsFromPoint(x,y) : [];
      var top=stack[0], obscured=!!top && top!==e && !e.contains(top) && !top.contains(e);
      return {x:round(x),y:round(y),obscured:obscured,top:top?ref(top):null,
        stack:stack.slice(0,6).map(function (n) {return {ref:ref(n),styles:styles(n)};})};
    });
  }
  function snapshot(reason) {
    styleCache=new WeakMap();rectCache=new WeakMap();
    try {
    var found=document.querySelectorAll('*'), nodes=Array.prototype.slice.call(found,0,MAX_NODES);
    var candidates=nodes.filter(isInput), forms=nodes.filter(function(e){return e.tagName==='FORM';}), cookie=cookieNodes(nodes);
    var measured=nodes.map(function(e){return measure(e,false);});
    var targets=forms.concat(candidates).filter(function(e,i,a){return a.indexOf(e)===i;}).slice(0,16).map(function(e){
      var m=measure(e,true);m.type=e.tagName==='FORM'?'form':inputType(e);m.hitTests=hitTests(e);return m;
    });
    var containers=[document.documentElement,document.body,document.getElementById('root'),document.getElementById('app'),document.getElementById('__next')];
    if(document.body)containers=containers.concat(Array.prototype.slice.call(document.body.children,0,8));
    containers=containers.filter(function(e,i,a){return e && a.indexOf(e)===i;}).map(function(e){return measure(e,true);});
    var overlays=measured.filter(function(m){return /^(fixed|absolute|sticky)$/.test(m.styles.position) && m.inViewport &&
      m.rect.width*m.rect.height>=window.innerWidth*window.innerHeight*0.4;}).slice(0,12);
    return {reason:reason,scope:'top-document; cross-origin frames not inspected',readyState:document.readyState,
      viewport:{width:window.innerWidth,height:window.innerHeight,scrollX:round(window.scrollX),scrollY:round(window.scrollY)},
      counts:{total:found.length,scanned:nodes.length,scanLimited:found.length>MAX_NODES,
        positiveRect:measured.filter(function(m){return m.rect.width>0&&m.rect.height>0;}).length,
        rendered:measured.filter(function(m){return m.rendered;}).length,
        renderedInViewport:measured.filter(function(m){return m.rendered&&m.inViewport;}).length},
      forms:{actualForms:forms.length,inputCandidates:candidates.length,
        loginHeuristic:candidates.some(function(e){return /^(password|email|tel)$/.test(inputType(e));}),
        note:'candidate counts do not prove a visible login form; inspect targets/ancestors/hitTests'},
      consent:{visible:cookie.length>0,containers:cookie.map(function(e){return measure(e,false);})},
      containers:containers,targets:targets,overlays:overlays,
      viewportHitTests:[[0.1,0.1],[0.5,0.5],[0.9,0.9]].map(function(p){
        var x=window.innerWidth*p[0],y=window.innerHeight*p[1];
        return {x:round(x),y:round(y),stack:(document.elementsFromPoint?document.elementsFromPoint(x,y):[]).slice(0,6).map(function(e){return measure(e,false);})};
      }),
      limitations:'rendered is a CSS/geometry heuristic, not pixel proof; hit tests sample points and may miss overlays/pseudo-elements; closed shadow roots and frame internals unavailable'};
    } finally {styleCache=null;rectCache=null;}
  }
  function compare(before, after) {
    var changes=[], old={};
    before.containers.concat(before.targets).forEach(function(m){old[m.ref]=m;});
    after.containers.concat(after.targets).forEach(function(m){
      var prev=old[m.ref];if(!prev){changes.push({ref:m.ref,change:'added'});return;}
      var fields=[];
      ['rect','styles','effectiveOpacity','rendered','inViewport','clippedBy','hitTests'].forEach(function(k){if(JSON.stringify(prev[k])!==JSON.stringify(m[k]))fields.push(k);});
      if(fields.length)changes.push({ref:m.ref,changed:fields});delete old[m.ref];
    });
    Object.keys(old).forEach(function(key){changes.push({ref:key,change:'removed'});});
    return {before:{consent:{visible:before.consent.visible},counts:before.counts,forms:before.forms},
      after:{consent:{visible:after.consent.visible},counts:after.counts,forms:after.forms},changes:changes,
      note:'Full geometry/styles are in paired before/after snapshots; correlation with cookie dismissal is not proof of cause'};
  }
  function sample(reason) {
    try {
      var s=snapshot(reason), fingerprint=JSON.stringify([s.counts,s.forms,s.consent.visible,s.containers,s.targets,s.overlays]), emitted=false;
      if(lastWithCookie && !s.consent.visible) {
        emit('COOKIE_BEFORE_DISAPPEAR','last observed consent-visible snapshot',lastWithCookie);
        emit('COOKIE_AFTER_DISAPPEAR','first observed consent-hidden snapshot',s);
        emit('COOKIE_DOM_DIFF','consent visible → hidden',compare(lastWithCookie,s));lastWithCookie=null;
        emitted=true;
        [300,1500].forEach(function(ms){setTimeout(function(){sample('cookie-settled-'+ms);},ms);});
      } else if(reason!=='poll' || (fingerprint!==lastFingerprint && Date.now()-lastAuto>5000)) {
        emit('DOM_SNAPSHOT',reason,s);emitted=true;
      }
      if(s.consent.visible)lastWithCookie=s;
      // Compare against the last recorded state, so throttled changes are deferred, not lost.
      if(emitted){lastFingerprint=fingerprint;lastAuto=Date.now();}
    } catch(e) {emit('DIAGNOSTIC_ERROR',errorDetail(e));}
  }
  document.addEventListener('click',function(e){
    try {
      var nodes=Array.prototype.slice.call(document.querySelectorAll('*'),0,MAX_NODES), banners=cookieNodes(nodes);
      if(banners.some(function(n){return n.contains(e.target);})) {
        var s=snapshot('cookie-before-click');lastWithCookie=s;
        emit('COOKIE_BEFORE_CLICK','capture phase; site handler has not run',s);
        [0,300,1500].forEach(function(ms){setTimeout(function(){sample('cookie-click-after-'+ms);},ms);});
      }
    } catch(err){emit('DIAGNOSTIC_ERROR',errorDetail(err));}
  },true);
  window.__snowseekDebugV2={sample:sample,snapshot:snapshot,drain:function(){
    var result=events;events=[];eventBytes=0;
    if(dropped){result.unshift({kind:'DIAGNOSTIC_DROPPED',text:String(dropped)+' queued events dropped',at:Date.now()});dropped=0;}
    return result;
  }};
  emit('HOOK_READY','Observing window errors, Promise rejection and DOM; no prevention/overrides. Only errors after hook installation are observable.');
})();
