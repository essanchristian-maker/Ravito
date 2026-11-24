package com.ravito.app.data

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverter
import androidx.room.TypeConverters
import com.ravito.app.domain.Operator
import com.ravito.app.domain.TxKind
import com.ravito.app.domain.TxSource
import com.ravito.app.domain.TxStatus
import net.zetetic.database.sqlcipher.SupportOpenHelperFactory
import java.security.KeyStore
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

class Converters {
    @TypeConverter fun opToString(v: Operator): String = v.name
    @TypeConverter fun stringToOp(v: String): Operator = Operator.valueOf(v)
    @TypeConverter fun kindToString(v: TxKind): String = v.name
    @TypeConverter fun stringToKind(v: String): TxKind = TxKind.valueOf(v)
    @TypeConverter fun statusToString(v: TxStatus): String = v.name
    @TypeConverter fun stringToStatus(v: String): TxStatus = TxStatus.valueOf(v)
    @TypeConverter fun sourceToString(v: TxSource): String = v.name
    @TypeConverter fun stringToSource(v: String): TxSource = TxSource.valueOf(v)
    @TypeConverter fun reviewToString(v: ReviewStatus): String = v.name
    @TypeConverter fun stringToReview(v: String): ReviewStatus = ReviewStatus.valueOf(v)
}

@Database(
    entities = [TransactionEntity::class, UnrecognizedEntity::class, MerchantEntity::class, ReportDraftEntity::class],
    version = 1,
    exportSchema = true,
)
@TypeConverters(Converters::class)
abstract class RavitoDatabase : RoomDatabase() {
    abstract fun transactions(): TransactionDao
    abstract fun unrecognized(): UnrecognizedDao
    abstract fun merchants(): MerchantDao
    abstract fun drafts(): ReportDraftDao

    companion object {
        private const val NAME = "ravito.db"

        fun create(context: Context): RavitoDatabase {
            System.loadLibrary("sqlcipher")
            val passphrase = DatabaseKey.passphrase(context)
            return Room.databaseBuilder(context, RavitoDatabase::class.java, NAME)
                .openHelperFactory(SupportOpenHelperFactory(passphrase))
                .build()
        }
    }
}

/**
 * Clé de chiffrement de la base : 32 octets aléatoires, eux-mêmes chiffrés par une clé
 * matérielle Android Keystore (non exportable). Sans le téléphone, la base est illisible.
 */
object DatabaseKey {
    private const val KEY_ALIAS = "ravito_db_wrap_key"
    private const val PREFS = "ravito_keys"
    private const val PREF_BLOB = "db_key_blob"

    @Synchronized
    fun passphrase(context: Context): ByteArray {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val stored = prefs.getString(PREF_BLOB, null)
        if (stored != null) {
            val blob = Base64.decode(stored, Base64.NO_WRAP)
            val iv = blob.copyOfRange(0, 12)
            val data = blob.copyOfRange(12, blob.size)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, wrapKey(), GCMParameterSpec(128, iv))
            return cipher.doFinal(data)
        }
        val raw = ByteArray(32).also { SecureRandom().nextBytes(it) }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, wrapKey())
        val blob = cipher.iv + cipher.doFinal(raw)
        prefs.edit().putString(PREF_BLOB, Base64.encodeToString(blob, Base64.NO_WRAP)).commit()
        return raw
    }

    private fun wrapKey(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getEntry(KEY_ALIAS, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        gen.init(
            KeyGenParameterSpec.Builder(KEY_ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build(),
        )
        return gen.generateKey()
    }
}
