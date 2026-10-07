package com.abhinavxt.newsforge.ui.listen

import com.abhinavxt.newsforge.core.listen.Narration
import com.abhinavxt.newsforge.core.reader.ReaderArticle
import com.abhinavxt.newsforge.core.reader.ReaderBlock
import com.abhinavxt.newsforge.data.model.ScoredArticle
import com.abhinavxt.newsforge.listen.ListenItem
import com.abhinavxt.newsforge.ui.feed.StoryPresentation

/** A story as listen mode reads it in a list: topic, headline, summary, outlet. */
fun ScoredArticle.toListenItem(): ListenItem = ListenItem(
    title = article.title,
    text = Narration.story(
        topic = article.category.label,
        title = article.title,
        summary = StoryPresentation.summaryOrNull(article),
        source = article.sourceName,
    ),
    clusterId = article.clusterId,
)

/** A whole article from the reader, body included. */
fun ReaderArticle.toListenItem(): ListenItem = ListenItem(
    title = title,
    text = Narration.article(
        title = title,
        site = siteName,
        paragraphs = blocks.filterNot { it is ReaderBlock.Image }.map { it.text },
    ),
)
