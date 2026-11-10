// Dictask for Windows: a desktop widget + full window + tray, synced with the phone through Firebase.
const { app, BrowserWindow, Tray, Menu, ipcMain, globalShortcut, Notification, screen, nativeImage, shell, session } = require('electron');
const path = require('path');
const fs = require('fs');
const desk = require('./desktop');

const HOTKEY = 'Control+Shift+Space';
let PEEK_KEY = 'Control+Alt+D';
const PEEK_KEYS = ['Control+Alt+D', 'Control+Alt+Space', 'Alt+Shift+D', 'Control+Shift+Alt+T'];
const GROQ = 'https://api.groq.com/openai/v1';
const STT_MODEL = 'whisper-large-v3-turbo';
const PARSE_MODEL = 'openai/gpt-oss-120b';
const FALLBACK_MODEL = 'openai/gpt-oss-20b';
const SILENCE = new Set(['thank you', 'thanks', 'thank you very much', 'thanks for watching', 'thank you for watching', 'you', 'bye', 'okay', 'ok', 'so', 'hmm', 'uh', 'um', 'the', 'a', 'and', 'i', 'yeah', 'oh', 'please subscribe', 'subtitles by the amara.org community']);

app.setAppUserModelId('com.hemanth.dictask');
if (!app.requestSingleInstanceLock()) { app.quit(); return; }

// ---- settings (in %APPDATA%\Dictask) ----
const dataDir = app.getPath('userData');
const settingsFile = path.join(dataDir, 'settings.json');
const stateFile = path.join(dataDir, 'window-state.json');
const DEFAULTS = { groqKey: '', language: 'en', vocab: '', autoStop: true, skipReview: false, defaultRemind: 0, notify: true, onTop: false, widget: true, startup: true, locked: true, corner: 'top-right', micId: '', glass: true, sttModel: 'whisper-large-v3', glassOpacity: 55 };
const readJson = (f, d) => { try { return { ...d, ...JSON.parse(fs.readFileSync(f, 'utf8')) }; } catch { return { ...d }; } };
let settings = readJson(settingsFile, DEFAULTS);
const saveSettings = () => fs.writeFileSync(settingsFile, JSON.stringify(settings, null, 2));
let winState = readJson(stateFile, {});
const saveState = () => fs.writeFileSync(stateFile, JSON.stringify(winState));

let widget = null, mainWin = null, tray = null;
let micList = []; // reported by the widget page (only a page can list audio devices)
/** Transparent mode floats above other windows; otherwise the widget lives on the desktop (or on top if asked). */
const floating = () => !!(settings.glass || settings.onTop);
function placeWidget() {
  if (!widget || widget.isDestroyed()) return;
  widget.setAlwaysOnTop(floating(), 'floating');
  if (floating()) desk.unpin(widget); else console.log('pinned to desktop:', desk.pinToDesktop(widget));
  if (!settings.glass) widget.setIgnoreMouseEvents(false);
}
const icon = nativeImage.createFromPath(path.join(__dirname, 'icon.png'));
const page = (mode, extra = '') => `file://${path.join(__dirname, 'app.html').replace(/\\/g, '/')}?mode=${mode}${extra}`;
const all = () => [widget, mainWin].filter((w) => w && !w.isDestroyed());
const broadcast = (ch, ...a) => all().forEach((w) => w.webContents.send(ch, ...a));

function boundsVisible(b) {
  return b && screen.getAllDisplays().some((d) => { const a = d.workArea; return b.x < a.x + a.width - 40 && b.x + b.width > a.x + 40 && b.y >= a.y - 10 && b.y < a.y + a.height - 40; });
}

function createWidget() {
  const wa = screen.getPrimaryDisplay().workArea;
  const def = { width: 340, height: 460, x: wa.x + wa.width - 340 - 24, y: wa.y + 24 };
  const b = boundsVisible(winState.widget) ? winState.widget : def;
  widget = new BrowserWindow({
    ...b, minWidth: 260, minHeight: 200, frame: false, transparent: true, resizable: !settings.locked, movable: !settings.locked, skipTaskbar: true,
    maximizable: false, minimizable: false, fullscreenable: false, show: false, alwaysOnTop: floating(), hasShadow: false, title: 'Dictask widget',
    webPreferences: { preload: path.join(__dirname, 'preload.js'), backgroundThrottling: false },
  });
  widget.loadURL(page('widget'));
  widget.once('ready-to-show', () => {
    placeWidget();
    if (settings.widget) widget.showInactive();
  });
  // After a corner flick or a click, it drops back onto the desktop as soon as you switch to another app.
  widget.on('blur', () => setTimeout(unpeek, 150));
  const remember = () => { if (!widget.isDestroyed()) { winState.widget = widget.getBounds(); saveState(); } };
  widget.on('moved', remember); widget.on('resized', remember);
  // "Show desktop" (Win+D) would otherwise leave a frameless widget with no way back but the tray.
  widget.on('minimize', () => setTimeout(() => { if (!widget.isDestroyed()) { widget.restore(); widget.showInactive(); } }, 300));
  widget.on('close', (e) => { if (!app.quitting) { e.preventDefault(); hideWidget(); } });
}

