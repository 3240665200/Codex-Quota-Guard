import { File as NodeFile } from "node:buffer";
import { createHash } from "node:crypto";
import { afterEach, beforeEach, expect, it, vi } from "vitest";
import { request } from "./api";
import { fileSha256, uploadRelease, type UploadState } from "./resumableUpload";

vi.mock("./api", () => ({ request: vi.fn() }));
const chunkSize = 4 * 1024 * 1024;
const release = {
  id: "release-1",
  version: "0.4.14",
  notes: "",
  status: "DRAFT" as const,
  createdAt: "",
  publishedAt: null,
  revision: 0,
  artifacts: [],
};
const file = (value: Uint8Array | string, name = "client.exe") =>
  new NodeFile([value], name) as unknown as File;
function state(size: number, receivedChunks: number[] = []): UploadState {
  return {
    id: "upload-1",
    releaseId: release.id,
    component: "DESKTOP",
    status: "OPEN",
    chunkSize,
    chunkCount: Math.ceil(size / chunkSize),
    receivedChunks,
    receivedBytes: receivedChunks.reduce(
      (sum, index) => sum + Math.min(chunkSize, size - index * chunkSize),
      0,
    ),
    expiresAt: new Date(Date.now() + 86400000).toISOString(),
  };
}
beforeEach(() => {
  vi.mocked(request).mockReset();
});
afterEach(() => {
  vi.unstubAllGlobals();
});
it("hashes incrementally on HTTP pages without WebCrypto", async () => {
  vi.stubGlobal("crypto", {});
  const result = await fileSha256(
    file("abc"),
    new AbortController().signal,
    () => {},
  );
  expect(result).toBe(
    "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",
  );
});
it("restores server progress after reselecting a file and only sends missing chunks", async () => {
  const payload = new Uint8Array(chunkSize + 17).fill(8);
  const selected = file(payload);
  const progress = vi.fn();
  const handler = async (
    path: string,
    init?: RequestInit,
    options?: { onUploadProgress?: (event: { loaded: number }) => void },
  ) => {
    if (path.endsWith("/complete")) return release;
    if (path.endsWith("/chunks/1")) {
      expect(init?.headers).toMatchObject({
        "Content-Type": "application/octet-stream",
      });
      const bytes = new Uint8Array(await (init!.body as Blob).arrayBuffer());
      expect([...bytes]).toEqual([...payload.slice(chunkSize)]);
      options?.onUploadProgress?.({ loaded: bytes.length });
      return state(payload.length, [0, 1]);
    }
    expect(path).toBe("/api/v1/admin/release-uploads");
    const metadata = JSON.parse(String(init?.body));
    expect(metadata.sha256).toBe(
      createHash("sha256").update(payload).digest("hex"),
    );
    return state(payload.length, [0]);
  };
  vi.mocked(request).mockImplementation(handler as typeof request);
  await uploadRelease({
    release,
    kind: "desktop",
    target: "windows",
    arch: "x86_64",
    file: selected,
    signature: file("synthetic-signature", "client.sig"),
    signal: new AbortController().signal,
    onSession: () => {},
    onProgress: progress,
  });
  expect(
    vi.mocked(request).mock.calls.some(([path]) => path.endsWith("/chunks/0")),
  ).toBe(false);
  expect(
    progress.mock.calls.some(([value]) => value.resumedBytes === chunkSize),
  ).toBe(true);
  expect(progress.mock.calls.at(-1)?.[0]).toMatchObject({
    stage: "verifying",
    percent: 100,
  });
});
it("retains confirmed progress on pause and resumes without reuploading completed chunks", async () => {
  const payload = new Uint8Array(chunkSize + 3);
  const selected = file(payload);
  let saved = state(payload.length);
  let pause = true;
  const controller = new AbortController();
  const handler = async (path: string) => {
    if (path.endsWith("/complete")) return release;
    if (path.endsWith("/chunks/0")) {
      saved = state(payload.length, [0]);
      return saved;
    }
    if (path.endsWith("/chunks/1")) {
      if (pause) {
        controller.abort();
        throw new DOMException("paused", "AbortError");
      }
      saved = state(payload.length, [0, 1]);
      return saved;
    }
    return saved;
  };
  vi.mocked(request).mockImplementation(handler as typeof request);
  const input = {
    release,
    kind: "desktop" as const,
    target: "windows",
    arch: "x86_64",
    file: selected,
    signature: file("signature"),
    onSession: vi.fn(),
    onProgress: vi.fn(),
  };
  await expect(
    uploadRelease({ ...input, signal: controller.signal }),
  ).rejects.toMatchObject({ name: "AbortError" });
  expect(
    vi.mocked(request).mock.calls.some(([path]) => path.endsWith("/complete")),
  ).toBe(false);
  pause = false;
  await uploadRelease({
    ...input,
    signal: new AbortController().signal,
    resumeId: saved.id,
  });
  expect(
    vi
      .mocked(request)
      .mock.calls.filter(([path]) => path.endsWith("/chunks/0")),
  ).toHaveLength(1);
  expect(
    vi
      .mocked(request)
      .mock.calls.filter(([path]) => path.endsWith("/chunks/1")),
  ).toHaveLength(2);
});
it("retries a lost completion response without uploading the file again", async () => {
  const selected = file("MZ-synthetic");
  let saved = state(selected.size);
  let lost = true;
  const handler = async (path: string) => {
    if (path.endsWith("/chunks/0")) {
      saved = state(selected.size, [0]);
      return saved;
    }
    if (path.endsWith("/complete")) {
      saved = { ...saved, status: "COMPLETED", expiresAt: "2000-01-01T00:00:00Z" };
      if (lost) {
        lost = false;
        throw Error("connection lost");
      }
      return release;
    }
    return saved;
  };
  vi.mocked(request).mockImplementation(handler as typeof request);
  const input = {
    release,
    kind: "desktop" as const,
    target: "windows",
    arch: "x86_64",
    file: selected,
    signature: file("signature"),
    onSession: () => {},
    onProgress: () => {},
    signal: new AbortController().signal,
  };
  await expect(uploadRelease(input)).rejects.toThrow("connection lost");
  await expect(
    uploadRelease({ ...input, resumeId: saved.id }),
  ).resolves.toEqual(release);
  expect(
    vi
      .mocked(request)
      .mock.calls.filter(([path]) => path.endsWith("/chunks/0")),
  ).toHaveLength(1);
});
