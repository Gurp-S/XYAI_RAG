import { cpSync, existsSync, mkdirSync, rmSync } from "node:fs";
import { dirname, resolve } from "node:path";
import { fileURLToPath } from "node:url";

const scriptDir = dirname(fileURLToPath(import.meta.url));
const frontendRoot = resolve(scriptDir, "..");
const distDir = resolve(frontendRoot, "dist");
const targetDir = resolve(
  frontendRoot,
  "..",
  "src",
  "main",
  "resources",
  "static",
);

if (!existsSync(distDir)) {
  console.error(`[sync-dist] Build output not found: ${distDir}`);
  process.exit(1);
}

mkdirSync(targetDir, { recursive: true });
rmSync(targetDir, { recursive: true, force: true });
mkdirSync(targetDir, { recursive: true });
cpSync(distDir, targetDir, { recursive: true, force: true });

console.log(`[sync-dist] Copied ${distDir} -> ${targetDir}`);
