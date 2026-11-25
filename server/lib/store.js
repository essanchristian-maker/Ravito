// Stockage Vercel Blob (privé). Un fichier JSON par client / statut, écrits par un seul acteur à la fois :
//   clients/<CODE>.json   fiche client (portail admin + activation)
//   bindings/<CODE>.json  liaison code -> téléphone, créée une seule fois (allowOverwrite: false)
//   status/<CODE>.json    dernier état envoyé par le téléphone (version, stats, erreurs, SMS non reconnus masqués)
//   config.json           réglages globaux (prix, numéro Orange Money, durée d'essai, message)
import { put, get, list, del } from '@vercel/blob';

export async function readJson(pathname) {
  const r = await get(pathname, { access: 'private', useCache: false });
  if (!r || r.statusCode !== 200) return null;
  const text = await new Response(r.stream).text();
  return text ? JSON.parse(text) : null;
}

/** Écrit un JSON. overwrite=false : échoue si le fichier existe déjà (création atomique). */
export async function writeJson(pathname, data, { overwrite = true } = {}) {
  await put(pathname, JSON.stringify(data), {
    access: 'private',
    contentType: 'application/json',
    addRandomSuffix: false,
    allowOverwrite: overwrite,
    cacheControlMaxAge: 60,
  });
}

export async function listPathnames(prefix) {
  const out = [];
  let cursor;
  do {
    const r = await list({ prefix, cursor, limit: 1000 });
    out.push(...r.blobs.map((b) => b.pathname));
    cursor = r.hasMore ? r.cursor : undefined;
  } while (cursor);
  return out;
}

export async function remove(pathname) {
  try {
    await del(pathname);
  } catch {
    /* déjà absent */
  }
}
