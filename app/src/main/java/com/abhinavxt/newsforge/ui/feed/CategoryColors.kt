package com.abhinavxt.newsforge.ui.feed

import androidx.compose.ui.graphics.Color
import com.abhinavxt.newsforge.core.model.Category
import com.abhinavxt.newsforge.ui.theme.CatDeal
import com.abhinavxt.newsforge.ui.theme.CatGlobal
import com.abhinavxt.newsforge.ui.theme.CatNeutral
import com.abhinavxt.newsforge.ui.theme.CatOrder
import com.abhinavxt.newsforge.ui.theme.CatPolicy
import com.abhinavxt.newsforge.ui.theme.CatRegulatory
import com.abhinavxt.newsforge.ui.theme.CatResults

/**
 * Accent per category.
 *
 * Deliberately fewer colours than categories: related events share a hue so the list
 * reads as a handful of distinguishable kinds rather than fourteen. The colour is never
 * the only signal — every card also carries the category label in text.
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
        Category.MANAGEMENT, Category.OTHER -> CatNeutral
    }
