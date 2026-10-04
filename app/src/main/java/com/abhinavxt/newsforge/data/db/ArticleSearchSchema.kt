package com.abhinavxt.newsforge.data.db

/**
 * The full-text index over articles, as the exact SQL Room generates for it.
 *
 * Plain strings in a file with no Room imports, for one reason: the migration and the
 * verification of the migration have to run the *same* text. Room builds a fresh install
 * from the `@Fts4` entity and an upgraded one from these statements, and if the two ever
 * differ an upgraded install fails schema validation at launch — on the user's phone,
 * after the update, which is the worst possible place to find out. `ArticleSearchSchemaTest`
 * compares these strings with the schema Room exports at build time, so the drift fails a
 * test instead.
 *
 * External content (`content=article`) rather than a standalone copy: the index stores
 * only its token lists and points back at `article` by rowid, so headlines are not
 * stored twice. The triggers keep it in step. They are sound here because every write to
 * `article` is an INSERT OR IGNORE, an UPDATE or a DELETE — never a REPLACE, whose
 * implicit delete fires no triggers unless recursive triggers are on, and would leave
 * index rows pointing at rowids that might later be reused by unrelated articles.
 */
object ArticleSearchSchema {

    const val TABLE = "article_fts"

    const val CREATE: String =
        "CREATE VIRTUAL TABLE IF NOT EXISTS `article_fts` USING FTS4(" +
            "`title` TEXT NOT NULL, `summary` TEXT, `sourceName` TEXT NOT NULL, " +
            "content=`article`)"

    val TRIGGERS: List<String> = listOf(
        "CREATE TRIGGER IF NOT EXISTS room_fts_content_sync_article_fts_BEFORE_UPDATE " +
            "BEFORE UPDATE ON `article` BEGIN DELETE FROM `article_fts` " +
            "WHERE `docid`=OLD.`rowid`; END",
        "CREATE TRIGGER IF NOT EXISTS room_fts_content_sync_article_fts_BEFORE_DELETE " +
            "BEFORE DELETE ON `article` BEGIN DELETE FROM `article_fts` " +
            "WHERE `docid`=OLD.`rowid`; END",
        "CREATE TRIGGER IF NOT EXISTS room_fts_content_sync_article_fts_AFTER_UPDATE " +
            "AFTER UPDATE ON `article` BEGIN INSERT INTO `article_fts`" +
            "(`docid`, `title`, `summary`, `sourceName`) " +
            "VALUES (NEW.`rowid`, NEW.`title`, NEW.`summary`, NEW.`sourceName`); END",
        "CREATE TRIGGER IF NOT EXISTS room_fts_content_sync_article_fts_AFTER_INSERT " +
            "AFTER INSERT ON `article` BEGIN INSERT INTO `article_fts`" +
            "(`docid`, `title`, `summary`, `sourceName`) " +
            "VALUES (NEW.`rowid`, NEW.`title`, NEW.`summary`, NEW.`sourceName`); END",
    )

    /**
     * Rebuilds the index from `article`.
     *
     * Run once by the migration, so every article stored before the upgrade becomes
     * searchable immediately rather than only the ones that arrive afterwards.
     */
    const val REBUILD: String = "INSERT INTO `article_fts`(`article_fts`) VALUES('rebuild')"

    /** Everything the migration runs, in order. */
    val MIGRATION: List<String> get() = listOf(CREATE) + TRIGGERS + REBUILD

