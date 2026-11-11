// Task rules shared by the widget and the main window. Mirrors the Android app (Task.kt, Groq.kt)
// so a dictation means the same thing on the laptop and the phone.

export const DAY = 86400000;
export const REC = ['NONE', 'DAILY', 'WEEKDAYS', 'WEEKLY', 'MONTHLY', 'YEARLY'];
export const REC_LABEL = { NONE: "Doesn't repeat", DAILY: 'Every day', WEEKDAYS: 'Every weekday', WEEKLY: 'Every week', MONTHLY: 'Every month', YEARLY: 'Every year' };
export const PRIORITY = ['None', 'Low', 'Medium', 'High'];
const WD_SHORT = ['Sun', 'Mon', 'Tue', 'Wed', 'Thu', 'Fri', 'Sat'];
const WD_FULL = ['Sunday', 'Monday', 'Tuesday', 'Wednesday', 'Thursday', 'Friday', 'Saturday'];
const MONTHS = ['Jan', 'Feb', 'Mar', 'Apr', 'May', 'Jun', 'Jul', 'Aug', 'Sep', 'Oct', 'Nov', 'Dec'];

// ---- dates: a date is a LocalDate.toEpochDay() number, a time is minutes after midnight ----
export const epochOf = (d = new Date()) => Math.round(Date.UTC(d.getFullYear(), d.getMonth(), d.getDate()) / DAY);
export const today = () => epochOf(new Date());
export const nowMinute = () => { const n = new Date(); return n.getHours() * 60 + n.getMinutes(); };
const utc = (ed) => new Date(ed * DAY);
export const iso = (ed) => utc(ed).toISOString().slice(0, 10);
export const weekday = (ed) => utc(ed).getUTCDay();
export function parseIso(s) {
  const m = /^(\d{4})-(\d{2})-(\d{2})$/.exec(s || '');
  return m ? Math.round(Date.UTC(+m[1], +m[2] - 1, +m[3]) / DAY) : null;
}
export const hhmm = (min) => `${String(Math.floor(min / 60)).padStart(2, '0')}:${String(min % 60).padStart(2, '0')}`;
export function parseHm(s) {
  const m = /^(\d{1,2}):(\d{2})$/.exec(s || '');
  if (!m || +m[1] > 23 || +m[2] > 59) return null;
  return +m[1] * 60 + +m[2];
}
export const localMillis = (ed, min) => { const d = utc(ed); return new Date(d.getUTCFullYear(), d.getUTCMonth(), d.getUTCDate(), 0, min || 0).getTime(); };

function addMonths(ed, n) {
  const d = utc(ed), y = d.getUTCFullYear(), m = d.getUTCMonth() + n, day = d.getUTCDate();
  const last = new Date(Date.UTC(y, m + 1, 0)).getUTCDate();
  return Math.round(Date.UTC(y, m, Math.min(day, last)) / DAY);
}

export function nextOf(rec, ed) {
  switch (rec) {
    case 'DAILY': return ed + 1;
    case 'WEEKDAYS': { let d = ed + 1; while (weekday(d) === 0 || weekday(d) === 6) d++; return d; }
    case 'WEEKLY': return ed + 7;
    case 'MONTHLY': return addMonths(ed, 1);
    case 'YEARLY': return addMonths(ed, 12);
    default: return ed;
  }
}

/** Moves a repeating task's date forward until its due moment is not in the past. */
export function firstNotPast(rec, ed, min) {
  if (!rec || rec === 'NONE') return ed;
  const t = today(), nm = nowMinute();
  const past = (d) => (min == null ? d < t : d * 1440 + min <= t * 1440 + nm);
  let d = ed, guard = 0;
  while (past(d) && guard++ < 5000) d = nextOf(rec, d);
  return d;
}

// ---- tasks ----
export const newUid = () => crypto.randomUUID().replace(/-/g, '');

