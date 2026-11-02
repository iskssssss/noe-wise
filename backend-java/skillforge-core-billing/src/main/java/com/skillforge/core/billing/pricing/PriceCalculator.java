package com.skillforge.core.billing.pricing;

import com.skillforge.core.billing.metering.CostType;
import com.skillforge.core.billing.metering.MediaUsage;
import com.skillforge.core.billing.metering.LlmUsage;
import com.skillforge.core.billing.metering.UsageSource;
import com.skillforge.core.billing.pricing.ModelPriceSource.ModelRevisionRef;
import com.skillforge.core.billing.pricing.ModelPriceSource.Snapshot;
import java.math.BigDecimal;
import java.time.Instant;
import org.springframework.stereotype.Component;

/**
 * 写时计价器（LiteLLM 模式）。
 *
 * <p>按事件类型 × 计量值计算金额（单位：分）：
 * <ul>
 *   <li>{@link CostType#LLM_CHAT}：prompt × 输入单价 + completion × 输出单价（每百万 token）</li>
 *   <li>{@link CostType#LLM_EMBEDDING}：输入 token 估算 × 输入单价（每百万 token）</li>
 *   <li>{@link CostType#LLM_RERANK}：按冻结价格明确选择输入 Token 或文档数量计价</li>
 *   <li>{@link CostType#REQUEST}：无金额</li>
 * </ul>
 * 单价来自一次读取的平台模型不可变版本快照；无匹配单价返回 null，金额列留空。
 */
@Component
public class PriceCalculator {

    /** 每百万 token 基数。 */
    private static final BigDecimal PER_MILLION = new BigDecimal("1000000");
    private static final BigDecimal MAXIMUM_PRECISE_AMOUNT = new BigDecimal("999999999999.999999999999");
    private static final BigDecimal MAXIMUM_INTEGER_AMOUNT = BigDecimal.valueOf(Long.MAX_VALUE);

    /** 平台模型不可变版本价格目录。 */
    private final ModelPriceSource pricing;

    /**
     * 构造写时计价器。
     *
     * @param pricing 平台模型价格快照服务
     */
    public PriceCalculator(ModelPriceSource pricing) {
        this.pricing = java.util.Objects.requireNonNull(pricing, "pricing");
    }

    /** 发送前只冻结原模型币种与版本；真实媒体计量尚未报告，不提前计算金额。 */
    public Amounts unpricedMedia(ModelRevisionRef model) {
        Snapshot snapshot = pricing.snapshot(java.util.Objects.requireNonNull(model, "model"));
        return Amounts.ofPrecise(null, null, null, snapshot.exactPrice().currency(), snapshot.version());
    }

    /** 按原模型版本和真实媒体计量计价；请求条件仅用于匹配原合同，不补造消费。 */
    public Amounts calculateMedia(ModelRevisionRef model, CostType type, MediaUsage usage, MediaPricingContext context) {
        Snapshot snapshot = pricing.snapshot(java.util.Objects.requireNonNull(model, "model"));
        ModelPriceSource.Price price = snapshot.exactPrice();
        MediaCostPrice media = price.mediaPricing();
        Amounts unpriced = Amounts.ofPrecise(null, null, null, price.currency(), snapshot.version());
        if (media == null) return unpriced;
        BigDecimal input = null;
        BigDecimal output = null;
        BigDecimal total;
        if (media.unit() == MediaCostPrice.Unit.TOKENS) {
            if (type != CostType.MEDIA_IMAGE && type != CostType.MEDIA_VIDEO
                    && type != CostType.MEDIA_TRANSCRIPTION && type != CostType.MEDIA_SPEECH) return unpriced;
            input = usage.inputTokens() == null ? null : centsOf(usage.inputTokens(), media.inputPricePerMillionCents());
            output = usage.outputTokens() == null ? null : centsOf(usage.outputTokens(), media.outputPricePerMillionCents());
            total = add(input, output);
        } else {
            BigDecimal count = switch (media.unit()) {
                case OUTPUT_IMAGE -> type == CostType.MEDIA_IMAGE && usage.imageCount() != null
                        ? BigDecimal.valueOf(usage.imageCount()) : null;
                case OUTPUT_VIDEO -> type == CostType.MEDIA_VIDEO && usage.videoCount() != null
                        ? BigDecimal.valueOf(usage.videoCount()) : null;
                case VIDEO_SECOND -> type == CostType.MEDIA_VIDEO && context != null
                        && media.videoResolution().equals(context.videoResolution()) ? usage.durationSeconds() : null;
                case INPUT_AUDIO_SECOND -> type == CostType.MEDIA_TRANSCRIPTION ? usage.durationSeconds() : null;
                case OUTPUT_AUDIO_SECOND -> type == CostType.MEDIA_SPEECH ? usage.durationSeconds() : null;
                case BILLING_CHARACTER -> type == CostType.MEDIA_SPEECH && usage.characters() != null
                        ? BigDecimal.valueOf(usage.characters()) : null;
                case TOKENS -> throw new IllegalStateException("media_price_unit_invalid");
            };
            total = count == null ? null : count.multiply(media.pricePerUnitCents())
                    .setScale(12, java.math.RoundingMode.HALF_UP);
        }
        // 已发生的供应商用量必须保留；金额容量不足只保留缺价，不让数据库截断或拒绝原事实。
        for (BigDecimal amount : new BigDecimal[]{input, output, total}) {
            if (!fitsAmount(amount)) return unpriced;
        }
        return Amounts.ofPrecise(input, output, total, price.currency(), snapshot.version());
    }

