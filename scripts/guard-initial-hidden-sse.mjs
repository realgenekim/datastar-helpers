import { readFileSync, writeFileSync } from "node:fs";

const before = `};D()});g({name:"attr",requirement`;
const after = `};(h||!document.hidden)&&D()});g({name:"attr",requirement`;

if (process.argv.length < 3) {
  throw new Error("usage: node scripts/guard-initial-hidden-sse.mjs BUNDLE...");
}

for (const path of process.argv.slice(2)) {
  const source = readFileSync(path, "utf8");
  const matches = source.split(before).length - 1;
  if (matches !== 1) {
    throw new Error(`${path}: expected one unguarded initial SSE open, found ${matches}`);
  }
  writeFileSync(path, source.replace(before, after));
}
