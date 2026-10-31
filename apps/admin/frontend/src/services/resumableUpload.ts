import axios from "axios";
import { sha256 } from "@noble/hashes/sha2.js";
import { bytesToHex } from "@noble/hashes/utils.js";
import { request } from "./api";
import type { DesktopRelease, ReleaseKind } from "./desktopReleasesApi";

export type UploadState = {
  id: string;
  component: "DESKTOP" | "METER";
  releaseId: string;
  status: "OPEN" | "COMPLETED" | "EXPIRED";
  chunkSize: number;
  chunkCount: number;
  receivedChunks: number[];
  receivedBytes: number;
  expiresAt: string;
};
export type UploadProgress = {
  stage: "hashing" | "uploading" | "verifying";
  percent: number;
  loaded: number;
  total: number;
  resumedBytes?: number;
};
type Input = {
  release: DesktopRelease;
  kind: ReleaseKind;
  target: string;
  arch: string;
  file: File;
  signature: File;
  signal: AbortSignal;
  resumeId?: string;
  onSession: (state: UploadState) => void;
  onProgress: (progress: UploadProgress) => void;
};
const base = "/api/v1/admin/release-uploads";
const fileHashes = new WeakMap<File, string>();
const pauseError = () => new DOMException("上传已暂停", "AbortError");
function check(signal: AbortSignal) {
  if (signal.aborted) throw pauseError();
}
async function bytes(blob: Blob, signal: AbortSignal) {
  check(signal);
  const buffer = await blob.arrayBuffer();
  check(signal);
  return new Uint8Array(buffer);
}
export async function fileSha256(
  file: File,
  signal: AbortSignal,
  progress: (percent: number) => void,
): Promise<string> {
  const cached = fileHashes.get(file);
  if (cached) return cached;
  const hash = sha256.create();
  try {
    for (let offset = 0; offset < file.size; offset += 4 * 1024 * 1024) {
      const block = await bytes(
        file.slice(offset, offset + 4 * 1024 * 1024),
        signal,
      );
      hash.update(block);
      progress(Math.min(100, ((offset + block.byteLength) / file.size) * 100));
      await new Promise((resolve) => setTimeout(resolve, 0));
    }
    check(signal);
    const result = bytesToHex(hash.digest());
    fileHashes.set(file, result);
    return result;
  } finally {
    hash.destroy();
  }
}
export const discardUpload = (id: string) =>
  request<void>(`${base}/${id}`, { method: "DELETE", cache: "no-store" });
export async function uploadRelease(input: Input): Promise<DesktopRelease> {
  const { file, signal, onProgress, onSession } = input;
  let state: UploadState | undefined;
  if (input.resumeId) {
    state = await request<UploadState>(`${base}/${input.resumeId}`, {
      cache: "no-store",
      signal,
    });
    if (state.status === "EXPIRED" || (state.status === "OPEN" && Date.parse(state.expiresAt) <= Date.now()))
      state = undefined;
  }
  if (!state) {
    onProgress({ stage: "hashing", percent: 0, loaded: 0, total: file.size });
    const hash = await fileSha256(file, signal, (percent) =>
      onProgress({ stage: "hashing", percent, loaded: 0, total: file.size }),
    );
    const signature = new TextDecoder()
      .decode(await bytes(input.signature, signal))
      .trim();
    state = await request<UploadState>(base, {
      method: "POST",
      cache: "no-store",
      signal,
      body: JSON.stringify({
        component: input.kind.toUpperCase(),
        releaseId: input.release.id,
        revision: input.release.revision,
        target: input.target,
        arch: input.arch,
        filename: file.name,
        sizeBytes: file.size,
        sha256: hash,
        signature,
      }),
    });
  }
  onSession(state);
  check(signal);
  if (
    state.releaseId !== input.release.id ||
    state.component !== input.kind.toUpperCase() ||
    state.chunkSize <= 0 ||
    state.chunkSize > 8 * 1024 * 1024 ||
    state.chunkCount !== Math.ceil(file.size / state.chunkSize)
  )
    throw Error("服务器上传参数无效，请刷新后重试。");
  const resumedBytes = state.receivedBytes;
  for (let index = 0; index < state.chunkCount; index++) {
    check(signal);
    if (state.receivedChunks.includes(index)) continue;
    const start = index * state.chunkSize;
    const blob = file.slice(start, start + state.chunkSize);
    const hash = bytesToHex(sha256(await bytes(blob, signal)));
    const confirmed = state.receivedBytes;
    onProgress({
      stage: "uploading",
      percent: (confirmed / file.size) * 100,
      loaded: confirmed,
      total: file.size,
      resumedBytes,
    });
    let result: UploadState | undefined;
    for (let attempt = 0; attempt < 3; attempt++) {
      try {
        result = await request<UploadState>(
          `${base}/${state.id}/chunks/${index}`,
          {
            method: "PUT",
            cache: "no-store",
            signal,
            body: blob,
            headers: {
              "Content-Type": "application/octet-stream",
              "X-Chunk-SHA256": hash,
            },
          },
          {
            onUploadProgress: (event) => {
              const loaded = Math.min(
                file.size,
                confirmed + Math.min(blob.size, event.loaded),
              );
              onProgress({
                stage: "uploading",
                percent: (loaded / file.size) * 100,
                loaded,
                total: file.size,
                resumedBytes,
              });
            },
          },
        );
        break;
      } catch (failure) {
        check(signal);
        if (!axios.isAxiosError(failure) || failure.response || attempt === 2)
          throw failure;
        await new Promise((resolve) =>
          setTimeout(resolve, (attempt + 1) * 750),
        );
      }
    }
    if (!result) throw Error("上传中断，可重新选择相同文件继续。");
    state = result;
    onSession(state);
    onProgress({
      stage: "uploading",
      percent: (state.receivedBytes / file.size) * 100,
      loaded: state.receivedBytes,
      total: file.size,
      resumedBytes,
    });
  }
  check(signal);
  onProgress({
    stage: "verifying",
    percent: 100,
    loaded: file.size,
    total: file.size,
    resumedBytes,
  });
  return request<DesktopRelease>(`${base}/${state.id}/complete`, {
    method: "POST",
    cache: "no-store",
    signal,
  });
}
