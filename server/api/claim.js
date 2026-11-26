// POST /api/claim  { code, deviceId, months, amount }
// Le client appuie sur « J'ai payé » dans l'application : l'administrateur est prévenu pour vérifier son Orange Money.
import { readJson, writeJson } from '../lib/store.js';
import { body, loadConfig, normalizeCode, send } from '../lib/core.js';
import { notify } from '../lib/push.js';

export default async function handler(req, res) {
  if (req.method !== 'POST') return send(res, 405, { error: 'methode' });
  const b = body(req);
  const code = normalizeCode(b.code);
  const client = code ? await readJson(`clients/${code}.json`) : null;
  if (!client) return send(res, 404, { error: 'code_inconnu' });
  if (client.deviceId !== String(b.deviceId || '')) return send(res, 409, { error: 'deja_utilise' });

  const cfg = await loadConfig();
  const months = Math.max(1, Math.min(24, Math.round(Number(b.months) || 1)));
  const amount = cfg.priceFcfa * months;
  const previous = client.claim;
  client.claim = { at: Date.now(), months, amount };
  await writeJson(`clients/${code}.json`, client);

  // Pas de nouvelle notification si la même déclaration a été envoyée il y a moins d'une heure.
  if (!previous || previous.months !== months || Date.now() - previous.at > 3_600_000) {
    await notify(
      `💰 ${client.name} dit avoir payé`,
      `${amount.toLocaleString('fr-FR')} F pour ${months} mois. Vérifiez votre Orange Money puis validez.`,
      `/admin/?code=${encodeURIComponent(code)}`,
    );
  }
  return send(res, 200, { ok: true });
}
