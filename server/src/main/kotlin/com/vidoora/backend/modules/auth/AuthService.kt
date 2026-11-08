package com.vidoora.backend.modules.auth

import com.vidoora.backend.infrastructure.DatabaseFactory.dbQuery
import com.vidoora.backend.modules.user.UserDto
import com.vidoora.backend.modules.user.UsersTable
import kotlinx.serialization.Serializable
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.SqlExpressionBuilder.lessEq
import kotlinx.coroutines.Dispatchers
import java.security.MessageDigest
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.or
import org.jetbrains.exposed.sql.select
import java.time.Instant
import java.util.UUID

class AuthService(private val jwtConfig: JwtConfig,private val database: Database?=null, private val resetMailer: PasswordResetMailer=PasswordResetMailers.environment()) {
    private suspend fun <T> query(block: suspend ()->T): T = if(database==null) dbQuery(block)
        else org.jetbrains.exposed.sql.transactions.experimental.newSuspendedTransaction(Dispatchers.IO,db=database) { block() }
    private fun hash(token: String)=MessageDigest.getInstance("SHA-256").digest(token.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
    private fun issue(user: UserDto): TokenResponse {
        val now=Instant.now()
        // Expired session metadata is no longer useful; never touch media/user records.
        RefreshSessionsTable.deleteWhere { (userId eq UUID.fromString(user.id)) and (expiresAt lessEq now) }
        val session=UUID.randomUUID()
        val expiry=now.plusMillis(jwtConfig.refreshLifetimeMs)
        val refresh=jwtConfig.generateSessionRefreshToken(user.id,session,expiry)
        RefreshSessionsTable.insert {
            it[id]=session; it[userId]=UUID.fromString(user.id); it[tokenHash]=hash(refresh)
            it[createdAt]=now; it[expiresAt]=expiry
        }
        return TokenResponse(jwtConfig.generateAccessToken(user.id,user.email,user.role),refresh,user)
    }

    suspend fun register(request: RegisterRequest): TokenResponse {
        val generated = java.math.BigInteger(UUID.randomUUID().toString().replace("-",""),16).toString()
        val usernameTrimmed = request.username.trim().ifBlank { "u"+generated.take(30) }
        val emailTrimmed = request.email.trim().lowercase()
        val displayNameTrimmed = request.displayName.trim().ifBlank { "Vidoora member" }

        if (usernameTrimmed.length < 3 || usernameTrimmed.length > 32) {
            throw IllegalArgumentException("Username must be between 3 and 32 characters")
        }
        if (!emailTrimmed.contains("@") || emailTrimmed.length > 255) {
            throw IllegalArgumentException("A valid email address is required")
        }
        if (request.password.length < 8) {
            throw IllegalArgumentException("Password must be at least 8 characters long")
        }
        if (displayNameTrimmed.isEmpty() || displayNameTrimmed.length > 64) {
            throw IllegalArgumentException("Display name must be between 1 and 64 characters")
        }

        val existingUser = query {
            UsersTable.select {
                (UsersTable.username eq usernameTrimmed) or (UsersTable.email eq emailTrimmed)
            }.firstOrNull()
        }

        if (existingUser != null) {
            val duplicateField = if (existingUser[UsersTable.username].equals(usernameTrimmed, ignoreCase = true)) "Username" else "Email"
            throw IllegalArgumentException("$duplicateField is already registered")
        }

        val hashedPassword = PasswordHasher.hash(request.password)
        val newUserId = UUID.randomUUID()

        return query {
            UsersTable.insert {
                it[id] = newUserId
                it[username] = usernameTrimmed
                it[email] = emailTrimmed
                it[passwordHash] = hashedPassword
                it[displayName] = displayNameTrimmed
                it[role] = "USER"
                it[isSuspended] = false
                it[createdAt] = Instant.now()
                it[updatedAt] = Instant.now()
            }
        if(request.creatorInviteCode.isNotBlank()) {
            CreatorInvites.consume(request.creatorInviteCode.trim(),newUserId)
            UsersTable.update({UsersTable.id eq newUserId}) {it[role]="CREATOR"}
        }
        val userDto=rowToUserDto(UsersTable.select {UsersTable.id eq newUserId}.single())
        issue(userDto)
        }
    }
    suspend fun login(request: LoginRequest): TokenResponse {
        val emailTrimmed = request.email.trim().lowercase()
        if (emailTrimmed.isBlank() || request.password.isBlank()) {
            throw IllegalArgumentException("Email and password are required")
        }

        val row = query {
            UsersTable.select { UsersTable.email eq emailTrimmed }.firstOrNull()
        } ?: throw IllegalArgumentException("Invalid email or password")

        if (row[UsersTable.isSuspended]) {
            throw IllegalStateException("Account is suspended")
        }

        val isValidPassword = PasswordHasher.verify(request.password, row[UsersTable.passwordHash])
        if (!isValidPassword) {
            throw IllegalArgumentException("Invalid email or password")
        }

        return query {
            val current=UsersTable.select { UsersTable.id eq row[UsersTable.id] }.single()
            if(current[UsersTable.isSuspended]) throw IllegalStateException("Account is suspended")
            issue(rowToUserDto(current))
        }
    }

    suspend fun refreshToken(refreshTokenStr: String): TokenResponse {
        val decoded=decodeRefresh(refreshTokenStr) ?: throw InvalidRefreshToken()
        val session=try { UUID.fromString(decoded.getClaim("sid").asString()) } catch (_: Exception) { throw InvalidRefreshToken() }
        val owner=try { UUID.fromString(decoded.subject) } catch (_: Exception) { throw InvalidRefreshToken() }
        return query {
            val row=RefreshSessionsTable.select { (RefreshSessionsTable.id eq session) and (RefreshSessionsTable.userId eq owner) }.forUpdate().singleOrNull()
                ?: throw InvalidRefreshToken()
            if(row[RefreshSessionsTable.revokedAt]!=null || !row[RefreshSessionsTable.expiresAt].isAfter(Instant.now()) ||
                !MessageDigest.isEqual(row[RefreshSessionsTable.tokenHash].toByteArray(),hash(refreshTokenStr).toByteArray())) throw InvalidRefreshToken()
            val user=UsersTable.select { UsersTable.id eq owner }.singleOrNull() ?: throw InvalidRefreshToken()
            if(user[UsersTable.isSuspended]) throw InvalidRefreshToken()
            val dto=rowToUserDto(user)
            val next=jwtConfig.generateSessionRefreshToken(dto.id,session,row[RefreshSessionsTable.expiresAt])
            RefreshSessionsTable.update({ RefreshSessionsTable.id eq session }) {
                it[tokenHash]=hash(next); it[lastUsedAt]=Instant.now()
            }
            TokenResponse(jwtConfig.generateAccessToken(dto.id,dto.email,dto.role),next,dto)
        }
    }

    private fun decodeRefresh(token: String): com.auth0.jwt.interfaces.DecodedJWT? {
        if(token.isBlank() || token.length>4096) return null
        val decoded=try { jwtConfig.verifier.verify(token) } catch (_: Exception) { return null }
        return decoded.takeIf { it.getClaim("type").asString()=="refresh" && !it.id.isNullOrBlank() }
    }

    /** Possession of any valid generation may revoke only its own session, never another login. */
    suspend fun logout(refreshToken: String) {
        val decoded=decodeRefresh(refreshToken) ?: return // Idempotent for invalid/expired/legacy tokens.
        val session=try { UUID.fromString(decoded.getClaim("sid").asString()) } catch (_: Exception) { return }
        val owner=try { UUID.fromString(decoded.subject) } catch (_: Exception) { return }
        query {
            val row=RefreshSessionsTable.select { (RefreshSessionsTable.id eq session) and (RefreshSessionsTable.userId eq owner) }.forUpdate().singleOrNull()
            if(row!=null && row[RefreshSessionsTable.revokedAt]==null) RefreshSessionsTable.update({ RefreshSessionsTable.id eq session }) {
                it[revokedAt]=Instant.now()
            }
        }
    }
    val passwordResetEnabled: Boolean get()=resetMailer.enabled
    suspend fun requestPasswordReset(email: String) {
        check(resetMailer.enabled) { "Password recovery is not configured yet" }
        require(email.length<=255 && email.contains("@")) { "Enter a valid email" }
        val normalized=email.trim().lowercase()
        val code=java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(32).also {java.security.SecureRandom().nextBytes(it)})
        val send=query {
            val row=UsersTable.select {UsersTable.email eq normalized}.forUpdate().singleOrNull() ?: return@query false
            if(row[UsersTable.isSuspended])return@query false
            val now=Instant.now()
            val latest=PasswordResets.select {PasswordResets.user eq row[UsersTable.id]}.orderBy(PasswordResets.created,SortOrder.DESC).limit(1).singleOrNull()
            if(latest!=null && latest[PasswordResets.created]>now.minusSeconds(300))return@query false
            PasswordResets.insert {it[hash]=hash(code);it[user]=row[UsersTable.id];it[created]=now;it[expires]=now.plusSeconds(900)}
            true
        }
        if(send) kotlinx.coroutines.withContext(Dispatchers.IO) {resetMailer.send(normalized,code)}
        // Same response for unknown, suspended, throttled and accepted recipients.
    }
    suspend fun confirmPasswordReset(code: String,password: String) {
        require(code.length==43 && Regex("[A-Za-z0-9_-]{43}").matches(code)) { "Reset code is invalid or expired" }
        require(password.length>=8 && password.toByteArray(Charsets.UTF_8).size<=72) { "Password must be 8 characters or more and at most 72 UTF-8 bytes" }
        val hashed=PasswordHasher.hash(password)
        query {
            // User lock first, matching request/invite flows; then consume the token atomically.
            val candidate=PasswordResets.select {PasswordResets.hash eq hash(code)}.singleOrNull()
                ?: throw IllegalArgumentException("Reset code is invalid or expired")
            val owner=candidate[PasswordResets.user]
            val account=UsersTable.select {UsersTable.id eq owner}.forUpdate().single()
            val token=PasswordResets.select {PasswordResets.hash eq hash(code)}.forUpdate().single()
            require(!account[UsersTable.isSuspended] && token[PasswordResets.consumed]==null && token[PasswordResets.expires]>Instant.now()) { "Reset code is invalid or expired" }
            val now=Instant.now()
            UsersTable.update({UsersTable.id eq owner}) {it[passwordHash]=hashed;it[updatedAt]=now}
            PasswordResets.update({PasswordResets.user eq owner}) {it[consumed]=now}
            RefreshSessionsTable.update({RefreshSessionsTable.userId eq owner}) {it[revokedAt]=now}
        }
    }

