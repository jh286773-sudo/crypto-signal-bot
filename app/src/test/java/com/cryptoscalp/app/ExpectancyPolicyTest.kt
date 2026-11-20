package com.cryptoscalp.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ExpectancyPolicyTest {
    @Test fun insufficientSampleBlocks() {
        val result=ExpectancyPolicy.evaluate(List(29){1.0})
        assertEquals(false,result.allowed)
        assertEquals(29,result.count)
    }
    @Test fun robustPositiveNetRPasses() {
        val result=ExpectancyPolicy.evaluate(listOf(.8,1.0,1.2).let{it+it+it+it+it+it+it+it+it+it})
        assertTrue(result.allowed)
        assertTrue(result.lower95NetR>0.0)
        assertTrue(result.profitFactor>1.10)
    }
    @Test fun uncertainAndNegativeSamplesBlock() {
        assertEquals(false,ExpectancyPolicy.evaluate(List(30){if(it%2==0)1.0 else -.95}).allowed)
        assertEquals(false,ExpectancyPolicy.evaluate(List(30){-.1}).allowed)
    }
    @Test fun longAndShortAreEvaluatedSeparately() {
        assertTrue(ExpectancyPolicy.evaluate(List(30){.9}).allowed)
        assertEquals(false,ExpectancyPolicy.evaluate(List(30){-.1}).allowed)
    }
}