    /**
     * Shrinks old stories to their headline.
     *
     * Past the feed's window, an unsaved story about a company nobody follows is kept for
     * one reason only: so it can be found again. That needs the headline, the outlet, the
     * date and the tags. It does not need the summary, which is usually the largest
     * column, or the clustering tokens, which are only ever read inside a two-day window.
     *
     * The guard on already-compacted rows is what keeps this cheap. Every UPDATE fires
     * the index triggers, so without it each sync would rewrite and re-index every old
     * row in the store — tens of thousands of them — to change nothing.
     *
     * Followed companies are left whole, for the same reason they always outlived the
     * feed: their timeline is only as useful as its depth.
     */
    const val COMPACT: String =
        "UPDATE article SET summary = NULL, tokens = '' " +
            "WHERE publishedAt < :cutoff AND saved = 0 " +
            "AND (summary IS NOT NULL OR tokens != '') " +
            "AND id NOT IN (" +
            "  SELECT s.articleId FROM article_symbol s " +
            "  JOIN watchlist w ON w.symbol = s.symbol)"

    /**
     * What a story row carries beyond the anchor, as every story query projects it.
     *
     * Shared by the two search queries below. The feed's own queries still spell it out,
     * and they are left alone here: touching four working queries to deduplicate them is
     * a separate change from adding search, and should be reviewable as one.
     */
    private const val STORY_COLUMNS: String =
        "(SELECT COUNT(DISTINCT b.sourceName) FROM article b " +
            " WHERE b.clusterId = a.clusterId) AS outletCount, " +
            "(SELECT GROUP_CONCAT(s) FROM (SELECT DISTINCT b.sourceName AS s " +
            " FROM article b WHERE b.clusterId = a.clusterId LIMIT :outlets)) AS sources, " +
            "(SELECT GROUP_CONCAT(DISTINCT b.tier) FROM article b " +
            " WHERE b.clusterId = a.clusterId) AS tiers, " +
            "(SELECT GROUP_CONCAT(DISTINCT b.category) FROM article b " +
            " WHERE b.clusterId = a.clusterId) AS categories, " +
            "(SELECT MAX(b.publishedAt) FROM article b " +
            " WHERE b.clusterId = a.clusterId) AS lastPublishedAt "

    /** Stories older than the window survive a search only if they were saved. */
    private const val IN_WINDOW: String =
        "a.clusterId = a.id AND (a.publishedAt >= :since OR a.saved = 1) "

    /**
     * Stories whose headline, summary or outlet matches, or which are tagged with the
     * ticker exactly.
     *
     * Matched against *every member* of a cluster and answered with the anchor. The
     * anchor is only whichever outlet arrived first; if the exchange filing is the member
     * that names the order size, a search for it should find the story, not miss it
     * because a rewrite happened to land two minutes earlier.
     *
     * Newest first and nothing else. A history search is asking "when did this happen",
     * and ordering by the feed's importance score — which decays by the hour — would
     * scatter a two-month-old match unpredictably among this morning's.
     */
    const val SEARCH_TEXT: String =
        "SELECT a.*, " + STORY_COLUMNS +
            "FROM article a WHERE " + IN_WINDOW +
            "AND a.id IN (" +
            "  SELECT m.clusterId FROM article m WHERE m.rowid IN (" +
            "    SELECT docid FROM article_fts WHERE article_fts MATCH :match) " +
            "  UNION " +
            "  SELECT m.clusterId FROM article m " +
            "  JOIN article_symbol s ON s.articleId = m.id WHERE s.symbol = :symbol) " +
            "ORDER BY a.publishedAt DESC LIMIT :limit"

    /**
     * The same, by ticker alone, for input that has no searchable words in it.
     *
     * "L&T" and "M&M" reduce to single letters, which the text match discards, and a
     * ticker search must still work for them. A second query rather than a sentinel term
     * that "matches nothing", because a sentinel is a bet that no headline will ever
     * contain it.
     */
    const val SEARCH_SYMBOL: String =
        "SELECT a.*, " + STORY_COLUMNS +
            "FROM article a WHERE " + IN_WINDOW +
            "AND a.id IN (" +
            "  SELECT m.clusterId FROM article m " +
            "  JOIN article_symbol s ON s.articleId = m.id WHERE s.symbol = :symbol) " +
            "ORDER BY a.publishedAt DESC LIMIT :limit"
}
