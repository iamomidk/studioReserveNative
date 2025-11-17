package com.kaj.studioreserve

import com.kaj.studioreserve.admin.CommissionSettingsTable
import com.kaj.studioreserve.auth.RefreshTokensTable
import com.kaj.studioreserve.bookings.BookingsTable
import com.kaj.studioreserve.config.DatabaseConfig
import com.kaj.studioreserve.equipment.EquipmentLogsTable
import com.kaj.studioreserve.equipment.EquipmentTable
import com.kaj.studioreserve.payments.PaymentsTable
import com.kaj.studioreserve.studios.RoomsTable
import com.kaj.studioreserve.studios.StudiosTable
import com.kaj.studioreserve.users.UsersTable
import org.jetbrains.exposed.sql.SchemaUtils
import org.jetbrains.exposed.sql.transactions.transaction

object TestDatabase {
    private val tables = arrayOf(
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

    fun setup() {
        System.setProperty("DB_URL", "jdbc:h2:mem:studioreserve;MODE=PostgreSQL;DB_CLOSE_DELAY=-1;DATABASE_TO_UPPER=false")
        System.setProperty("DB_USER", "sa")
        System.setProperty("DB_PASSWORD", "")
        System.setProperty("DB_DRIVER", "org.h2.Driver")

        System.setProperty("JWT_SECRET", "test-secret")
        System.setProperty("JWT_ISSUER", "studioreserve-test")
        System.setProperty("JWT_AUDIENCE", "studioreserve-clients")
        System.setProperty("JWT_ACCESS_EXPIRES_MIN", "60")
        System.setProperty("JWT_REFRESH_EXPIRES_MIN", "120")

        DatabaseConfig.initDatabase()
        reset()
    }

    fun reset() {
        transaction {
            SchemaUtils.drop(*tables)
            SchemaUtils.create(*tables)
        }
    }
}
