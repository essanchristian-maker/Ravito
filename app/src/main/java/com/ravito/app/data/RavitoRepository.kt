package com.ravito.app.data

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.ContactsContract
import android.provider.Telephony
import android.telephony.SubscriptionManager
import android.util.Log
import androidx.core.content.ContextCompat
import com.ravito.app.domain.Category
import com.ravito.app.domain.Formats
import com.ravito.app.domain.Operator
import com.ravito.app.domain.SmsParser
import com.ravito.app.domain.TxKind
import com.ravito.app.domain.TxSource
import com.ravito.app.domain.TxStatus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.OutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class RavitoRepository(
    private val context: Context,
    val db: RavitoDatabase,
    val settings: SettingsStore,
) {
    private val txDao = db.transactions()
    private val reviewDao = db.unrecognized()
    private val merchantDao = db.merchants()
    val drafts = db.drafts()

    private val syncMutex = Mutex()
    private val _syncing = MutableStateFlow(false)
    val syncing: StateFlow<Boolean> = _syncing.asStateFlow()

    enum class Outcome { ADDED, DUPLICATE, IGNORED, TO_REVIEW, UNKNOWN_SENDER }

    data class SyncReport(val scanned: Int, val added: Int, val toReview: Int, val error: String? = null)

    // ------------------------------------------------------------------ lecture

    fun observeTransactions(from: Long, to: Long): Flow<List<TransactionView>> =
        combine(txDao.observeRange(from, to), merchantDao.observeAll()) { txs, merchants ->
            val book = merchants.associateBy { it.phone }
            txs.map { t -> t.recipientNumber?.let(book::get).let { m -> TransactionView(t, m?.name, m?.category) } }
        }

    fun observeTransaction(id: Long): Flow<TransactionView?> =
        combine(txDao.observe(id), merchantDao.observeAll()) { tx, merchants ->
            tx?.let { t ->
                val m = merchants.firstOrNull { it.phone == t.recipientNumber }
                TransactionView(t, m?.name, m?.category)
            }
        }

    fun observePendingReview() = reviewDao.observePending()
    fun observePendingReviewCount(from: Long, to: Long) = reviewDao.observePendingCount(from, to)
    fun observeMerchants() = merchantDao.observeAll()

    suspend fun getTransaction(id: Long) = txDao.get(id)
    suspend fun getReview(id: Long) = reviewDao.get(id)

    // ------------------------------------------------------------------ traitement d'un message

    /** SMS entrant (récepteur en direct ou relecture de la boîte). */
    suspend fun processSms(sender: String?, body: String, timestamp: Long, subscriptionId: Int?): Outcome {
        val s = settings.current
        val op = Operator.matchSender(sender, s.activeSenders()) ?: return Outcome.UNKNOWN_SENDER
        val slot = subscriptionId?.let { simSlotFor(it) }
        val day = SimpleDateFormat("yyyyMMdd", Locale.US).format(Date(timestamp))
        return process(op, body, timestamp, slot, TxSource.SMS, sender, salt = day)
    }

    /** Notification de l'application Wave. [stableId] = identifiant stable de la notification. */
    suspend fun processNotification(op: Operator, text: String, timestamp: Long, stableId: String): Outcome {
        if (op !in settings.current.enabledOperators) return Outcome.IGNORED
        return process(op, text, timestamp, null, TxSource.NOTIFICATION, "Notification ${op.label}", salt = stableId)
    }

    /** SMS collé dans « Tester un SMS » : ajouté à l'heure actuelle, comme une saisie manuelle. */
    suspend fun addTestSms(op: Operator, body: String): Outcome {
        // L'opérateur doit être actif pour apparaître dans le rapport.
        if (op !in settings.current.enabledOperators) settings.update { it.copy(enabledOperators = it.enabledOperators + op) }
        return process(op, body, System.currentTimeMillis(), null, TxSource.MANUAL, "Test (SMS collé)", salt = "test")
    }

    private suspend fun process(
        op: Operator,
        body: String,
        timestamp: Long,
        slot: Int?,
        source: TxSource,
        sender: String?,
        salt: String,
    ): Outcome = withContext(Dispatchers.IO) {
        when (val r = SmsParser.parse(op, body)) {
            is SmsParser.Result.Ignored -> Outcome.IGNORED
            is SmsParser.Result.Unrecognized -> {
                val key = SmsParser.dedupKey(op, null, body, salt)
                val id = reviewDao.insert(
                    UnrecognizedEntity(
                        dedupKey = key, operator = op, sender = sender, body = body,
                        timestamp = timestamp, simSlot = slot, source = source,
                    ),
                )
                if (id > 0) Outcome.TO_REVIEW else Outcome.DUPLICATE
            }
            is SmsParser.Result.Transaction -> {
                val p = r.tx
                val key = SmsParser.dedupKey(op, p.reference, body, salt)
                if (txDao.exists(key)) return@withContext Outcome.DUPLICATE
                if (p.reference == null && isCrossSourceDuplicate(op, p.amount, p.recipientNumber, timestamp, source)) {
                    return@withContext Outcome.DUPLICATE
                }
                val name = p.recipientName ?: p.recipientNumber?.let { contactName(it) }
                val id = txDao.insert(
                    TransactionEntity(
                        dedupKey = key,
                        operator = op,
                        kind = p.kind,
                        status = p.status,
                        amount = p.amount,
                        fee = p.fee,
                        balanceAfter = p.balanceAfter,
                        recipientNumber = p.recipientNumber,
                        recipientName = name,
                        reference = p.reference,
                        timestamp = timestamp,
                        simSlot = slot,
                        source = source,
                        sender = sender,
                        rawText = body,
                    ),
                )
                if (id > 0) Outcome.ADDED else Outcome.DUPLICATE
            }
        }
    }

    private suspend fun isCrossSourceDuplicate(op: Operator, amount: Long, number: String?, ts: Long, source: TxSource): Boolean {
        val window = 5 * 60_000L
        return txDao.findSimilar(op, amount, ts - window, ts + window, source.name)
            .any { number == null || it.recipientNumber == null || it.recipientNumber == number }
    }

    // ------------------------------------------------------------------ rattrapage (relecture de la boîte SMS)

    /**
     * Relit la boîte de réception depuis la dernière lecture (avec 2 h de marge, les doublons sont écartés).
     * @param fullDays si non nul, relit les N derniers jours.
     */
    suspend fun syncInbox(fullDays: Int? = null): SyncReport = syncMutex.withLock {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.READ_SMS) != PackageManager.PERMISSION_GRANTED) {
            return SyncReport(0, 0, 0, "Autorisation SMS refusée")
        }
        _syncing.value = true
        try {
            withContext(Dispatchers.IO) {
                val s = settings.current
                val now = System.currentTimeMillis()
                val since = when {
                    fullDays != null -> now - fullDays * 86_400_000L
                    s.lastInboxSync == 0L -> now - s.firstSyncDays * 86_400_000L
                    else -> s.lastInboxSync - 2 * 3_600_000L
                }
                val senders = s.activeSenders()
                var scanned = 0
                var added = 0
                var review = 0
                readInbox(since) { address, body, date, subId ->
                    if (Operator.matchSender(address, senders) == null) return@readInbox
                    scanned++
                    when (processSms(address, body, date, subId)) {
                        Outcome.ADDED -> added++
                        Outcome.TO_REVIEW -> review++
                        else -> Unit
                    }
                }
                settings.update { it.copy(lastInboxSync = now) }
                SyncReport(scanned, added, review)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Synchronisation impossible", e)
            SyncReport(0, 0, 0, e.message ?: e.javaClass.simpleName)
        } finally {
            _syncing.value = false
        }
    }

    private suspend fun readInbox(since: Long, onSms: suspend (String?, String, Long, Int?) -> Unit) {
        val uri = Telephony.Sms.Inbox.CONTENT_URI
        val base = arrayOf(Telephony.Sms.ADDRESS, Telephony.Sms.BODY, Telephony.Sms.DATE)
        val withSub = base + "sub_id"
        val cursor = try {
            context.contentResolver.query(uri, withSub, "${Telephony.Sms.DATE} > ?", arrayOf(since.toString()), "${Telephony.Sms.DATE} ASC")
        } catch (e: Exception) {
            // Certains constructeurs n'ont pas la colonne sub_id.
            context.contentResolver.query(uri, base, "${Telephony.Sms.DATE} > ?", arrayOf(since.toString()), "${Telephony.Sms.DATE} ASC")
        } ?: return
        cursor.use { c ->
            val iAddr = c.getColumnIndex(Telephony.Sms.ADDRESS)
            val iBody = c.getColumnIndex(Telephony.Sms.BODY)
            val iDate = c.getColumnIndex(Telephony.Sms.DATE)
            val iSub = c.getColumnIndex("sub_id")
            while (c.moveToNext()) {
                val body = c.getString(iBody) ?: continue
                val sub = if (iSub >= 0 && !c.isNull(iSub)) c.getInt(iSub) else null
                onSms(c.getString(iAddr), body, c.getLong(iDate), sub)
            }
        }
    }

    // ------------------------------------------------------------------ SIM & contacts

    fun simSlotFor(subscriptionId: Int): Int? {
        if (subscriptionId < 0) return null
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.READ_PHONE_STATE) != PackageManager.PERMISSION_GRANTED) return null
        return try {
            val sm = context.getSystemService(SubscriptionManager::class.java)
            sm?.getActiveSubscriptionInfo(subscriptionId)?.simSlotIndex?.plus(1)
        } catch (e: SecurityException) {
            null
        }
    }

    fun contactName(number: String): String? {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CONTACTS) != PackageManager.PERMISSION_GRANTED) return null
        return try {
            val uri = Uri.withAppendedPath(ContactsContract.PhoneLookup.CONTENT_FILTER_URI, Uri.encode(number))
            context.contentResolver.query(uri, arrayOf(ContactsContract.PhoneLookup.DISPLAY_NAME), null, null, null)?.use {
                if (it.moveToFirst()) it.getString(0) else null
            }
        } catch (e: Exception) {
            null
        }
    }

    /** Lit le nom et le numéro d'un contact choisi via le sélecteur de contacts. */
    fun readPickedPhone(uri: Uri): Pair<String, String>? = try {
        context.contentResolver.query(
            uri,
            arrayOf(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME, ContactsContract.CommonDataKinds.Phone.NUMBER),
            null, null, null,
        )?.use { c ->
            if (c.moveToFirst()) {
                val name = c.getString(0).orEmpty()
                val phone = Formats.normalizePhone(c.getString(1)).orEmpty()
                if (phone.isNotEmpty()) name to phone else null
            } else null
        }
    } catch (e: Exception) {
        null
    }

    // ------------------------------------------------------------------ modifications

    suspend fun setExcluded(id: Long, excluded: Boolean) {
        txDao.get(id)?.let { txDao.update(it.copy(excluded = excluded)) }
    }

    suspend fun updateTransaction(tx: TransactionEntity) = txDao.update(tx.copy(edited = true))

    suspend fun deleteTransaction(id: Long) = txDao.delete(id)

    suspend fun addManual(
        op: Operator,
        amount: Long,
        number: String?,
        name: String?,
        timestamp: Long,
        fee: Long?,
        reference: String?,
        status: TxStatus = TxStatus.SUCCESS,
        rawText: String? = null,
        fromReviewId: Long? = null,
    ): Long {
        val key = if (!reference.isNullOrBlank()) SmsParser.dedupKey(op, reference, "")
        else "${op.name}:manual:${System.nanoTime()}"
        val id = txDao.insert(
            TransactionEntity(
                dedupKey = key, operator = op, kind = TxKind.TRANSFER, status = status, amount = amount, fee = fee,
                balanceAfter = null, recipientNumber = Formats.normalizePhone(number),
                recipientName = name?.takeIf { it.isNotBlank() }, reference = reference?.takeIf { it.isNotBlank() },
                timestamp = timestamp, simSlot = null, source = TxSource.MANUAL, sender = null, rawText = rawText,
                edited = true,
            ),
        )
        if (fromReviewId != null) reviewDao.setStatus(fromReviewId, ReviewStatus.ADDED)
        return id
    }

    suspend fun dismissReview(id: Long) = reviewDao.setStatus(id, ReviewStatus.DISMISSED)

    /** Enregistre le nom d'un numéro. [category] null = garder la rubrique existante. */
    suspend fun saveMerchant(phone: String, name: String, category: Category? = null) {
        val p = Formats.normalizePhone(phone) ?: return
        if (name.isBlank()) return
        val existing = merchantDao.get(p)
        merchantDao.upsert(MerchantEntity(p, name.trim(), category ?: existing?.category))
    }

    /** Range un numéro dans une rubrique du rapport (cabine / agent), pour toujours. */
    suspend fun setCategory(phone: String, category: Category?) {
        val p = Formats.normalizePhone(phone) ?: return
        val existing = merchantDao.get(p)
        if (category == null && existing?.name.isNullOrBlank()) {
            merchantDao.delete(p)
            return
        }
        merchantDao.upsert(MerchantEntity(p, existing?.name.orEmpty(), category))
    }

    suspend fun deleteMerchant(phone: String) = merchantDao.delete(phone)

    // ------------------------------------------------------------------ export

    /** Export CSV (séparateur « ; », compatible Excel en français). */
    suspend fun exportCsv(out: OutputStream) = withContext(Dispatchers.IO) {
        val book = merchantDao.getAll().associateBy { it.phone }
        val dateFmt = SimpleDateFormat("dd/MM/yyyy", Locale.FRANCE)
        val timeFmt = SimpleDateFormat("HH:mm", Locale.FRANCE)
        out.bufferedWriter(Charsets.UTF_8).use { w ->
            w.write("﻿")
            w.write("Date;Heure;Opérateur;Type;Rubrique;Statut;Montant;Frais;Solde après;Numéro;Nom;Référence;SIM;Source;Exclu du rapport;SMS\r\n")
            for (t in txDao.getAll()) {
                val cols = listOf(
                    dateFmt.format(Date(t.timestamp)),
                    timeFmt.format(Date(t.timestamp)),
                    t.operator.label,
                    t.kind.label,
                    if (t.kind == TxKind.RECEIVED) "UV reçues" else t.recipientNumber?.let(book::get)?.category?.label.orEmpty(),
                    if (t.status == TxStatus.SUCCESS) "Réussi" else "Échoué",
                    t.amount.toString(),
                    t.fee?.toString().orEmpty(),
                    t.balanceAfter?.toString().orEmpty(),
                    t.recipientNumber.orEmpty(),
                    (t.recipientNumber?.let(book::get)?.name?.takeIf { it.isNotBlank() } ?: t.recipientName).orEmpty(),
                    t.reference.orEmpty(),
                    t.simSlot?.let { "SIM $it" }.orEmpty(),
                    t.source.label,
                    if (t.excluded) "Oui" else "Non",
                    t.rawText.orEmpty(),
                )
                w.write(cols.joinToString(";") { csv(it) })
                w.write("\r\n")
            }
        }
    }

    private fun csv(v: String): String {
        val clean = v.replace("\r", " ").replace("\n", " ")
        return if (clean.contains('"') || clean.contains(';')) {
            "\"" + clean.replace("\"", "\"\"") + "\""
        } else clean
    }

    companion object {
        const val TAG = "Ravito"
    }
}
