package org.kert0n.medappserver.services.security

import com.sksamuel.aedile.core.Cache
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.security.SecureRandom
import java.time.Instant
import java.time.temporal.ChronoUnit
import kotlin.io.encoding.Base64
import org.kert0n.medappserver.domain.User
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.beans.factory.annotation.Value
import org.springframework.security.crypto.password.PasswordEncoder
import org.springframework.security.oauth2.jwt.JwtClaimsSet
import org.springframework.security.oauth2.jwt.JwtDecoder
import org.springframework.security.oauth2.jwt.JwtEncoder
import org.springframework.security.oauth2.jwt.JwtEncoderParameters
import org.springframework.stereotype.Service

@Service
class SecurityService(
    private val passwordEncoder: PasswordEncoder,
    private val encoder: JwtEncoder,
    private val decoder: JwtDecoder,
    @Value($$"${authentication.termInMinutes}") private val authenticationTerm: Long,
    @Value($$"${registration.timeout.BanNumber}") private val registrationNumber: Long,
    @Value($$"${authentication.throttle.enabled:true}") private val loginThrottleEnabled: Boolean,
    @Value($$"${authentication.throttle.maxAttempts:20}") private val maxLoginAttempts: Int,
    // Оба кеша — `Cache<String, Int>`: квалификаторы поставлены, чтобы разрешение не зависело
    // от имён параметров.
    @Qualifier("successfulRegistrationsCache") private val successfulRegistrationsCache: Cache<String, Int>,
    @Qualifier("loginAttemptsCache") private val loginAttemptsCache: Cache<String, Int>
) {

    private val secureRandom = SecureRandom()

    /**
     * Ключ печатается в URL приглашения и уезжает в заголовок Basic, поэтому URL-safe и без
     * набивки: `+`, `/` и `=` пришлось бы экранировать, и клиенты сделали бы это по-разному.
     */
    fun generateKey(size: Int) = URL_SAFE_KEYS.encode(ByteArray(size).also { secureRandom.nextBytes(it) })
    fun check(raw: String, hashedPassword: String): Boolean = passwordEncoder.matches(raw, hashedPassword)
    fun hashPassword(rawPassword: String): String = passwordEncoder.encode(rawPassword)!!
    fun hashToken(token: String): String =
        Base64.encode(MessageDigest.getInstance("SHA-256").digest(token.toByteArray()))

    /**
     * Сравнивает два секрета, не выдавая, насколько кандидат угадан.
     *
     * `==` останавливается на первом различии, и время ответа повторяет длину совпавшего
     * начала. Обе стороны хешируются потому, что [MessageDigest.isEqual] выходит раньше при
     * разной длине: хеш уравнивает длины и прячет заодно размер ожидаемого секрета.
     */
    fun secretsMatch(candidate: String, expected: String): Boolean = MessageDigest.isEqual(
        hashToken(candidate).toByteArray(StandardCharsets.UTF_8),
        hashToken(expected).toByteArray(StandardCharsets.UTF_8)
    )


    fun generateToken(user: User, termInMinutes: Long = authenticationTerm): String {
        val now = Instant.now()
        return encoder.encode(
            JwtEncoderParameters.from(
                JwtClaimsSet.builder().run {
                    issuedAt(now)
                    expiresAt(now.plus(termInMinutes, ChronoUnit.MINUTES))
                    subject(user.id.toString())
                    build()
                }
            )
        ).tokenValue
    }

    /**
     * Ключ кеша для адреса клиента: сам адрес ключом не становится.
     *
     * Обычный SHA-256 намеренно: записи живут минуты, кеш умирает вместе с процессом, а тот, кто
     * может прочитать эту память, видит и живые соединения.
     */
    private fun addressCacheKey(clientAddress: String): String = hashToken(clientAddress)

    /**
     * Считает удачные регистрации с адреса, чтобы автоматические не шли потоком.
     *
     * Две неточности намеренны — это заслон от простых ботов, а не граница безопасности:
     * проверка стоит перед увеличением, поэтому одновременные запросы проходят вместе, а `<=`
     * пропускает на одну больше [registrationNumber]. Точность потребовала бы атомарного
     * счётчика с возвратом при отказе — механизм ради ничего. Не «чинить» без причины.
     */
    fun validateRequest(ip: String): Boolean =
        (successfulRegistrationsCache.getOrNull(addressCacheKey(ip)) ?: 0) <= registrationNumber

    /**
     * Можно ли пропустить к аутентификации ещё один запрос токена с этого адреса.
     *
     * Сравнение строгое, в отличие от [validateRequest]: здесь охраняется настоящая цена —
     * проверка bcrypt на каждый запрос, — а не только боты.
     */
    fun isLoginAllowed(clientAddress: String): Boolean =
        !loginThrottleEnabled ||
            (loginAttemptsCache.getOrNull(addressCacheKey(clientAddress)) ?: 0) < maxLoginAttempts

    /**
     * Считает запрос токена — каждый, а не только неудачный.
     *
     * Законный клиент просит примерно раз за время жизни токена, далеко не упираясь в лимит, а
     * счёт всех попыток покрывает и того, у кого учётные данные верные.
     */
    fun recordLoginAttempt(clientAddress: String) {
        if (!loginThrottleEnabled) return
        val key = addressCacheKey(clientAddress)
        loginAttemptsCache[key] = (loginAttemptsCache.getOrNull(key) ?: 0) + 1
    }

    private companion object {
        val URL_SAFE_KEYS: Base64 = Base64.UrlSafe.withPadding(Base64.PaddingOption.ABSENT)
    }

    /** Засчитывает ещё одну удачную регистрацию с этого адреса. */
    fun registerIncrease(ip: String) {
        val key = addressCacheKey(ip)
        val current = successfulRegistrationsCache.getOrNull(key)
        if (current == null) {
            successfulRegistrationsCache.put(key, 1)
        } else {
            successfulRegistrationsCache[key] = current + 1
        }
    }
}
