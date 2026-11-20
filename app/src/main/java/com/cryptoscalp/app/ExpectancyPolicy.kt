package com.cryptoscalp.app

import kotlin.math.sqrt

data class ExpectancyResult(
    val allowed:Boolean,
    val count:Int,
    val meanNetR:Double=0.0,
    val lower95NetR:Double=0.0,
    val profitFactor:Double=0.0
)

/** Same conservative one-sided 95% net-R policy as expectancy.py. */
object ExpectancyPolicy {
    const val MIN_TRADES=30
    const val MIN_PROFIT_FACTOR=1.10
    const val ONE_SIDED_Z=1.645

    fun evaluate(closedNetR:List<Double>):ExpectancyResult {
        val values=closedNetR.takeLast(200)
        if(values.size<MIN_TRADES || values.any{!it.isFinite()})
            return ExpectancyResult(false,values.size)
        val mean=values.average()
        val variance=values.sumOf{(it-mean)*(it-mean)}/(values.size-1)
        val lower=mean-ONE_SIDED_Z*sqrt(variance/values.size)
        val profit=values.filter{it>0}.sum()
        val loss=-values.filter{it<0}.sum()
        val pf=if(loss>0)profit/loss else if(profit>0)Double.POSITIVE_INFINITY else 0.0
        return ExpectancyResult(mean>0&&lower>0&&pf>MIN_PROFIT_FACTOR,values.size,mean,lower,pf)
    }
}
