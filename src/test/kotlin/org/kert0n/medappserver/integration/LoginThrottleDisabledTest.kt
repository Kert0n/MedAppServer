package org.kert0n.medappserver.integration

import com.sksamuel.aedile.core.Cache
import kotlin.test.assertTrue
import kotlin.uuid.Uuid
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.kert0n.medappserver.domain.User
import org.kert0n.medappserver.services.aggregate.UserService
import org.kert0n.medappserver.services.security.AuthenticatedUserService
import org.kert0n.medappserver.testutil.ApiRoutes
import org.mockito.kotlin.whenever
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.httpBasic
import org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.context.bean.override.mockito.MockitoBean
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.test.web.servlet.setup.DefaultMockMvcBuilder
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import org.springframework.web.context.WebApplicationContext

@SpringBootTest(properties = [
    "authentication.throttle.enabled=false",
    "authentication.throttle.maxAttempts=1"
])
@ActiveProfiles("test")
class LoginThrottleDisabledTest {

    @Autowired
    private lateinit var context: WebApplicationContext

    @Autowired
    @Qualifier("loginAttemptsCache")
    private lateinit var loginAttemptsCache: Cache<String, Int>

    @MockitoBean
    private lateinit var userService: UserService

    @MockitoBean
    private lateinit var authenticatedUserService: AuthenticatedUserService

    private lateinit var mockMvc: MockMvc

    @BeforeEach
    fun setup() {
        mockMvc = MockMvcBuilders.webAppContextSetup(context)
            .apply<DefaultMockMvcBuilder>(SecurityMockMvcConfigurers.springSecurity())
            .build()
        loginAttemptsCache.invalidateAll()
    }

    @Test
    fun `disabled throttle permits repeated token requests without counting them`() {
        val userId = Uuid.random()
        whenever(authenticatedUserService.loadUserByUsername(userId.toString()))
            .thenReturn(User(id = userId, hashedKey = "{noop}password"))

        repeat(3) {
            mockMvc.perform(post(ApiRoutes.TOKEN).with(httpBasic(userId.toString(), "password")))
                .andExpect(status().isOk)
        }

        assertTrue(loginAttemptsCache.asDeferredMap().isEmpty())
    }
}
