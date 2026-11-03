import assert from "node:assert/strict";
import { createHash } from "node:crypto";
import { test } from "node:test";
import { createWorker } from "../src/index.mjs";
import { MAX_RESPONSE_BYTES } from "../src/policy.mjs";

const TOKEN = "test-token-0123456789abcdef0123456789";
const ORIGIN = "https://relay.example.test";
const json = (body, options = {}) => Response.json(body, options);
const env = () => ({
  PROXY_TOKEN: TOKEN, DANDANPLAY_APP_ID: "test-app", DANDANPLAY_APP_SECRET: "test-secret",
  CLIENT_LIMITER: { limit: async () => ({ success: true }) },
  UPSTREAM_LIMITER: { limit: async () => ({ success: true }) },
});

function request(path, options = {}) {
  return new Request(ORIGIN + path, { ...options,
    headers: { Authorization: `Bearer ${TOKEN}`, ...options.headers } });
}

async function call(worker, input, configuration = env()) {
  const pending = [];
  const response = await worker.fetch(input, configuration, { waitUntil: promise => pending.push(promise) });
  await Promise.all(pending);
  return response;
}

test("missing secrets, limiter bindings and wrong tokens fail without upstream requests", async () => {
  let calls = 0;
  const worker = createWorker({ fetcher: async () => { calls++; return json({}); } });
  for (const key of ["PROXY_TOKEN", "DANDANPLAY_APP_ID", "DANDANPLAY_APP_SECRET", "CLIENT_LIMITER", "UPSTREAM_LIMITER"]) {
    const configuration = env(); delete configuration[key];
    assert.equal((await call(worker, request("/api/v2/search/anime?keyword=show"), configuration)).status, 503);
  }
  const response = await call(worker, request("/api/v2/search/anime?keyword=show", { headers: { Authorization: "Bearer wrong" } }));
  assert.equal(response.status, 401);
  assert.equal(calls, 0);
  assert.equal((await response.text()).includes(TOKEN), false);
});

test("route allowlist rejects alternate targets, duplicate queries and unsupported methods", async () => {
  const worker = createWorker({ fetcher: () => assert.fail("must not fetch") });
  const invalid = [
    ["/api/v2/login", {}, 404], ["/api/v2/search/anime?keyword=show&url=https://evil.test", {}, 400],
    ["/api/v2/search/anime?keyword=a&keyword=b", {}, 400], ["/api/v2/search/anime?keyword=", {}, 400],
    ["/api/v2/comment/9223372036854775808", {}, 404], ["/api/v2/comment/1?withRelated=other", {}, 400],
    ["/api/v2/bangumi/%2F%2Fevil.test", {}, 404], ["/api/v2/search/anime?keyword=show", { method: "POST" }, 405],
  ];
  for (const [path, options, status] of invalid) assert.equal((await call(worker, request(path, options))).status, status);
});

test("only server credentials sign the fixed upstream; client headers are discarded", async () => {
  const worker = createWorker({ fetcher: async (url, options) => {
    assert.equal(url, "https://api.dandanplay.net/api/v2/search/anime?keyword=show");
    assert.equal(options.redirect, "manual");
    assert.equal(options.headers.Authorization, undefined);
    assert.equal(options.headers["X-AppSecret"], undefined);
    assert.equal(options.headers["X-AppId"], "test-app");
    assert.equal(options.headers["X-Signature"], createHash("sha256")
      .update("test-app" + options.headers["X-Timestamp"] + "/api/v2/search/anime" + "test-secret").digest("base64"));
    return json({ success: true, animes: [] }, { headers: { "Set-Cookie": "private" } });
  } });
  const response = await call(worker, request("/api/v2/search/anime?keyword=show", { headers: { "X-Emby-Token": "media-secret" } }));
  assert.equal(response.status, 200);
  assert.equal(response.headers.get("Set-Cookie"), null);
  assert.equal(response.headers.get("Cache-Control"), "no-store");
});

