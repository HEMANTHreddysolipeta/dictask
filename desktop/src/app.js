import * as S from './store.js';
import * as L from './logic.js';
import { record, listMics, meter } from './voice.js';

const api = window.dictask;
const params = new URLSearchParams(location.search);
const MODE = params.get('mode') || 'main';
document.body.className = MODE;
const root = document.getElementById('root');

const esc = (s) => String(s ?? '').replace(/[&<>"']/g, (c) => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' })[c]);
const ICONS = {
  mic: '<path d="M12 14a3 3 0 0 0 3-3V5a3 3 0 0 0-6 0v6a3 3 0 0 0 3 3Zm5-3a5 5 0 0 1-10 0H5a7 7 0 0 0 6 6.92V21h2v-3.08A7 7 0 0 0 19 11h-2Z"/>',
  plus: '<path d="M11 5h2v6h6v2h-6v6h-2v-6H5v-2h6z"/>',
  open: '<path d="M14 3h7v7h-2V6.41l-9.29 9.3-1.42-1.42 9.3-9.29H14V3ZM5 5h6v2H5v12h12v-6h2v6a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2V7a2 2 0 0 1 2-2Z"/>',
  close: '<path d="m6.4 5 5.6 5.6L17.6 5 19 6.4 13.4 12l5.6 5.6-1.4 1.4-5.6-5.6L6.4 19 5 17.6l5.6-5.6L5 6.4z"/>',
  stop: '<path d="M7 7h10v10H7z"/>',
};
const icon = (n) => `<svg class="icon" viewBox="0 0 24 24" fill="currentColor">${ICONS[n]}</svg>`;

let settings = {};
let hotkey = 'Ctrl+Shift+Space';
let peekKey = 'Ctrl+Alt+Space';
const ui = { tab: 'today', list: null, search: '', selected: null, view: 'tasks', voice: null, toast: null, authMsg: '', busy: false };

// ---------------------------------------------------------------- rendering helpers

function rowHtml(t) {
  const due = L.dueLabel(t);
  const cls = L.isOverdue(t) ? 'overdue' : t.dueEpochDay === L.today() && !t.done ? 'today' : '';
  const meta = [
    due && `<span class="${cls}">${esc(due)}</span>`,
    t.recurrence && t.recurrence !== 'NONE' && `<span>↻ ${esc(L.REC_LABEL[t.recurrence])}</span>`,
    t.remindBefore != null && t.dueMinute != null && '<span>🔔</span>',
    t.listName && `<span class="tag">#${esc(t.listName)}</span>`,
  ].filter(Boolean).join('');
  return `<div class="row ${t.done ? 'done' : ''} ${ui.selected === t.uid ? 'sel' : ''}" data-act="select" data-uid="${t.uid}">
    <button class="check p${t.priority || 0} ${t.done ? 'on' : ''}" data-act="toggle" data-uid="${t.uid}" title="${t.done ? 'Mark not done' : 'Mark done'}"></button>
    <div class="body"><div class="title">${esc(t.title)}</div>${meta ? `<div class="meta">${meta}</div>` : ''}</div>
  </div>`;
}

function sectioned(list) {
  const order = ['Overdue', 'Today', 'Tomorrow', 'Next 7 days', 'Later', 'No date'];
  const groups = new Map();
  for (const t of L.sortTasks(list)) { const s = L.section(t); if (!groups.has(s)) groups.set(s, []); groups.get(s).push(t); }
  return order.filter((s) => groups.has(s)).map((s) => `<div class="sec ${s === 'Overdue' ? 'overdue' : ''}">${s}</div>${groups.get(s).map(rowHtml).join('')}`).join('');
}

function counts() {
  const open = S.open(), td = L.today();
  const overdue = open.filter(L.isOverdue).length;
  const dueToday = open.filter((t) => t.dueEpochDay === td && !L.isOverdue(t)).length;
  return { open: open.length, overdue, dueToday };
}

function signInHtml(compact) {
  return `<form class="signin" data-form="signin">
    ${compact ? '<b>Sign in to see your tasks</b>' : '<h2>Sign in to Dictask</h2>'}
    <div class="muted small">Use the same email and password as in the phone app (Settings → Sync with laptop). New? Create an account here, then sign in on the phone with it.</div>
    <input name="email" type="email" placeholder="Email" autocomplete="username" required>
    <input name="password" type="password" placeholder="Password (6+ characters)" autocomplete="current-password" required>
    ${ui.authMsg ? `<div class="error">${esc(ui.authMsg)}</div>` : ''}
    <div style="display:flex;gap:8px"><button class="btn" data-act="signin" ${ui.busy ? 'disabled' : ''}>Sign in</button>
    <button class="btn ghost" type="button" data-act="create" ${ui.busy ? 'disabled' : ''}>Create account</button></div>
  </form>`;
}

// ---------------------------------------------------------------- widget

function renderWidget() {
  if (!S.authKnown) { root.innerHTML = '<div class="w"></div>'; return; }
  const head = (extra) => `<div class="w-head"><span class="name">Dictask</span><span class="counts">${extra}</span>
    <button class="iconbtn" data-act="open" title="Open Dictask">${icon('open')}</button>
    <button class="iconbtn mic" data-act="dictate" title="Dictate (${hotkey})">${icon('mic')}</button>
    <button class="iconbtn hide-x" data-act="hidewidget" title="Hide widget (opening Dictask brings it back)">${icon('close')}</button></div>`;
  if (!S.account()) { root.innerHTML = `<div class="w">${head('')}${signInHtml(true)}</div>`; return; }
  const c = counts();
  const bits = [c.overdue && `${c.overdue} overdue`, c.dueToday && `${c.dueToday} today`, !c.overdue && !c.dueToday && `${c.open} open`].filter(Boolean).join(' · ');
  const listEl = document.querySelector('.w-list');
  const scroll = listEl ? listEl.scrollTop : 0;
  const addEl = document.querySelector('.w-add input');
  const typed = addEl ? addEl.value : '';
  const hadFocus = document.activeElement === addEl;
  const open = S.open();
  root.innerHTML = `<div class="w">${head(esc(bits))}
    <div class="w-list">${!S.ready ? '<div class="empty">Loading…</div>' : open.length ? sectioned(open) : `<div class="empty">Nothing to do 🎉<br><span class="small">Press <kbd>${hotkey}</kbd> and say a task</span></div>`}</div>
    <form class="w-add" data-form="quick"><input name="q" placeholder="Add a task… e.g. pay rent friday" autocomplete="off">
      <select class="wmic" data-vmic data-short title="Microphone for dictation">${micOptions(true)}</select></form>
    ${voiceHtml()}${toastHtml()}</div>`;
  document.querySelector('.w-list').scrollTop = scroll;
  const ni = document.querySelector('.w-add input');
  ni.value = typed; if (hadFocus) { ni.focus(); ni.setSelectionRange(typed.length, typed.length); }
}

// ---------------------------------------------------------------- main window

const TABS = [['today', 'Today'], ['upcoming', 'Upcoming'], ['all', 'All open'], ['done', 'Done']];

function tabTasks(tab) {
  const td = L.today();
  const all = S.tasks;
  if (tab === 'done') return all.filter((t) => t.done).sort((a, b) => (b.completedAt || 0) - (a.completedAt || 0));
  const open = all.filter((t) => !t.done);
  if (tab === 'today') return open.filter((t) => t.dueEpochDay != null && t.dueEpochDay <= td);
  if (tab === 'upcoming') return open.filter((t) => t.dueEpochDay != null && t.dueEpochDay > td);
  return open;
}

function renderMain() {
  const editorFocused = document.querySelector('.editor')?.contains(document.activeElement);
  const settingsFocused = document.querySelector('.settings')?.contains(document.activeElement);
  if (!S.authKnown) { root.innerHTML = ''; return; }
  if (!S.account()) { root.innerHTML = `<div style="display:flex;height:100%">${signInHtml(false)}</div>${toastHtml()}`; return; }
  if (ui.view === 'settings' && settingsFocused) return; // don't wipe what is being typed
  const lists = [...new Set(S.open().map((t) => t.listName).filter(Boolean))].sort();
  const nav = TABS.map(([k, n]) => `<button class="nav ${ui.view === 'tasks' && ui.tab === k ? 'on' : ''}" data-act="tab" data-tab="${k}">${n}<span class="n">${k === 'done' ? '' : tabTasks(k).length || ''}</span></button>`).join('');
  const sel = ui.selected && S.get(ui.selected);
  if (ui.selected && !sel) ui.selected = null;

  if (editorFocused && sel && ui.view === 'tasks') { // keep the editor as is; refresh only the list
    const ml = document.querySelector('.mlist'); if (ml) ml.innerHTML = mainListHtml();
    return;
  }
  const scroll = document.querySelector('.mlist')?.scrollTop || 0;
  const q = document.querySelector('.bar .q'); const typed = q ? q.value : ''; const qFocus = document.activeElement === q;
  const sq = document.querySelector('.bar .search'); const sFocus = document.activeElement === sq;
  root.innerHTML = `<div class="m ${sel && ui.view === 'tasks' ? 'editing' : ''}">
    <aside class="side"><div class="brand"><img src="icon.png" alt="">Dictask</div>${nav}
      <div class="grow"></div>
      <button class="nav" data-act="togglewidget" title="The task widget on your desktop">${settings.widget ? 'Hide desktop widget' : 'Show desktop widget'}</button>
      <button class="nav ${ui.view === 'settings' ? 'on' : ''}" data-act="settings">Settings</button>
      <div class="acct">Synced as ${esc(S.account().email)}</div></aside>
    ${ui.view === 'settings' ? `<div class="center">${settingsHtml()}${toastHtml()}</div>` : `
    <section class="center">
      <form class="bar" data-form="quick">
        <input class="q" name="q" placeholder="Add a task, or type an edit: &quot;move gym to 7am&quot;" autocomplete="off">
        <button class="iconbtn mic" type="button" data-act="dictate" title="Dictate (${hotkey})">${icon('mic')}</button>
        <input class="search" placeholder="Search" value="${esc(ui.search)}" data-input="search">
      </form>
      ${lists.length ? `<div class="lists"><button class="chip ${!ui.list ? 'on' : ''}" data-act="list" data-list="">All lists</button>${lists.map((l) => `<button class="chip ${ui.list === l ? 'on' : ''}" data-act="list" data-list="${esc(l)}">#${esc(l)}</button>`).join('')}</div>` : ''}
      <div class="mlist">${mainListHtml()}</div>
      ${ui.tab === 'done' && S.tasks.some((t) => t.done) ? '<div class="mfoot"><button class="btn ghost" data-act="cleardone">Clear completed</button></div>' : ''}
      ${voiceHtml()}${toastHtml()}
    </section>
    ${sel ? editorHtml(sel, lists) : ''}`}
  </div>`;
  const ml = document.querySelector('.mlist'); if (ml) ml.scrollTop = scroll;
  const nq = document.querySelector('.bar .q'); if (nq) { nq.value = typed; if (qFocus) nq.focus(); }
  if (sFocus) { const s = document.querySelector('.bar .search'); s.focus(); s.setSelectionRange(s.value.length, s.value.length); }
}

function mainListHtml() {
  let list = tabTasks(ui.tab);
  if (ui.list) list = list.filter((t) => t.listName === ui.list);
  if (ui.search) { const s = ui.search.toLowerCase(); list = list.filter((t) => (t.title + ' ' + t.notes + ' ' + (t.listName || '')).toLowerCase().includes(s)); }
  if (!S.ready) return '<div class="empty">Loading…</div>';
  if (!list.length) return `<div class="empty">${ui.search ? 'No matches' : ui.tab === 'done' ? 'Nothing completed yet' : `Nothing here. Press <kbd>${hotkey}</kbd> anywhere and say a task.`}</div>`;
  return ui.tab === 'done' ? list.map(rowHtml).join('') : sectioned(list);
}

function editorHtml(t, lists) {
  const opt = (v, cur, label) => `<option value="${v}" ${String(cur) === String(v) ? 'selected' : ''}>${label}</option>`;
  const rem = [['', 'No reminder'], [0, 'At the time'], [5, '5 min before'], [10, '10 min before'], [15, '15 min before'], [30, '30 min before'], [60, '1 hour before'], [1440, '1 day before']];
  return `<aside class="editor" data-uid="${t.uid}">
    <input class="title-in" data-field="title" value="${esc(t.title)}">
    <label>Notes<textarea data-field="notes" rows="3">${esc(t.notes)}</textarea></label>
    <div class="two">
      <label>Date<input type="date" data-field="date" value="${t.dueEpochDay != null ? L.iso(t.dueEpochDay) : ''}"></label>
      <label>Time<input type="time" data-field="time" value="${t.dueMinute != null ? L.hhmm(t.dueMinute) : ''}" ${t.dueEpochDay == null ? 'disabled' : ''}></label>
    </div>
    <label>Reminder<select data-field="remind" ${t.dueMinute == null ? 'disabled' : ''}>${rem.map(([v, l]) => opt(v, t.remindBefore ?? '', l)).join('')}</select></label>
    <div class="two">
      <label>Repeat<select data-field="recurrence">${L.REC.map((r) => opt(r, t.recurrence, L.REC_LABEL[r])).join('')}</select></label>
      <label>Priority<select data-field="priority">${L.PRIORITY.map((p, i) => opt(i, t.priority || 0, p)).join('')}</select></label>
    </div>
    <label>List<input data-field="list" list="lists" value="${esc(t.listName || '')}" placeholder="e.g. Work"><datalist id="lists">${lists.map((l) => `<option value="${esc(l)}">`).join('')}</datalist></label>
    <div class="actions">
      <button class="btn" data-act="toggle" data-uid="${t.uid}">${t.done ? 'Mark not done' : 'Mark done'}</button>
      <button class="btn ghost danger" data-act="delete" data-uid="${t.uid}">Delete</button>
      <button class="btn ghost" data-act="deselect">Close</button>
    </div>
    ${t.spokenText ? `<div class="spoken">You said: “${esc(t.spokenText)}”</div>` : ''}
  </aside>`;
}

function settingsHtml() {
  const chip = (field, v, l) => `<button class="chip ${settings[field] === v ? 'on' : ''}" data-act="set" data-field="${field}" data-value='${JSON.stringify(v)}'>${l}</button>`;
  const tog = (field, title, sub) => `<label class="opt"><span>${title}<br><span class="muted small">${sub}</span></span><input type="checkbox" data-set="${field}" ${settings[field] ? 'checked' : ''}></label>`;
  return `<div class="settings">
    <h2>Settings</h2>
    <h3>Sync</h3>
    <div class="opt"><span>Signed in as <b>${esc(S.account().email)}</b><br><span class="muted small">Phone and laptop show the same tasks when both use this account.</span></span><button class="btn ghost" data-act="signout">Sign out</button></div>
    <h3>Voice (Whisper via Groq)</h3>
    <label>Groq API key<br><input type="password" style="width:100%" data-setinput="groqKey" value="${esc(settings.groqKey)}" placeholder="gsk_…"></label>
    <div class="muted small">Free key from console.groq.com. Audio goes to Groq only while you dictate.</div>
    <div>Speech recognition</div>
    <div class="chips">${chip('sttModel', 'whisper-large-v3', 'Most accurate (best for names)')}${chip('sttModel', 'whisper-large-v3-turbo', 'Fastest')}</div>
    <div>Spoken language</div>
    <div class="chips">${chip('language', 'en', 'English')}${chip('language', '', 'Auto-detect')}${chip('language', 'hi', 'Hindi')}${chip('language', 'te', 'Telugu')}</div>
    <label>Words Whisper should know<br><textarea style="width:100%" rows="2" data-setinput="vocab" placeholder="Adi, Anna, pitch deck, sales call flow">${esc(settings.vocab)}</textarea></label>
    <label>Microphone<br><select style="width:100%" data-micselect>${micOptions()}</select></label>
    <div class="opt"><span class="muted small" id="mic-test-label">Speak to check the level</span><button class="btn ghost" data-act="mictest">${micTest ? 'Stop test' : 'Test mic'}</button></div>
    <div class="meter"><div id="mic-meter"></div></div>
    ${tog('autoStop', 'Stop recording when I pause', 'Ends after about 2 seconds of silence')}
    ${tog('skipReview', 'Save without review', 'Add dictated tasks straight away')}
    <div class="opt"><span>Dictate from anywhere</span><kbd>${hotkey}</kbd></div>
    <h3>Reminders</h3>
    <div>When a dictated task has a time but you didn't ask for a reminder</div>
    <div class="chips">${chip('defaultRemind', -1, 'No reminder')}${chip('defaultRemind', 0, 'Remind on time')}${chip('defaultRemind', 10, '10 min before')}${chip('defaultRemind', 30, '30 min before')}</div>
    ${tog('notify', 'Reminders on this laptop', 'Windows notifications at reminder time (your phone reminds you too)')}
    <h3>Desktop</h3>
    ${tog('widget', 'Show desktop widget', 'Sits on your desktop under your apps and stays through Win+D')}
    ${tog('locked', 'Lock widget in place', 'Turn off to drag it by its header and resize it from the edges')}
    <div>Hot corner: flick the mouse quickly into this corner to bring the widget in front</div>
    <div class="chips">${chip('corner', 'top-right', 'Top right')}${chip('corner', 'top-left', 'Top left')}${chip('corner', 'bottom-left', 'Bottom left')}${chip('corner', 'bottom-right', 'Bottom right')}${chip('corner', 'off', 'Off')}</div>
    <div class="opt"><span>Bring the widget in front</span><kbd>${peekKey}</kbd></div>
    ${tog('glass', 'Transparent floating widget', 'Floats over your apps; clicks between tasks go to the app underneath')}
    ${settings.glass ? `<label class="opt"><span>Widget background<br><span class="muted small">Left = fully clear, right = solid</span></span>
      <span style="display:flex;align-items:center;gap:10px"><input type="range" min="0" max="100" step="5" value="${settings.glassOpacity ?? 55}" data-range="glassOpacity" style="width:180px;accent-color:var(--accent)"><b id="glass-val" style="width:38px">${settings.glassOpacity ?? 55}%</b></span></label>` : ''}
    ${settings.glass ? '' : tog('onTop', 'Keep widget on top of windows', 'Off: it stays on the desktop and pops up with the corner or shortcut')}
    ${tog('startup', 'Start with Windows', 'Widget appears when you sign in to Windows')}
  </div>`;
}

// ---- microphone choice (Settings) ----
let mics = [];
let micTest = null;
/** "Microphone Array (2- Intel® Smart Sound…)" → "Laptop mic", "Microphone (USB-Audio)" → "USB-Audio". */
function shortMic(name) {
  const inner = (/\(([^)]+)\)/.exec(name) || [])[1] || name;
  const brand = /intel|realtek|usb|bluetooth|headset|airpods|webcam|camera|jabra|logitech|rode|blue|hyperx/i.exec(inner);
  if (/intel/i.test(inner)) return 'Laptop mic';
  return (brand && inner.length > 22 ? brand[0] : inner.replace(/^\d+-\s*/, '')).slice(0, 22);
}
function micOptions(short = false) {
  const known = mics.some((m) => m.id === settings.micId);
  return `<option value="" ${!settings.micId ? 'selected' : ''}>${short ? 'Default mic' : 'Windows default microphone'}</option>` +
    mics.map((m) => `<option value="${esc(m.id)}" ${m.id === settings.micId ? 'selected' : ''}>${esc(short ? shortMic(m.name) : m.name)}</option>`).join('') +
    (settings.micId && !known ? '<option value="" disabled selected>Chosen mic is unplugged (using default)</option>' : '');
}
async function refreshMics() {
  try { mics = await listMics(); } catch { mics = []; }
  document.querySelectorAll('[data-micselect], [data-vmic]').forEach((sel) => { if (document.activeElement !== sel) sel.innerHTML = micOptions('short' in sel.dataset); });
  if (MODE === 'widget') api.reportMics(mics);
}
navigator.mediaDevices?.addEventListener?.('devicechange', refreshMics);
async function toggleMicTest() {
  if (micTest) { micTest(); micTest = null; render(); return; }
  try {
    micTest = await meter(settings.micId, (lvl, label) => {
      const bar = document.getElementById('mic-meter'); if (bar) bar.style.width = Math.round(lvl * 100) + '%';
      const l = document.getElementById('mic-test-label'); if (l) l.textContent = 'Listening on: ' + label;
    });
    refreshMics(); // names show up once the mic has been used
  } catch (e) { toast("Can't open that mic: " + e.message); }
  render();
}

function toastHtml() {
  if (!ui.toast) return '';
  return `<div class="toast">${esc(ui.toast.text)}${ui.toast.undo ? '<button data-act="undo">Undo</button>' : ''}</div>`;
}
let toastTimer;
function toast(text, undo) {
  ui.toast = { text, undo };
  clearTimeout(toastTimer);
  toastTimer = setTimeout(() => { ui.toast = null; render(); }, undo ? 6000 : 3500);
  render();
}

// ---------------------------------------------------------------- voice

function voiceHtml() {
  const v = ui.voice;
  if (!v) return '';
  const head = (t) => `<div class="vhead"><b>${t}</b><button class="iconbtn" data-act="vcancel" title="Cancel (Esc)">${icon('close')}</button></div>`;
  if (v.state === 'recording') return `<div class="voice">${head('Listening…')}
    <div class="listen"><button class="orb ${v.speaking ? 'speaking' : ''}" data-act="vstop" title="Done talking" style="transform:scale(${1 + (v.level || 0) * 0.25})">${icon('mic')}</button>
    <div class="muted">${v.speaking ? 'Got it, keep going or pause to finish' : 'Say a task, or an edit like “move gym to 7am”'}</div>
    <div class="small muted">${settings.autoStop ? 'Stops when you pause · ' : ''}Click the mic to finish · Esc to cancel</div>
    <label class="vmic">🎙 <select data-vmic title="Microphone">${micOptions()}</select></label></div></div>`;
  if (v.state === 'working') return `<div class="voice">${head(v.label)}<div class="listen"><div class="spinner"></div>${v.heard ? `<div class="heard">“${esc(v.heard)}”</div>` : ''}</div></div>`;
  if (v.state === 'failed') return `<div class="voice">${head('Hmm')}<div class="listen"><div>${esc(v.msg)}</div>${v.heard ? `<div class="heard">“${esc(v.heard)}”</div>` : ''}
    ${v.heard ? '' : `<label class="vmic">🎙 <select data-vmic title="Microphone">${micOptions()}</select></label>`}</div>
    <div class="vfoot">${v.heard ? '<button class="link" data-act="vraw">Save my words as a task</button>' : ''}<button class="btn ghost" data-act="vcancel">Close</button><button class="btn" data-act="dictate">Try again</button></div></div>`;
  if (v.state === 'review') {
    const items = v.drafts.map((d, i) => {
      const t = d.target && S.get(d.target);
      const badge = `<span class="badge ${d.kind}">${{ ADD: 'New', UPDATE: 'Change', DONE: 'Mark done', DELETE: 'Delete' }[d.kind]}</span>`;
      if (d.kind === 'ADD') {
        const after = L.draftToTask(d, '', settings.defaultRemind);
        const meta = [L.dueLabel(after), after.recurrence !== 'NONE' && L.REC_LABEL[after.recurrence], after.listName && '#' + after.listName, after.priority === 3 && 'High priority'].filter(Boolean).join(' · ');
        return `<div class="draft">${badge}<input data-draft="${i}" value="${esc(d.title)}">${meta ? `<div class="was">${esc(meta)}</div>` : ''}</div>`;
      }
      if (!t) return '';
      if (d.kind === 'UPDATE') {
        const after = L.applyDraft(d, t, settings.defaultRemind);
        const b = [t.title, L.dueLabel(t)].filter(Boolean).join(' · '), a = [after.title, L.dueLabel(after), after.listName && '#' + after.listName].filter(Boolean).join(' · ');
        return `<div class="draft">${badge}<div><b>${esc(after.title)}</b></div><div class="was">was ${esc(b || '—')} → ${esc(a)}</div></div>`;
      }
      return `<div class="draft">${badge}<div><b>${esc(t.title)}</b></div></div>`;
    }).join('');
    const label = v.drafts.every((d) => d.kind === 'ADD') ? (v.drafts.length === 1 ? 'Add task' : `Add ${v.drafts.length}`) : 'Apply';
    return `<div class="voice">${head('Check this')}<div class="heard">“${esc(v.heard)}”</div><div class="drafts">${items}</div>
      <div class="vfoot"><button class="link" data-act="vraw">Save my words instead</button><button class="btn ghost" data-act="vcancel">Cancel</button><button class="btn" data-act="vapply">${label}</button></div></div>`;
  }
  return '';
}

let signal = {};
async function startDictation() {
  if (!S.account()) { toast('Sign in first'); return; }
  if (ui.voice?.state === 'recording') { signal.stop?.(); return; } // hotkey again = done talking
  if (ui.voice?.state === 'working') return;
  if (!mics.length) refreshMics();
  signal = {};
  ui.voice = { state: 'recording', level: 0 };
  render();
  let r;
  try {
    let last = 0;
    r = await record({ autoStop: settings.autoStop, micId: settings.micId, signal, onLevel: (level, speaking) => {
      if (!ui.voice || ui.voice.state !== 'recording') return;
      ui.voice.level = level; ui.voice.speaking = speaking;
      const orb = document.querySelector('.orb');
      if (orb) { orb.style.transform = `scale(${1 + level * 0.25})`; orb.classList.toggle('speaking', speaking); }
      const now = Date.now(); if (speaking && now - last > 1000) { last = now; const m = document.querySelector('.listen .muted'); if (m) m.textContent = 'Got it, keep going or pause to finish'; }
    } });
  } catch (e) {
    ui.voice = { state: 'failed', msg: "Can't use the microphone: " + e.message };
    return render();
  }
  if (r.cancelled) {
    ui.voice = null;
    if (ui.restartMic) { ui.restartMic = false; return startDictation(); }
    return render();
  }
  if (r.silent || r.tooShort) { ui.voice = { state: 'failed', msg: "Didn't hear anything. Check your mic and speak a little closer." }; return render(); }
  ui.voice = { state: 'working', label: 'Writing it down…' }; render();
  let heard;
  try {
    const vocab = settings.vocab.split(/[,\n]/).concat(S.open().map((t) => t.listName).filter(Boolean));
    heard = await api.transcribe(r.audio, r.mime, vocab, r.meta);
  } catch (e) { ui.voice = { state: 'failed', msg: e.message }; return render(); }
  if (!heard) { ui.voice = { state: 'failed', msg: "Didn't catch any words. Try again a bit louder." }; return render(); }
  await understand(heard);
}

async function understand(heard) {
  if (!ui.voice) return;
  ui.voice = { state: 'working', label: 'Understanding…', heard }; render();
  let drafts;
  try { drafts = await parse(heard); } catch (e) { ui.voice = { state: 'failed', msg: e.message, heard }; return render(); }
  if (!ui.voice) return; // cancelled meanwhile
  if (!drafts.length) { ui.voice = { state: 'failed', msg: "I couldn't find a task in that.", heard }; return render(); }
  if (settings.skipReview && drafts.every((d) => d.kind === 'ADD')) {
    const s = S.applyDrafts(drafts, heard, settings.defaultRemind);
    ui.voice = null; toast(s); return;
  }
  ui.voice = { state: 'review', drafts, heard }; render();
}

async function parse(text) {
  const open = L.sortTasks(S.open());
  const { body, ids, lists } = L.buildParseRequest(text, open);
  const content = await api.parse(body);
  return L.readDrafts(content, ids, lists, open);
}

// ---------------------------------------------------------------- events

function render() { MODE === 'widget' ? renderWidget() : renderMain(); }

let lastDeleted = null;
root.addEventListener('click', async (e) => {
  const el = e.target.closest('[data-act]');
  if (!el) return;
  const act = el.dataset.act, uid = el.dataset.uid;
  if (act !== 'select') e.stopPropagation();
  switch (act) {
    case 'toggle': { const t = S.get(uid); if (t) { S.setDone(t, !t.done); if (!t.done && t.recurrence !== 'NONE') toast('Done · next one scheduled'); } break; }
    case 'select':
      if (e.target.closest('.check')) return;
      if (MODE === 'widget') api.openMain('task=' + uid);
      else { ui.selected = ui.selected === uid ? null : uid; ui.view = 'tasks'; render(); }
      break;
    case 'deselect': ui.selected = null; render(); break;
    case 'delete': { const t = S.get(uid); if (t) { lastDeleted = t; S.remove(uid); ui.selected = null; toast('Deleted', true); } break; }
    case 'undo': if (lastDeleted) { S.save(lastDeleted); lastDeleted = null; ui.toast = null; render(); } break;
    case 'cleardone': S.clearDone(); toast('Cleared completed tasks'); break;
    case 'tab': ui.tab = el.dataset.tab; ui.view = 'tasks'; if (micTest) { micTest(); micTest = null; } render(); break;
    case 'list': ui.list = el.dataset.list || null; render(); break;
    case 'settings': ui.view = 'settings'; ui.selected = null; render(); refreshMics(); break;
    case 'open': api.openMain(); break;
    case 'hidewidget': api.hideWidget(); break;
    case 'togglewidget': settings = await api.setSettings({ widget: !settings.widget }); render(); break;
    case 'dictate': startDictation(); break;
    case 'vstop': signal.stop?.(); break;
    case 'vcancel': signal.cancel?.(); ui.voice = null; render(); break;
    case 'vapply': {
      const v = ui.voice; if (!v) return;
      document.querySelectorAll('[data-draft]').forEach((inp) => { v.drafts[+inp.dataset.draft].title = inp.value.trim() || v.drafts[+inp.dataset.draft].title; });
      const s = S.applyDrafts(v.drafts, v.heard, settings.defaultRemind);
      ui.voice = null; toast(s); break;
    }
    case 'vraw': { const h = ui.voice?.heard; if (h) { S.add({ title: h.replace(/^./, (c) => c.toUpperCase()), spokenText: h }); ui.voice = null; toast('Saved as a task'); } break; }
    case 'set': settings = await api.setSettings({ [el.dataset.field]: JSON.parse(el.dataset.value) }); render(); break;
    case 'signout': await S.signOut(); break;
    case 'mictest': toggleMicTest(); break;
    case 'create': submitSignIn(el.closest('form'), true); break;
  }
});

async function submitSignIn(form, create) {
  const email = form.email.value, password = form.password.value;
  if (!email || !password) { ui.authMsg = 'Enter your email and password.'; return render(); }
  ui.busy = true; ui.authMsg = ''; render();
  try { await S.signIn(email, password, create); } catch (err) { ui.authMsg = S.authMessage(err); }
  ui.busy = false; render();
  const f = document.querySelector('[data-form=signin]');
  if (f && ui.authMsg) { f.email.value = email; }
}

root.addEventListener('submit', async (e) => {
  e.preventDefault();
  const f = e.target;
  if (f.dataset.form === 'signin') return submitSignIn(f, false);
  if (f.dataset.form === 'quick') {
    const text = f.q.value.trim();
    if (!text) return;
    f.q.value = '';
    // Typed text goes through the same reader as speech, so "pay rent friday 5pm" gets its date.
    try {
      const drafts = await parse(text);
      if (!drafts.length) { S.add({ title: text[0].toUpperCase() + text.slice(1) }); toast('Added'); }
      else toast(S.applyDrafts(drafts, text, settings.defaultRemind));
    } catch {
      S.add({ title: text[0].toUpperCase() + text.slice(1) }); toast('Added (no date detected offline)');
    }
  }
});

root.addEventListener('input', (e) => {
  const el = e.target;
  if (el.dataset.input === 'search') { ui.search = el.value; const ml = document.querySelector('.mlist'); if (ml) ml.innerHTML = mainListHtml(); }
  if (el.dataset.range) {
    const v = +el.value; settings[el.dataset.range] = v;
    const lbl = document.getElementById('glass-val'); if (lbl) lbl.textContent = v + '%';
    clearTimeout(rangeT); rangeT = setTimeout(() => api.setSettings({ [el.dataset.range]: v }), 60); // live preview on the widget
  }
  if (el.dataset.setinput) { settings[el.dataset.setinput] = el.value; debounceSave(el.dataset.setinput, el.value); }
});
let saveT, rangeT;
function debounceSave(k, v) { clearTimeout(saveT); saveT = setTimeout(() => api.setSettings({ [k]: v }), 400); }

root.addEventListener('change', async (e) => {
  const el = e.target;
  if (el.dataset.set) { settings = await api.setSettings({ [el.dataset.set]: el.checked }); return; }
  if ('vmic' in el.dataset) {
    settings = await api.setSettings({ micId: el.value });
    if (ui.voice?.state === 'recording') { ui.restartMic = true; signal.cancel?.(); }
    else if (ui.voice?.state === 'failed') startDictation();
    else toast('Mic: ' + (el.value ? shortMic(el.options[el.selectedIndex].text) : 'Windows default'));
    return;
  }
  if ('micselect' in el.dataset) {
    settings = await api.setSettings({ micId: el.value });
    if (micTest) { micTest(); micTest = null; await toggleMicTest(); } // re-test on the newly chosen mic
    return;
  }
  const ed = el.closest('.editor');
  if (!ed || !el.dataset.field) return;
  const t = S.get(ed.dataset.uid); if (!t) return;
  const v = el.value, n = { ...t };
  switch (el.dataset.field) {
    case 'title': if (!v.trim()) return; n.title = v.trim(); break;
    case 'notes': n.notes = v; break;
    case 'date': n.dueEpochDay = L.parseIso(v); if (n.dueEpochDay == null) { n.dueMinute = null; n.remindBefore = null; } break;
    case 'time': n.dueMinute = L.parseHm(v); if (n.dueMinute == null) n.remindBefore = null; else if (t.dueMinute == null && settings.defaultRemind >= 0) n.remindBefore = settings.defaultRemind; break;
    case 'remind': n.remindBefore = v === '' ? null : +v; break;
    case 'recurrence': n.recurrence = v; break;
    case 'priority': n.priority = +v; break;
    case 'list': n.listName = v.trim() || null; break;
  }
  S.save(n);
  if (['date', 'time'].includes(el.dataset.field)) setTimeout(() => { document.activeElement?.blur(); render(); }, 0);
});

root.addEventListener('keydown', (e) => {
  if (e.key === 'Enter' && e.target.dataset?.field === 'title') e.target.blur();
});
document.addEventListener('keydown', (e) => {
  if (e.key === 'Escape') {
    if (ui.voice) { signal.cancel?.(); ui.voice = null; render(); }
    else if (ui.selected) { ui.selected = null; render(); }
    else if (MODE === 'widget') api.peekDone();
  }
  if (e.key === 'Enter' && ui.voice?.state === 'review' && !e.target.closest?.('[data-draft]')) document.querySelector('[data-act=vapply]')?.click();
});

// ---------------------------------------------------------------- reminders (the widget window does these, once)

function startReminders() {
  let last = Date.now();
  const fired = new Set();
  const check = () => {
    const now = Date.now();
    for (const t of S.open()) {
      const at = L.reminderAt(t);
      if (at == null || at <= last || at > now) continue;
      const key = t.uid + ':' + at;
      if (fired.has(key)) continue;
      fired.add(key);
      api.notify({ title: t.title, body: L.dueLabel(t) + (t.notes ? ' · ' + t.notes : ''), uid: t.uid });
    }
    last = now;
  };
  setInterval(check, 15000);
}

// ---------------------------------------------------------------- transparent widget: click-through

/** Over a task, button or the add box the widget takes the click; anywhere else it passes to the app beneath. */
function setupClickThrough() {
  let through = null;
  const set = (v) => { if (v !== through) { through = v; api.clickThrough(v); } };
  const solid = '.row, button, input, .voice, .w-head, .signin, .toast, .w-add, .empty';
  document.addEventListener('mousemove', (e) => {
    const t = e.target instanceof Element ? e.target : null;
    set(!(t && t.closest(solid)) && !ui.voice);
  });
  document.addEventListener('mouseleave', () => { if (!ui.voice) set(true); });
  set(true);
}

// ---------------------------------------------------------------- start

(async () => {
  settings = await api.getSettings();
  hotkey = await api.hotkey();
  peekKey = await api.peekKey();
  const bodyClass = () => {
    document.body.classList.toggle('locked', MODE === 'widget' && !!settings.locked);
    document.body.classList.toggle('glass', MODE === 'widget' && !!settings.glass);
    // How see-through the transparent widget is: 0 = fully clear, 100 = solid.
    const a = Math.max(0, Math.min(100, settings.glassOpacity ?? 55)) / 100;
    document.body.style.setProperty('--glass-a', a.toFixed(2));
    document.body.style.setProperty('--glass-hover', Math.min(1, a + 0.12).toFixed(2));
    document.body.style.setProperty('--glass-line', a > 0 ? '0.10' : '0');
  };
  if (MODE === 'widget') setupClickThrough();
  bodyClass();
  api.on('settings', bodyClass);
  api.on('peek', () => { if (!ui.voice) document.querySelector('.w-add input')?.focus(); });
  api.on('settings', (s) => { settings = s; if (!document.querySelector('.settings')?.contains(document.activeElement)) render(); });
  api.on('dictate', startDictation);
  api.on('open', (extra) => { const p = new URLSearchParams(extra); if (p.get('task')) { ui.selected = p.get('task'); ui.view = 'tasks'; ui.tab = 'all'; render(); } });
  if (params.get('task')) { ui.selected = params.get('task'); ui.tab = 'all'; }
  S.subscribe(render);
  render();
  setInterval(render, 60000); // "Today", overdue colours move on as time passes
  if (MODE === 'widget') { startReminders(); refreshMics(); }
  api.on('settings', () => document.querySelectorAll('[data-micselect], [data-vmic]').forEach((sel) => { if (document.activeElement !== sel) sel.innerHTML = micOptions('short' in sel.dataset); }));
})();
