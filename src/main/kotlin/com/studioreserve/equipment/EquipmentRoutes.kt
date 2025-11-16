package com.studioreserve.equipment

import com.studioreserve.config.StudioOwnersTable
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.call
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import kotlinx.serialization.Serializable
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.SqlExpressionBuilder.inList
import org.jetbrains.exposed.sql.Table
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.select
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.update
import java.util.UUID

fun Route.equipmentRoutes() {
    route("/api/equipment") {
        post {
            val role = call.request.headers["X-User-Role"]?.lowercase()
            if (role != "studio_owner") {
                call.respond(HttpStatusCode.Forbidden, ErrorResponse("Studio owner role required"))
                return@post
            }

            val userId = call.request.headers["X-User-Id"]?.toIntOrNull()
                ?: run {
                    call.respond(HttpStatusCode.BadRequest, ErrorResponse("Missing or invalid X-User-Id header"))
                    return@post
                }

            val request = call.receive<EquipmentCreateRequest>()
            if (request.studioIds.isEmpty()) {
                call.respond(HttpStatusCode.BadRequest, ErrorResponse("At least one studioId must be provided"))
                return@post
            }

            val ownedStudios = validateStudioOwnership(userId, request.studioIds)
            if (ownedStudios.isEmpty()) {
                call.respond(HttpStatusCode.Forbidden, ErrorResponse("User does not own the requested studios"))
                return@post
            }

            val createdEquipment = transaction {
                ownedStudios.map { studioId ->
                    val equipmentId = UUID.randomUUID()
                    val barcodeCode = UUID.randomUUID().toString()

                    EquipmentTable.insert { row ->
                        row[id] = equipmentId
                        row[EquipmentTable.studioId] = studioId
                        row[name] = request.name
                        row[description] = request.description
                        row[createdBy] = userId
                        row[EquipmentTable.barcodeCode] = barcodeCode
                        row[barcodeImageUrl] = null
                    }

                    EquipmentResponse(
                        id = equipmentId.toString(),
                        studioId = studioId,
                        name = request.name,
                        description = request.description,
                        barcodeCode = barcodeCode,
                        barcodeImageUrl = null
                    )
                }
            }

            call.respond(HttpStatusCode.Created, EquipmentBatchResponse(createdEquipment))
        }
    }
}

@Serializable
data class EquipmentCreateRequest(
    val studioIds: List<Int>,
    val name: String,
    val description: String? = null
)

@Serializable
data class EquipmentResponse(
    val id: String,
    val studioId: Int,
    val name: String,
    val description: String?,
    val barcodeCode: String,
    val barcodeImageUrl: String?
)

@Serializable
data class EquipmentBatchResponse(val equipment: List<EquipmentResponse>)

@Serializable
data class ErrorResponse(val message: String)

object EquipmentTable : Table("equipment") {
    val id = uuid("id")
    val studioId = integer("studio_id")
    val name = varchar("name", length = 255)
    val description = text("description").nullable()
    val createdBy = integer("created_by")
    val barcodeCode = varchar("barcode_code", length = 255)
    val barcodeImageUrl = varchar("barcode_image_url", length = 1024).nullable()

    override val primaryKey = PrimaryKey(id)
}

fun generateBarcodeForEquipment(equipmentId: String) {
    val parsedId = runCatching { UUID.fromString(equipmentId) }
        .getOrElse { throw IllegalArgumentException("Invalid equipmentId: $equipmentId") }

    transaction {
        val existingRow = EquipmentTable.select { EquipmentTable.id eq parsedId }
            .limit(1)
            .firstOrNull()
            ?: throw IllegalArgumentException("Equipment not found for id=$equipmentId")

        // TODO: call external barcode generator API
        val dummyUrl = "https://cdn.studioreserve.test/barcodes/${existingRow[EquipmentTable.barcodeCode]}.png"

        EquipmentTable.update({ EquipmentTable.id eq parsedId }) { row ->
            row[barcodeImageUrl] = dummyUrl
        }
    }
}

private fun validateStudioOwnership(userId: Int, requestedStudioIds: List<Int>): List<Int> {
    if (requestedStudioIds.isEmpty()) return emptyList()

    return transaction {
        StudioOwnersTable
            .slice(StudioOwnersTable.studioId)
            .select {
                (StudioOwnersTable.ownerId eq userId) and
                    (StudioOwnersTable.studioId inList requestedStudioIds)
            }
            .map { it[StudioOwnersTable.studioId] }
    }
}
