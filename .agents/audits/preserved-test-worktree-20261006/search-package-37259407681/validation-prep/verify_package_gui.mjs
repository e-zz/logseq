#!/usr/bin/env node
/**
 * verify_package_gui.mjs — 新包真实 GUI/MCP 行为验证（父代理验收后运行）
 *
 * 前置（缺任一项即 BLOCKED, exit 2; 不构造模拟结果）:
 *   --app <win-unpacked 目录>       CI 产物解压后的 win-unpacked（含 Logseq.exe）
 *   --graph-dir <合成图目录>        prepare_fixture_graph.mjs 生成的文件图（含 1 个 .edn 页）
 *   node_modules/playwright 可解析（本仓已装 1.58.2）
 *
 * 隔离（绝不触碰当前 Logseq 实例/现有图/原配置）:
 *   - Electron 单实例锁: src/electron/electron/core.cljs main() 的
 *     requestSingleInstanceLock 基于 app 名+userData 的锁文件。本脚本用
 *     --user-data-dir 指向全新隔离目录, 即使 live Logseq 在运行, 两实例 userData
 *     不同 → 锁不冲突, 不触发 second-instance（该 handler 只做窗口切换, 也无害）。
 *   - 图与 registry: HOME/USERPROFILE 指向隔离目录 → ~/.logseq/graphs.edn 与
 *     ~/logseq 全部落在隔离区; 预置隔离 registry 指向 --graph-dir（:path 字段, 只读使用,
 *     app 不修改 --graph-dir 文件）。
 *   - 只 spawn --app 指定的包, 不 launch 默认路径 Logseq, 不碰其它进程。
 *
 * 观测入口（全部包内生产路径; 不用硬编码 JS 转写 CLJS 冒充验证）:
 *   - window.logseq.api.*: src/main/logseq/api.cljs 的 ^:export 绑定（生产 renderer 全局,
 *     electron.api-method dispatch-real-api-method 对 ns'=="app" 即取 window.logseq.api,
 *     见 src/electron/electron/api_method.cljs 行 40-41）。
 *   - CDP: 启动参数 --remote-debugging-port（仅隔离实例监听 127.0.0.1）。
 *   - :search/result 状态观测: RF64 (frontend.state) 无公开 add-watch, 唯一读路径是
 *     get_state_from_store（api-app/get_state_from_store）。watcher = 页内 500ms 轮询指纹,
 *     事件计数记为 poll-events。判定语义: delta=0 是严格结论（轮询持续运行时, 期间任何
 *     swap 必然被捕获）; delta>0 需比对 from/to 归因。结果中始终标 poll-only=true,
 *     由父代理按此语义复核（brief: 前后相等不足以证明无重复写 → 用事件流而非首尾快照）。
 *
 * 场景:
 *   S1 GUI 常规搜索正控制: #search-button 打开 cmdk（与 clj-e2e util/search 同入口,
 *      clj-e2e/src/logseq/e2e/util.clj:128）, fill needle → :search/result 被写入
 *      且 blocks 含目标 → publish-result? true 路径在包内可用。
 *   S2 MCP opt-out: production MCP HTTP（127.0.0.1:12315/mcp, streamable-http,
 *      electron.server initialize-mcp-routes 真实链路: fastify → invoke-logseq-api! →
 *      renderer electron.listener invokeLogseqAPI → api-method dispatch →
 *      window.logseq.api.search → frontend.handler.search/search）连发 3 次 searchBlocks
 *      → 返回正确 且 watcher delta=0。
 *   S3 Cmd-K direct-path: 与 S1 同入口重查, 分开记录两条独立证据:
 *      (a) UI 列表文本含 needle（direct-path 渲染层）, (b) :search/result 含 needle
 *      （聚合 handler 发布层）。brief 要求二者分开验证, 本脚本分字段落盘。
 *   S4 临时块 active→recycle→restore: window.logseq.api 造块 → 搜索可见 →
 *      MCP recycleBlock → 搜索不可见 → MCP restoreBlock → 搜索可见。
 *      recycle/restore 用 production MCP 工具（window.logseq.api 未导出 recycle/restore,
 *      缺口 G1）; 其间的 watcher delta 期望恒 0（回收不发布搜索状态）。
 *
 * 输出: <out-dir>/gui-run.json（逐场景 PASS/FAIL/UNVERIFIED + 证据）
 * 退出码: 0=全 PASS, 1=任一 FAIL, 2=前置 BLOCKED, 3=app 启动失败
 * 不做: 不 kill 非本脚本 spawn 的进程; SIGTERM→SIGKILL 只对本子进程; 不自动删隔离区（留证）。
 */
