const statusElement = document.querySelector("#service-status");
const refreshButton = document.querySelector("#refresh-status");

refreshButton.addEventListener("click", () => void refreshStatus());
void refreshStatus();

async function refreshStatus() {
    refreshButton.disabled = true;
    statusElement.textContent = "正在连接本地服务…";
    try {
        const status = await window.quotaGuard.status();
        statusElement.textContent = status.state === "online" ? "本地服务正常运行" : "本地服务状态未知";
    } catch {
        statusElement.textContent = "无法连接本地服务。请确认桌面应用已从受管入口启动。";
    } finally {
        refreshButton.disabled = false;
    }
}
