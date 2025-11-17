package com.studioreserve.config

import com.studioreserve.admin.CommissionSettingsTable
import com.studioreserve.auth.RefreshTokensTable
import com.studioreserve.bookings.BookingsTable
import com.studioreserve.equipment.EquipmentLogsTable
import com.studioreserve.equipment.EquipmentTable
import com.studioreserve.payments.PaymentsTable
import com.studioreserve.studios.RoomsTable
import com.studioreserve.studios.StudiosTable
import com.studioreserve.users.UsersTable
import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.SchemaUtils
import org.jetbrains.exposed.sql.transactions.transaction
import org.slf4j.LoggerFactory

object DatabaseConfig {
    private val logger = LoggerFactory.getLogger(DatabaseConfig::class.java)

    private val dbUrl: String = env("DB_URL", "jdbc:postgresql://localhost:5432/studioreserve")
    private val dbUser: String = env("DB_USER", "postgres")
    private val dbPassword: String = env("DB_PASSWORD", "postgres")
    private val dbDriver: String = env("DB_DRIVER", "org.postgresql.Driver")

    private val dataSource: HikariDataSource by lazy { createDataSource() }

    private fun createDataSource(): HikariDataSource {
        val config = HikariConfig().apply {
            jdbcUrl = dbUrl
            username = dbUser
            password = dbPassword
            driverClassName = dbDriver
            maximumPoolSize = 5
            isAutoCommit = false
            transactionIsolation = "TRANSACTION_REPEATABLE_READ"
            validate()
        }
        return HikariDataSource(config)
    }

    fun initDatabase() {
        Database.connect(dataSource)
        transaction {
            SchemaUtils.create(
                UsersTable,
                StudiosTable,
                RoomsTable,
                RefreshTokensTable,
                BookingsTable,
                EquipmentTable,
                EquipmentLogsTable,
                PaymentsTable,
                CommissionSettingsTable,
            )
        }
        logger.info("Database initialized and tables ensured")
    }

    private fun env(key: String, default: String): String =
        System.getenv(key) ?: System.getProperty(key) ?: default
}
