package com.pingche.web.proxy;

import com.pingche.web.auth.DomainException;
import java.io.StringReader;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.http.HttpStatus;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;
import org.yaml.snakeyaml.events.AliasEvent;
import org.yaml.snakeyaml.events.CollectionStartEvent;
import org.yaml.snakeyaml.events.ScalarEvent;
import tools.jackson.databind.ObjectMapper;

/** Reads only data from the top-level proxies list. Global routing/providers are never imported or evaluated. */
final class ProxyClashParser {
    private static final Set<String> FIELDS = Set.of("name", "type", "server", "port", "username", "password", "tls",
            "skip-cert-verify", "sni", "servername", "alpn", "client-fingerprint", "network", "ws-opts", "grpc-opts",
            "uuid", "alterId", "cipher", "flow", "reality-opts", "udp");
    private final ProxyNodeParser parser;
    private final ObjectMapper json;
    ProxyClashParser(ProxyNodeParser parser, ObjectMapper json) { this.parser = parser; this.json = json; }

    List<ProxyNodeDefinition> parse(String value) {
        try {
            LoaderOptions options = new LoaderOptions();
            options.setAllowDuplicateKeys(false);
            options.setAllowRecursiveKeys(false);
            options.setMaxAliasesForCollections(0);
            options.setNestingDepthLimit(24);
            options.setCodePointLimit(ProxyNodeParser.MAX_SUBSCRIPTION_BYTES);
            Yaml yaml = new Yaml(new SafeConstructor(options));
            for (var event : yaml.parse(new StringReader(value))) {
                if (event instanceof AliasEvent || event instanceof ScalarEvent scalar && scalar.getTag() != null
                        || event instanceof CollectionStartEvent collection && collection.getTag() != null) throw invalid();
            }
            Map<String, Object> root = object(yaml.load(value));
            if (root.containsKey("script") || root.containsKey("scripts")) throw invalid();
            Object raw = root.get("proxies");
            if (!(raw instanceof List<?> proxies) || proxies.isEmpty() || proxies.size() > ProxyNodeParser.MAX_NODES) throw invalid();
            List<ProxyNodeDefinition> definitions = new ArrayList<>();
            for (Object proxy : proxies) definitions.add(proxy(object(proxy)));
            return List.copyOf(definitions);
        } catch (RuntimeException ignored) { throw invalid(); }
    }

    private ProxyNodeDefinition proxy(Map<String, Object> fields) {
        if (!FIELDS.containsAll(fields.keySet())) throw invalid();
        String name = ProxyNodeParser.label(string(fields, "name", null));
        String type = string(fields, "type", null);
        String host = ProxyNodeParser.normalizeHost(string(fields, "server", null));
        int port = integer(fields, "port", 0);
        if (port < 1 || port > 65535 || bool(fields, "skip-cert-verify", false)) throw invalid();
        if (fields.containsKey("udp")) bool(fields, "udp", false);
        if (!Set.of("http", "https", "socks5", "ss", "vmess", "vless", "trojan").contains(type)) throw invalid();
        Map<String, String> query = transport(fields);
        String servername = string(fields, "servername", string(fields, "sni", ""));
        if (fields.containsKey("sni") && fields.containsKey("servername") && !servername.equals(string(fields, "sni", ""))) throw invalid();
        boolean tls = bool(fields, "tls", type.equals("https") || type.equals("trojan"));
        if (!tls && (!servername.isEmpty() || fields.containsKey("alpn") || fields.containsKey("client-fingerprint") || fields.containsKey("reality-opts"))) throw invalid();
        if (!servername.isEmpty()) query.put("sni", servername);
        if (fields.containsKey("client-fingerprint")) query.put("fp", string(fields, "client-fingerprint", null));
        if (fields.containsKey("alpn")) {
            if (!(fields.get("alpn") instanceof List<?> protocols) || protocols.isEmpty()) throw invalid();
            List<String> names = new ArrayList<>();
            for (Object protocol : protocols) { if (!(protocol instanceof String text)) throw invalid(); names.add(text); }
            query.put("alpn", String.join(",", names));
        }
        String authority = (host.contains(":") ? "[" + host + "]" : host) + ":" + port;
        if (Set.of("http", "https", "socks5").contains(type)) {
            restrict(fields, Set.of("name", "type", "server", "port", "username", "password", "tls", "skip-cert-verify", "udp"));
            if (type.equals("socks5") && tls || type.equals("https") && !tls) throw invalid();
            String auth = credentials(fields);
            return parser.parse((type.equals("http") && tls ? "https" : type) + "://" + auth + authority + "#" + encode(name));
        }
        if (type.equals("ss")) {
            restrict(fields, Set.of("name", "type", "server", "port", "cipher", "password", "udp"));
            return parser.parse("ss://" + encode(string(fields, "cipher", null)) + ":" + encode(string(fields, "password", null))
                    + "@" + authority + "#" + encode(name));
        }
        if (type.equals("vmess")) {
            if (fields.containsKey("reality-opts") || fields.containsKey("flow") || fields.containsKey("password") || fields.containsKey("username")) throw invalid();
            Map<String, Object> vmess = new LinkedHashMap<>();
            vmess.put("v", "2"); vmess.put("ps", name); vmess.put("add", host); vmess.put("port", port);
            vmess.put("id", string(fields, "uuid", null)); vmess.put("aid", integer(fields, "alterId", 0));
            vmess.put("scy", string(fields, "cipher", "auto")); vmess.put("net", query.get("type"));
            vmess.put("tls", tls ? "tls" : "none");
            for (String key : List.of("sni", "fp", "alpn", "host", "path")) if (query.containsKey(key)) vmess.put(key, query.get(key));
            if (query.containsKey("serviceName")) vmess.put("path", query.get("serviceName"));
            return parser.parse("vmess://" + java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(json.writeValueAsBytes(vmess)));
        }
        if (fields.containsKey("username") || fields.containsKey("alterId") || fields.containsKey("cipher")) throw invalid();
        if (type.equals("trojan") && (!tls || fields.containsKey("uuid") || fields.containsKey("flow") || fields.containsKey("reality-opts"))) throw invalid();
        if (type.equals("vless") && fields.containsKey("password")) throw invalid();
        query.put("security", tls ? "tls" : "none");
        if (fields.containsKey("reality-opts")) {
            Map<String, Object> reality = object(fields.get("reality-opts"));
            restrict(reality, Set.of("public-key", "short-id"));
            query.put("security", "reality"); query.put("pbk", string(reality, "public-key", null));
            query.put("sid", string(reality, "short-id", ""));
        }
        if (fields.containsKey("flow")) query.put("flow", string(fields, "flow", null));
        String user = string(fields, type.equals("vless") ? "uuid" : "password", null);
        StringBuilder uri = new StringBuilder(type + "://" + encode(user) + "@" + authority + "?");
        query.forEach((key, value) -> uri.append(encode(key)).append('=').append(encode(value)).append('&'));
        uri.setLength(uri.length() - 1);
        return parser.parse(uri + "#" + encode(name));
    }

