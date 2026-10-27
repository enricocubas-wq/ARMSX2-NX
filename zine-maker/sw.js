/* Service worker: deixa o app funcionar offline depois da primeira visita.
 * Estratégia "stale-while-revalidate": responde na hora com o que está em cache e,
 * em segundo plano, baixa a versão nova, que aparece na visita seguinte. Assim o app
 * nunca fica preso numa versão antiga. Mude CACHE para forçar a limpeza total. */
const CACHE = 'zine-maker-v1';
const FILES = [
  './', 'index.html', 'manifest.webmanifest', 'css/style.css',
  'js/core.js', 'js/exif.js', 'js/pdfbuilder.js', 'js/sources.js', 'js/app.js',
  'vendor/pdf-lib.min.js', 'vendor/pdf.min.js', 'vendor/pdf.worker.min.js',
  'icons/icon.svg', 'icons/icon-192.png', 'icons/icon-512.png',
];

self.addEventListener('install', (e) => {
  e.waitUntil(caches.open(CACHE).then((c) => c.addAll(FILES)).then(() => self.skipWaiting()));
});

self.addEventListener('activate', (e) => {
  e.waitUntil(
    caches.keys()
      .then((keys) => Promise.all(keys.filter((k) => k !== CACHE).map((k) => caches.delete(k))))
      .then(() => self.clients.claim())
  );
});

self.addEventListener('fetch', (e) => {
  const req = e.request;
  if (req.method !== 'GET' || new URL(req.url).origin !== location.origin) return;
  e.respondWith(
    caches.open(CACHE).then(async (cache) => {
      const hit = await cache.match(req);
      const refresh = fetch(req)
        .then((res) => {
          if (res.ok) cache.put(req, res.clone());
          return res;
        })
        .catch(() => null);
      if (hit) {
        e.waitUntil(refresh); // atualiza em segundo plano
        return hit;
      }
      return (await refresh) || Response.error();
    })
  );
});
