package com.vidoora.backend.modules.auth

import com.vidoora.backend.modules.user.UsersTable
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.javatime.timestamp
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import kotlinx.serialization.json.*

object PasswordResets : Table("password_reset_tokens") {
    val hash=varchar("token_hash",64)
    val user=uuid("user_id").references(UsersTable.id)
    val created=timestamp("created_at")
    val expires=timestamp("expires_at")
    val consumed=timestamp("consumed_at").nullable()
    override val primaryKey=PrimaryKey(hash)
}
interface PasswordResetMailer {
    val enabled: Boolean
    fun send(email: String,code: String): Boolean
}
/** Disabled until a verified sender and private API key are configured on the server. */
class ResendPasswordResetMailer : PasswordResetMailer {
    private val key=System.getenv("PASSWORD_RESET_RESEND_KEY").orEmpty()
    private val from=System.getenv("PASSWORD_RESET_FROM").orEmpty()
    override val enabled get()=key.isNotBlank() && from.isNotBlank()
    private val client=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).followRedirects(HttpClient.Redirect.NEVER).build()
    override fun send(email: String,code: String): Boolean {
        if(!enabled)return false
        val body=buildJsonObject {
            put("from",from);put("to",buildJsonArray {add(email)})
            put("subject","Reset your Vidoora password")
            put("text","Paste this single-use code into Vidoora's Forgot password screen:\n\n$code\n\nIt expires in 15 minutes. If you did not request this, ignore this email. Never share this code.")
        }
        return try {
            val request=HttpRequest.newBuilder(URI("https://api.resend.com/emails")).timeout(Duration.ofSeconds(15))
                .header("Authorization","Bearer $key").header("Content-Type","application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body.toString())).build()
            client.send(request,HttpResponse.BodyHandlers.discarding()).statusCode() in 200..299
        } catch(_: Exception) {false} // Never log the body, recipient or reset token.
    }
}
@kotlinx.serialization.Serializable data class PasswordResetRequest(val email: String)
@kotlinx.serialization.Serializable data class PasswordResetConfirm(val code: String,val password: String) {
    override fun toString()="PasswordResetConfirm(<redacted>)"
}

object PasswordResetMailers {
    fun environment(): PasswordResetMailer = GmailPasswordResetMailer().takeIf {it.enabled} ?: ResendPasswordResetMailer()
}
/** Optional Gmail sender: requires a private app password, never the account login password. */
class GmailPasswordResetMailer : PasswordResetMailer {
    private val username=System.getenv("PASSWORD_RESET_GMAIL_USER").orEmpty()
    private val password=System.getenv("PASSWORD_RESET_GMAIL_APP_PASSWORD").orEmpty()
    override val enabled get()=username.endsWith("@gmail.com") && password.isNotBlank()
    override fun send(email: String,code: String): Boolean {
        if(!enabled)return false
        return try {
            val properties=java.util.Properties().apply {
                setProperty("mail.smtps.host","smtp.gmail.com");setProperty("mail.smtps.port","465")
                setProperty("mail.smtps.auth","true");setProperty("mail.smtps.ssl.enable","true")
                setProperty("mail.smtps.ssl.checkserveridentity","true")
                setProperty("mail.smtps.ssl.protocols","TLSv1.2 TLSv1.3")
                setProperty("mail.smtps.connectiontimeout","10000");setProperty("mail.smtps.timeout","10000")
                setProperty("mail.smtps.writetimeout","10000")
            }
            val session=jakarta.mail.Session.getInstance(properties)
            val message=jakarta.mail.internet.MimeMessage(session).apply {
                setFrom(jakarta.mail.internet.InternetAddress(username,true))
                setRecipient(jakarta.mail.Message.RecipientType.TO,jakarta.mail.internet.InternetAddress(email,true))
                subject="Reset your Vidoora password"
                setText("Paste this single-use code into Vidoora's Forgot password screen:\n\n$code\n\nIt expires in 15 minutes. If you did not request this, ignore this email. Never share the code.","UTF-8")
            }
            val transport=session.getTransport("smtps")
            try {transport.connect("smtp.gmail.com",username,password);transport.sendMessage(message,message.allRecipients)} finally {transport.close()}
            true
        } catch(_: Exception) {false}
    }
}
