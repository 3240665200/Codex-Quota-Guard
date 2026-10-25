import axios, { type AxiosRequestConfig, type AxiosResponse } from "axios";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { api } from "./api";
import { ApiHttpHarness } from "@/test/api-http-harness";

vi.mock("@tauri-apps/api/core", () => ({ invoke: vi.fn() }));
const http = new ApiHttpHarness();
const encode = (text: string) => new TextEncoder().encode(text);
let streamResponse: (config: AxiosRequestConfig) => AxiosResponse;
let streamRequest: AxiosRequestConfig | undefined;

beforeEach(async () => {
  await http.prepare(api);
  streamRequest = undefined;
  const original = axios.request.bind(axios);
  vi.spyOn(axios, "request").mockImplementation((async (
    config: AxiosRequestConfig,
  ) => {
    if (config.adapter !== "fetch") return original(config);
    streamRequest = config;
    return streamResponse(config);
  }) as typeof axios.request);
});
afterEach(() => vi.restoreAllMocks());

function reply(
  config: AxiosRequestConfig,
  stream: ReadableStream<Uint8Array>,
  contentType = "text/event-stream",
) {
  return {
    config,
    data: stream,
    headers: { "content-type": contentType },
    status: 200,
    statusText: "OK",
  } as AxiosResponse;
}

describe("authenticated Axios SSE byte stream", () => {
  it("parses split frames and emits only invalidations while keeping credentials out of the URL", async () => {
    streamResponse = (config) =>
      reply(
        config,
        new ReadableStream<Uint8Array>({
          start(controller) {
            for (const part of [
              "event: con",
              'nected\r\ndata: {"code":"secret-otp"}\r\n\r\n',
              'event: state_changed\ndata: {"accessToken":"hidden"}\n\n',
              "event: otp\ndata: 123456\n\n",
            ])
              controller.enqueue(encode(part));
            controller.close();
          },
        }),
      );
    const events = vi.fn();
    await api.streamLoginAssistanceEvents(events, new AbortController().signal);
    expect(events.mock.calls).toEqual([["connected"], ["state_changed"]]);
    expect(streamRequest?.url).toBe("/api/v1/client/login-assistance/events");
    expect(streamRequest?.params).toBeUndefined();
    expect(streamRequest?.headers).toMatchObject({
      Authorization: "Bearer access-test-token",
      Accept: "text/event-stream",
    });
    expect(streamRequest?.headers).not.toHaveProperty("Cache-Control");
    expect(JSON.stringify(events.mock.calls)).not.toMatch(
      /secret-otp|hidden|123456/,
    );
  });

  it.each([20_000, 80_000])(
    "bounds oversized server frames (%i bytes) and cancels their stream",
    async (size) => {
      const canceled = vi.fn();
      streamResponse = (config) =>
        reply(
          config,
          new ReadableStream({
            start(controller) {
              controller.enqueue(
                encode(`event: state_changed\ndata: ${"x".repeat(size)}\n\n`),
              );
            },
            cancel: canceled,
          }),
        );
      const events = vi.fn();
      await expect(
        api.streamLoginAssistanceEvents(events, new AbortController().signal),
      ).rejects.toThrow(/超出限制/);
      expect(events).not.toHaveBeenCalled();
      expect(canceled).toHaveBeenCalledOnce();
    },
  );

  it("rejects an HTML response and closes the received stream", async () => {
    const canceled = vi.fn();
    streamResponse = (config) =>
      reply(config, new ReadableStream({ cancel: canceled }), "text/html");
    const events = vi.fn();
    await expect(
      api.streamLoginAssistanceEvents(events, new AbortController().signal),
    ).rejects.toThrow("事件流格式无效");
    expect(canceled).toHaveBeenCalledOnce();
    expect(events).not.toHaveBeenCalled();
  });

  it("aborts a pending read on disposal without delivering late events", async () => {
    let opened!: () => void;
    const ready = new Promise<void>((resolve) => {
      opened = resolve;
    });
    streamResponse = (config) => {
      const stream = new ReadableStream<Uint8Array>({
        start(controller) {
          const signal = config.signal as AbortSignal;
          signal.addEventListener(
            "abort",
            () => controller.error(new axios.CanceledError("disposed")),
            { once: true },
          );
        },
      });
      const getReader = stream.getReader.bind(stream);
      vi.spyOn(stream, "getReader").mockImplementation((() => {
        const reader = getReader();
        const read = reader.read.bind(reader);
        vi.spyOn(reader, "read").mockImplementation(() => {
          opened();
          return read();
        });
        return reader;
      }) as typeof stream.getReader);
      return reply(config, stream);
    };
    const events = vi.fn();
    const controller = new AbortController();
    const pending = api.streamLoginAssistanceEvents(events, controller.signal);
    await ready;
    const rejection = expect(pending).rejects.toMatchObject({
      __CANCEL__: true,
    });
    controller.abort();
    await rejection;
    expect((streamRequest?.signal as AbortSignal).aborted).toBe(true);
    expect(events).not.toHaveBeenCalled();
  });
});
