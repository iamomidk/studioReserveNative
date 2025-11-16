package com.studioreserve.rooms

import com.studioreserve.auth.UserPrincipal
import com.studioreserve.studios.RoomsTable
import com.studioreserve.studios.StudiosTable
import com.studioreserve.users.UserRole
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.call
import io.ktor.server.auth.authenticate
import io.ktor.server.auth.principal
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.post
import io.ktor.server.routing.put
import io.ktor.server.routing.route
import java.util.UUID
import kotlinx.serialization.Serializable
import org.jetbrains.exposed.sql.ResultRow
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.select
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.update

fun Route.roomRoutes() {
    route("/api/rooms") {
        authenticate("auth-jwt") {
            post {
                val principal = call.principal<UserPrincipal>()
                    ?: return@post call.respond(HttpStatusCode.Unauthorized, ErrorResponse("Missing authentication token"))

                val role = runCatching { UserRole.valueOf(principal.role) }.getOrNull()
                    ?: return@post call.respond(HttpStatusCode.Forbidden, ErrorResponse("Unknown role"))

                if (role != UserRole.STUDIO_OWNER) {
                    return@post call.respond(HttpStatusCode.Forbidden, ErrorResponse("Only studio owners can create rooms"))
                }

                val ownerId = runCatching { UUID.fromString(principal.userId) }.getOrNull()
                    ?: return@post call.respond(HttpStatusCode.BadRequest, ErrorResponse("Invalid user id"))

                val request = runCatching { call.receive<CreateRoomRequest>() }.getOrElse {
                    return@post call.respond(HttpStatusCode.BadRequest, ErrorResponse("Invalid request payload"))
                }

                request.validationError()?.let { message ->
                    return@post call.respond(HttpStatusCode.BadRequest, ErrorResponse(message))
                }

                val studioId = runCatching { UUID.fromString(request.studioId) }.getOrElse {
                    return@post call.respond(HttpStatusCode.BadRequest, ErrorResponse("studioId must be a valid UUID"))
                }

                val result = transaction {
                    val studioRow = StudiosTable.select { StudiosTable.id eq studioId }.singleOrNull()
                        ?: return@transaction RoomOperationResult.NotFound

                    if (studioRow[StudiosTable.ownerId] != ownerId) {
                        return@transaction RoomOperationResult.Forbidden
                    }

                    val normalizedFeatures = request.features.mapNotNull { it.trim().takeIf(String::isNotEmpty) }
                    val normalizedImages = request.images.mapNotNull { it.trim().takeIf(String::isNotEmpty) }

                    val roomId = UUID.randomUUID()
                    RoomsTable.insert { statement ->
                        statement[RoomsTable.id] = roomId
                        statement[RoomsTable.studioId] = studioId
                        statement[RoomsTable.name] = request.name.trim()
                        statement[RoomsTable.description] = request.description.trim()
                        statement[RoomsTable.hourlyPrice] = request.hourlyPrice
                        statement[RoomsTable.dailyPrice] = request.dailyPrice
                        statement[RoomsTable.features] = normalizedFeatures
                        statement[RoomsTable.images] = normalizedImages
                    }

                    val roomRow = RoomsTable.select { RoomsTable.id eq roomId }.single()
                    RoomOperationResult.Success(roomRow.toRoomDto())
                }

                when (result) {
                    RoomOperationResult.Forbidden -> call.respond(HttpStatusCode.Forbidden, ErrorResponse("You do not own this studio"))
                    RoomOperationResult.NotFound -> call.respond(HttpStatusCode.NotFound, ErrorResponse("Studio not found"))
                    is RoomOperationResult.Success -> call.respond(HttpStatusCode.Created, result.room)
                }
            }

            put("/{id}") {
                val principal = call.principal<UserPrincipal>()
                    ?: return@put call.respond(HttpStatusCode.Unauthorized, ErrorResponse("Missing authentication token"))

                val role = runCatching { UserRole.valueOf(principal.role) }.getOrNull()
                    ?: return@put call.respond(HttpStatusCode.Forbidden, ErrorResponse("Unknown role"))

                if (role != UserRole.STUDIO_OWNER) {
                    return@put call.respond(HttpStatusCode.Forbidden, ErrorResponse("Only studio owners can update rooms"))
                }

                val ownerId = runCatching { UUID.fromString(principal.userId) }.getOrNull()
                    ?: return@put call.respond(HttpStatusCode.BadRequest, ErrorResponse("Invalid user id"))

                val roomIdParam = call.parameters["id"]
                    ?: return@put call.respond(HttpStatusCode.BadRequest, ErrorResponse("Room id is required"))
                val roomId = runCatching { UUID.fromString(roomIdParam) }.getOrElse {
                    return@put call.respond(HttpStatusCode.BadRequest, ErrorResponse("Invalid room id"))
                }

                val request = runCatching { call.receive<UpdateRoomRequest>() }.getOrElse {
                    return@put call.respond(HttpStatusCode.BadRequest, ErrorResponse("Invalid request payload"))
                }

                request.validationError()?.let { message ->
                    return@put call.respond(HttpStatusCode.BadRequest, ErrorResponse(message))
                }

                val updateResult = transaction {
                    val existingRoom = RoomsTable.select { RoomsTable.id eq roomId }.singleOrNull()
                        ?: return@transaction RoomOperationResult.NotFound

                    val studioId = existingRoom[RoomsTable.studioId]
                    val studioRow = StudiosTable.select { StudiosTable.id eq studioId }.singleOrNull()
                        ?: return@transaction RoomOperationResult.NotFound

                    if (studioRow[StudiosTable.ownerId] != ownerId) {
                        return@transaction RoomOperationResult.Forbidden
                    }

                    val normalizedFeatures = request.features.mapNotNull { it.trim().takeIf(String::isNotEmpty) }
                    val normalizedImages = request.images.mapNotNull { it.trim().takeIf(String::isNotEmpty) }

                    RoomsTable.update({ RoomsTable.id eq roomId }) { statement ->
                        statement[RoomsTable.name] = request.name.trim()
                        statement[RoomsTable.description] = request.description.trim()
                        statement[RoomsTable.hourlyPrice] = request.hourlyPrice
                        statement[RoomsTable.dailyPrice] = request.dailyPrice
                        statement[RoomsTable.features] = normalizedFeatures
                        statement[RoomsTable.images] = normalizedImages
                    }

                    val roomRow = RoomsTable.select { RoomsTable.id eq roomId }.single()
                    RoomOperationResult.Success(roomRow.toRoomDto())
                }

                when (updateResult) {
                    RoomOperationResult.Forbidden -> call.respond(HttpStatusCode.Forbidden, ErrorResponse("You do not own this room"))
                    RoomOperationResult.NotFound -> call.respond(HttpStatusCode.NotFound, ErrorResponse("Room not found"))
                    is RoomOperationResult.Success -> call.respond(HttpStatusCode.OK, updateResult.room)
                }
            }
        }
    }
}

