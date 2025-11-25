package searchengine.services.impl;

import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.springframework.stereotype.Service;
import searchengine.dto.search.SearchData;
import searchengine.dto.search.SearchResponse;
import searchengine.model.IndexingStatus;
import searchengine.model.LemmaEntity;
import searchengine.model.PageEntity;
import searchengine.model.SiteEntity;
import searchengine.repository.IndexRepository;
import searchengine.repository.LemmaRepository;
import searchengine.repository.PageRepository;
import searchengine.repository.SiteRepository;
import searchengine.services.SearchService;

import java.util.*;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Slf4j
public class SearchServiceImpl implements SearchService {

    private final SiteRepository siteRepository;
    private final PageRepository pageRepository;
    private final LemmaRepository lemmaRepository;
    private final IndexRepository indexRepository;
    private final LemmaService lemmaService;

    @Override
    public SearchResponse search(String query, String siteUrl, int offset, int limit) {
        if (query == null || query.trim().isEmpty()) {
            return new SearchResponse(false, "Задан пустой поисковый запрос");
        }

        try {
            Set<String> lemmas = lemmaService.getLemmasFromQuery(query);
            if (lemmas.isEmpty()) {
                return new SearchResponse(true, 0, Collections.emptyList());
            }

            List<SiteEntity> sitesToSearch = getSitesToSearch(siteUrl);
            if (sitesToSearch.isEmpty()) {
                return new SearchResponse(false, "Указанный сайт не найден");
            }

            Map<PageEntity, Float> pageRelevance = new HashMap<>();

            for (SiteEntity site : sitesToSearch) {
                if (site.getStatus() != IndexingStatus.INDEXED) {
                    continue;
                }

                Map<PageEntity, Float> sitePageRelevance = searchOnSite(site, lemmas);
                pageRelevance.putAll(sitePageRelevance);
            }

            if (pageRelevance.isEmpty()) {
                return new SearchResponse(true, 0, Collections.emptyList());
            }

            float maxRelevance = Collections.max(pageRelevance.values());

            List<SearchData> searchResults = pageRelevance.entrySet().stream()
                    .map(entry -> createSearchData(entry.getKey(), entry.getValue() / maxRelevance, lemmas))
                    .sorted(Comparator.comparing(SearchData::getRelevance).reversed())
                    .collect(Collectors.toList());

            int totalResults = searchResults.size();
            int toIndex = Math.min(offset + limit, totalResults);

            if (offset >= totalResults) {
                return new SearchResponse(true, totalResults, Collections.emptyList());
            }

            List<SearchData> paginatedResults = searchResults.subList(offset, toIndex);

            return new SearchResponse(true, totalResults, paginatedResults);

        } catch (Exception e) {
            log.error("Ошибка при выполнении поиска", e);
            return new SearchResponse(false, "Ошибка при выполнении поиска: " + e.getMessage());
        }
    }

    private List<SiteEntity> getSitesToSearch(String siteUrl) {
        if (siteUrl == null || siteUrl.isEmpty()) {
            return siteRepository.findAll();
        }

        return siteRepository.findByUrl(siteUrl)
                .map(Collections::singletonList)
                .orElse(Collections.emptyList());
    }

    private Map<PageEntity, Float> searchOnSite(SiteEntity site, Set<String> queryLemmas) {
        Map<PageEntity, Float> pageRelevance = new HashMap<>();

        long totalPages = pageRepository.countBySite(site);
        if (totalPages == 0) {
            return pageRelevance;
        }

        List<LemmaEntity> sortedLemmas = queryLemmas.stream()
                .map(lemma -> lemmaRepository.findBySiteAndLemma(site, lemma).orElse(null))
                .filter(Objects::nonNull)
                .filter(lemma -> lemma.getFrequency() < totalPages * 0.8)
                .sorted(Comparator.comparing(LemmaEntity::getFrequency))
                .collect(Collectors.toList());

        if (sortedLemmas.isEmpty()) {
            return pageRelevance;
        }

        Set<PageEntity> pages = findPagesWithAllLemmas(sortedLemmas);

        for (PageEntity page : pages) {
            float relevance = calculatePageRelevance(page, sortedLemmas);
            pageRelevance.put(page, relevance);
        }

        return pageRelevance;
    }

