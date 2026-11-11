package com.hemanth.dictask.voice

import com.hemanth.dictask.data.Priority
import com.hemanth.dictask.data.Recurrence
import com.hemanth.dictask.data.Task
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.util.Locale
import java.util.concurrent.TimeUnit

enum class Kind { ADD, UPDATE, DONE, DELETE }

/**
 * One thing the model read out of the dictation, before the user confirms it: a new task,
 * or a change / completion / deletion of an existing one ([targetId]).
 * For UPDATE, empty or null fields mean "leave as it is".
 */
data class Draft(
    val title: String,
    val notes: String,
    val date: LocalDate?,
    val time: LocalTime?,
    /** null = keep (UPDATE) / none (ADD) */
    val priority: Int?,
    val list: String?,
    /** null = keep (UPDATE) / doesn't repeat (ADD) */
    val recurrence: Recurrence?,
    /** null = the user didn't ask for a reminder */
    val remindBefore: Int?,
    val kind: Kind = Kind.ADD,
    val targetId: Long? = null,
    /** The person explicitly said "am" / "in the morning" for this hour, so it must never move to pm. */
    val amSaid: Boolean = false,
) {
    /**
     * "by 10" said at 5:30 pm means 10 pm. Models sometimes return 10:00, which is already gone today,
     * so a morning time today that has passed (while its pm twin hasn't) is moved to pm,
     * unless "am" was actually said ("gym at 7am" stays 7 am; the date logic moves it to the next day).
     */
    private fun resolvedTime(): LocalTime? {
        val t = time ?: return null
        if (amSaid) return t
        val today = date == null || date == LocalDate.now()
        val now = LocalTime.now()
        return if (today && t.hour in 1..11 && t.isBefore(now) && t.plusHours(12).isAfter(now)) t.plusHours(12) else t
    }

    /** A time with no date means its next occurrence: "at 6:30" said at 5 pm is tomorrow morning. */
    private fun resolvedDate(rec: Recurrence): LocalDate? {
        var d = date
        val time = resolvedTime()
        if (d == null && time != null) {
            d = LocalDate.now()
            if (!time.isAfter(LocalTime.now())) d = d.plusDays(1)
            if (rec == Recurrence.WEEKDAYS) while (d!!.dayOfWeek.value >= 6) d = d.plusDays(1)
        }
        // A repeating task never starts in the past ("gym every weekday at 6:30" said in the evening).
        return d?.let { Recurrence.firstNotPast(rec, it, time) }
    }

    fun toTask(spoken: String, defaultRemind: Int): Task {
        val rec = recurrence ?: Recurrence.NONE
        return Task(
            title = title,
            notes = notes,
            dueEpochDay = resolvedDate(rec)?.toEpochDay(),
            dueMinute = resolvedTime()?.let { it.hour * 60 + it.minute },
            priority = priority ?: Priority.NONE,
            listName = list,
            recurrence = rec,
            remindBefore = if (time == null) null else remindBefore ?: defaultRemind.takeIf { it >= 0 },
            spokenText = spoken,
        )
    }

    /** The task as it will look after this change ("move the pitch deck to tomorrow"). */
    fun applyTo(t: Task, defaultRemind: Int): Task {
        val rec = recurrence ?: t.recurrence
        var day = t.dueDate
        var tm = t.dueTime
        if (date != null || time != null) {
            tm = resolvedTime() ?: t.dueTime
            day = date ?: t.dueDate ?: resolvedDate(rec)
            if (day != null) day = Recurrence.firstNotPast(rec, day, tm)
        }
        val remind = when {
            tm == null -> null
            remindBefore != null -> remindBefore
            t.remindBefore != null -> t.remindBefore
            t.dueTime == null -> defaultRemind.takeIf { it >= 0 } // a time was just added
            else -> null
        }
        return t.copy(
            title = title.ifBlank { t.title },
            notes = notes.ifBlank { t.notes },
            dueEpochDay = day?.toEpochDay(),
            dueMinute = tm?.let { it.hour * 60 + it.minute },
            priority = priority ?: t.priority,
            listName = list ?: t.listName,
            recurrence = rec,
            remindBefore = remind,
        )
    }
}

class GroqException(msg: String, val code: Int = 0) : IOException(msg)

object Groq {
    private const val BASE = "https://api.groq.com/openai/v1"
    const val STT_MODEL = "whisper-large-v3-turbo"
    const val PARSE_MODEL = "openai/gpt-oss-120b"
    const val FALLBACK_MODEL = "openai/gpt-oss-20b"

