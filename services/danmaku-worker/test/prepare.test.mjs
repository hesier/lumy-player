import assert from "node:assert/strict";
import { copyFile, mkdir, mkdtemp, readFile, rm, writeFile } from "node:fs/promises";
import { execFileSync, spawnSync } from "node:child_process";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { test } from "node:test";

async function fixture(context) {
  const root = await mkdtemp(join(tmpdir(), "lumy-worker-prepare-"));
  context.after(() => rm(root, { recursive: true, force: true }));
  const environment = { ...process.env };
  // Never let a developer's real credentials or Git environment reach the fixture.
  for (const key of Object.keys(environment)) {
    if (/^(DANMAKU_|DANDANPLAY_|CLOUDFLARE_|GIT_)/u.test(key)) delete environment[key];
  }
  Object.assign(environment, { GIT_CONFIG_GLOBAL: "/dev/null", GIT_CONFIG_NOSYSTEM: "1",
    DANDANPLAY_APP_ID: "fixture-app", DANDANPLAY_APP_SECRET: "fixture-secret" });
  execFileSync("git", ["init", "--quiet", root], { env: environment, stdio: "pipe" });
  await writeFile(join(root, ".gitignore"), "/.local/\n");
  const script = join(root, "services/danmaku-worker/prepare.mjs");
  await mkdir(join(root, "services/danmaku-worker"), { recursive: true });
  await copyFile(new URL("../prepare.mjs", import.meta.url), script);
  return { root,
    run: (values = {}) => spawnSync(process.execPath, [script], { cwd: root,
      env: { ...environment, ...values }, encoding: "utf8" }),
    json: async name => JSON.parse(await readFile(join(root, ".local/danmaku-worker", name), "utf8")),
  };
}

test("unsafe properties tokens fail before writing any private configuration", async context => {
  const f = await fixture(context);
  for (const suffix of ["\\x", "\\n", "\\"]) {
    const token = "a".repeat(32) + suffix;
    const result = f.run({ DANMAKU_PROXY_URL: "https://relay.example.test", DANMAKU_PROXY_TOKEN: token });
    assert.equal(result.status, 1);
    assert.equal((result.stdout + result.stderr).includes(token), false);
    await assert.rejects(readFile(join(f.root, ".local/danmaku-worker/secrets.json")), { code: "ENOENT" });
    await assert.rejects(readFile(join(f.root, ".local/danmaku-proxy.properties")), { code: "ENOENT" });
  }
});

test("safe printable tokens round-trip and regeneration preserves deployment settings", async context => {
  const f = await fixture(context);
  const token = "a".repeat(32) + "!#:=+/\"[]";
  const first = f.run({ DANMAKU_PROXY_URL: "https://relay.example.test", DANMAKU_PROXY_TOKEN: token });
  assert.equal(first.status, 0);
  assert.equal((first.stdout + first.stderr).includes(token), false);
  const saved = await f.json("secrets.json");
  assert.equal(saved.PROXY_TOKEN, token);
  const app = await readFile(join(f.root, ".local/danmaku-proxy.properties"), "utf8");
  assert.equal(app, `DANMAKU_PROXY_URL=https://relay.example.test\nDANMAKU_PROXY_TOKEN=${token}\n`);
  const config = await f.json("wrangler.json");
  config.name = "fixture-worker";
  config.account_id = "fixture-account";
  config.ratelimits[0].namespace_id = "123456789";
  config.ratelimits[0].simple.limit = 42;
  await writeFile(join(f.root, ".local/danmaku-worker/wrangler.json"), JSON.stringify(config));
  assert.equal(f.run().status, 0);
  assert.deepEqual(await f.json("secrets.json"), saved);
  assert.deepEqual(await f.json("wrangler.json"), config);
  assert.equal(await readFile(join(f.root, ".local/danmaku-proxy.properties"), "utf8"), app);
});

test("bootstrap without an origin generates a token without enabling App or public routes", async context => {
  const f = await fixture(context);
  assert.equal(f.run().status, 0);
  const saved = await f.json("secrets.json");
  assert.match(saved.PROXY_TOKEN, /^[a-f0-9]{64}$/u);
  const config = await f.json("wrangler.json");
  assert.equal(config.workers_dev, false);
  assert.equal(config.routes, undefined);
  await assert.rejects(readFile(join(f.root, ".local/danmaku-proxy.properties")), { code: "ENOENT" });
});
