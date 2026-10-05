const MAX_SCREENSHOT_BASE64 = 5_500_000;
const MAX_TITLE = 200;
const MAX_DESCRIPTION = 8000;
const CANONICAL_HOST = "meacorelauncher.meacore-enterprise.workers.dev";

function json(statusCode, payload) {
  return new Response(JSON.stringify(payload), {
    status: statusCode,
    headers: {
      "Content-Type": "application/json; charset=utf-8",
      "Cache-Control": "no-store"
    }
  });
}

function addSecurityHeaders(response, pathname) {
  const h = new Headers(response.headers);
  h.set("X-Content-Type-Options", "nosniff");
  h.set("X-Frame-Options", "DENY");
  h.set("Referrer-Policy", "strict-origin-when-cross-origin");
  h.set("Permissions-Policy", "geolocation=(), camera=(), microphone=()");

  // Cache control based on file extension
  if (!h.has("Cache-Control")) {
    const ext = pathname.split(".").pop().toLowerCase();
    if (["css", "js", "png", "ico", "svg", "woff", "woff2", "ttf"].includes(ext)) {
      h.set("Cache-Control", "public, max-age=31536000, immutable");
    } else if (ext === "xml") {
      h.set("Cache-Control", "public, max-age=3600");
    } else if (ext === "txt") {
      h.set("Cache-Control", "public, max-age=86400");
    } else {
      h.set("Cache-Control", "public, max-age=300, must-revalidate");
    }
  }
  return new Response(response.body, { status: response.status, statusText: response.statusText, headers: h });
}