    suspend fun redeemCreatorInvite(userId: String,code: String): UserDto = query {
        val owner=UUID.fromString(userId)
        val user=UsersTable.select {UsersTable.id eq owner}.forUpdate().single()
        check(!user[UsersTable.isSuspended]) { "Account is suspended" }
        check(user[UsersTable.role] !in setOf("CREATOR","ADMIN")) { "Creator access is already active" }
        CreatorInvites.consume(code.trim(),owner)
        UsersTable.update({UsersTable.id eq owner}) {it[role]="CREATOR";it[updatedAt]=Instant.now()}
        rowToUserDto(UsersTable.select {UsersTable.id eq owner}.single())
    }

    suspend fun getUserProfile(userIdStr: String): UserDto {
        val userUuid = try {
            UUID.fromString(userIdStr)
        } catch (e: Exception) {
            throw IllegalArgumentException("Invalid user ID format")
        }

        val row = query {
            UsersTable.select { UsersTable.id eq userUuid }.firstOrNull()
        } ?: throw IllegalArgumentException("User account not found")

        return rowToUserDto(row)
    }

    private fun rowToUserDto(row: ResultRow): UserDto = UserDto(
        id = row[UsersTable.id].toString(),
        username = row[UsersTable.username],
        email = row[UsersTable.email],
        displayName = row[UsersTable.displayName],
        avatarUrl = row[UsersTable.avatarUrl],
        bio = row[UsersTable.bio],
        isSuspended = row[UsersTable.isSuspended],
        role = row[UsersTable.role],
        createdAt = row[UsersTable.createdAt].toString(),
        publicId = (row[UsersTable.publicNumber] + 10_000_000L).toString()
    )
}

@Serializable
data class RegisterRequest(
    val username: String = "",
    val email: String,
    val password: String,
    val displayName: String = "",
    val creatorInviteCode: String = ""
) { override fun toString()="RegisterRequest(<redacted>)" }

@Serializable
data class LoginRequest(
    val email: String,
    val password: String
)

@Serializable
data class RefreshTokenRequest(
    val refreshToken: String
) { override fun toString()="RefreshTokenRequest(<redacted>)" }

@Serializable
data class TokenResponse(
    val accessToken: String,
    val refreshToken: String,
    val user: UserDto
)