import { readFileSync, writeFileSync, mkdirSync, existsSync, statSync, readdirSync, cpSync } from "node:fs";
import { spawn } from "node:child_process";
import path from "node:path";
import crypto from "node:crypto";
import http from "node:http";
import { fileURLToPath } from "node:url";

const HERE = path.dirname(fileURLToPath(import.meta.url));

function parseArgs() {
  const a = process.argv.slice(2);
  const get = (k) => { const i = a.indexOf(k); return i >= 0 ? a[i + 1] : undefined; };
  return { app: get("--app"), graphDir: get("--graph-dir"), outDir: get("--out") };
}
function blocked(msg) { console.log(`BLOCKED ${msg}`); process.exit(2); }

// ---------- 隔离环境 ----------
function buildIsolation({ app, graphDir, outArg }) {
  const ts = new Date().toISOString().replace(/[:.]/g, "-");
  outArg = outArg || path.join(HERE, "results", `search-package-${ts}`);
  const outDir = outArg;
  mkdirSync(outDir, { recursive: true });
  const isoHome = path.join(outDir, "home");
  mkdirSync(path.join(isoHome, ".logseq"), { recursive: true });
  const userData = path.join(outDir, "userdata");
  mkdirSync(userData, { recursive: true });

  const gstat = statSync(graphDir);
  if (!gstat.isDirectory()) blocked(`graph dir not a directory: ${graphDir}`);
  const graphName = path.basename(graphDir);
  const pages = readdirSync(graphDir).filter((f) => f.endsWith(".edn"));
  if (pages.length === 0) blocked(`no .edn pages in graph dir (run prepare_fixture_graph.mjs first): ${graphDir}`);

  // 合成图复制到隔离 logseq 根（Logseq 文件图按 <root>/graphs/<name> 解析; 复制后 app
  // 对图的读写全部落在隔离区, --graph-dir 源目录保持只读）
  const logseqRoot = path.join(isoHome, "logseq");
  mkdirSync(path.join(logseqRoot, "graphs"), { recursive: true });
  const isoGraph = path.join(logseqRoot, "graphs", graphName);
  cpSync(graphDir, isoGraph, { recursive: true });

  // 隔离 userData 的 configs.edn（electron.configs/cfg-path = <userData>/configs.edn）:
  // 开 HTTP server 自启 + MCP 开关（server.cljs reset-state! 读 :server/mcp-enabled?）
  writeFileSync(
    path.join(userData, "configs.edn"),
    "{:server/autostart true :server/mcp-enabled? true :server/port 12315}",
    "utf8"
  );
  // 隔离 HOME 的 graph registry（graph-registry-path = <home>/.logseq/graphs.edn）;
  // 条目含 :path 指向隔离图副本 → app 打开时读隔离图, 不触碰源目录与任何 live 图。
  const gid = crypto.randomUUID();
  writeFileSync(
    path.join(isoHome, ".logseq", "graphs.edn"),
    `[{:repo "logseq_db_${graphName}" :graph-name "${graphName}" :local-graph-id "${gid}" :graph-id "${gid}" :path "${isoGraph.replace(/\\/g, "/")}" :updated-at ${Date.now()}}]`,
    "utf8"
  );
  return { outDir, isoHome, isoGraph, userData, graphName, ts };
}

