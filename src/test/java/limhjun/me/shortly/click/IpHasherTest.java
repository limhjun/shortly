package limhjun.me.shortly.click;

import org.junit.jupiter.api.Test;

import java.time.LocalDate;

import static org.junit.jupiter.api.Assertions.*;

class IpHasherTest {

    private static final String PEPPER = "test-pepper";
    private static final LocalDate TODAY = LocalDate.of(2026, 5, 9);
    private static final LocalDate TOMORROW = LocalDate.of(2026, 5, 10);

    @Test
    void sameIpSameDayProducesSameHash() {
        IpHasher hasher = new IpHasher(PEPPER, () -> TODAY);
        String h1 = hasher.hash("203.0.113.5");
        String h2 = hasher.hash("203.0.113.5");
        assertEquals(h1, h2);
    }

    @Test
    void sameIpDifferentDayProducesDifferentHash() {
        IpHasher today    = new IpHasher(PEPPER, () -> TODAY);
        IpHasher tomorrow = new IpHasher(PEPPER, () -> TOMORROW);
        assertNotEquals(today.hash("203.0.113.5"), tomorrow.hash("203.0.113.5"));
    }

    @Test
    void differentIpsProduceDifferentHashes() {
        IpHasher hasher = new IpHasher(PEPPER, () -> TODAY);
        assertNotEquals(hasher.hash("203.0.113.5"), hasher.hash("203.0.113.6"));
    }

    @Test
    void hashIsSixtyFourHexChars() {
        IpHasher hasher = new IpHasher(PEPPER, () -> TODAY);
        String h = hasher.hash("203.0.113.5");
        assertEquals(64, h.length());
        assertTrue(h.matches("[0-9a-f]+"));
    }

    @Test
    void differentPeppersProduceDifferentHashes() {
        IpHasher a = new IpHasher("pepper-a", () -> TODAY);
        IpHasher b = new IpHasher("pepper-b", () -> TODAY);
        assertNotEquals(a.hash("203.0.113.5"), b.hash("203.0.113.5"));
    }
}
