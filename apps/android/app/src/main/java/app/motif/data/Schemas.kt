package app.motif.data

import android.content.Context

/**
 * Statements from a `schemas/` asset (the shared SQL is packaged as assets),
 * without comments. The PRAGMA is left out: SQLiteOpenHelper's version handles it.
 */
internal fun schemaStatements(context: Context, asset: String): List<String> {
    val sql = context.assets.open(asset).bufferedReader().use { it.readText() }
    return sql.lines()
        .map { it.substringBefore("--").trimEnd() }
        .joinToString("\n")
        .split(";")
        .map { it.trim() }
        .filter { it.isNotEmpty() && !it.startsWith("PRAGMA", ignoreCase = true) }
}
