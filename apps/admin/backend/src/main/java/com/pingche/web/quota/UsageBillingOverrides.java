package com.pingche.web.quota;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import static com.pingche.web.quota.UsageBillingReview.*;

/** One current, revisioned correction; never writes to the device report. */
@Component
public class UsageBillingOverrides {
    record Stored(long revision,String pricingMode,Prices customPrice) { }
    private final JdbcTemplate jdbc;
    public UsageBillingOverrides(JdbcTemplate jdbc) { this.jdbc=jdbc; }

    Stored stored(String id) {
        return jdbc.query("select * from usage_billing_overrides where event_id=?",(rs,n) -> new Stored(rs.getLong("revision"),
                rs.getString("pricing_mode"),"CUSTOM".equals(rs.getString("pricing_mode")) ? price(rs) : null),id)
                .stream().findFirst().orElse(new Stored(0,"CATALOG",null));
    }
    static Prices price(ResultSet rs) throws SQLException {
        return new Prices(rs.getBigDecimal("input_price"),rs.getBigDecimal("output_price"),rs.getBigDecimal("cache_read_price"),
                rs.getBigDecimal("cache_write_price"),rs.getBigDecimal("fast_multiplier"));
    }
    public CodexUsageEvent effective(CodexUsageEvent original) {
        return effective(List.of(original)).get(0);
    }
    public List<CodexUsageEvent> effective(Collection<CodexUsageEvent> originals) {
        if (originals.isEmpty()) return List.of();
        if (originals.size()>1000) throw new IllegalArgumentException("Billing review lookup is bounded to 1000 events");
        String placeholders=String.join(",",Collections.nCopies(originals.size(),"?"));
        Map<String,Facts> facts=new HashMap<>();
        jdbc.query("select * from usage_effective_events where id in ("+placeholders+")",rs -> {
            facts.put(rs.getString("id"),new Facts(rs.getString("model"),rs.getString("reasoning_effort"),rs.getLong("total_tokens"),
                    rs.getObject("input_tokens",Long.class),rs.getObject("cached_input_tokens",Long.class),
                    rs.getObject("cache_write_input_tokens",Long.class),rs.getObject("output_tokens",Long.class),
                    rs.getObject("reasoning_output_tokens",Long.class),rs.getString("service_tier")));
        },originals.stream().map(CodexUsageEvent::getId).toArray());
        return originals.stream().map(event -> facts.containsKey(event.getId()) ? facts.get(event.getId()).apply(event) : event).toList();
    }
    UsageModelPrice customPrice(String id,String model) {
        var stored=stored(id);
        return stored.customPrice()==null ? null : stored.customPrice().modelPrice(model);
    }
    void save(String id,long revision,Request request,UUID administrator,Instant now) {
        Facts f=request.facts(); Prices p="CUSTOM".equals(request.pricingMode()) ? request.customPrice() : null;
        Object[] values={revision,f.model(),f.reasoningEffort(),f.totalTokens(),f.inputTokens(),f.cachedInputTokens(),
                f.cacheWriteInputTokens(),f.outputTokens(),f.reasoningOutputTokens(),request.pricingMode(),
                p==null?null:p.inputPrice(),p==null?null:p.outputPrice(),p==null?null:p.cacheReadPrice(),p==null?null:p.cacheWritePrice(),
                p==null?null:p.fastMultiplier(),Timestamp.from(now),administrator,request.note().trim(),id};
        if (revision==1) jdbc.update("insert into usage_billing_overrides(revision,model,reasoning_effort,total_tokens,input_tokens,cached_input_tokens,"
                +"cache_write_input_tokens,output_tokens,reasoning_output_tokens,pricing_mode,input_price,output_price,cache_read_price,cache_write_price,"
                +"fast_multiplier,reviewed_at,reviewed_by,review_note,event_id) values(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",values);
        else jdbc.update("update usage_billing_overrides set revision=?,model=?,reasoning_effort=?,total_tokens=?,input_tokens=?,cached_input_tokens=?,"
                +"cache_write_input_tokens=?,output_tokens=?,reasoning_output_tokens=?,pricing_mode=?,input_price=?,output_price=?,cache_read_price=?,"
                +"cache_write_price=?,fast_multiplier=?,reviewed_at=?,reviewed_by=?,review_note=? where event_id=?",values);
    }
}
