package app.rasidi

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import app.rasidi.data.LedgerRepository
import app.rasidi.data.RasidiDatabase

object TestUtil {
    val ctx: Context get() = ApplicationProvider.getApplicationContext()
    fun memDb(): RasidiDatabase = Room.inMemoryDatabaseBuilder(ctx, RasidiDatabase::class.java).allowMainThreadQueries().build()
    fun fileDb(name: String): RasidiDatabase = Room.databaseBuilder(ctx, RasidiDatabase::class.java, name).allowMainThreadQueries().build()
    fun repo(db: RasidiDatabase, clock: () -> Long = System::currentTimeMillis) = LedgerRepository(db, clock)
}
