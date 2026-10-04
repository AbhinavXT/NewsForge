@file:OptIn(
    androidx.compose.material3.ExperimentalMaterial3Api::class,
    androidx.compose.foundation.layout.ExperimentalLayoutApi::class,
    kotlinx.coroutines.FlowPreview::class,
)

package com.abhinavxt.newsforge.ui.reader

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withLink
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.net.toUri
import coil3.compose.AsyncImage
import com.abhinavxt.newsforge.R
import com.abhinavxt.newsforge.core.quote.ReactionBasis
import com.abhinavxt.newsforge.core.reader.Mentions
import com.abhinavxt.newsforge.core.reader.ReaderArticle
import com.abhinavxt.newsforge.core.reader.ReaderBlock
import com.abhinavxt.newsforge.core.reader.ReaderFont
import com.abhinavxt.newsforge.core.reader.ReaderSettings
import com.abhinavxt.newsforge.core.reader.ReaderSpacing
import com.abhinavxt.newsforge.core.tag.SymbolSpan
import com.abhinavxt.newsforge.data.ReaderResult
import com.abhinavxt.newsforge.data.ReadingPosition
import com.abhinavxt.newsforge.data.db.CoverageRow
import com.abhinavxt.newsforge.ui.chart.ShimmerBlock
import com.abhinavxt.newsforge.ui.components.EmptyState
import com.abhinavxt.newsforge.ui.components.GroupCard
import com.abhinavxt.newsforge.ui.components.GroupDivider
import com.abhinavxt.newsforge.ui.components.IconCircleButton
import com.abhinavxt.newsforge.ui.components.ScreenGutter
import com.abhinavxt.newsforge.ui.components.ScreenHeader
import com.abhinavxt.newsforge.ui.components.SegmentedControl
import com.abhinavxt.newsforge.ui.components.TonalButton
import com.abhinavxt.newsforge.ui.feed.PriceBook
import com.abhinavxt.newsforge.ui.feed.StoryPresentation
import com.abhinavxt.newsforge.ui.theme.Chalk500
import com.abhinavxt.newsforge.ui.theme.family
import com.abhinavxt.newsforge.ui.theme.QuoteDown
import com.abhinavxt.newsforge.ui.theme.QuoteUp
import com.abhinavxt.newsforge.ui.util.RelativeTime
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged

/**
 * An article as text, inside the app.
 *
 * The browser stays one tap away in the header, and is the only way forward when the
 * page could not be reduced — paywalled and script-rendered pages are exactly the ones a
 * reader mode cannot do, and pretending otherwise would show a reader two sentences.
 *
 * @param prices the feed's current quotes, for what the market has done since the story.
 */
