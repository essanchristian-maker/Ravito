// API du portail admin. Toutes les requêtes exigent :  Authorization: Bearer <ADMIN_PASSWORD>
//   GET  ?action=list                     clients + statut + dernière activité
//   GET  ?action=detail&code=X            fiche complète (stats, erreurs, SMS non reconnus masqués)
//   GET  ?action=config                   réglages globaux
//   POST ?action=create   {name, phone, trialDays, note}
//   POST ?action=pay      {code, months, amount}
//   POST ?action=trial    {code, days}            prolonger l'essai
//   POST ?action=block    {code, blocked}
//   POST ?action=transfer {code}                  libérer le code pour un nouveau téléphone
//   POST ?action=update   {code, name, phone, note, flags}
//   POST ?action=delete   {code}                  supprimer le client (le téléphone sera bloqué)
//   GET  ?action=prospects                         demandes reçues par la page /inscription/
//   POST ?action=prospect {id, status, note}        suivi d'une demande (nouveau, contacte, refuse)
//   POST ?action=convert  {id, trialDays}           créer l'accès (code) d'une demande
//   POST ?action=prospect_delete {id}
//   POST ?action=config   {priceFcfa, omNumber, trialDays, checkDays, graceDays, notice}
import crypto from 'node:crypto';
import { listPathnames, readJson, remove, writeJson } from '../lib/store.js';
import { addMonths, body, DEFAULT_CONFIG, expiry, loadConfig, makeCode, normalizeCode, send, statusOf } from '../lib/core.js';

const DAY = 86_400_000;

function authorized(req) {
  const expected = String(process.env.ADMIN_PASSWORD || '');
  const given = String(req.headers.authorization || '').replace(/^Bearer\s+/i, '');
  if (!expected || !given) return false;
  const a = crypto.createHash('sha256').update(expected).digest();
  const b = crypto.createHash('sha256').update(given).digest();
  return crypto.timingSafeEqual(a, b);
}

const sum7 = (stats, key) => {
  const days = Object.keys(stats || {}).sort().slice(-7);
  return days.reduce((t, d) => t + (stats[d]?.[key] || 0), 0);
};

function summary(client, st, cfg) {
  return {
    code: client.code,
    name: client.name,
    phone: client.phone,
    email: client.email || '',
    city: client.city || '',
    activity: client.activity || '',
    prospectId: client.prospectId || null,
    note: client.note || '',
    createdAt: client.createdAt,
    activatedAt: client.activatedAt || null,
    status: statusOf(client, cfg),
    expiry: expiry(client),
    paidUntil: client.paidUntil || null,
    trialEnd: client.trialEnd || null,
    trialDays: client.trialDays,
    blocked: !!client.blocked,
    flags: client.flags || {},
    device: client.device || null,
    hasDevice: !!client.deviceId,
    payments: client.payments || [],
    lastSeen: st?.lastSeen || null,
    versionName: st?.versionName || null,
    operators: st?.operators || [],
    sends7: sum7(st?.stats, 'sends'),
    reports7: sum7(st?.stats, 'shares') + sum7(st?.stats, 'copies'),
    errorsCount: (st?.errors || []).length,
    unrecognizedCount: (st?.unrecognized || []).length,
  };
}

async function getClient(code) {
  const c = normalizeCode(code);
  const client = c ? await readJson(`clients/${c}.json`) : null;
  return client;
}