export function blankTask(fields = {}) {
  const now = Date.now();
  return {
    uid: newUid(), title: '', notes: '', dueEpochDay: null, dueMinute: null, priority: 0, listName: null,
    recurrence: 'NONE', remindBefore: null, done: false, completedAt: null, createdAt: now, spokenText: null,
    updatedAt: now, ...fields,
  };
}

export function reminderAt(t) {
  if (t.dueEpochDay == null || t.dueMinute == null || t.remindBefore == null) return null;
  return localMillis(t.dueEpochDay, t.dueMinute) - t.remindBefore * 60000;
}

export function isOverdue(t) {
  if (t.done || t.dueEpochDay == null) return false;
  if (t.dueMinute == null) return t.dueEpochDay < today();
  return localMillis(t.dueEpochDay, t.dueMinute) < Date.now();
}

export function timeLabel(min) {
  const h = Math.floor(min / 60), m = min % 60, h12 = h % 12 === 0 ? 12 : h % 12;
  return `${h12}${m ? ':' + String(m).padStart(2, '0') : ''} ${h < 12 ? 'AM' : 'PM'}`;
}

export function dayLabel(ed) {
  const t = today();
  if (ed === t) return 'Today';
  if (ed === t + 1) return 'Tomorrow';
  if (ed === t - 1) return 'Yesterday';
  const d = utc(ed);
  if (ed > t && ed < t + 7) return WD_FULL[d.getUTCDay()];
  const s = `${WD_SHORT[d.getUTCDay()]} ${d.getUTCDate()} ${MONTHS[d.getUTCMonth()]}`;
  return d.getUTCFullYear() === new Date().getFullYear() ? s : `${s} ${d.getUTCFullYear()}`;
}

export function dueLabel(t) {
  if (t.dueEpochDay == null) return '';
  return dayLabel(t.dueEpochDay) + (t.dueMinute != null ? ', ' + timeLabel(t.dueMinute) : '');
}

/** Same order as the phone: undone first, by date, all-day after timed, then priority. */
export function sortTasks(list) {
  const k = (t) => [t.done ? 1 : 0, t.dueEpochDay == null ? 1 : 0, t.dueEpochDay ?? 0, t.dueMinute == null ? 1 : 0, t.dueMinute ?? 0, -(t.priority || 0), t.createdAt || 0];
  return [...list].sort((a, b) => { const x = k(a), y = k(b); for (let i = 0; i < x.length; i++) if (x[i] !== y[i]) return x[i] - y[i]; return 0; });
}

export function section(t) {
  const d = t.dueEpochDay, td = today();
  if (d == null) return 'No date';
  if (isOverdue(t)) return 'Overdue';
  if (d === td) return 'Today';
  if (d === td + 1) return 'Tomorrow';
  if (d < td + 7) return 'Next 7 days';
  return 'Later';
}

// ---- drafts (what the model read out of a dictation) ----
// { kind: ADD|UPDATE|DONE|DELETE, target: uid|null, title, notes, date, time, priority, list, recurrence, remindBefore }
// For UPDATE, null/empty fields mean "keep".

function resolvedTime(d) {
  const t = d.time;
  if (t == null) return null;
  const isToday = d.date == null || d.date === today();
  const nm = nowMinute(), h = Math.floor(t / 60);
  // "by 10" said at 5:30 pm means 10 pm, so a passed morning time today whose pm twin is still ahead moves to pm,
  // unless "am" was actually said ("gym at 7am" stays 7 am; the date logic moves it to the next day).
  if (d.amSaid) return t;
  return isToday && h >= 1 && h <= 11 && t < nm && t + 720 > nm ? t + 720 : t;
}

function resolvedDate(d, rec) {
  let day = d.date;
  const time = resolvedTime(d);
  if (day == null && time != null) {
    day = today();
    if (!(time > nowMinute())) day++;
    if (rec === 'WEEKDAYS') while (weekday(day) === 0 || weekday(day) === 6) day++;
  }
  return day == null ? null : firstNotPast(rec, day, time);
}

