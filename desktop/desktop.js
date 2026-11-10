// Windows-only glue: pins the widget to the desktop (like a gadget) and watches for the hot-corner flick.
const { screen } = require('electron');

let user32 = null;
try {
  const koffi = require('koffi');
  const lib = koffi.load('user32.dll');
  user32 = {
    FindWindowW: lib.func('intptr_t __stdcall FindWindowW(const char16_t *cls, const char16_t *name)'),
    SetWindowLongPtrW: lib.func('intptr_t __stdcall SetWindowLongPtrW(intptr_t hwnd, int index, intptr_t value)'),
    SetWindowPos: lib.func('bool __stdcall SetWindowPos(intptr_t hwnd, intptr_t after, int x, int y, int cx, int cy, uint32_t flags)'),
  };
} catch (e) { console.warn('desktop pinning unavailable:', e.message); }

const hwndOf = (win) => { const b = win.getNativeWindowHandle(); return b.length >= 8 ? b.readBigInt64LE(0) : BigInt(b.readInt32LE(0)); };
const GWLP_HWNDPARENT = -8, HWND_BOTTOM = 1n, SWP = 0x0001 | 0x0002 | 0x0010; // NOSIZE | NOMOVE | NOACTIVATE

/**
 * Makes the desktop ("Program Manager") the widget's owner: it then sits on the desktop under every app,
 * stays visible through Win+D / "Show desktop", and still takes clicks.
 */
function pinToDesktop(win) {
  if (!user32) return false;
  const progman = user32.FindWindowW('Progman', null);
  if (!progman) return false;
  user32.SetWindowLongPtrW(hwndOf(win), GWLP_HWNDPARENT, progman);
  sendToBack(win);
  return true;
}

/** Undo pinToDesktop, so the widget can float above other windows. */
function unpin(win) {
  if (user32 && win && !win.isDestroyed()) user32.SetWindowLongPtrW(hwndOf(win), GWLP_HWNDPARENT, 0n);
}

function sendToBack(win) {
  if (user32 && win && !win.isDestroyed()) user32.SetWindowPos(hwndOf(win), HWND_BOTTOM, 0, 0, 0, 0, SWP);
}

/**
 * Calls onFlick when the pointer is flung quickly into the chosen screen corner.
 * A slow drift into the corner does nothing, so it doesn't fire by accident.
 */
function watchCorner(getCorner, onFlick) {
  const trail = [];
  let armed = true;
  setInterval(() => {
    const corner = getCorner();
    if (!corner || corner === 'off') return;
    const p = screen.getCursorScreenPoint(), now = Date.now();
    trail.push({ x: p.x, y: p.y, t: now });
    while (trail.length && now - trail[0].t > 160) trail.shift();
    const b = screen.getDisplayNearestPoint(p).bounds;
    const cx = corner.endsWith('right') ? b.x + b.width - 1 : b.x;
    const cy = corner.startsWith('bottom') ? b.y + b.height - 1 : b.y;
    const dist = Math.hypot(p.x - cx, p.y - cy);
    if (dist > 60) { armed = true; return; }
    if (!armed || dist > 6) return;
    const first = trail[0];
    const travelled = Math.hypot(p.x - first.x, p.y - first.y);
    if (travelled >= 220) { armed = false; onFlick(); }
  }, 25);
}

module.exports = { pinToDesktop, unpin, sendToBack, watchCorner };
