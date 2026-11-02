package com.skillforge.runtime.session;

import com.skillforge.runtime.api.port.ModelPort.ModelMessage;
import com.skillforge.runtime.api.port.ModelPort.ModelRole;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/** 新实际请求的宿主时间样本；只保存在原请求，不改写来源输入、历史或数据库生命周期时间。 */
public final class AgentExecutionTime {
    public static final String SCHEMA = "skillforge.runtime.execution.time-context.v5";
    private static final String MESSAGE = "time-context:";
    private static final String CURRENT_TIME = "本次当前可信时间（UTC）：";
    private static final String NOTICE = "这是本次模型请求准备时的当前时间锚点，来源为宿主时钟；它替代此前的时间观察，不改变执行权限。"
            + "计算“今天”“昨天”等相对日期时，先按已知业务时区把此时刻换算为当地日历日期，再做日期加减。"
            + "历史消息、示例和业务记录中的日期不能替代当前日期。\n"
            + "UTC仅标识宿主时间样本，不表示用户时区；业务时区未知且影响结论时先澄清。"
            + "这是准备时的快照，不是持续更新的计时器；执行时刻的精确时间仍须使用实时能力。";
    private AgentExecutionTime() { }

    /** 同一个新实际请求只调用一次宿主Clock；Instant的规范文本明确使用UTC。 */
    public static Sample sample(Clock clock) { return new Sample(Objects.requireNonNull(clock).instant()); }
    public record Sample(Instant sampledAt) {
        public Sample { Objects.requireNonNull(sampledAt); }
    }

    /** 在完整历史、调用组和本次动态规则之后追加可读样本，原列表逐项保持不变。 */
    public static List<ModelMessage> append(List<ModelMessage> messages, Sample sample, long ordinal) {
        var result = new ArrayList<>(messages); result.add(message(sample, ordinal)); return List.copyOf(result);
    }

    /** 恢复只解析原请求末条消息的首行，再逐字验证整条消息；不查询时钟或推定用户时区。 */
    public static Sample frozen(List<ModelMessage> messages, long ordinal) {
        if (messages.isEmpty()) throw invalid(); var last = messages.getLast();
        if (last.role() != ModelRole.USER || !last.messageId().equals(MESSAGE + ordinal)) throw invalid();
        String text=last.textContent(); int end=text.indexOf('\n');
        if(!text.startsWith(CURRENT_TIME)||end<=CURRENT_TIME.length())throw invalid();
        final Sample sample;
        try { sample = new Sample(Instant.parse(text.substring(CURRENT_TIME.length(),end))); }
        catch (java.time.DateTimeException malformed) { throw invalid(); }
        if (!last.equals(message(sample, ordinal))) throw invalid();
        return sample;
    }

    private static ModelMessage message(Sample sample, long ordinal) {
        Objects.requireNonNull(sample); if (ordinal <= 0) throw invalid();
        // 格式归准备版本、序号归消息身份；模型只需精确时间及其使用边界。
        String text=CURRENT_TIME+sample.sampledAt()+"\n"+NOTICE;
        return new ModelMessage(MESSAGE + ordinal, ModelRole.USER, text);
    }
    private static IllegalArgumentException invalid() { return new IllegalArgumentException("execution_time_context_invalid"); }
}