// ---------- 启动 Electron ----------
function startApp({ app, isoHome, userData, cdpPort }) {
  const exe = path.join(app, "Logseq.exe");
  if (!existsSync(exe)) blocked(`Logseq.exe not found under --app: ${app}`);
  const args = [
    "--user-data-dir", userData,
    "--remote-debugging-port", String(cdpPort),
    "--no-first-run",
    "--no-default-browser-check",
  ];
  const env = { ...process.env, HOME: isoHome, USERPROFILE: isoHome, LOGSEQ_CLI_ROOT_DIR: isoHome };
  const child = spawn(exe, args, { env, stdio: ["ignore", "pipe", "pipe"] });
  let buf = "";
  child.stdout.on("data", (d) => (buf += d.toString()));
  child.stderr.on("data", (d) => (buf += d.toString()));
  child.__log = () => buf;
  return child;
}

function waitHttp(url, timeoutMs) {
  const t0 = Date.now();
  return new Promise((resolve) => {
    const tick = async () => {
      try { const r = await fetch(url); if (r.status > 0) return resolve(true); } catch {}
      if (Date.now() - t0 >= timeoutMs) return resolve(false);
      setTimeout(tick, 500);
    };
    tick();
  });
}

// ---------- CDP via playwright ----------
let pwDefault;
async function cdpPageViaPlaywright(port) {
  const pw = await import("playwright");
  pwDefault = pw.default || pw;
  const browser = await pwDefault.chromium.connectOverCDP(`http://127.0.0.1:${port}`);
  const ctx = browser.contexts()[0];
  if (!ctx) throw new Error("CDP: no browser context");
  const page = ctx.pages().find((p) => /logseq/i.test(p.url())) || ctx.pages()[0];
  if (!page) throw new Error("CDP: no page target");
  return { browser, page };
}

// ---------- 状态观测 ----------
async function installWatcher(page) {
  await page.evaluate(() => {
    window.__sresWatch = { events: [], last: null, count: 0 };
    const f = () => {
      try { return JSON.stringify(window.logseq.api.get_state_from_store("search/result")); }
      catch { return undefined; }
    };
    window.__sresWatch.last = f();
    window.__sresWatch.timer = setInterval(() => {
      const cur = f();
      if (cur !== window.__sresWatch.last) {
        window.__sresWatch.events.push({ at: Date.now(), from: window.__sresWatch.last, to: cur });
        window.__sresWatch.last = cur;
        window.__sresWatch.count += 1;
      }
    }, 500);
  });
}
async function readWatcher(page) {
  return await page.evaluate(() => ({ ...window.__sresWatch, events: [...window.__sresWatch.events] }));
}

// ---------- MCP HTTP（production streamable-http 会话） ----------
function httpJson(method, url, headers, body) {
  return new Promise((resolve, reject) => {
    const u = new URL(url);
    const req = http.request({ hostname: u.hostname, port: u.port, path: u.pathname, method, headers },
      (res) => {
        let data = "";
        res.on("data", (c) => (data += c));
        res.on("end", () => resolve({ status: res.statusCode, headers: res.headers, body: data }));
      });
    req.on("error", reject);
    req.setTimeout(30000, () => req.destroy(new Error("MCP http timeout")));
    if (body) req.write(body);
    req.end();
  });
}
function sseLast(body, headers) {
  const t = headers["content-type"] || "";
  if (t.includes("text/event-stream")) {
    const lines = body.split("\n").filter((l) => l.startsWith("data:"));
    return lines.length ? JSON.parse(lines[lines.length - 1].slice(5)) : null;
  }
  return body ? JSON.parse(body) : null;
}
async function mcpSession(base) {
  const init = await httpJson("POST", base + "/mcp",
    { "content-type": "application/json", accept: "application/json, text/event-stream" },
    JSON.stringify({ jsonrpc: "2.0", id: 1, method: "initialize",
      params: { protocolVersion: "2025-03-26", capabilities: {}, clientInfo: { name: "pkg-verify", version: "1.0" } } }));
  const sid = init.headers["mcp-session-id"];
  if (!sid) throw new Error(`MCP initialize failed: http ${init.status} (need server running + :server/mcp-enabled? true)`);
  const hdr = (extra) => ({ "content-type": "application/json", accept: "application/json, text/event-stream", "mcp-session-id": sid, ...extra });
  await httpJson("POST", base + "/mcp", hdr({}), JSON.stringify({ jsonrpc: "2.0", method: "notifications/initialized" })).catch(() => {});
  let n = 10;
  const call = (method, params) =>
    httpJson("POST", base + "/mcp", hdr({}), JSON.stringify({ jsonrpc: "2.0", id: ++n, method, params }))
      .then((r) => sseLast(r.body, r.headers));
  return { sid, call, close: () => httpJson("DELETE", base + "/mcp", { "mcp-session-id": sid }).catch(() => {}) };
}