@Composable
fun ReaderScreen(
    url: String,
    state: ReaderUiState,
    context: StoryContext,
    settings: ReaderSettings,
    offline: Boolean,
    prices: PriceBook,
    nowMillis: Long,
    onOpenInBrowser: (String) -> Unit,
    onShare: (ReaderArticle?) -> Unit,
    onToggleSave: () -> Unit,
    onOpenSymbol: (String) -> Unit,
    onOpenCoverage: (String) -> Unit,
    onSettingsChange: (ReaderSettings) -> Unit,
    onPositionChange: (ReadingPosition) -> Unit,
    onRetry: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val done = state as? ReaderUiState.Done
    val ready = done?.result as? ReaderResult.Ready
    val article = ready?.article
    val host = remember(url) { url.toUri().host?.removePrefix("www.") }
    // The publisher's page where one was resolved, so the browser never lands on a
    // redirect service's interstitial.
    val browserUrl = article?.url ?: (done?.result as? ReaderResult.NotReadable)?.url ?: url
    var showSettings by rememberSaveable { mutableStateOf(false) }
    val listState = done?.let { rememberReaderListState(url, it.position) }
    val progress by remember(listState) {
        derivedStateOf { listState?.let(::readFraction) ?: 0f }
    }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            Column {
                ScreenHeader(
                    title = article?.siteName ?: host ?: "Article",
                    subtitle = article?.let {
                        buildString {
                            append("${it.minutes} min read")
                            if (offline) append(" · saved offline")
                        }
                    },
                    onBack = onBack,
                    actions = {
                        if (article != null) {
                            IconCircleButton(
                                icon = R.drawable.ic_text_size,
                                contentDescription = "Text settings",
                                onClick = { showSettings = true },
                            )
                        }
                        context.story?.let { story ->
                            IconCircleButton(
                                icon = if (story.saved) R.drawable.ic_star else R.drawable.ic_star_border,
                                contentDescription = if (story.saved) "Remove from saved" else "Save",
                                tint = if (story.saved) {
                                    MaterialTheme.colorScheme.primary
                                } else {
                                    MaterialTheme.colorScheme.onSurface
                                },
                                onClick = onToggleSave,
                            )
                        }
                        IconCircleButton(
                            icon = R.drawable.ic_share,
                            contentDescription = "Share",
                            onClick = { onShare(article) },
                        )
                        IconCircleButton(
                            icon = R.drawable.ic_open,
                            contentDescription = "Open in browser",
                            onClick = { onOpenInBrowser(browserUrl) },
                        )
                    },
                )
                if (article != null) {
                    LinearProgressIndicator(
                        progress = { progress },
                        modifier = Modifier.fillMaxWidth().height(2.dp),
                        color = MaterialTheme.colorScheme.primary,
                        trackColor = Color.Transparent,
                        drawStopIndicator = {},
                        gapSize = 0.dp,
                    )
                }
            }
        },
    ) { padding ->
        Box(Modifier.padding(padding).fillMaxSize()) {
            when (state) {
                ReaderUiState.Loading -> ReaderSkeleton()
                is ReaderUiState.Done -> when (val result = state.result) {
                    is ReaderResult.Ready -> ArticleBody(
                        article = result.article,
                        mentions = state.mentions,
                        context = context,
                        settings = settings,
                        prices = prices,
                        nowMillis = nowMillis,
                        listState = listState!!,
                        onOpenSymbol = onOpenSymbol,
                        onOpenCoverage = onOpenCoverage,
                        onPositionChange = onPositionChange,
                    )
                    // A file, not a page: the reader has no business showing it, so it
                    // goes to the browser and steps out of the way, leaving the feed
                    // underneath for when the reader comes back.
                    is ReaderResult.NotAPage -> {
                        ReaderSkeleton()
                        LaunchedEffect(result) {
                            onOpenInBrowser(result.url)
                            onBack()
                        }
                    }
                    is ReaderResult.NotReadable -> EmptyState(
                        icon = R.drawable.ic_news,
                        title = "This page can't be shown in the reader",
                        body = "It may be paywalled or built by script. The original will " +
                            "still open in your browser.",
                        actionLabel = "Open in browser",
                        onAction = { onOpenInBrowser(browserUrl) },
                    )
                    is ReaderResult.Failed -> Column {
                        EmptyState(
                            icon = R.drawable.ic_refresh,
                            title = "Couldn't load the article",
                            body = result.message,
                            actionLabel = "Try again",
                            onAction = onRetry,
                            modifier = Modifier.weight(1f),
                        )
                        TonalButton(
                            text = "Open in browser",
                            onClick = { onOpenInBrowser(browserUrl) },
                            icon = R.drawable.ic_open,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = ScreenGutter, vertical = 16.dp),
                        )
                    }
                }
            }
        }
    }

    if (showSettings) {
        ReaderSettingsSheet(
            settings = settings,
            onChange = onSettingsChange,
            onDismiss = { showSettings = false },
        )
    }
}

/**
 * The list's state, opened where the reader left off.
 *
 * Keyed on the URL, so returning to the same article reuses it, and created only once the
 * article is loaded — an initial index given to an empty list is clamped to zero and lost.
 */
@Composable
private fun rememberReaderListState(url: String, position: ReadingPosition?): LazyListState =
    rememberSaveable(url, saver = LazyListState.Saver) {
        LazyListState(position?.item ?: 0, position?.offset ?: 0)
    }

/** How far through the list the reader is, 0 to 1, counting the last item as the end. */
private fun readFraction(state: LazyListState): Float {
    val info = state.layoutInfo
    val total = info.totalItemsCount
    if (total == 0) return 0f
    val last = info.visibleItemsInfo.lastOrNull() ?: return 0f
    if (last.index == total - 1 && last.offset + last.size <= info.viewportEndOffset) return 1f
    val first = info.visibleItemsInfo.first()
    val within = if (first.size > 0) -first.offset.toFloat() / first.size else 0f
    return ((first.index + within.coerceIn(0f, 1f)) / total).coerceIn(0f, 1f)
}

