// Accès au portail : mot de passe (haché, modifiable), session signée de 12 h, code de secours.
//   auth.json = { salt, hash, recoverySalt, recoveryHash, changedAt }
//   Tant que auth.json n'existe pas, le mot de passe de départ est la variable ADMIN_PASSWORD.
import crypto from 'node:crypto';
import { readJson, writeJson } from './store.js';

const ITER = 120_000;
const SESSION_MS = 12 * 3_600_000;

const pbkdf2 = (secret, salt) => crypto.pbkdf2Sync(String(secret), salt, ITER, 32, 'sha256').toString('hex');
const same = (a, b) => {
  const x = Buffer.from(String(a)), y = Buffer.from(String(b));
  return x.length === y.length && crypto.timingSafeEqual(x, y);
};

export async function checkPassword(password) {
  if (!password) return false;
  const auth = await readJson('auth.json');
  if (auth?.hash) return same(pbkdf2(password, auth.salt), auth.hash);
  const initial = String(process.env.ADMIN_PASSWORD || '');
  return !!initial && same(password, initial);
}

/** Enregistre un nouveau mot de passe et renvoie un NOUVEAU code de secours (à garder par l'administrateur). */
export async function setPassword(password) {
  const salt = crypto.randomBytes(16).toString('hex');
  const recovery = crypto.randomBytes(6).toString('hex').toUpperCase().match(/.{4}/g).join('-'); // ex. 3F9A-0C21-B7D4
  const recoverySalt = crypto.randomBytes(16).toString('hex');
  await writeJson('auth.json', {
    salt, hash: pbkdf2(password, salt),
    recoverySalt, recoveryHash: pbkdf2(recovery.replace(/-/g, ''), recoverySalt),
    changedAt: Date.now(),
  });
  return recovery;
}

export async function checkRecovery(code) {
  const auth = await readJson('auth.json');
  if (!auth?.recoveryHash || !code) return false;
  return same(pbkdf2(String(code).toUpperCase().replace(/[^0-9A-F]/g, ''), auth.recoverySalt), auth.recoveryHash);
}

export function passwordIsValid(p) {
  return typeof p === 'string' && p.length >= 8 && p.length <= 100;
}

// ---- Session signée (HMAC), pour ne pas renvoyer le mot de passe à chaque requête
const sessionKey = () => crypto.createHash('sha256').update('ravito-session:' + (process.env.LICENSE_PRIVATE_KEY || '')).digest();

export async function newSession() {
  const auth = await readJson('auth.json');
  const body = Buffer.from(JSON.stringify({ exp: Date.now() + SESSION_MS, v: auth?.changedAt || 0 })).toString('base64url');
  const sig = crypto.createHmac('sha256', sessionKey()).update(body).digest('base64url');
  return `${body}.${sig}`;
}

/** Une session reste valable 12 h, et devient invalide si le mot de passe change. */
export async function checkSession(token) {
  const [body, sig] = String(token || '').split('.');
  if (!body || !sig) return false;
  const expected = crypto.createHmac('sha256', sessionKey()).update(body).digest('base64url');
  if (!same(sig, expected)) return false;
  try {
    const s = JSON.parse(Buffer.from(body, 'base64url').toString());
    if (Date.now() > s.exp) return false;
    const auth = await readJson('auth.json');
    return (auth?.changedAt || 0) === s.v;
  } catch {
    return false;
  }
}
