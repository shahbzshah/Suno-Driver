package com.megah.sunopoc

import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors

/**
 * The "AI mind" of the app (phase 2): talks to the NVIDIA API
 * (OpenAI-compatible) with the channel rulebook baked in, and turns a
 * one-line song idea into a ready-to-paste Suno description + style pair.
 *
 * The rulebook below mirrors Megah's DeepSoundRealm / StillMind rules:
 * natural human language, hook first, short singable lines, and the hard
 * banned-word blacklist. A local scan double-checks the AI's output
 * against the blacklist and retries once if a banned word slips through.
 *
 * Framework-only (HttpURLConnection + org.json) — no external deps.
 */
object AiMind {

    data class SongPrompt(val title: String, val description: String, val style: String)

    enum class Channel(val label: String, val rulebook: String) {
        DEEP_SOUND_REALM(
            "DeepSoundRealm",
            """
            Channel: DeepSoundRealm — uplifting EDM-pop / tropical house LOVE songs.
            Sound: warm plucks, laid-back groove, soft emotive male vocals, romantic
            feel-good energy, around 100-110 bpm. Never name real artists.
            The song description must ask for the chorus/hook to come in right at
            the start (short intro), with short singable lines of about 6-8
            syllables and one repeatable hook line. The style string must be
            short and specific: genre, tempo, mood, vocal type, one or two
            signature sounds, plus the words "short intro, hook first".
            Existing song titles on this channel (never reuse a title or its
            hook phrase): Rise Above, Whispers of Love, Windows Down,
            Home Is You, Stay A Little Longer, Hold Me in the Summer.
            A title containing the word "Rise" is forbidden.
            """.trimIndent()
        ),
        STILL_MIND(
            "StillMind",
            """
            Channel: StillMind Music Official — meditation and relaxation music.
            Sound: peaceful ambient instrumental, soft piano, gentle pads, nature
            textures, very slow and calm, NO vocals and NO lyrics. The song
            description must ask for a seamless, steady, calming instrumental
            that can loop comfortably. The style string must be short and
            specific: ambient meditation, tempo/mood, and the main instruments,
            plus the words "instrumental, no vocals".
            """.trimIndent()
        )
    }

    // HARD blacklist from Megah's lyric rules (and close family).
    private val BANNED = listOf(
        "echoes", "echo", "fading", "fade", "shadow", "shadows", "twilight",
        "neon", "stars", "star", "starlight", "moonlight", "ashes", "ash",
        "glow", "glowing", "dawn"
    )

    // Existing channel titles — a new title must not be a near-miss of any
    // of these (Megah's duplicate rule: no reused titles or distinctive
    // hook phrases). "Hold Me Close" vs "Hold Me in the Summer" is exactly
    // the collision this guards against.
    private val EXISTING_TITLES = listOf(
        "Rise Above", "Whispers of Love", "Windows Down", "Home Is You",
        "Stay A Little Longer", "Hold Me in the Summer",
        "Tranquil Morning", "Inner Stillness"
    )

    private fun titleWords(title: String): List<String> =
        title.lowercase().replace(Regex("[^a-z0-9 ]"), " ")
            .split(Regex("\\s+")).filter { it.isNotEmpty() }

    // Function words carry no distinctiveness — bigrams and overlaps made
    // only of these ("in the", "is you") must never count as a collision.
    private val STOPWORDS = setOf(
        "in", "the", "a", "an", "of", "to", "and", "or", "on", "at", "by",
        "for", "with", "is", "it", "its"
    )

    /** Two-word phrases where at least one word is a real content word. */
    private fun distinctiveBigrams(words: List<String>): Set<String> =
        words.zipWithNext { a, b -> "$a $b" }
            .filter { phrase ->
                val (a, b) = phrase.split(" ")
                a !in STOPWORDS || b !in STOPWORDS
            }.toSet()

