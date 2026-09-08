import { contextBridge, ipcRenderer } from "electron";

contextBridge.exposeInMainWorld("quotaGuard", {
    status: () => ipcRenderer.invoke("quota:status")
});
