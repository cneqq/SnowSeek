/* Scoped recovery for a collapsed login document; never change form styling or protection. */
(function () {
  'use strict';
  if (window.__snowseekRepairHeight) return;
  var attempted = new WeakSet(), owned = null;
  function restore(entries, onlyOwned) {
    entries.forEach(function(s){
      if(onlyOwned && (s.element.style.getPropertyValue(s.property)!=='100%' || s.element.style.getPropertyPriority(s.property)!=='important'))return;
      if(s.value)s.element.style.setProperty(s.property,s.value,s.priority);else s.element.style.removeProperty(s.property);
    });
  }
  function leaveLogin(reason) {
    if(!owned)return {status:reason};
    restore(owned.entries,true);attempted.delete(owned.root);owned=null;
    if(window.__snowseekDebugV2)window.__snowseekDebugV2.sample('height-fix-removed');
    return {status:'restored-after-login',reason:reason};
  }
  function rect(e) {
    var r=e.getBoundingClientRect();
    return {width:Math.round(r.width),height:Math.round(r.height)};
  }
  function snapshot(html,body,root) {return {html:rect(html),body:rect(body),root:rect(root),viewportHeight:window.innerHeight};}
  function suppressed(e) {
    var s=getComputedStyle(e);
    return e.hidden || s.display==='none' || s.visibility==='hidden' || s.visibility==='collapse' || Number(s.opacity)===0;
  }
  window.__snowseekRepairHeight=function(native) {
    if (window!==window.top || window.location.origin!=='https://chat.deepseek.com') return {status:'wrong-origin'};
    if (window.location.pathname!=='/' && !/^\/sign_in(?:\/|$)/.test(window.location.pathname)) return leaveLogin('not-login-route');
    if (!native || !native.ready || native.webWidth<=0 || native.webHeight<=0 || native.hostWidth<=0 || native.hostHeight<=0) return {status:'native-not-ready'};
    if (window.innerHeight<=0 || window.innerWidth<=0) return {status:'viewport-not-ready'};
    var html=document.documentElement,body=document.body;
    if (!html || !body || document.readyState==='loading') return {status:'document-not-ready'};
    var controls=Array.prototype.slice.call(document.querySelectorAll('input[type=password],input[type=email],input[type=tel]'),0,20);
    if(!controls.length)return leaveLogin('no-login-controls');
    if(owned && !controls.some(function(e){return owned.root.contains(e);}))return leaveLogin('login-root-replaced');
    var input=controls.find(function(e){var r=e.getBoundingClientRect();return r.width>1&&r.height>1&&!suppressed(e);});
    if (!input) return leaveLogin('no-login-controls');
    if (rect(html).height>1 || rect(body).height>1) return {status:'not-collapsed',html:rect(html),body:rect(body)};
    // The SPA root may have no stable id (e.g. div@45 in diagnostics). Use only the
    // direct body child containing the existing login input, not arbitrary form ancestors.
    var root=input;
    while(root.parentElement && root.parentElement!==body)root=root.parentElement;
    if(root.parentElement!==body || root===input || !/^(DIV|MAIN|SECTION)$/.test(root.tagName)) return {status:'no-app-root'};
    if (suppressed(html)||suppressed(body)||suppressed(root)) return {status:'intentionally-hidden'};
    if (attempted.has(root)) return {status:'already-attempted'};
    attempted.add(root);
    var before=snapshot(html,body,root),saved=[];
    if(window.__snowseekDebugV2)window.__snowseekDebugV2.sample('height-fix-before');
    [html,body,root].forEach(function(e){
      ['height','min-height'].forEach(function(property){
        saved.push({element:e,property:property,value:e.style.getPropertyValue(property),priority:e.style.getPropertyPriority(property)});
        e.style.setProperty(property,'100%','important');
      });
    });
    // Read after writes: force a layout, rather than claiming CSS application proves recovery.
    var after=snapshot(html,body,root);
    if(after.html.height<=1 || after.body.height<=1 || after.root.height<=1) {
      restore(saved,false);
      if(window.__snowseekDebugV2)window.__snowseekDebugV2.sample('height-fix-rolled-back');
      return {status:'rolled-back',before:before,attempted:after,after:snapshot(html,body,root)};
    }
    owned={root:root,entries:saved};
    if(window.__snowseekDebugV2)window.__snowseekDebugV2.sample('height-fix-after');
    return {status:'applied',before:before,after:after,properties:['height','min-height'],value:'100%',targets:['html','body','login-app-root']};
  };
})();