export default async function handler(req, res) {
  if (!authorized(req)) return send(res, 401, { error: 'non_autorise' });
  const action = String(req.query.action || '');
  const cfg = await loadConfig();
  const b = req.method === 'POST' ? body(req) : {};

  try {
    if (action === 'list') {
      const paths = (await listPathnames('clients/')).filter((p) => p.endsWith('.json'));
      const rows = await Promise.all(paths.map(async (p) => {
        const client = await readJson(p);
        if (!client) return null;
        const st = await readJson(`status/${client.code}.json`).catch(() => null);
        return summary(client, st, cfg);
      }));
      return send(res, 200, { clients: rows.filter(Boolean).sort((a, b2) => (b2.createdAt || 0) - (a.createdAt || 0)), config: cfg });
    }

    if (action === 'detail') {
      const client = await getClient(req.query.code);
      if (!client) return send(res, 404, { error: 'introuvable' });
      const st = await readJson(`status/${client.code}.json`).catch(() => null);
      return send(res, 200, {
        client: summary(client, st, cfg),
        stats: st?.stats || {},
        errors: (st?.errors || []).slice().reverse(),
        unrecognized: (st?.unrecognized || []).slice().reverse(),
        model: st?.model || client.device?.model || null,
        android: st?.android || client.device?.android || null,
      });
    }

    if (action === 'config' && req.method === 'GET') return send(res, 200, { config: cfg });

    if (action === 'prospects') {
      const paths = (await listPathnames('prospects/')).filter((p) => p.endsWith('.json'));
      const rows = (await Promise.all(paths.map((p) => readJson(p).catch(() => null)))).filter(Boolean);
      return send(res, 200, { prospects: rows.sort((a, b2) => b2.createdAt - a.createdAt) });
    }

    if (req.method === 'POST' && ['prospect', 'convert', 'prospect_delete'].includes(action)) {
      const id = String(b.id || '').replace(/[^\w-]/g, '');
      const path = `prospects/${id}.json`;
      const pr = id ? await readJson(path) : null;
      if (!pr) return send(res, 404, { error: 'introuvable' });
      if (action === 'prospect_delete') { await remove(path); return send(res, 200, { deleted: id }); }
      if (action === 'prospect') {
        if (['nouveau', 'contacte', 'refuse'].includes(b.status)) pr.status = b.status;
        if (typeof b.note === 'string') pr.note = b.note.slice(0, 500);
        await writeJson(path, pr);
        return send(res, 200, { prospect: pr });
      }
      // convert : crée le client (code d'invitation) relié à la demande
      if (pr.code) {
        const existing = await readJson(`clients/${pr.code}.json`);
        if (existing) return send(res, 200, { client: summary(existing, null, cfg), prospect: pr });
      }
      const name = `${pr.firstName} ${pr.lastName}`.trim();
      let code;
      for (let i = 0; i < 10; i++) {
        code = makeCode(pr.firstName || name);
        if (!(await readJson(`clients/${code}.json`))) break;
      }
      const client = {
        code, name, phone: pr.whatsapp, email: pr.email || '', city: pr.city || '', activity: pr.activity || '',
        prospectId: pr.id, note: pr.message || '',
        trialDays: Math.max(1, Number(b.trialDays) || cfg.trialDays), createdAt: Date.now(), flags: {}, payments: [],
      };
      await writeJson(`clients/${code}.json`, client, { overwrite: false });
      pr.status = 'converti'; pr.code = code; pr.convertedAt = Date.now();
      await writeJson(path, pr);
      return send(res, 200, { client: summary(client, null, cfg), prospect: pr });
    }

    if (req.method !== 'POST') return send(res, 405, { error: 'methode' });

    if (action === 'config') {
      const next = { ...cfg };
      for (const k of Object.keys(DEFAULT_CONFIG)) {
        if (!(k in b)) continue;
        next[k] = typeof DEFAULT_CONFIG[k] === 'number' ? Math.max(0, Number(b[k]) || 0) : String(b[k]).slice(0, 300);
      }
      await writeJson('config.json', next);
      return send(res, 200, { config: next });
    }

    if (action === 'create') {
      const name = String(b.name || '').trim().slice(0, 80);
      if (!name) return send(res, 400, { error: 'nom_requis' });
      let code;
      for (let i = 0; i < 10; i++) {
        code = makeCode(name);
        if (!(await readJson(`clients/${code}.json`))) break;
      }
      const client = {
        code,
        name,
        phone: String(b.phone || '').trim().slice(0, 30),
        email: String(b.email || '').trim().toLowerCase().slice(0, 120),
        note: String(b.note || '').slice(0, 500),
        trialDays: Math.max(1, Number(b.trialDays) || cfg.trialDays),
        createdAt: Date.now(),
        flags: {},
        payments: [],
      };
      await writeJson(`clients/${code}.json`, client, { overwrite: false });
      return send(res, 200, { client: summary(client, null, cfg) });
    }

    const client = await getClient(b.code);
    if (!client) return send(res, 404, { error: 'introuvable' });
    const now = Date.now();

    if (action === 'pay') {
      const months = Math.max(1, Math.min(24, Number(b.months) || 1));
      const base = Math.max(now, expiry(client) || now);
      client.paidUntil = addMonths(base, months);
      client.payments = [...(client.payments || []), { at: now, months, amount: Number(b.amount) || cfg.priceFcfa * months }];
    } else if (action === 'trial') {
      const days = Math.max(1, Math.min(90, Number(b.days) || 7));
      if (client.paidUntil) client.paidUntil += days * DAY;
      else if (client.trialEnd) client.trialEnd = Math.max(now, client.trialEnd) + days * DAY;
      else client.trialDays = (client.trialDays || cfg.trialDays) + days;
    } else if (action === 'block') {
      client.blocked = !!b.blocked;
    } else if (action === 'transfer') {
      await remove(`bindings/${client.code}.json`);
      client.deviceId = null;
      client.device = null;
    } else if (action === 'delete') {
      await remove(`bindings/${client.code}.json`);
      await remove(`status/${client.code}.json`);
      await remove(`clients/${client.code}.json`);
      return send(res, 200, { deleted: client.code });
    } else if (action === 'update') {
      if (typeof b.name === 'string' && b.name.trim()) client.name = b.name.trim().slice(0, 80);
      if (typeof b.phone === 'string') client.phone = b.phone.trim().slice(0, 30);
      if (typeof b.email === 'string') client.email = b.email.trim().toLowerCase().slice(0, 120);
      if (typeof b.note === 'string') client.note = b.note.slice(0, 500);
      if (b.flags && typeof b.flags === 'object') {
        client.flags = Object.fromEntries(Object.entries(b.flags).map(([k, v]) => [k.slice(0, 30), !!v]));
      }
    } else {
      return send(res, 400, { error: 'action_inconnue' });
    }

    await writeJson(`clients/${client.code}.json`, client);
    return send(res, 200, { client: summary(client, null, cfg) });
  } catch (e) {
    console.error('admin', action, e);
    return send(res, 500, { error: 'serveur', detail: String(e.message || e) });
  }
}