@Composable
private fun ArticleBody(
    article: ReaderArticle,
    mentions: Map<Int, List<SymbolSpan>>,
    context: StoryContext,
    settings: ReaderSettings,
    prices: PriceBook,
    nowMillis: Long,
    listState: LazyListState,
    onOpenSymbol: (String) -> Unit,
    onOpenCoverage: (String) -> Unit,
    onPositionChange: (ReadingPosition) -> Unit,
) {
    // Debounced, so a fling writes one row when it settles rather than one per frame.
    LaunchedEffect(listState) {
        snapshotFlow {
            ReadingPosition(
                listState.firstVisibleItemIndex,
                listState.firstVisibleItemScrollOffset,
                readFraction(listState),
            )
        }
            .distinctUntilChanged()
            .debounce(POSITION_SAVE_DELAY_MS)
            .collect(onPositionChange)
    }

    val prose = proseStyle(settings)
    val symbols = remember(context.story, mentions) {
        (context.story?.symbols.orEmpty() + Mentions.symbols(mentions)).distinct()
    }

    LazyColumn(
        state = listState,
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(
            start = ScreenGutter + 4.dp,
            end = ScreenGutter + 4.dp,
            top = 8.dp,
            bottom = 32.dp,
        ),
    ) {
        article.leadImage?.let { image ->
            item(key = "lead") {
                AsyncImage(
                    model = image,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .fillMaxWidth()
                        .aspectRatio(16f / 9f)
                        .clip(MaterialTheme.shapes.medium)
                        .background(MaterialTheme.colorScheme.surfaceVariant),
                )
                Spacer(Modifier.height(16.dp))
            }
        }
        item(key = "title") {
            Text(
                text = article.title,
                style = MaterialTheme.typography.headlineMedium.copy(
                    fontSize = 26.sp,
                    lineHeight = 32.sp,
                    fontFamily = settings.font.family,
                ),
                color = MaterialTheme.colorScheme.onBackground,
            )
            val meta = listOfNotNull(
                article.byline,
                context.story?.let { RelativeTime.ago(it.publishedAt, nowMillis) },
            )
            if (meta.isNotEmpty()) {
                Text(
                    text = meta.joinToString(" · "),
                    style = MaterialTheme.typography.labelMedium,
                    color = Chalk500,
                    modifier = Modifier.padding(top = 10.dp),
                )
            }
            Spacer(Modifier.height(16.dp))
        }
        if (symbols.isNotEmpty()) {
            item(key = "market") {
                MarketStrip(symbols, context, prices, nowMillis, onOpenSymbol)
                Spacer(Modifier.height(20.dp))
            }
        }
        itemsIndexed(article.blocks) { index, block ->
            Block(block, mentions[index].orEmpty(), prose, onOpenSymbol)
        }
        if (context.coverage.isNotEmpty()) {
            item(key = "coverage") {
                Coverage(context.coverage, nowMillis, onOpenCoverage)
            }
        }
    }
}

/**
 * The companies in the story, with the day's move, and what has happened since publication.
 *
 * Above the body rather than beside it: the question a market reader brings to an article
 * is whether the market has already acted on it, and that should be answered before the
 * first paragraph, not after the last.
 */
@Composable
private fun MarketStrip(
    symbols: List<String>,
    context: StoryContext,
    prices: PriceBook,
    nowMillis: Long,
    onOpenSymbol: (String) -> Unit,
) {
    Column(Modifier.fillMaxWidth()) {
        Text(
            text = "IN THIS STORY",
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.Bold,
            color = Chalk500,
            modifier = Modifier.padding(bottom = 8.dp),
        )
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            for (symbol in symbols.take(MAX_STRIP_SYMBOLS)) {
                val change = prices[symbol]?.changePercent
                Row(
                    Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant)
                        .clickable { onOpenSymbol(symbol) }
                        .padding(horizontal = 9.dp, vertical = 5.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = symbol,
                        style = MaterialTheme.typography.labelMedium,
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    if (change != null) {
                        Text(
                            text = StoryPresentation.changeLabel(change),
                            style = MaterialTheme.typography.labelMedium,
                            fontFamily = FontFamily.Monospace,
                            color = if (change < 0) QuoteDown else QuoteUp,
                        )
                    }
                }
            }
        }
        val story = context.story
        story?.let { prices.reactionFor(it) }?.let { reaction ->
            val rising = reaction.percent >= 0
            Text(
                text = when (reaction.basis) {
                    ReactionBasis.PUBLICATION ->
                        "${reaction.symbol} ${StoryPresentation.changeLabel(reaction.percent)} " +
                            "since this was published, " +
                            RelativeTime.ago(story.publishedAt, nowMillis)
                    ReactionBasis.PREVIOUS_CLOSE ->
                        "${reaction.symbol} ${StoryPresentation.changeLabel(reaction.percent)} " +
                            "on the previous close"
                },
                style = MaterialTheme.typography.labelMedium,
                color = if (rising) QuoteUp else QuoteDown,
                modifier = Modifier
                    .padding(top = 10.dp)
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(10.dp))
                    .background((if (rising) QuoteUp else QuoteDown).copy(alpha = 0.10f))
                    .padding(horizontal = 12.dp, vertical = 8.dp),
            )
        }
    }
}

