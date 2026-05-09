package limhjun.me.shortly.click;

import limhjun.me.shortly.analytics.dto.DayCount;
import limhjun.me.shortly.analytics.dto.ReferrerCount;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.time.Instant;
import java.util.List;

public interface ClickEventRepository extends JpaRepository<ClickEvent, Long> {

    long countByShortUrlId(Long shortUrlId);

    @Query("""
        select count(distinct e.ipHash)
        from ClickEvent e
        where e.shortUrlId = :id
        """)
    long countDistinctIpHashByShortUrlId(Long id);

    @Query("""
        select function('date_trunc', 'day', e.clickedAt) as day,
               count(e) as n
        from ClickEvent e
        where e.shortUrlId = :id and e.clickedAt >= :since
        group by function('date_trunc', 'day', e.clickedAt)
        order by day
        """)
    List<DayCount> byDay(Long id, Instant since);

    @Query("""
        select e.referrer as referrer, count(e) as n
        from ClickEvent e
        where e.shortUrlId = :id and e.referrer is not null
        group by e.referrer
        order by count(e) desc
        """)
    List<ReferrerCount> topReferrers(Long id, Pageable limit);
}
