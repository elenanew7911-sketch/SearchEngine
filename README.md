# Поисковый движок

Локальный поисковый движок для индексации и поиска по веб-сайтам.

## Описание проекта

Spring Boot приложение, которое индексирует веб-сайты и предоставляет API для поиска по ним. Движок использует многопоточную индексацию, лемматизацию текста и расчет релевантности для точного поиска.

## Технологический стек

- **Java 17**
- **Spring Boot 2.7.1**
- **MySQL 8.0**
- **Hibernate/JPA** - работа с БД
- **JSoup** - парсинг веб-страниц
- **Apache Lucene Morphology** - лемматизация
- **Maven** - сборка проекта
- **Lombok** - упрощение кода

## Функциональность

### Веб-интерфейс
- **Dashboard** - статистика по всем сайтам
- **Management** - управление индексацией
- **Search** - поиск по проиндексированным сайтам

### API
- `GET /api/statistics` - получение статистики
- `GET /api/startIndexing` - запуск полной индексации
- `GET /api/stopIndexing` - остановка индексации
- `POST /api/indexPage` - индексация отдельной страницы
- `GET /api/search` - поиск по запросу

## Требования

- **JDK 17** или выше
- **MySQL 8.0** или выше
- **Maven 3.6** или выше
- Минимум **4GB RAM**

## Установка и запуск

### 1. Клонирование репозитория

```bash
git clone <repository-url>
cd Adaptation
```

### 2. Создание базы данных

```sql
CREATE DATABASE search_engine;
```

### 3. Настройка конфигурации

Отредактируйте `application.yaml` **в корне проекта**:

```yaml
server:
  port: 8080

spring:
  datasource:
    username: root           # ваш логин MySQL
    password: your_password  # ваш пароль MySQL
    url: jdbc:mysql://localhost:3306/search_engine?createDatabaseIfNotExist=true&useSSL=false&requireSSL=false&allowPublicKeyRetrieval=true&serverTimezone=UTC

indexing-settings:
  sites:
    - url: https://playback.ru
      name: PlayBack.Ru
    # добавьте свои сайты
```

**Важно:** Все локальные библиотеки находятся в папке `lib/` в корне проекта и автоматически подключаются через `pom.xml`.

### 4. Сборка проекта

```bash
mvn clean install
```

### 5. Запуск приложения

```bash
mvn spring-boot:run
```

Или запустите JAR файл:

```bash
java -jar target/SearchEngine-1.0-SNAPSHOT.jar
```

### 6. Доступ к приложению

Откройте в браузере: **http://localhost:8080**

## Использование

### Запуск индексации

1. Перейдите на вкладку **MANAGEMENT**
2. Нажмите кнопку **START INDEXING**
3. Дождитесь завершения индексации (статус INDEXED на вкладке DASHBOARD)

### Поиск

1. Перейдите на вкладку **SEARCH**
2. Введите поисковый запрос
3. Выберите сайт (или оставьте "All sites")
4. Нажмите **SEARCH**

### API примеры

**Запуск индексации:**
```bash
curl http://localhost:8080/api/startIndexing
```

**Поиск:**
```bash
curl "http://localhost:8080/api/search?query=java&limit=10"
```

**Статистика:**
```bash
curl http://localhost:8080/api/statistics
```

## Структура проекта

```
Adaptation/
├── lib/                           # Локальные JAR библиотеки
│   ├── lucene-core-8.11.0.jar
│   ├── lucene-analyzers-common-8.11.0.jar
│   ├── morphology-1.5.jar
│   ├── dictionary-reader-1.5.jar
│   ├── english-1.5.jar
│   ├── morph-1.5.jar
│   └── russian-1.5.jar
├── application.yaml               # Конфигурация приложения (в корне!)
├── pom.xml                        # Maven конфигурация
└── src/main/java/searchengine/
    ├── config/                    # Конфигурация Spring
    ├── controllers/               # REST контроллеры
    ├── dto/                       # Data Transfer Objects
    ├── model/                     # Entity классы (БД)
    ├── repository/                # JPA репозитории
    ├── services/                  # Бизнес-логика
    │   ├── LemmaService.java           # Лемматизация
    │   ├── IndexingServiceImpl.java    # Индексация
    │   ├── SearchServiceImpl.java      # Поиск
    │   └── StatisticsServiceImpl.java  # Статистика
    └── Application.java           # Точка входа
```

### Важные файлы конфигурации:
- **application.yaml** - находится в **корне проекта**, содержит порт приложения и настройки MySQL
- **pom.xml** - настроен на автоматическое подключение библиотек из папки `lib/`
- **lib/** - все необходимые JAR библиотеки для морфологии и Lucene

## Архитектура

### База данных (4 таблицы):
- **site** - информация о сайтах
- **page** - проиндексированные страницы
- **lemma** - словарь лемм
- **search_index** - поисковый индекс

### Алгоритм индексации:
1. Многопоточный обход страниц (ForkJoinPool)
2. Получение HTML через JSoup
3. Извлечение текста и лемматизация
4. Сохранение в БД с подсчетом частоты

### Алгоритм поиска:
1. Преобразование запроса в леммы
2. Фильтрация слишком частых слов
3. Поиск страниц с пересечением лемм
4. Расчет релевантности
5. Генерация сниппетов
6. Сортировка и постраничная выдача

## Особенности реализации

- **Многопоточность** - ForkJoinPool для параллельного обхода
- **Лемматизация** - Apache Lucene Morphology (русский язык)
- **Релевантность** - TF (term frequency) на базе rank
- **Сниппеты** - автоматическая генерация с подсветкой
- **Защита от перегрузки** - задержки между запросами
- **User-Agent** - корректная идентификация бота

## Возможные проблемы и решения

### Ошибка подключения к БД
```
Проверьте:
- MySQL запущен
- База данных создана
- Логин/пароль верны
- Порт 3306 доступен
```

### Ошибка загрузки библиотек морфологии
```
Проверьте:
- Все JAR файлы находятся в папке lib/
- В pom.xml корректно указаны пути к библиотекам
- Выполните: mvn clean install
```

### Ошибка "application.yaml not found"
```
Проверьте:
- Файл application.yaml находится в корне проекта (рядом с pom.xml)
- При сборке Maven автоматически копирует его в target/classes
```

### OutOfMemoryError
```
Увеличьте память JVM:
java -Xmx2g -jar target/SearchEngine-1.0-SNAPSHOT.jar
```

## Лицензия

Учебный проект Skillbox

## Автор

Разработано в рамках итогового проекта курса "Java-разработчик"
