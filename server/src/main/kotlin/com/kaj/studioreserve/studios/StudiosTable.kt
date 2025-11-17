package com.kaj.studioreserve.studios

import com.kaj.studioreserve.db.columns.textArray
import com.kaj.studioreserve.users.UsersTable
import org.jetbrains.exposed.sql.ReferenceOption
import org.jetbrains.exposed.sql.Table
import org.jetbrains.exposed.sql.javatime.CurrentDateTime
import org.jetbrains.exposed.sql.javatime.datetime

enum class VerificationStatus { PENDING, APPROVED, REJECTED }

object StudiosTable : Table("studios") {
    val id = uuid("id")
    val ownerId = reference("owner_id", UsersTable.id, onDelete = ReferenceOption.CASCADE)
    val name = varchar("name", 255)
    val description = text("description")
    val province = varchar("province", 100)
    val city = varchar("city", 100)
    val address = varchar("address", 255)
    val mapCoordinates = text("map_coordinates")
    // Stored as a PostgreSQL text[] via [textArray] helper so we can keep simple JSON-like payloads.
    val photos = textArray("photos").default(emptyList())
    val verificationStatus = enumerationByName("verification_status", 20, VerificationStatus::class)
        .default(VerificationStatus.PENDING)
    val createdAt = datetime("created_at").defaultExpression(CurrentDateTime())

    override val primaryKey = PrimaryKey(id)
}

object RoomsTable : Table("rooms") {
    val id = uuid("id")
    val studioId = reference("studio_id", StudiosTable.id, onDelete = ReferenceOption.CASCADE)
    val name = varchar("name", 255)
    val description = text("description")
    val hourlyPrice = integer("hourly_price")
    val dailyPrice = integer("daily_price")
    val features = textArray("features").default(emptyList())
    val images = textArray("images").default(emptyList())

    override val primaryKey = PrimaryKey(id)
}
