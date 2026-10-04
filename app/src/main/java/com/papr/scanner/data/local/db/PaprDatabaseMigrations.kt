package com.papr.scanner.data.local.db

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

val MIGRATION_1_2 = object : Migration(1, 2) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE pages ADD COLUMN confidentialMarks TEXT")
    }
}

val MIGRATION_2_3 = object : Migration(2, 3) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE pages ADD COLUMN signature_imagePath TEXT")
        db.execSQL("ALTER TABLE pages ADD COLUMN signature_x REAL")
        db.execSQL("ALTER TABLE pages ADD COLUMN signature_y REAL")
        db.execSQL("ALTER TABLE pages ADD COLUMN signature_width REAL")
        db.execSQL("ALTER TABLE pages ADD COLUMN signature_aspectRatio REAL")
    }
}

val MIGRATION_3_4 = object : Migration(3, 4) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE pages ADD COLUMN annotations TEXT")
    }
}
