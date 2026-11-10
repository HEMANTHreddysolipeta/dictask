package com.hemanth.dictask.data

import android.content.Context
import com.hemanth.dictask.BuildConfig

class Prefs(ctx: Context) {
    private val p = ctx.applicationContext.getSharedPreferences("dictask", Context.MODE_PRIVATE)

    var groqKey: String
        get() = p.getString("groq_key", null)?.takeIf { it.isNotBlank() } ?: BuildConfig.GROQ_API_KEY
        set(v) = p.edit().putString("groq_key", v.trim()).apply()

    /** Stop recording by itself after a pause in speech. */
    var autoStop: Boolean
        get() = p.getBoolean("auto_stop", true)
        set(v) = p.edit().putBoolean("auto_stop", v).apply()

    /** Save dictated tasks straight away instead of showing the review screen. */
    var skipReview: Boolean
        get() = p.getBoolean("skip_review", false)
        set(v) = p.edit().putBoolean("skip_review", v).apply()

    /** "" = auto-detect, otherwise an ISO-639-1 code passed to Whisper. */
    var language: String
        get() = p.getString("language", "en") ?: "en"
        set(v) = p.edit().putString("language", v).apply()

    /** Names and terms Whisper should expect, comma separated. */
    var vocab: String
        get() = p.getString("vocab", "") ?: ""
        set(v) = p.edit().putString("vocab", v).apply()

    var digestOn: Boolean
        get() = p.getBoolean("digest_on", true)
        set(v) = p.edit().putBoolean("digest_on", v).apply()

    /** minutes after midnight */
    var digestMinute: Int
        get() = p.getInt("digest_minute", 8 * 60)
        set(v) = p.edit().putInt("digest_minute", v).apply()

    /** Reminder lead time for tasks that have a time but no reminder was said. -1 = no reminder. */
    var defaultRemind: Int
        get() = p.getInt("default_remind", 0)
        set(v) = p.edit().putInt("default_remind", v).apply()
}
