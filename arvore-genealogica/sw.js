// Service worker: guarda os arquivos do app para abrir sem internet.
// Só mexe em arquivos do próprio site (nunca em dados do usuário, que ficam no localStorage).
const CACHE = 'arvore-genealogica-v1';
const FILES = [
  './',
  'index.html',
  'manifest.webmanifest',
  'css/app.css',
  'vendor/family-chart.css',
  'vendor/d3.min.js',
  'vendor/family-chart.min.js',
  'js/cpf.js',
  'js/dates.js',
  'js/model.js',
  'js/gedcom.js',
  'js/app.js',
  'icons/icon-192.png',
  'icons/icon-512.png',
  'icons/icon-maskable-512.png',
  'icons/apple-touch-icon.png',
];

self.addEventListener('install', (event) => {
  event.waitUntil(caches.open(CACHE).then((cache) => cache.addAll(FILES)).then(() => self.skipWaiting()));
});

self.addEventListener('activate', (event) => {
  event.waitUntil(
    caches.keys()
      .then((keys) => Promise.all(keys.filter((key) => key !== CACHE).map((key) => caches.delete(key))))
      .then(() => self.clients.claim())
  );
});

// Abre na hora com o que está guardado e atualiza o cache em segundo plano:
// a versão nova aparece na próxima abertura do app.
self.addEventListener('fetch', (event) => {
  const request = event.request;
  if (request.method !== 'GET' || new URL(request.url).origin !== self.location.origin) return;
  event.respondWith(
    caches.open(CACHE).then(async (cache) => {
      const cached = await cache.match(request, { ignoreSearch: true });
      const refresh = fetch(request)
        .then((response) => {
          if (response.ok) cache.put(request, response.clone());
          return response;
        })
        .catch(() => cached);
      return cached || refresh;
    })
  );
});
