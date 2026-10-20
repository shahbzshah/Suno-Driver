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
