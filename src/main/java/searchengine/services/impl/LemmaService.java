package searchengine.services.impl;

import lombok.extern.slf4j.Slf4j;
import org.apache.lucene.morphology.LuceneMorphology;
import org.apache.lucene.morphology.english.EnglishLuceneMorphology;
import org.apache.lucene.morphology.russian.RussianLuceneMorphology;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.util.*;

@Service
@Slf4j
public class LemmaService {

    private final LuceneMorphology russianMorphology;
    private final LuceneMorphology englishMorphology;
    private static final String[] RUSSIAN_SERVICE_PARTS = {"МЕЖД", "ПРЕДЛ", "СОЮЗ", "ЧАСТ"};
    private static final String[] ENGLISH_SERVICE_PARTS = {"PREP", "CONJ", "PART", "ARTICLE", "INT"};

    public LemmaService() {
        try {
            this.russianMorphology = new RussianLuceneMorphology();
            this.englishMorphology = new EnglishLuceneMorphology();
        } catch (IOException e) {
            log.error("Ошибка инициализации лемматизатора", e);
            throw new RuntimeException("Не удалось инициализировать лемматизатор", e);
        }
    }

    public Map<String, Integer> collectLemmas(String text) {
        Map<String, Integer> lemmas = new HashMap<>();
        String[] words = text.toLowerCase().replaceAll("[^а-яёa-z\\s]", " ").trim().split("\\s+");

        for (String word : words) {
            if (word.length() < 3) {
                continue;
            }

            try {
                LuceneMorphology morphology;
                String[] serviceParts;

                if (isRussian(word)) {
                    morphology = russianMorphology;
                    serviceParts = RUSSIAN_SERVICE_PARTS;
                } else if (isEnglish(word)) {
                    morphology = englishMorphology;
                    serviceParts = ENGLISH_SERVICE_PARTS;
                } else {
                    continue;
                }

                List<String> wordBaseForms = morphology.getMorphInfo(word);
                if (isServiceWord(wordBaseForms, serviceParts)) {
                    continue;
                }

                List<String> normalForms = morphology.getNormalForms(word);
                if (!normalForms.isEmpty()) {
                    String lemma = normalForms.get(0);
                    lemmas.put(lemma, lemmas.getOrDefault(lemma, 0) + 1);
                }
            } catch (Exception e) {
                log.debug("Не удалось обработать слово: {}", word);
            }
        }

        return lemmas;
    }

    private boolean isServiceWord(List<String> morphInfo, String[] serviceParts) {
        if (morphInfo.isEmpty()) {
            return true;
        }

        String info = morphInfo.get(0).toUpperCase();
        for (String part : serviceParts) {
            if (info.contains(part)) {
                return true;
            }
        }
        return false;
    }

    private boolean isRussian(String word) {
        return word.matches(".*[а-яё].*");
    }

    private boolean isEnglish(String word) {
        return word.matches(".*[a-z].*");
    }

    public Set<String> getLemmasFromQuery(String query) {
        Set<String> lemmas = new HashSet<>();
        String[] words = query.toLowerCase().replaceAll("[^а-яёa-z\\s]", " ").trim().split("\\s+");

        for (String word : words) {
            if (word.length() < 3) {
                continue;
            }

            try {
                LuceneMorphology morphology;
                String[] serviceParts;

                if (isRussian(word)) {
                    morphology = russianMorphology;
                    serviceParts = RUSSIAN_SERVICE_PARTS;
                } else if (isEnglish(word)) {
                    morphology = englishMorphology;
                    serviceParts = ENGLISH_SERVICE_PARTS;
                } else {
                    continue;
                }

                List<String> wordBaseForms = morphology.getMorphInfo(word);
                if (isServiceWord(wordBaseForms, serviceParts)) {
                    continue;
                }

                List<String> normalForms = morphology.getNormalForms(word);
                if (!normalForms.isEmpty()) {
                    lemmas.add(normalForms.get(0));
                }
            } catch (Exception e) {
                log.debug("Не удалось обработать слово запроса: {}", word);
            }
        }

        return lemmas;
    }
}