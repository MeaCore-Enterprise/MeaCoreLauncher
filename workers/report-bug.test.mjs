// Tests del endpoint /api/latest-release del Worker, con el runner nativo de Node (sin dependencias).
//
//   node --test workers/
//
// Lo que se protege aqui es la ruta de datos que usa el launcher para decidir si hay
// actualizacion. Un fallo silencioso en este endpoint significa "los usuarios no reciben
// actualizaciones" sin que nadie se entere, asi que la precedencia de fuentes y el fallback
// se fijan con tests y no solo con una comprobacion manual.

import { test, describe, beforeEach, afterEach } from "node:test";
import assert from "node:assert/strict";

import worker from "./report-bug.js";

const REPO = "MeaCore-Enterprise/MeaCoreLauncher";
const MANIFEST_URL = `https://github.com/${REPO}/releases/latest/download/latest.json`;
const API_URL = `https://api.github.com/repos/${REPO}/releases/latest`;

const MANIFEST = {
  tag_name: "v1.6.2",
  name: "v1.6.2",
  html_url: `https://github.com/${REPO}/releases/tag/v1.6.2`,
  published_at: "2026-10-04T12:00:00Z",
  assets: [
    {
      name: "MeaCoreLauncher-1.6.2.exe",
      browser_download_url: `https://github.com/${REPO}/releases/download/v1.6.2/MeaCoreLauncher-1.6.2.exe`,
      size: 12345,
      digest: "sha256:2b6cd692ef4a762fa949cddd6fc8a50b9d969647d69accb8b98b518fdabf95aa",
    },
    {
      name: "meacore-launcher_1.6.2_amd64.deb",
      browser_download_url: `https://github.com/${REPO}/releases/download/v1.6.2/meacore-launcher_1.6.2_amd64.deb`,
      size: 54321,
      digest: "sha256:d3e77c7b7d9eb1b3dca5d9918d9208a6f741c0e5f06beab4b64df4b3ca651114",
    },
  ],
};

// Respuesta de la API: como la devuelve /releases/latest. Nótese que un asset SIN digest
// llega con digest: null desde nuestra transformacion, y el launcher no puede verificarlo.
const API_RELEASE = {
  tag_name: "v1.6.1",
  name: "v1.6.1",
  html_url: `https://github.com/${REPO}/releases/tag/v1.6.1`,
  published_at: "2026-09-01T10:00:00Z",
  assets: [
    {
      name: "MeaCoreLauncher-1.6.1.exe",
      browser_download_url: `https://github.com/${REPO}/releases/download/v1.6.1/MeaCoreLauncher-1.6.1.exe`,
      size: 12000,
      digest: "sha256:2b6cd692ef4a762fa949cddd6fc8a50b9d969647d69accb8b98b518fdabf95aa",
    },
  ],
};

// ── Arneses ────────────────────────────────────────────────────────────────────

/** Sustituye fetch y caches, y registra las URLs solicitadas. */
function installFetchStub(routes) {
  const calls = [];
  globalThis.fetch = async (input, init) => {
    const url = typeof input === "string" ? input : input.url;
    calls.push({ url, init });
    for (const [pattern, respond] of Object.entries(routes)) {
      if (url === pattern) return respond(url, init);
    }
    throw new Error(`fetch inesperado a ${url} (no estaba en el stub)`);
  };

  // caches.default: Map por URL. put/match son los dos metodos que usa el Worker.
  const store = new Map();
  globalThis.caches = {
    default: {
      async match(request) {
        const url = typeof request === "string" ? request : request.url;
        return store.get(url) ?? undefined;
      },
      async put(request, response) {
        const url = typeof request === "string" ? request : request.url;
        store.set(url, response);
      },
    },
  };

  return { calls, store };
}

const ok = (body) => async () =>
  new Response(JSON.stringify(body), { status: 200, headers: { "Content-Type": "application/json" } });
const status = (code) => async () => new Response("nope", { status: code });

