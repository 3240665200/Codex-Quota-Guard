import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";

const { axiosRequest, invokeMock } = vi.hoisted(() => ({
  axiosRequest: vi.fn(),
  invokeMock: vi.fn(),
}));

vi.mock("axios", () => ({
  default: {
    request: axiosRequest,
    isAxiosError: (error: unknown) =>
      typeof error === "object" &&
      error !== null &&
      "isAxiosError" in error &&
      error.isAxiosError === true,
    isCancel: (error: unknown) =>
      typeof error === "object" &&
      error !== null &&
      "__CANCEL__" in error &&
      error.__CANCEL__ === true,
    CanceledError: class extends Error {
      readonly __CANCEL__ = true;

      constructor(message: string) {
        super(message);
        this.name = "CanceledError";
      }
    },
  },
}));

vi.mock("@tauri-apps/api/core", () => ({ invoke: invokeMock }));

const user = {
  id: "11111111-1111-4111-8111-111111111111",
  email: "alice@example.com",
  displayName: "Alice",
  role: "USER",
  status: "ACTIVE",
};
const deviceId = "22222222-2222-4222-8222-222222222222";
const emptyIdentity = { deviceId: null, enrollmentId: null };
const validIdentity = { deviceId, enrollmentId: deviceId };
const centralUrl = "http://central.example";
const sevenDaysSeconds = 7 * 86400;
const thirtyDaysSeconds = 30 * 86400;
const fixedNow = new Date("2026-10-21T00:00:00.000Z");

type RequestConfig = {
  url?: string;
  method?: string;
  baseURL?: string;
  headers?: Record<string, unknown>;
  data?: unknown;
  signal?: AbortSignal;
};

type StoredSession = {
  serverOrigin: string;
  refreshToken: string;
  userId: string;
  loginAtEpochSeconds: number;
  expiresAtEpochSeconds: number;
};
type DeviceIdentity = { deviceId: string | null; enrollmentId: string | null };

let api: typeof import("./api").api;
let configuredIdentity: DeviceIdentity = emptyIdentity;
let storedVault: StoredSession | null = null;
let vaultWrites: StoredSession[] = [];
let pairingWrites: Record<string, unknown>[] = [];
let savedServerUrl = centralUrl;

function configureNativeMocks() {
  configuredIdentity = emptyIdentity;
  storedVault = null;
  vaultWrites = [];
  pairingWrites = [];
  savedServerUrl = centralUrl;
  invokeMock.mockImplementation(
    async (
      command: string,
      args?: Record<string, unknown>,
    ): Promise<unknown> => {
      switch (command) {
        case "server_config_get":
          return { url: savedServerUrl };
        case "server_config_save": {
          const config = args?.config as { url?: string } | undefined;
          savedServerUrl = config?.url ?? savedServerUrl;
          return { url: savedServerUrl };
        }
        case "device_identity":
          return configuredIdentity;
        case "session_vault_load":
          return storedVault;
        case "session_vault_save": {
          const session = args?.session as StoredSession;
          storedVault = session;
          vaultWrites.push(session);
          return null;
        }
        case "session_vault_clear":
          storedVault = null;
          return null;
        case "meter_pairing_write":
          pairingWrites.push(args?.pairing as Record<string, unknown>);
          return null;
        default:
          return null;
      }
    },
  );
}

function authResponse(
  accessToken: string,
  refreshToken: string,
  options: { expiresIn?: number; refreshExpiresIn?: number } = {},
) {
  return {
    accessToken,
    tokenType: "Bearer",
    expiresIn: options.expiresIn ?? 3600,
    refreshToken,
    refreshExpiresIn: options.refreshExpiresIn ?? thirtyDaysSeconds,
    user,
  };
}

function clientAccessResponse() {
  return { allowed: true, bindingId: user.id, reason: null };
}

function response(config: RequestConfig, data: unknown) {
  return {
    data,
    status: 200,
    statusText: "OK",
    headers: { "content-type": "application/json" },
    config,
  };
}

