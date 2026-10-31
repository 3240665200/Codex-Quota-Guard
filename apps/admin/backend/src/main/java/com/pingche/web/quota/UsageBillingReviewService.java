package com.pingche.web.quota;

import com.pingche.web.auth.DomainException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.Timestamp;
import java.time.Clock;
import java.util.*;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.annotation.Isolation;
import tools.jackson.databind.ObjectMapper;
import static com.pingche.web.quota.UsageBillingReview.*;

@Service
public class UsageBillingReviewService {
    private record State(Facts original,Facts effective,UsageBillingService.Entry billing,long revision,
            String pricingMode,Prices customPrice,Prices priceSnapshot) { }
    private record Candidate(String fingerprint,Facts facts,String pricingMode,Prices prices,String note,
            String status,String cost,String multiplier) { }
    private final JdbcTemplate jdbc;
    private final CodexUsageEventRepository events;
    private final UsageModelPriceRepository prices;
    private final UsageBillingOverrides overrides;
    private final UsageBillingService billing;
    private final ObjectMapper json;
    private final Clock clock;

    public UsageBillingReviewService(JdbcTemplate jdbc,CodexUsageEventRepository events,UsageModelPriceRepository prices,
            UsageBillingOverrides overrides,UsageBillingService billing,ObjectMapper json,Clock clock) {
        this.jdbc=jdbc;this.events=events;this.prices=prices;this.overrides=overrides;this.billing=billing;this.json=json;this.clock=clock;
    }

    @Transactional(readOnly=true,isolation=Isolation.REPEATABLE_READ)
    public Detail detail(String id) {
        var original=event(id); var effective=overrides.effective(original); var stored=overrides.stored(id);
        var entry=entry(id); var state=state(original,effective,entry,stored);
        var account=jdbc.queryForObject("select official_account_id from usage_billing_entries where event_id=?",UUID.class,id);
        var history=jdbc.query("select a.*,u.display_name as administrator_name from usage_billing_review_audit a "
                +"left join users u on u.id=a.reviewed_by where a.event_id=? order by a.revision desc limit 20",(rs,n) -> new Audit(
                        rs.getObject("id",UUID.class),rs.getLong("revision"),rs.getObject("reviewed_by",UUID.class),rs.getString("administrator_name"),
                        rs.getTimestamp("reviewed_at").toInstant(),rs.getString("review_note"),
                        json.readTree(rs.getString("before_state")),json.readTree(rs.getString("after_state"))),id);
        return new Detail(id,original.getUserId(),original.getTeamId(),account,original.getOccurredAt(),original.getReceivedAt(),
                original.getSource(),state.original(),state.effective(),stored.revision(),hash(state),entry.costUsd()==null,
                stored.pricingMode(),stored.customPrice(),Prices.from(catalog(effective.getModel())),priceSnapshot(id),money(entry.multiplier()),entry.costStatus(),
                money(entry.costUsd()),entry.modeStatus(),history);
    }

    @Transactional(readOnly=true,isolation=Isolation.REPEATABLE_READ)
    public Preview preview(String id,Request request) { return calculate(id,request); }

    @Transactional
    public Saved save(String id,Request request,UUID administrator) {
        var locked=jdbc.queryForList("select event_id from usage_billing_entries where event_id=? for update",id);
        if(locked.isEmpty()) throw missing();
        Preview preview=calculate(id,request);
        if(request.previewToken()==null || !request.previewToken().equals(preview.previewToken())) throw conflict();
        var original=event(id); var stored=overrides.stored(id);
        String before=json.writeValueAsString(state(original,overrides.effective(original),entry(id),stored));
        var now=clock.instant(); long revision=stored.revision()+1;
        overrides.save(id,revision,request,administrator,now);
        billing.recalculateReviewed(id,request.facts().serviceTier(),administrator);
        var updated=entry(id);
        if(!Objects.equals(preview.costUsd(),money(updated.costUsd())) || !preview.costStatus().equals(updated.costStatus()))
            throw new IllegalStateException("Reviewed billing calculation differed from the approved preview");
        String after=json.writeValueAsString(state(original,overrides.effective(original),updated,overrides.stored(id)));
        jdbc.update("insert into usage_billing_review_audit(id,event_id,revision,reviewed_at,reviewed_by,review_note,before_state,after_state) values(?,?,?,?,?,?,?,?)",
                UUID.randomUUID(),id,revision,Timestamp.from(now),administrator,request.note().trim(),before,after);
        return new Saved(detail(id),preview);
    }