    private Map<String, String> transport(Map<String, Object> fields) {
        Map<String, String> query = new LinkedHashMap<>();
        String network = string(fields, "network", "tcp");
        query.put("type", network);
        if (fields.containsKey("ws-opts")) {
            if (!network.equals("ws")) throw invalid();
            Map<String, Object> ws = object(fields.get("ws-opts")); restrict(ws, Set.of("path", "headers"));
            query.put("path", string(ws, "path", "/"));
            if (ws.containsKey("headers")) {
                Map<String, Object> headers = object(ws.get("headers")); restrict(headers, Set.of("Host"));
                if (headers.containsKey("Host")) query.put("host", string(headers, "Host", null));
            }
        }
        if (fields.containsKey("grpc-opts")) {
            if (!network.equals("grpc")) throw invalid();
            Map<String, Object> grpc = object(fields.get("grpc-opts")); restrict(grpc, Set.of("grpc-service-name"));
            query.put("serviceName", string(grpc, "grpc-service-name", null));
        }
        return query;
    }
    private String credentials(Map<String, Object> fields) {
        if (!fields.containsKey("username") && !fields.containsKey("password")) return "";
        return encode(string(fields, "username", null)) + ":" + encode(string(fields, "password", null)) + "@";
    }
    private static Map<String, Object> object(Object value) {
        if (!(value instanceof Map<?, ?> map)) throw invalid();
        Map<String, Object> result = new LinkedHashMap<>();
        map.forEach((key, field) -> { if (!(key instanceof String name)) throw invalid(); result.put(name, field); });
        return result;
    }
    private static void restrict(Map<String, Object> fields, Set<String> allowed) { if (!allowed.containsAll(fields.keySet())) throw invalid(); }
    private static String string(Map<String, Object> fields, String key, String fallback) {
        Object value = fields.get(key);
        if (value == null && !fields.containsKey(key)) { if (fallback == null) throw invalid(); return fallback; }
        if (!(value instanceof String text)) throw invalid();
        return text;
    }
    private static int integer(Map<String, Object> fields, String key, int fallback) {
        Object value = fields.get(key);
        if (value == null && !fields.containsKey(key)) return fallback;
        if (value instanceof Integer number) return number;
        if (value instanceof String text && text.matches("[0-9]{1,5}")) return Integer.parseInt(text);
        throw invalid();
    }
    private static boolean bool(Map<String, Object> fields, String key, boolean fallback) {
        Object value = fields.get(key);
        if (value == null && !fields.containsKey(key)) return fallback;
        if (value instanceof Boolean bool) return bool;
        throw invalid();
    }
    private static String encode(String value) { return URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20"); }
    private static DomainException invalid() {
        return new DomainException("PROXY_SUBSCRIPTION_FORMAT_UNSUPPORTED", "订阅格式或节点参数不受支持；支持 URI/Base64 和 Clash/Mihomo proxies 列表中的受支持协议，且必须验证 TLS 证书。", HttpStatus.BAD_REQUEST);
    }
}
