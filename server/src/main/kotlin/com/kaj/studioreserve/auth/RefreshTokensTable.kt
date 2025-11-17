package com.kaj.studioreserve.auth

import com.kaj.studioreserve.users.UsersTable
import org.jetbrains.exposed.sql.Table
import org.jetbrains.exposed.sql.javatime.datetime

object RefreshTokensTable : Table("refresh_tokens") {
    val id = uuid("id")
    val tokenId = uuid("token_id").uniqueIndex()
    val userId = uuid("user_id").references(UsersTable.id)
    val expiresAt = datetime("expires_at")
    val revoked = bool("revoked").default(false)
    val createdAt = datetime("created_at")

    override val primaryKey = PrimaryKey(id)
}
