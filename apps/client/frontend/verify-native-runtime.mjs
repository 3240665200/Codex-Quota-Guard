import { readFileSync } from "node:fs";

// Inspect PE imports directly so the packaging check needs no dumpbin utility.
export function importedDlls(binaryPath) {
  const bytes = readFileSync(binaryPath);
  const pe = bytes.readUInt32LE(0x3c);
  if (bytes.toString("ascii", pe, pe + 4) !== "PE\0\0") {
    throw new Error("Expected a Windows PE executable.");
  }
  const optional = pe + 24;
  const magic = bytes.readUInt16LE(optional);
  if (magic !== 0x20b && magic !== 0x10b)
    throw new Error("Unsupported PE format.");
  const directories = optional + (magic === 0x20b ? 112 : 96);
  const sectionTable = optional + bytes.readUInt16LE(pe + 20);
  const sectionCount = bytes.readUInt16LE(pe + 6);
  const offset = (rva) => {
    for (let index = 0; index < sectionCount; index++) {
      const section = sectionTable + index * 40;
      const start = bytes.readUInt32LE(section + 12);
      const size = Math.max(
        bytes.readUInt32LE(section + 8),
        bytes.readUInt32LE(section + 16),
      );
      if (rva >= start && rva < start + size) {
        return bytes.readUInt32LE(section + 20) + rva - start;
      }
    }
    throw new Error("Invalid import address in Windows executable.");
  };
  const names = [];
  const imports = bytes.readUInt32LE(directories + 8);
  if (!imports) return names;
  for (let descriptor = offset(imports); ; descriptor += 20) {
    const nameRva = bytes.readUInt32LE(descriptor + 12);
    if (!nameRva) break;
    const start = offset(nameRva);
    const end = bytes.indexOf(0, start);
    if (end < 0) throw new Error("Invalid DLL name in Windows executable.");
    names.push(bytes.toString("ascii", start, end));
  }
  return names;
}

export function verifyNativeRuntime(binaryPath) {
  const dependencies = importedDlls(binaryPath);
  const extraRuntime = dependencies.filter((name) =>
    /^(vcruntime|msvcp|concrt)/i.test(name),
  );
  if (extraRuntime.length) {
    throw new Error(
      `Native executable requires an unbundled VC++ runtime: ${extraRuntime.join(", ")}. Build with +crt-static.`,
    );
  }
  return dependencies;
}
