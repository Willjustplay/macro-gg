package com.example

import com.example.billing.BillingConfig
import com.example.billing.PremiumFeature
import com.example.billing.PremiumFeatureGate
import com.example.billing.SubscriptionPlan
import com.example.data.ActionKind
import com.example.data.ClickAction
import com.example.data.ImageAction
import com.example.data.LoopAction
import com.example.data.Macro
import com.example.data.SwipeAction
import com.example.data.WaitAction
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PremiumFeatureGateTest {

    @Test
    fun onlyFreeActionsIncludingAccountSwitchAreFree() {
        val expectedFree = setOf(
            ActionKind.CLICK,
            ActionKind.SWIPE,
            ActionKind.IMAGE,
            ActionKind.SWITCH_ACCOUNT,
            ActionKind.SELECT_ACCOUNT
        )
        assertEquals(expectedFree, PremiumFeatureGate.FREE_ACTION_KINDS)
        assertEquals(2, PremiumFeatureGate.FREE_MAX_ACCOUNTS)
        assertTrue(PremiumFeatureGate.canAddAccount(0, isPremium = false))
        assertTrue(PremiumFeatureGate.canAddAccount(1, isPremium = false))
        assertFalse(PremiumFeatureGate.canAddAccount(2, isPremium = false))
        assertTrue(PremiumFeatureGate.canAddAccount(2, isPremium = true))

        for (kind in ActionKind.entries) {
            if (kind in expectedFree) {
                assertTrue("Expected $kind to be free", PremiumFeatureGate.isActionKindFree(kind))
            } else {
                assertFalse("Expected $kind to be PRO-locked", PremiumFeatureGate.isActionKindFree(kind))
            }
        }
    }

    @Test
    fun freeMacroLimitIsStrictlyThree() {
        assertEquals(3, PremiumFeatureGate.FREE_MAX_MACROS)

        assertTrue(PremiumFeatureGate.canCreateMacro(0, isPremium = false))
        assertTrue(PremiumFeatureGate.canCreateMacro(1, isPremium = false))
        assertTrue(PremiumFeatureGate.canCreateMacro(2, isPremium = false))
        assertFalse(PremiumFeatureGate.canCreateMacro(3, isPremium = false))
        assertFalse(PremiumFeatureGate.canCreateMacro(10, isPremium = false))

        assertTrue(PremiumFeatureGate.canCreateMacro(3, isPremium = true))
        assertTrue(PremiumFeatureGate.canCreateMacro(100, isPremium = true))
    }

    @Test
    fun freeMacroWithClickSwipeAndNestedImageClickIsAllowed() {
        val macro = Macro(
            name = "Gratis Valid",
            actions = listOf(
                ClickAction(x = 100, y = 200),
                SwipeAction(x1 = 100, y1 = 500, x2 = 100, y2 = 200),
                ImageAction(
                    imageName = "play.png",
                    thenActions = listOf(ClickAction(x = 300, y = 400)),
                    elseActions = listOf(SwipeAction(x1 = 200, y1 = 600, x2 = 200, y2 = 300))
                )
            )
        )

        assertNull(PremiumFeatureGate.checkMacroRun(macro, isPremium = false))
        assertNull(PremiumFeatureGate.checkMacroRun(macro, isPremium = true))
    }

    @Test
    fun nestedProActionInsideImageBranchIsBlockedForFree() {
        val macro = Macro(
            name = "Nested Wait",
            actions = listOf(
                ImageAction(
                    imageName = "play.png",
                    thenActions = listOf(
                        ClickAction(x = 300, y = 400),
                        WaitAction(durationMs = 1000)
                    )
                )
            )
        )

        val denial = PremiumFeatureGate.checkMacroRun(macro, isPremium = false)
        assertNotNull(denial)
        assertEquals(PremiumFeature.ACTION_LOCKED, denial!!.feature)
        assertEquals(ActionKind.WAIT, denial.blockedKind)

        assertNull(PremiumFeatureGate.checkMacroRun(macro, isPremium = true))
    }

    @Test
    fun loopActionIsBlockedForFreeAndAllowedForPremium() {
        val macro = Macro(
            name = "With Loop",
            actions = listOf(
                LoopAction(
                    count = 3,
                    actions = listOf(ClickAction(x = 100, y = 100))
                )
            )
        )

        val denial = PremiumFeatureGate.checkMacroRun(macro, isPremium = false)
        assertNotNull(denial)
        assertEquals(ActionKind.LOOP, denial!!.blockedKind)
        assertNull(PremiumFeatureGate.checkMacroRun(macro, isPremium = true))
    }

    @Test
    fun watchdogConfigIsBlockedForFreeAndAllowedForPremium() {
        val macroWithSec = Macro(
            name = "Watchdog Timer",
            autoRecoverStuckSec = 45,
            actions = listOf(ClickAction(x = 100, y = 100))
        )
        val denialSec = PremiumFeatureGate.checkMacroRun(macroWithSec, isPremium = false)
        assertNotNull(denialSec)
        assertEquals(PremiumFeature.WATCHDOG, denialSec!!.feature)
        assertNull(PremiumFeatureGate.checkMacroRun(macroWithSec, isPremium = true))

        val macroWithStuckActions = Macro(
            name = "Watchdog Actions",
            actions = listOf(ClickAction(x = 100, y = 100)),
            stuckActions = listOf(ClickAction(x = 50, y = 50))
        )
        val denialStuck = PremiumFeatureGate.checkMacroRun(macroWithStuckActions, isPremium = false)
        assertNotNull(denialStuck)
        assertEquals(PremiumFeature.WATCHDOG, denialStuck!!.feature)
        assertNull(PremiumFeatureGate.checkMacroRun(macroWithStuckActions, isPremium = true))
    }

    @Test
    fun billingConfigHasOnlyMonthlyAndYearlyPlans() {
        assertEquals("wjust_macro_premium", BillingConfig.SUBSCRIPTION_PRODUCT_ID)
        assertEquals(listOf(SubscriptionPlan.MONTHLY, SubscriptionPlan.YEARLY), SubscriptionPlan.entries)
        assertEquals("monthly", SubscriptionPlan.MONTHLY.basePlanId)
        assertEquals("yearly", SubscriptionPlan.YEARLY.basePlanId)
    }
}
