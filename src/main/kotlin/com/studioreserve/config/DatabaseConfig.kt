package com.studioreserve.config

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.SchemaUtils
import org.jetbrains.exposed.sql.Table
import org.jetbrains.exposed.sql.transactions.transaction
import org.slf4j.LoggerFactory

object DatabaseConfig {
    private val logger = LoggerFactory.getLogger(DatabaseConfig::class.java)

    private val dbUrl: String = System.getenv("DB_URL") ?: "jdbc:postgresql://localhost:5432/studioreserve"
    private val dbUser: String = System.getenv("DB_USER") ?: "postgres"
    private val dbPassword: String = System.getenv("DB_PASSWORD") ?: "postgres"

    private val dataSource: HikariDataSource by lazy { createDataSource() }

    private fun createDataSource(): HikariDataSource {
        val config = HikariConfig().apply {
            jdbcUrl = dbUrl
            username = dbUser
            password = dbPassword
            driverClassName = "org.postgresql.Driver"
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
            SchemaUtils.create(UsersTable, StudiosTable)
        }
        logger.info("Database initialized and tables ensured")
    }
}

object UsersTable : Table("users") {
    val id = integer("id").autoIncrement()
    val email = varchar("email", length = 255).uniqueIndex()
    val displayName = varchar("display_name", length = 255).nullable()

    override val primaryKey = PrimaryKey(id)
}

object StudiosTable : Table("studios") {
    val id = integer("id").autoIncrement()
    val name = varchar("name", length = 255)
    val location = varchar("location", length = 255).nullable()

    override val primaryKey = PrimaryKey(id)
}
