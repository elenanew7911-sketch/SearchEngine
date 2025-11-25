package searchengine.services.impl;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import searchengine.dto.statistics.DetailedStatisticsItem;
import searchengine.dto.statistics.StatisticsData;
import searchengine.dto.statistics.StatisticsResponse;
import searchengine.dto.statistics.TotalStatistics;
import searchengine.model.IndexingStatus;
import searchengine.model.SiteEntity;
import searchengine.repository.LemmaRepository;
import searchengine.repository.PageRepository;
import searchengine.repository.SiteRepository;
import searchengine.services.StatisticsService;

import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;

@Service
@RequiredArgsConstructor
public class StatisticsServiceImpl implements StatisticsService {

    private final SiteRepository siteRepository;
    private final PageRepository pageRepository;
    private final LemmaRepository lemmaRepository;

    @Override
    public StatisticsResponse getStatistics() {
        TotalStatistics total = new TotalStatistics();
        List<DetailedStatisticsItem> detailed = new ArrayList<>();

        List<SiteEntity> sites = siteRepository.findAll();
        total.setSites(sites.size());

        boolean isIndexing = sites.stream()
                .anyMatch(site -> site.getStatus() == IndexingStatus.INDEXING);
        total.setIndexing(isIndexing);

        long totalPages = 0;
        long totalLemmas = 0;

        for (SiteEntity site : sites) {
            DetailedStatisticsItem item = new DetailedStatisticsItem();
            item.setName(site.getName());
            item.setUrl(site.getUrl());
            item.setStatus(site.getStatus().toString());

            long pages = pageRepository.countBySite(site);
            long lemmas = lemmaRepository.countBySite(site);

            item.setPages((int) pages);
            item.setLemmas((int) lemmas);

            totalPages += pages;
            totalLemmas += lemmas;

            if (site.getLastError() != null && !site.getLastError().isEmpty()) {
                item.setError(site.getLastError());
            } else {
                item.setError("");
            }

            long statusTime = site.getStatusTime()
                    .atZone(ZoneId.systemDefault())
                    .toInstant()
                    .toEpochMilli();
            item.setStatusTime(statusTime);

            detailed.add(item);
        }

        total.setPages((int) totalPages);
        total.setLemmas((int) totalLemmas);

        StatisticsResponse response = new StatisticsResponse();
        StatisticsData data = new StatisticsData();
        data.setTotal(total);
        data.setDetailed(detailed);
        response.setStatistics(data);
        response.setResult(true);

        return response;
    }
}
