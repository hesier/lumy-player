import { HttpError, MAX_RESPONSE_BYTES, UPSTREAM_TIMEOUT_MS, readLimited } from "./policy.mjs";

const ORIGIN = "https://api.dandanplay.net";
const REDIRECTS = new Set([301, 302, 303, 307, 308]);
const MAX_REDIRECTS = 3;
const MAX_METADATA_BYTES = 512 * 1024;

async function headers(url, env) {
  const result = { Accept: "application/json" };
  if (url.origin !== ORIGIN) return result;
  const timestamp = String(Math.floor(Date.now() / 1000));
  const value = env.DANDANPLAY_APP_ID + timestamp + url.pathname + env.DANDANPLAY_APP_SECRET;
  const bytes = new Uint8Array(await crypto.subtle.digest("SHA-256", new TextEncoder().encode(value)));
  result["X-AppId"] = env.DANDANPLAY_APP_ID;
  result["X-Timestamp"] = timestamp;
  result["X-Signature"] = btoa(String.fromCharCode(...bytes));
  return result;
}

function redirectTarget(location, current) {
  if (!location) throw new HttpError(502, "invalid_redirect");
  const next = new URL(location, current);
  if (next.protocol !== "https:" || next.username || next.password || next.port ||
      !/^(api|cas[0-9]*)\.dandanplay\.net$/u.test(next.hostname))
    throw new HttpError(502, "invalid_redirect");
  return next;
}

async function fetchRedirects(route, env, signal, fetcher) {
  let url = new URL(route.path, ORIGIN);
  url.search = route.query?.toString() ?? "";
  for (let hop = 0; hop <= MAX_REDIRECTS; hop++) {
    const requestHeaders = await headers(url, env);
    if (route.body !== undefined) requestHeaders["Content-Type"] = "application/json";
    const response = await fetcher(url.toString(), {
      method: route.body === undefined ? "GET" : "POST", headers: requestHeaders,
      body: route.body, redirect: "manual", signal,
    });
    if (!REDIRECTS.has(response.status)) return response;
    await response.body?.cancel();
    if (!route.comments || hop === MAX_REDIRECTS) throw new HttpError(502, "unexpected_redirect");
    url = redirectTarget(response.headers.get("Location"), url);
  }
}

export async function upstream(route, env, requestSignal, fetcher) {
  const controller = new AbortController();
  const abort = () => controller.abort();
  const timer = setTimeout(abort, UPSTREAM_TIMEOUT_MS);
  requestSignal.addEventListener("abort", abort, { once: true });
  if (requestSignal.aborted) abort();
  const finish = () => { clearTimeout(timer); requestSignal.removeEventListener("abort", abort); };
  try {
    const response = await fetchRedirects(route, env, controller.signal, fetcher);
    checkResponse(response, route.path === "/api/v2/match");
    const contentLength = Number(response.headers.get("Content-Length"));
    if (contentLength > MAX_RESPONSE_BYTES) throw new HttpError(502, "upstream_too_large");
    if (route.comments) return { response: streamComments(response, controller, finish), cacheable: false };
    const bytes = await readLimited(response.body, MAX_METADATA_BYTES);
    let result;
    try { result = JSON.parse(new TextDecoder().decode(bytes)); }
    catch { throw new HttpError(502, "invalid_upstream_json"); }
    if (!result || typeof result !== "object" || Array.isArray(result))
      throw new HttpError(502, "invalid_upstream_json");
    if (result.success === false) return { response: Response.json({
      success: false, ...(Number.isInteger(result.errorCode) ? { errorCode: result.errorCode } : {}),
    }), cacheable: false };
    return { response: new Response(bytes, { headers: { "Content-Type": "application/json" } }),
      cacheable: result?.success === true };
  } catch (error) {
    const aborted = controller.signal.aborted;
    controller.abort();
    finish();
    if (error instanceof HttpError) throw error;
    throw new HttpError(aborted ? 504 : 502, "upstream_failed");
  } finally { if (!route.comments) finish(); }
}

function checkResponse(response, optionalMatch) {
  if (response.status !== 200) {
    response.body?.cancel().catch(() => {});
    const preserved = [400, 401, 403, 404, 429].includes(response.status) ||
      (optionalMatch && [405, 501].includes(response.status));
    const status = preserved ? response.status : 502;
    throw new HttpError(status, "upstream_rejected");
  }
  if (!response.headers.get("Content-Type")?.toLowerCase().includes("application/json"))
    throw new HttpError(502, "invalid_upstream_type");
}

function streamComments(response, controller, finish) {
  if (!response.body) { finish(); throw new HttpError(502, "empty_upstream"); }
  const reader = response.body.getReader();
  let length = 0;
  const body = new ReadableStream({
    async pull(output) {
      try {
        const { done, value } = await reader.read();
        if (done) { finish(); reader.releaseLock(); output.close(); return; }
        length += value.byteLength;
        if (length > MAX_RESPONSE_BYTES) throw new HttpError(502, "upstream_too_large");
        output.enqueue(value);
      } catch (error) {
        controller.abort(); finish(); await reader.cancel().catch(() => {}); output.error(error);
      }
    },
    async cancel() { controller.abort(); finish(); await reader.cancel().catch(() => {}); },
  });
  return new Response(body, { headers: { "Content-Type": "application/json" } });
}
