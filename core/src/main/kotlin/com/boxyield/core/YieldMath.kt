package com.boxyield.core

import kotlin.math.ln
import kotlin.math.pow

enum class Convention {
    /** (face/price - 1) * 365/days. */
    SIMPLE,
    /**
     * Matches Treasury's Constant Maturity (CMT) yields: simple 365-day yield up to a
     * half year, semiannual compounding beyond. Use this to compare against Treasuries.
     */
    BOND_EQUIVALENT,
    CONTINUOUS,
}

object YieldMath {
    /** Annualized yield in percent for paying [price] today to receive [face] in [days]. */
    fun annualizedYieldPct(price: Double, face: Double, days: Int, convention: Convention = Convention.BOND_EQUIVALENT): Double {
        require(price > 0 && face > 0 && days > 0)
        val growth = face / price
        val years = days / 365.0
        val y = when (convention) {
            Convention.SIMPLE -> (growth - 1) * 365.0 / days
            Convention.CONTINUOUS -> ln(growth) / years
            Convention.BOND_EQUIVALENT ->
                if (days <= 182) (growth - 1) * 365.0 / days
                else 2 * (growth.pow(1 / (2 * years)) - 1)
        }
        return y * 100
    }

    /**
     * Yield actually earned by someone buying the box, after commissions/exchange fees.
     * [feePerContract] is charged on each of the 4 legs.
     */
    fun netYieldPct(box: BoxTrade, days: Int, feePerContract: Double, quantity: Int = box.quantity): Double {
        val cost = box.price * 100 * quantity + feePerContract * 4 * quantity
        val face = box.width * 100 * quantity
        return annualizedYieldPct(cost, face, days)
    }

    /** Price that would deliver [yieldPct] (bond-equivalent) on a box of [width]. Inverse of annualizedYieldPct. */
    fun priceForYield(yieldPct: Double, width: Double, days: Int): Double {
        val y = yieldPct / 100
        val years = days / 365.0
        return if (days <= 182) width / (1 + y * days / 365.0)
        else width / (1 + y / 2).pow(2 * years)
    }
}
