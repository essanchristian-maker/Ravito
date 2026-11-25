// POST /api/checkin
// Le téléphone envoie son état de FONCTIONNEMENT (jamais le contenu des transactions) et reçoit
// une licence à jour + la dernière version disponible.
// { code, deviceId, appVersion, versionName, model, android, operators[],
//   stats: { "2026-11-25": { opens, sends, receipts, unrecognized, reports, shares, copies } },
//   errors: [{ ts, msg }], unrecognized: [{ ts, op, text }]  // texte déjà masqué sur le téléphone }
import { readJson, writeJson } from '../lib/store.js';
import { body, latestRelease, loadConfig, normalizeCode, send, signLicense } from '../lib/core.js';

const MAX_DAYS = 60;
const MAX_ITEMS = 40;

/** Double sécurité : on remasque les chiffres côté serveur. */
const mask = (s) => String(s || '').replace(/\d+/g, 'N').slice(0, 600);

export default async function handler(req, res) {
  if (req.method !== 'POST') return send(res, 405, { error: 'methode' });
  const b = body(req);
  const code = normalizeCode(b.code);
  const deviceId = String(b.deviceId || '').slice(0, 64);
  if (!code || !deviceId) return send(res, 400, { error: 'requete' });

  const cfg = await loadConfig();
  const client = await readJson(`clients/${code}.json`);
  if (!client) return send(res, 404, { error: 'code_inconnu' });
  if (client.deviceId !== deviceId) return send(res, 409, { error: 'deja_utilise' });

  const statusPath = `status/${code}.json`;
  const st = (await readJson(statusPath)) || { stats: {}, errors: [], unrecognized: [] };
  const now = Date.now();

  // Statistiques par jour : le téléphone renvoie les compteurs complets des derniers jours.
  const stats = { ...(st.stats || {}) };
  for (const [day, counts] of Object.entries(b.stats || {}).slice(0, 14)) {
    if (!/^\d{4}-\d{2}-\d{2}$/.test(day) || typeof counts !== 'object') continue;
    const clean = {};
    for (const [k, v] of Object.entries(counts)) if (Number.isFinite(v)) clean[k.slice(0, 20)] = Math.max(0, Math.round(v));
    stats[day] = clean;
  }
  const days = Object.keys(stats).sort().slice(-MAX_DAYS);

  const errors = [...(st.errors || []), ...(b.errors || []).slice(0, 10).map((e) => ({
    ts: Number(e.ts) || now, msg: String(e.msg || '').slice(0, 1500), v: String(b.versionName || ''),
  }))].slice(-MAX_ITEMS);

  const unrecognized = [...(st.unrecognized || []), ...(b.unrecognized || []).slice(0, 10).map((u) => ({
    ts: Number(u.ts) || now, op: String(u.op || '').slice(0, 10), text: mask(u.text),
  }))].slice(-MAX_ITEMS);

  await writeJson(statusPath, {
    lastSeen: now,
    appVersion: Number(b.appVersion) || 0,
    versionName: String(b.versionName || '').slice(0, 20),
    model: String(b.model || '').slice(0, 80),
    android: String(b.android || '').slice(0, 20),
    operators: Array.isArray(b.operators) ? b.operators.slice(0, 6).map(String) : [],
    stats: Object.fromEntries(days.map((d) => [d, stats[d]])),
    errors,
    unrecognized,
  });

  const latest = await latestRelease();
  return send(res, 200, { token: signLicense(client, cfg, now), latest });
}
