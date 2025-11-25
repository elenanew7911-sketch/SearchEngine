package searchengine.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;
import searchengine.model.LemmaEntity;
import searchengine.model.SiteEntity;

import java.util.List;
import java.util.Optional;

@Repository
public interface LemmaRepository extends JpaRepository<LemmaEntity, Integer> {
    Optional<LemmaEntity> findBySiteAndLemma(SiteEntity site, String lemma);

    long countBySite(SiteEntity site);

    @Modifying
    @Query("DELETE FROM LemmaEntity l WHERE l.site = :site")
    void deleteBySite(@Param("site") SiteEntity site);

    @Modifying
    @Transactional
    @Query(value = "INSERT INTO lemma (site_id, lemma, frequency) VALUES (:siteId, :lemma, :frequency) " +
                   "ON DUPLICATE KEY UPDATE frequency = frequency + :frequency", nativeQuery = true)
    void insertOrUpdateLemma(@Param("siteId") Integer siteId,
                             @Param("lemma") String lemma,
                             @Param("frequency") int frequency);

    @Query("SELECT l FROM LemmaEntity l WHERE l.site.id = :siteId AND l.lemma IN :lemmas")
    List<LemmaEntity> findBySiteIdAndLemmaIn(@Param("siteId") Integer siteId, @Param("lemmas") List<String> lemmas);
}
