package com.ravito.app.data

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import com.ravito.app.domain.Operator
import com.ravito.app.domain.ReportTx
import com.ravito.app.domain.TxKind
import com.ravito.app.domain.TxSource
import com.ravito.app.domain.TxStatus

@Entity(
    tableName = "transactions",
    indices = [Index(value = ["dedupKey"], unique = true), Index("timestamp"), Index("recipientNumber")],
)
data class TransactionEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val dedupKey: String,
    val operator: Operator,
    val kind: TxKind,
    val status: TxStatus,
    val amount: Long,
    val fee: Long?,
    val balanceAfter: Long?,
    val recipientNumber: String?,
    /** Nom lu dans le SMS ou dans les contacts (le carnet des marchands est prioritaire à l'affichage). */
    val recipientName: String?,
    val reference: String?,
    /** Heure de réception du SMS (ms). */
    val timestamp: Long,
    val simSlot: Int?,
    val source: TxSource,
    val sender: String?,
    val rawText: String?,
    val excluded: Boolean = false,
    val edited: Boolean = false,
    val note: String? = null,
    val createdAt: Long = System.currentTimeMillis(),
)

/** Transaction + nom du carnet. */
data class TransactionView(
    val tx: TransactionEntity,
    val merchantName: String?,
) {
    val displayName: String? get() = merchantName ?: tx.recipientName

    fun toReportTx() = ReportTx(
        operator = tx.operator,
        timestamp = tx.timestamp,
        amount = tx.amount,
        fee = tx.fee,
        balanceAfter = tx.balanceAfter,
        recipientNumber = tx.recipientNumber,
        recipientName = displayName,
        status = tx.status,
        excluded = tx.excluded,
    )
}

enum class ReviewStatus { PENDING, ADDED, DISMISSED }

/** SMS de l'opérateur au format inconnu, rangé dans « À vérifier ». */
@Entity(tableName = "unrecognized", indices = [Index(value = ["dedupKey"], unique = true), Index("timestamp")])
data class UnrecognizedEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val dedupKey: String,
    val operator: Operator,
    val sender: String?,
    val body: String,
    val timestamp: Long,
    val simSlot: Int?,
    val source: TxSource,
    val status: ReviewStatus = ReviewStatus.PENDING,
)

/** Carnet des marchands : numéro (10 chiffres) -> nom. */
@Entity(tableName = "merchants")
data class MerchantEntity(
    @PrimaryKey val phone: String,
    val name: String,
)

/** Texte du rapport modifié à la main, par date (yyyy-MM-dd). */
@Entity(tableName = "report_drafts")
data class ReportDraftEntity(
    @PrimaryKey val dateKey: String,
    val text: String,
    val updatedAt: Long = System.currentTimeMillis(),
)
