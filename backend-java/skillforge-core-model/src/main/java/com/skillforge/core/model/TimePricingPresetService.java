package com.skillforge.core.model;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.skillforge.core.billing.pricing.TimePricingPolicy;
import com.skillforge.core.shared.config.SnowflakeIds;
import com.skillforge.core.shared.exception.ApiException;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.transaction.annotation.Transactional;

/** 平台管理员维护的价格规则预设；采用预设只复制已发布内容，不建立实时继承。 */
@Transactional
public class TimePricingPresetService {
    private final TimePricingMapper mapper;
    private final TimePricingRules rules;

    public TimePricingPresetService(TimePricingMapper mapper, TimePricingRules rules) {
        this.mapper = mapper; this.rules = rules;
    }

    public List<View> list() { return views(mapper.listPresets()); }
    public View detail(String id) { return views(List.of(require(id))).getFirst(); }
    public List<View> history(String id) { require(id); return views(mapper.history(id)); }

    public View create(Write command, String actor) {
        validate(command, actor);
        String id = Long.toString(SnowflakeIds.nextId());
        mapper.createPreset(id, command.name().strip(), rules.save(command.policy()), actor);
        return detail(id);
    }

    public View update(String id, Write command, String actor) {
        validate(command, actor);
        var previous = require(id);
        if (command.expectedRevision() == null || command.expectedRevision() != previous.revision()) throw conflict();
        long rule = previous.ruleId();
        if (!rules.read(rule).equals(command.policy())) rule = rules.save(command.policy());
        if (mapper.updatePreset(id, previous.revision(), command.name().strip(), rule, actor) != 1) throw conflict();
        return detail(id);
    }

    public View publish(String id, long expectedRevision, String actor) {
        requireActor(actor);
        var draft = require(id);
        if (draft.revision() != expectedRevision) throw conflict();
        if (!draft.hasUnpublishedChanges()) return detail(id);
        long revision = draft.publishedRevision() == null ? 1 : Math.addExact(draft.publishedRevision(), 1);
        if (mapper.advancePreset(id, expectedRevision, revision, actor) != 1) throw conflict();
        mapper.appendPreset(id, revision, draft.name(), draft.ruleId(), actor);
        return detail(id);
    }

    private List<View> views(List<TimePricingMapper.PresetRow> rows) {
        Map<Long, TimePricingPolicy> policies = rules.readAll(rows.stream().map(TimePricingMapper.PresetRow::ruleId).toList());
        return rows.stream().map(r -> new View(r.id(), r.name(), r.revision(), r.publishedRevision(), r.hasUnpublishedChanges(), policies.get(r.ruleId()))).toList();
    }

    private TimePricingMapper.PresetRow require(String id) {
        if (id == null || !id.matches("[1-9][0-9]{0,18}")) throw new ApiException(HttpStatus.BAD_REQUEST, "time_pricing_preset_reference_invalid");
        try { Long.parseLong(id); }
        catch (NumberFormatException invalid) { throw new ApiException(HttpStatus.BAD_REQUEST, "time_pricing_preset_reference_invalid"); }
        var row = mapper.preset(id);
        if (row == null) throw new ApiException(HttpStatus.NOT_FOUND, "time_pricing_preset_not_found");
        return row;
    }

    private static void validate(Write command, String actor) {
        requireActor(actor);
        if (command == null || command.name() == null || command.name().isBlank()
                || command.name().strip().codePointCount(0, command.name().strip().length()) > 128 || command.policy() == null)
            throw new ApiException(HttpStatus.BAD_REQUEST, "time_pricing_preset_invalid");
    }
    private static void requireActor(String actor) {
        if (actor == null || actor.isBlank()) throw new ApiException(HttpStatus.UNAUTHORIZED, "platform_actor_required");
    }
    private static ApiException conflict() { return new ApiException(HttpStatus.CONFLICT, "time_pricing_preset_revision_conflict"); }

    public record Write(@JsonProperty("expected_revision") Long expectedRevision, String name, TimePricingPolicy policy) { }
    public record View(String id, String name, long revision, @JsonProperty("published_revision") Long publishedRevision,
            @JsonProperty("has_unpublished_changes") boolean hasUnpublishedChanges, TimePricingPolicy policy) { }
}
