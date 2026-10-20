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
  function candidates() {
    return Array.prototype.slice.call(
      document.querySelectorAll('textarea, input[type="text"], input:not([type])')
    ).filter(function (el) {
      return !el.disabled && el.type !== 'hidden' && el.offsetParent !== null;
    });
  }
  function isLyricsField(el) { return fieldText(el).indexOf('lyric') !== -1; }
  // A REAL song-description field: description wording, textarea preferred,
  // and NEVER a lyrics field.
  function strictDescField(exclude) {
    var els = candidates().filter(function (el) {
      return el !== exclude && !isLyricsField(el);
    });
    var best = null, bestScore = 0;
    els.forEach(function (el) {
      var t = fieldText(el);
      var score = 0;
      ['song description', 'describe', 'description'].forEach(function (k) {
        if (t.indexOf(k) !== -1) score += 2;
      });
      if (el.tagName === 'TEXTAREA') score += 1;
      if (score > bestScore) { bestScore = score; best = el; }
    });
    return (best && bestScore >= 2) ? best : null;
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
    window.__sdBaseline = document.querySelectorAll('a[href*="/song/"]').length;
    var titleWanted = $titleLiteral;
    var descText = $descLiteral;
    var styleText = $styleLiteral;

    var descEl = strictDescField(null);
    if (!descEl) {
      window.__sdAbort = true;
      log('Fill: ABORT — no Song Description field on this page. The description was NOT put in the Lyrics box and Create was NOT clicked, so no credits were spent. Open the Suno Page, switch Suno to Simple mode, then tap create again.');
      return;
    }
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