function showMain(extra = '') {
  showWidget(); // opening Dictask always brings the desktop widget back
  if (mainWin && !mainWin.isDestroyed()) {
    if (extra) mainWin.webContents.send('open', extra);
    if (mainWin.isMinimized()) mainWin.restore();
    mainWin.show(); mainWin.focus(); return;
  }
  const b = winState.main && boundsVisible(winState.main) ? winState.main : { width: 980, height: 720 };
  mainWin = new BrowserWindow({
    ...b, minWidth: 420, minHeight: 480, title: 'Dictask', icon, autoHideMenuBar: true, backgroundColor: '#111318', show: false,
    webPreferences: { preload: path.join(__dirname, 'preload.js') },
  });
  mainWin.removeMenu();
  mainWin.loadURL(page('main', extra ? '&' + extra : ''));
  mainWin.once('ready-to-show', () => mainWin.show());
  const remember = () => { if (!mainWin.isDestroyed() && !mainWin.isMaximized()) { winState.main = mainWin.getBounds(); saveState(); } };
  mainWin.on('moved', remember); mainWin.on('resized', remember);
  mainWin.on('closed', () => { mainWin = null; showWidget(); });
}

function showWidget() {
  const changed = !settings.widget;
  settings.widget = true; saveSettings();
  if (!widget || widget.isDestroyed()) createWidget(); // shows itself once loaded
  else if (!widget.isVisible()) { widget.showInactive(); if (!floating()) desk.sendToBack(widget); }
  if (changed) broadcast('settings', settings);
  buildTray();
}

function hideWidget() {
  widget?.hide(); settings.widget = false; saveSettings();
  broadcast('settings', settings); buildTray();
}

let peeking = false;
/** Brings the desktop widget in front of every window for a moment (hot corner, hotkey, tray). */
function peek(focusAdd = true) {
  if (!widget || widget.isDestroyed()) createWidget();
  peeking = true;
  widget.setAlwaysOnTop(true, 'pop-up-menu');
  widget.show(); widget.focus(); widget.moveTop();
  if (focusAdd) widget.webContents.send('peek');
}
function unpeek(force = false) {
  if (!peeking || !widget || widget.isDestroyed() || (!force && widget.isFocused())) return;
  peeking = false;
  widget.setAlwaysOnTop(floating(), 'floating');
  if (!floating()) desk.sendToBack(widget);
  if (!settings.widget) widget.hide();
}
function togglePeek() { if (peeking) unpeek(true); else peek(); }

function dictate() {
  peek(false);
  widget.webContents.send('dictate');
}

function applyLock() {
  if (!widget || widget.isDestroyed()) return;
  widget.setMovable(!settings.locked); widget.setResizable(!settings.locked);
}

function buildTray() {
  const menu = Menu.buildFromTemplate([
    { label: 'Open Dictask', click: () => showMain() },
    { label: `Show widget in front    ${PEEK_KEY.replace('Control', 'Ctrl')}`, click: () => peek() },
    { label: `Dictate a task    ${HOTKEY.replace('Control', 'Ctrl')}`, click: dictate },
    { type: 'separator' },
    { label: 'Show desktop widget', type: 'checkbox', checked: !!(widget && widget.isVisible()), click: (i) => (i.checked ? showWidget() : hideWidget()) },
    { label: 'Lock widget in place', type: 'checkbox', checked: settings.locked, click: (i) => setSetting({ locked: i.checked }) },
    { label: 'Hot corner (flick the mouse there)', submenu: [['top-right', 'Top right'], ['top-left', 'Top left'], ['bottom-left', 'Bottom left'], ['bottom-right', 'Bottom right'], ['off', 'Off']]
      .map(([v, l]) => ({ label: l, type: 'radio', checked: settings.corner === v, click: () => setSetting({ corner: v }) })) },
    { label: 'Transparent floating widget', type: 'checkbox', checked: settings.glass, click: (i) => setSetting({ glass: i.checked }) },
    { label: 'Widget background', enabled: settings.glass, submenu: [[0, 'Clear'], [30, 'Light'], [55, 'Medium'], [75, 'Dark'], [92, 'Solid']]
      .map(([v, l]) => ({ label: l, type: 'radio', checked: (settings.glassOpacity ?? 55) === v, click: () => setSetting({ glassOpacity: v }) })) },
    { label: 'Keep widget on top', type: 'checkbox', checked: settings.onTop, enabled: !settings.glass, click: (i) => setSetting({ onTop: i.checked }) },
    { label: 'Start with Windows', type: 'checkbox', checked: settings.startup, click: (i) => { settings.startup = i.checked; saveSettings(); applyStartup(); } },
    { label: 'Microphone', submenu: [{ id: '', name: 'Windows default microphone' }, ...micList]
      .map((m) => ({ label: m.name, type: 'radio', checked: (settings.micId || '') === m.id, click: () => setSetting({ micId: m.id }) })) },
    { type: 'separator' },
    { label: 'Quit Dictask', click: () => { app.quitting = true; app.quit(); } },
  ]);
  if (!tray) {
    tray = new Tray(icon.resize({ width: 16, height: 16 }));
    tray.setToolTip('Dictask');
    tray.on('click', () => (widget && widget.isVisible() ? showMain() : showWidget()));
  }
  tray.setContextMenu(menu);
}

