package searchengine.services.impl;

import lombok.extern.slf4j.Slf4j;
import org.jsoup.Connection;
import org.jsoup.Jsoup;
import org.jsoup.UnsupportedMimeTypeException;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;
import org.springframework.context.annotation.Lazy;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.CannotCreateTransactionException;
import org.springframework.transaction.UnexpectedRollbackException;
import org.springframework.transaction.annotation.Transactional;
import searchengine.config.Site;
import searchengine.config.SitesList;
import searchengine.dto.ApiResponse;
import searchengine.model.*;
import searchengine.repository.IndexRepository;
import searchengine.repository.LemmaRepository;
import searchengine.repository.PageRepository;
import searchengine.repository.SiteRepository;
import searchengine.services.IndexingService;

import javax.net.ssl.SSLHandshakeException;
import java.net.SocketTimeoutException;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

@Service
@Slf4j
public class IndexingServiceImpl implements IndexingService {

    private final SiteRepository siteRepository;
    private final PageRepository pageRepository;
    private final LemmaRepository lemmaRepository;
    private final IndexRepository indexRepository;
    private final LemmaService lemmaService;
    private final SitesList sitesList;
    private final IndexingServiceImpl self;

    private final Map<String, ForkJoinPool> indexingPools = new ConcurrentHashMap<>();
    private final AtomicBoolean isIndexing = new AtomicBoolean(false);

    public IndexingServiceImpl(
            SiteRepository siteRepository,
            PageRepository pageRepository,
            LemmaRepository lemmaRepository,
            IndexRepository indexRepository,
            LemmaService lemmaService,
            SitesList sitesList,
            @Lazy IndexingServiceImpl self) {
        this.siteRepository = siteRepository;
        this.pageRepository = pageRepository;
        this.lemmaRepository = lemmaRepository;
        this.indexRepository = indexRepository;
        this.lemmaService = lemmaService;
        this.sitesList = sitesList;
        this.self = self;
    }

    @Override
    @Transactional
    public ApiResponse startIndexing() {
        if (isIndexing.get()) {
            return new ApiResponse(false, "Индексация уже запущена");
        }

        isIndexing.set(true);

        ExecutorService executorService = Executors.newFixedThreadPool(sitesList.getSites().size());

        for (Site site : sitesList.getSites()) {
            executorService.submit(() -> indexSite(site));
        }

        executorService.shutdown();

        return new ApiResponse(true);
    }

    @Override
    public ApiResponse stopIndexing() {
        if (!isIndexing.get()) {
            return new ApiResponse(false, "Индексация не запущена");
        }

        isIndexing.set(false);

        int poolsCount = indexingPools.size();
        for (ForkJoinPool pool : indexingPools.values()) {
            pool.shutdownNow();
        }
        indexingPools.clear();
        log.info("ForkJoinPool остановлены, всего пулов было: {}", poolsCount);

        for (Site site : sitesList.getSites()) {
            log.info("Обработка сайта из конфига: {}", site.getUrl());
            SiteEntity siteEntity = siteRepository.findByUrl(site.getUrl()).orElse(null);

            if (siteEntity == null) {
                log.warn("SiteEntity не найден для URL: {}", site.getUrl());
                continue;
            }

            log.info("SiteEntity найден: id={}, url={}, status={}",
                    siteEntity.getId(), siteEntity.getUrl(), siteEntity.getStatus());

            if (siteEntity.getStatus() == IndexingStatus.INDEXING) {
                log.info("Изменяем статус с INDEXING на INDEXED для сайта: {}", site.getUrl());
                siteEntity.setStatus(IndexingStatus.INDEXED);
                siteEntity.setStatusTime(LocalDateTime.now());
                siteRepository.save(siteEntity);
                log.info("Статус сохранен в БД для сайта: {}", site.getUrl());
            } else {
                log.info("Статус не INDEXING (текущий статус: {}), пропускаем сайт: {}",
                        siteEntity.getStatus(), site.getUrl());
            }
        }
        return new ApiResponse(true);
    }

    @Override
    public ApiResponse indexPage(String url) {
        Site configSite = findSiteByUrl(url);
        if (configSite == null) {
            return new ApiResponse(false, "Данная страница находится за пределами сайтов, указанных в конфигурационном файле");
        }

        try {
            SiteEntity siteEntity = siteRepository.findByUrl(configSite.getUrl())
                    .orElseGet(() -> createSiteEntity(configSite));

            String path = url.replace(configSite.getUrl(), "");
            if (path.isEmpty()) {
                path = "/";
            }

            PageEntity existingPage = pageRepository.findFirstBySiteAndPath(siteEntity, path).orElse(null);
            if (existingPage != null) {
                self.deletePageIndexes(existingPage);
                pageRepository.delete(existingPage);
            }

            indexSinglePage(siteEntity, url, path);

            return new ApiResponse(true);
        } catch (Exception e) {
            log.error("Ошибка при индексации страницы: {}", url, e);
            return new ApiResponse(false, "Ошибка при индексации страницы: " + e.getMessage());
        }
    }

