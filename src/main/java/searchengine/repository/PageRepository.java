package searchengine.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import searchengine.model.PageEntity;
import searchengine.model.SiteEntity;

import java.util.Optional;

@Repository
public interface PageRepository extends JpaRepository<PageEntity, Integer> {
    Optional<PageEntity> findBySiteAndPath(SiteEntity site, String path);

    @Query("SELECT p FROM PageEntity p WHERE p.site = :site AND p.path = :path ORDER BY p.id ASC")
    Optional<PageEntity> findFirstBySiteAndPath(@Param("site") SiteEntity site, @Param("path") String path);

    long countBySite(SiteEntity site);

    @Modifying
    @Query("DELETE FROM PageEntity p WHERE p.site = :site")
    void deleteBySite(@Param("site") SiteEntity site);
}