export default {
  async fetch(request, env, ctx) {
    const url = new URL(request.url);

    // ── Sitemap: static file first, dynamic fallback ──────────────
    if (url.pathname === "/sitemap.xml") {
      // Try serving the static file
      const staticRes = await env.ASSETS.fetch(request);
      if (staticRes.status !== 404 && staticRes.status !== 200) {
        return staticRes; // let the redirect or error pass through
      }
      if (staticRes.status === 200) {
        return addSecurityHeaders(staticRes, "/sitemap.xml");
      }
      // Fallback: generate dynamic sitemap with lastmod from GitHub
      let lastmod = new Date().toISOString().split("T")[0];
      try {
        const gh = await fetch(
          "https://api.github.com/repos/MeaCore-Enterprise/MeaCoreLauncher/releases/latest",
          { headers: { Accept: "application/vnd.github.v3+json", "User-Agent": "MeaCoreLauncher-Website" } }
        );
        if (gh.ok) {
          const d = await gh.json();
          lastmod = d.published_at?.split("T")[0] || lastmod;
        }
      } catch {}

      const base = `https://${CANONICAL_HOST}`;
      return new Response(
        `<?xml version="1.0" encoding="UTF-8"?>
<urlset xmlns="http://www.sitemaps.org/schemas/sitemap/0.9">
  <url>
    <loc>${base}/</loc>
    <lastmod>${lastmod}</lastmod>
    <changefreq>weekly</changefreq>
    <priority>1.0</priority>
  </url>
  <url>
    <loc>${base}/changelog</loc>
    <lastmod>${lastmod}</lastmod>
    <changefreq>weekly</changefreq>
    <priority>0.8</priority>
  </url>
</urlset>`,
        { headers: { "Content-Type": "application/xml; charset=utf-8", "Cache-Control": "public, max-age=3600" } }
      );
    }

    // ── Bug report ────────────────────────────────────────────────
    if (url.pathname === "/.netlify/functions/report-bug") {
      if (request.method !== "POST") return json(405, { error: "Método no permitido. Usa POST." });

      let payload;
      try { payload = await request.json(); }
      catch { return json(400, { error: "El cuerpo de la petición no es JSON válido." }); }

      const { name, email, title, description, screenshot, screenshotName, honeypot } = payload;
      if (honeypot) return json(200, { ok: true });

      if (!email || !title || !description)
        return json(400, { error: "Faltan campos obligatorios: correo, título y descripción." });
      if (!/^[^\s@]+@[^\s@]+\.[^\s@]+$/.test(String(email)))
        return json(400, { error: "El correo no tiene un formato válido." });
      if (String(title).length > MAX_TITLE)
        return json(400, { error: `El título no puede superar ${MAX_TITLE} caracteres.` });
      if (String(description).length > MAX_DESCRIPTION)
        return json(400, { error: `La descripción no puede superar ${MAX_DESCRIPTION} caracteres.` });

      const token = env.GITHUB_TOKEN;
      const repo = env.GITHUB_REPO;
      const branch = env.GITHUB_BRANCH || "main";
      if (!token || !repo) return json(500, { error: "El servidor de reportes no está configurado correctamente." });

      const ghHeaders = {
        Authorization: `Bearer ${token}`,
        Accept: "application/vnd.github+json",
        "X-GitHub-Api-Version": "2022-11-28",
        "User-Agent": "MeaCore-Bug-Reporter",
        "Content-Type": "application/json"
      };

      let screenshotUrl = null;
      if (screenshot && screenshotName) {
        if (typeof screenshot !== "string" || screenshot.length > MAX_SCREENSHOT_BASE64)
          return json(413, { error: "La captura excede el tamaño máximo permitido (4 MB)." });

        const safeName = String(screenshotName).replace(/[^a-zA-Z0-9.\-_]/g, "_").slice(-80);
        const path = `bug-reports/${Date.now()}-${safeName}`;
        try {
          const upload = await fetch(
            `https://api.github.com/repos/${repo}/contents/${encodeURIComponent(path).replace(/%2F/g, "/")}`,
            { method: "PUT", headers: ghHeaders, body: JSON.stringify({ message: `chore(bug-report): captura para "${String(title).slice(0, 60)}"`, content: screenshot, branch }) }
          );
          if (!upload.ok) return json(502, { error: "No se pudo subir la captura al repositorio." });
          const uploadJson = await upload.json();
          screenshotUrl = uploadJson?.content?.download_url || null;
        } catch { return json(502, { error: "Fallo de red al subir la captura." }); }
      }

      const reporter = (name && String(name).trim()) || "Anónimo";
      const bodyParts = [
        `**Reportado por:** ${reporter}`,
        `**Correo de contacto:** ${email}`,
        "",
        "### Descripción del problema",
        description
      ];
      if (screenshotUrl) bodyParts.push("", "### Captura de pantalla", `![captura](${screenshotUrl})`);
      bodyParts.push("", "---", "_Este reporte fue enviado automáticamente desde el formulario de la web de MeaCore Launcher._");

      try {
        const issueRes = await fetch(`https://api.github.com/repos/${repo}/issues`, {
          method: "POST",
          headers: ghHeaders,
          body: JSON.stringify({ title: `[Bug] ${title}`, body: bodyParts.join("\n"), labels: ["bug", "web-report"] })
        });
        if (!issueRes.ok) return json(502, { error: "No se pudo crear el issue en GitHub." });
        const issue = await issueRes.json();
        return json(200, { ok: true, url: issue.html_url, number: issue.number });
      } catch { return json(502, { error: "Fallo de red al crear el issue en GitHub." }); }
    }

    // ── Latest release con caché ──────────────────────────────────
    if (url.pathname === "/api/latest-release") {
      if (request.method === "POST") {
        return json(405, { error: "Método no permitido" });
      }

      const repo = env.GITHUB_REPO || "MeaCore-Enterprise/MeaCoreLauncher";

      // Fuente principal: latest.json, que publica el propio CI como asset de la release.
      // La URL /releases/latest/download/ NO consume la cuota de la API de GitHub, así que
      // este endpoint sigue answering aunque el launcher haya agotado sus 60 peticiones/hora
      // o GitHub esté limitando al edge. El SHA-256 lo calculó el CI sobre el binario real.
      const manifestUrl = `https://github.com/${repo}/releases/latest/download/latest.json`;
      const cacheKey = new Request(manifestUrl, request);

      const cached = await caches.default.match(cacheKey);
      if (cached) {
        const h = new Headers(cached.headers);
        h.set("X-Cache", "HIT");
        return new Response(cached.body, { status: cached.status, headers: h });
      }

      const headers = { Accept: "application/json", "User-Agent": "MeaCoreLauncher-Website" };
      // Token opcional: solo si alguien lo configura. latest.json NO lo necesita.
      if (env.GITHUB_READ_TOKEN) {
        headers.Authorization = `Bearer ${env.GITHUB_READ_TOKEN}`;
      }

      let upstream = await fetch(manifestUrl, { headers });

      // Releases anteriores a latest.json: se cae a la API para no dejar de responder.
      if (upstream.status === 404) {
        const apiUrl = `https://api.github.com/repos/${repo}/releases/latest`;
        upstream = await fetch(apiUrl, {
          headers: { Accept: "application/vnd.github.v3+json", ...headers }
        });
        if (!upstream.ok) {
          return json(upstream.status === 403 || upstream.status === 429 ? 429 : 502, {
            error: "GitHub API no disponible",
            status: upstream.status
          });
        }

        const data = await upstream.json();
        const response = new Response(JSON.stringify({
          tag_name: data.tag_name,
          name: data.name,
          html_url: data.html_url,
          published_at: data.published_at,
          // digest y size son imprescindibles: sin digest el launcher no puede verificar el
          // instalador y se niega a instalarlo automáticamente.
          assets: (data.assets || []).map(a => ({
            name: a.name,
            browser_download_url: a.browser_download_url,
            size: a.size,
            digest: a.digest || null
          }))
        }), {
          status: 200,
          headers: {
            "Content-Type": "application/json; charset=utf-8",
            "Cache-Control": "public, max-age=300, must-revalidate",
            "X-Source": "api"
          }
        });
        ctx.waitUntil(caches.default.put(cacheKey, response.clone()));
        return response;
      }

      if (!upstream.ok) {
        return json(upstream.status === 403 || upstream.status === 429 ? 429 : 502, {
          error: "GitHub no disponible",
          status: upstream.status
        });
      }

      // latest.json ya viene con la forma que el launcher espera (tag_name + assets con
      // name, browser_download_url, size y digest): se reenvía tal cual, validando que
      // trae al menos un asset para no cachear un manifest vacío por error.
      const manifest = await upstream.json();
      if (!manifest || !Array.isArray(manifest.assets) || manifest.assets.length === 0) {
        return json(502, { error: "latest.json sin assets" });
      }

      const response = new Response(JSON.stringify(manifest), {
        status: 200,
        headers: {
          "Content-Type": "application/json; charset=utf-8",
          // TTL corto: una release publicada debe verse rápido, pero el respaldo no debe
          // servir datos de hace horas si el launcher consulta en ese momento.
          "Cache-Control": "public, max-age=300, must-revalidate",
          "X-Source": "latest.json",
          "X-Cache": "MISS"
        }
      });
      ctx.waitUntil(caches.default.put(cacheKey, response.clone()));
      return response;
    }

    // ── Config Supabase ───────────────────────────────────────────
    if (url.pathname === "/api/config") {
      return json(200, {
        SUPABASE_URL: env.SUPABASE_URL || "",
        SUPABASE_KEY: env.SUPABASE_KEY || ""
      });
    }

    // ── Assets estáticos con headers de seguridad ─────────────────
    // Rewrite / to serve index.html internally (html_handling:none disables auto-mapping)
    if (url.pathname === "/") {
      const internalReq = new Request("https://dummy/index.html", request);
      const assetRes = await env.ASSETS.fetch(internalReq);
      if (assetRes.status === 404) {
        return new Response("Página no encontrada", { status: 404, headers: { "Content-Type": "text/plain; charset=utf-8" } });
      }
      return addSecurityHeaders(assetRes, "/");
    }

    // Rewrite /changelog internally to serve changelog.html
    // (no HTTP redirect, no loop, clean URL in browser)
    if (url.pathname === "/changelog") {
      const internalReq = new Request("https://dummy/changelog.html", request);
      const assetRes = await env.ASSETS.fetch(internalReq);
      if (assetRes.status === 404) {
        return new Response("Página no encontrada", { status: 404, headers: { "Content-Type": "text/plain; charset=utf-8" } });
      }
      return addSecurityHeaders(assetRes, "/changelog");
    }

    try {
      const assetRes = await env.ASSETS.fetch(request);
      if (assetRes.status === 404) {
        return new Response("Página no encontrada", { status: 404, headers: { "Content-Type": "text/plain; charset=utf-8" } });
      }
      return addSecurityHeaders(assetRes, url.pathname);
    } catch (err) {
      return new Response("Error interno del servidor", { status: 500, headers: { "Content-Type": "text/plain; charset=utf-8" } });
    }
  }
};
