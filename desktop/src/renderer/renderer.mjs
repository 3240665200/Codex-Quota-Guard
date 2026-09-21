const statusElement = document.querySelector("#service-status");
const connectionDot = document.querySelector("#connection-dot");
const refreshButton = document.querySelector("#refresh-status");
const roomName = document.querySelector("#room-name");
const roomMeta = document.querySelector("#room-meta");
const lastSync = document.querySelector("#last-sync");
const memberCount = document.querySelector("#member-count");
const memberSummary = document.querySelector("#member-summary");
const authenticatedCount = document.querySelector("#authenticated-count");
const warningCount = document.querySelector("#warning-count");
const memberUpdated = document.querySelector("#member-updated");
const membersElement = document.querySelector("#members");
const roomState = document.querySelector("#room-state");
const roomStateText = document.querySelector("#room-state-text");

refreshButton.addEventListener("click", () => void refreshDashboard());
void refreshDashboard();

async function refreshDashboard() {
    refreshButton.disabled = true;
    setConnection("正在连接本地服务…", "");
    roomState.classList.remove("notice-warning");
    roomStateText.textContent = "正在读取共享房间状态。";

    try {
        const [status, overview] = await Promise.all([
            window.quotaGuard.status(),
            window.quotaGuard.overview()
        ]);
        setConnection(status.state === "online" ? "本地服务正常运行" : "本地服务状态未知", "is-online");
        renderOverview(overview);
    } catch {
        setConnection("无法连接本地服务", "is-offline");
        roomName.textContent = "房间尚未连接";
        roomMeta.textContent = "请确认桌面应用已从受管入口启动。";
        lastSync.textContent = "--";
        memberCount.textContent = "--";
        memberSummary.textContent = "等待本地服务";
        authenticatedCount.textContent = "--";
        warningCount.textContent = "--";
        memberUpdated.textContent = "--";
        membersElement.replaceChildren(createMessage("暂时无法读取成员数据。"));
        roomState.classList.add("notice-warning");
        roomStateText.textContent = "本地服务不可用，额度数据没有更新。";
    } finally {
        refreshButton.disabled = false;
    }
}

function renderOverview(overview) {
    if (!overview.configured) {
        roomName.textContent = "未配置共享房间";
        roomMeta.textContent = "创建或加入一个房间后，成员额度会显示在这里。";
        lastSync.textContent = "--";
        memberCount.textContent = "0";
        memberSummary.textContent = "等待成员加入";
        authenticatedCount.textContent = "0";
        warningCount.textContent = String(overview.warnings.length);
        memberUpdated.textContent = "暂无数据";
        membersElement.replaceChildren(createMessage("还没有已验证的成员。"));
        setNotice(overview.warnings);
        return;
    }

    const members = overview.members;
    const authenticated = members.filter((member) => member.snapshot?.authenticated).length;
    roomName.textContent = overview.name || "共享房间";
    roomMeta.textContent = `${members.length} 名已验证成员 · ${overview.roomId}`;
    memberCount.textContent = String(members.length);
    memberSummary.textContent = members.length === 0 ? "等待成员加入" : "已验证成员";
    authenticatedCount.textContent = String(authenticated);
    warningCount.textContent = String(overview.warnings.length);
    memberUpdated.textContent = overview.warnings.length === 0 ? "数据已验证" : "存在待处理 warning";

    const capturedAt = members
        .map((member) => member.snapshot?.capturedAt)
        .filter(Boolean)
        .sort()
        .at(-1);
    lastSync.textContent = capturedAt ? formatDate(capturedAt) : "--";
    membersElement.replaceChildren(...members.map(createMemberCard));
    setNotice(overview.warnings);
}

