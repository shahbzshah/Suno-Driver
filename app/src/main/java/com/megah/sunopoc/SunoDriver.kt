package com.megah.sunopoc

import android.os.Handler
import android.os.Looper
import android.webkit.JavascriptInterface
import android.webkit.WebView
import org.json.JSONObject

/**
 * SunoDriver — ALL Suno page automation lives in this one file.
 *
 * IMPORTANT (proof-of-concept): the selectors below are BEST-EFFORT,
 * tuned against how Suno's create page behaved in the first real-device
 * runs. The create flow is mode-aware (see the create-flow comment): it
 * fills ONLY a genuine Song Description field and aborts rather than
 * ever writing a description into the Lyrics box. Every step reports
 * back through the "PocBridge" JavascriptInterface so failures are
 * visible in the on-screen status log.
 */
object SunoDriver {

    const val SUNO_CREATE_URL = "https://suno.com/create"

    // ------------------------------------------------------------------
    // Bridge: injected JS -> app status log
    // ------------------------------------------------------------------

    /**
     * Exposed to the page as `window.PocBridge`.
     *  - log(String)   : free-form step reports from injected scripts.
     *  - state(String) : polled page-state snapshot (deduplicated by the UI).
     *  - beat2(...)    : the page's player reporting itself through every
     *                    signal it exposes (audio element / media session /
     *                    pause button) so the VU meter can dance in sync
     *                    with the music without ever touching the audio
     *                    stream itself.
     * Callbacks arrive on a background thread; the owner must hop to the UI
     * thread before touching views.
     */
    class PocBridge(
        private val onLog: (String) -> Unit,
        private val onState: (String) -> Unit,
        private val onBeat: (Boolean, Double, String, Int, String) -> Unit =
            { _, _, _, _, _ -> }
    ) {
        @JavascriptInterface
        fun log(message: String) = onLog(message)

        @JavascriptInterface
        fun state(state: String) = onState(state)

        /**
         * Playback report from BEAT_SCRIPT. Suno's player technology is not
         * documented, so the script checks every honest signal:
         *  - source "audio"   : a real <audio> element is playing; position
         *                       is its exact currentTime in seconds.
         *  - source "session" : navigator.mediaSession says playing (the
         *                       player may use Web Audio, where no element
         *                       position exists); position is -1.
         *  - source "button"  : a visible Pause button implies playback;
         *                       position is -1.
         *  - source "none"    : nothing is playing; position is -1.
         * audioCount / mediaState ride along so the app log shows what the
         * player is actually doing when something looks off.
         */
        @JavascriptInterface
        fun beat2(playing: Boolean, position: Double, source: String, audioCount: Int, mediaState: String) =
            onBeat(playing, position, source, audioCount, mediaState)
    }

    /**
     * Polled ~5x/second by the activity while the app is open. Read-only:
     * it never plays, pauses or re-routes the audio — browser security
     * would mute the stream if the app tried to tap it, so the meter syncs
     * to the player's own signals instead.
     */
    const val BEAT_SCRIPT = """
(function () {
  try {
    if (!window.PocBridge || !window.PocBridge.beat2) return;
    var audios = document.querySelectorAll('audio');
    var chosen = null;
    for (var i = 0; i < audios.length; i++) {
      if (!audios[i].paused && !audios[i].ended && audios[i].currentTime > 0) { chosen = audios[i]; break; }
      if (!chosen && audios[i].currentTime > 0) chosen = audios[i];
    }
    var ms = 'none';
    try { if (navigator.mediaSession) ms = navigator.mediaSession.playbackState || 'none'; } catch (e) {}
    var playing = false, pos = -1, source = 'none';
    if (chosen && !chosen.paused && !chosen.ended) {
      playing = true; pos = chosen.currentTime; source = 'audio';
    } else if (ms === 'playing') {
      playing = true; source = 'session';
    } else {
      var btns = document.querySelectorAll('button[aria-label]');
      for (var j = 0; j < btns.length; j++) {
        var lab = (btns[j].getAttribute('aria-label') || '').toLowerCase();
        if (lab === 'pause' && btns[j].offsetParent !== null) { playing = true; source = 'button'; break; }
      }
    }
    window.PocBridge.beat2(playing, pos, source, audios.length, ms);
  } catch (e) {}
})();
"""

