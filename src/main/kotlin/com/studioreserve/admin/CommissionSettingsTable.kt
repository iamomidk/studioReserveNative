package com.studioreserve.admin

import org.jetbrains.exposed.sql.Table
import org.jetbrains.exposed.sql.javatime.datetime

object CommissionSettingsTable : Table("commission_settings") {
    val id = uuid("id")
    val commissionRate = decimal("commission_rate", 5, 4)
    val updatedAt = datetime("updated_at")

    override val primaryKey = PrimaryKey(id)
}