function createMemberCard(member) {
    const snapshot = member.snapshot;
    const locked = snapshot?.enforcement.locked;
    const stateLabel = locked ? "已锁定" : snapshot?.authenticated ? "已登录" : "未登录";
    const stateClass = locked ? "state-warning" : snapshot?.authenticated ? "" : "state-muted";

    const card = document.createElement("article");
    card.className = "member-card";

    const heading = document.createElement("div");
    heading.className = "member-heading";
    const identity = document.createElement("div");
    identity.className = "member-identity";
    const avatar = document.createElement("span");
    avatar.className = "avatar";
    avatar.setAttribute("aria-hidden", "true");
    avatar.textContent = initials(member.displayName);
    const identityText = document.createElement("div");
    const name = document.createElement("h3");
    name.textContent = member.displayName;
    const memberId = document.createElement("p");
    memberId.className = "member-id";
    memberId.textContent = member.memberId;
    identityText.append(name, memberId);
    identity.append(avatar, identityText);
    const state = document.createElement("span");
    state.className = `member-state ${stateClass}`.trim();
    state.textContent = stateLabel;
    heading.append(identity, state);

    const quotaList = document.createElement("div");
    quotaList.className = "quota-list";
    if (!snapshot || snapshot.buckets.length === 0) {
        quotaList.append(createMessage(snapshot ? "账号尚未返回额度窗口。" : "尚未收到额度快照。"));
    } else {
        snapshot.buckets.forEach((bucket) => quotaList.append(createQuotaBlock(bucket)));
    }

    const footer = document.createElement("div");
    footer.className = "member-footer";
    const managed = document.createElement("span");
    managed.textContent = member.managedLogin ? "受管入口已启用" : "受管入口未启用";
    const updated = document.createElement("span");
    updated.textContent = snapshot?.capturedAt ? `更新于 ${formatDate(snapshot.capturedAt)}` : "暂无更新时间";
    footer.append(managed, updated);

    card.append(heading, quotaList, footer);
    return card;
}

function createQuotaBlock(bucket) {
    const block = document.createElement("section");
    block.className = "quota-block";
    const title = document.createElement("div");
    title.className = "window-heading";
    const label = document.createElement("span");
    label.textContent = bucket.limitName || bucket.limitId;
    const id = document.createElement("strong");
    id.textContent = bucket.limitId;
    title.append(label, id);
    block.append(title);
    if (bucket.primary) block.append(createWindow("主窗口", bucket.primary));
    if (bucket.secondary) block.append(createWindow("次窗口", bucket.secondary));
    return block;
}

function createWindow(labelText, window) {
    const wrapper = document.createElement("div");
    const heading = document.createElement("div");
    heading.className = "window-heading";
    const label = document.createElement("span");
    label.textContent = labelText;
    const percent = document.createElement("strong");
    percent.textContent = `${formatPercent(window.usedPercent)}% 已使用`;
    heading.append(label, percent);
    const meter = document.createElement("div");
    meter.className = "meter";
    meter.setAttribute("role", "progressbar");
    meter.setAttribute("aria-label", `${labelText}已使用百分比`);
    meter.setAttribute("aria-valuemin", "0");
    meter.setAttribute("aria-valuemax", "100");
    meter.setAttribute("aria-valuenow", String(window.usedPercent));
    const fill = document.createElement("div");
    fill.className = `meter-fill ${meterClass(window.usedPercent)}`.trim();
    fill.style.width = `${Math.min(100, Math.max(0, window.usedPercent))}%`;
    meter.append(fill);
    const reset = document.createElement("div");
    reset.className = "window-reset";
    reset.textContent = `将在 ${formatDate(window.resetsAt)} 重置 · 剩余 ${formatPercent(window.remainingPercent)}%`;
    wrapper.append(heading, meter, reset);
    return wrapper;
}

function createMessage(text) {
    const message = document.createElement("div");
    message.className = "quota-empty";
    message.textContent = text;
    return message;
}

function setConnection(text, stateClass) {
    statusElement.textContent = text;
    connectionDot.className = `connection-dot ${stateClass}`.trim();
}

function setNotice(warnings) {
    roomState.classList.toggle("notice-warning", warnings.length > 0);
    roomStateText.textContent = warnings.length > 0
        ? `${warnings.length} 个共享文件未通过验证，已从统计中排除。`
        : "所有成员资料和额度快照均已通过签名验证。";
}

function initials(value) {
    return Array.from(value.trim()).slice(0, 2).join("").toUpperCase() || "?";
}

function formatPercent(value) {
    return Number(value).toFixed(1).replace(/\.0$/, "");
}

function meterClass(value) {
    if (value >= 90) return "meter-full";
    if (value >= 70) return "meter-high";
    return "";
}

function formatDate(value) {
    const date = new Date(value);
    if (Number.isNaN(date.getTime())) return "时间未知";
    return new Intl.DateTimeFormat("zh-CN", {
        month: "numeric",
        day: "numeric",
        hour: "2-digit",
        minute: "2-digit"
    }).format(date);
}
