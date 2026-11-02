import java.math.BigDecimal;
import java.util.Locale;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
final class Valid {
    BigDecimal amount() { return new BigDecimal("0.1"); }
    boolean present(String value) { return value != null; }
    String key(String value) { return value.toLowerCase(Locale.ROOT); }
    ThreadPoolExecutor pool() {
        return new ThreadPoolExecutor(1, 1, 0, TimeUnit.SECONDS,
                new ArrayBlockingQueue<>(16), Thread.ofPlatform().name("fixture-").factory(),
                new ThreadPoolExecutor.AbortPolicy());
    }
}
