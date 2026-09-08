import { isAbsolute } from "node:path";

const TOKEN_PATTERN = /^[A-Za-z0-9_-]{32,256}$/;

export class BackendLaunchError extends Error {
    constructor(message) {
        super(message);
        this.name = "BackendLaunchError";
    }
}

export function buildBackendLaunch({
    jarPath,
    token,
    port,
    javaExecutable = "java",
    parentEnvironment = process.env
}) {
    if (typeof jarPath !== "string" || !isAbsolute(jarPath)) {
        throw new BackendLaunchError("The backend JAR path must be absolute.");
    }
    if (!TOKEN_PATTERN.test(token ?? "")) {
        throw new BackendLaunchError("The backend token is invalid.");
    }
    if (!Number.isInteger(port) || port < 1024 || port > 65535) {
        throw new BackendLaunchError("The backend port must be between 1024 and 65535.");
    }
    if (typeof javaExecutable !== "string" || javaExecutable.trim() === "" || javaExecutable.includes("\u0000")) {
        throw new BackendLaunchError("The Java executable is invalid.");
    }

    return {
        command: javaExecutable,
        args: [
            "-jar",
            jarPath,
            "--server.address=127.0.0.1",
            `--server.port=${port}`
        ],
        options: {
            env: { ...parentEnvironment, CODEX_QUOTA_GUARD_API_TOKEN: token },
            shell: false,
            stdio: ["ignore", "ignore", "pipe"],
            windowsHide: true
        }
    };
}
