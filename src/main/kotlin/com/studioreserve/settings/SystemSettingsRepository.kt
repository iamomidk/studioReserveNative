package com.studioreserve.settings

import java.time.LocalDateTime
import java.time.ZoneOffset
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.select
import org.jetbrains.exposed.sql.update
import org.jetbrains.exposed.sql.transactions.experimental.newSuspendedTransaction
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq

class SystemSettingsRepository(
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO
) {
    suspend fun getCommissionPercent(): Int = newSuspendedTransaction(dispatcher) {
        val storedValue = SystemSettingsTable
            .select { SystemSettingsTable.key eq COMMISSION_PERCENT_KEY }
            .singleOrNull()
            ?.get(SystemSettingsTable.value)

        storedValue?.toIntOrNull() ?: DEFAULT_COMMISSION_PERCENT
    }

    suspend fun setCommissionPercent(percent: Int): Int = newSuspendedTransaction(dispatcher) {
        val now = LocalDateTime.now(ZoneOffset.UTC)
        val updated = SystemSettingsTable.update({ SystemSettingsTable.key eq COMMISSION_PERCENT_KEY }) {
            it[value] = percent.toString()
            it[updatedAt] = now
        }

        if (updated == 0) {
            SystemSettingsTable.insert {
                it[key] = COMMISSION_PERCENT_KEY
                it[value] = percent.toString()
                it[updatedAt] = now
            }
        }

        percent
    }

    companion object {
        private const val COMMISSION_PERCENT_KEY = "platform.commission.percent"
        private const val DEFAULT_COMMISSION_PERCENT = 10
    }
}