/** The same story from the other outlets that carried it. */
@Composable
private fun Coverage(rows: List<CoverageRow>, nowMillis: Long, onOpen: (String) -> Unit) {
    Column(Modifier.padding(top = 16.dp)) {
        Text(
            text = "ALSO COVERED BY ${rows.size} ${if (rows.size == 1) "OUTLET" else "OUTLETS"}",
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.Bold,
            color = Chalk500,
            modifier = Modifier.padding(bottom = 8.dp),
        )
        GroupCard {
            rows.forEachIndexed { index, row ->
                if (index > 0) GroupDivider()
                Column(
                    Modifier
                        .fillMaxWidth()
                        .clickable { onOpen(row.link) }
                        .padding(horizontal = 14.dp, vertical = 12.dp),
                ) {
                    Text(
                        text = "${row.sourceName} · ${RelativeTime.format(row.publishedAt, nowMillis)}",
                        style = MaterialTheme.typography.labelSmall,
                        color = Chalk500,
                    )
                    Text(
                        text = row.title,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(top = 2.dp),
                    )
                }
            }
        }
    }
}

/**
 * Prose wants the opposite of the app's scanning scale: larger, with room between lines,
 * so a long paragraph can be followed back to the start of the next line.
 */
@Composable
private fun proseStyle(settings: ReaderSettings): TextStyle = TextStyle(
    fontSize = settings.textSizeSp.sp,
    lineHeight = (settings.textSizeSp * settings.spacing.lineHeight).sp,
    letterSpacing = 0.sp,
    fontFamily = settings.font.family,
)

@Composable
private fun Block(
    block: ReaderBlock,
    spans: List<SymbolSpan>,
    prose: TextStyle,
    onOpenSymbol: (String) -> Unit,
) {
    val onBackground = MaterialTheme.colorScheme.onBackground
    val linkStyle = TextLinkStyles(
        SpanStyle(color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Medium)
    )
    val text = remember(block, spans, linkStyle) { linked(block.text, spans, linkStyle, onOpenSymbol) }
    when (block) {
        is ReaderBlock.Paragraph -> Text(
            text = text,
            style = prose,
            color = onBackground,
            modifier = Modifier.padding(bottom = 16.dp),
        )

        is ReaderBlock.Heading -> Text(
            text = block.text,
            style = MaterialTheme.typography.titleLarge.copy(fontFamily = prose.fontFamily),
            color = onBackground,
            modifier = Modifier.padding(top = 8.dp, bottom = 12.dp),
        )

        // Intrinsic height so the rule can fill it; a lazy item is otherwise unbounded.
        is ReaderBlock.Quote -> Row(Modifier.height(IntrinsicSize.Min).padding(bottom = 16.dp)) {
            Box(
                Modifier
                    .width(3.dp)
                    .fillMaxHeight()
                    .background(MaterialTheme.colorScheme.primary),
            )
            Text(
                text = text,
                style = prose.copy(fontWeight = FontWeight.Medium),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 14.dp),
            )
        }

        is ReaderBlock.ListItem -> Row(Modifier.padding(start = 4.dp, bottom = 10.dp)) {
            Text(
                text = block.marker,
                style = prose,
                color = Chalk500,
                modifier = Modifier.width(24.dp),
            )
            Text(text = text, style = prose, color = onBackground)
        }

        is ReaderBlock.Image -> Column(Modifier.padding(bottom = 16.dp)) {
            AsyncImage(
                model = block.url,
                contentDescription = block.text.ifEmpty { null },
                contentScale = ContentScale.FillWidth,
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(MaterialTheme.shapes.small)
                    .background(MaterialTheme.colorScheme.surfaceVariant),
            )
            if (block.text.isNotEmpty()) {
                Text(
                    text = block.text,
                    style = MaterialTheme.typography.labelMedium,
                    color = Chalk500,
                    modifier = Modifier.padding(top = 6.dp),
                )
            }
        }
    }
}

