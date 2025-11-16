package com.studioreserve.studios

import com.studioreserve.auth.UserPrincipal
import com.studioreserve.rooms.RoomDto
import com.studioreserve.users.UserRole
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.call
import io.ktor.server.auth.authenticate
import io.ktor.server.auth.principal
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import java.util.UUID
import kotlinx.serialization.Serializable
import org.jetbrains.exposed.sql.ResultRow
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.SqlExpressionBuilder.inList
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.select
import org.jetbrains.exposed.sql.transactions.transaction

fun Route.studioRoutes() {
    route("/api/studios") {
        authenticate("auth-jwt") {
            post {
                val principal = call.principal<UserPrincipal>()
                    ?: return@post call.respond(HttpStatusCode.Unauthorized, ErrorResponse("Missing authentication token"))

                val role = runCatching { UserRole.valueOf(principal.role) }.getOrNull()
                    ?: return@post call.respond(HttpStatusCode.Forbidden, ErrorResponse("Unknown role"))

                if (role != UserRole.STUDIO_OWNER) {
                    return@post call.respond(HttpStatusCode.Forbidden, ErrorResponse("Only studio owners can create studios"))
                }

                val ownerId = runCatching { UUID.fromString(principal.userId) }.getOrNull()
                    ?: return@post call.respond(HttpStatusCode.BadRequest, ErrorResponse("Invalid user id"))

                val request = runCatching { call.receive<CreateStudioRequest>() }.getOrElse {
                    return@post call.respond(HttpStatusCode.BadRequest, ErrorResponse("Invalid request payload"))
                }

                request.validationError()?.let { message ->
                    return@post call.respond(HttpStatusCode.BadRequest, ErrorResponse(message))
                }

                val response = transaction {
                    val studioId = UUID.randomUUID()
                    val sanitizedPhotos = request.photos.mapNotNull { it.trim().takeIf(String::isNotEmpty) }
                    StudiosTable.insert { statement ->
                        statement[id] = studioId
                        statement[ownerId] = ownerId
                        statement[name] = request.name.trim()
                        statement[description] = request.description.trim()
                        statement[province] = request.province.trim()
                        statement[city] = request.city.trim()
                        statement[address] = request.address.trim()
                        statement[mapCoordinates] = request.mapCoordinates.trim()
                        statement[photos] = sanitizedPhotos
                        statement[verificationStatus] = VerificationStatus.PENDING
                    }
                    StudiosTable.select { StudiosTable.id eq studioId }.single().toStudioDto()
                }

                call.respond(HttpStatusCode.Created, response)
            }
        }

        get {
            val provinceFilter = call.request.queryParameters["province"]?.trim()?.lowercase()
            val cityFilter = call.request.queryParameters["city"]?.trim()?.lowercase()

            val minPriceParam = call.request.queryParameters["minPrice"]
            val maxPriceParam = call.request.queryParameters["maxPrice"]

            val minPrice = minPriceParam?.toIntOrNull()
                ?: if (minPriceParam == null) null else return@get call.respond(HttpStatusCode.BadRequest, ErrorResponse("minPrice must be a number"))
            val maxPrice = maxPriceParam?.toIntOrNull()
                ?: if (maxPriceParam == null) null else return@get call.respond(HttpStatusCode.BadRequest, ErrorResponse("maxPrice must be a number"))

            if (minPrice != null && maxPrice != null && minPrice > maxPrice) {
                return@get call.respond(HttpStatusCode.BadRequest, ErrorResponse("minPrice cannot be greater than maxPrice"))
            }

            val studios = transaction {
                val approvedStudios = StudiosTable
                    .select { StudiosTable.verificationStatus eq VerificationStatus.APPROVED }
                    .toList()

                val provinceFiltered = provinceFilter?.let { filter ->
                    approvedStudios.filter { it[StudiosTable.province].trim().lowercase() == filter }
                } ?: approvedStudios

                val cityFiltered = cityFilter?.let { filter ->
                    provinceFiltered.filter { it[StudiosTable.city].trim().lowercase() == filter }
                } ?: provinceFiltered

                val studioIds = cityFiltered.map { it[StudiosTable.id] }
                val roomsByStudio = if (studioIds.isEmpty()) {
                    emptyMap()
                } else {
                    RoomsTable
                        .select { RoomsTable.studioId inList studioIds }
                        .groupBy { it[RoomsTable.studioId] }
                }

                val priceFiltered = cityFiltered.filter { row ->
                    val studioId = row[StudiosTable.id]
                    val studioRooms = roomsByStudio[studioId]
                    if (minPrice == null && maxPrice == null) {
                        true
                    } else {
                        val minRoomPrice = studioRooms?.minOfOrNull { it[RoomsTable.hourlyPrice] }
                        if (minRoomPrice == null) {
                            false
                        } else {
                            val minCheck = minPrice?.let { minRoomPrice >= it } ?: true
                            val maxCheck = maxPrice?.let { minRoomPrice <= it } ?: true
                            minCheck && maxCheck
                        }
                    }
                }

                priceFiltered.map { it.toStudioDto() }
            }

            call.respond(StudiosResponse(studios))
        }

        get("/{id}") {
            val idParam = call.parameters["id"]
                ?: return@get call.respond(HttpStatusCode.BadRequest, ErrorResponse("Studio id is required"))

            val studioId = runCatching { UUID.fromString(idParam) }.getOrElse {
                return@get call.respond(HttpStatusCode.BadRequest, ErrorResponse("Invalid studio id"))
            }

            val detail = transaction {
                val studioRow = StudiosTable.select {
                    (StudiosTable.id eq studioId) and (StudiosTable.verificationStatus eq VerificationStatus.APPROVED)
                }.singleOrNull() ?: return@transaction null

                val rooms = RoomsTable.select { RoomsTable.studioId eq studioId }
                    .map { it.toRoomDto() }

                StudioDetailDto(
                    studio = studioRow.toStudioDto(),
                    rooms = rooms
                )
            } ?: return@get call.respond(HttpStatusCode.NotFound, ErrorResponse("Studio not found"))

            call.respond(detail)
        }
    }
}

