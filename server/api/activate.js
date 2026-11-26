// POST /api/activate  { code, deviceId, model, android, appVersion }
// Lie le code d'invitation à CE téléphone (une seule fois) et renvoie la licence signée.
import { readJson, writeJson } from '../lib/store.js';
import { body, loadConfig, normalizeCode, send, signLicense } from '../lib/core.js';
import { notify } from '../lib/push.js';

const DAY = 86_400_000;

export default async function handler(req, res) {
  if (req.method !== 'POST') return send(res, 405, { error: 'methode' });
  const b = body(req);
  const code = normalizeCode(b.code);
  const deviceId = String(b.deviceId || '').slice(0, 64);
  if (!code || !deviceId) return send(res, 400, { error: 'requete' });

  const cfg = await loadConfig();
  const path = `clients/${code}.json`;
  const client = await readJson(path);
  if (!client) return send(res, 404, { error: 'code_inconnu' });
  if (client.blocked) return send(res, 403, { error: 'bloque' });

  if (client.deviceId && client.deviceId !== deviceId) return send(res, 409, { error: 'deja_utilise' });

  if (!client.deviceId) {
    // Création atomique : si deux téléphones essaient le même code, un seul gagne.
    try {
      await writeJson(`bindings/${code}.json`, { deviceId, at: Date.now() }, { overwrite: false });
    } catch {
      const binding = await readJson(`bindings/${code}.json`);
      if (!binding || binding.deviceId !== deviceId) return send(res, 409, { error: 'deja_utilise' });
    }
    const now = Date.now();
    client.deviceId = deviceId;
    client.activatedAt = client.activatedAt || now;
    if (!client.trialEnd && !client.paidUntil) {
      client.trialEnd = now + (client.trialDays || cfg.trialDays) * DAY;
    }
    client.device = { model: String(b.model || '').slice(0, 80), android: String(b.android || '').slice(0, 20) };
    await writeJson(path, client);
    await notify(`✅ ${client.name} a activé Ravito`, `${client.device.model || 'Téléphone'} · essai jusqu'au ${new Date(client.trialEnd || client.paidUntil).toLocaleDateString('fr-FR')}`, `/admin/?code=${encodeURIComponent(code)}`);
  }

  return send(res, 200, { token: signLicense(client, cfg) });
}