test("filename-only match normalizes the compatibility hash and preserves candidates", async () => {
  const worker = createWorker({ fetcher: async (url, options) => {
    assert.equal(url, "https://api.dandanplay.net/api/v2/match");
    assert.equal(options.method, "POST");
    assert.deepEqual(JSON.parse(options.body), { fileName: "三体 S01E01", matchMode: "fileNameOnly", fileHash: "0".repeat(32) });
    return json({ success: true, isMatched: false, matches: [{ animeId: 1, episodeId: 2 }] });
  } });
  const response = await call(worker, request("/api/v2/match", { method: "POST",
    headers: { "Content-Type": "application/json" }, body: JSON.stringify({ fileName: "三体 S01E01" }) }));
  assert.equal((await response.json()).matches.length, 1);
});

test("unsupported match statuses remain recognizable for client search fallback", async () => {
  for (const code of [404, 405, 501]) {
    const paths = [];
    const worker = createWorker({ fetcher: async url => {
      const path = new URL(url).pathname;
      paths.push(path);
      return path === "/api/v2/match" ? new Response("private-upstream-data", { status: code }) :
        json({ success: true, animes: [{ animeId: 1, animeTitle: "show" }] });
    } });
    const response = await call(worker, request("/api/v2/match", { method: "POST",
      headers: { "Content-Type": "application/json" }, body: JSON.stringify({ fileName: "show S01E01" }) }));
    assert.equal(response.status, code);
    assert.equal((await response.text()).includes("private-upstream-data"), false);
    const search = await call(worker, request("/api/v2/search/anime?keyword=show"));
    assert.equal((await search.json()).animes[0].animeTitle, "show");
    assert.deepEqual(paths, ["/api/v2/match", "/api/v2/search/anime"]);
  }
});

test("match rejects oversized, malformed or unsupported bodies", async () => {
  const worker = createWorker({ fetcher: () => assert.fail("must not fetch") });
  for (const [body, status] of [["x".repeat(4097), 413], ["not-json", 400], ["null", 400],
    ['{"fileName":"show","matchMode":"hashOnly"}', 400], ['{"fileName":"show","url":"https://evil.test"}', 400]]) {
    const response = await call(worker, request("/api/v2/match", { method: "POST", body,
      headers: { "Content-Type": "application/json" } }));
    assert.equal(response.status, status);
  }
});

test("official CDN redirects strip secrets and stream comment data", async () => {
  let calls = 0;
  const worker = createWorker({ fetcher: async (url, options) => {
    if (calls++ === 0) {
      assert.equal(url, "https://api.dandanplay.net/api/v2/comment/1?withRelated=true");
      return new Response(null, { status: 302, headers: { Location: "https://cas2.dandanplay.net/data/1" } });
    }
    assert.deepEqual(options.headers, { Accept: "application/json" });
    return json({ comments: [{ cid: 1, p: "1,1,16777215,user", m: "hello" }] });
  } });
  const response = await call(worker, request("/api/v2/comment/1"));
  assert.equal((await response.json()).comments.length, 1);
  assert.equal(calls, 2);
});

test("untrusted, downgraded, looping and non-comment redirects are rejected", async () => {
  for (const location of ["https://evil.test/data", "http://cas2.dandanplay.net/data", "https://user:secret@cas2.dandanplay.net/data"]) {
    let calls = 0;
    const worker = createWorker({ fetcher: async () => { calls++; return new Response(null, { status: 302, headers: { Location: location } }); } });
    assert.equal((await call(worker, request("/api/v2/comment/1"))).status, 502);
    assert.equal(calls, 1);
  }
  let calls = 0;
  const worker = createWorker({ fetcher: async () => { calls++; return new Response(null,
    { status: 302, headers: { Location: "https://cas2.dandanplay.net/loop" } }); } });
  assert.equal((await call(worker, request("/api/v2/comment/1"))).status, 502);
  assert.equal(calls, 4);
  assert.equal((await call(worker, request("/api/v2/search/anime?keyword=show"))).status, 502);
});

