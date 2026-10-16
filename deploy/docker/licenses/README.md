# 历史浏览器配置许可说明

服务器 0.3.20 已移除 Chromium 运行目标、浏览器 overlay 和 chromium-seccomp.json；当前部署使用 Docker 默认 seccomp，不引用此目录的 Playwright 派生配置。

以下许可与说明仅用于追溯旧版本，旧文件仍可在 server-v0.3.19 等历史标签查看。保留 playwright-LICENSE，不代表当前镜像内含浏览器。

# Browser sandbox profile

../chromium-seccomp.json is derived from the Chromium sandbox seccomp profile from Microsoft Playwright v1.64.0 (Apache-2.0).

Source: https://github.com/microsoft/playwright/blob/v1.64.0/utils/docker/seccomp_profile.json
Git blob: fddc05fb520affb145404e6f6f647ca96af8087d
License: playwright-LICENSE

Local change (2026-10-15): add one independent `chroot` syscall allow rule without a container-capability condition. The upstream rule allows `chroot` only when `CAP_SYS_CHROOT` is present at container creation; `cap_drop: ALL` filters that rule out, preventing Chromium from preparing its sandbox inside a new user namespace.

The syscall permission does not grant `CAP_SYS_CHROOT` to the container. Deployment retains UID 10001, zero effective/bounding capabilities, `no-new-privileges`, a read-only root filesystem, and the original default-deny policy and all other syscall rules. Verification with the deployed browser image loaded a synthetic data page with the sandbox enabled and networking disabled; the ordinary UID 10001 process still received EPERM from `chroot /tmp`, with `CapEff` and `CapBnd` both zero.

The derived profile is used only by the source or registry `compose.web-login.yaml` overlay; the base deployment keeps Docker's default seccomp. No browser or DevTools port is exposed. Kernel/container compatibility still needs deployment-specific verification.
