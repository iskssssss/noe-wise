package com.skillforge.runtime.session;

import static org.junit.jupiter.api.Assertions.*;

import com.skillforge.runtime.api.port.ModelPort.ModelMessage;
import com.skillforge.runtime.api.port.ModelPort.ModelRole;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class AgentExecutionTimeTest {
    @Test void readableCurrentTimePreservesTheUtcInstantAcrossBusinessTimezoneMidnight() {
        var business=ZoneId.of("Asia/Shanghai");
        for(var instant:List.of(Instant.parse("2026-10-27T15:59:59.123456789Z"),Instant.parse("2026-10-27T16:00:00.123456789Z"))) {
            var sample=AgentExecutionTime.sample(Clock.fixed(instant,ZoneOffset.ofHours(-4)));
            var messages=AgentExecutionTime.append(List.of(),sample,7);
            assertEquals(ModelRole.USER,messages.getLast().role());
            String text=messages.getLast().textContent();
            assertTrue(text.contains("替代此前的时间观察，不改变执行权限"));
            assertTrue(text.startsWith("本次当前可信时间（UTC）："+instant+"\n"));
            assertTrue(text.contains("先按已知业务时区把此时刻换算为当地日历日期，再做日期加减"));
            assertTrue(text.contains("历史消息、示例和业务记录中的日期不能替代当前日期"));
            assertFalse(text.contains("冻结格式"));assertFalse(text.contains("模型准备序号"));
            assertFalse(text.startsWith("{"));assertFalse(text.contains("Asia/Shanghai"));
            assertEquals(instant,AgentExecutionTime.frozen(messages,7).sampledAt());
            var yesterday=AgentExecutionTime.frozen(messages,7).sampledAt().atZone(business).toLocalDate().minusDays(1);
            assertEquals(instant.isBefore(Instant.parse("2026-10-27T16:00:00Z"))?LocalDate.of(2026,10,26):LocalDate.of(2026,10,27),yesterday);
        }
    }

    @Test void restorationOnlyReadsTheFrozenTextAndNeverResamplesTheClock() {
        var reads=new AtomicInteger();var instant=Instant.parse("2026-10-27T16:15:00.123456789Z");
        var clock=new Clock() {
            @Override public ZoneId getZone(){return ZoneOffset.UTC;}
            @Override public Clock withZone(ZoneId zone){return this;}
            @Override public Instant instant(){if(reads.incrementAndGet()!=1)throw new AssertionError("恢复不应读取当前时钟");return instant;}
        };
        var messages=AgentExecutionTime.append(List.of(),AgentExecutionTime.sample(clock),3);
        assertEquals(instant,AgentExecutionTime.frozen(messages,3).sampledAt());
        assertEquals(messages,AgentExecutionTime.append(List.of(),AgentExecutionTime.frozen(messages,3),3));
        assertEquals(1,reads.get());
    }

    @Test void changedFormatIdentityClockZoneGuidanceOrNonCanonicalInstantIsRejected() {
        var instant=Instant.parse("2026-10-27T16:15:00.123456789Z");
        var message=AgentExecutionTime.append(List.of(),new AgentExecutionTime.Sample(instant),3).getLast();
        for(var changed:List.of(message.textContent()+"\n冻结格式：skillforge.runtime.execution.time-context.v2",
                message.textContent()+"；模型准备序号：4",
                message.textContent().replace(instant.toString(),"2026-10-28T00:15:00.123456789+08:00"),
                message.textContent().replace(instant.toString(),"invalid-time"),
                message.textContent().replace("来源为宿主时钟","来源为供应商时钟"),
                message.textContent().replace("（UTC）","（Asia/Shanghai）"),
                message.textContent().replace("不表示用户时区","表示用户时区"),
                message.textContent().replace("当前时间锚点","历史时间锚点"))) {
            assertThrows(IllegalArgumentException.class,()->AgentExecutionTime.frozen(List.of(new ModelMessage(message.messageId(),ModelRole.USER,changed)),3));
        }
        assertThrows(IllegalArgumentException.class,()->AgentExecutionTime.frozen(List.of(message),4));
        assertThrows(IllegalArgumentException.class,()->AgentExecutionTime.frozen(List.of(new ModelMessage(message.messageId(),ModelRole.SYSTEM,message.textContent())),3));
        assertThrows(IllegalArgumentException.class,()->AgentExecutionTime.frozen(List.of(new ModelMessage(message.messageId(),ModelRole.USER,"{}")),3));
    }
}