    /**
     * 计算金额。
     *
     * @param tenantId    租户 ID
     * @param eventType   事件类型
     * @param provider    AI 服务商标识
     * @param model       模型名
     * @param usage 供应商报告的总输入、输出与缓存子集；缺失保持未知
     * @param quantity    计量单位数（llm.rerank 文档数）
     * @param attemptStartedAt 原外调账本的开始时间；仅显式时段政策消费，缺失不能用当前时间代替
     * @return 金额结果（输入/输出/合计分，无单价均为 null）
     */
    public Amounts calculate(
            Long tenantId, CostType eventType, String provider, String model,
            LlmUsage usage, Integer quantity, Instant attemptStartedAt) {
        return calculate(null, tenantId, eventType, provider, model,
                usage, quantity, attemptStartedAt);
    }

    /** 按显式冻结模型版本计算金额；引用为空时保留当前目录计价语义。 */
    public Amounts calculate(
            ModelRevisionRef pricingModel,
            Long tenantId, CostType eventType, String provider, String model,
            LlmUsage usage, Integer quantity, Instant attemptStartedAt) {
        Snapshot snapshot = pricingModel == null
                ? pricing.snapshot(provider, model) : pricing.snapshot(pricingModel);
        ModelPriceSource.Price price = pricingModel == null
                ? snapshot.resolve(provider, model) : snapshot.exactPrice();
        if (price == null) {
            return Amounts.empty(snapshot.version());
        }
        Long promptTokens = usage == null ? null : usage.promptTokens();
        Long completionTokens = usage == null ? null : usage.completionTokens();
        switch (eventType) {
            case LLM_CHAT -> {
                BigDecimal input = price.cachePricing() == null
                        ? promptTokens == null || promptTokens < 0 ? null : cacheBucket(promptTokens, price.inputPricePerMillion())
                        : cacheInput(usage, price);
                BigDecimal output = completionTokens == null || completionTokens < 0 ? null
                        : cacheBucket(completionTokens, price.outputPricePerMillion());
                String version = snapshot.version();
                if (price.timePricing() != null) {
                    var band = price.timePricing().classification(attemptStartedAt);
                    version += "@time-" + price.timePricing().snapshotKey() + "-" + band.name() + "-" + attemptStartedAt;
                    BigDecimal factor = price.timePricing().multiplier(band);
                    if (factor == null) {
                        input = null; output = null;
                    } else {
                        // 先合并完整输入桶再乘倍率并舍入；容量检查在倍率之后，避免逐桶舍入或过早丢量。
                        if (input != null) input = input.multiply(factor).setScale(12, java.math.RoundingMode.HALF_UP);
                        if (output != null) output = output.multiply(factor).setScale(12, java.math.RoundingMode.HALF_UP);
                    }
                }
                if (!fitsAmount(input)) input = null;
                if (!fitsAmount(output)) output = null;
                BigDecimal total = add(input, output);
                if (!fitsAmount(total)) total = null;
                return new Amounts(Amounts.rounded(input), Amounts.rounded(output), Amounts.rounded(total), price.currency(),
                        input, output, total, version, price.timePricing());
            }
            case LLM_EMBEDDING -> {
                BigDecimal input = promptTokens == null ? null : centsOf(promptTokens.longValue(),
                        price.inputPricePerMillion());
                return Amounts.ofPrecise(input, null, input, price.currency(), snapshot.version());
            }
            case LLM_RERANK -> {
                return switch (price.rerankUnit()) {
                    case INPUT_TOKENS -> {
                        BigDecimal input = promptTokens == null ? null : centsOf(promptTokens,
                                price.inputPricePerMillion());
                        yield Amounts.ofPrecise(input, null, input, price.currency(), snapshot.version());
                    }
                    case QUANTITY -> {
                        BigDecimal total = quantity == null ? null
                                : BigDecimal.valueOf(quantity).multiply(price.pricePerQuantity());
                        yield Amounts.ofPrecise(total, null, total, price.currency(), snapshot.version());
                    }
                    case UNPRICED -> Amounts.empty(snapshot.version());
                };
            }
            case KNOWLEDGE_SOURCE, TOOL_CALL, WORKFLOW_EXTERNAL -> {
                // 每单位数量单价（分/文档或一次外部操作）直接相乘，不经百万基数折算
                BigDecimal total = quantity == null || price.pricePerQuantity() == null
                        ? null : BigDecimal.valueOf(quantity).multiply(price.pricePerQuantity());
                return Amounts.ofPrecise(total, null, total, price.currency(), snapshot.version());
            }
            default -> {
                return Amounts.empty(snapshot.version());
            }
        }
    }

