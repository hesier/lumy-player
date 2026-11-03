import { authorize, failure, HttpError, route } from "./policy.mjs";
import { upstream } from "./upstream.mjs";

function privateResponse(response) {
  const headers = new Headers(response.headers);
  headers.set("Cache-Control", "no-store");
  headers.set("X-Content-Type-Options", "nosniff");
  return new Response(response.body, { status: response.status, headers });
}

// Dependencies are injectable so tests do not contact production services.
export function createWorker({ fetcher = fetch, cache = () => globalThis.caches?.default } = {}) {
  return {
    async fetch(request, env, ctx) {
      try {
        await authorize(request, env);
        const selected = await route(request);
        const url = new URL(request.url);
        url.pathname = selected.path;
        url.search = selected.query?.toString() ?? "";
        const cacheKey = new Request(url.toString());
        const storage = selected.ttl ? cache() : undefined;
        // Authentication and client limits apply to cache hits as well.
        const cached = await storage?.match(cacheKey).catch(() => undefined);
        if (cached) return privateResponse(cached);
        if (!(await env.UPSTREAM_LIMITER.limit({ key: "official" })).success)
          throw new HttpError(429, "rate_limited");
        const result = await upstream(selected, env, request.signal, fetcher);
        if (storage && result.cacheable) {
          const copy = result.response.clone();
          copy.headers.set("Cache-Control", `public, max-age=${selected.ttl}`);
          ctx.waitUntil(storage.put(cacheKey, copy).catch(() => {}));
        }
        return privateResponse(result.response);
      } catch (error) {
        return failure(error instanceof HttpError ? error.status : 503,
          error instanceof HttpError ? error.message : "service_unavailable");
      }
    },
  };
}

export default createWorker();
