package com.pingche.web.proxy;

import com.pingche.web.auth.DomainException;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@Component
public class ProxyNodeParser {
    static final int MAX_SUBSCRIPTION_BYTES = 1_048_576;
    static final int MAX_NODES = 128;
    private static final Set<String> SS_METHODS = Set.of("aes-128-gcm", "aes-192-gcm", "aes-256-gcm",
            "chacha20-ietf-poly1305", "xchacha20-ietf-poly1305", "2022-blake3-aes-128-gcm",
            "2022-blake3-aes-256-gcm", "2022-blake3-chacha20-poly1305");
    private static final Set<String> VMESS_FIELDS = Set.of("v", "ps", "add", "port", "id", "aid", "scy",
            "net", "type", "host", "path", "tls", "sni", "alpn", "fp");
    private static final Set<String> V2_QUERY = Set.of("encryption", "security", "type", "sni", "host", "path",
            "serviceName", "alpn", "fp", "pbk", "sid", "flow");
    private final ObjectMapper json;

    public ProxyNodeParser(ObjectMapper json) { this.json = json; }

    ProxyNodeDefinition parse(String input) {
        try {
            if (input == null || input.length() > 16_384) throw invalid();
            String value = input.trim();
            if (value.isEmpty() || value.chars().anyMatch(c -> c <= 32 || c == 127)) throw invalid();
            if (value.startsWith("vmess://")) return vmess(value.substring(8));
            URI uri = URI.create(value);
            String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
            if (!Set.of("http", "https", "socks5", "vless", "trojan", "ss").contains(scheme)) throw invalid();
            String host = normalizeHost(uri.getHost());
            int port = uri.getPort();
            if (port == -1) port = scheme.equals("http") ? 80 : scheme.equals("socks5") ? 1080 : 443;
            if (port < 1 || port > 65535) throw invalid();
            if (uri.getRawPath() != null && !uri.getRawPath().isEmpty() && !uri.getRawPath().equals("/")) throw invalid();
            String label = label(uri.getRawFragment() == null ? scheme.toUpperCase(Locale.ROOT) + " " + host : decode(uri.getRawFragment()));
            Map<String, String> query = query(uri.getRawQuery());
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("type", scheme.equals("https") ? "http" : scheme.equals("socks5") ? "socks" : scheme.equals("ss") ? "shadowsocks" : scheme);
            out.put("server", host); out.put("server_port", port);
            String user = uri.getRawUserInfo();
            boolean credentials = false;
            if (Set.of("http", "https", "socks5").contains(scheme)) {
                if (!query.isEmpty()) throw invalid();
                if (user != null) {
                    String[] auth = decode(user).split(":", 2);
                    if (auth.length != 2) throw invalid();
                    out.put("username", text(auth[0], 255, false)); out.put("password", text(auth[1], 4096, false));
                    credentials = true;
                }
                if (scheme.equals("https")) out.put("tls", tls("tls", host, Map.of()));
                if (scheme.equals("socks5")) { out.put("version", "5"); out.put("network", "tcp"); }
            } else if (scheme.equals("ss")) {
                if (!query.isEmpty() || user == null) throw invalid();
                String auth = decode(user);
                if (!auth.contains(":")) auth = decodeBase64(auth);
                String[] parts = auth.split(":", 2);
                if (parts.length != 2 || !SS_METHODS.contains(parts[0])) throw invalid();
                String password = text(parts[1], 4096, false);
                if (parts[0].startsWith("2022-")) {
                    byte[] key = Base64.getDecoder().decode(password);
                    if (key.length != (parts[0].equals("2022-blake3-aes-128-gcm") ? 16 : 32)) throw invalid();
                }
                out.put("method", parts[0]); out.put("password", password); out.put("network", "tcp"); credentials = true;
            } else {
                if (user == null || !V2_QUERY.containsAll(query.keySet())) throw invalid();
                boolean vless = scheme.equals("vless");
                if (vless) {
                    out.put("uuid", uuid(decode(user)));
                    if (query.containsKey("encryption") && !query.get("encryption").equals("none")) throw invalid();
                } else {
                    out.put("password", text(decode(user), 4096, false));
                    if (query.containsKey("encryption") || query.containsKey("flow")) throw invalid();
                }
                String security = query.getOrDefault("security", vless ? "none" : "tls");
                if (!vless && !security.equals("tls")) throw invalid();
                String transport = query.getOrDefault("type", "tcp");
                String flow = query.getOrDefault("flow", "");
                if (!flow.isEmpty()) {
                    if (!flow.equals("xtls-rprx-vision") || !transport.equals("tcp") || security.equals("none")) throw invalid();
                    out.put("flow", flow);
                }
                if (!security.equals("none")) out.put("tls", tls(security, host, query));
                else if (query.keySet().stream().anyMatch(Set.of("sni", "alpn", "fp", "pbk", "sid")::contains)) throw invalid();
                configureTransport(out, transport, query);
                out.put("network", "tcp"); credentials = true;
            }
            return new ProxyNodeDefinition(label, scheme.equals("ss") ? "SHADOWSOCKS" : scheme.toUpperCase(Locale.ROOT),
                    host, port, json.writeValueAsString(out), credentials);
        } catch (RuntimeException ignored) { throw invalid(); }
    }

