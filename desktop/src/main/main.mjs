import { app, BrowserWindow, ipcMain, net, protocol, session } from "electron";
import { spawn } from "node:child_process";
import { randomBytes } from "node:crypto";
import { fileURLToPath, pathToFileURL } from "node:url";
import { dirname, isAbsolute, relative, resolve } from "node:path";

import { BackendClient } from "./backend-client.mjs";
import { buildBackendLaunch } from "./backend-launch.mjs";
import { isTrustedRendererUrl } from "./renderer-origin.mjs";

const currentDirectory = dirname(fileURLToPath(import.meta.url));
const rendererDirectory = resolve(currentDirectory, "../renderer");

protocol.registerSchemesAsPrivileged([
    {
        scheme: "app",
        privileges: {
            standard: true,
            secure: true,
            supportFetchAPI: true
        }
    }
]);

app.enableSandbox();

let backendRuntime;

app.whenReady().then(async () => {
    configureLocalRendererProtocol();
    session.defaultSession.setPermissionRequestHandler((_webContents, _permission, callback) => callback(false));
    backendRuntime = startBackend();
    registerIpcHandlers();
    await createWindow();
});

app.on("window-all-closed", () => {
    if (process.platform !== "darwin") {
        app.quit();
    }
});

app.on("before-quit", stopBackend);

async function createWindow() {
    const window = new BrowserWindow({
        width: 1120,
        height: 760,
        minWidth: 880,
        minHeight: 600,
        webPreferences: {
            preload: resolve(currentDirectory, "../preload/preload.mjs"),
            contextIsolation: true,
            nodeIntegration: false,
            sandbox: true,
            webSecurity: true,
            webviewTag: false
        }
    });

    window.webContents.setWindowOpenHandler(() => ({ action: "deny" }));
    window.webContents.on("will-navigate", (event, url) => {
        if (!isTrustedRendererUrl(url)) {
            event.preventDefault();
        }
    });
    await window.loadURL("app://quota-guard/index.html");
}

function startBackend() {
    const token = randomBytes(32).toString("base64url");
    const port = 47821;
    const launch = buildBackendLaunch({
        jarPath: resolveBackendJarPath(),
        token,
        port
    });
    const backendProcess = spawn(launch.command, launch.args, launch.options);
    backendProcess.stderr.on("data", () => {
        // The renderer receives only a generic availability message; backend output may contain local paths.
    });
    backendProcess.on("error", () => {
        // The status request will report the backend as unavailable without exposing process details.
    });
    return {
        backendProcess,
        client: new BackendClient({ baseUrl: `http://127.0.0.1:${port}`, token })
    };
}

function resolveBackendJarPath() {
    if (process.env.CODEX_QUOTA_GUARD_BACKEND_JAR) {
        return resolve(process.env.CODEX_QUOTA_GUARD_BACKEND_JAR);
    }
    if (app.isPackaged) {
        return resolve(process.resourcesPath, "backend", "codex-quota-guard-backend.jar");
    }
    return resolve(currentDirectory, "../../../backend/target/backend-0.1.0-SNAPSHOT.jar");
}

function stopBackend() {
    const backendProcess = backendRuntime?.backendProcess;
    if (backendProcess && backendProcess.exitCode === null && !backendProcess.killed) {
        backendProcess.kill();
    }
}

function configureLocalRendererProtocol() {
    protocol.handle("app", (request) => {
        if (!isTrustedRendererUrl(request.url)) {
            return new Response("Not found", { status: 404 });
        }

        const requestedUrl = new URL(request.url);
        const requestedPath = requestedUrl.pathname === "/" ? "/index.html" : decodeURIComponent(requestedUrl.pathname);
        const candidate = resolve(rendererDirectory, `.${requestedPath}`);
        const relativePath = relative(rendererDirectory, candidate);
        if (relativePath.startsWith("..") || isAbsolute(relativePath)) {
            return new Response("Not found", { status: 404 });
        }
        return net.fetch(pathToFileURL(candidate).toString());
    });
}

function registerIpcHandlers() {
    ipcMain.handle("quota:status", async (event) => {
        assertTrustedRenderer(event);
        return requireBackend().status();
    });
    ipcMain.handle("quota:overview", async (event) => {
        assertTrustedRenderer(event);
        return requireBackend().overview();
    });
}

function assertTrustedRenderer(event) {
    if (!isTrustedRendererUrl(event.senderFrame.url)) {
        throw new Error("Untrusted renderer attempted to access the local backend.");
    }
}

function requireBackend() {
    if (!backendRuntime) {
        throw new Error("The local backend is starting.");
    }
    return backendRuntime.client;
}