    // ------------------------------------------------------------------
    // Create flow: MODE check -> strict FILL -> guarded CREATE click.
    //
    // Why the mode check exists (bug fixed 2026-10-20, Megah's catch):
    // Suno's create page has two modes. SIMPLE mode has a real Song
    // Description field (Suno writes the lyrics from it — Megah's
    // workflow). CUSTOM mode has NO description field: it has a Lyrics
    // box, a Style box and a Title box. The old driver treated
    // "description" and "lyrics" as the same field and happily wrote the
    // song description into the Lyrics box, so Suno sang the description
    // prose as if it were lyrics. The driver now:
    //   1. detects a Lyrics box with no Description field (Custom mode)
    //      and clicks the page's "Simple" toggle;
    //   2. fills ONLY a genuine Song Description field — lyrics fields
    //      are explicitly excluded;
    //   3. ABORTS (no Create click, credits untouched) if no real
    //      description field can be found.
    // It also fills Suno's Title field when the page has one, and records
    // a baseline song-link count so the poller can tell NEW songs apart
    // from the library list.
    // ------------------------------------------------------------------

    fun fillAndCreate(webView: WebView, title: String, description: String, style: String) {
        val handler = Handler(Looper.getMainLooper())
        webView.evaluateJavascript(MODE_SCRIPT, null)
        // Let a mode switch re-render the form before filling.
        handler.postDelayed({
            webView.evaluateJavascript(buildFillScript(title, description, style), null)
        }, 1500)
        // Give the page's framework a beat to register the fill and
        // enable the Create button before clicking.
        handler.postDelayed({
            webView.evaluateJavascript(CREATE_CLICK_SCRIPT, null)
        }, 2900)
    }

    /** Field helpers shared by the mode and fill scripts. */
    private const val JS_HELPERS = """
  function log(m) { try { window.PocBridge.log(m); } catch (e) {} }
  function fieldText(el) {
    var t = '';
    try {
      t = ((el.getAttribute('placeholder') || '') + ' ' +
           (el.getAttribute('aria-label') || '') + ' ' +
           (el.getAttribute('name') || '') + ' ' +
           (el.getAttribute('data-testid') || '')).toLowerCase();
      if (el.id) {
        var lab = document.querySelector('label[for="' + el.id + '"]');
        if (lab) t += ' ' + lab.textContent.toLowerCase();
      }
    } catch (e) {}
    return t;
  }
  // Heading / label text NEAR a field (previous sibling, or the first
  // heading-ish element within a few ancestors). Suno labels its boxes
  // with nearby headings rather than placeholders on the current page,
  // so identification has to look around the field, not just at it.
  function nearbyText(el) {
    var t = '';
    try {
      var sib = el.previousElementSibling;
      if (sib) t += ' ' + (sib.textContent || '');
      var node = el;
      for (var i = 0; i < 3 && node; i++) {
        node = node.parentElement;
        if (!node) break;
        var h = node.querySelector('label, h1, h2, h3, h4');
        if (h && h !== el && !el.contains(h)) { t += ' ' + (h.textContent || ''); break; }
      }
    } catch (e) {}
    return t.toLowerCase().slice(0, 200);
  }
  function candidates() {
    return Array.prototype.slice.call(
      document.querySelectorAll('textarea, input[type="text"], input:not([type]), [contenteditable="true"]')
    ).filter(function (el) {
      return !el.disabled && el.type !== 'hidden' && el.offsetParent !== null;
    });
  }
  function isLyricsField(el) {
    return (fieldText(el) + ' ' + nearbyText(el)).indexOf('lyric') !== -1;
  }
  function isTextAreaLike(el) { return el.tagName === 'TEXTAREA' || el.isContentEditable; }
  // A REAL song-description field — never a lyrics field. Identification:
  // description wording on/around the field wins; otherwise, if the page
  // has NO lyrics field and exactly ONE big text box, that box is the
  // description by elimination (Suno's Simple mode).
  function strictDescField(exclude) {
    var pool = candidates().filter(function (el) {
      return el !== exclude && !isLyricsField(el);
    });
    var best = null, bestScore = 0;
    pool.forEach(function (el) {
      var t = fieldText(el) + ' ' + nearbyText(el);
      var score = 0;
      ['song description', 'describe', 'description'].forEach(function (k) {
        if (t.indexOf(k) !== -1) score += 2;
      });
      if (isTextAreaLike(el)) score += 1;
      if (score > bestScore) { bestScore = score; best = el; }
    });
    if (best && bestScore >= 2) return best;
    var hasLyrics = candidates().some(isLyricsField);
    var big = pool.filter(isTextAreaLike);
    if (!hasLyrics && big.length === 1) return big[0];
    return null;
  }
  // One log line naming every field the driver can see — ends the
  // guessing about what Suno's page actually contains.
  function dumpFields() {
    var list = candidates().slice(0, 6).map(function (el) {
      return el.tagName + (el.isContentEditable ? '(editable)' : '') +
        ' ph="' + (el.getAttribute('placeholder') || '') + '"' +
        ' near="' + nearbyText(el).trim().slice(0, 40) + '"' +
        (isLyricsField(el) ? ' [LYRICS]' : '');
    });
    log('Fields on page: ' + (list.length ? list.join(' | ') : 'NONE'));
  }
  function setValue(el, value) {
    el.focus();
    if (el.isContentEditable) {
      var ok = false;
      try {
        document.execCommand('selectAll', false, null);
        ok = document.execCommand('insertText', false, value);
      } catch (e) {}
      if (!ok) el.textContent = value;
      el.dispatchEvent(new InputEvent('input', { bubbles: true, inputType: 'insertText', data: value }));
      el.dispatchEvent(new Event('change', { bubbles: true }));
      return;
    }
    var proto = el.tagName === 'TEXTAREA'
      ? window.HTMLTextAreaElement.prototype
      : window.HTMLInputElement.prototype;
    var desc = Object.getOwnPropertyDescriptor(proto, 'value');
    if (desc && desc.set) { desc.set.call(el, value); } else { el.value = value; }
    el.dispatchEvent(new Event('input', { bubbles: true }));
    el.dispatchEvent(new Event('change', { bubbles: true }));
  }
"""

