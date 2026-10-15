# Browser sandbox profile

../chromium-seccomp.json is the unmodified Chromium sandbox seccomp profile from Microsoft Playwright v1.64.0 (Apache-2.0).

Source: https://github.com/microsoft/playwright/blob/v1.64.0/utils/docker/seccomp_profile.json
Git blob: fddc05fb520affb145404e6f6f647ca96af8087d
License: playwright-LICENSE

It preserves a default-deny syscall policy and permits Chromium's user-namespace sandbox. It is used only by compose.web-login.yaml; the base deployment keeps Docker's default seccomp. No browser or DevTools port is exposed. Kernel/container compatibility still needs deployment-specific verification.