export function draftToTask(d, spoken, defaultRemind) {
  const rec = d.recurrence || 'NONE';
  return blankTask({
    title: d.title, notes: d.notes || '',
    dueEpochDay: resolvedDate(d, rec), dueMinute: resolvedTime(d),
    priority: d.priority ?? 0, listName: d.list || null, recurrence: rec,
    remindBefore: d.time == null ? null : d.remindBefore ?? (defaultRemind >= 0 ? defaultRemind : null),
    spokenText: spoken,
  });
}

export function applyDraft(d, t, defaultRemind) {
  const rec = d.recurrence || t.recurrence;
  let day = t.dueEpochDay, tm = t.dueMinute;
  if (d.date != null || d.time != null) {
    tm = resolvedTime(d) ?? t.dueMinute;
    day = d.date ?? t.dueEpochDay ?? resolvedDate(d, rec);
    if (day != null) day = firstNotPast(rec, day, tm);
  }
  let remind = null;
  if (tm == null) remind = null;
  else if (d.remindBefore != null) remind = d.remindBefore;
  else if (t.remindBefore != null) remind = t.remindBefore;
  else if (t.dueMinute == null) remind = defaultRemind >= 0 ? defaultRemind : null; // a time was just added
  return {
    ...t, title: (d.title || '').trim() || t.title, notes: (d.notes || '').trim() || t.notes,
    dueEpochDay: day, dueMinute: tm, priority: d.priority ?? t.priority, listName: d.list ?? t.listName,
    recurrence: rec, remindBefore: remind,
  };
}

// ---- the parse request: identical prompt and schema to the phone ----

/** @param open open tasks; each gets a short number for the model to point at. */
export function buildParseRequest(text, open, now = new Date()) {
  const lists = [...new Set(open.map((t) => t.listName).filter(Boolean))];
  const td = epochOf(now);
  const calendar = Array.from({ length: 14 }, (_, i) => {
    const d = td + i, wd = WD_SHORT[weekday(d)];
    const tag = i === 0 ? ' today' : i === 1 ? ' tomorrow' : i >= 7 ? ` next ${wd}` : '';
    return `${iso(d)} ${wd}${tag}`;
  }).join('\n');
  const ids = new Map();
  const openLines = open.slice(0, 60).map((t, i) => {
    ids.set(i + 1, t.uid);
    const parts = [String(i + 1), t.title];
    if (t.dueEpochDay != null) parts.push(iso(t.dueEpochDay) + (t.dueMinute != null ? ' ' + hhmm(t.dueMinute) : ''));
    if (t.listName) parts.push('#' + t.listName);
    return parts.join(' | ');
  }).join('\n') || 'none';
  const nowStr = `${WD_FULL[now.getDay()]} ${iso(td)} ${hhmm(now.getHours() * 60 + now.getMinutes())}`;
  const system = `Turn the user's words (often speech-to-text, so words may be misheard: "mail" may be "meal") into to-do actions.
Now: ${nowStr}.
Calendar - copy dates from here, never compute weekdays ("Friday" = first Fri below; "next Monday" = line marked next Mon):
${calendar}
Lists: ${lists.length ? lists.join(', ') : 'none'}
Open tasks (id | title | due | list):
${openLines}
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
- Each item keeps its own date/time. Ignore filler, false starts; later corrections win.`;
  const N = ['string', 'null'];
  const item = {
    type: 'object', additionalProperties: false,
    required: ['action', 'task_id', 'title', 'notes', 'due_date', 'due_time', 'priority', 'list', 'recurrence', 'remind_minutes_before'],
    properties: {
      action: { type: 'string', enum: ['add', 'update', 'complete', 'delete'] }, task_id: { type: ['integer', 'null'] },
      title: { type: 'string' }, notes: { type: 'string' }, due_date: { type: N }, due_time: { type: N },
      priority: { type: 'string', enum: ['keep', 'none', 'low', 'medium', 'high'] }, list: { type: N },
      recurrence: { type: 'string', enum: ['keep', 'none', 'daily', 'weekdays', 'weekly', 'monthly', 'yearly'] },
      remind_minutes_before: { type: ['integer', 'null'] },
    },
  };
  const body = {
    temperature: 0, reasoning_effort: 'low',
    messages: [{ role: 'system', content: system }, { role: 'user', content: text }],
    response_format: { type: 'json_schema', json_schema: { name: 'tasks', strict: true, schema: { type: 'object', additionalProperties: false, required: ['tasks'], properties: { tasks: { type: 'array', items: item } } } } },
  };
  return { body, ids, lists };
}