    private void indexSite(Site site) {
        try {
            self.deleteOldSiteData(site.getUrl());

            SiteEntity siteEntity = createSiteEntity(site);
            siteEntity.setStatus(IndexingStatus.INDEXING);
            siteEntity = siteRepository.save(siteEntity);
            
            ForkJoinPool forkJoinPool = new ForkJoinPool(2);
            indexingPools.put(site.getUrl(), forkJoinPool);

            Set<String> visitedUrls = ConcurrentHashMap.newKeySet();
            SiteIndexingTask task = new SiteIndexingTask(site.getUrl(), site.getUrl(), siteEntity, visitedUrls);

            forkJoinPool.invoke(task);

            if (isIndexing.get()) {
                siteEntity.setStatus(IndexingStatus.INDEXED);
                siteEntity.setStatusTime(LocalDateTime.now());
                siteRepository.save(siteEntity);
            }

            indexingPools.remove(site.getUrl());

        } catch (Exception e) {
            log.error("Ошибка при индексации сайта: {}", site.getUrl(), e);
            SiteEntity siteEntity = siteRepository.findByUrl(site.getUrl()).orElse(null);
            if (siteEntity != null) {
                if (isIndexing.get()) {
                    siteEntity.setStatus(IndexingStatus.FAILED);
                    siteEntity.setLastError(e.getMessage());
                    siteEntity.setStatusTime(LocalDateTime.now());
                    siteRepository.save(siteEntity);
                }
            }
        } finally {
            if (indexingPools.values().stream().allMatch(ForkJoinPool::isQuiescent)) {
                isIndexing.set(false);
            }
        }
    }

    @Transactional
    public void deleteOldSiteData(String url) {
        SiteEntity siteEntity = siteRepository.findByUrl(url).orElse(null);
        if (siteEntity != null) {
            indexRepository.deleteBySite(siteEntity.getId());
            lemmaRepository.deleteBySite(siteEntity);
            pageRepository.deleteBySite(siteEntity);
            siteRepository.delete(siteEntity);
        }
    }

    private SiteEntity createSiteEntity(Site site) {
        SiteEntity siteEntity = new SiteEntity();
        siteEntity.setUrl(site.getUrl());
        siteEntity.setName(site.getName());
        siteEntity.setStatus(IndexingStatus.INDEXING);
        siteEntity.setStatusTime(LocalDateTime.now());
        return siteRepository.save(siteEntity);
    }

    private void indexSinglePage(SiteEntity siteEntity, String fullUrl, String path) {
        try {
            Thread.sleep(150);

            Connection.Response response = Jsoup.connect(fullUrl)
                    .userAgent("Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")
                    .referrer("http://www.google.com")
                    .timeout(10000)
                    .ignoreHttpErrors(true)
                    .execute();

            int statusCode = response.statusCode();
            String html = response.body();

            PageEntity pageEntity;
            try {
                pageEntity = new PageEntity();
                pageEntity.setSite(siteEntity);
                pageEntity.setPath(path);
                pageEntity.setCode(statusCode);
                pageEntity.setContent(html);
                pageEntity = pageRepository.save(pageEntity);
            } catch (DataIntegrityViolationException e) {
                log.info("Страница уже существует (пропускаем дубликат): {}", fullUrl);
                return;
            }

            if (statusCode >= 200 && statusCode < 400) {
                boolean indexed = false;
                int retries = 3;
                for (int attempt = 1; attempt <= retries && !indexed; attempt++) {
                    try {
                        self.indexPageContent(pageEntity, html);
                        indexed = true;
                    } catch (DataAccessException e) {
                        boolean isDeadlock = e.getMessage() != null &&
                                           e.getMessage().contains("Deadlock found");

                        if (isDeadlock && attempt < retries) {
                            log.warn("Deadlock при индексации (попытка {}/{}): {}", attempt, retries, fullUrl);
                            try {
                                Thread.sleep(50 + new Random().nextInt(100));
                            } catch (InterruptedException ie) {
                                Thread.currentThread().interrupt();
                                break;
                            }
                        } else if (isDeadlock) {
                            log.error("Не удалось проиндексировать после {} попыток (deadlock): {}", retries, fullUrl);
                            break;
                        } else {
                            log.warn("Не удалось проиндексировать контент страницы (БД ошибка): {}", fullUrl);
                            break;
                        }
                    } catch (RuntimeException e) {
                        log.warn("Не удалось проиндексировать контент страницы: {}", fullUrl);
                        break;
                    }
                }
            }
            if (isIndexing.get()) {
                siteEntity.setStatusTime(LocalDateTime.now());
                siteRepository.save(siteEntity);
            }

        } catch (SSLHandshakeException e) {
            log.warn("SSL ошибка при индексации страницы (пропускаем): {}", fullUrl);
        } catch (UnsupportedMimeTypeException e) {
            log.debug("Неподдерживаемый тип файла (пропускаем): {}", fullUrl);
        } catch (SocketTimeoutException e) {
            log.warn("Таймаут при индексации страницы (пропускаем): {}", fullUrl);
        } catch (DataIntegrityViolationException e) {
            log.debug("Дубликат страницы (пропускаем): {}", fullUrl);
        } catch (CannotCreateTransactionException e) {
            log.error("Ошибка соединения с БД при индексации страницы: {}", fullUrl);
            throw new RuntimeException("DB Connection error", e);
        } catch (UnexpectedRollbackException e) {
            log.warn("Транзакция откачена при индексации страницы (пропускаем): {}", fullUrl);
        } catch (Exception e) {
            log.error("Ошибка при индексации страницы: {}", fullUrl, e);
        }
    }

