const RENDERER_PROTOCOL = "app:";
const RENDERER_HOST = "quota-guard";

export function isTrustedRendererUrl(value) {
    try {
        const url = new URL(value);
        return url.protocol === RENDERER_PROTOCOL && url.hostname === RENDERER_HOST;
    } catch {
        return false;
    }
}
