export const MAX_RESPONSE_BYTES = 8 * 1024 * 1024;
export const MAX_REQUEST_BYTES = 4096;
export const UPSTREAM_TIMEOUT_MS = 15_000;

export class HttpError extends Error {
  constructor(status, code) {
    super(code);
    this.status = status;
  }
}

export function failure(status, code) {
  const headers = { "Cache-Control": "no-store" };
  if (status === 429) headers["Retry-After"] = "60";
  return Response.json({ success: false, errorMessage: code }, { status, headers });
}

async function digest(value) {
  return new Uint8Array(await crypto.subtle.digest("SHA-256", new TextEncoder().encode(value)));
}

export async function authorize(request, env) {
  const required = ["DANDANPLAY_APP_ID", "DANDANPLAY_APP_SECRET", "PROXY_TOKEN"];
  if (required.some(key => !env[key]) || env.PROXY_TOKEN.length < 32 ||
      !env.CLIENT_LIMITER || !env.UPSTREAM_LIMITER) throw new HttpError(503, "not_configured");
  const provided = request.headers.get("Authorization") ?? "";
  if (provided.length > 512) throw new HttpError(401, "unauthorized");
  const actual = await digest(provided);
  const expected = await digest(`Bearer ${env.PROXY_TOKEN}`);
  let difference = 0;
  for (let i = 0; i < actual.length; i++) difference |= actual[i] ^ expected[i];
  if (difference !== 0) throw new HttpError(401, "unauthorized");
  // IP limits may affect shared NATs; the separate upstream limit protects cache misses.
  const key = request.headers.get("CF-Connecting-IP") || "local";
  if (!(await env.CLIENT_LIMITER.limit({ key })).success) throw new HttpError(429, "rate_limited");
}

export async function readLimited(stream, limit) {
  if (!stream) return new Uint8Array();
  const reader = stream.getReader();
  const chunks = [];
  let length = 0;
  try {
    while (true) {
      const { done, value } = await reader.read();
      if (done) break;
      length += value.byteLength;
      if (length > limit) throw new HttpError(413, "body_too_large");
      chunks.push(value);
    }
  } catch (error) {
    await reader.cancel().catch(() => {});
    throw error;
  } finally { reader.releaseLock(); }
  const bytes = new Uint8Array(length);
  let offset = 0;
  for (const chunk of chunks) { bytes.set(chunk, offset); offset += chunk.length; }
  return bytes;
}

function queries(url, allowed) {
  for (const key of url.searchParams.keys()) {
    if (!allowed.includes(key) || url.searchParams.getAll(key).length !== 1)
      throw new HttpError(400, "invalid_query");
  }
}

function text(value, maximum) {
  if (typeof value !== "string" || !value.trim() || value.length > maximum || /[\u0000-\u001f]/u.test(value))
    throw new HttpError(400, "invalid_text");
  return value.trim();
}

export async function route(request) {
  const url = new URL(request.url);
  const path = url.pathname;
  const expected = path === "/api/v2/match" ? "POST" : "GET";
  if (request.method !== expected) throw new HttpError(405, "method_not_allowed");
  if (path === "/api/v2/match") return matchRoute(request, url);
  if (path === "/api/v2/search/anime") {
    queries(url, ["keyword"]);
    const keyword = text(url.searchParams.get("keyword"), 120);
    return { path, query: new URLSearchParams({ keyword }), ttl: 1800 };
  }
  if (/^\/api\/v2\/bangumi\/[a-zA-Z0-9_-]{1,160}$/u.test(path)) {
    queries(url, []);
    return { path, ttl: 21_600 };
  }
  const comment = path.match(/^\/api\/v2\/comment\/([1-9][0-9]{0,18})$/u);
  if (comment && BigInt(comment[1]) <= 9223372036854775807n) {
    queries(url, ["withRelated"]);
    const related = url.searchParams.get("withRelated") ?? "true";
    if (!["true", "false"].includes(related)) throw new HttpError(400, "invalid_query");
    return { path, query: new URLSearchParams({ withRelated: related }), comments: true, ttl: 0 };
  }
  throw new HttpError(404, "not_found");
}

async function matchRoute(request, url) {
  queries(url, []);
  if (request.headers.get("Content-Type")?.split(";")[0].trim() !== "application/json")
    throw new HttpError(415, "json_required");
  const bytes = await readLimited(request.body, MAX_REQUEST_BYTES);
  let body;
  try { body = JSON.parse(new TextDecoder().decode(bytes)); }
  catch { throw new HttpError(400, "invalid_json"); }
  if (!body || typeof body !== "object" || Array.isArray(body) ||
      Object.keys(body).some(key => !["fileName", "matchMode", "fileHash"].includes(key)) ||
      (body.matchMode !== undefined && body.matchMode !== "fileNameOnly"))
    throw new HttpError(400, "invalid_match");
  return { path: url.pathname, ttl: 0, body: JSON.stringify({
    fileName: text(body.fileName, 256), matchMode: "fileNameOnly", fileHash: "0".repeat(32),
  }) };
}