    private val silenceHallucinations = setOf(
        "thank you", "thanks", "thank you very much", "thanks for watching", "thank you for watching",
        "you", "bye", "okay", "ok", "so", "hmm", "uh", "um", "the", "a", "and", "i", "yeah", "oh", "please subscribe", "subtitles by the amara.org community",
    )

    private val http = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .build()

    /**
     * @param vocab the person's own words (names, work terms, list names). Whisper's prompt biases it
     * toward spelling these right, which matters most when the audio is soft or unclear.
     */
    suspend fun transcribe(
        file: File, key: String, language: String, vocab: List<String> = emptyList(), model: String = STT_MODEL,
    ): String = withContext(Dispatchers.IO) {
        val words = vocab.map { it.trim() }.filter { it.isNotEmpty() }.distinct().joinToString(", ").take(500)
        val prompt = "To-do list: remind me to call, buy, pay, email, meeting tomorrow at 5 pm, next Monday." +
            if (words.isNotEmpty()) " Words I use: $words." else ""
        val body = MultipartBody.Builder().setType(MultipartBody.FORM)
            .addFormDataPart("model", model)
            .addFormDataPart("response_format", "json")
            .addFormDataPart("temperature", "0")
            .addFormDataPart("prompt", prompt)
            .apply { if (language.isNotBlank()) addFormDataPart("language", language) }
            .addFormDataPart("file", file.name, file.asRequestBody("audio/wav".toMediaType()))
            .build()
        val json = call(Request.Builder().url("$BASE/audio/transcriptions").post(body), key)
        val text = json.optString("text").trim()
        // Whisper invents these on silence or noise (Groq's no_speech_prob stays 0, so it can't be used).
        val norm = text.lowercase().trim(' ', '.', '!', ',')
        if (norm in silenceHallucinations) "" else text
    }

    /** Checks a key with a free, tiny request. Returns null if it works, else a message for the person. */
    suspend fun checkKey(key: String): String? = withContext(Dispatchers.IO) {
        val k = key.trim()
        if (!k.startsWith("gsk_")) return@withContext "Groq keys start with gsk_. Copy the whole key."
        runCatching {
            http.newCall(Request.Builder().url("$BASE/models").header("Authorization", "Bearer $k").build()).execute().use { r ->
                when {
                    r.isSuccessful -> null
                    r.code == 401 -> "Groq says this key isn't valid. Copy it again from console.groq.com/keys."
                    else -> "Groq answered ${r.code}. Try again in a minute."
                }
            }
        }.getOrElse { "Can't reach Groq. Check your internet connection." }
    }