const parsePriority = (s) => ({ high: 3, urgent: 3, medium: 2, low: 1 })[(s || '').toLowerCase()] ?? 0;
const parseRec = (s) => ({ daily: 'DAILY', weekdays: 'WEEKDAYS', weekly: 'WEEKLY', monthly: 'MONTHLY', yearly: 'YEARLY' })[(s || '').toLowerCase()] ?? 'NONE';
const cap = (s) => (s ? s[0].toUpperCase() + s.slice(1) : s);
const str = (v) => (v == null || v === 'null' || String(v).trim() === '' ? null : String(v).trim());

export function readDrafts(content, ids, lists, open, text = '') {
  const arr = JSON.parse(content).tasks || [];
  const out = [];
  for (const o of arr) {
    const kind = { update: 'UPDATE', complete: 'DONE', delete: 'DELETE' }[o.action] || 'ADD';
    const target = o.task_id == null ? null : ids.get(o.task_id);
    if (kind !== 'ADD' && !target) continue; // an edit that points at no real task is dropped, not guessed
    const title = (o.title || '').trim();
    if (kind === 'ADD' && !title) continue;
    let list = str(o.list);
    if (list && ['keep', 'none', 'null', 'unchanged'].includes(list.toLowerCase())) list = null;
    if (list) list = lists.find((l) => l.toLowerCase() === list.toLowerCase()) || cap(list);
    out.push({
      kind, target: kind === 'ADD' ? null : target, title: cap(title), notes: (o.notes || '').trim(),
      date: parseIso(str(o.due_date)), time: parseHm(str(o.due_time)),
      priority: o.priority === 'keep' ? null : parsePriority(o.priority), list,
      recurrence: o.recurrence === 'keep' ? null : parseRec(o.recurrence),
      remindBefore: o.remind_minutes_before == null ? null : o.remind_minutes_before | 0,
    });
  }
  return guardPriorities(text, repairEdits(out, open))
    .map((d) => (d.time != null ? { ...d, amSaid: saidAm(text, Math.floor(d.time / 60)) } : d));
}

/** True if text says this hour with am / a.m. / "in the morning" ("7am", "7:30 a.m.", "7 in the morning"). */
export function saidAm(text, hour) {
  if (hour < 1 || hour > 11) return false;
  return new RegExp(`\\b${hour}(?:[:.]\\d{2})?\\s*(?:a\\.?\\s?m\\b\\.?|in the morning\\b)`, 'i').test(text);
}

// ---- "it's urgent" belongs to one task, not all of them (same as the phone's Groq.guardPriorities) ----
const PRIORITY_WORD = /\b(urgent(ly)?|important|asap|a\.s\.a\.p|priority|critical|immediately|right away)\b/i;

/**
 * Models spread a priority word to every task in the sentence. A priority stays only if a priority word
 * was said at all and, with several tasks, falls in that task's own stretch of the sentence (each task is
 * located by its title words; the first stretch also covers a leading "Urgent:"). Tasks that can't be
 * located keep the model's answer.
 */