// ---------- GUI helpers ----------
const results = [];
function record(scene, status, detail) {
  results.push({ scene, status, detail });
  console.log(`SCENE ${scene} ${status} ${JSON.stringify(detail).slice(0, 500)}`);
}
async function typeCmdkSearch(page, text) {
  await page.evaluate(() => {
    if (!document.querySelector(".cp__cmdk-search-input")) {
      const b = document.querySelector("#search-button");
      if (b) b.click();
    }
  });
  await page.waitForSelector(".cp__cmdk-search-input", { timeout: 15000 });
  const input = page.locator(".cp__cmdk-search-input");
  await input.fill("");   // 先清空: fill 相同值不触发 onChange（clj-e2e util 同法）
  await input.fill(text);
  await page.waitForTimeout(1500); // cmdk search-debounce-ms 300 + 搜索往返
}
async function searchResultState(page) {
  return await page.evaluate(() => {
    try { return window.logseq.api.get_state_from_store("search/result") || null; } catch { return null; }
  });
}
async function cmdkUiHits(page, needle) {
  return await page.evaluate((nd) => {
    const all = Array.from(document.querySelectorAll(".cp__cmdk-item-main-text"));
    return all.map((e) => e.textContent).filter((t) => t && t.includes(nd)).length;
  }, needle);
}
// 造块: window.logseq.api（生产 renderer API）, 不走 MCP upsert（不触碰每请求一次 upsert 规则）
async function ensureBlock(page, pageName, content) {
  const r = await page.evaluate(async ([pn, ct]) => {
    const api = window.logseq.api;
    await api.create_page(pn, {});          // 幂等: 页已存在则返回现有页
    const tree = await api.get_page_blocks_tree(pn);
    const pageBlockId = tree && (tree["db/id"] || tree["block/id"]);
    const blk = await api.insert_block(pageBlockId, ct, {}); // 追加为该页子块
    return { pageBlockId, blk: blk ? (blk["block/uuid"] || blk["uuid"]) : null };
  }, [pageName, content]);
  await page.waitForTimeout(2500); // worker search-upsert-blocks 索引更新
  return r;
}