/** [text] with each span made a link to its company. */
private fun linked(
    text: String,
    spans: List<SymbolSpan>,
    style: TextLinkStyles,
    onOpenSymbol: (String) -> Unit,
): AnnotatedString {
    if (spans.isEmpty()) return AnnotatedString(text)
    return buildAnnotatedString {
        var at = 0
        for (span in spans.sortedBy { it.start }) {
            if (span.start < at || span.end > text.length) continue
            append(text, at, span.start)
            withLink(LinkAnnotation.Clickable(span.symbol, style) { onOpenSymbol(span.symbol) }) {
                append(text, span.start, span.end)
            }
            at = span.end
        }
        append(text, at, text.length)
    }
}

@Composable
private fun ReaderSettingsSheet(
    settings: ReaderSettings,
    onChange: (ReaderSettings) -> Unit,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = MaterialTheme.colorScheme.surface,
    ) {
        Column(
            Modifier
                .padding(horizontal = 20.dp)
                .padding(bottom = 28.dp),
            verticalArrangement = Arrangement.spacedBy(18.dp),
        ) {
            Text(
                text = "Text",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                IconCircleButton(
                    icon = R.drawable.ic_text_size,
                    contentDescription = "Smaller text",
                    onClick = { onChange(settings.shrink()) },
                    enabled = settings.canShrink,
                    size = 36.dp,
                )
                // The size itself, set in the chosen face, is the clearest preview.
                Text(
                    text = "The quick brown fox",
                    style = proseStyle(settings).copy(lineHeight = 0.sp),
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                IconCircleButton(
                    icon = R.drawable.ic_text_size,
                    contentDescription = "Larger text",
                    onClick = { onChange(settings.grow()) },
                    enabled = settings.canGrow,
                    size = 44.dp,
                )
            }
            SettingRow("Font") {
                SegmentedControl(
                    options = ReaderFont.entries.map { it.label },
                    selectedIndex = settings.font.ordinal,
                    onSelect = { onChange(settings.copy(font = ReaderFont.entries[it])) },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            SettingRow("Line spacing") {
                SegmentedControl(
                    options = ReaderSpacing.entries.map { it.label },
                    selectedIndex = settings.spacing.ordinal,
                    onSelect = { onChange(settings.copy(spacing = ReaderSpacing.entries[it])) },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }
}

@Composable
private fun SettingRow(label: String, content: @Composable () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            text = label.uppercase(),
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.Bold,
            color = Chalk500,
        )
        content()
    }
}

@Composable
private fun ReaderSkeleton() {
    Column(Modifier.padding(horizontal = ScreenGutter + 4.dp, vertical = 8.dp)) {
        ShimmerBlock(
            Modifier.fillMaxWidth().aspectRatio(16f / 9f),
            shape = MaterialTheme.shapes.medium,
        )
        Spacer(Modifier.height(16.dp))
        ShimmerBlock(Modifier.fillMaxWidth().height(26.dp))
        Spacer(Modifier.height(8.dp))
        ShimmerBlock(Modifier.fillMaxWidth(0.6f).height(26.dp))
        Spacer(Modifier.height(28.dp))
        repeat(3) {
            repeat(4) {
                ShimmerBlock(Modifier.fillMaxWidth().height(14.dp))
                Spacer(Modifier.height(10.dp))
            }
            ShimmerBlock(Modifier.fillMaxWidth(0.4f).height(14.dp))
            Spacer(Modifier.height(24.dp))
        }
    }
}

/** Long enough for a fling to settle; short enough that leaving straight after is caught. */
private const val POSITION_SAVE_DELAY_MS = 600L

/** A round-up naming twenty companies would bury the article under its own tags. */
private const val MAX_STRIP_SYMBOLS = 8
