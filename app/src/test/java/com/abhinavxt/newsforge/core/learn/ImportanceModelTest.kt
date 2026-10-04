package com.abhinavxt.newsforge.core.learn

import com.abhinavxt.newsforge.core.model.Category
import com.abhinavxt.newsforge.core.model.SourceTier
import com.abhinavxt.newsforge.core.rank.MarketPhase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class ImportanceModelTest {

    private fun input(
        category: Category = Category.OTHER,
        tier: SourceTier = SourceTier.WIRE,
        isFiling: Boolean = false,
        outletCount: Int = 1,
        spanMinutes: Long = 0,
        symbolCount: Int = 1,
        title: String = "Something happened",
        phase: MarketPhase = MarketPhase.OPEN,
    ) = FeatureInput(
        category, tier, isFiling, outletCount, spanMinutes, symbolCount, title,
        summary = null, phase = phase,
    )

    @Test
    fun theFeatureNamesMatchTheVectorTheBuilderWrites() {
        // The failure mode of these two drifting apart is silent: training continues,
        // every weight lands on the wrong feature, nothing crashes.
        assertEquals(ImportanceFeatures.NAMES.size, ImportanceFeatures.of(input()).size)
        assertEquals(ImportanceFeatures.SIZE, ImportanceFeatures.of(input()).size)
    }

    @Test
    fun categoriesAndTiersAreOneHot() {
        val features = ImportanceFeatures.of(input(category = Category.RESULTS))
        val categorySlots = ImportanceFeatures.NAMES.indices
            .filter { ImportanceFeatures.NAMES[it].startsWith("cat:") }
        assertEquals(1.0, categorySlots.sumOf { features[it] }, 1e-9)
        val resultsSlot =
            ImportanceFeatures.NAMES.indexOf("cat:${Category.RESULTS.name.lowercase()}")
        assertEquals(1.0, features[resultsSlot], 1e-9)
    }

    @Test
    fun namingAnAmountIsSeparateFromNamingALargeOne() {
        val names = ImportanceFeatures.NAMES
        val money = names.indexOf("money")
        val hasMoney = names.indexOf("has_money")

        val silent = ImportanceFeatures.of(input(title = "Outcome of board meeting"))
        assertEquals(0.0, silent[hasMoney], 1e-9)
        assertEquals(0.0, silent[money], 1e-9)

        val small = ImportanceFeatures.of(input(title = "bags Rs 12 crore order"))
        val large = ImportanceFeatures.of(input(title = "bags Rs 1,200 crore order"))
        assertEquals(1.0, small[hasMoney], 1e-9)
        assertEquals(1.0, large[hasMoney], 1e-9)
        // A headline with no figure is not a headline with a figure of zero, and the
        // twelve-crore order is not the twelve-hundred-crore one.
        assertTrue(large[money] > small[money])
    }

    @Test
    fun everyFeatureStaysInsideTheUnitInterval() {
        // Not for stability — a logistic regression tolerates far worse — but so the
        // learned weights stay comparable to each other and the model stays readable.
        val extreme = ImportanceFeatures.of(
            input(
                outletCount = 400,
                spanMinutes = 1,
                symbolCount = 90,
                title = "Rs 90,00,000 crore mega deal up 900% " + "word ".repeat(80),
            )
        )
        assertTrue(extreme.all { it in 0.0..1.0 })
    }

    @Test
    fun anUntrainedModelHasNoOpinion() {
        val model = ImportanceModel()
        assertFalse(model.isTrained)
        // Exactly one half for everything: zero weights, so nothing is ranked above
        // anything else by a model that has seen no evidence.
        assertEquals(0.5, model.predict(ImportanceFeatures.of(input())), 1e-9)
        assertEquals(
            0.5,
            model.predict(ImportanceFeatures.of(input(category = Category.REGULATORY))),
            1e-9,
        )
    }

    @Test
    fun itLearnsASignalThatIsActuallyThere() {
        // Filings move stocks and general wire copy does not, in this made-up world.
        val random = Random(7)
        val examples = (1..400).map { index ->
            val filing = random.nextBoolean()
            TrainingExample(
                features = ImportanceFeatures.of(
                    input(
                        category = if (filing) Category.RESULTS else Category.OTHER,
                        tier = if (filing) SourceTier.OFFICIAL else SourceTier.AGGREGATOR,
                        isFiling = filing,
                    )
                ),
                label = if (filing) 1.0 else 0.0,
                publishedAt = index.toLong(),
            )
        }
        val model = ImportanceModel().trained(examples)

        val onFiling = model.predict(
            ImportanceFeatures.of(
                input(
                    category = Category.RESULTS,
                    tier = SourceTier.OFFICIAL,
                    isFiling = true,
                )
            )
        )
        val onNoise = model.predict(
            ImportanceFeatures.of(
                input(category = Category.OTHER, tier = SourceTier.AGGREGATOR)
            )
        )
        assertTrue("filing $onFiling should beat noise $onNoise", onFiling > onNoise)
        assertTrue(onFiling > 0.5)
        assertTrue(onNoise < 0.5)
    }

    @Test
    fun trainingIsWarmStartedAndCounted() {
        val examples = listOf(
            TrainingExample(ImportanceFeatures.of(input()), 1.0, publishedAt = 100L),
            TrainingExample(ImportanceFeatures.of(input()), 1.0, publishedAt = 300L),
        )
        val once = ImportanceModel().trained(examples)
        val twice = once.trained(examples)

        assertEquals(2, once.examplesSeen)
        assertEquals(4, twice.examplesSeen)
        // The watermark is what stops the same story training the model forever.
        assertEquals(300L, once.watermark)
        // Warm start: the second pass continues from the first rather than restarting.
        assertTrue(twice.predict(examples[0].features) > once.predict(examples[0].features))
    }

    @Test
    fun trainingOnNothingChangesNothing() {
        val model = ImportanceModel()
        assertTrue(model === model.trained(emptyList()))
    }

    @Test
    fun theColdStartGuardHoldsUntilThereIsEvidence() {
        val one = TrainingExample(ImportanceFeatures.of(input()), 1.0, 1L)
        val few = ImportanceModel().trained(List(ImportanceModel.MIN_EXAMPLES - 1) { one })
        assertFalse(few.isTrained)
        assertTrue(few.trained(listOf(one)).isTrained)
    }

    @Test
    fun theLabelIsAnAbnormalMoveNotAnyMove() {
        assertEquals(1.0, ImportanceModel.labelFor(2.5), 1e-9)
        assertEquals(1.0, ImportanceModel.labelFor(-2.5), 1e-9)
        assertEquals(0.0, ImportanceModel.labelFor(0.3), 1e-9)
        assertEquals(0.0, ImportanceModel.labelFor(-0.9), 1e-9)
    }

    @Test
    fun aModelSurvivesBeingWrittenDownAndReadBack() {
        val trained = ImportanceModel().trained(
            listOf(TrainingExample(ImportanceFeatures.of(input()), 1.0, 500L))
        )
        val restored = ImportanceModel.decode(ImportanceModel.encode(trained))
        assertEquals(trained.examplesSeen, restored.examplesSeen)
        assertEquals(trained.watermark, restored.watermark)
        assertEquals(
            trained.predict(ImportanceFeatures.of(input())),
            restored.predict(ImportanceFeatures.of(input())),
            1e-9,
        )
    }

    @Test
    fun aStoredModelFromADifferentFeatureListIsDiscarded() {
        // What a build that added a feature leaves behind. Starting over costs a few
        // days of learning; loading a vector whose entries no longer line up would be
        // silently wrong forever.
        val short = "10|500|" + DoubleArray(ImportanceFeatures.SIZE - 1).joinToString(",")
        assertEquals(0, ImportanceModel.decode(short).examplesSeen)
        assertEquals(0, ImportanceModel.decode(null).examplesSeen)
        assertEquals(0, ImportanceModel.decode("garbage").examplesSeen)
        assertEquals(0, ImportanceModel.decode("1|2").examplesSeen)
    }

    @Test
    fun aCorruptWeightIsNotLoaded() {
        val weights = DoubleArray(ImportanceFeatures.SIZE).also { it[0] = Double.NaN }
        val text = "10|500|" + weights.joinToString(",")
        assertEquals(0, ImportanceModel.decode(text).examplesSeen)
    }

    @Test
    fun theExplanationNamesWhatMovedThisStory() {
        val examples = (1..200).map { index ->
            TrainingExample(
                ImportanceFeatures.of(input(isFiling = index % 2 == 0)),
                label = if (index % 2 == 0) 1.0 else 0.0,
                publishedAt = index.toLong(),
            )
        }
        val model = ImportanceModel().trained(examples)
        val features = ImportanceFeatures.of(input(isFiling = true))
        val top = model.contributions(features).map { it.first }
        assertTrue("filing" in top)
        // A large weight on a feature that is zero here explains nothing about this story.
        val absent = ImportanceFeatures.of(input(isFiling = false))
        assertFalse("filing" in model.contributions(absent).map { it.first })
    }

    @Test
    fun theWeightVectorIsNotSharedBetweenModels() {
        val base = ImportanceModel()
        val trained = base.trained(
            listOf(TrainingExample(ImportanceFeatures.of(input()), 1.0, 1L))
        )
        // Copied, not mutated: the cold-start model has to stay opinionless.
        assertEquals(0.5, base.predict(ImportanceFeatures.of(input())), 1e-9)
        assertNotEquals(0.5, trained.predict(ImportanceFeatures.of(input())), 1e-9)
    }
}
