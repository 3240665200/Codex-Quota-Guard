import { app, BrowserWindow, ipcMain, net, protocol, session } from "electron";
import { fileURLToPath, pathToFileURL } from "node:url";
import { dirname, isAbsolute, relative, resolve } from "node:path";

import { BackendClient } from "./backend-client.mjs";
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

const backendClient = createBackendClientFromEnvironment();

app.whenReady().then(async () => {
    configureLocalRendererProtocol();
    session.defaultSession.setPermissionRequestHandler((_webContents, _permission, callback) => callback(false));
    registerIpcHandlers();
    await createWindow();
});

app.on("window-all-closed", () => {
    if (process.platform !== "darwin") {
        app.quit();
    }
});

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

function createBackendClientFromEnvironment() {
    const token = process.env.CODEX_QUOTA_GUARD_API_TOKEN;
    if (!token) {
        throw new Error("The desktop app must launch the backend with an ephemeral API token.");
    }
    return new BackendClient({
        baseUrl: process.env.CODEX_QUOTA_GUARD_BACKEND_URL ?? "http://127.0.0.1:47821",
        token
    });
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
        if (!isTrustedRendererUrl(event.senderFrame.url)) {
            throw new Error("Untrusted renderer attempted to access the local backend.");
        }
        return backendClient.status();
    });
}