    /** Step 1: if the page is in Custom mode (Lyrics box, no Description
     *  field), click Suno's "Simple" toggle so a description field exists. */
    private const val MODE_SCRIPT = """
(function () {
$JS_HELPERS
  function findSimpleToggle() {
    var all = document.querySelectorAll('button, [role="tab"], [role="button"], a, div, span');
    var m = Array.prototype.filter.call(all, function (e) {
      return (e.textContent || '').trim().toLowerCase() === 'simple' && e.offsetParent !== null;
    });
    m.sort(function (a, b) {
      return (a.clientWidth * a.clientHeight) - (b.clientWidth * b.clientHeight);
    });
    return m[0] || null;
  }
  try {
    window.__sdAbort = false;
    dumpFields();
    var hasLyrics = candidates().some(isLyricsField);
    var desc = strictDescField(null);
    if (hasLyrics && !desc) {
      var toggle = findSimpleToggle();
      if (toggle) {
        toggle.click();
        log('Mode: page was in Custom mode (Lyrics box) — switched to Simple');
      } else {
        log('Mode: WARN — Lyrics box present but no Simple toggle found; fill will verify');
      }
    } else if (desc) {
      log('Mode: Song Description field present — Simple mode, good');
    } else if (hasLyrics) {
      log('Mode: only a Lyrics box is visible — fill will verify before writing anything');
    } else {
      log('Mode: WARN — no create fields found (page loading or signed out?)');
    }
  } catch (e) {
    log('Mode: ERROR ' + e.message);
  }
})();
"""

    /**
     * Step 2: fill description (strict field only), style (folded into
     * the description when Simple mode has no style field), and the title
     * when the page offers a title field. Sets the poller baseline.
     * Aborts — loudly, in the log — rather than ever touching a Lyrics box.
     */
    private fun buildFillScript(title: String, description: String, style: String): String {
        val titleLiteral = JSONObject.quote(title)
        val descLiteral = JSONObject.quote(description)
        val styleLiteral = JSONObject.quote(style)
        return """
(function () {
$JS_HELPERS
  function findField(keywords, excludeList) {
    var els = candidates().filter(function (el) {
      return excludeList.indexOf(el) === -1 && !isLyricsField(el);
    });
    var best = null, bestScore = 0;
    els.forEach(function (el) {
      var t = fieldText(el);
      var score = 0;
      keywords.forEach(function (k) { if (t.indexOf(k) !== -1) score += 2; });
      if (score > bestScore) { bestScore = score; best = el; }
    });
    return (best && bestScore >= 2) ? best : null;
  }
  try {
    var titleWanted = $titleLiteral;
    var descText = $descLiteral;
    var styleText = $styleLiteral;

    var descEl = strictDescField(null);
    if (!descEl) {
      window.__sdAbort = true;
      delete window.__sdBaseline;
      log('Fill: ABORT — no Song Description field on this page. The description was NOT put in the Lyrics box and Create was NOT clicked, so no credits were spent. Open the Suno Page, switch Suno to Simple mode, then tap create again.');
      return;
    }
    window.__sdBaseline = document.querySelectorAll('a[href*="/song/"]').length;
    var styleEl = findField(['style', 'genre'], [descEl]);
    if (!styleEl && styleText) {
      descText = descText + '\n\nStyle: ' + styleText;
      log('Fill: no separate Style field (Simple mode) — style folded into the description');
    }
    setValue(descEl, descText);
    log('Fill: Song Description FILLED (' + descEl.tagName + ', placeholder="' + (descEl.getAttribute('placeholder') || '') + '")');
    if (styleEl && styleText) {
      setValue(styleEl, styleText);
      log('Fill: Style FILLED (' + styleEl.tagName + ')');
    }
    if (titleWanted) {
      var titleEl = findField(['song title', 'title'], [descEl, styleEl]);
      if (titleEl) {
        setValue(titleEl, titleWanted);
        log('Fill: Title FILLED — "' + titleWanted + '"');
      } else {
        log('Fill: no Title field on this page (Simple mode names the song itself) — title kept in the log only');
      }
    }
  } catch (e) {
    window.__sdAbort = true;
    log('Fill: ERROR ' + e.message + ' — Create will NOT be clicked');
  }
})();
"""
    }