    /** Returns the existing title [title] collides with, or null if clear. */
    fun titleCollision(title: String): String? {
        if (title.isBlank()) return null
        val words = titleWords(title)
        val contentWords = words.filter { it !in STOPWORDS }.toSet()
        val myBigrams = distinctiveBigrams(words)
        for (existing in EXISTING_TITLES) {
            val eWords = titleWords(existing)
            // Shared distinctive two-word phrase (e.g. "hold me") = the
            // existing song's hook phrase — too close.
            if (myBigrams.intersect(distinctiveBigrams(eWords)).isNotEmpty()) return existing
            // Heavy content-word overlap also counts as a near-miss.
            val eContent = eWords.filter { it !in STOPWORDS }.toSet()
            val shared = contentWords.intersect(eContent).size
            val union = contentWords.union(eContent).size
            if (union > 0 && shared.toDouble() / union >= 0.5) return existing
        }
        return null
    }

    private const val API_URL = "https://integrate.api.nvidia.com/v1/chat/completions"
    private const val MODEL = "meta/llama-3.2-11b-vision-instruct"

    private val executor = Executors.newSingleThreadExecutor()

    private const val SYSTEM_PROMPT = """
You write Suno song prompts for Megah's music channels. You output ONLY a
JSON object, no markdown fences, no commentary:
{"title": "...", "description": "...", "style": "..."}

Rules that can never be broken:
- Natural human language, the way a real person talks. No poetic AI cliches.
- BANNED WORDS (never use any of these, in title, description or style):
  echoes, echo, fading, fade, shadow, shadows, twilight, neon, stars,
  starlight, moonlight, ashes, ash, glow, glowing, dawn.
  (The words night, midnight and light ARE allowed.)
- The hook comes first: ask for a short intro with the chorus landing
  immediately, so the song sits well in playlist mixes.
- Never name real artists, bands or existing songs.
- "description" is the song-description prompt pasted into Suno: what the
  song is about, its feeling and structure, in 2-4 plain sentences.
- "style" is ONE short comma-separated style string for Suno.
"""

    fun bannedWordsIn(text: String): List<String> {
        val lower = " " + text.lowercase().replace(Regex("[^a-z ]"), " ") + " "
        return BANNED.filter { lower.contains(" $it ") }
    }

    /**
     * Generates a prompt for [idea] on [channel]. Runs off the UI thread;
     * [onStatus] reports progress lines for the app's status log and
     * [onDone] fires exactly once with the result or the error message.
     */
    fun generate(
        apiKey: String,
        idea: String,
        channel: Channel,
        onStatus: (String) -> Unit,
        onDone: (SongPrompt?, String?) -> Unit
    ) {
        executor.execute {
            try {
                var correction = ""
                var attempt = 0
                while (true) {
                    attempt++
                    onStatus("AI: asking NVIDIA (${channel.label}) — attempt $attempt…")
                    val prompt = requestWithRetries(apiKey, idea, channel, correction, onStatus)
                        ?: run {
                            onDone(
                                null,
                                "NVIDIA didn't answer after several tries — their side is " +
                                    "slow right now. Nothing was created and no Suno " +
                                    "credits were used. Wait a minute and tap again."
                            )
                            return@execute
                        }
                    val allText = prompt.title + " " + prompt.description + " " + prompt.style
                    val bad = bannedWordsIn(allText)
                    if (bad.isNotEmpty()) {
                        if (attempt >= 3) {
                            onDone(null, "AI kept using banned word(s): ${bad.joinToString(", ")}. Try again.")
                            return@execute
                        }
                        onStatus("AI: banned word(s) slipped in (${bad.joinToString(", ")}) — asking for a rewrite…")
                        correction = "Your previous answer used banned word(s): " +
                            bad.joinToString(", ") + ". Rewrite without them."
                        continue
                    }
                    val collision = titleCollision(prompt.title)
                    if (collision != null) {
                        if (attempt >= 3) {
                            onDone(null, "AI kept titling it too close to \"$collision\". Try again.")
                            return@execute
                        }
                        onStatus("AI: title \"${prompt.title}\" is too close to existing \"$collision\" — asking for a different title…")
                        correction = "Your previous title \"${prompt.title}\" is too similar to the " +
                            "existing song \"$collision\" on this channel. Choose a clearly different " +
                            "title that shares no two-word phrase with it."
                        continue
                    }
                    onDone(prompt, null)
                    return@execute
                }
            } catch (e: Exception) {
                onDone(null, e.message ?: "Unknown AI error")
            }
        }
    }

