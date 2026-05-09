package limhjun.me.shortly.url;

import org.junit.jupiter.api.Test;
import java.util.random.RandomGenerator;

import static org.junit.jupiter.api.Assertions.*;

class Base62EncoderTest {

    @Test
    void encodeKnownValues() {
        assertEquals("0",   Base62Encoder.encode(0L));
        assertEquals("9",   Base62Encoder.encode(9L));
        assertEquals("a",   Base62Encoder.encode(10L));
        assertEquals("Z",   Base62Encoder.encode(61L));
        assertEquals("10",  Base62Encoder.encode(62L));
        assertEquals("q0U", Base62Encoder.encode(100_000L));
    }

    @Test
    void roundTripsRandomLongs() {
        RandomGenerator rng = RandomGenerator.of("L64X128MixRandom");
        for (int i = 0; i < 1000; i++) {
            long v = Math.abs(rng.nextLong());
            String encoded = Base62Encoder.encode(v);
            long decoded = Base62Encoder.decode(encoded);
            assertEquals(v, decoded, "Round-trip failed for " + v);
        }
    }

    @Test
    void rejectsNegative() {
        assertThrows(IllegalArgumentException.class, () -> Base62Encoder.encode(-1L));
    }

    @Test
    void boundaryValues() {
        assertEquals(0L, Base62Encoder.decode(Base62Encoder.encode(0L)));
        assertEquals(Long.MAX_VALUE, Base62Encoder.decode(Base62Encoder.encode(Long.MAX_VALUE)));
        assertTrue(Base62Encoder.encode(Long.MAX_VALUE).length() <= 11,
                   "Long.MAX_VALUE encoding fits in 11 chars");
    }
}
