package com.studioreserve.users

import org.jetbrains.exposed.sql.Table
import org.jetbrains.exposed.sql.javatime.datetime

object UsersTable : Table("users") {
    val id = uuid("id")
    val name = varchar("name", 100)
    val phoneNumber = varchar("phone_number", 20).uniqueIndex()
    val email = varchar("email", 100).nullable()
    val passwordHash = varchar("password_hash", 255)
    val role = enumerationByName("role", 50, UserRole::class)
    val avatarUrl = varchar("avatar_url", 255).nullable()
    val createdAt = datetime("created_at")

    init {
        uniqueIndex("uk_users_email", email)
    }

    override val primaryKey = PrimaryKey(id)
}
