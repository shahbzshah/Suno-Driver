package com.megah.sunopoc

import android.os.Handler
import android.os.Looper
import android.webkit.JavascriptInterface
import android.webkit.WebView
import org.json.JSONObject

/**
 * SunoDriver — ALL Suno page automation lives in this one file.
 *
 * IMPORTANT (proof-of-concept): the selectors below are BEST-EFFORT guesses
 * based on how Suno's create page is generally structured (a description /
 * lyrics textarea, a style field, and a "Create" button). They WILL need to
 * be tuned after the first real-device run, when we can see what the live
 * DOM actually looks like. Every step reports back through the "PocBridge"
 * JavascriptInterface so failures are visible in the on-screen status log.
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
    // Step 1 + 2: fill the form, then click Create
    // ------------------------------------------------------------------

    fun fillAndCreate(webView: WebView, description: String, style: String) {
        val fillScript = buildFillScript(description, style)
        webView.evaluateJavascript(fillScript, null)
        // Give the page's framework a beat to register the input events and
        // enable the Create button before we try to click it.
        Handler(Looper.getMainLooper()).postDelayed({
            webView.evaluateJavascript(CREATE_CLICK_SCRIPT, null)
        }, 1200)
    }

    /**
     * Finds the description/lyrics field and the style field and fills them.
     *
     * Matching strategy (best-effort, tune after first device run):
     *  - Look at every visible textarea / text input.
     *  - Score each by keywords in its placeholder / aria-label / name /
     *    associated <label> text ("song description", "lyrics", "describe",
     *    for the description; "style", "genre" for the style).
     *  - Description prefers a <textarea>; style excludes the element that
     *    was already used for the description.
     *  - Fallbacks: first textarea for description; first unused field for
     *    style.
     * Values are set with the native prototype setter + input/change events
     * so React-style controlled inputs actually register them.
     */
    private fun buildFillScript(description: String, style: String): String {
        val descLiteral = JSONObject.quote(description)
        val styleLiteral = JSONObject.quote(style)
        return """
(function () {
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
  function candidates() {
    return Array.prototype.slice.call(
      document.querySelectorAll('textarea, input[type="text"], input:not([type])')
    ).filter(function (el) {
      return !el.disabled && el.type !== 'hidden' && el.offsetParent !== null;
    });
  }
  function findField(keywords, preferTextarea, exclude) {
    var els = candidates().filter(function (el) { return el !== exclude; });
    var best = null, bestScore = 0;
    els.forEach(function (el) {
      var t = fieldText(el);
      var score = 0;
      keywords.forEach(function (k) { if (t.indexOf(k) !== -1) score += 2; });
      if (preferTextarea && el.tagName === 'TEXTAREA') score += 1;
      if (score > bestScore) { bestScore = score; best = el; }
    });
    if (best && bestScore >= 2) return best;
    if (preferTextarea) {
      var ta = els.filter(function (e) { return e.tagName === 'TEXTAREA'; })[0];
      return ta || els[0] || null;
    }
    return els[0] || null;
  }
  function setValue(el, value) {
    var proto = el.tagName === 'TEXTAREA'
      ? window.HTMLTextAreaElement.prototype
      : window.HTMLInputElement.prototype;
    var desc = Object.getOwnPropertyDescriptor(proto, 'value');
    el.focus();
    if (desc && desc.set) { desc.set.call(el, value); } else { el.value = value; }
    el.dispatchEvent(new Event('input', { bubbles: true }));
    el.dispatchEvent(new Event('change', { bubbles: true }));
  }
  try {
    log('Fill: scanning ' + candidates().length + ' candidate field(s) on ' + location.pathname);
    var descEl = findField(['song description', 'lyrics', 'describe', 'description', 'prompt'], true, null);
    if (descEl) {
      setValue(descEl, $descLiteral);
      log('Fill: description/lyrics field FILLED (' + descEl.tagName + ', placeholder="' + (descEl.getAttribute('placeholder') || '') + '")');
    } else {
      log('Fill: description/lyrics field NOT FOUND');
    }
    var styleEl = findField(['style', 'genre'], false, descEl);
    if (styleEl) {
      setValue(styleEl, $styleLiteral);
      log('Fill: style field FILLED (' + styleEl.tagName + ', placeholder="' + (styleEl.getAttribute('placeholder') || '') + '")');
    } else {
      log('Fill: style field NOT FOUND (page may be in simple/description-only mode)');
    }
  } catch (e) {
    log('Fill: ERROR ' + e.message);
  }
})();
"""
    }

    /** Finds the Create button (a <button> whose text includes "Create") and clicks it. */
    private const val CREATE_CLICK_SCRIPT = """
(function () {
  function log(m) { try { window.PocBridge.log(m); } catch (e) {} }
  try {
    var btns = Array.prototype.slice.call(document.querySelectorAll('button'));
    var btn = btns.filter(function (b) {
      var t = (b.textContent || '').trim().toLowerCase();
      return t.indexOf('create') !== -1;
    })[0];
    if (!btn) { log('Create: button NOT FOUND'); return; }
    if (btn.disabled) { log('Create: button found but DISABLED (form may not have registered the fill)'); return; }
    btn.click();
    log('Create: button CLICKED — watch for Cloudflare / generation state');
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
    var hasForm = !!document.querySelector('textarea');
    var signInVisible = Array.prototype.slice.call(document.querySelectorAll('a, button')).some(function (e) {
      var t = (e.textContent || '').trim().toLowerCase();
      return t === 'sign in' || t === 'log in' || t === 'sign up';
    });
    var state;
    if (challengeFrame || challengeText) {
      state = 'CHALLENGE: Cloudflare verification is showing';
    } else if (songs > 0) {
      state = 'RESULTS: ' + songs + ' song link(s) on page';
    } else if (hasForm) {
      state = 'FORM: create form visible';
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
