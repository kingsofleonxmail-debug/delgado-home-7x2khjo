/* Delgado Family Dashboard: offline helper.
   Online: everything loads fresh from the internet, as always.
   Offline: the dashboard can't load live data, so you get Sheepdog Run instead of an error page. */
const CACHE = "delgado-offline-v1";
const KEEP = ["game.html"];

self.addEventListener("install", e => {
  e.waitUntil(caches.open(CACHE).then(c => c.addAll(KEEP)).then(() => self.skipWaiting()));
});
self.addEventListener("activate", e => {
  e.waitUntil(caches.keys().then(keys => Promise.all(keys.filter(k => k !== CACHE).map(k => caches.delete(k)))).then(() => self.clients.claim()));
});
self.addEventListener("fetch", e => {
  const req = e.request;
  if (req.method !== "GET") return;
  const url = new URL(req.url);
  if (url.origin !== location.origin) return;            // Google script, weather, fonts: untouched
  const isGame = url.pathname.endsWith("/game.html");
  if (req.mode === "navigate" || isGame) {
    e.respondWith(
      fetch(req).then(res => {
        if (isGame && res.ok) { const copy = res.clone(); caches.open(CACHE).then(c => c.put("game.html", copy)); }
        return res;
      }).catch(async () => {
        const cached = await caches.match("game.html", { ignoreSearch: true });
        if (isGame && cached) return cached;
        if (cached) {                                    // any page offline → the game, offline mode
          const to = new URL("game.html", self.registration.scope);
          url.searchParams.forEach((v, k) => { if (k !== "recipe") to.searchParams.set(k, v); });
          to.searchParams.set("offline", "1");
          return Response.redirect(to.href, 302);
        }
        return new Response("<h1 style='font-family:sans-serif'>You're offline</h1><p style='font-family:sans-serif'>The dashboard will work again when the internet is back.</p>",
          { status: 503, headers: { "Content-Type": "text/html" } });
      })
    );
  }
});