let originalFetch;
let originalCaches;

beforeEach(() => {
  originalFetch = globalThis.fetch;
  originalCaches = globalThis.caches;
});

afterEach(() => {
  globalThis.fetch = originalFetch;
  globalThis.caches = originalCaches;
});

/** Invoca el endpoint con un ctx que ejecuta los waitUntil de forma sincrona. */
function callEndpoint(env = {}) {
  const pending = [];
  const ctx = { waitUntil: (p) => pending.push(p) };
  return worker
    .fetch(new Request("https://meacorelauncher.meacore-enterprise.workers.dev/api/latest-release"), env, ctx)
    .then(async (res) => {
      // El Worker mete las escrituras de cache en waitUntil: hay que resolverlas antes de
      // inspeccionar el store, o el test comprobaria una cache vacia y pasaria por lo que
      // CASO QUE el cacheo este roto.
      await Promise.all(pending);
      return res;
    });
}

// ── Precedencia de fuentes ─────────────────────────────────────────────────────

describe("/api/latest-release", () => {
  test("sirve latest.json sin llamar a la API de GitHub (no consume cuota)", async () => {
    const { calls } = installFetchStub({ [MANIFEST_URL]: ok(MANIFEST) });

    const res = await callEndpoint();
    const body = await res.json();

    assert.equal(res.status, 200);
    assert.equal(res.headers.get("X-Source"), "latest.json");
    assert.equal(body.tag_name, "v1.6.2");
    assert.equal(body.assets.length, 2);

    // La razon de existir de latest.json: cero peticiones a la API.
    assert.equal(
      calls.filter((c) => c.url.startsWith("https://api.github.com")).length,
      0,
      `no debe llamar a la API, pero llamó: ${calls.map((c) => c.url).join(", ")}`,
    );
  });

  test("reenvía digest y size: sin ellos el launcher no puede verificar el instalador", async () => {
    installFetchStub({ [MANIFEST_URL]: ok(MANIFEST) });

    const body = await (await callEndpoint()).json();

    for (const asset of body.assets) {
      assert.match(asset.digest ?? "", /^sha256:[0-9a-f]{64}$/, `digest ausente/inválido en ${asset.name}`);
      assert.ok(Number.isInteger(asset.size) && asset.size > 0, `size ausente/inválido en ${asset.name}`);
    }
  });

  // ── El fallback que pregunta el usuario ───────────────────────────────────────

  test("latest.json 404 (release antigua) cae a la API de GitHub", async () => {
    const { calls } = installFetchStub({
      [MANIFEST_URL]: status(404),
      [API_URL]: ok(API_RELEASE),
    });

    const res = await callEndpoint();
    const body = await res.json();

    assert.equal(res.status, 200);
    assert.equal(res.headers.get("X-Source"), "api");
    assert.equal(body.tag_name, "v1.6.1");
    assert.equal(body.assets.length, 1);
    assert.equal(body.assets[0].digest, API_RELEASE.assets[0].digest);
    assert.equal(body.assets[0].size, 12000);

    assert.ok(
      calls.some((c) => c.url === API_URL),
      "debería haber consultado la API como respaldo",
    );
  });

  test("el fallback envía Authorization solo si hay GITHUB_READ_TOKEN", async () => {
    const { calls } = installFetchStub({
      [MANIFEST_URL]: status(404),
      [API_URL]: ok(API_RELEASE),
    });

    await callEndpoint();
    const sinToken = calls.find((c) => c.url === API_URL);
    assert.equal(sinToken.init?.headers?.Authorization, undefined, "no debe inventar un token");

    // Sin token, GitHub limita a 60 peticiones/hora por IP: la causa raíz del 502.
    const { calls: calls2 } = installFetchStub({
      [MANIFEST_URL]: status(404),
      [API_URL]: ok(API_RELEASE),
    });

    await callEndpoint({ GITHUB_READ_TOKEN: "ghp_ejemplo" });
    const conToken = calls2.find((c) => c.url === API_URL);
    assert.equal(conToken.init.headers.Authorization, "Bearer ghp_ejemplo");
  });

  test("API con rate limit (403) responde 429, no 502", async () => {
    installFetchStub({ [MANIFEST_URL]: status(404), [API_URL]: status(403) });

    const res = await callEndpoint();

    assert.equal(res.status, 429, "el launcher distingue rate limit de caída, para poder esperar");
    assert.equal((await res.json()).status, 403);
  });

  test("API caída (500) responde 502", async () => {
    installFetchStub({ [MANIFEST_URL]: status(404), [API_URL]: status(500) });

    assert.equal((await callEndpoint()).status, 502);
  });

  test("latest.json 5xx (sin fallback): 502 y sin tocar la API", async () => {
    // 500 en el manifest NO es un release antiguo: reintentar por la API gastaría cuota
    // para nada y, si la API responde bien con datos viejos, serviría info incorrecta.
    const { calls } = installFetchStub({ [MANIFEST_URL]: status(500) });

    const res = await callEndpoint();

    assert.equal(res.status, 502);
    assert.equal(calls.filter((c) => c.url.startsWith("https://api.github.com")).length, 0);
  });

  // ── Manifest inutilizable ─────────────────────────────────────────────────────

  test("latest.json sin assets responde 502 y NO se cachea", async () => {
    const { store } = installFetchStub({ [MANIFEST_URL]: ok({ tag_name: "v1.6.2", assets: [] }) });

    const res = await callEndpoint();

    assert.equal(res.status, 502);
    assert.equal(store.size, 0, "cachear un manifest vacío dejaría el endpoint muerto 5 minutos");
  });

  test("latest.json que no es JSON responde 502", async () => {
    installFetchStub({
      [MANIFEST_URL]: async () => new Response("<html>404</html>", { status: 200 }),
    });

    // No debe propagar una excepción: el launcher espera JSON y un throw sería un 500 opaco.
    const res = await callEndpoint();
    assert.equal(res.status, 502);
  });

  // ── Caché ─────────────────────────────────────────────────────────────────────

  test("la segunda petición se sirve de caché (X-Cache: HIT) sin salir a la red", async () => {
    const { calls } = installFetchStub({ [MANIFEST_URL]: ok(MANIFEST) });

    const first = await callEndpoint();
    assert.equal(first.headers.get("X-Cache"), "MISS");

    const callsAfterFirst = calls.length;
    const second = await callEndpoint();

    assert.equal(second.status, 200);
    assert.equal(second.headers.get("X-Cache"), "HIT");
    assert.equal(second.headers.get("X-Source"), "latest.json");
    assert.equal(calls.length, callsAfterFirst, "la caché no debe volver a salir a la red");
  });

  test("la respuesta se cachea 5 minutos, no para siempre", async () => {
    installFetchStub({ [MANIFEST_URL]: ok(MANIFEST) });

    const res = await callEndpoint();

    // Una release publicada debe verse en minutos, no al reiniciar el cache del edge.
    assert.match(res.headers.get("Cache-Control") ?? "", /max-age=300/);
  });

  test("un 502 no queda cacheado: el siguiente intento vuelve a intentarlo", async () => {
    const { store } = installFetchStub({ [MANIFEST_URL]: status(404), [API_URL]: status(500) });

    assert.equal((await callEndpoint()).status, 502);
    assert.equal(store.size, 0, "un error cacheado deja el endpoint sin actualizaciones hasta que expire");
  });

  test("POST está permitido por GET solamente", async () => {
    installFetchStub({});
    const ctx = { waitUntil: () => {} };

    const res = await worker.fetch(
      new Request("https://meacorelauncher.meacore-enterprise.workers.dev/api/latest-release", { method: "POST" }),
      {},
      ctx,
    );

    assert.equal(res.status, 405);
  });
});