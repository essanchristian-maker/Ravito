// GET /api/cron  (Vercel Cron, chaque matin à 7 h, heure d'Abidjan)
// Récapitulatif + alertes : essais qui finissent dans 2 jours, accès expirés hier, demandes en attente.
import { listPathnames, readJson } from '../lib/store.js';
import { expiry, loadConfig, send, statusOf } from '../lib/core.js';
import { notify } from '../lib/push.js';

const DAY = 86_400_000;

export default async function handler(req, res) {
  const secret = process.env.CRON_SECRET;
  if (!secret || req.headers.authorization !== `Bearer ${secret}`) return send(res, 401, { error: 'non_autorise' });

  const cfg = await loadConfig();
  const now = Date.now();
  const clients = (await Promise.all((await listPathnames('clients/')).map((p) => readJson(p).catch(() => null)))).filter(Boolean);
  const prospects = (await Promise.all((await listPathnames('prospects/')).map((p) => readJson(p).catch(() => null)))).filter(Boolean);

  const endingSoon = clients.filter((c) => {
    const e = expiry(c);
    return e && !c.blocked && e > now && e - now <= 2 * DAY;
  });
  const justExpired = clients.filter((c) => statusOf(c, cfg, now) === 'expire' && statusOf(c, cfg, now - DAY) !== 'expire');
  const claims = clients.filter((c) => c.claim);
  const newProspects = prospects.filter((p) => p.status === 'nouveau');

  for (const c of endingSoon) {
    await notify(`⏳ ${c.paidUntil ? 'Abonnement' : 'Essai'} de ${c.name}`, `Se termine le ${new Date(expiry(c)).toLocaleDateString('fr-FR')}. Pensez à le relancer.`, `/admin/?code=${encodeURIComponent(c.code)}`);
  }
  for (const c of justExpired) {
    await notify(`🔒 ${c.name} est expiré`, 'Son accès est bloqué jusqu\'au paiement. Relancez-le.', `/admin/?code=${encodeURIComponent(c.code)}`);
  }

  const parts = [];
  if (newProspects.length) parts.push(`${newProspects.length} demande(s) à traiter`);
  if (claims.length) parts.push(`${claims.length} paiement(s) à vérifier`);
  if (endingSoon.length) parts.push(`${endingSoon.length} fin(s) d'essai ou d'abonnement sous 2 jours`);
  const active = clients.filter((c) => ['essai', 'paye'].includes(statusOf(c, cfg, now))).length;
  await notify(
    '☀️ Bonjour : votre point Ravito',
    `${active} client(s) actif(s). ${parts.length ? parts.join(', ') + '.' : 'Rien d\'urgent aujourd\'hui.'}`,
    newProspects.length ? '/admin/?tab=prospects' : '/admin/',
  );
  return send(res, 200, { endingSoon: endingSoon.length, justExpired: justExpired.length, claims: claims.length, prospects: newProspects.length });
}