    private Set<PageEntity> findPagesWithAllLemmas(List<LemmaEntity> lemmas) {
        if (lemmas.isEmpty()) {
            return Collections.emptySet();
        }

        LemmaEntity firstLemma = lemmas.get(0);
        Set<PageEntity> pages = new HashSet<>(indexRepository.findPagesByLemma(firstLemma));

        for (int i = 1; i < lemmas.size(); i++) {
            if (pages.isEmpty()) {
                break;
            }

            LemmaEntity lemma = lemmas.get(i);
            List<PageEntity> filteredPages = indexRepository.findPagesByLemmaAndPageIn(lemma, pages);
            pages.retainAll(filteredPages);
        }

        return pages;
    }

    private float calculatePageRelevance(PageEntity page, List<LemmaEntity> lemmas) {
        Double sum = indexRepository.sumRankByPageAndLemmas(page, lemmas);
        return sum != null ? sum.floatValue() : 0f;
    }

    private SearchData createSearchData(PageEntity page, float relevance, Set<String> lemmas) {
        SearchData data = new SearchData();

        SiteEntity site = page.getSite();
        data.setSite(site.getUrl());
        data.setSiteName(site.getName());
        data.setUri(page.getPath());
        data.setRelevance(relevance);

        try {
            Document doc = Jsoup.parse(page.getContent());
            String title = doc.title();
            data.setTitle(title.isEmpty() ? "Без названия" : title);

            String snippet = generateSnippet(doc.text(), lemmas);
            data.setSnippet(snippet);
        } catch (Exception e) {
            log.error("Ошибка при создании данных для поиска", e);
            data.setTitle("Ошибка загрузки");
            data.setSnippet("");
        }

        return data;
    }

    private String generateSnippet(String text, Set<String> queryLemmas) {
        if (text == null || text.isEmpty()) {
            return "";
        }

        String[] sentences = text.split("\\. ");
        List<SentenceScore> sentenceScores = new ArrayList<>();

        for (String sentence : sentences) {
            if (sentence.length() < 20) {
                continue;
            }

            Map<String, Integer> sentenceLemmas = lemmaService.collectLemmas(sentence);
            long matchCount = sentenceLemmas.keySet().stream()
                    .filter(queryLemmas::contains)
                    .count();

            if (matchCount > 0) {
                sentenceScores.add(new SentenceScore(sentence, matchCount));
            }
        }

        if (sentenceScores.isEmpty()) {
            return text.substring(0, Math.min(200, text.length())) + "...";
        }

        sentenceScores.sort(Comparator.comparing(SentenceScore::getScore).reversed());

        StringBuilder snippet = new StringBuilder();
        int addedSentences = 0;

        for (SentenceScore ss : sentenceScores) {
            if (addedSentences >= 3 || snippet.length() > 300) {
                break;
            }

            String highlightedSentence = highlightLemmasInText(ss.getSentence(), queryLemmas);
            snippet.append(highlightedSentence).append(". ");
            addedSentences++;
        }

        return snippet.toString().trim();
    }

    private String highlightLemmasInText(String text, Set<String> queryLemmas) {
        String[] words = text.split("\\s+");
        StringBuilder result = new StringBuilder();

        for (String word : words) {
            String cleanWord = word.replaceAll("[^а-яёa-zА-ЯЁA-Z]", "").toLowerCase();

            if (!cleanWord.isEmpty()) {
                Set<String> wordLemmas = lemmaService.getLemmasFromQuery(cleanWord);
                boolean matches = wordLemmas.stream().anyMatch(queryLemmas::contains);

                if (matches) {
                    result.append("<b>").append(word).append("</b> ");
                } else {
                    result.append(word).append(" ");
                }
            } else {
                result.append(word).append(" ");
            }
        }

        return result.toString().trim();
    }

    @Getter
    private static class SentenceScore {
        private final String sentence;
        private final long score;

        public SentenceScore(String sentence, long score) {
            this.sentence = sentence;
            this.score = score;
        }

    }
}