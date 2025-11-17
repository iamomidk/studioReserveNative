package com.studioreserve.db.columns

import java.sql.Array as JdbcArray
import org.jetbrains.exposed.sql.Column
import org.jetbrains.exposed.sql.ColumnType
import org.jetbrains.exposed.sql.Table
import org.jetbrains.exposed.sql.transactions.TransactionManager

/**
 * Simple column type for PostgreSQL `text[]` columns so we can persist a list of strings
 * without introducing a JSON dependency. Values are represented in Kotlin as [List]s.
 */
class TextArrayColumnType : ColumnType<List<String>>() {
    override fun sqlType(): String = "TEXT[]"

    override fun valueFromDB(value: Any): List<String> = when (value) {
        is JdbcArray -> {
            @Suppress("UNCHECKED_CAST")
            val array = value.array
            when (array) {
                is Array<*> -> array.filterIsInstance<String>()
                is List<*> -> array.filterIsInstance<String>()
                else -> (array as? Array<*>)?.filterIsInstance<String>() ?: emptyList()
            }
        }
        is Array<*> -> value.filterIsInstance<String>()
        is Iterable<*> -> value.filterIsInstance<String>()
        is String -> parsePgArrayString(value)
        else -> error("Unexpected value for TEXT[] column: ${value::class.qualifiedName}")
    }

    override fun notNullValueToDB(value: List<String>): Any {
        val connection = TransactionManager.currentOrNull()?.connection?.connection
        return connection?.createArrayOf("text", value.toTypedArray()) ?: value.toTypedArray()
    }

    override fun nonNullValueToString(value: List<String>): String =
        value.joinToString(prefix = "{", postfix = "}") { entry ->
            "\"" + entry.replace("\"", "\\\"") + "\""
        }

    private fun parsePgArrayString(value: String): List<String> {
        if (value.length <= 2) return emptyList()
        val trimmed = value.removePrefix("{").removeSuffix("}")
        if (trimmed.isBlank()) return emptyList()
        return trimmed.split(',').map { token ->
            token.trim().removePrefix("\"").removeSuffix("\"")
        }
    }
}

fun Table.textArray(name: String): Column<List<String>> = registerColumn(name, TextArrayColumnType())
