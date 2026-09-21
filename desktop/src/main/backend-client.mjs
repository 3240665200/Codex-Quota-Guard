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
        const payload = await this.#request("/api/v1/status");
        if (payload?.service !== "codex-quota-guard" || payload?.state !== "online") {
            throw new BackendClientError("The local backend returned an invalid status response.");
        }
        return { service: payload.service, state: payload.state };
    }

    async overview() {
        const payload = await this.#request("/api/v1/room/overview");
        if (typeof payload?.configured !== "boolean" || !Array.isArray(payload.members) || !Array.isArray(payload.warnings)) {
            throw new BackendClientError("The local backend returned an invalid room overview.");
        }
        return {
            configured: payload.configured,
            roomId: typeof payload.roomId === "string" ? payload.roomId : "",
            name: typeof payload.name === "string" ? payload.name : "",
            members: payload.members.map(normalizeMember),
            warnings: payload.warnings.filter((warning) => typeof warning === "string").slice(0, 50)
        };
    }

    async #request(path) {
        let response;
        try {
            response = await this.fetchImpl(`${this.baseUrl}${path}`, {
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
        return payload;
    }
}

function normalizeMember(member) {
    if (!member || typeof member.memberId !== "string" || typeof member.displayName !== "string") {
        throw new BackendClientError("The local backend returned an invalid member.");
    }
    return {
        memberId: member.memberId,
        displayName: member.displayName,
        managedLogin: member.managedLogin === true,
        enforcementConsent: member.enforcementConsent === true,
        snapshot: normalizeSnapshot(member.snapshot)
    };
}

function normalizeSnapshot(snapshot) {
    if (snapshot === null || snapshot === undefined) {
        return null;
    }
    if (typeof snapshot.capturedAt !== "string" || typeof snapshot.authenticated !== "boolean" || !Array.isArray(snapshot.buckets)) {
        throw new BackendClientError("The local backend returned an invalid quota snapshot.");
    }
    return {
        capturedAt: snapshot.capturedAt,
        authenticated: snapshot.authenticated,
        enforcement: {
            locked: snapshot.enforcement?.locked === true,
            lockedUntil: typeof snapshot.enforcement?.lockedUntil === "string" ? snapshot.enforcement.lockedUntil : null
        },
        buckets: snapshot.buckets.map((bucket) => ({
            limitId: typeof bucket?.limitId === "string" ? bucket.limitId : "unknown",
            limitName: typeof bucket?.limitName === "string" ? bucket.limitName : "",
            primary: normalizeWindow(bucket?.primary),
            secondary: normalizeWindow(bucket?.secondary)
        }))
    };
}

function normalizeWindow(window) {
    if (!window) {
        return null;
    }
    if (typeof window.usedPercent !== "number" || !Number.isFinite(window.usedPercent)
        || window.usedPercent < 0 || window.usedPercent > 100 || typeof window.resetsAt !== "string") {
        throw new BackendClientError("The local backend returned an invalid quota window.");
    }
    return {
        usedPercent: window.usedPercent,
        remainingPercent: typeof window.remainingPercent === "number" ? window.remainingPercent : 100 - window.usedPercent,
        resetsAt: window.resetsAt
    };
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
