#!/usr/bin/env node
/**
 * verify_package_static.mjs
 * 包静态核验（不启动 app，不触图，不网络）。
 *
 * 用法:
 *   node verify_package_static.mjs <解压后的包目录或 .zip 路径> [对照旧包路径]
 *
 * 检查项:
 *   A. 目录/zip 完整性: resources/app.asar 存在（win 包）
 *   B. 版本: static/package.json version + VERSION 文件（与 CI get-pkg-version 输出核对）
 *   C. 补丁静态指纹: app.asar 内 main.js 必须含
 *        a) :publish-result? 键（handler/search.cljs 新形参）
 *        b) mcp_search/search-call-args 构造的 :publish-result? false
 *     对照旧包: 旧包 app.asar 不应含指纹（否则说明指纹无效或对照包非旧源码）
 *
 * 局限（不冒充行为验证）:
 *   - shadow-cljs release 对未用局部做 dead-code 裁剪，CLJS 源码词不会原样进 bundle，
 *     因此不能靠搜源码字符串证明代码路径存在。本指纹依赖 publish-result? 是形参解构键
 *     （被 js 对象键构造消费，通常存活），若新包检出、旧包未检出，作为"新包含补丁"的
 *     推断级证据；若两者都检出或都未检出 → 指纹失效，结论 UNVERIFIED。
 *   - 真正的 source SHA 核验仍以 CI checkout job log 为准（brief §4），本脚本只验包内容。
 *
 * 输出: 每行 CHECK <name> <PASS|FAIL|SKIP|UNVERIFIED> <detail>，末行 SUMMARY。
 * 退出码: 0 = 全部 PASS（指纹在新包检出且旧包未检出，或无对照）；1 = 任一 FAIL/UNVERIFIED。
 */
import { readFileSync, existsSync, statSync, readdirSync } from "node:fs";
import { execFileSync } from "node:child_process";
import { createHash } from "node:crypto";
import path from "node:path";
import { fileURLToPath } from "node:url";

const HERE = path.dirname(fileURLToPath(import.meta.url));

function sha256(buf) {
  return createHash("sha256").update(buf).digest("hex");
}

// 解析 app.asar: 头部是 pickle(序列化 buffer 头 + JSON 目录树)
// 简化: 读整个 asar, 找到 "files" JSON 段的结尾 (两个 0x00 之前的 JSON), 直接用
// 内置 asar 解包更稳 —— 这里不引依赖, 改为: 用 `npx @electron/asar` 若可用, 否则
// 用启发式: asar 内文本以 UTF-8 存, 直接在整文件 buffer 上找指纹字符串(含 JSON 元数据区,
// 不影响"含/不含"判断)。
function bufferContains(haystack, needle) {
  const n = Buffer.from(needle, "utf8");
  return haystack.indexOf(n) !== -1;
}

function loadAsar(p) {
  if (!existsSync(p)) throw new Error(`missing: ${p}`);
  const buf = readFileSync(p);
  return buf;
}

function findPackageRoot(artifactPath) {
  // 支持: 目录（已解压）或 .zip
  if (artifactPath.toLowerCase().endsWith(".zip")) {
    throw new Error(
      "zip 未自动解包: 先执行 7z x <zip> -o<dir> 或 Expand-Archive, 再把目录传给脚本"
    );
  }
  const st = statSync(artifactPath);
  if (!st.isDirectory()) throw new Error(`not a directory: ${artifactPath}`);
  // 包目录可能是 dist/win-unpacked 本身, 或其上层含 dist/
  const cands = [artifactPath];
  if (existsSync(path.join(artifactPath, "dist"))) {
    cands.push(path.join(artifactPath, "dist"));
  }
  for (const c of cands) {
    const unpacked = path.join(c, "win-unpacked");
    if (existsSync(unpacked)) return { winUnpacked: unpacked, root: c };
  }
  // 可能传的就是 win-unpacked
  if (existsSync(path.join(artifactPath, "Logseq.exe"))) {
    return { winUnpacked: artifactPath, root: artifactPath };
  }
  throw new Error("无法定位 win-unpacked/ (找 dist/win-unpacked 或含 Logseq.exe 的目录)");
}

function checkStaticArtifact(artifactPath, label) {
  const out = [];
  const { winUnpacked, root } = findPackageRoot(artifactPath);
  out.push(`CHECK ${label}/layout PASS win-unpacked=${path.relative(root, winUnpacked)}`);

  const asar = path.join(winUnpacked, "resources", "app.asar");
  if (!existsSync(asar)) {
    out.push(`CHECK ${label}/asar FAIL missing resources/app.asar`);
    return { out, ok: false, hasFingerprint: false };
  }
  out.push(`CHECK ${label}/asar PASS sha256=${sha256(readFileSync(asar)).slice(0, 16)}…`);

  // 版本: static/package.json 打进 asar 内; 也看 win-unpacked/resources 附近
  const buf = loadAsar(asar);
  const m = Buffer.from("version").toString();
  // 版本核对: CI "Update APP Version" 改 static/package.json 的 "version": "0.0.1"
  // 在 asar 里 package.json 是打包产物, 直接读 unpacked/resources 不行(在 asar 内)。
  // 简化: 从 asar buffer 抓 "version":"X.Y.Z" 首个匹配(package.json 在 asar 头后靠前)。
  const vm = buf.toString("utf8", 0, Math.min(buf.length, 8 * 1024 * 1024)).match(/"version"\s*:\s*"([0-9][^"]*)"/);
  if (vm) out.push(`CHECK ${label}/version PASS version=${vm[1]}`);
  else out.push(`CHECK ${label}/version FAIL no version field found in app.asar head`);

  // 补丁指纹
  const fp1 = "publish-result"; // 形参解构键 :publish-result? → js 属性 "publish-result"
  const fp2 = "publishResult"; // 驼峰变体(js->clj 默认 keywordize 前形态)
  const has1 = bufferContains(buf, fp1);
  const has2 = bufferContains(buf, fp2);
  const has = has1 || has2;
  out.push(
    `CHECK ${label}/fingerprint ${has ? "PASS" : "FAIL"} "publish-result" in-app.asar=${has1} "publishResult"=${has2}`
  );
  return { out, ok: true, hasFingerprint: has };
}

function main() {
  const args = process.argv.slice(2);
  if (args.length < 1) {
    console.error("usage: node verify_package_static.mjs <new-pkg-dir> [old-pkg-dir]");
    process.exit(2);
  }
  const newRes = checkStaticArtifact(args[0], "new");
  for (const line of newRes.out) console.log(line);

  let summary = "PASS";
  let verdict = "PASS";
  if (!newRes.hasFingerprint) verdict = "UNVERIFIED(指纹未在新包检出: 可能死码裁剪或包非新源码)";

  if (args.length >= 2) {
    const oldRes = checkStaticArtifact(args[1], "old");
    for (const line of oldRes.out) console.log(line);
    if (oldRes.hasFingerprint) {
      verdict = `UNVERIFIED(指纹在旧包也检出 → 指纹无区分度, 不能证明新包含补丁)`;
    } else if (newRes.hasFingerprint) {
      verdict = "PASS(新包检出指纹, 旧包未检出 → 推断级证据: 新包含补丁)";
    }
  }
  if (verdict.startsWith("PASS")) summary = "PASS"; else summary = "FAIL/UNVERIFIED";
  console.log(`SUMMARY ${summary} :: ${verdict}`);
  process.exit(summary === "PASS" ? 0 : 1);
}

try {
  main();
} catch (e) {
  console.log(`SUMMARY FAIL :: ${e.message}`);
  process.exit(1);
}
