package com.studioreserve.admin

import com.studioreserve.auth.UserPrincipal
import com.studioreserve.studios.StudiosTable
import com.studioreserve.studios.VerificationStatus
import com.studioreserve.users.UserRole
import com.studioreserve.users.UsersTable
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.call
import io.ktor.server.auth.authenticate
import io.ktor.server.auth.principal
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.patch
import io.ktor.server.routing.route
import java.util.UUID
import kotlinx.serialization.Serializable
import org.jetbrains.exposed.sql.JoinType
import org.jetbrains.exposed.sql.ResultRow
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.select
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.singleOrNull
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.update

fun Route.adminStudioRoutes() {
    authenticate("auth-jwt") {
        route("/api/admin/studios") {
            get {
                val principal = call.principal<UserPrincipal>()
                    ?: return@get call.respond(HttpStatusCode.Unauthorized, AdminErrorResponse("Missing authentication token"))

                val role = runCatching { UserRole.valueOf(principal.role) }.getOrNull()
                    ?: return@get call.respond(HttpStatusCode.Forbidden, AdminErrorResponse("Unknown role"))

                if (role != UserRole.ADMIN) {
                    return@get call.respond(HttpStatusCode.Forbidden, AdminErrorResponse("Admin privileges required"))
                }

                val statusParam = call.request.queryParameters["status"]?.uppercase()
                val statusFilter = statusParam?.let {
                    runCatching { VerificationStatus.valueOf(it) }.getOrElse {
                        return@get call.respond(HttpStatusCode.BadRequest, AdminErrorResponse("Invalid status filter"))
                    }
                }

                val studios = transaction {
                    val join = StudiosTable.join(
                        UsersTable,
                        JoinType.INNER,
                        additionalConstraint = { StudiosTable.ownerId eq UsersTable.id }
                    )

                    val query = if (statusFilter != null) {
                        join.select { StudiosTable.verificationStatus eq statusFilter }
                    } else {
                        join.selectAll()
                    }

                    query.map { it.toAdminStudioDto() }
                }

                call.respond(studios)
            }

            patch("/{id}/verification") {
                val principal = call.principal<UserPrincipal>()
                    ?: return@patch call.respond(HttpStatusCode.Unauthorized, AdminErrorResponse("Missing authentication token"))

                val role = runCatching { UserRole.valueOf(principal.role) }.getOrNull()
                    ?: return@patch call.respond(HttpStatusCode.Forbidden, AdminErrorResponse("Unknown role"))

                if (role != UserRole.ADMIN) {
                    return@patch call.respond(HttpStatusCode.Forbidden, AdminErrorResponse("Admin privileges required"))
                }

                val studioIdParam = call.parameters["id"]
                    ?: return@patch call.respond(HttpStatusCode.BadRequest, AdminErrorResponse("Studio id is required"))

                val studioId = runCatching { UUID.fromString(studioIdParam) }.getOrElse {
                    return@patch call.respond(HttpStatusCode.BadRequest, AdminErrorResponse("Invalid studio id"))
                }

                val request = runCatching { call.receive<UpdateVerificationRequest>() }.getOrElse {
                    return@patch call.respond(HttpStatusCode.BadRequest, AdminErrorResponse("Invalid request payload"))
                }

                val updatedStudio = transaction {
                    val updatedRows = StudiosTable.update({ StudiosTable.id eq studioId }) {
                        it[verificationStatus] = request.status
                    }

                    if (updatedRows == 0) {
                        return@transaction null
                    }

                    val join = StudiosTable.join(
                        UsersTable,
                        JoinType.INNER,
                        additionalConstraint = { StudiosTable.ownerId eq UsersTable.id }
                    )

                    join.select { StudiosTable.id eq studioId }.singleOrNull()?.toAdminStudioDto()
                } ?: return@patch call.respond(HttpStatusCode.NotFound, AdminErrorResponse("Studio not found"))

                if (request.status == VerificationStatus.REJECTED && !request.reason.isNullOrBlank()) {
                    call.application.environment.log.info("Studio $studioId rejected: ${request.reason}")
                }

                call.respond(updatedStudio)
            }
        }
    }
}

private fun ResultRow.toAdminStudioDto(): AdminStudioDto = AdminStudioDto(
    id = this[StudiosTable.id].toString(),
    name = this[StudiosTable.name],
    ownerId = this[StudiosTable.ownerId].toString(),
    ownerName = this[UsersTable.name],
    city = this[StudiosTable.city],
    province = this[StudiosTable.province],
    verificationStatus = this[StudiosTable.verificationStatus],
    createdAt = this[StudiosTable.createdAt].toString(),
    description = this[StudiosTable.description]
)

@Serializable
data class AdminStudioDto(
    val id: String,
    val name: String,
    val ownerId: String,
    val ownerName: String,
    val city: String,
    val province: String,
    val verificationStatus: VerificationStatus,
    val createdAt: String,
    val description: String?
)

@Serializable
data class UpdateVerificationRequest(val status: VerificationStatus, val reason: String? = null)

@Serializable
data class AdminErrorResponse(val message: String)
