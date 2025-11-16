package com.studioreserve.settings

import org.jetbrains.exposed.sql.Table
import org.jetbrains.exposed.sql.javatime.datetime

object SystemSettingsTable : Table("system_settings") {
    val key = varchar("key", 255)
    val value = varchar("value", 255)
    val updatedAt = datetime("updated_at")

    override val primaryKey = PrimaryKey(key)
}
