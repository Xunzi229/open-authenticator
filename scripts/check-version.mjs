import { readFile } from "node:fs/promises";

const packageJson = JSON.parse(await readFile("package.json", "utf8"));
const tauriConfig = JSON.parse(await readFile("src-tauri/tauri.conf.json", "utf8"));
const cargoToml = await readFile("src-tauri/Cargo.toml", "utf8");
const cargoVersion = cargoToml.match(/^version\s*=\s*"([^"]+)"/m)?.[1];

const versions = new Map([
  ["package.json", packageJson.version],
  ["src-tauri/Cargo.toml", cargoVersion],
  ["src-tauri/tauri.conf.json", tauriConfig.version],
]);
const expected = packageJson.version;

for (const [source, version] of versions) {
  if (version !== expected) {
    throw new Error(`Version mismatch: ${source} has ${version ?? "no version"}, expected ${expected}`);
  }
}

const semver = expected.match(/^(0|[1-9]\d*)\.(0|[1-9]\d*)\.(0|[1-9]\d*)(?:-([0-9A-Za-z.-]+))?$/);
if (!semver) {
  throw new Error(`Invalid application version: ${expected}`);
}

if (semver[4]) {
  const identifiers = semver[4].split(".");
  if (identifiers.some((identifier) => !/^\d+$/.test(identifier) || Number(identifier) > 65535)) {
    throw new Error(
      `Windows MSI requires numeric prerelease identifiers no greater than 65535: ${expected}`,
    );
  }
}

console.log(`Application version ${expected} is consistent and bundle-compatible.`);