    /** 总输入inclusive；缓存命中和写入只从普通输入桶扣除一次，不把缺失子集补零。 */
    private BigDecimal cacheInput(LlmUsage usage, ModelPriceSource.Price price) {
        if (usage == null || usage.usageSource() != UsageSource.PROVIDER_REPORTED
                || usage.promptTokens() == null || usage.promptTokens() < 0
                || usage.cachedInputTokens() == null || usage.cachedInputTokens() < 0
                || usage.cachedInputTokens() > usage.promptTokens()) return null;
        long ordinary = usage.promptTokens() - usage.cachedInputTokens();
        BigDecimal write = BigDecimal.ZERO;
        if (price.cachePricing().mode() == CacheCostPrice.Mode.CACHE_READ_WRITE) {
            Long count = usage.cacheWriteInputTokens();
            // 先校验剩余量，避免H+W在Long边界溢出后误判合法。
            if (count == null || count < 0 || count > ordinary) return null;
            ordinary -= count;
            write = cacheBucket(count, price.cachePricing().writePricePerMillionCents());
        }
        return add(add(cacheBucket(ordinary, price.inputPricePerMillion()),
                cacheBucket(usage.cachedInputTokens(), price.cachePricing().readPricePerMillionCents())), write);
    }

    private BigDecimal cacheBucket(long count, BigDecimal price) {
        return count == 0 ? BigDecimal.ZERO : centsOf(count, price);
    }

    /** 媒体与缓存新增分项均服从现有精确金额列和整数投影容量。 */
    private boolean fitsAmount(BigDecimal amount) {
        return amount == null || amount.compareTo(MAXIMUM_PRECISE_AMOUNT) <= 0
                && amount.setScale(0, java.math.RoundingMode.HALF_UP).compareTo(MAXIMUM_INTEGER_AMOUNT) <= 0;
    }

    /**
     * 数量 × 单价（每百万）折算金额（分，四舍五入）。
     *
     * @param count  计量数量（token 或文档数）
     * @param price  单价（分/百万）
     * @return 金额（分）；单价为 null 返回 null
     */
    private BigDecimal centsOf(long count, BigDecimal price) {
        if (price == null) {
            return null;
        }
        return BigDecimal.valueOf(count)
                .multiply(price)
                .divide(PER_MILLION, 12, java.math.RoundingMode.HALF_UP);
    }

    /**
     * 相加（可空）。
     *
     * @param a 数值 a
     * @param b 数值 b
     * @return 和；任一为 null 返回 null
     */
    private BigDecimal add(BigDecimal a, BigDecimal b) {
        if (a == null || b == null) {
            return null;
        }
        return a.add(b);
    }

    /**
     * 金额结果。
     *
     * @param input  输入金额（分）
     * @param output 输出金额（分）
     * @param total  合计金额（分）
     * @param currency 币种
     */
    public record Amounts(
            Long input, Long output, Long total, String currency,
            BigDecimal preciseInput, BigDecimal preciseOutput, BigDecimal preciseTotal,
            String pricingSnapshotVersion, TimePricingPolicy timePricing) {
        public Amounts {
            pricingSnapshotVersion = java.util.Objects.requireNonNull(
                    pricingSnapshotVersion, "pricingSnapshotVersion");
        }

        /** 空金额（无单价）。 */
        static Amounts empty(String version) {
            return new Amounts(null, null, null, null, null, null, null, version, null);
        }

        /** 构造金额。 */
        static Amounts ofPrecise(
                BigDecimal input, BigDecimal output, BigDecimal total,
                String currency, String version) {
            return new Amounts(rounded(input), rounded(output), rounded(total),
                    currency, input, output, total, version, null);
        }

        private static Long rounded(BigDecimal value) {
            return value == null ? null : value.setScale(0, java.math.RoundingMode.HALF_UP).longValueExact();
        }
    }
}