private fun CreateStudioRequest.validationError(): String? {
    if (name.isBlank()) return "Name is required"
    if (description.isBlank()) return "Description is required"
    if (province.isBlank()) return "Province is required"
    if (city.isBlank()) return "City is required"
    if (address.isBlank()) return "Address is required"
    if (mapCoordinates.isBlank()) return "Map coordinates are required"
    return null
}

private fun ResultRow.toStudioDto(): StudioDto = StudioDto(
    id = this[StudiosTable.id].toString(),
    ownerId = this[StudiosTable.ownerId].toString(),
    name = this[StudiosTable.name],
    description = this[StudiosTable.description],
    province = this[StudiosTable.province],
    city = this[StudiosTable.city],
    address = this[StudiosTable.address],
    mapCoordinates = this[StudiosTable.mapCoordinates],
    photos = this[StudiosTable.photos],
    verificationStatus = this[StudiosTable.verificationStatus],
    createdAt = this[StudiosTable.createdAt].toString()
)

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
data class StudioDto(
    val id: String,
    val ownerId: String,
    val name: String,
    val description: String,
    val province: String,
    val city: String,
    val address: String,
    val mapCoordinates: String,
    val photos: List<String>,
    val verificationStatus: VerificationStatus,
    val createdAt: String
)

@Serializable
data class StudioDetailDto(
    val studio: StudioDto,
    val rooms: List<RoomDto>
)

@Serializable
data class StudiosResponse(
    val studios: List<StudioDto>
)

@Serializable
data class CreateStudioRequest(
    val name: String,
    val description: String,
    val province: String,
    val city: String,
    val address: String,
    val mapCoordinates: String,
    val photos: List<String> = emptyList()
)

@Serializable
data class ErrorResponse(val message: String)