    /**
     * Reads new tasks and edits to [openTasks] out of what the person said.
     * Edits only ever point at ids that exist in [openTasks].
     */
    suspend fun parse(
        text: String,
        key: String,
        openTasks: List<Task>,
        now: LocalDateTime = LocalDateTime.now(),
    ): List<Draft> = withContext(Dispatchers.IO) {
        val knownLists = openTasks.mapNotNull { it.listName }.distinct()
        val day = now.dayOfWeek.getDisplayName(TextStyle.FULL, Locale.ENGLISH)
        // Models get weekday arithmetic wrong, so hand them a ready-made calendar to look dates up in.
        val calendar = (0..13).joinToString("\n") { i ->
            val d = now.toLocalDate().plusDays(i.toLong())
            val wd = d.dayOfWeek.getDisplayName(TextStyle.SHORT, Locale.ENGLISH)
            val tag = when (i) { 0 -> " today"; 1 -> " tomorrow"; in 7..13 -> " next $wd"; else -> "" }
            "$d $wd$tag"
        }
        val open = openTasks.take(60).joinToString("\n") { t ->
            listOfNotNull(
                t.id.toString(), t.title,
                t.dueDate?.let { d -> d.toString() + (t.dueTime?.let { " $it" } ?: "") },
                t.listName?.let { "#$it" },
            ).joinToString(" | ")
        }.ifEmpty { "none" }
        // Kept short on purpose: Groq's free tier caps tokens per day, and this goes out with every dictation.
        val system = """
Turn the user's words (often speech-to-text, so words may be misheard: "mail" may be "meal") into to-do actions.
Now: $day ${now.toLocalDate()} ${now.toLocalTime().withSecond(0).withNano(0)}.
Calendar - copy dates from here, never compute weekdays ("Friday" = first Fri below; "next Monday" = line marked next Mon):
$calendar
Lists: ${if (knownLists.isEmpty()) "none" else knownLists.joinToString()}
Open tasks (id | title | due | list):
$open
action:
- add: new to-do (task_id null). Several = several items. Nothing to do = [].
- update/complete/delete: about an OPEN task (task_id = its id). Words like change, move, reschedule, postpone, rename, edit, update, make it, push = update; done, finished, completed = complete; delete, remove, cancel, drop = delete. Match tasks by meaning or sound, not exact words. Never add a task that is really an edit of an open one.
fields:
- title: short English action in the user's words, no date/time words; drop "remind me to", "I need to". update: "" unless renamed. complete/delete: "".
- notes: extra details, else "".
- due_date YYYY-MM-DD, due_time HH:mm 24h, null if not said (update: null = keep). morning 09:00, afternoon 14:00, evening 18:00, tonight 20:00. Times without am/pm = next occurrence after Now (at 17:21 "9.30" = 21:30). "9.30 or 10" = first one. "by X" = due X. "this month" = last day of month.
- priority: high only for the task called urgent/important/asap; never copy it to other items. add default none; others default keep.
- list: only if the user names one, else null.
- recurrence: only if repeating was said; add default none, others keep. Repeating with a start ("1st of every month") → due_date = next such day.
- remind_minutes_before: 0 for "remind me" at a time, N for "N min before", else null.
- Each item keeps its own date/time. Ignore filler, false starts; later corrections win.
""".trimIndent()

        val item = JSONObject()
            .put("type", "object")
            .put("additionalProperties", false)
            .put("required", JSONArray(listOf("action", "task_id", "title", "notes", "due_date", "due_time", "priority", "list", "recurrence", "remind_minutes_before")))
            .put("properties", JSONObject()
                .put("action", JSONObject().put("type", "string").put("enum", JSONArray(listOf("add", "update", "complete", "delete"))))
                .put("task_id", JSONObject().put("type", JSONArray(listOf("integer", "null"))))
                .put("title", JSONObject().put("type", "string"))
                .put("notes", JSONObject().put("type", "string"))
                .put("due_date", JSONObject().put("type", JSONArray(listOf("string", "null"))))
                .put("due_time", JSONObject().put("type", JSONArray(listOf("string", "null"))))
                .put("priority", JSONObject().put("type", "string").put("enum", JSONArray(listOf("keep", "none", "low", "medium", "high"))))
                .put("list", JSONObject().put("type", JSONArray(listOf("string", "null"))))
                .put("recurrence", JSONObject().put("type", "string").put("enum", JSONArray(listOf("keep", "none", "daily", "weekdays", "weekly", "monthly", "yearly"))))
                .put("remind_minutes_before", JSONObject().put("type", JSONArray(listOf("integer", "null")))))
        val schema = JSONObject()
            .put("type", "object")
            .put("additionalProperties", false)
            .put("required", JSONArray(listOf("tasks")))
            .put("properties", JSONObject().put("tasks", JSONObject().put("type", "array").put("items", item)))

        val req = JSONObject()
            .put("model", PARSE_MODEL)
            .put("temperature", 0)
            .put("reasoning_effort", "low")
            .put("messages", JSONArray()
                .put(JSONObject().put("role", "system").put("content", system))
                .put(JSONObject().put("role", "user").put("content", text)))
            .put("response_format", JSONObject().put("type", "json_schema").put("json_schema",
                JSONObject().put("name", "tasks").put("strict", true).put("schema", schema)))

        fun ask(model: String) = call(
            Request.Builder().url("$BASE/chat/completions")
                .post(req.put("model", model).toString().toRequestBody("application/json".toMediaType())),
            key,
        )
        // 120b reads dates far more reliably; 20b is the fallback when 120b is rate-limited or busy.
        val json = try { ask(PARSE_MODEL) } catch (e: GroqException) {
            if (e.code == 401 || e.code == 0) throw e else ask(FALLBACK_MODEL)
        }
        val content = json.getJSONArray("choices").getJSONObject(0).getJSONObject("message").getString("content")
        val arr = JSONObject(content).getJSONArray("tasks")
        val ids = openTasks.map { it.id }.toSet()
        val parsed = (0 until arr.length()).mapNotNull { i ->
            val o = arr.getJSONObject(i)
            val kind = when (o.optString("action")) {
                "update" -> Kind.UPDATE; "complete" -> Kind.DONE; "delete" -> Kind.DELETE; else -> Kind.ADD
            }
            val target = if (o.isNull("task_id")) null else o.optLong("task_id")
            // An edit that points at no real task is dropped rather than guessed.
            if (kind != Kind.ADD && (target == null || target !in ids)) return@mapNotNull null
            val title = o.optString("title").trim()
            if (kind == Kind.ADD && title.isEmpty()) return@mapNotNull null
            val pr = o.optString("priority")
            val rc = o.optString("recurrence")
            Draft(
                kind = kind,
                targetId = if (kind == Kind.ADD) null else target,
                title = title.replaceFirstChar { it.uppercase() },
                notes = o.optString("notes").trim(),
                date = o.optStringOrNull("due_date")?.let { runCatching { LocalDate.parse(it) }.getOrNull() },
                time = o.optStringOrNull("due_time")?.let {
                    runCatching { LocalTime.parse(it, DateTimeFormatter.ofPattern("H:mm")) }.getOrNull()
                },
                priority = if (pr == "keep") null else Priority.parse(pr),
                list = o.optStringOrNull("list")?.trim()
                    ?.takeIf { it.isNotEmpty() && it.lowercase() !in setOf("keep", "none", "null", "unchanged") }
                    ?.let { l -> knownLists.firstOrNull { it.equals(l, ignoreCase = true) } ?: l.replaceFirstChar { it.uppercase() } },
                recurrence = if (rc == "keep") null else Recurrence.parse(rc),
                remindBefore = if (o.isNull("remind_minutes_before")) null else o.optInt("remind_minutes_before"),
            )
        }
        guardPriorities(text, repairEdits(parsed, openTasks))
            .map { d -> d.time?.let { d.copy(amSaid = saidAm(text, it.hour)) } ?: d }
    }