function rejectStatus(config: RequestConfig, status: number) {
  return Promise.reject({
    isAxiosError: true,
    message: `Request failed with status code ${status}`,
    config,
    response: {
      status,
      statusText: "",
      data: null,
      headers: {},
      config,
    },
  });
}

function authorization(config: RequestConfig) {
  return config.headers?.Authorization as string | undefined;
}

function deferred<T>() {
  let resolve!: (value: T | PromiseLike<T>) => void;
  let reject!: (reason?: unknown) => void;
  const promise = new Promise<T>((resolvePromise, rejectPromise) => {
    resolve = resolvePromise;
    reject = rejectPromise;
  });
  return { promise, resolve, reject };
}

beforeEach(async () => {
  vi.useRealTimers();
  vi.clearAllMocks();
  axiosRequest.mockReset();
  invokeMock.mockReset();
  vi.resetModules();
  configureNativeMocks();
  api = (await import("./api")).api;
});

afterEach(() => {
  vi.useRealTimers();
});

describe("Axios authentication races", () => {
  it("shares one refresh across concurrent 401s and retries each request with the new access token", async () => {
    const bothInitialRequests = deferred<void>();
    let firstTokenRequests = 0;
    let refreshRequests = 0;
    const retryTokens: string[] = [];

    axiosRequest.mockImplementation(async (config: RequestConfig) => {
      if (config.url === "/api/v1/auth/login") {
        return response(config, authResponse("access-v1", "refresh-v1"));
      }
      if (config.url === "/api/v1/auth/refresh") {
        refreshRequests += 1;
        return response(config, authResponse("access-v2", "refresh-v2"));
      }
      if (config.url === "/api/v1/client/access") {
        if (authorization(config) === "Bearer access-v1") {
          firstTokenRequests += 1;
          if (firstTokenRequests === 2) bothInitialRequests.resolve(undefined);
          await bothInitialRequests.promise;
          return rejectStatus(config, 401);
        }
        retryTokens.push(authorization(config) ?? "");
        return response(config, clientAccessResponse());
      }
      throw new Error(`Unexpected request: ${config.method} ${config.url}`);
    });

    await api.systemLogin(user.email, "password");
    const results = await Promise.all([api.clientAccess(), api.clientAccess()]);

    expect(results).toEqual([clientAccessResponse(), clientAccessResponse()]);
    expect(firstTokenRequests).toBe(2);
    expect(refreshRequests).toBe(1);
    expect(retryTokens).toEqual(["Bearer access-v2", "Bearer access-v2"]);
  });

  it.each(["logout", "server switch"] as const)(
    "%s cancels an older refresh and prevents it from saving or reviving the session",
    async (invalidation) => {
      const refreshStarted = deferred<void>();
      const pendingRefresh = deferred<unknown>();
      let refreshConfig: RequestConfig | undefined;

      axiosRequest.mockImplementation((config: RequestConfig) => {
        if (config.url === "/api/v1/auth/login") {
          return Promise.resolve(
            response(config, authResponse("access-v1", "refresh-v1")),
          );
        }
        if (config.url === "/api/v1/client/access") {
          return rejectStatus(config, 401);
        }
        if (config.url === "/api/v1/auth/refresh") {
          refreshConfig = config;
          refreshStarted.resolve(undefined);
          return pendingRefresh.promise;
        }
        if (config.url === "/api/v1/auth/logout") {
          return Promise.resolve(response(config, {}));
        }
        if (config.url === "/api/v1/users/me") {
          return Promise.resolve(response(config, user));
        }
        throw new Error(`Unexpected request: ${config.method} ${config.url}`);
      });

      await api.systemLogin(user.email, "password");
      const protectedOutcome = api.clientAccess().then(
        () => null,
        (error: unknown) => error,
      );
      await refreshStarted.promise;

      if (invalidation === "logout") {
        await api.systemLogout();
      } else {
        await api.saveServerConfig({ url: "http://replacement.example" });
      }

      expect(refreshConfig?.signal?.aborted).toBe(true);
      pendingRefresh.resolve(
        response(refreshConfig!, authResponse("access-v2", "refresh-v2")),
      );
      const staleRequestError = await protectedOutcome;

      expect(staleRequestError).toBeInstanceOf(Error);
      expect(vaultWrites).toHaveLength(1);
      await expect(api.systemSession()).resolves.toEqual({
        authenticated: false,
        user: null,
      });
      expect(vaultWrites).toHaveLength(1);
      expect(storedVault).toBeNull();
    },
  );

  it("keeps the original seven-day local login deadline after a thirty-day refresh", async () => {
    vi.useFakeTimers();
    vi.setSystemTime(fixedNow);
    let refreshRequests = 0;

    axiosRequest.mockImplementation(async (config: RequestConfig) => {
      if (config.url === "/api/v1/auth/login") {
        return response(
          config,
          authResponse("access-v1", "refresh-v1", { expiresIn: 60 }),
        );
      }
      if (config.url === "/api/v1/auth/refresh") {
        refreshRequests += 1;
        return response(
          config,
          authResponse("access-v2", "refresh-v2", {
            expiresIn: 3600,
            refreshExpiresIn: thirtyDaysSeconds,
          }),
        );
      }
      if (config.url === "/api/v1/client/access") {
        return response(config, clientAccessResponse());
      }
      throw new Error(`Unexpected request: ${config.method} ${config.url}`);
    });

    await api.systemLogin(user.email, "password");
    await vi.advanceTimersByTimeAsync(61_000);
    await expect(api.clientAccess()).resolves.toEqual(clientAccessResponse());

    expect(vaultWrites).toHaveLength(2);
    const refreshedVault = vaultWrites[1]!;
    expect(refreshedVault.expiresAtEpochSeconds).toBeGreaterThan(
      refreshedVault.loginAtEpochSeconds + sevenDaysSeconds,
    );

    vi.setSystemTime(
      new Date(fixedNow.getTime() + (sevenDaysSeconds + 1) * 1000),
    );
    await expect(api.systemSession()).resolves.toEqual({
      authenticated: false,
      user: null,
    });

    expect(refreshRequests).toBe(1);
    expect(vaultWrites).toHaveLength(2);
    expect(storedVault).toBeNull();
  });

  it("retries failed meter enrollment after backoff and does not pair again after success", async () => {
    vi.useFakeTimers();
    vi.setSystemTime(fixedNow);
    configuredIdentity = validIdentity;
    let enrollmentRequests = 0;

    axiosRequest.mockImplementation(async (config: RequestConfig) => {
      if (config.url === "/api/v1/auth/login") {
        return response(config, authResponse("access-v1", "refresh-v1"));
      }
      if (config.url === "/api/v1/client/meter/enroll") {
        enrollmentRequests += 1;
        if (enrollmentRequests === 1) return rejectStatus(config, 503);
        return response(config, {
          deviceId,
          deviceToken: "meter-device-token",
          expiresAt: "2027-10-21T00:00:00Z",
          userId: user.id,
        });
      }
      if (config.url === "/api/v1/users/me") {
        return response(config, user);
      }
      throw new Error(`Unexpected request: ${config.method} ${config.url}`);
    });

    await api.systemLogin(user.email, "password");
    expect(enrollmentRequests).toBe(1);
    expect(api.meterPairingError()).toBe("PAIRING_ENROLL_FAILED");

    await api.systemSession();
    expect(enrollmentRequests).toBe(1);
    expect(pairingWrites).toHaveLength(0);

    await vi.advanceTimersByTimeAsync(5_001);
    await expect(api.systemSession()).resolves.toMatchObject({
      authenticated: true,
      user,
    });
    expect(api.meterPairingError()).toBeNull();
    expect(enrollmentRequests).toBe(2);
    expect(pairingWrites).toEqual([
      {
        deviceId,
        deviceToken: "meter-device-token",
        userId: user.id,
        serverOrigin: centralUrl,
      },
    ]);

    await api.systemSession();
    expect(enrollmentRequests).toBe(2);
    expect(pairingWrites).toHaveLength(1);
  });
});