test("both client and cache-miss rate limits stop upstream calls", async () => {
  const worker = createWorker({ fetcher: () => assert.fail("must not fetch") });
  for (const binding of ["CLIENT_LIMITER", "UPSTREAM_LIMITER"]) {
    const configuration = env(); configuration[binding].limit = async () => ({ success: false });
    const response = await call(worker, request("/api/v2/search/anime?keyword=show"), configuration);
    assert.equal(response.status, 429);
    assert.equal(response.headers.get("Retry-After"), "60");
  }
});

test("metadata cache is authenticated, contains no token and skips business errors", async () => {
  const entries = new Map(); let calls = 0;
  const cache = { match: async key => entries.get(key.url)?.clone(),
    put: async (key, response) => { assert.equal(key.headers.get("Authorization"), null); entries.set(key.url, response); } };
  const worker = createWorker({ cache: () => cache, fetcher: async () => { calls++; return json({ success: true, animes: [] }); } });
  const path = "/api/v2/search/anime?keyword=show";
  await call(worker, request(path));
  assert.equal((await call(worker, request(path))).status, 200);
  assert.equal(calls, 1);
  assert.equal((await call(worker, request(path, { headers: { Authorization: "wrong" } }))).status, 401);
  assert.equal(calls, 1);
  entries.clear();
  const failing = createWorker({ cache: () => cache,
    fetcher: async () => json({ success: false, errorCode: 2, errorMessage: "private-upstream-data" }) });
  assert.deepEqual(await (await call(failing, request(path))).json(), { success: false, errorCode: 2 });
  assert.equal(entries.size, 0);
});

test("upstream HTTP failures, wrong content types and network errors expose no details", async () => {
  for (const code of [401, 403, 404, 405, 429, 500, 501]) {
    const worker = createWorker({ fetcher: async () => new Response("private-secret", { status: code }) });
    const response = await call(worker, request("/api/v2/search/anime?keyword=show"));
    assert.equal(response.status, [405, 500, 501].includes(code) ? 502 : code);
    assert.equal((await response.text()).includes("private-secret"), false);
  }
  const worker = createWorker({ fetcher: async () => { throw new Error("private-url-and-token"); } });
  const response = await call(worker, request("/api/v2/search/anime?keyword=show"));
  assert.equal(response.status, 502);
  assert.equal((await response.text()).includes("private-url"), false);
  const html = createWorker({ fetcher: async () => new Response("<html>not json</html>") });
  assert.equal((await call(html, request("/api/v2/comment/1"))).status, 502);
});

test("comment limits apply to content length and chunked response bodies", async () => {
  const oversized = createWorker({ fetcher: async () => json({ comments: [] },
    { headers: { "Content-Length": String(MAX_RESPONSE_BYTES + 1) } }) });
  assert.equal((await call(oversized, request("/api/v2/comment/1"))).status, 502);
  const chunked = createWorker({ fetcher: async () => new Response(new Uint8Array(MAX_RESPONSE_BYTES + 1),
    { headers: { "Content-Type": "application/json" } }) });
  const response = await call(chunked, request("/api/v2/comment/1"));
  await assert.rejects(response.arrayBuffer());
});

test("client cancellation aborts upstream requests and comment streams", async () => {
  const controller = new AbortController();
  controller.abort();
  const cancelled = createWorker({ fetcher: async (url, options) => {
    assert.equal(options.signal.aborted, true);
    throw new DOMException("cancelled", "AbortError");
  } });
  assert.equal((await call(cancelled, request("/api/v2/comment/1", { signal: controller.signal }))).status, 504);
  let signal;
  let streamCancelled = false;
  const streaming = createWorker({ fetcher: async (url, options) => {
    signal = options.signal;
    return new Response(new ReadableStream({ cancel() { streamCancelled = true; } }),
      { headers: { "Content-Type": "application/json" } });
  } });
  const response = await call(streaming, request("/api/v2/comment/1"));
  await response.body.cancel();
  assert.equal(signal.aborted, true);
  assert.equal(streamCancelled, true);
});
