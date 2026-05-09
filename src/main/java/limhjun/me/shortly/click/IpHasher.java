package limhjun.me.shortly.click;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.HexFormat;
import java.util.function.Supplier;

@Component
public class IpHasher {

    private final String pepper;
    private final Supplier<LocalDate> dateSupplier;

    public IpHasher(@Value("${app.ip-hash-pepper}") String pepper) {
        this(pepper, () -> LocalDate.now(ZoneOffset.UTC));
    }

    // For tests: inject a fixed date.
    IpHasher(String pepper, Supplier<LocalDate> dateSupplier) {
        this.pepper = pepper;
        this.dateSupplier = dateSupplier;
    }

    public String hash(String ip) {
        String salt = pepper + ":" + dateSupplier.get();
        String input = salt + ":" + ip;
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(input.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }
}