    /** True if [text] says this hour with am / a.m. / "in the morning" ("7am", "7:30 a.m.", "7 in the morning"). */
    internal fun saidAm(text: String, hour: Int): Boolean {
        if (hour !in 1..11) return false
        return Regex("\\b$hour(?:[:.]\\d{2})?\\s*(?:a\\.?\\s?m\\b\\.?|in the morning\\b)", RegexOption.IGNORE_CASE).containsMatchIn(text)
    }

    private val priorityWord = Regex(
        "\\b(urgent(ly)?|important|asap|a\\.s\\.a\\.p|priority|critical|immediately|right away)\\b",
        RegexOption.IGNORE_CASE,
    )

    /**
     * Models spread "it's urgent" from one task to every task in the sentence (both gpt-oss sizes do it,
     * and 120b even rewrites each task's words to include it). So priority is checked against what was
     * actually said, in code:
     *  - no priority word anywhere: no task gets a priority;
     *  - several tasks: each task is located in the sentence by its title words, the sentence is cut into
     *    one segment per task (the first also covers a leading "Urgent:"), and a priority stays only if a
     *    priority word is in that task's own segment.
     * If a task can't be located (e.g. an edit with no title), the model's answer is kept.
     * A dropped priority becomes none for a new task and "unchanged" for an edit.
     */
    internal fun guardPriorities(text: String, drafts: List<Draft>): List<Draft> {
        fun strip(d: Draft) = d.copy(priority = if (d.kind == Kind.ADD) Priority.NONE else null)
        fun hasPriority(d: Draft) = d.priority != null && d.priority != Priority.NONE
        if (drafts.none(::hasPriority)) return drafts
        if (!priorityWord.containsMatchIn(text)) return drafts.map { if (hasPriority(it)) strip(it) else it }
        if (drafts.size == 1) return drafts

        val lower = text.lowercase()
        var from = 0
        val anchors = drafts.map { d ->
            val keys = words(d.title)
            fun firstHit(start: Int) = keys.mapNotNull { k ->
                Regex("\\b${Regex.escape(k)}").find(lower, start)?.range?.first
            }.minOrNull()
            (firstHit(from) ?: firstHit(0))?.also { from = it + 1 }
        }
        if (anchors.any { it == null }) return drafts
        val starts = anchors.map { it!! }.sorted()
        return drafts.mapIndexed { i, d ->
            if (!hasPriority(d)) return@mapIndexed d
            val a = anchors[i]!!
            val begin = if (a == starts.first()) 0 else a
            val end = starts.firstOrNull { it > a } ?: text.length
            if (priorityWord.containsMatchIn(text.substring(begin, end))) d else strip(d)
        }
    }

