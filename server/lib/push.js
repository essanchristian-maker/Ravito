// Notifications « Ravito Gestion » (Web Push) vers les appareils de l'administrateur.
//   push/<id>.json = abonnement d'un navigateur / téléphone
import crypto from 'node:crypto';
import * as webpushNs from 'web-push';
const webpush = webpushNs.default || webpushNs;
import { listPathnames, readJson, remove, writeJson } from './store.js';

function configured() {
  const pub = process.env.VAPID_PUBLIC_KEY, priv = process.env.VAPID_PRIVATE_KEY;
  if (!pub || !priv) return false;
  webpush.setVapidDetails('mailto:admin@ravito.app', pub, priv);
  return true;
}

export const vapidPublicKey = () => process.env.VAPID_PUBLIC_KEY || '';

const idOf = (endpoint) => crypto.createHash('sha256').update(String(endpoint)).digest('hex').slice(0, 32);

export async function subscribe(sub, label) {
  if (!sub?.endpoint || !sub?.keys?.p256dh || !sub?.keys?.auth) throw new Error('abonnement invalide');
  await writeJson(`push/${idOf(sub.endpoint)}.json`, { sub, label: String(label || '').slice(0, 80), at: Date.now() });
}

export async function unsubscribe(endpoint) {
  await remove(`push/${idOf(endpoint)}.json`);
}

/** Envoie une notification à tous les appareils abonnés. url = page à ouvrir au toucher. */
export async function notify(title, body, url = '/admin/') {
  if (!configured()) return 0;
  let sent = 0;
  try {
    const paths = await listPathnames('push/');
    await Promise.all(paths.map(async (p) => {
      const rec = await readJson(p).catch(() => null);
      if (!rec?.sub) return;
      try {
        await webpush.sendNotification(rec.sub, JSON.stringify({ title, body, url }), { TTL: 86_400 });
        sent++;
      } catch (e) {
        if (e.statusCode === 404 || e.statusCode === 410) await remove(p); // abonnement expiré
      }
    }));
  } catch (e) {
    console.error('notify', e);
  }
  return sent;
}
