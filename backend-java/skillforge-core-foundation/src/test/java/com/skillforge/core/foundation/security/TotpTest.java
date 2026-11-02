package com.skillforge.core.foundation.security;
import static org.assertj.core.api.Assertions.*;
import java.time.Instant;
import org.junit.jupiter.api.Test;
/** RFC6238已知向量、时间窗和重放边界。 */
class TotpTest {
 private static final String SECRET="GEZDGNBVGY3TQOJQGEZDGNBVGY3TQOJQ";
 @Test void rfc6238Sha1VectorsAtSixDigits(){assertThat(Totp.generate(SECRET,59/30)).isEqualTo("287082");assertThat(Totp.generate(SECRET,1111111109L/30)).isEqualTo("081804");assertThat(Totp.generate(SECRET,1234567890L/30)).isEqualTo("005924");}
 @Test void consumedCounterAndOutsideWindowAreRejected(){var now=Instant.ofEpochSecond(1234567890);long counter=now.getEpochSecond()/30;String code=Totp.generate(SECRET,counter);assertThat(Totp.match(SECRET,code,now,-1)).isEqualTo(counter);assertThat(Totp.match(SECRET,code,now,counter)).isEqualTo(-1);assertThat(Totp.match(SECRET,Totp.generate(SECRET,counter-2),now,-1)).isEqualTo(-1);}
 @Test void generatedSecretIsStandard160BitBase32(){assertThat(Totp.newSecret()).matches("[A-Z2-7]{32}");}
}