    private val editVerb = Regex("^(change|move|reschedule|postpone|update|edit|shift|push|rename|make)\\b", RegexOption.IGNORE_CASE)
    private val stop = setOf("the", "a", "an", "to", "for", "of", "my", "and", "with", "on", "in", "at", "it", "task", "one", "that", "this")

    private fun words(s: String) = s.lowercase().split(Regex("[^a-z0-9]+")).filter { it.length >= 3 && it !in stop }

    /** Consonant skeleton, so misheard vowels still match: "mail" and "meal" are both "ml". */
    private fun skeleton(w: String) = w.filter { it !in "aeiouy" }

    private fun similar(a: String, b: String) = a == b || (a.length >= 4 && b.length >= 4 && (a.startsWith(b.take(4)) || b.startsWith(a.take(4)))) ||
        (skeleton(a).length >= 2 && skeleton(a) == skeleton(b))

    /** The open task an "add" titled like "Change the mail subscription" is really about, if any. */
    private fun matchOpenTask(title: String, open: List<Task>): Task? {
        val said = words(title.replace(editVerb, ""))
        if (said.isEmpty()) return null
        return open.map { t ->
            val tw = words(t.title)
            val hits = said.count { s -> tw.any { similar(s, it) } }
            t to (if (tw.isEmpty()) 0.0 else hits.toDouble() / minOf(tw.size, said.size)) + hits * 0.01
        }.filter { it.second >= 0.6 }.maxByOrNull { it.second }?.first
    }

    /**
     * Safety net for weaker models and misheard speech: "Change the mail subscription. <garbled> tomorrow 5 pm"
     * often comes back as two new tasks. The first becomes an edit of the matching open task and takes the
     * date/time from the fragment right after it.
     */
    private fun repairEdits(drafts: List<Draft>, open: List<Task>): List<Draft> {
        val out = mutableListOf<Draft>()
        var i = 0
        while (i < drafts.size) {
            val d = drafts[i]
            val fixed: Draft? = when {
                // "Change the X" as an add: really an edit of X.
                d.kind == Kind.ADD && editVerb.containsMatchIn(d.title) -> matchOpenTask(d.title, open)?.let { t ->
                    d.copy(kind = Kind.UPDATE, targetId = t.id, title = "", notes = "",
                        priority = if (d.priority == Priority.NONE) null else d.priority,
                        recurrence = if (d.recurrence == Recurrence.NONE) null else d.recurrence)
                }
                // An update whose "new title" is just the command ("Change the mail subscription") is not a rename.
                d.kind == Kind.UPDATE && editVerb.containsMatchIn(d.title) -> d.copy(title = "")
                else -> null
            }
            if (fixed == null) { out += d; i++; continue }
            var upd: Draft = fixed
            val changesNothing = upd.title.isBlank() && upd.date == null && upd.time == null && upd.priority == null &&
                upd.list == null && upd.recurrence == null && upd.remindBefore == null
            val next = drafts.getOrNull(i + 1)
            // An edit that changes nothing must have had its new date carried by the next (garbled) fragment.
            if (changesNothing && next != null && next.kind == Kind.ADD && (next.date != null || next.time != null)) {
                upd = upd.copy(date = next.date, time = next.time, remindBefore = next.remindBefore ?: upd.remindBefore)
                i++ // the fragment was only carrying the new date/time
            }
            out += upd
            i++
        }
        return out
    }

    private fun JSONObject.optStringOrNull(k: String): String? =
        if (isNull(k)) null else optString(k).takeIf { it.isNotBlank() && it != "null" }

    private fun call(b: Request.Builder, key: String): JSONObject {
        if (key.isBlank()) throw GroqException("Add your Groq API key in Settings first.")
        http.newCall(b.header("Authorization", "Bearer $key").build()).execute().use { r ->
            val s = r.body?.string().orEmpty()
            if (!r.isSuccessful) {
                val msg = runCatching { JSONObject(s).getJSONObject("error").getString("message") }.getOrNull()
                throw GroqException(
                    when (r.code) {
                        401 -> "Groq rejected the API key. Check it in Settings."
                        429 -> "Groq rate limit hit. Try again in a minute."
                        else -> "Groq error ${r.code}: ${msg ?: s.take(200)}"
                    },
                    r.code,
                )
            }
            return JSONObject(s)
        }
    }
}