    private Preview calculate(String id,Request request) {
        var original=event(id); var stored=overrides.stored(id); var entry=entry(id);
        if(entry.costUsd()!=null) throw new DomainException("BILLING_REVIEW_ALREADY_PRICED","记录已入账，不能改写已核算金额。",HttpStatus.CONFLICT);
        if(stored.revision()!=request.revision() || !hash(state(original,overrides.effective(original),entry,stored)).equals(request.fingerprint())) throw conflict();
        if("CUSTOM".equals(request.pricingMode()) != (request.customPrice()!=null))
            throw invalid("自定义单价须完整填写；使用价格表时请勿同时提交自定义价格。");
        Facts f=request.facts(); validateCounts(f);
        var price="CUSTOM".equals(request.pricingMode()) ? request.customPrice().modelPrice(f.model()) : catalog(f.model());
        var estimate=UsagePricingCalculator.estimate(f.apply(original),price);
        boolean fast="fast".equals(f.serviceTier());
        boolean known=fast || "standard".equals(f.serviceTier());
        BigDecimal multiplier=fast ? price==null?new BigDecimal("1.5"):price.getFastMultiplier() : BigDecimal.ONE;
        BigDecimal cost=known && estimate.estimatedCostUsd()!=null ? estimate.estimatedCostUsd().multiply(multiplier).setScale(12,RoundingMode.HALF_UP) : null;
        String status="PRICED".equals(estimate.costStatus())&&!known ? "MISSING_SERVICE_TIER" : estimate.costStatus();
        List<String> missing=new ArrayList<>();
        if(f.model()==null) missing.add("model");
        if(price==null) missing.add("price");
        if(f.inputTokens()==null) missing.add("inputTokens");
        if(f.cachedInputTokens()==null) missing.add("cachedInputTokens");
        if(f.cacheWriteInputTokens()==null) missing.add("cacheWriteInputTokens");
        if(f.outputTokens()==null) missing.add("outputTokens");
        if(!known) missing.add("serviceTier");
        var applied=Prices.from(price);
        String token=hash(new Candidate(request.fingerprint(),f,request.pricingMode(),applied,request.note().trim(),status,money(cost),money(multiplier)));
        return new Preview(token,status,money(cost),money(multiplier),applied,List.copyOf(missing));
    }
    private void validateCounts(Facts f) {
        if(f.inputTokens()!=null && f.outputTokens()!=null && f.inputTokens()+f.outputTokens()!=f.totalTokens())
            throw invalid("输入（含缓存）与输出（含思考）之和必须等于总 Token。");
        if(f.inputTokens()!=null && ((f.cachedInputTokens()!=null && f.cachedInputTokens()>f.inputTokens())
                || (f.cacheWriteInputTokens()!=null && f.cacheWriteInputTokens()>f.inputTokens())
                || (f.cachedInputTokens()!=null && f.cacheWriteInputTokens()!=null && f.cachedInputTokens()+f.cacheWriteInputTokens()>f.inputTokens())))
            throw invalid("缓存读与缓存写之和不能超过输入 Token。");
        if(f.outputTokens()!=null && f.reasoningOutputTokens()!=null && f.reasoningOutputTokens()>f.outputTokens())
            throw invalid("思考 Token 不能超过输出 Token。");
    }
    private State state(CodexUsageEvent original,CodexUsageEvent effective,UsageBillingService.Entry entry,UsageBillingOverrides.Stored stored) {
        var facts=Facts.from(effective);
        String tier="FAST".equals(entry.modeStatus())?"fast":"STANDARD".equals(entry.modeStatus())?"standard":"unknown";
        facts=new Facts(facts.model(),facts.reasoningEffort(),facts.totalTokens(),facts.inputTokens(),facts.cachedInputTokens(),
                facts.cacheWriteInputTokens(),facts.outputTokens(),facts.reasoningOutputTokens(),tier);
        return new State(Facts.from(original),facts,entry,stored.revision(),stored.pricingMode(),stored.customPrice(),priceSnapshot(original.getId()));
    }
    private Prices priceSnapshot(String id) {
        var row=jdbc.queryForMap("select input_price,output_price,cache_read_price,cache_write_price,multiplier from usage_billing_entries where event_id=?",id);
        if(row.get("input_price")==null) return null;
        return new Prices((BigDecimal)row.get("input_price"),(BigDecimal)row.get("output_price"),(BigDecimal)row.get("cache_read_price"),
                (BigDecimal)row.get("cache_write_price"),(BigDecimal)row.get("multiplier"));
    }
    private CodexUsageEvent event(String id) { return events.findById(id).orElseThrow(this::missing); }
    private UsageBillingService.Entry entry(String id) {
        var entry=billing.entries(List.of(id)).get(id);if(entry==null) throw missing();return entry;
    }
    private UsageModelPrice catalog(String model) { return model==null ? null : prices.findById(model).orElse(null); }
    private String hash(Object value) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(json.writeValueAsString(value).getBytes(StandardCharsets.UTF_8))); }
        catch(java.security.NoSuchAlgorithmException error) { throw new IllegalStateException(error); }
    }
    private String money(BigDecimal value) { return value==null?null:value.stripTrailingZeros().toPlainString(); }
    private DomainException missing() { return new DomainException("BILLING_REVIEW_NOT_FOUND","计费记录不存在或已删除。",HttpStatus.NOT_FOUND); }
    private DomainException conflict() { return new DomainException("BILLING_REVIEW_CONFLICT","记录、价格或预览已变化，请重新读取并预览后再保存。",HttpStatus.CONFLICT); }
    private DomainException invalid(String message) { return new DomainException("BILLING_REVIEW_INVALID",message,HttpStatus.BAD_REQUEST); }
}
