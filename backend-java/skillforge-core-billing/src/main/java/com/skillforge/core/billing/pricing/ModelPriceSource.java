package com.skillforge.core.billing.pricing;

import java.math.BigDecimal;
import java.util.Map;
import java.util.Objects;

/** 计费拥有的模型价格读取契约；实现必须保留未知单价并按精确版本读取已冻结调用。 */
public interface ModelPriceSource {
    /** 一次读取当前价格目录并冻结本次计价使用的完整快照。 */
    Snapshot snapshot();

    /** 只读取目标非媒体 provider/model；不相关模型的价格冲突不得阻断本次计价。 */
    Snapshot snapshot(String provider, String model);

    /** 按受理时冻结的模型身份和 revision 读取价格，不使用当前配置或供应商别名替代。 */
    Snapshot snapshot(ModelRevisionRef model);

    public record Snapshot(String version, Map<Key, Price> prices) {
        public Snapshot {
            version = Objects.requireNonNull(version, "version");
            prices = Map.copyOf(prices);
        }

        public Price resolve(String provider, String model) {
            if (provider == null || model == null) {
                return null;
            }
            return prices.get(new Key(provider, model));
        }

        /** 精确 revision 快照只含一个模型，计价不依赖供应商回包的模型别名。 */
        public Price exactPrice() {
            if (prices.size() != 1) {
                throw new IllegalStateException("精确模型价格快照必须只包含一个模型");
            }
            return prices.values().iterator().next();
        }
    }

    public record Key(String provider, String model) {
        public Key {
            if (provider == null || provider.isBlank() || model == null || model.isBlank()) {
                throw new IllegalStateException("平台模型计价键不能为空");
            }
        }
    }

    /** 宿主在任务受理时冻结的平台模型版本引用。 */
    public record ModelRevisionRef(String modelId, long revision) {
        public ModelRevisionRef {
            if (modelId == null || modelId.isBlank()) {
                throw new IllegalArgumentException("平台模型 ID 不能为空");
            }
            if (revision <= 0) {
                throw new IllegalArgumentException("平台模型 revision 必须是正整数");
            }
        }
    }

    /** Rerank 的冻结成本计量单位；由价格字段明确选择，与响应 usage 是否完整无关。 */
    enum RerankUnit { INPUT_TOKENS, QUANTITY, UNPRICED }

    public record Price(
            BigDecimal inputPricePerMillion,
            BigDecimal outputPricePerMillion,
            BigDecimal pricePerQuantity,
            String currency,
            MediaCostPrice mediaPricing,
            CacheCostPrice cachePricing,
            TimePricingPolicy timePricing) {
        public Price {
            currency = Objects.requireNonNull(currency, "currency");
        }

        public RerankUnit rerankUnit() {
            if (inputPricePerMillion != null && pricePerQuantity != null) {
                throw new IllegalStateException("rerank_price_unit_ambiguous");
            }
            if (inputPricePerMillion != null) return RerankUnit.INPUT_TOKENS;
            return pricePerQuantity == null ? RerankUnit.UNPRICED : RerankUnit.QUANTITY;
        }
    }
}
