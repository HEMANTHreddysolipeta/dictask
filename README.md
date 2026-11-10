# Dictask

A personal Android to-do app you can talk to. Say one or more tasks, and Whisper (via Groq) writes them down;
a small language model then splits them into separate tasks with dates, times, reminders, lists, priority and repeats.

## Ways to add a task

| From | How |
|---|---|
| Home screen widget | Mic button (dictate) or + (type) |
| Quick Settings | Pull down the shade → edit tiles → add **Dictate a task** |
| App icon | Long-press → **Dictate a task** (can be dragged out as its own home-screen icon) |
| Inside the app | Big mic button, or type in the bottom bar ("pay rent friday 10am" is understood too) |
| Any app | Share text → **Add to Dictask** |
| Morning summary | **Add by voice** button on the notification |

Example: *"Call the electrician tomorrow at 10, buy milk and eggs tonight, and pay the credit card bill by Friday, it's urgent"*
→ three tasks, each with its own date/time, the last one marked high priority.

## Features

- Today / Upcoming / All / Done views, grouped into Overdue, Today, Tomorrow, Next 7 days, Later, No date
- Lists (`# Work`, `# Shopping`) as filter chips, search, priority dots
- Swipe right = done, swipe left = delete (both with Undo)
- Reminders with **Done** and **Snooze 15 min** buttons right in the notification; exact-time alarms
- Repeating tasks (daily, weekdays, weekly, monthly, yearly): ticking one creates the next
- Morning summary notification (time is configurable)
- Widget: open tasks with tick boxes, overdue in red, due today in amber; tap a task to edit it
- Review screen after dictation (edit titles, untick ones you don't want), or turn on "Save without review"
- Auto-stops recording after ~2 s of silence; dynamic Material You colours, dark mode

## Setup

1. Put your Groq key in `local.properties`: `GROQ_API_KEY=gsk_...` (or paste it in the app's Settings).
2. `gradlew assembleDebug` and install `app/build/outputs/apk/debug/app-debug.apk`.

Tasks are stored only on the phone (Room/SQLite). Audio goes to Groq only while you dictate and the local
copy is deleted after the tasks are saved.

Models: `whisper-large-v3-turbo` for speech, `openai/gpt-oss-20b` (strict JSON schema) for splitting tasks.