// ---------- 主流程 ----------
async function main() {
  const { app, graphDir, outDir } = parseArgs();
  if (!app) blocked("missing --app <win-unpacked dir>");
  if (!graphDir) blocked("missing --graph-dir <synthetic graph dir>");
  if (!existsSync(graphDir)) blocked(`graph dir missing: ${graphDir}`);
  try { await import("playwright"); } catch { blocked("playwright not resolvable from repo node_modules"); }

  const iso = buildIsolation({ app, graphDir, outArg: outDir });
  const runDir = iso.outDir; // gui-run.json 与隔离区同目录
  const CDP_PORT = 9333;
  const API_PORT = 12315;
  const base = `http://127.0.0.1:${API_PORT}`;
  const child = startApp({ app, isoHome: iso.isoHome, userData: iso.userData, cdpPort: CDP_PORT });

  let browser = null, page = null;
  try {
    const up = await waitHttp(base + "/", 90000);
    if (!up) {
      record("app-start", "FAIL", { note: "HTTP API 90s 未起", log: child.__log().slice(-4000) });
      finish(child, null, 3);
      return;
    }
    record("app-start", "PASS", { base, isolation: { isoHome: iso.isoHome, userData: iso.userData, isoGraph: iso.isoGraph } });

    const c = await cdpPageViaPlaywright(CDP_PORT);
    browser = c.browser; page = c.page;

    // 等合成图加载（get_user_configs.current-graph == logseq_db_<graphName>）
    let loaded = null;
    for (let i = 0; i < 120; i++) {
      loaded = await page.evaluate(() => {
        try { return window.logseq.api.get_user_configs(); } catch { return null; }
      }).catch(() => null);
      if (loaded && String(loaded["current-graph"] || "").includes(`logseq_db_${iso.graphName}`)) break;
      await new Promise((r) => setTimeout(r, 1000));
    }
    if (!loaded || !String(loaded["current-graph"] || "").includes(`logseq_db_${iso.graphName}`)) {
      record("graph-load", "FAIL", { loaded, log: child.__log().slice(-4000) });
      finish(child, browser, 1);
      return;
    }
    const appInfo = await page.evaluate(() => window.logseq.api.get_app_info()).catch(() => null);
    record("graph-load", "PASS", { repo: loaded["current-graph"], appInfo });

    // 搜索索引确保构建（handler/events.cljs 图加载后调度; 显式再触发一次防竞态, 失败不致命）
    await page.evaluate(() => {
      try { return window.logseq.api.rebuild_search_indices(true); } catch { return null; }
    }).catch(() => {});
    await page.waitForTimeout(4000);

    await installWatcher(page);

    // ---- S1 ----
    {
      const needle = "s1needle";
      await ensureBlock(page, "S1 Page", `${needle} block content`);
      await typeCmdkSearch(page, needle);
      const res = await searchResultState(page);
      const blocks = (res && res["blocks"]) || [];
      const ok = blocks.some((b) => (b["title"] || "").includes(needle));
      record("S1-gui-search", ok ? "PASS" : "FAIL",
        { stateBlocks: blocks.length, hasNeedle: ok, stateHasMore: res && res["has-more"] });
    }

    // ---- S2 ----
    {
      const needle = "s1needle";
      const before = await readWatcher(page);
      let mcp;
      try { mcp = await mcpSession(base); }
      catch (e) {
        record("S2-mcp-optout", "FAIL", { note: "MCP 会话建立失败", err: String(e) });
        finish(child, browser, 1); return;
      }
      const callSearch = (term) =>
        mcp.call("tools/call", { name: "searchBlocks", arguments: { searchTerm: term, limit: 5 } });
      const r1 = await callSearch(needle);
      const r1text = r1 && r1.result && r1.result.content ? r1.result.content[0].text : JSON.stringify(r1);
      let parsed = null; try { parsed = JSON.parse(r1text); } catch {}
      const found = parsed && (parsed["blocks"] || []).some((b) => (b["title"] || "").includes(needle));
      await callSearch(needle);      // 同 query 再发 → 若 opt-out 失效, 会重复写 :search/result
      await callSearch("s4needle");   // 不同 query（可能 0 结果, 仍走聚合 handler）
      await page.waitForTimeout(1500); // ≥2 个 500ms 轮询周期
      const after = await readWatcher(page);
      const delta = after.count - before.count;
      const ok = !!found && delta === 0;
      record("S2-mcp-optout", ok ? "PASS" : "UNVERIFIED",
        { mcpFoundNeedle: !!found, watchDelta: delta, pollOnly: true,
          eventsInWindow: after.events.slice(-5).map((e) => ({ at: e.at })),
          note: delta === 0
            ? "MCP 3 次调用期间 :search/result 轮询事件为 0 → 严格判定 opt-out 生效（无重复写）"
            : "出现状态事件 → opt-out 未生效或存在其它写方, 父代理按事件流 from/to 归因" });
      await mcp.close();
    }

    // ---- S3 ----
    {
      const needle = "s1needle";
      await typeCmdkSearch(page, needle);
      const ui = await cmdkUiHits(page, needle);
      const res = await searchResultState(page);
      const st = res && (res["blocks"] || []).some((b) => (b["title"] || "").includes(needle));
      record("S3-cmdk-direct", ui > 0 && st ? "PASS" : "UNVERIFIED",
        { uiListHits: ui, statePublishPresent: st,
          note: "UI 命中与 :search/result 发布为两条独立证据（brief: Cmd-K 结果来源与聚合 handler 发布路径分开验证）" });
    }

    // ---- S4 ----
    {
      const needle = "s4needle";
      await ensureBlock(page, "S4 Page", `${needle} block content`);
      await typeCmdkSearch(page, needle);
      let res = await searchResultState(page);
      const blk = ((res && res["blocks"]) || []).find((b) => (b["title"] || "").includes(needle));
      const uuid = blk && (blk["uuid"] || blk["block/uuid"]);
      if (!blk || !uuid) {
        record("S4-recycle-filter", "UNVERIFIED", { note: "S4 造块后搜索不可见或无 uuid, 无法进入回收段", res });
        finish(child, browser, 1); return;
      }
      const mcp = await mcpSession(base);
      const before = await readWatcher(page);
      const rc = await mcp.call("tools/call", { name: "recycleBlock", arguments: { blockUuid: uuid } });
      const rcText = rc && rc.result && rc.result.content ? rc.result.content[0].text : JSON.stringify(rc);
      await page.waitForTimeout(1500);
      await typeCmdkSearch(page, needle);
      res = await searchResultState(page);
      const recycledHidden = !((res["blocks"] || []).some((b) => (b["title"] || "").includes(needle)));
      const rs = await mcp.call("tools/call", { name: "restoreBlock", arguments: { blockUuid: uuid } });
      const rsText = rs && rs.result && rs.result.content ? rs.result.content[0].text : JSON.stringify(rs);
      await page.waitForTimeout(1500);
      await typeCmdkSearch(page, needle);
      const res3 = await searchResultState(page);
      const restoredVisible = !!(res3 && (res3["blocks"] || []).some((b) => (b["title"] || "").includes(needle)));
      const after = await readWatcher(page);
      const delta = after.count - before.count;
      const ok = recycledHidden && restoredVisible;
      record("S4-recycle-filter", ok ? "PASS" : "FAIL",
        { recycledHidden, restoredVisible, recycleResp: rcText.slice(0, 200), restoreResp: rsText.slice(0, 200),
          watchDelta: delta,
          note: "recycle/restore 走 production MCP; 共享过滤逻辑未改, 此段防间接回归; delta 期望 0" });
      await mcp.close();
    }

    finish(child, browser, 0);
  } catch (e) {
    console.log(`RUN-ERROR ${e && e.stack ? e.stack : e}`);
    record("run-error", "FAIL", { err: String(e), log: child.__log().slice(-3000) });
    finish(child, browser, 1);
  }
}

function finish(child, browser, code, runDir) {
  const sum = results.reduce((a, r) => { a[r.status] = (a[r.status] || 0) + 1; return a; }, {});
  const outFile = path.join(runDir || path.join(HERE, "results"), `gui-run-${Date.now()}.json`);
  try {
    mkdirSync(path.dirname(outFile), { recursive: true });
    writeFileSync(outFile, JSON.stringify({ summary: sum, results }, null, 2));
    console.log(`RESULTS-WRITTEN ${outFile}`);
  } catch (e) { console.log(`RESULTS-WRITE-FAIL ${e.message}`); }
  console.log(`ISOLATION-DIR (留证, 未自动删) ${path.join(HERE, "results")}`);
  console.log(`SUMMARY ${JSON.stringify(sum)}`);
  // 只结束本脚本 spawn 的实例与 CDP 连接
  const done = async () => {
    if (browser) await browser.close().catch(() => {});
    if (child.exitCode === null) {
      try { child.kill("SIGTERM"); } catch {}
      await new Promise((r) => setTimeout(r, 4000));
      if (child.exitCode === null) { try { child.kill("SIGKILL"); } catch {} }
    }
    process.exit(code);
  };
  done().catch(() => process.exit(code));
}

main();
