package com.studioreserve.equipment

import org.jetbrains.exposed.sql.Table
import org.jetbrains.exposed.sql.javatime.datetime

object EquipmentLogsTable : Table("equipment_logs") {
    val id = uuid("id")
    val equipmentId = uuid("equipment_id")
    val userId = uuid("user_id")
    val action = enumerationByName("action", 50, EquipmentAction::class)
    val timestamp = datetime("timestamp")
    val notes = text("notes")

    override val primaryKey = PrimaryKey(id)
}
