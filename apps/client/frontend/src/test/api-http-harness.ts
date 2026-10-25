import axios, {
  AxiosError,
  type AxiosAdapter,
  type AxiosRequestConfig,
} from "axios";
import { invoke } from "@tauri-apps/api/core";
import { vi } from "vitest";

export const apiTestUser = {
  id: "11111111-1111-4111-8111-111111111111",
  email: "alice@example.com",
  displayName: "Alice",
  role: "USER",
  status: "ACTIVE",
};

export const apiTestDeviceId = "99999999-9999-4999-8999-999999999999";

type RouteResponse = {
  data: unknown;
  status?: number;
  headers?: Record<string, string>;
};
type RouteHandler = (
  config: AxiosRequestConfig,
) => RouteResponse | Promise<RouteResponse>;

export class ApiHttpHarness {
  readonly native = vi.mocked(invoke);
  readonly requests: AxiosRequestConfig[] = [];
  readonly routes = new Map<string, RouteHandler>();
  centralResponse: unknown = null;
  private serverUrl = "https://control.example.test:8443";
  private vault: unknown = null;
  private resolvedValueWrapped = false;
  private readonly adapter: AxiosAdapter = async (config) => {
    this.requests.push(config);
    const url = new URL(config.url ?? "/", config.baseURL ?? this.serverUrl);
    const method = (config.method ?? "GET").toUpperCase();
    const route = this.routes.get(`${method} ${url.pathname}`);
    const result = route
      ? await route(config)
      : this.defaultResponse(method, url.pathname, config);
    const status = result.status ?? 200;
    const response = {
      config,
      data: result.data,
      headers: result.headers ?? {},
      request: {},
      status,
      statusText: status === 204 ? "No Content" : "OK",
    };
    if (status < 200 || status >= 300) {
      throw new AxiosError(
        `Request failed with status code ${status}`,
        undefined,
        config,
        {},
        response,
      );
    }
    return response;
  };

  async prepare(api: {
    serverConfig: () => Promise<unknown>;
    systemLogout: () => Promise<void>;
    systemLogin: (email: string, password: string) => Promise<unknown>;
  }) {
    this.native.mockReset();
    this.requests.length = 0;
    this.routes.clear();
    this.centralResponse = null;
    this.serverUrl = "https://control.example.test:8443";
    this.vault = null;
    this.native.mockImplementation(async (command, args) => {
      switch (command) {
        case "server_config_get":
          return { url: this.serverUrl };
        case "server_config_save": {
          const url = (args as { config?: { url?: unknown } } | undefined)
            ?.config?.url;
          if (typeof url !== "string") throw new Error("bad config");
          this.serverUrl = url;
          return { url, meterSync: "UPDATED" };
        }
        case "device_identity":
          return { deviceId: apiTestDeviceId, enrollmentId: apiTestDeviceId };
        case "session_vault_load":
          return this.vault;
        case "session_vault_save":
          this.vault =
            (args as { session?: unknown } | undefined)?.session ?? null;
          return null;
        case "session_vault_clear":
          this.vault = null;
          return null;
        case "meter_pairing_write":
          return { state: "PAIRING_SENT" };
        case "meter_status":
          return {
            state: "UP_TO_DATE",
            lastScanEpochSeconds: 1_800_000_000,
            lastReportEpochSeconds: 1_800_000_000,
            reportedEvents: 1,
            errorCode: null,
            version: "0.3.24",
          };
        case "meter_runtime_logs":
          return { state: "READY", skippedLines: 0, entries: [] };
        case "meter_service_version":
          return {
            installedVersion: "0.3.24",
            bundledVersion: "0.3.24",
            needsUpgrade: false,
            status: "CURRENT",
          };
        case "codex_rate_limits":
          return {
            planType: "pro",
            windows: [],
            fetchedAt: new Date().toISOString(),
          };
        case "autostart_status":
          return { supported: true, enabled: false };
        case "autostart_set":
          return { supported: true, enabled: true };
        case "startup_gate_check":
        case "startup_guard_status":
          return { state: "READY", ccSwitchTerminated: false };
        default:
          return null;
      }
    });
    axios.defaults.adapter = this.adapter;
    this.wrapMockResolvedValue();

    await api.serverConfig();
    await api.systemLogout();
    await api.systemLogin("alice@example.com", "test-password");
    this.requests.length = 0;
    this.native.mockClear();
  }

  respond(data: unknown) {
    this.centralResponse = data;
  }

  route(method: string, path: string, handler: RouteHandler) {
    this.routes.set(`${method.toUpperCase()} ${path}`, handler);
  }

  requestsFor(method: string, path: string) {
    return this.requests.filter((request) => {
      const url = new URL(
        request.url ?? "/",
        request.baseURL ?? this.serverUrl,
      );
      return (
        (request.method ?? "GET").toUpperCase() === method.toUpperCase() &&
        url.pathname === path
      );
    });
  }

  private wrapMockResolvedValue() {
    if (this.resolvedValueWrapped) return;
    this.resolvedValueWrapped = true;
    const original = this.native.mockResolvedValue.bind(this.native);
    this.native.mockResolvedValue = ((value: unknown) => {
      this.centralResponse = value;
      return original(value);
    }) as typeof this.native.mockResolvedValue;
  }

  private defaultResponse(
    method: string,
    path: string,
    config: AxiosRequestConfig,
  ): RouteResponse {
    if (path === "/api/v1/auth/login" || path === "/api/v1/auth/refresh") {
      return {
        data: {
          accessToken: "access-test-token",
          tokenType: "Bearer",
          expiresIn: 3600,
          refreshToken: "refresh-test-token",
          refreshExpiresIn: 7 * 86400,
          user: apiTestUser,
        },
      };
    }
    if (path === "/api/v1/auth/logout") return { data: null, status: 204 };
    if (path === "/api/v1/users/me") return { data: apiTestUser };
    if (path === "/api/v1/client/meter/enroll") {
      return {
        data: {
          deviceId: apiTestDeviceId,
          deviceToken: "meter-test-token",
          expiresAt: new Date(Date.now() + 86_400_000).toISOString(),
          userId: apiTestUser.id,
        },
      };
    }
    if (path === "/api/v1/client/official-quota")
      return { data: null, status: 204 };
    if (path === "/api/v1/client/login-assistance/events") {
      return {
        data: new ReadableStream<Uint8Array>({
          start(controller) {
            controller.enqueue(
              new TextEncoder().encode("event: connected\ndata: {}\n\n"),
            );
          },
        }),
        headers: { "content-type": "text/event-stream" },
      };
    }
    if (path === "/api/v1/client/session-bindings/current") {
      return { data: this.centralResponse };
    }
    if (path.startsWith("/api/v1/client/session-bindings/attempts/")) {
      return { data: this.centralResponse };
    }
    if (
      path === "/api/v1/client/access" ||
      path === "/api/v1/client/quota/overview" ||
      path.startsWith("/api/v1/client/login-assistance") ||
      path === "/api/v1/client/usage/events" ||
      path === "/api/v1/client/usage/analytics"
    ) {
      return { data: this.centralResponse };
    }
    void method;
    void config;
    return { data: this.centralResponse };
  }
}
