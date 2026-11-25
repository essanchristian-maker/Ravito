package com.ravito.app.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import androidx.room.Upsert
import com.ravito.app.domain.Operator
import kotlinx.coroutines.flow.Flow

@Dao
interface TransactionDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(tx: TransactionEntity): Long

    @Update
    suspend fun update(tx: TransactionEntity)

    @Query("DELETE FROM transactions WHERE id = :id")
    suspend fun delete(id: Long)

    @Query("SELECT * FROM transactions WHERE id = :id")
    fun observe(id: Long): Flow<TransactionEntity?>

    @Query("SELECT * FROM transactions WHERE id = :id")
    suspend fun get(id: Long): TransactionEntity?

    @Query("SELECT * FROM transactions WHERE timestamp >= :from AND timestamp < :to ORDER BY timestamp DESC")
    fun observeRange(from: Long, to: Long): Flow<List<TransactionEntity>>

    @Query("SELECT * FROM transactions WHERE timestamp >= :from AND timestamp < :to ORDER BY timestamp DESC")
    suspend fun getRange(from: Long, to: Long): List<TransactionEntity>

    @Query("SELECT * FROM transactions ORDER BY timestamp DESC")
    suspend fun getAll(): List<TransactionEntity>

    @Query("SELECT EXISTS(SELECT 1 FROM transactions WHERE dedupKey = :key)")
    suspend fun exists(key: String): Boolean

    /** Doublon probable venant d'une autre source (ex. SMS Wave + notification Wave). */
    @Query(
        """SELECT * FROM transactions WHERE operator = :operator AND amount = :amount
           AND timestamp BETWEEN :from AND :to AND source != :source"""
    )
    suspend fun findSimilar(operator: Operator, amount: Long, from: Long, to: Long, source: String): List<TransactionEntity>
}

@Dao
interface UnrecognizedDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(item: UnrecognizedEntity): Long

    @Query("SELECT * FROM unrecognized WHERE status = 'PENDING' ORDER BY timestamp DESC")
    fun observePending(): Flow<List<UnrecognizedEntity>>

    @Query("SELECT COUNT(*) FROM unrecognized WHERE status = 'PENDING' AND timestamp >= :from AND timestamp < :to")
    fun observePendingCount(from: Long, to: Long): Flow<Int>

    @Query("SELECT * FROM unrecognized WHERE id = :id")
    suspend fun get(id: Long): UnrecognizedEntity?

    @Query("UPDATE unrecognized SET status = :status WHERE id = :id")
    suspend fun setStatus(id: Long, status: ReviewStatus)
}

@Dao
interface MerchantDao {
    @Query("SELECT * FROM merchants ORDER BY name COLLATE NOCASE")
    fun observeAll(): Flow<List<MerchantEntity>>

    @Query("SELECT * FROM merchants")
    suspend fun getAll(): List<MerchantEntity>

    @Query("SELECT * FROM merchants WHERE phone = :phone")
    suspend fun get(phone: String): MerchantEntity?

    @Upsert
    suspend fun upsert(m: MerchantEntity)

    @Query("DELETE FROM merchants WHERE phone = :phone")
    suspend fun delete(phone: String)
}

@Dao
interface ReportDraftDao {
    @Query("SELECT * FROM report_drafts WHERE dateKey = :dateKey")
    fun observe(dateKey: String): Flow<ReportDraftEntity?>

    @Upsert
    suspend fun upsert(d: ReportDraftEntity)

    @Query("DELETE FROM report_drafts WHERE dateKey = :dateKey")
    suspend fun delete(dateKey: String)
}