    List<ProxyNodeDefinition> parseSubscription(String input) {
        if (input == null || input.getBytes(StandardCharsets.UTF_8).length > MAX_SUBSCRIPTION_BYTES) throw invalid();
        String value = input.strip();
        if (value.startsWith("{") || value.startsWith("[") || value.startsWith("proxies:") || value.startsWith("---")) throw unsupportedFormat();
        if (!value.contains("://")) {
            try { value = decodeBase64(value.replaceAll("\\s", "")); }
            catch (RuntimeException ignored) { throw invalid(); }
        }
        List<ProxyNodeDefinition> nodes = new ArrayList<>();
        for (String line : value.split("\\R")) {
            if (line.isBlank()) continue;
            if (nodes.size() == MAX_NODES) throw invalid();
            nodes.add(parse(line));
        }
        if (nodes.isEmpty()) throw invalid();
        return List.copyOf(nodes);
    }

    private ProxyNodeDefinition vmess(String encoded) {
        JsonNode node = json.readTree(decodeBase64(encoded));
        if (node == null || !node.isObject() || !VMESS_FIELDS.containsAll(node.propertyNames())) throw invalid();
        Map<String, String> fields = new LinkedHashMap<>();
        for (String name : node.propertyNames()) {
            JsonNode field = node.get(name);
            if (!field.isString() && !field.isIntegralNumber()) throw invalid();
            fields.put(name, field.asString());
        }
        if (!fields.getOrDefault("v", "2").equals("2") || !fields.getOrDefault("aid", "0").equals("0")
                || !fields.getOrDefault("type", "none").equals("none")) throw invalid();
        String host = normalizeHost(fields.get("add"));
        int port = Integer.parseInt(fields.getOrDefault("port", "0"));
        if (port < 1 || port > 65535) throw invalid();
        String security = fields.getOrDefault("scy", "auto");
        if (!Set.of("auto", "aes-128-gcm", "chacha20-poly1305").contains(security)) throw invalid();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("type", "vmess"); out.put("server", host); out.put("server_port", port);
        out.put("uuid", uuid(fields.get("id"))); out.put("security", security); out.put("alter_id", 0); out.put("network", "tcp");
        String tlsMode = fields.getOrDefault("tls", "");
        if (tlsMode.equals("tls")) out.put("tls", tls("tls", host, fields));
        else if (!tlsMode.isEmpty() && !tlsMode.equals("none")) throw invalid();
        else if (Set.of("sni", "alpn", "fp").stream().anyMatch(key -> !fields.getOrDefault(key, "").isEmpty())) throw invalid();
        String transport = fields.getOrDefault("net", "tcp");
        if (transport.equals("grpc")) fields.put("serviceName", fields.getOrDefault("path", ""));
        configureTransport(out, transport, fields);
        return new ProxyNodeDefinition(label(fields.getOrDefault("ps", "VMESS " + host)), "VMESS", host, port,
                json.writeValueAsString(out), true);
    }

    private Map<String, Object> tls(String mode, String host, Map<String, String> fields) {
        if (!Set.of("tls", "reality").contains(mode)) throw invalid();
        Map<String, Object> tls = new LinkedHashMap<>();
        tls.put("enabled", true); tls.put("insecure", false);
        tls.put("server_name", normalizeHost(fields.getOrDefault("sni", "").isEmpty() ? host : fields.get("sni")));
        if (!fields.getOrDefault("alpn", "").isEmpty()) {
            List<String> alpn = List.of(fields.get("alpn").split(",", -1));
            if (alpn.size() > 4 || alpn.stream().anyMatch(value -> !Set.of("h2", "http/1.1").contains(value))) throw invalid();
            tls.put("alpn", alpn);
        }
        String fingerprint = fields.getOrDefault("fp", "");
        if (!fingerprint.isEmpty()) {
            if (!Set.of("chrome", "firefox", "edge", "safari", "ios", "android", "random", "randomized").contains(fingerprint)) throw invalid();
            tls.put("utls", Map.of("enabled", true, "fingerprint", fingerprint));
        }
        if (mode.equals("reality")) {
            String publicKey = fields.getOrDefault("pbk", "");
            String shortId = fields.getOrDefault("sid", "");
            if (Base64.getUrlDecoder().decode(publicKey).length != 32 || !shortId.matches("(?:[0-9a-fA-F]{2}){0,8}")
                    || fields.getOrDefault("sni", "").isBlank()) throw invalid();
            tls.put("reality", Map.of("enabled", true, "public_key", publicKey, "short_id", shortId));
            if (fingerprint.isEmpty()) tls.put("utls", Map.of("enabled", true, "fingerprint", "chrome"));
        } else if (fields.containsKey("pbk") || fields.containsKey("sid")) throw invalid();
        return tls;
    }

