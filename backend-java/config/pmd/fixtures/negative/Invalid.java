import java.math.BigDecimal;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
final class Invalid {
    BigDecimal amount() { return new BigDecimal(0.1); }
    boolean present(String value) { return value.equals(null); }
    String key(String value) { return value.toLowerCase(); }
    ExecutorService pool() { return Executors.newFixedThreadPool(4); }
}
