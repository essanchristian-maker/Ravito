// POST /api/signup  (public) : demande d'inscription depuis la page /inscription/
// { firstName, lastName, whatsapp, email, city, activity, operators[], message, consent, website (piège à robots), t (ms à l'ouverture) }
import crypto from 'node:crypto';
import { writeJson } from '../lib/store.js';
import { body, send } from '../lib/core.js';
import { notify } from '../lib/push.js';

const clean = (v, max = 120) => String(v ?? '').replace(/[\u0000-\u001f]/g, ' ').trim().slice(0, max);

export default async function handler(req, res) {
  if (req.method !== 'POST') return send(res, 405, { error: 'methode' });
  const b = body(req);

  // Robots : champ caché rempli, ou formulaire envoyé en moins de 3 secondes.
  if (b.website || (Number(b.t) && Date.now() - Number(b.t) < 3000)) return send(res, 200, { ok: true });

  const firstName = clean(b.firstName, 60);
  const lastName = clean(b.lastName, 60);
  const whatsapp = clean(b.whatsapp, 30).replace(/[^\d+ ]/g, '');
  const email = clean(b.email, 120).toLowerCase();
  if (!firstName || !lastName) return send(res, 400, { error: 'Indiquez votre prénom et votre nom.' });
  if (whatsapp.replace(/\D/g, '').length < 8) return send(res, 400, { error: 'Numéro WhatsApp invalide.' });
  if (email && !/^[^\s@]+@[^\s@]+\.[^\s@]+$/.test(email)) return send(res, 400, { error: 'Adresse e-mail invalide.' });
  if (b.consent !== true) return send(res, 400, { error: 'Merci de cocher la case d\'accord pour être recontacté.' });

  const now = Date.now();
  const id = `${now}-${crypto.randomBytes(3).toString('hex')}`;
  await writeJson(`prospects/${id}.json`, {
    id,
    createdAt: now,
    status: 'nouveau',
    firstName,
    lastName,
    whatsapp,
    email,
    city: clean(b.city, 60),
    activity: clean(b.activity, 60),
    operators: Array.isArray(b.operators) ? b.operators.slice(0, 4).map((o) => clean(o, 10)) : [],
    message: clean(b.message, 500),
    consentAt: now,
  }, { overwrite: false });

  await notify(`🆕 Nouvelle demande : ${firstName} ${lastName}`, [clean(b.city, 60), clean(b.activity, 60), whatsapp].filter(Boolean).join(' · '), '/admin/?tab=prospects');
  return send(res, 200, { ok: true });
}