    /** Step 3: click Create — unless the fill aborted. */
    private const val CREATE_CLICK_SCRIPT = """
(function () {
  function log(m) { try { window.PocBridge.log(m); } catch (e) {} }
  try {
    if (window.__sdAbort) {
      log('Create: SKIPPED — the fill was aborted (see above). No credits spent.');
      return;
    }
    var btns = Array.prototype.slice.call(document.querySelectorAll('button'));
    var btn = btns.filter(function (b) {
      var t = (b.textContent || '').trim().toLowerCase();
      return t.indexOf('create') !== -1;
    })[0];
    if (!btn) { log('Create: button NOT FOUND'); return; }
    if (btn.disabled) { log('Create: button found but DISABLED (form may not have registered the fill)'); return; }
    btn.click();
    log('Create: button CLICKED');
  } catch (e) {
    log('Create: ERROR ' + e.message);
  }
})();
"""

    // ------------------------------------------------------------------
    // Poller script: report page state (challenge / login / results)
    // ------------------------------------------------------------------

    /**
     * Injected every few seconds by the activity's poller. Computes a short
     * state string and hands it to PocBridge.state(); the UI logs it only
     * when it changes.
     *
     *  CHALLENGE  — Cloudflare "Verify you are human" text, a challenges /
     *               turnstile iframe, or a "Just a moment" title.
     *  RESULTS:n  — n links to /song/... are on the page (generation output).
     *  FORM       — a textarea (the create form) is visible.
     *  SIGNED_OUT — a sign-in prompt is visible and no create form exists.
     *  PAGE       — anything else (title/path reported for debugging).
     */
    const val POLL_SCRIPT = """
(function () {
  try {
    var bodyText = (document.body && document.body.innerText) || '';
    var challengeFrame = false;
    var frames = document.querySelectorAll('iframe');
    for (var i = 0; i < frames.length; i++) {
      var src = frames[i].src || '';
      if (src.indexOf('challenges.cloudflare.com') !== -1 || src.indexOf('turnstile') !== -1) {
        challengeFrame = true;
      }
    }
    var challengeText = bodyText.indexOf('Verify you are human') !== -1 ||
                        (document.title || '').indexOf('Just a moment') !== -1;
    var songs = document.querySelectorAll('a[href*="/song/"]').length;
    var base = (typeof window.__sdBaseline === 'number') ? window.__sdBaseline : -1;
    var hasForm = !!document.querySelector('textarea');
    var signInVisible = Array.prototype.slice.call(document.querySelectorAll('a, button')).some(function (e) {
      var t = (e.textContent || '').trim().toLowerCase();
      return t === 'sign in' || t === 'log in' || t === 'sign up';
    });
    var state;
    if (challengeFrame || challengeText) {
      state = 'CHALLENGE: Cloudflare verification is showing';
    } else if (base >= 0 && songs > base) {
      state = 'RESULTS: ' + (songs - base) + ' new song(s) since create';
    } else if (hasForm) {
      state = 'FORM: create form visible';
    } else if (songs > 0) {
      state = 'LIBRARY: ' + songs + ' song link(s) on page';
    } else if (signInVisible) {
      state = 'SIGNED_OUT: sign-in prompt, no create form';
    } else {
      state = 'PAGE: ' + (document.title || location.pathname);
    }
    if (window.PocBridge && window.PocBridge.state) window.PocBridge.state(state);
  } catch (e) {}
})();
"""
}