private fun CreateRoomRequest.validationError(): String? {
    if (studioId.isBlank()) return "studioId is required"
    if (name.isBlank()) return "name is required"
    if (description.isBlank()) return "description is required"
    if (hourlyPrice <= 0) return "hourlyPrice must be greater than zero"
    if (dailyPrice <= 0) return "dailyPrice must be greater than zero"
    return null
}

private fun UpdateRoomRequest.validationError(): String? {
    if (name.isBlank()) return "name is required"
    if (description.isBlank()) return "description is required"
    if (hourlyPrice <= 0) return "hourlyPrice must be greater than zero"
    if (dailyPrice <= 0) return "dailyPrice must be greater than zero"
    return null
}

private fun ResultRow.toRoomDto(): RoomDto = RoomDto(
    id = this[RoomsTable.id].toString(),
    studioId = this[RoomsTable.studioId].toString(),
    name = this[RoomsTable.name],
    description = this[RoomsTable.description],
    hourlyPrice = this[RoomsTable.hourlyPrice],
    dailyPrice = this[RoomsTable.dailyPrice],
    features = this[RoomsTable.features],
    images = this[RoomsTable.images]
)

@Serializable
data class RoomDto(
    val id: String,
    val studioId: String,
    val name: String,
    val description: String,
    val hourlyPrice: Int,
    val dailyPrice: Int,
    val features: List<String>,
    val images: List<String>
)

@Serializable
data class CreateRoomRequest(
    val studioId: String,
    val name: String,
    val description: String,
    val hourlyPrice: Int,
    val dailyPrice: Int,
    val features: List<String> = emptyList(),
    val images: List<String> = emptyList()
)

@Serializable
data class UpdateRoomRequest(
    val name: String,
    val description: String,
    val hourlyPrice: Int,
    val dailyPrice: Int,
    val features: List<String> = emptyList(),
    val images: List<String> = emptyList()
)

@Serializable
data class ErrorResponse(val message: String)

private sealed class RoomOperationResult {
    data class Success(val room: RoomDto) : RoomOperationResult()
    object Forbidden : RoomOperationResult()
    object NotFound : RoomOperationResult()
}