export function guardPriorities(text, drafts) {
  const has = (d) => d.priority != null && d.priority !== 0;
  const strip = (d) => ({ ...d, priority: d.kind === 'ADD' ? 0 : null });
  if (!drafts.some(has)) return drafts;
  if (!PRIORITY_WORD.test(text)) return drafts.map((d) => (has(d) ? strip(d) : d));
  if (drafts.length === 1) return drafts;
  const lower = text.toLowerCase();
  let from = 0;
  const firstHit = (keys, start) => {
    const hits = keys.map((k) => { const re = new RegExp('\\b' + k.replace(/[.*+?^${}()|[\]\\]/g, '\\$&'), 'g'); re.lastIndex = start; const m = re.exec(lower); return m ? m.index : null; }).filter((x) => x != null);
    return hits.length ? Math.min(...hits) : null;
  };
  const anchors = drafts.map((d) => {
    const keys = words(d.title);
    const a = firstHit(keys, from) ?? firstHit(keys, 0);
    if (a != null) from = a + 1;
    return a;
  });
  if (anchors.some((a) => a == null)) return drafts;
  const starts = [...anchors].sort((x, y) => x - y);
  return drafts.map((d, i) => {
    if (!has(d)) return d;
    const a = anchors[i];
    const begin = a === starts[0] ? 0 : a;
    const end = starts.find((s) => s > a) ?? text.length;
    return PRIORITY_WORD.test(text.slice(begin, end)) ? d : strip(d);
  });
}

// ---- safety net for weaker models and misheard speech (same as the phone) ----
const EDIT_VERB = /^(change|move|reschedule|postpone|update|edit|shift|push|rename|make)\b/i;
const STOP = new Set(['the', 'a', 'an', 'to', 'for', 'of', 'my', 'and', 'with', 'on', 'in', 'at', 'it', 'task', 'one', 'that', 'this']);
const words = (s) => s.toLowerCase().split(/[^a-z0-9]+/).filter((w) => w.length >= 3 && !STOP.has(w));
const skeleton = (w) => [...w].filter((c) => !'aeiouy'.includes(c)).join('');
const similar = (a, b) => a === b || (a.length >= 4 && b.length >= 4 && (a.startsWith(b.slice(0, 4)) || b.startsWith(a.slice(0, 4)))) || (skeleton(a).length >= 2 && skeleton(a) === skeleton(b));

function matchOpenTask(title, open) {
  const said = words(title.replace(EDIT_VERB, ''));
  if (!said.length) return null;
  let best = null, bestScore = 0;
  for (const t of open) {
    const tw = words(t.title);
    const hits = said.filter((s) => tw.some((w) => similar(s, w))).length;
    const score = (tw.length ? hits / Math.min(tw.length, said.length) : 0) + hits * 0.01;
    if (score >= 0.6 && score > bestScore) { best = t; bestScore = score; }
  }
  return best;
}

function repairEdits(drafts, open) {
  const out = [];
  for (let i = 0; i < drafts.length; i++) {
    const d = drafts[i];
    let fixed = null;
    if (d.kind === 'ADD' && EDIT_VERB.test(d.title)) {
      const t = matchOpenTask(d.title, open);
      if (t) fixed = { ...d, kind: 'UPDATE', target: t.uid, title: '', notes: '', priority: d.priority === 0 ? null : d.priority, recurrence: d.recurrence === 'NONE' ? null : d.recurrence };
    } else if (d.kind === 'UPDATE' && EDIT_VERB.test(d.title)) fixed = { ...d, title: '' };
    if (!fixed) { out.push(d); continue; }
    const nothing = !fixed.title && fixed.date == null && fixed.time == null && fixed.priority == null && fixed.list == null && fixed.recurrence == null && fixed.remindBefore == null;
    const next = drafts[i + 1];
    if (nothing && next && next.kind === 'ADD' && (next.date != null || next.time != null)) {
      fixed = { ...fixed, date: next.date, time: next.time, remindBefore: next.remindBefore ?? fixed.remindBefore };
      i++; // that fragment only carried the new date/time
    }
    out.push(fixed);
  }
  return out;
}

export function summary(c) {
  const n = (k, w) => `${k} ${w}${k === 1 ? '' : 's'}`;
  return [c.added && 'Added ' + n(c.added, 'task'), c.changed && 'Updated ' + n(c.changed, 'task'), c.done && 'Completed ' + n(c.done, 'task'), c.deleted && 'Deleted ' + n(c.deleted, 'task')].filter(Boolean).join(', ') || 'Nothing changed';
}