    @Transactional
    public void indexPageContent(PageEntity pageEntity, String html) {
        try {
            Document doc = Jsoup.parse(html);
            String text = doc.text();

            Map<String, Integer> lemmas = lemmaService.collectLemmas(text);

            for (Map.Entry<String, Integer> entry : lemmas.entrySet()) {
                String lemmaText = entry.getKey();
                Integer count = entry.getValue();

                lemmaRepository.insertOrUpdateLemma(pageEntity.getSite().getId(), lemmaText, 1);
            }

            List<String> lemmaTexts = new ArrayList<>(lemmas.keySet());
            List<LemmaEntity> lemmaEntities = lemmaRepository.findBySiteIdAndLemmaIn(
                    pageEntity.getSite().getId(), lemmaTexts);

            for (LemmaEntity lemmaEntity : lemmaEntities) {
                Integer count = lemmas.get(lemmaEntity.getLemma());
                if (count != null) {
                    IndexEntity indexEntity = new IndexEntity();
                    indexEntity.setPage(pageEntity);
                    indexEntity.setLemma(lemmaEntity);
                    indexEntity.setRankValue(count.floatValue());
                    indexRepository.save(indexEntity);
                }
            }
        } catch (DataAccessException e) {
            log.warn("Ошибка БД при индексировании контента страницы: {}", e.getMessage());
            throw e;
        } catch (Exception e) {
            log.error("Ошибка при индексировании контента страницы", e);
            throw new RuntimeException(e);
        }
    }

    @Transactional
    public void deletePageIndexes(PageEntity page) {
        indexRepository.deleteByPage(page);
    }

    private Site findSiteByUrl(String url) {
        for (Site site : sitesList.getSites()) {
            if (url.startsWith(site.getUrl())) {
                return site;
            }
        }
        return null;
    }

    private class SiteIndexingTask extends RecursiveAction {
        private final String url;
        private final String baseUrl;
        private final SiteEntity siteEntity;
        private final Set<String> visitedUrls;

        public SiteIndexingTask(String url, String baseUrl, SiteEntity siteEntity, Set<String> visitedUrls) {
            this.url = url;
            this.baseUrl = baseUrl;
            this.siteEntity = siteEntity;
            this.visitedUrls = visitedUrls;
        }

        @Override
        protected void compute() {
            if (!isIndexing.get() || !visitedUrls.add(url)) {
                return;
            }

            try {
                String path = url.replace(baseUrl, "");
                if (path.isEmpty()) {
                    path = "/";
                }

                indexSinglePage(siteEntity, url, path);

                Document doc = Jsoup.connect(url)
                        .userAgent("Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")
                        .referrer("http://www.google.com")
                        .timeout(10000)
                        .ignoreHttpErrors(true)
                        .get();

                Elements links = doc.select("a[href]");
                List<SiteIndexingTask> tasks = new ArrayList<>();

                for (Element link : links) {
                    String absUrl = link.absUrl("href");
                    if (isValidUrl(absUrl)) {
                        tasks.add(new SiteIndexingTask(absUrl, baseUrl, siteEntity, visitedUrls));
                    }
                }

                invokeAll(tasks);

            } catch (SSLHandshakeException e) {
                log.debug("SSL ошибка при обработке URL (пропускаем): {}", url);
            } catch (UnsupportedMimeTypeException e) {
                log.debug("Неподдерживаемый тип файла при обработке URL (пропускаем): {}", url);
            } catch (SocketTimeoutException e) {
                log.debug("Таймаут при обработке URL (пропускаем): {}", url);
            } catch (Exception e) {
                log.debug("Ошибка при обработке URL: {}", url);
            }
        }

        private boolean isValidUrl(String url) {
            return url.startsWith(baseUrl)
                    && !url.contains("#")
                    && !url.matches(".*\\.(jpg|jpeg|png|gif|webp|svg|ico|bmp|tiff|pdf|zip|rar|7z|tar|gz|doc|docx|xls|xlsx|ppt|pptx|mp3|mp4|avi|mov|wmv|flv|css|js)$");
        }
    }
}