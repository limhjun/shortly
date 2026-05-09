package limhjun.me.shortly.click;

import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;

@Entity
@Table(
    name = "click_event",
    indexes = {
        @Index(name = "ix_click_url_time", columnList = "short_url_id, clicked_at"),
        @Index(name = "ix_click_time",     columnList = "clicked_at")
    }
)
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class ClickEvent {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Long shortUrlId;

    @Column(nullable = false)
    private Instant clickedAt;

    @Column(nullable = false, length = 64)
    private String ipHash;

    @Column(length = 2048)
    private String referrer;

    @Column(length = 50)
    private String userAgentFamily;
}
