package com.abhinavxt.newsforge.ui.feed

import androidx.compose.ui.graphics.Color
import com.abhinavxt.newsforge.core.model.Category
import com.abhinavxt.newsforge.core.model.CategoryGroup
import com.abhinavxt.newsforge.ui.theme.CatDeal
import com.abhinavxt.newsforge.ui.theme.CatGlobal
import com.abhinavxt.newsforge.ui.theme.CatNeutral
import com.abhinavxt.newsforge.ui.theme.CatOrder
import com.abhinavxt.newsforge.ui.theme.CatPolicy
import com.abhinavxt.newsforge.ui.theme.CatRegulatory
import com.abhinavxt.newsforge.ui.theme.CatResults
import com.abhinavxt.newsforge.ui.theme.CatViews

/**
 * Accent per category.
 *
 * Deliberately fewer colours than categories: related events share a hue so the list
 * reads as a handful of distinguishable kinds rather than fourteen. The colour is never
 * the only signal — it always sits beside the name in text, on the card when the list is
 * flat and on the section heading when it is grouped.
 */
val Category.accent: Color
    get() = when (this) {
        Category.REGULATORY -> CatRegulatory
        Category.MERGER, Category.BLOCK_DEAL -> CatDeal
        Category.ORDER_WIN -> CatOrder
        Category.RESULTS, Category.RATING, Category.FUNDRAISE -> CatResults
        // Dividends share the calendar screen's hue for the same event, so an ex-date
        // does not change colour depending on which screen you meet it on.
        Category.DIVIDEND, Category.POLICY, Category.MACRO -> CatPolicy
        Category.GLOBAL, Category.GEOPOLITICS, Category.COMMODITY -> CatGlobal
        // Its own hue: a broker's view should be distinguishable at a glance from the
        // events around it, which is the entire reason it has its own bucket.
        Category.PRICE_TARGET -> CatViews
        Category.MANAGEMENT, Category.OTHER -> CatNeutral
        // World topics reuse the same handful of hues. They never share a screen with the
        // market categories, so a red that means "regulatory" there can mean "politics"
        // here without the two being read against each other.
        Category.POLITICS, Category.LAW -> CatRegulatory
        Category.INDIA, Category.CITIES, Category.EDUCATION -> CatPolicy
        Category.WORLD, Category.TRAVEL -> CatGlobal
        Category.SCIENCE, Category.AUTO -> CatResults
        Category.TECHNOLOGY, Category.GAMING -> CatDeal
        Category.HEALTH, Category.ENVIRONMENT, Category.FOOD -> CatOrder
        Category.SPORTS, Category.CULTURE, Category.ENTERTAINMENT, Category.LIFESTYLE -> CatViews
        Category.HEADLINES, Category.OPINION -> CatNeutral
    }

/**
 * Accent per section heading.
 *
 * One hue per coarse bucket, not per category, because a heading covers several: a Movers
 * section holds order wins and regulatory action, which are orange and green on a card.
 * Picking the bucket's own colour keeps the heading from claiming to be one of them.
 */
val CategoryGroup.accent: Color
    get() = when (this) {
        CategoryGroup.MOVERS -> CatDeal
        CategoryGroup.RESULTS -> CatResults
        CategoryGroup.POLICY -> CatPolicy
        CategoryGroup.GLOBAL -> CatGlobal
        CategoryGroup.VIEWS -> CatViews
        CategoryGroup.OTHER, CategoryGroup.NEWS -> CatNeutral
    }
