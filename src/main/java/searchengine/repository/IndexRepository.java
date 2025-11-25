package searchengine.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import searchengine.model.IndexEntity;
import searchengine.model.LemmaEntity;
import searchengine.model.PageEntity;

import java.util.List;
import java.util.Set;

@Repository
public interface IndexRepository extends JpaRepository<IndexEntity, Integer> {
    @Modifying
    @Query("DELETE FROM IndexEntity i WHERE i.page = :page")
    void deleteByPage(@Param("page") PageEntity page);

    @Modifying
    @Query(value = "DELETE FROM search_index WHERE page_id IN (SELECT id FROM page WHERE site_id = :siteId)", nativeQuery = true)
    void deleteBySite(@Param("siteId") Integer siteId);

    @Query("SELECT DISTINCT i.page FROM IndexEntity i WHERE i.lemma = :lemma")
    List<PageEntity> findPagesByLemma(@Param("lemma") LemmaEntity lemma);

    @Query("SELECT DISTINCT i.page FROM IndexEntity i WHERE i.lemma = :lemma AND i.page IN :pages")
    List<PageEntity> findPagesByLemmaAndPageIn(@Param("lemma") LemmaEntity lemma, @Param("pages") Set<PageEntity> pages);

    @Query("SELECT SUM(i.rankValue) FROM IndexEntity i WHERE i.page = :page AND i.lemma IN :lemmas")
    Double sumRankByPageAndLemmas(@Param("page") PageEntity page, @Param("lemmas") List<LemmaEntity> lemmas);
}