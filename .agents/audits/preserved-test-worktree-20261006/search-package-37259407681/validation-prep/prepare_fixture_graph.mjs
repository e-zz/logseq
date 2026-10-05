#!/usr/bin/env node
/**
 * prepare_fixture_graph.mjs — 生成合成文件图（fixture, 只写 --out 指定目录）
 *
 * 用法: node prepare_fixture_graph.mjs --out <dir>
 *
 * 生成 1 个页面文件 <dir>/Fixture-Home.edn（Logseq 文件图格式）:
 *   - 3 个顶层块, 各带唯一 needle 词（s1needle/s4needle/fixturecontrolword）
 *   - 1 个嵌套子块（fixturechildneedle）
 * 全部为合成数据, 无任何真实 UUID/路径。
 *
 * 该目录随后传给 verify_package_gui.mjs --graph-dir（脚本会复制到隔离区再使用,
 * 源目录保持只读）。
 */
import { writeFileSync, mkdirSync } from "node:fs";
import path from "node:path";

function parseArgs() {
  const a = process.argv.slice(2);
  const i = a.indexOf("--out");
  return { out: i >= 0 ? a[i + 1] : undefined };
}
const { out } = parseArgs();
if (!out) {
  console.log("usage: node prepare_fixture_graph.mjs --out <dir>");
  process.exit(2);
}
mkdirSync(out, { recursive: true });
const page = [
  "# Fixture Home",
  "",
  "- s1needle top-level block (GUI search target)",
  "- s4needle top-level block (recycle/restore target)",
  "  - fixturechildnested block (child, excluded from exact-block scoping)",
  "- fixturecontrolword control block (no needle, negative control)",
  "",
].join("\n");
const f = path.join(out, "Fixture-Home.edn");
writeFileSync(f, page, "utf8");
console.log(`FIXTURE-WRITTEN ${f}`);
console.log(`pages=1 blocks=4 needles=[s1needle s4needle fixturechildnested fixturecontrolword]`);
