package limhjun.me.shortly.url;

import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;

@Entity
@Table(name = "short_url")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class ShortUrl {

    @Id
    @GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "short_url_seq")
    @SequenceGenerator(name = "short_url_seq", sequenceName = "short_url_seq", allocationSize = 1)
    private Long id;

    @Column(nullable = false, unique = true, length = 11)
    private String code;

    @Column(nullable = false, length = 2048)
    private String originalUrl;

    @Column(nullable = false)
    private Instant createdAt;

    @Column
    private Instant expiresAt;

    @PrePersist
    void assignCode() {
        // id is set by Hibernate from the sequence before this hook fires
        this.code = Base62Encoder.encode(this.id);
    }
}