    /**
     * One prompt request with transport resilience: NVIDIA occasionally
     * answers very slowly or not at all (Megah's log, 2026-10-21: a
     * single 60s timeout killed a whole create). Timeouts and I/O errors
     * retry up to 2 extra times before the create is allowed to fail.
     * Returns null only when every try failed on transport.
     */
    private fun requestWithRetries(
        apiKey: String,
        idea: String,
        channel: Channel,
        correction: String,
        onStatus: (String) -> Unit
    ): SongPrompt? {
        var tries = 0
        while (true) {
            try {
                return requestPrompt(apiKey, idea, channel, correction)
            } catch (e: Exception) {
                // A 4xx from NVIDIA (bad key, bad request) will never fix
                // itself by retrying — surface it immediately, as-is.
                val m = e.message ?: ""
                if (m.startsWith("NVIDIA API error 4") &&
                    !m.startsWith("NVIDIA API error 429")
                ) throw e
                tries++
                if (tries > 2) return null
                onStatus("AI: NVIDIA didn't answer in time — retrying (${tries + 1} of 3)…")
                try {
                    Thread.sleep(3000)
                } catch (ie: InterruptedException) {
                    return null
                }
            }
        }
    }

    private fun requestPrompt(
        apiKey: String,
        idea: String,
        channel: Channel,
        correction: String
    ): SongPrompt {
        val userMessage = buildString {
            append(channel.rulebook)
            append("\n\nSong idea from Megah: ").append(idea)
            if (correction.isNotEmpty()) append("\n\n").append(correction)
        }

        val messages = org.json.JSONArray()
            .put(JSONObject().put("role", "system").put("content", SYSTEM_PROMPT))
            .put(JSONObject().put("role", "user").put("content", userMessage))

        val body = JSONObject()
            .put("model", MODEL)
            .put("messages", messages)
            .put("temperature", 0.6)
            .put("max_tokens", 800)

        val connection = (URL(API_URL).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 30_000
            readTimeout = 120_000
            doOutput = true
            setRequestProperty("Content-Type", "application/json")
            setRequestProperty("Authorization", "Bearer $apiKey")
        }

        connection.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }

        val code = connection.responseCode
        val stream = if (code in 200..299) connection.inputStream else connection.errorStream
        val responseText = stream?.let {
            BufferedReader(InputStreamReader(it, Charsets.UTF_8)).use { r -> r.readText() }
        } ?: ""

        if (code !in 200..299) {
            val snippet = responseText.take(220).replace("\n", " ")
            throw RuntimeException("NVIDIA API error $code: $snippet")
        }

        val content = JSONObject(responseText)
            .getJSONArray("choices").getJSONObject(0)
            .getJSONObject("message").getString("content")

        // The model is told JSON-only, but strip fences just in case and
        // fall back to the outermost {...} if anything surrounds it.
        val cleaned = content.trim()
            .removePrefix("```json").removePrefix("```").removeSuffix("```").trim()
        val jsonText = if (cleaned.startsWith("{")) cleaned
            else cleaned.substring(cleaned.indexOf('{'), cleaned.lastIndexOf('}') + 1)

        val parsed = JSONObject(jsonText)
        val title = parsed.optString("title").trim()
        val description = parsed.optString("description").trim()
        val style = parsed.optString("style").trim()
        if (description.isEmpty() || style.isEmpty()) {
            throw RuntimeException("AI returned an incomplete prompt — try again.")
        }
        return SongPrompt(title, description, style)
    }
}
