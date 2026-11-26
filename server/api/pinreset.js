// POST /api/pinreset  { code, deviceId, value }
// Code PIN oublié : le téléphone (déjà lié au code) prouve qu'il appartient bien au client avec
//   - son code personnel Ravito (reçu par WhatsApp), ou
//   - le code de réinitialisation à 6 chiffres donné par le vendeur (valable 24 h).
import { readJson, writeJson } from '../lib/store.js';
import { body, normalizeCode, send } from '../lib/core.js';
import { notify } from '../lib/push.js';

export default async function handler(req, res) {
  if (req.method !== 'POST') return send(res, 405, { error: 'methode' });
  const b = body(req);
  const code = normalizeCode(b.code);
  const client = code ? await readJson(`clients/${code}.json`) : null;
  if (!client || client.deviceId !== String(b.deviceId || '')) {
    await new Promise((r) => setTimeout(r, 700));
    return send(res, 403, { error: 'refuse' });
  }
  const value = String(b.value || '').trim().toUpperCase().replace(/\s+/g, '');
  const byCode = value === client.code;
  const byReset = !!client.pinReset && client.pinReset.exp > Date.now() && value === client.pinReset.code;
  if (!byCode && !byReset) {
    await new Promise((r) => setTimeout(r, 700));
    return send(res, 403, { error: 'refuse' });
  }
  if (byReset) {
    client.pinReset = null; // usage unique
    await writeJson(`clients/${code}.json`, client);
  }
  await notify('🔓 Code PIN réinitialisé', `${client.name} a réinitialisé son code PIN.`, `/admin/?code=${encodeURIComponent(code)}`);
  return send(res, 200, { ok: true });
}
