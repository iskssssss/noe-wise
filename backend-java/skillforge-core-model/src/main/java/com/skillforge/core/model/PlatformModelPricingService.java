package com.skillforge.core.model;

import com.skillforge.core.billing.pricing.ModelPriceSource;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Objects;
import org.springframework.stereotype.Service;

/** 从平台模型不可变版本构建一次计价操作使用的价格快照。 */
@Service
public class PlatformModelPricingService implements ModelPriceSource {
    private final PlatformModelCatalogService models;

    public PlatformModelPricingService(PlatformModelCatalogService models) {
        this.models = Objects.requireNonNull(models, "models");
    }

    /**
     * 未携带精确模型引用的非媒体调用读取非媒体价格目录。
     *
     * <p>相同 provider/model 的多个配置只有价格全值相同时才允许合并；null 也参与
     * 相等判断，避免把“未知价格”和零价或已知价格混为一条模糊规则。</p>
     */
    @Override
    public Snapshot snapshot() {
        return snapshot(models.list().stream().filter(model -> switch (model.kind()) {
            case "IMAGE_GENERATION", "VIDEO_GENERATION", "SPEECH_RECOGNITION", "SPEECH_SYNTHESIS" -> false;
            default -> true;
        }).toList());
    }

    /** 先缩小到本次非媒体计价目标，再校验目标价格的唯一性。 */
    @Override
    public Snapshot snapshot(String provider, String model) {
        return snapshot(models.pricingModels(provider, model));
    }

    /** 按模型不可变 revision 构建快照，供已冻结任务计价。 */
    @Override
    public Snapshot snapshot(ModelRevisionRef model) {
        Objects.requireNonNull(model, "model");
        return snapshot(List.of(ModelView.from(
                models.readRevision(model.modelId(), model.revision()))));
    }

    private Snapshot snapshot(List<ModelView> source) {
        List<ModelView> current = new ArrayList<>(source);
        current.sort(Comparator.comparing(ModelView::id));
        LinkedHashMap<Key, Price> prices = new LinkedHashMap<>();
        StringBuilder identity = new StringBuilder();
        for (ModelView model : current) {
            Price price = new Price(ModelPrices.normalize(model.inputPricePerMillionCents()),
                    ModelPrices.normalize(model.outputPricePerMillionCents()),
                    ModelPrices.normalize(model.pricePerQuantityCents()),
                    model.currency() == null || model.currency().isBlank()
                            ? "CNY" : model.currency(), model.mediaPricing() == null ? null : model.mediaPricing().toPrice(),
                    model.cachePricing() == null ? null : model.cachePricing().toPrice(), model.timePricing());
            Key key = new Key(model.providerId(), model.modelId());
            Price previous = prices.putIfAbsent(key, price);
            if (previous != null && !previous.equals(price)) {
                throw new IllegalStateException(
                        "平台模型价格匹配不唯一: provider=" + model.providerId()
                                + ", model=" + model.modelId());
            }
            identity.append(model.id()).append(':').append(model.revision()).append(':')
                    .append(key.provider()).append(':').append(key.model()).append(':')
                    .append(price).append('\n');
        }
        return new Snapshot(version(identity.toString()), prices);
    }

    private static String version(String identity) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(identity.getBytes(StandardCharsets.UTF_8));
            return "model-pricing-" + HexFormat.of().formatHex(digest, 0, 16);
        } catch (Exception exception) {
            throw new IllegalStateException("无法生成平台模型价格快照版本", exception);
        }
    }

}