function applyStartup() {
  if (!app.isPackaged) return;
  app.setLoginItemSettings({ openAtLogin: settings.startup, path: process.execPath, args: ['--background'] });
}

/** A Start-menu shortcut carrying the app id is what lets Windows show Dictask's notifications. */
function ensureShortcut() {
  if (!app.isPackaged) return;
  const lnk = path.join(app.getPath('appData'), 'Microsoft', 'Windows', 'Start Menu', 'Programs', 'Dictask.lnk');
  try { shell.writeShortcutLink(lnk, fs.existsSync(lnk) ? 'update' : 'create', { target: process.execPath, appUserModelId: 'com.hemanth.dictask', description: 'Voice to-dos synced with your phone' }); } catch (e) { console.warn(e); }
}

// ---- Groq (kept in the main process: the key never sits in a web page) ----
class GroqError extends Error { constructor(m, code) { super(m); this.code = code; } }
async function groq(urlPath, init) {
  if (!settings.groqKey) throw new GroqError('Add your Groq API key in Settings first.', 0);
  let r;
  try { r = await fetch(GROQ + urlPath, { ...init, headers: { ...(init.headers || {}), Authorization: 'Bearer ' + settings.groqKey } }); }
  catch { throw new GroqError("Can't reach Groq. Check your internet.", 0); }
  const text = await r.text();
  if (!r.ok) {
    let msg; try { msg = JSON.parse(text).error.message; } catch { msg = text.slice(0, 200); }
    throw new GroqError(r.status === 401 ? 'Groq rejected the API key. Check it in Settings.' : r.status === 429 ? 'Groq rate limit hit. Try again in a minute.' : `Groq error ${r.status}: ${msg}`, r.status);
  }
  return JSON.parse(text);
}

const handle = (ch, fn) => ipcMain.handle(ch, async (_e, ...a) => { try { return { ok: await fn(...a) }; } catch (e) { return { error: e.message || String(e) }; } });

handle('transcribe', async (audio, mime, vocab, meta) => {
  const words = [...new Set((vocab || []).map((w) => w.trim()).filter(Boolean))].join(', ').slice(0, 500);
  const form = new FormData();
  // large-v3 gets names right far more often ("Akshita" vs turbo's "Akshitar"); turbo is the faster option.
  form.append('model', settings.sttModel || STT_MODEL);
  form.append('response_format', 'json');
  form.append('temperature', '0');
  form.append('prompt', 'To-do list: remind me to call, buy, pay, email, meeting tomorrow at 5 pm, next Monday.' + (words ? ` Words I use: ${words}.` : ''));
  if (settings.language) form.append('language', settings.language);
  form.append('file', new Blob([audio], { type: mime }), mime.includes('wav') ? 'dictation.wav' : 'dictation.webm');
  // Keep the last recording + a short log, so a bad transcription can be checked afterwards.
  try { fs.writeFileSync(path.join(dataDir, mime.includes('wav') ? 'last-dictation.wav' : 'last-dictation.webm'), Buffer.from(audio)); } catch {}
  const json = await groq('/audio/transcriptions', { method: 'POST', body: form });
  const text = (json.text || '').trim();
  try { fs.appendFileSync(path.join(dataDir, 'dictation-log.txt'), `${new Date().toISOString()}  ${JSON.stringify({ ...(meta || {}), lang: settings.language, model: settings.sttModel })}  heard: ${text}\n`); } catch {}
  // Whisper invents these on silence or noise.
  return SILENCE.has(text.toLowerCase().replace(/^[\s.!,]+|[\s.!,]+$/g, '')) ? '' : text;
});

