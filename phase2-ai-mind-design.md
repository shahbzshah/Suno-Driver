# Suno driver app — Phase 2 design: the AI mind

Status: DESIGN DRAFT (reversible), for use only when Megah approves phase 2.
Prepared 2026-10-20 ~15:50 EAT.

## Why this design
Megah asked how the app gets "a mind of AI so it can work as you even when am not near" (2026-10-20). The PoC passed the same day: song generated with no error, no Cloudflare challenge inside the WebView on his device. So phase 2 is unblocked — the remaining decision is where the AI lives.

## Architecture: server-side AI mind, NOT key-in-app
The app does NOT call NVIDIA directly and does NOT hold his API key. Instead:

1. New route on The-Tool (workflow-app), e.g. `POST /api/suno/prompt`, protected by his existing JWT session.
2. The route injects the **channel rulebook** as the system prompt (single source of truth, see below) and calls the existing NVIDIA adapter `src/lib/ai.ts`, which reads the key from the encrypted DB setting `NVIDIA_API_KEY` (env fallback). Verified present 2026-10-20.
3. The Android app sends `{idea, channel}` and gets back `{descriptionPrompt, stylePrompt}`, then drives Suno with Fill & Create exactly as the PoC proved.

Why: the raw key never lives on the phone (losable, rootable, sniffable); the rulebook updates without an app rebuild/re-sign; the same key powers Refine/Hunter already. The PoC proved the driver works; this reuses the exact fill+create path.

## The rulebook (baked into the route's system prompt)
Source: ~/MEMORY.md §2–3 (channel rules), verified standing 2026-10-20.
- Lyric rules: natural human language; big feelings allowed (think-wide); hook-first (short intro, chorus lands in first 30s); mix-friendly; singability (short lines, ~6–8 syllables, near-rhymes OK, one repeatable hook line per chorus).
- HARD banned-word blacklist: echoes, fading, shadow(s), twilight, neon, stars, moonlight, ashes, glow, dawn (and family). Scan every output; rewrite any line containing one. (night, midnight, light are ALLOWED.)
- Styles: DeepSoundRealm = tropical house / melodic EDM pop, warm marimba plucks, laid-back groove, 104 bpm, soft emotive male vocals, romantic feel-good vibe, short intro. StillMind = per-theme ambient styles from ~/workspace/meditation-channel/suno_prompts_batch1.md.
- Never name real artists in prompts. Megah's Suno login is Premium → commercial-use license; every upload ticks AI disclosure.
- Output contract: short description prompt + short style prompt, plain text only.

## Flow when he's away (phase 2 v1)
1. App: type idea ("new love song") or pick a queued idea → calls the route → gets prompts.
2. App: Fill & Create in WebView (supervised — same button as PoC).
3. Takes download to phone. He picks the take later; MP3 comes to Ziggy; Ziggy does QC + cover + video + upload. Same split as today.

Deliberately NOT in v1: fully-headless unattended generation. Suno sessions expire and Cloudflare can still challenge; keep the human tap in the loop until several weeks of supervised runs prove stability.

## BLOCKER before wiring anything: rotate the NVIDIA key
The NVIDIA key was pasted in chat on 2026-10-09 (twice) — burned and live for 11 days. Rotation nudge sent 2026-10-14, never confirmed. Phase 2 must not be wired to a compromised key.
- His step (1 min, no dashboard navigation beyond one screen): build.nvidia.com → rotate → paste into The-Tool → Settings → NVIDIA API Key → Save (AES-256-GCM encrypted, env fallback respected).
- Builder's first step after his go: confirm the new key answers once via the new route, then build the app call.

## Open questions for the build
- Auth between app and route: reuse The-Tool's JWT cookie (WebView in the app can share the session) — simplest; details at build time.
- Model: default `meta/llama-3.2-11b-vision-instruct` answered in ~3s on his account (2026-10-08 live test) — fine for short prompt generation; overridable via NVIDIA_MODEL.
