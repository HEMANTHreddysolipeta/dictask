// Cloud task list shared with the phone: users/{account}/tasks/{uid}. Firestore keeps an offline copy,
// so the widget still shows tasks without internet and edits are sent when it comes back.
import { initializeApp } from 'firebase/app';
import { getAuth, onAuthStateChanged, signInWithEmailAndPassword, createUserWithEmailAndPassword, signOut as fbSignOut } from 'firebase/auth';
import { initializeFirestore, persistentLocalCache, persistentMultipleTabManager, collection, doc, setDoc, deleteDoc, onSnapshot, writeBatch } from 'firebase/firestore';
import { blankTask, newUid, nextOf, firstNotPast, today, draftToTask, applyDraft, summary } from './logic.js';

const app = initializeApp({
  apiKey: 'AIzaSyDNPl7BkAT5f7Ulz6dFJlCgv3dF9de4f24',
  authDomain: 'dictask-hr.firebaseapp.com',
  projectId: 'dictask-hr',
  storageBucket: 'dictask-hr.firebasestorage.app',
  messagingSenderId: '259736723544',
  appId: '1:259736723544:web:3808263a22e550ac056a2d',
});
const auth = getAuth(app);
const db = initializeFirestore(app, { localCache: persistentLocalCache({ tabManager: persistentMultipleTabManager() }) });

const FIELDS = ['title', 'notes', 'dueEpochDay', 'dueMinute', 'priority', 'listName', 'recurrence', 'remindBefore', 'done', 'completedAt', 'createdAt', 'spokenText', 'updatedAt'];

let user = null;
let unsub = null;
export let tasks = []; // every task, done ones included
export let ready = false;
const listeners = new Set();
const emit = () => listeners.forEach((f) => f());
export const subscribe = (f) => { listeners.add(f); return () => listeners.delete(f); };
export const account = () => user;
export let authKnown = false;

const col = () => collection(db, 'users', user.uid, 'tasks');

onAuthStateChanged(auth, (u) => {
  user = u; authKnown = true;
  unsub?.(); unsub = null; tasks = []; ready = false;
  if (u) {
    unsub = onSnapshot(col(), (snap) => {
      tasks = snap.docs.map((d) => ({ ...blankTask(), ...d.data(), uid: d.id }));
      ready = true; emit();
    }, (e) => console.warn('sync', e));
  }
  emit();
});

export async function signIn(email, password, create) {
  if (create) await createUserWithEmailAndPassword(auth, email.trim(), password);
  else await signInWithEmailAndPassword(auth, email.trim(), password);
}
export const signOut = () => fbSignOut(auth);

export function authMessage(e) {
  const c = e?.code || '';
  if (c.includes('invalid-credential') || c.includes('wrong-password') || c.includes('user-not-found')) return 'Wrong email or password. New here? Click Create account.';
  if (c.includes('email-already-in-use')) return 'That email already has an account. Click Sign in.';
  if (c.includes('weak-password')) return 'Use a password of at least 6 characters.';
  if (c.includes('invalid-email')) return "That email address doesn't look right.";
  if (c.includes('network')) return 'No internet. Try again when you are online.';
  return e?.message || "Couldn't sign in.";
}

const clean = (t) => Object.fromEntries(FIELDS.map((k) => [k, t[k] ?? (k === 'notes' || k === 'title' ? '' : k === 'done' ? false : k === 'priority' ? 0 : k === 'recurrence' ? 'NONE' : null)]));

// Writes don't wait for the server: Firestore applies them locally at once and syncs in the background.
export function save(t) {
  const s = { ...t, updatedAt: Date.now() };
  setDoc(doc(col(), s.uid), clean(s)).catch((e) => console.warn('save', e));
  return s;
}
export const add = (fields) => save(blankTask(fields));
export const remove = (uid) => deleteDoc(doc(col(), uid)).catch((e) => console.warn('delete', e));
export const get = (uid) => tasks.find((t) => t.uid === uid);
export const open = () => tasks.filter((t) => !t.done);

/** Ticking a repeating task completes it and creates the next occurrence, exactly like the phone. */
export function setDone(t, done) {
  if (!!t.done === done) return;
  save({ ...t, done, completedAt: done ? Date.now() : null });
  if (done && t.recurrence && t.recurrence !== 'NONE') {
    const base = t.dueEpochDay ?? today();
    const next = firstNotPast(t.recurrence, nextOf(t.recurrence, base), t.dueMinute);
    save({ ...t, uid: newUid(), done: false, completedAt: null, createdAt: Date.now(), dueEpochDay: next });
  }
}

export function clearDone() {
  const b = writeBatch(db);
  tasks.filter((t) => t.done).forEach((t) => b.delete(doc(col(), t.uid)));
  return b.commit();
}

export function applyDrafts(drafts, spoken, defaultRemind) {
  const c = { added: 0, changed: 0, done: 0, deleted: 0 };
  for (const d of drafts) {
    const t = d.target && get(d.target);
    if (d.kind === 'ADD') { save(draftToTask(d, spoken, defaultRemind)); c.added++; }
    else if (!t) continue;
    else if (d.kind === 'UPDATE') { save(applyDraft(d, t, defaultRemind)); c.changed++; }
    else if (d.kind === 'DONE') { setDone(t, true); c.done++; }
    else if (d.kind === 'DELETE') { remove(t.uid); c.deleted++; }
  }
  return summary(c);
}
