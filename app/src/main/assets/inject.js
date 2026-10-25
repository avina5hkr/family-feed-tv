// Injected by MainActivity on onPageCommitVisible, onPageFinished and doUpdateVisitedHistory.
// Repeated injection is safe: the second and later runs only re-apply the zoom and re-check the focus.
//
// Remote control model (built against the desktop site, www.facebook.com):
//  - Feed:   Up/Down move between the parts of a post that matter (photo/video, "See more", Like/Comment/Share)
//            and on to the next post; Left/Right move along the Like/Comment/Share row. Up from the first post
//            reaches the top bar (Home, Reels, Friends...).
//  - Reels:  Up/Down = previous/next reel (Facebook's own buttons), Right = Like/Comment/Share column,
//            Left = back to the video, or rewind while on the video. OK plays/pauses.
//  - Feed videos: OK plays them in "theater mode" (stretched over the page); there OK plays/pauses and
//            Left/Right seek. Back returns to the feed. Sound is always switched on, for reels too.
//  - Dialogs (comments, photo viewer, menus) and every other page: move to the nearest clickable element.
//  - Back (from Kotlin, via __fbtv.back()): leave theater mode / a text field / a dialog, leave Reels in one
//            step, or jump to the top bar from deep in the feed.
(function () {
  var ZOOM = __PAGE_ZOOM_PERCENT__ / 100;
  var SCROLL_STEP = 350;
  var SEEK_SECONDS = 10;
  var FOCUS_ATTR = 'data-fbtv-focus';
  var THEATER_ATTR = 'data-fbtv-theater';
  var CLICKABLE = 'a[href],button,[role=button],[role=link],[role=tab],[role=menuitem],[role=menuitemradio],' +
      '[role=menuitemcheckbox],[role=option],[role=checkbox],[role=switch],[role=radio],[role=slider],' +
      'input:not([type=hidden]),textarea,select,[contenteditable=true]';
  // Facebook's mute toggle, matched by label because toggling video.muted directly doesn't update Facebook's
  // own sound setting (the next reel would start muted again).
  var UNMUTE_LABEL = /unmute|अनम्यूट/i;

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
    window.__fbtv.refresh();
    return;
  }

  var focused = null;      // element carrying the focus ring
  var lastRect = null;     // where it was, to recover when Facebook removes it from the page
  var quietUntil = 0;      // no automatic focus recovery while our own smooth scroll is still running
  var lastPath = location.pathname;

  // ---------- Geometry and visibility ----------

  function rectOf(el) { return el.getBoundingClientRect(); }

  /** Bottom of Facebook's fixed top bar. The [role=banner] element itself has no height, so use its buttons. */
  function headerBottom() {
    var banner = document.querySelector('[role=banner]'), bottom = 0;
    if (!banner) return 0;
    var items = banner.querySelectorAll(CLICKABLE);
    for (var i = 0; i < items.length; i++) {
      var r = rectOf(items[i]);
      if (r.height > 0 && r.top >= -1 && r.bottom < innerHeight / 3) bottom = Math.max(bottom, r.bottom);
    }
    return bottom;
  }

  /** Usable: laid out, not hidden, horizontally on screen, and vertically within reach of the viewport. */
  function usable(el, minSide) {
    var r = rectOf(el);
    if (r.width < minSide || r.height < minSide) return false;
    if (r.right <= 0 || r.left >= innerWidth) return false;
    if (r.bottom < -innerHeight * 1.5 || r.top > innerHeight * 2.5) return false;
    if (el.closest('[aria-hidden=true],[inert]')) return false;
    if (el.getAttribute('aria-disabled') === 'true' || el.disabled) return false;
    return getComputedStyle(el).visibility !== 'hidden';
  }

  /** At least a real part of it (24px, or half of a small element) is visible, and not hidden under the top bar. */
  function onScreen(el) {
    var r = rectOf(el), top = el.closest('[role=banner]') ? 0 : headerBottom();
    var visible = Math.min(r.bottom, innerHeight) - Math.max(r.top, top);
    return r.width > 0 && r.right > 0 && r.left < innerWidth && visible >= Math.min(24, r.height / 2);
  }

  /** All clickable elements in scope; nested clickables covering the same area count once (outermost wins). */
  function clickables(scope, minSide) {
    var all = scope.querySelectorAll(CLICKABLE), set = new Set(), out = [];
    for (var i = 0; i < all.length; i++) if (usable(all[i], minSide)) set.add(all[i]);
    set.forEach(function (el) {
      var r = rectOf(el);
      for (var p = el.parentElement; p && p !== scope; p = p.parentElement) {
        if (set.has(p)) {
          var pr = rectOf(p);
          if (pr.width * pr.height <= r.width * r.height * 1.3) return;
        }
      }
      out.push(el);
    });
    return out;
  }

  // ---------- Page modes ----------

  function topDialog() {
    var ds = document.querySelectorAll('[role=dialog],[aria-modal=true],[role=menu]');
    for (var i = ds.length - 1; i >= 0; i--) {
      var r = rectOf(ds[i]);
      if (r.width > 100 && r.height > 100 && !ds[i].closest('[aria-hidden=true]')) return ds[i];
    }
    return null;
  }

  function isReels() { return /^\/reels?\//.test(location.pathname); }

  function posts() { return document.querySelectorAll('[aria-posinset]'); }

  function postOf(el) { return el && el.closest ? el.closest('[aria-posinset]') : null; }

  function headerTargets() {
    var banner = document.querySelector('[role=banner]');
    return banner ? clickables(banner, 24) : [];
  }

  /** The main photo or video of a post. */
  function postMedia(post) {
    var pw = rectOf(post).width, videos = post.querySelectorAll('video');
    for (var i = 0; i < videos.length; i++) if (rectOf(videos[i]).width >= pw * 0.5) return videos[i];
    var best = null, bestArea = 0, imgs = post.querySelectorAll('img');
    for (var j = 0; j < imgs.length; j++) {
      var r = rectOf(imgs[j]);
      if (r.width >= pw * 0.5 && r.width * r.height > bestArea) { bestArea = r.width * r.height; best = imgs[j]; }
    }
    return best ? best.closest('a[href],[role=button],[role=link]') : null;
  }

  /** Like / Comment / Share: the row with the most full-size buttons (comment rows only have small links). */
  function actionRow(post) {
    var rows = {}, buttons = post.querySelectorAll('[role=button]');
    for (var i = 0; i < buttons.length; i++) {
      var b = buttons[i], r = rectOf(b);
      if (r.height < 24 || r.height > 48 || r.width < 40 || !usable(b, 1)) continue;
      var key = Math.round(r.top / 6);
      (rows[key] = rows[key] || []).push(b);
    }
    var best = [];
    Object.keys(rows).forEach(function (k) {
      if (rows[k].length >= 2 && rows[k].length >= best.length) best = rows[k];
    });
    return best;
  }

  function postTargets(post) {
    var out = [], media = postMedia(post);
    if (media && usable(media, 1)) out.push(media);
    var more = post.querySelectorAll('[data-ad-preview=message] [role=button],[data-ad-comet-preview=message] [role=button]');
    for (var i = 0; i < more.length; i++) if (usable(more[i], 1)) out.push(more[i]);
    var row = actionRow(post);
    if (!media && row.length === 0) return clickables(post, 24);  // friend suggestions, reel trays...
    return out.concat(row);
  }

  function reelVideo() {
    var best = null, bestArea = 0, vs = document.querySelectorAll('video');
    for (var i = 0; i < vs.length; i++) {
      var r = rectOf(vs[i]);
      var visible = Math.max(0, Math.min(r.bottom, innerHeight) - Math.max(r.top, 0)) *
          Math.max(0, Math.min(r.right, innerWidth) - Math.max(r.left, 0));
      if (visible > bestArea) { bestArea = visible; best = vs[i]; }
    }
    return best;
  }

  /**
   * The reel's Like/Comment/Share column: the column of buttons nearest to the right of the video. (On landscape
   * reels Facebook's previous/next column is close by too; it must not count.)
   */
  function reelActions(video) {
    if (!video) return [];
    var vr = rectOf(video);
    var near = clickables(document.body, 32).filter(function (b) {
      var r = rectOf(b);
      return r.left >= vr.right - 16 && r.left <= vr.right + 120 && r.top > vr.top - 16 && r.bottom < vr.bottom + 80;
    });
    var minLeft = Math.min.apply(null, near.map(function (b) { return rectOf(b).left; }));
    return near.filter(function (b) { return rectOf(b).left <= minLeft + 10; });
  }

  /**
   * Facebook's previous/next reel buttons: the two round buttons in the rightmost column of the screen. Only that
   * column counts; the Like/Comment/Share column can also sit far right on landscape reels.
   */
  function reelNavButtons() {
    var banner = document.querySelector('[role=banner]'), video = reelVideo(), top = headerBottom() + 4;
    var actions = new Set(reelActions(video));
    // Not clickables(): on the first reel "previous" is aria-disabled, and both buttons are needed to tell them apart.
    var bs = Array.prototype.filter.call(document.querySelectorAll('[role=button]'), function (b) {
      var r = rectOf(b);
      // (Below the top bar: its right-hand buttons aren't all inside [role=banner].)
      return r.width >= 36 && r.width < 80 && r.height >= 36 && r.top >= top && r.bottom <= innerHeight &&
          r.left < innerWidth && !actions.has(b) && !(banner && banner.contains(b)) &&
          (!video || r.left >= rectOf(video).right);
    });
    var maxLeft = Math.max.apply(null, bs.map(function (b) { return rectOf(b).left; }));
    bs = bs.filter(function (b) { return rectOf(b).left >= maxLeft - 10; })
        .sort(function (a, b) { return rectOf(a).top - rectOf(b).top; });
    return bs.length === 2 ? { prev: bs[0], next: bs[1] } : null;
  }

  var pendingReel = null;
  function changeReel(dir) {
    if (restoring) {
      // Still sliding to the last one: Facebook mishandles clicks mid-animation, so do this one right after.
      pendingReel = dir;
      return;
    }
    var nav = reelNavButtons(), video = reelVideo();
    if (nav) {
      (dir === 'down' ? nav.next : nav.prev).click();
    } else if (video) {
      // Fallback: Facebook's reel viewer also listens for the arrow keys on its own container.
      video.dispatchEvent(new KeyboardEvent('keydown', {
        key: dir === 'down' ? 'ArrowDown' : 'ArrowUp', keyCode: dir === 'down' ? 40 : 38, bubbles: true
      }));
    }
    restoreReelSlot(video);
  }

  // Which control is selected in the reel viewer, so the same one stays selected on the next reel
  // (e.g. keep pressing OK on Facebook's on-screen "next" arrow, or stay on Like).
  var reelSlot = { kind: 'video' };

  /**
   * Opening Reels keeps this TV busy for several seconds, which delays the timer that selects the first reel.
   * A key press in the meantime selects it straight away.
   */
  function finishEntryRestore() {
    if (!restoring || !restoringEntry || !reelVideo()) return;
    clearInterval(restoreTimer);
    restoring = false;
    setFocus(slotElement(reelSlot), false);
  }

  function slotOf(el) {
    if (!el) return null;
    if (el.tagName === 'VIDEO') return { kind: 'video' };
    var nav = reelNavButtons();
    if (nav && el === nav.prev) return { kind: 'prev' };
    if (nav && el === nav.next) return { kind: 'next' };
    var index = reelActions(reelVideo()).indexOf(el);
    return index >= 0 ? { kind: 'action', index: index } : null;
  }

  function slotElement(slot) {
    var v = reelVideo();
    if (!slot || slot.kind === 'video') return v;
    if (slot.kind === 'prev' || slot.kind === 'next') {
      var nav = reelNavButtons();
      return nav ? nav[slot.kind] : v;
    }
    var actions = reelActions(v);
    return actions[slot.index] || v;
  }

  /** After a reel change, put the ring back on the same control once the new reel is on screen. */
  var restoreTimer = 0, restoring = false, restoringEntry = false;
  function restoreReelSlot(oldVideo) {
    var slot = reelSlot, started = Date.now();
    restoringEntry = !oldVideo;
    note('restore start slot=' + JSON.stringify(slot) + ' old=' + describe(oldVideo));
    clearInterval(restoreTimer);
    restoring = true;
    restoreTimer = setInterval(function () {
      var v = reelVideo(), waited = Date.now() - started;
      if (!isReels() || waited > 3000 || (v && v !== oldVideo && onScreen(v) && waited > 300)) {
        note('restore end after ' + waited + 'ms reels=' + isReels() + ' video=' + describe(v));
        clearInterval(restoreTimer);
        restoring = false;
        var el = isReels() ? slotElement(slot) : null;
        if (el) { setFocus(el, false); reelSlot = slot; } else recoverFocus();
        if (pendingReel && isReels()) {
          var next = pendingReel;
          pendingReel = null;
          setTimeout(function () { changeReel(next); }, 100);
        }
      }
    }, 150);
  }

  function targets() {
    var dialog = topDialog();
    if (dialog) return clickables(dialog, 12);
    if (isReels()) {
      var v = reelVideo(), nav = reelNavButtons();
      var arrows = nav ? [nav.prev, nav.next].filter(function (b) { return b.getAttribute('aria-disabled') !== 'true'; }) : [];
      return headerTargets().concat(v ? [v] : [], reelActions(v), arrows);
    }
    var ps = posts();
    if (ps.length === 0) return clickables(document.body, 12);
    var out = headerTargets();
    for (var i = 0; i < ps.length; i++) {
      if (usable(ps[i], 1)) out = out.concat(postTargets(ps[i]));
    }
    return out;
  }

  // ---------- Focus ring ----------

  var style = document.createElement('style');
  style.textContent = '[' + FOCUS_ATTR + ']{outline:4px solid #1877F2 !important;outline-offset:2px !important;}' +
      '[' + THEATER_ATTR + ']{position:fixed !important;left:0 !important;top:var(--fbtv-top,0px) !important;' +
      'width:100vw !important;height:calc(100vh - var(--fbtv-top,0px)) !important;max-width:none !important;' +
      'max-height:none !important;z-index:2147483647 !important;background:#000 !important;' +
      'object-fit:contain !important;transform:none !important;outline:none !important;}';
  (document.head || document.documentElement).appendChild(style);

  function isEditable(el) {
    return el.isContentEditable || /^(INPUT|TEXTAREA|SELECT)$/.test(el.tagName);
  }

  // Last focus changes with their cause, for remote debugging: __fbtv.log in chrome://inspect.
  var log = [];
  function note(msg) {
    log.push(new Date().toTimeString().slice(3, 8) + ' ' + location.pathname.slice(0, 14) + ' ' + msg);
    if (log.length > 40) log.shift();
  }

  function describe(el) {
    return el ? el.tagName + ' ' + (el.getAttribute('aria-label') || '').slice(0, 20) : 'none';
  }

  function setFocus(el, reveal) {
    note('focus ' + describe(el) + ' <- ' + (new Error().stack.split('\n')[2] || '').trim().split(' ')[1]);
    if (focused && focused !== el) focused.removeAttribute(FOCUS_ATTR);
    focused = el;
    el.setAttribute(FOCUS_ATTR, '');
    // Real DOM focus too, so Facebook's own keyboard handling follows. Editable fields only get focus on OK,
    // otherwise the on-screen keyboard would pop up while just moving past them.
    if (el.tagName === 'VIDEO' || isEditable(el)) {
      if (document.activeElement && document.activeElement !== document.body) document.activeElement.blur();
    } else if (typeof el.focus === 'function') {
      el.focus({ preventScroll: true });
    }
    if (reveal) revealElement(el, reveal === 'smooth');
    lastRect = rectOf(el);
    if (isReels() && !topDialog()) reelSlot = slotOf(el) || reelSlot;
  }

  /** The ring's element still exists, is laid out, and is inside the open dialog if there is one. */
  function focusValid() {
    if (!focused || !focused.isConnected || !usable(focused, 1)) return false;
    var dialog = topDialog();
    return dialog ? dialog.contains(focused) : true;
  }

  /** Put the ring on the most sensible element: the one nearest to where the old one was, or a default. */
  function recoverFocus() {
    var ts = targets().filter(onScreen);
    if (ts.length === 0) return false;
    var best = ts[0];
    if (isReels() && !topDialog()) {
      best = reelVideo() || best;
    } else if (lastRect && !topDialog()) {
      var cy = lastRect.top + lastRect.height / 2, bestD = Infinity;
      ts.forEach(function (t) {
        var r = rectOf(t), d = Math.abs(r.top + r.height / 2 - cy) + Math.abs(r.left - lastRect.left) * 0.5;
        if (d < bestD) { bestD = d; best = t; }
      });
    } else {
      // Skip the top bar: start on the topmost thing in the page itself.
      var header = new Set(headerTargets());
      best = edgeTarget(ts.filter(function (t) { return !header.has(t); }), true) || best;
    }
    setFocus(best, false);
    return true;
  }

  // ---------- Scrolling ----------

  /** The scrolling box inside an open dialog (comments, menus); outside dialogs the page itself scrolls. */
  function scrollerOf(el) {
    var dialog = topDialog();
    if (!dialog || !dialog.contains(el)) return null;
    for (var p = el.parentElement; p && dialog.contains(p); p = p.parentElement) {
      var cs = getComputedStyle(p);
      if (/(auto|scroll)/.test(cs.overflowY) && p.scrollHeight > p.clientHeight + 4) return p;
    }
    return null;  // the page itself
  }

  function scrollByY(scroller, dy, smooth) {
    quietUntil = Date.now() + 1000;
    var opts = { top: dy, behavior: smooth ? 'smooth' : 'auto' };
    if (scroller) scroller.scrollBy(opts); else window.scrollBy(opts);
  }

  /**
   * In the feed, show as much of the element's post as possible: post top just under the top bar, unless that
   * would push the element out of view. Everywhere else, centre the element.
   */
  function revealElement(el, smooth) {
    var r = rectOf(el), scroller = scrollerOf(el);
    if (el.closest('[role=banner]')) return;
    var top = scroller ? rectOf(scroller).top : headerBottom(), bottom = scroller ? rectOf(scroller).bottom : innerHeight;
    var post = !scroller && postOf(el), dy;
    if (post) {
      dy = rectOf(post).top - top - 8;
      if (r.bottom - dy > bottom - 8) dy = r.bottom - bottom + 8;
      if (r.top - dy < top + 8) dy = r.top - top - 8;
    } else {
      dy = r.top + r.height / 2 - (top + bottom) / 2;
    }
    if (Math.abs(dy) > 2) scrollByY(scroller, dy, smooth);
  }

  // ---------- Directional movement ----------

  function nearest(from, dir, candidates) {
    var f = rectOf(from), best = null, bestScore = Infinity;
    candidates.forEach(function (c) {
      if (c === from || c.contains(from) || from.contains(c)) return;
      var r = rectOf(c), primary, cross;
      if (dir === 'down') { primary = r.top - f.bottom; if (r.top + r.height / 2 <= f.top + f.height / 2 + 2) return; }
      if (dir === 'up') { primary = f.top - r.bottom; if (r.top + r.height / 2 >= f.top + f.height / 2 - 2) return; }
      if (dir === 'right') { primary = r.left - f.right; if (r.left + r.width / 2 <= f.left + f.width / 2 + 2) return; }
      if (dir === 'left') { primary = f.left - r.right; if (r.left + r.width / 2 >= f.left + f.width / 2 - 2) return; }
      if (dir === 'up' || dir === 'down') cross = Math.max(0, r.left - f.right, f.left - r.right);
      else cross = Math.max(0, r.top - f.bottom, f.top - r.bottom);
      var score = Math.max(primary, 0) + cross * 3;
      if (score < bestScore) { bestScore = score; best = c; }
    });
    return best;
  }

  function isFirstPost(post) {
    var ps = posts();
    for (var i = 0; i < ps.length; i++) if (usable(ps[i], 1)) return ps[i] === post && window.scrollY < innerHeight * 3;
    return false;
  }

  /** Topmost (or bottom-most) target, leftmost within that row. */
  function edgeTarget(list, top) {
    var best = null, bestR = null;
    list.forEach(function (t) {
      var r = rectOf(t);
      if (!best) { best = t; bestR = r; return; }
      var dy = top ? bestR.top - r.top : r.bottom - bestR.bottom;
      if (dy > 6 || (Math.abs(dy) <= 6 && r.left < bestR.left)) { best = t; bestR = r; }
    });
    return best;
  }

  function move(dir, smooth) {
    var candidates = targets(), header = new Set(headerTargets());
    var inHeader = header.has(focused);
    // The top bar is fixed on screen, so it always looks "near". Only go up into it when nothing in the page is
    // above; inside it, stay in it for Left/Right.
    var pool = candidates.filter(function (c) {
      return inHeader ? (dir === 'down' ? !header.has(c) : header.has(c)) : !header.has(c);
    });
    var next = nearest(focused, dir, pool);
    if (!next && dir === 'up' && !inHeader && !topDialog() && (!postOf(focused) || isFirstPost(postOf(focused)))) {
      // Land on the tab for the current page (Home in the feed), so it's always the same place.
      next = Array.from(header).filter(function (h) { return h.getAttribute('aria-current') === 'page'; })[0] ||
          nearest(focused, dir, Array.from(header));
    }
    var vertical = dir === 'up' || dir === 'down';
    if (next && vertical && postOf(next) !== postOf(focused) && postOf(focused)) {
      // Leaving a tall post: first scroll through the rest of it instead of skipping it.
      var pr = rectOf(postOf(focused));
      var hidden = dir === 'down' ? pr.bottom - innerHeight : headerBottom() - pr.top;
      if (hidden > 40 && (!onScreen(next) || next.closest('[role=banner]'))) {
        scrollByY(null, (dir === 'down' ? 1 : -1) * Math.min(SCROLL_STEP, hidden + 16), smooth);
        return;
      }
    }
    var nextPost = next && vertical ? postOf(next) : null;
    if (nextPost && nextPost !== postOf(focused)) {
      // Entering another post: start at its top going down (photo/text), at its bottom row going up (Like...).
      next = edgeTarget(postTargets(nextPost), dir === 'down') || next;
    }
    if (next) {
      setFocus(next, smooth ? 'smooth' : 'instant');
    } else if (vertical && !isReels()) {
      scrollByY(scrollerOf(focused), dir === 'down' ? SCROLL_STEP : -SCROLL_STEP, smooth);
      setTimeout(function () { if (!onScreen(focused)) recoverFocus(); }, 500);
    }
  }

  // ---------- Video and sound ----------

  function currentVideo() {
    if (focused && focused.tagName === 'VIDEO') return focused;
    var vs = document.querySelectorAll('video');
    for (var i = 0; i < vs.length; i++) if (!vs[i].paused && onScreen(vs[i])) return vs[i];
    return reelVideo();
  }

  function playing(v) { return v && !v.paused && !v.ended; }

  /** Facebook's own mute toggle for this video: the nearest "Unmute" button around it. */
  function unmuteButton(video) {
    for (var p = video.parentElement, depth = 0; p && depth < 12; p = p.parentElement, depth++) {
      var bs = p.querySelectorAll('[role=button][aria-label]');
      for (var i = 0; i < bs.length; i++) if (UNMUTE_LABEL.test(bs[i].getAttribute('aria-label'))) return bs[i];
    }
    return null;
  }

  var unmuteAttempts = new WeakMap();
  function ensureSound(v) {
    if (!v.muted && v.volume > 0) return;
    var now = Date.now(), a = unmuteAttempts.get(v) || { n: 0, t: now };
    if (now - a.t > 10000) { a.n = 0; a.t = now; }
    if (++a.n > 3) return;  // don't fight the page if it insists
    unmuteAttempts.set(v, a);
    var button = unmuteButton(v);
    if (button) button.click(); else v.muted = false;
    if (v.volume === 0) v.volume = 1;
  }

  // Media events don't bubble, but capture listeners on the document still see them.
  document.addEventListener('playing', function (e) { if (e.target.tagName === 'VIDEO') ensureSound(e.target); }, true);
  document.addEventListener('volumechange', function (e) {
    var v = e.target;
    if (v.tagName === 'VIDEO' && playing(v) && v.muted) setTimeout(function () { ensureSound(v); }, 300);
  }, true);

  function togglePlay(v) {
    if (!v) return;
    if (v.paused || v.ended) {
      var p = v.play();
      if (p && p.catch) p.catch(function () {});
      ensureSound(v);
    } else {
      v.pause();
    }
  }

  function seek(v, seconds) {
    if (!v || !isFinite(v.duration)) return;
    v.currentTime = Math.max(0, Math.min(v.duration - 0.5, v.currentTime + seconds));
  }

  /**
   * Channel -/+ (and Page Down/Up): next/previous reel in Reels; elsewhere scroll about one screen and select the
   * first thing now visible.
   */
  function page(dir) {
    if (theaterVideo || fullscreenVideo()) return;
    var dialog = topDialog();
    if (isReels() && !dialog) { changeReel(dir); return; }
    var scroller = focused && dialog && dialog.contains(focused) ? scrollerOf(focused) : null;
    var height = scroller ? scroller.clientHeight : innerHeight - headerBottom();
    scrollByY(scroller, (dir === 'down' ? 1 : -1) * Math.round(height * 0.85), true);
    setTimeout(function () {
      var header = new Set(headerTargets());
      var visible = targets().filter(function (t) { return !header.has(t) && onScreen(t); });
      var first = edgeTarget(visible, true);
      if (first) setFocus(first, false);
    }, 600);
  }

  /** The top-bar tab whose link goes to a path matching the test (Home: "/", Reels: "/reel/..."). */
  function tab(test) {
    return headerTargets().filter(function (h) {
      return h.tagName === 'A' && h.href && test(new URL(h.href, location.href).pathname);
    })[0];
  }

  function goTo(test, fallbackUrl) {
    var t = tab(test);
    if (t) t.click(); else location.href = fallbackUrl;
  }

  /** The post (feed) or reel the user is looking at: Like is its first action, Comment the second. */
  function currentActions() {
    if (isReels() && !topDialog()) return reelActions(reelVideo());
    var post = postOf(focused);
    if (!post || !onScreen(post)) {
      var ps = Array.prototype.filter.call(posts(), function (p) { return usable(p, 1) && onScreen(p); });
      post = ps[0];
    }
    return post ? actionRow(post) : [];
  }

  function pressAction(index) {
    var button = currentActions()[index];
    if (!button) return;
    setFocus(button, 'smooth');
    button.click();
    setTimeout(function () { if (!focusValid()) recoverFocus(); }, 700);
  }

  /** Colour keys, Menu and Channel/Page keys from the remote (see REMOTE_COMMANDS in MainActivity). */
  function command(name) {
    if (theaterVideo && name !== 'toggle' && name !== 'play' && name !== 'pause' &&
        name !== 'rewind' && name !== 'forward') {
      exitTheater();
    }
    if (name === 'pageDown') page('down');
    else if (name === 'pageUp') page('up');
    else if (name === 'home') {
      if (location.pathname === '/' && !topDialog()) {
        window.scrollTo({ top: 0, behavior: 'smooth' });
        lastRect = null;
        quietUntil = Date.now() + 1000;
        setTimeout(recoverFocus, 700);
      } else {
        goTo(function (p) { return p === '/'; }, '/');
      }
    } else if (name === 'reels') {
      if (!isReels()) goTo(function (p) { return /^\/reels?\//.test(p); }, '/reel/');
    } else if (name === 'like') pressAction(0);
    else if (name === 'comment') pressAction(1);
    else if (name === 'topBar') {
      var header = headerTargets();
      var current = header.filter(function (h) { return h.getAttribute('aria-current') === 'page'; })[0];
      if (current || header[0]) setFocus(current || header[0], false);
    } else media(name);
  }

  function media(action) {
    var v = currentVideo();
    if (action === 'toggle') togglePlay(v);
    else if (action === 'play') { if (v && v.paused) togglePlay(v); }
    else if (action === 'pause') { if (playing(v)) v.pause(); }
    else if (action === 'rewind') seek(v, -SEEK_SECONDS);
    else if (action === 'forward') seek(v, SEEK_SECONDS);
  }

  // ---------- Keys ----------

  function fullscreenVideo() {
    var fs = document.fullscreenElement;
    if (!fs) return null;
    return fs.tagName === 'VIDEO' ? fs : fs.querySelector('video');
  }

  // Feed videos are often taller than the TV screen, so OK plays them in "theater mode": the video element is
  // stretched over the page below the top bar. Real fullscreen doesn't work here: Facebook's player pauses the
  // video as soon as it leaves the page. Back (or the end of the video) returns to the feed.
  var theaterVideo = null;

  function enterTheater(v) {
    document.documentElement.style.setProperty('--fbtv-top', headerBottom() + 'px');
    v.setAttribute(THEATER_ATTR, '');
    theaterVideo = v;
    if (v.paused || v.ended) togglePlay(v); else ensureSound(v);
  }

  function exitTheater() {
    var v = theaterVideo;
    if (!v) return;
    theaterVideo = null;
    v.removeAttribute(THEATER_ATTR);
    v.pause();
    if (v.isConnected) setFocus(v, 'instant');
  }

  document.addEventListener('ended', function (e) { if (e.target === theaterVideo) exitTheater(); }, true);

  function activate(el) {
    if (el.tagName === 'VIDEO') {
      if (isReels()) togglePlay(el); else enterTheater(el);
      return;
    }
    if (isEditable(el)) { el.focus(); el.click(); return; }
    var slot = isReels() && !topDialog() ? slotOf(el) : null;
    if (slot && (slot.kind === 'prev' || slot.kind === 'next')) {
      // Facebook's own on-screen arrow: stay on it for the next reel.
      var oldVideo = reelVideo();
      el.click();
      restoreReelSlot(oldVideo);
      return;
    }
    el.click();
    // Opening something (comments, a menu, a photo) usually replaces what was focused.
    setTimeout(function () { if (!focusValid()) recoverFocus(); }, 700);
  }

  var DIRS = { ArrowUp: 'up', ArrowDown: 'down', ArrowLeft: 'left', ArrowRight: 'right' };

  window.addEventListener('keydown', function (e) {
    var dir = DIRS[e.key];
    if (!dir && e.key !== 'Enter') return;
    // While typing, arrows and Enter belong to the text field.
    if (document.activeElement && isEditable(document.activeElement) && document.activeElement === focused) return;

    if (isReels()) finishEntryRestore();

    var fsVideo = theaterVideo || fullscreenVideo();
    if (fsVideo) {
      // Theater or full-screen video: OK plays/pauses, Left/Right seek, Up/Down do nothing. Back leaves it.
      if (e.key === 'Enter') togglePlay(fsVideo);
      else if (dir === 'left' || dir === 'right') seek(fsVideo, dir === 'left' ? -SEEK_SECONDS : SEEK_SECONDS);
      e.preventDefault();
      e.stopPropagation();
      return;
    }

    var hadFocus = focusValid() && onScreen(focused);
    if (!hadFocus && recoverFocus() && dir) {
      // The first press only shows where the focus is.
      revealElement(focused, false);
      e.preventDefault(); e.stopPropagation();
      return;
    }
    if (!focused) return;

    var smooth = !e.repeat;
    var video = focused.tagName === 'VIDEO' ? focused : null;
    var inReels = isReels() && !topDialog();

    if (e.key === 'Enter') {
      activate(focused);
    } else if (inReels && video && (dir === 'up' || dir === 'down')) {
      changeReel(dir);
    } else if (video && dir === 'left' && (inReels || playing(video))) {
      seek(video, inReels ? -5 : -SEEK_SECONDS);
    } else if (video && dir === 'right' && playing(video) && !inReels) {
      seek(video, SEEK_SECONDS);
    } else if (inReels && !video && dir === 'left' && reelActions(reelVideo()).indexOf(focused) >= 0) {
      setFocus(reelVideo(), false);
    } else {
      move(dir, smooth);
    }
    e.preventDefault();
    e.stopPropagation();
  }, true);

  // ---------- Back (called from Kotlin) ----------

  function back() {
    if (theaterVideo) {
      exitTheater();
      return 'handled';
    }
    // Leave a text field the user went into with OK. (Facebook also focuses the comment box by itself when a
    // post opens; that one shouldn't cost an extra Back press.)
    var active = document.activeElement;
    if (active && isEditable(active) && active === focused) {
      active.blur();
      return 'handled';
    }
    var dialog = topDialog();
    if (dialog) {
      var target = document.activeElement || document.body;
      target.dispatchEvent(new KeyboardEvent('keydown', { key: 'Escape', code: 'Escape', keyCode: 27, which: 27, bubbles: true }));
      setTimeout(function () {
        if (dialog.isConnected && rectOf(dialog).width > 100) {
          var close = dialog.querySelector('[aria-label*="Close" i],[aria-label*="बंद"]');
          if (close) close.click();
        }
        recoverFocus();
      }, 400);
      return 'handled';
    }
    if (isReels()) return 'reels';
    // In the feed, Back first goes to the top bar (scrolling to the top); only Back on the top bar leaves the app.
    var header = headerTargets();
    if (location.pathname === '/' && header.indexOf(focused) < 0) {
      if (window.scrollY > 0) window.scrollTo({ top: 0, behavior: 'smooth' });
      var home = header.filter(function (h) { return h.getAttribute('aria-current') === 'page'; })[0] ||
          header.filter(function (t) { return rectOf(t).width > 60; })[0];
      if (home) setFocus(home, false);
      quietUntil = Date.now() + 1000;
      return 'handled';
    }
    return 'none';
  }

  // ---------- Keep the ring alive ----------

  function refresh() {
    if (location.pathname !== lastPath) {
      note('path ' + lastPath + ' -> ' + location.pathname + ' restoring=' + restoring);
      var wasReels = /^\/reels?\//.test(lastPath);
      lastPath = location.pathname;
      lastRect = null;
      if (isReels() && wasReels) {
        // Next/previous reel (also when Facebook's own arrow was clicked with OK): keep the same control selected.
        if (!restoring) restoreReelSlot(null);
      } else {
        if (isReels()) {
          // Just opened Reels: select the video as soon as the first reel is on screen.
          reelSlot = { kind: 'video' };
          restoreReelSlot(null);
        } else {
          // Facebook navigated without a page load (new tab...): start from the new page's default.
          setTimeout(function () { if (!theaterVideo && !topDialog()) recoverFocus(); }, 600);
        }
      }
      return;
    }
    if (Date.now() < quietUntil || restoring || theaterVideo || document.fullscreenElement) return;
    if (!focusValid() || !onScreen(focused)) recoverFocus();
  }

  /** For remote debugging (chrome://inspect): why is the focus where it is? */
  function debugState() {
    return {
      focused: focused && (focused.tagName + ' ' + (focused.getAttribute('aria-label') || '').slice(0, 30)),
      connected: !!(focused && focused.isConnected), usable: !!(focused && usable(focused, 1)),
      onScreen: !!(focused && onScreen(focused)), valid: !!focusValid(), dialog: !!topDialog(),
      reels: isReels(), targets: targets().length
    };
  }

  window.__fbtv = { applyZoom: applyZoom, refresh: refresh, back: back, command: command, debug: debugState, log: log,
    internals: { targets: targets, reelNavButtons: reelNavButtons, reelActions: reelActions, reelVideo: reelVideo, nearest: nearest, headerTargets: headerTargets, posts: posts, isFirstPost: isFirstPost, focused: function () { return focused; } } };

  applyZoom();
  // Facebook may rewrite the viewport meta on its own; put ours back.
  new MutationObserver(applyZoom).observe(document.head || document.documentElement, {
    childList: true, subtree: true, attributes: true, attributeFilter: ['content']
  });
  // Facebook re-renders constantly; a cheap periodic check keeps the ring from vanishing.
  setInterval(function () { if (!document.hidden) refresh(); }, 1000);
  if (isReels()) restoreReelSlot(null);  // select the first reel's video once it is on screen
  else setTimeout(refresh, 800);
})();
