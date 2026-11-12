// 打包发布产物到 release/：
//   Windows：安装包（NSIS，当前用户安装）+ 便携版 zip（单个 exe，需系统自带 WebView2）
//   macOS：通用 dmg（Intel + Apple 芯片），ad-hoc 签名，未公证
// 用法：npm run package [-- v1.2.3]   版本号缺省取 tauri.conf.json；传入时只覆盖本次构建，不改仓库文件。
import { execFileSync } from "node:child_process";
import fs from "node:fs";
import os from "node:os";
import path from "node:path";

const root = path.resolve(import.meta.dirname, "..");
const conf = JSON.parse(fs.readFileSync(path.join(root, "src-tauri/tauri.conf.json"), "utf8"));
const version = (process.argv[2] ?? conf.version).replace(/^v/, "");
if (!/^\d+\.\d+\.\d+(-[0-9A-Za-z.-]+)?$/.test(version)) throw new Error(`版本号无效：${version}（应为 1.2.3 或 v1.2.3-beta.1）`);
if (!["win32", "darwin"].includes(process.platform)) throw new Error(`只支持在 Windows 或 macOS 上打包，当前：${process.platform}`);

const out = path.join(root, "release");
fs.rmSync(out, { recursive: true, force: true });
fs.mkdirSync(out);
const tmp = fs.mkdtempSync(path.join(os.tmpdir(), "cashflow-package-"));
const override = path.join(tmp, "version.json");
fs.writeFileSync(override, JSON.stringify({ version }));

const tauri = (...args) =>
  execFileSync(process.execPath, [path.join(root, "node_modules/@tauri-apps/cli/tauri.js"), "build", "--config", override, ...args], {
    cwd: root,
    stdio: "inherit",
  });
const base = `${conf.productName}_${version}`;

/** 把本版本的打包产物复制到 release/，重命名为统一的文件名。 */
function collect(dir, suffix, name) {
  const found = fs.existsSync(dir) ? fs.readdirSync(dir).filter((f) => f.includes(`_${version}_`) && f.endsWith(suffix)) : [];
  if (found.length !== 1) throw new Error(`在 ${dir} 中应恰好找到一个 *_${version}_*${suffix}，实际：${found.join(", ") || "无"}`);
  fs.copyFileSync(path.join(dir, found[0]), path.join(out, name));
}

try {
  if (process.platform === "win32") {
    tauri("--bundles", "nsis");
    const rel = path.join(root, "src-tauri/target/release");
    collect(path.join(rel, "bundle/nsis"), "-setup.exe", `${base}_x64-setup.exe`);
    const stage = path.join(tmp, "portable");
    fs.mkdirSync(stage);
    fs.copyFileSync(path.join(rel, "cashflow.exe"), path.join(stage, `${conf.productName}.exe`));
    const zip = path.join(out, `${base}_x64_portable.zip`);
    execFileSync("powershell", ["-NoProfile", "-NonInteractive", "-Command", `Compress-Archive -Path '${stage}\\*' -DestinationPath '${zip}'`], {
      stdio: "inherit",
    });
  } else {
    const target = "universal-apple-darwin";
    tauri("--bundles", "dmg", "--target", target);
    collect(path.join(root, "src-tauri/target", target, "release/bundle/dmg"), ".dmg", `${base}_universal.dmg`);
  }
} finally {
  fs.rmSync(tmp, { recursive: true, force: true });
}

for (const f of fs.readdirSync(out)) console.log(`release/${f}  ${(fs.statSync(path.join(out, f)).size / 1048576).toFixed(1)} MB`);
