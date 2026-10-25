package com.pingche.web.officialsession;

import com.pingche.web.auth.DomainException;
import com.pingche.web.quota.OfficialQuotaSnapshotRequest.Window;
import com.pingche.web.quota.OfficialQuotaSnapshotResponse;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.springframework.http.HttpStatus;
import tools.jackson.databind.JsonNode;

/** Matches OpenAI Codex backend-client's /wham/usage contract. Never retains the raw body. */
final class OfficialQuotaPayload {
    private OfficialQuotaPayload() { }

    static OfficialQuotaSnapshotResponse parse(JsonNode root, String accountId, String userId, Instant now) {
        if (root == null || !root.isObject()) throw invalid();
        checkIdentity(root, "account_id", accountId);
        checkIdentity(root, "user_id", userId);
        JsonNode plan = root.get("plan_type");
        if (plan == null || !plan.isString() || plan.asString().isBlank() || plan.asString().length() > 40) throw invalid();
        List<Window> windows = new ArrayList<>();
        addWindows(root.get("rate_limit"), "codex", windows);
        JsonNode additional = root.get("additional_rate_limits");
        Set<String> ids = new HashSet<>(Set.of("codex"));
        if (additional != null && !additional.isNull()) {
            if (!additional.isArray() || additional.size() > 7) throw invalid();
            for (JsonNode item : additional) {
                JsonNode id = item.get("metered_feature");
                if (id == null || !id.isString() || !id.asString().matches("[A-Za-z0-9_.:-]{1,80}") || !ids.add(id.asString())) throw invalid();
                addWindows(item.get("rate_limit"), id.asString(), windows);
            }
        }
        // An official plan may legitimately have no percentage windows. Missing fields are never zeroed.
        if (!root.has("rate_limit")) throw invalid();
        return new OfficialQuotaSnapshotResponse(plan.asString(), List.copyOf(windows), now, now);
    }

    private static void checkIdentity(JsonNode root, String field, String expected) {
        JsonNode value = root.get(field);
        if (value != null && !value.isNull() && (!value.isString() || !value.asString().equals(expected)))
            throw new DomainException("OFFICIAL_ACCOUNT_MISMATCH", "官方额度账户身份不匹配。", HttpStatus.BAD_GATEWAY);
    }

    private static void addWindows(JsonNode limit, String id, List<Window> windows) {
        if (limit == null || limit.isNull()) return;
        if (!limit.isObject()) throw invalid();
        for (String slot : List.of("primary", "secondary")) {
            JsonNode window = limit.get(slot + "_window");
            if (window == null || window.isNull()) continue;
            JsonNode percent = window.get("used_percent");
            JsonNode seconds = window.get("limit_window_seconds");
            JsonNode reset = window.get("reset_at");
            if (!window.isObject() || percent == null || !percent.isNumber() || seconds == null || !seconds.isIntegralNumber()
                    || !seconds.canConvertToLong() || seconds.asLong() < 1 || seconds.asLong() > 31_536_000) throw invalid();
            BigDecimal used = percent.decimalValue();
            if (used.signum() < 0 || used.compareTo(BigDecimal.valueOf(100)) > 0) throw invalid();
            Instant resetsAt = null;
            if (reset != null && !reset.isNull()) {
                if (!reset.isIntegralNumber() || !reset.canConvertToLong() || reset.asLong() < 0 || reset.asLong() >= 253_402_300_800L) throw invalid();
                resetsAt = Instant.ofEpochSecond(reset.asLong());
            }
            windows.add(new Window(id, slot, (seconds.asLong() + 59) / 60, used, resetsAt));
        }
    }

    private static DomainException invalid() {
        return new DomainException("OFFICIAL_RESPONSE_INVALID", "官方额度响应格式无法识别，保留上次成功结果。", HttpStatus.BAD_GATEWAY);
    }
}
