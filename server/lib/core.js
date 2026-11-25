import crypto from 'node:crypto';
import { readJson, writeJson } from './store.js';

const DAY = 86_400_000;

export const DEFAULT_CONFIG = {
  priceFcfa: 5000,
  omNumber: '07 48 86 96 03',
  trialDays: 14,
  /** Le téléphone doit se connecter au moins tous les N jours pour garder une licence valide. */
  checkDays: 7,
  /** Jours de tolérance après la fin de l'essai / de l'abonnement. */
  graceDays: 2,
  /** Message affiché à tous les utilisateurs (vide = aucun). */
  notice: '',
};

export async function loadConfig() {
  return { ...DEFAULT_CONFIG, ...((await readJson('config.json')) || {}) };
}

export function normalizeCode(code) {
  return String(code || '').trim().toUpperCase().replace(/\s+/g, '');
}

/** Code d'invitation lisible : NOM-1234 (lettres du nom, sans accents). */
export function makeCode(name) {
  const letters = String(name || 'CLIENT')
    .normalize('NFD').replace(/[̀-ͯ]/g, '')
    .toUpperCase().replace(/[^A-Z]/g, '').slice(0, 6) || 'CLIENT';
  const digits = String(crypto.randomInt(1000, 10000));
  return `${letters}-${digits}`;
}

/** Date de fin de droit : abonnement payé, sinon essai. */
export function expiry(client) {
  return client.paidUntil || client.trialEnd || null;
}

/** Statut lisible du client. */
export function statusOf(client, cfg, now = Date.now()) {
  if (client.blocked) return 'bloque';
  if (!client.deviceId && !client.activatedAt) return 'invite';
  const exp = expiry(client);
  if (!exp) return 'essai';
  if (now > exp + cfg.graceDays * DAY) return 'expire';
  return client.paidUntil ? 'paye' : 'essai';
}

/** Ajoute des mois calendaires. */
export function addMonths(ms, months) {
  const d = new Date(ms);
  const day = d.getUTCDate();
  d.setUTCMonth(d.getUTCMonth() + months);
  if (d.getUTCDate() < day) d.setUTCDate(0); // 31 janv. + 1 mois -> fin février
  return d.getTime();
}

/**
 * Licence signée (ECDSA P-256 / SHA-256) que le téléphone vérifie sans Internet.
 * Format : base64url(JSON) + "." + base64url(signature DER).
 */
export function signLicense(client, cfg, now = Date.now()) {
  const payload = {
    v: 1,
    code: client.code,
    dev: client.deviceId,
    name: client.name || '',
    st: client.blocked ? 'blocked' : client.paidUntil ? 'paid' : 'trial',
    exp: expiry(client) || 0,
    grace: cfg.graceDays * DAY,
    chk: now + cfg.checkDays * DAY,
    iat: now,
    flags: client.flags || {},
    price: cfg.priceFcfa,
    om: cfg.omNumber,
    notice: cfg.notice || '',
  };
  const body = Buffer.from(JSON.stringify(payload), 'utf8');
  const key = String(process.env.LICENSE_PRIVATE_KEY || '').replace(/\\n/g, '\n');
  const sig = crypto.sign('sha256', body, { key, dsaEncoding: 'der' });
  return `${body.toString('base64url')}.${sig.toString('base64url')}`;
}

const REPO = 'essanchristian-maker/Ravito';

/** Dernière version publiée sur GitHub (mise en cache 10 min). */
export async function latestRelease() {
  const cached = await readJson('cache/latest.json').catch(() => null);
  if (cached && Date.now() - cached.fetchedAt < 10 * 60_000) return cached.release;
  try {
    const r = await fetch(`https://api.github.com/repos/${REPO}/releases/latest`, {
      headers: { Accept: 'application/vnd.github+json', 'User-Agent': 'ravito-server' },
    });
    if (!r.ok) throw new Error(`GitHub ${r.status}`);
    const j = await r.json();
    const versionCode = Number(String(j.tag_name || '').split('.').pop()) || 0;
    const asset = (j.assets || []).find((a) => a.name === 'Ravito.apk');
    const release = asset
      ? { versionCode, versionName: String(j.tag_name).replace(/^v/, ''), url: asset.browser_download_url, size: asset.size }
      : null;
    await writeJson('cache/latest.json', { fetchedAt: Date.now(), release }).catch(() => {});
    return release;
  } catch {
    return cached ? cached.release : null;
  }
}

export function send(res, status, data) {
  res.setHeader('Content-Type', 'application/json; charset=utf-8');
  res.setHeader('Cache-Control', 'no-store');
  res.status(status).send(JSON.stringify(data));
}

/** Corps JSON (Vercel le parse déjà ; sécurité si c'est une chaîne). */
export function body(req) {
  if (!req.body) return {};
  if (typeof req.body === 'string') {
    try { return JSON.parse(req.body); } catch { return {}; }
  }
  return req.body;
}
