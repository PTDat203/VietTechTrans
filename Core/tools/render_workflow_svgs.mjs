"use strict";

import path from "path";
import fs from "fs";
import { fileURLToPath } from "url";
import { createRequire } from "module";

const require = createRequire(import.meta.url);
const sharp = require("sharp");

const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), "..");
const outDir = path.join(root, "assets", "report_workflows");
fs.mkdirSync(outDir, { recursive: true });

(async () => {
  for (const phase of ["01", "02", "03", "04", "05"]) {
    const source = path.join(root, "docs", `phase_${phase}`, "workflow.svg");
    const output = path.join(outDir, `giai_doan_${phase}.png`);
    await sharp(source, { density: 300 }).png().toFile(output);
    process.stdout.write(`${output}\n`);
  }
})().catch((error) => {
  console.error(error);
  process.exit(1);
});
