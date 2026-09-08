const TOKEN_PATTERN = /^[A-Za-z0-9_-]{32,256}$/;
const LOOPBACK_HOSTS = new Set(["127.0.0.1", "::1"]);

export class BackendClientError extends Error {
    constructor(message) {
        super(message);
        this.name = "BackendClientError";
    }
}

export class BackendClient {
    constructor({ baseUrl, token, fetchImpl = globalThis.fetch }) {
        this.baseUrl = normalizeLoopbackUrl(baseUrl);
        if (!TOKEN_PATTERN.test(token ?? "")) {
            throw new BackendClientError("The local backend token is invalid.");
        }
        if (typeof fetchImpl !== "function") {
            throw new BackendClientError("A fetch implementation is required.");
        }
        this.token = token;
        this.fetchImpl = fetchImpl;
    }

    async status() {
        let response;
        try {
            response = await this.fetchImpl(`${this.baseUrl}/api/v1/status`, {
                method: "GET",
                headers: { Authorization: `Bearer ${this.token}` }
            });
        } catch {
            throw new BackendClientError("The local backend is unavailable.");
        }

        if (!response?.ok) {
            throw new BackendClientError("The local backend rejected the request.");
        }

        let payload;
        try {
            payload = await response.json();
        } catch {
            throw new BackendClientError("The local backend returned invalid JSON.");
        }

        if (payload?.service !== "codex-quota-guard" || payload?.state !== "online") {
            throw new BackendClientError("The local backend returned an invalid status response.");
        }
        return { service: payload.service, state: payload.state };
    }
}

function normalizeLoopbackUrl(value) {
    let parsed;
    try {
        parsed = new URL(value);
    } catch {
        throw new BackendClientError("The local backend URL is invalid.");
    }

    if (parsed.protocol !== "http:" || !LOOPBACK_HOSTS.has(parsed.hostname)) {
        throw new BackendClientError("The backend must use an HTTP loopback URL.");
    }
    if (parsed.pathname !== "/" || parsed.search || parsed.hash || parsed.username || parsed.password) {
        throw new BackendClientError("The backend URL must not contain a path or credentials.");
    }
    return parsed.origin;
}