handle('parse', async (body) => {
  const ask = (model) => groq('/chat/completions', { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({ ...body, model }) });
  let json;
  // 120b reads dates far more reliably; 20b is the fallback when 120b is rate-limited or busy.
  try { json = await ask(PARSE_MODEL); } catch (e) { if (e.code === 401 || e.code === 0) throw e; json = await ask(FALLBACK_MODEL); }
  return json.choices[0].message.content;
});

handle('settings:get', () => settings);
function setSetting(patch) {
  settings = { ...settings, ...patch }; saveSettings();
  if ('onTop' in patch || 'glass' in patch) placeWidget();
  if ('locked' in patch) applyLock();
  if ('widget' in patch) { if (settings.widget) showWidget(); else hideWidget(); }
  if ('startup' in patch) applyStartup();
  broadcast('settings', settings); buildTray();
  return settings;
}
handle('settings:set', setSetting);
handle('notify', ({ title, body, uid }) => {
  if (!settings.notify || !Notification.isSupported()) return false;
  const n = new Notification({ title, body, icon, timeoutType: 'never' });
  n.on('click', () => showMain(uid ? 'task=' + uid : ''));
  n.show();
  return true;
});
handle('open-main', (extra) => showMain(extra || ''));
handle('hide-widget', hideWidget);
handle('focus-widget', () => { widget?.focus(); });
handle('hotkey', () => HOTKEY.replace('Control', 'Ctrl'));
handle('peek-key', () => (PEEK_KEY || 'none').replace('Control', 'Ctrl'));
handle('peek-done', () => unpeek(true));
// Transparent widget: clicks on empty space go to the window underneath; tasks and buttons still take clicks.
ipcMain.on('mics', (_e, list) => { micList = Array.isArray(list) ? list.slice(0, 20) : []; buildTray(); });
ipcMain.on('click-through', (_e, on) => { if (widget && !widget.isDestroyed()) widget.setIgnoreMouseEvents(!!on && settings.glass, { forward: true }); });
handle('is-packaged', () => app.isPackaged);

app.on('second-instance', () => showMain()); // launching it again: main window + widget back
app.on('window-all-closed', (e) => e.preventDefault()); // lives in the tray

app.whenReady().then(() => {
  // Microphone only for our own pages.
  session.defaultSession.setPermissionRequestHandler((wc, perm, cb) => cb(perm === 'media' || perm === 'notifications'));
  session.defaultSession.setPermissionCheckHandler((wc, perm) => perm === 'media' || perm === 'notifications');
  settings.widget = true; saveSettings(); // a fresh start always shows the widget
  createWidget();
  buildTray();
  ensureShortcut();
  applyStartup();
  if (!globalShortcut.register(HOTKEY, dictate)) console.warn('hotkey taken:', HOTKEY);
  // First free one wins; another app may already own a combination.
  PEEK_KEY = PEEK_KEYS.find((k) => globalShortcut.register(k, togglePeek)) || '';
  console.log('widget shortcut:', PEEK_KEY || 'none free');
  buildTray();
  desk.watchCorner(() => settings.corner, togglePeek);
  const background = process.argv.includes('--background');
  if (!background && !process.argv.includes('--widget-only')) showMain();
  // Dev-only: screenshots of Dictask's own windows for testing (never the rest of the screen).
  if (!app.isPackaged && process.env.DICTASK_SHOT) {
    for (const w of all()) w.webContents.on('console-message', (_e, _l, msg) => console.log('[renderer]', msg));
    const shoot = async () => {
      for (const [n, w] of [['widget', widget], ['main', mainWin]]) {
        if (w && !w.isDestroyed()) fs.writeFileSync(path.join(process.env.DICTASK_SHOT, n + '.png'), (await w.webContents.capturePage()).toPNG());
      }
    };
    setInterval(shoot, 2000);
    let lastVis = null;
    setInterval(() => { const v = !!(widget && !widget.isDestroyed() && widget.isVisible()); if (v !== lastVis) { lastVis = v; console.log('[state] widget visible:', v); } }, 300);
    if (process.env.DICTASK_JS) {
      // Run a test script in a window: file name like "main.js" or "widget.js" in the shot folder.
      fs.watch(process.env.DICTASK_SHOT, (_ev, f) => {
        const m = /^(main|widget)-cmd\.js$/.exec(f || ''); if (!m) return;
        const file = path.join(process.env.DICTASK_SHOT, f);
        setTimeout(() => {
          let code; try { code = fs.readFileSync(file, 'utf8'); fs.unlinkSync(file); } catch { return; }
          const w = m[1] === 'main' ? mainWin : widget;
          w?.webContents.executeJavaScript(code).then((r) => console.log('[test]', JSON.stringify(r)), (e) => console.log('[test error]', e.message));
        }, 100);
      });
    }
  }
});
app.on('will-quit', () => globalShortcut.unregisterAll());