    private void configureTransport(Map<String, Object> out, String type, Map<String, String> fields) {
        String host = fields.getOrDefault("host", "");
        String path = fields.getOrDefault("path", "");
        String service = fields.getOrDefault("serviceName", "");
        if (type.equals("tcp")) {
            if (!host.isEmpty() || !path.isEmpty() || !service.isEmpty()) throw invalid();
        } else if (type.equals("ws")) {
            if (!service.isEmpty()) throw invalid();
            Map<String, Object> transport = new LinkedHashMap<>();
            transport.put("type", "ws");
            String resolvedPath = path.isEmpty() ? "/" : text(path, 2048, false);
            if (!resolvedPath.startsWith("/")) throw invalid();
            transport.put("path", resolvedPath);
            if (!host.isEmpty()) transport.put("headers", Map.of("Host", normalizeHost(host)));
            out.put("transport", transport);
        } else if (type.equals("grpc")) {
            if (!host.isEmpty() || (!path.isEmpty() && !path.equals(service))) throw invalid();
            out.put("transport", Map.of("type", "grpc", "service_name", text(service, 256, false)));
        } else throw invalid();
    }

    private Map<String, String> query(String raw) {
        Map<String, String> result = new LinkedHashMap<>();
        if (raw == null || raw.isEmpty()) return result;
        for (String pair : raw.split("&", -1)) {
            String[] values = pair.split("=", 2);
            if (values.length != 2 || result.putIfAbsent(decode(values[0]), decode(values[1])) != null) throw invalid();
        }
        return result;
    }

    static String normalizeHost(String value) {
        if (value == null) throw invalid();
        String host = value.toLowerCase(Locale.ROOT);
        if (host.startsWith("[") && host.endsWith("]")) host = host.substring(1, host.length() - 1);
        if (host.length() > 253 || host.isBlank()) throw invalid();
        if (host.contains(":")) {
            if (!host.matches("[0-9a-f:]+") || host.chars().filter(c -> c == ':').count() < 2) throw invalid();
            return host;
        }
        for (String part : host.split("\\.", -1)) {
            if (!part.matches("[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?")) throw invalid();
        }
        return host;
    }

    static String label(String value) { return text(value == null ? "" : value.trim(), 120, false); }
    private static String uuid(String value) {
        if (value == null || !UUID.fromString(value).toString().equalsIgnoreCase(value)) throw invalid();
        return value.toLowerCase(Locale.ROOT);
    }
    private static String text(String value, int limit, boolean allowEmpty) {
        if (value == null || value.length() > limit || (!allowEmpty && value.isEmpty())
                || value.chars().anyMatch(c -> c < 32 || c == 127)) throw invalid();
        return value;
    }
    private static String decode(String value) { return URLDecoder.decode(value.replace("+", "%2B"), StandardCharsets.UTF_8); }
    private static String decodeBase64(String value) {
        try {
            byte[] bytes;
            try { bytes = Base64.getDecoder().decode(value); }
            catch (IllegalArgumentException ignored) { bytes = Base64.getUrlDecoder().decode(value); }
            return StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString();
        } catch (CharacterCodingException ignored) { throw invalid(); }
    }
    private static DomainException invalid() {
        return new DomainException("PROXY_NODE_INVALID", "代理链接无效或包含不支持的参数；请使用受支持的协议变体。", HttpStatus.BAD_REQUEST);
    }
    private static DomainException unsupportedFormat() {
        return new DomainException("PROXY_SUBSCRIPTION_FORMAT_UNSUPPORTED", "目前仅支持 URI 行或其 Base64 订阅，不支持 Clash YAML 或 sing-box JSON 配置。", HttpStatus.BAD_REQUEST);
    }
}
