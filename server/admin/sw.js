// Service worker de « Ravito Gestion » : installation de l'app et notifications.
// Pas de cache des données clients (tout passe par le serveur, avec la session).
const SHELL = 'ravito-gestion-v1';
const FILES = ['/admin/', '/admin/manifest.webmanifest', '/admin/icon-192.png', '/admin/badge-96.png'];

self.addEventListener('install', (e) => {
  e.waitUntil(caches.open(SHELL).then((c) => c.addAll(FILES)).then(() => self.skipWaiting()));
});

self.addEventListener('activate', (e) => {
  e.waitUntil(
    caches.keys().then((keys) => Promise.all(keys.filter((k) => k !== SHELL).map((k) => caches.delete(k))))
      .then(() => self.clients.claim()),
  );
});

// Page : réseau d'abord (toujours la dernière version), cache seulement hors connexion.
self.addEventListener('fetch', (e) => {
  const url = new URL(e.request.url);
  if (e.request.method !== 'GET' || url.pathname.startsWith('/api/')) return;
  e.respondWith(
    fetch(e.request)
      .then((r) => {
        if (r.ok && FILES.includes(url.pathname)) caches.open(SHELL).then((c) => c.put(e.request, r.clone()));
        return r;
      })
      .catch(() => caches.match(e.request).then((r) => r || caches.match('/admin/'))),
  );
});

self.addEventListener('push', (e) => {
  let data = {};
  try { data = e.data ? e.data.json() : {}; } catch { data = { title: 'Ravito Gestion', body: e.data && e.data.text() }; }
  e.waitUntil(self.registration.showNotification(data.title || 'Ravito Gestion', {
    body: data.body || '',
    icon: '/admin/icon-192.png',
    badge: '/admin/badge-96.png',
    data: { url: data.url || '/admin/' },
    vibrate: [120, 60, 120],
  }));
});

self.addEventListener('notificationclick', (e) => {
  e.notification.close();
  const target = new URL(e.notification.data?.url || '/admin/', self.location.origin).href;
  e.waitUntil((async () => {
    const all = await self.clients.matchAll({ type: 'window', includeUncontrolled: true });
    for (const c of all) {
      if (c.url.startsWith(self.location.origin + '/admin/')) {
        await c.focus();
        c.postMessage({ type: 'open', url: target });
        return;
      }
    }
    await self.clients.openWindow(target);
  })());
});
