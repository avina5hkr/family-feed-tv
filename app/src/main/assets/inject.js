// Injected by MainActivity on onPageCommitVisible, onPageFinished and doUpdateVisitedHistory.
// Repeated injection is safe: the second and later runs only re-apply the zoom.
(function () {
  var ZOOM = __PAGE_ZOOM_PERCENT__ / 100;

  // Page zoom. WebView.setInitialScale is ignored when a page pins its own scale, so we write the viewport
  // meta ourselves: a layout width of screen.width / ZOOM shown at a fixed scale, like browser zoom.
  function applyZoom() {
    var content = 'width=' + Math.round(screen.width / ZOOM) + ',initial-scale=' + ZOOM +
        ',minimum-scale=' + ZOOM + ',maximum-scale=' + ZOOM + ',user-scalable=no';
    var metas = document.querySelectorAll('meta[name="viewport"]');
    if (metas.length === 0) {
      var meta = document.createElement('meta');
      meta.name = 'viewport';
      (document.head || document.documentElement).appendChild(meta);
      metas = [meta];
    }
    for (var i = 0; i < metas.length; i++) {
      if (metas[i].content !== content) metas[i].content = content;
    }
  }

  if (window.__fbtv) {
    window.__fbtv.applyZoom();
    return;
  }
  window.__fbtv = { applyZoom: applyZoom };

  applyZoom();
  // Facebook may rewrite the viewport meta on its own; put ours back.
  new MutationObserver(applyZoom).observe(document.head || document.documentElement, {
    childList: true, subtree: true, attributes: true, attributeFilter: ['content']
  });
})();
