import { randomBytes } from "node:crypto";
import { mkdir, readFile, writeFile, chmod } from "node:fs/promises";
import { fileURLToPath } from "node:url";
import { resolve } from "node:path";
import { execFileSync } from "node:child_process";

const root = fileURLToPath(new URL("../../", import.meta.url));
const directory = resolve(root, ".local/danmaku-worker");

async function optional(path) {
  try { return await readFile(path, "utf8"); }
  catch (error) { if (error.code === "ENOENT") return ""; throw error; }
}

function properties(value) {
  return Object.fromEntries(value.split(/\r?\n/u).filter(line => line.trim() && !line.trim().startsWith("#"))
    .map(line => { const split = line.indexOf("="); return [line.slice(0, split).trim(), line.slice(split + 1).trim()]; }));
}

async function privateFile(path, contents) {
  execFileSync("git", ["check-ignore", "--quiet", path], { cwd: root, stdio: "ignore" });
  await writeFile(path, contents, { mode: 0o600 });
  await chmod(path, 0o600);
}

try {
  const credentials = properties(await optional(resolve(root, ".local/dandanplay.properties")));
  const appPath = resolve(root, ".local/danmaku-proxy.properties");
  const app = properties(await optional(appPath));
  const saved = JSON.parse(await optional(resolve(directory, "secrets.json")) || "{}");
  const previous = JSON.parse(await optional(resolve(directory, "wrangler.json")) || "{}");
  const secrets = {
    DANDANPLAY_APP_ID: process.env.DANDANPLAY_APP_ID || credentials.DANDANPLAY_APP_ID || saved.DANDANPLAY_APP_ID,
    DANDANPLAY_APP_SECRET: process.env.DANDANPLAY_APP_SECRET || credentials.DANDANPLAY_APP_SECRET || saved.DANDANPLAY_APP_SECRET,
    PROXY_TOKEN: process.env.DANMAKU_PROXY_TOKEN || app.DANMAKU_PROXY_TOKEN || saved.PROXY_TOKEN || randomBytes(32).toString("hex"),
  };
  // Avoid Java Properties escape processing changing the App's copy of the token.
  if (Object.values(secrets).some(value => typeof value !== "string" || !value.trim()) ||
      !/^[\x21-\x7e]{32,256}$/u.test(secrets.PROXY_TOKEN) ||
      secrets.PROXY_TOKEN.includes("\\")) throw new Error("invalid_configuration");
  const base = process.env.DANMAKU_PROXY_URL || app.DANMAKU_PROXY_URL;
  const url = base ? new URL(base) : undefined;
  if (url && (url.protocol !== "https:" || url.username || url.password || url.search || url.hash ||
      url.pathname !== "/" || url.port)) throw new Error("invalid_proxy_origin");
  await mkdir(directory, { recursive: true, mode: 0o700 });
  const config = {
    name: process.env.DANMAKU_WORKER_NAME || previous.name || "lumy-danmaku-proxy",
    main: fileURLToPath(new URL("src/index.mjs", import.meta.url)),
    ...((process.env.CLOUDFLARE_ACCOUNT_ID || previous.account_id) ?
      { account_id: process.env.CLOUDFLARE_ACCOUNT_ID || previous.account_id } : {}),
    compatibility_date: "2025-05-05", workers_dev: false, preview_urls: false,
    observability: { enabled: false },
    ratelimits: [
      { name: "CLIENT_LIMITER", namespace_id: "1001", simple: { limit: 60, period: 60 } },
      { name: "UPSTREAM_LIMITER", namespace_id: "1002", simple: { limit: 120, period: 60 } },
    ].map(binding => previous.ratelimits?.find(item => item.name === binding.name) || binding),
    ...(url ? { routes: [{ pattern: url.hostname, custom_domain: true }] } : {}),
  };
  await privateFile(resolve(directory, "secrets.json"), JSON.stringify(secrets, null, 2) + "\n");
  await privateFile(resolve(directory, "wrangler.json"), JSON.stringify(config, null, 2) + "\n");
  if (url) await privateFile(appPath, `DANMAKU_PROXY_URL=${url.origin}\nDANMAKU_PROXY_TOKEN=${secrets.PROXY_TOKEN}\n`);
  console.log("Private Worker configuration prepared under .local/danmaku-worker; no secret values displayed.");
  if (!url) console.log("Proxy origin is not configured; no public route or App configuration was generated.");
} catch {
  console.error("Unable to prepare private configuration. Check credentials, token format, HTTPS origin and Git ignore rules; values are not displayed.");
  process.exitCode = 1;
}
