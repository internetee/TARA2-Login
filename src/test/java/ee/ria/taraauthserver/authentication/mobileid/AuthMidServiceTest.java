package ee.ria.taraauthserver.authentication.mobileid;

import ee.ria.taraauthserver.BaseTest;
import ee.ria.taraauthserver.config.properties.LevelOfAssurance;
import ee.ria.taraauthserver.error.exceptions.ServiceNotAvailableException;
import ee.ria.taraauthserver.session.TaraSession;
import ee.sk.mid.*;
import ee.sk.mid.exception.MidInternalErrorException;
import ee.sk.mid.rest.MidConnector;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentMatchers;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.mock.mockito.SpyBean;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.session.SessionRepository;

import javax.ws.rs.ProcessingException;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static ee.ria.taraauthserver.config.properties.AuthenticationType.MOBILE_ID;
import static ee.ria.taraauthserver.error.ErrorCode.*;
import static ee.ria.taraauthserver.session.TaraAuthenticationState.*;
import static ee.ria.taraauthserver.session.TaraSession.TARA_SESSION;
import static java.lang.String.format;
import static java.util.Locale.forLanguageTag;
import static org.awaitility.Awaitility.await;
import static org.awaitility.Durations.FIVE_SECONDS;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasProperty;
import static org.junit.jupiter.api.Assertions.*;

public class AuthMidServiceTest extends BaseTest {
    private final MidAuthenticationHashToSign MOCK_HASH_TO_SIGN = new MidAuthenticationHashToSign.MobileIdAuthenticationHashToSignBuilder()
            .withHashType(MidHashType.SHA512)
            .withHashInBase64("bT+0Fuuf0QChq/sYb+Nz8vhLE8n3gLeL/wOXKxxE4ao=").build();
    private final MidConnector midConnectorMock = Mockito.mock(MidConnector.class);

    @SpyBean
    private AuthMidService authMidService;

    @Autowired
    private SessionRepository sessionRepository;

    @SpyBean
    private MidAuthenticationResponseValidator midAuthenticationResponseValidator;

    @SpyBean
    private MidClient midClient;

    @BeforeAll
    static void beforeAll() {
        LocaleContextHolder.setLocale(forLanguageTag("et"));
    }

    @BeforeEach
    void beforeEach() {
        Mockito.doReturn(MOCK_HASH_TO_SIGN).when(authMidService).getAuthenticationHash();
    }

    @AfterEach
    void afterEach() {
        Mockito.reset(authMidService, midClient);
    }

    @Test
    void correctAuthenticationSessionStateWhen_successfulAuthentication() {
        String sessionId = startMidAuthSessionWithPollResponse("mock_responses/mid/mid_poll_response.json", 200);

        TaraSession taraSession = await().atMost(FIVE_SECONDS)
                .until(() -> sessionRepository.findById(sessionId).getAttribute(TARA_SESSION), hasProperty("state", equalTo(NATURAL_PERSON_AUTHENTICATION_COMPLETED)));

        TaraSession.MidAuthenticationResult result = (TaraSession.MidAuthenticationResult) taraSession.getAuthenticationResult();
        assertNull(result.getErrorCode());
        assertEquals("60001019906", result.getIdCode());
        assertEquals("EE", result.getCountry());
        assertEquals("MARY ÄNN", result.getFirstName());
        assertEquals("O’CONNEŽ-ŠUSLIK TESTNUMBER", result.getLastName());
        assertEquals("+37200000766", result.getPhoneNumber());
        assertEquals("EE60001019906", result.getSubject());
        assertEquals("2000-01-01", result.getDateOfBirth().toString());
        assertEquals(MOBILE_ID, result.getAmr());
        assertEquals(LevelOfAssurance.HIGH, result.getAcr());

        assertInfoIsLogged("Mid init request: ee.sk.mid.rest.dao.request.MidAuthenticationRequest");
        assertInfoIsLogged("Mid init response: MidAbstractResponse{sessionID='de305d54-75b4-431b-adb2-eb6b9e546015'}");
        assertInfoIsLogged("Mobile ID authentication process with MID session id de305d54-75b4-431b-adb2-eb6b9e546015 has been initiated");
        assertMidApiRequests();
    }

    @ParameterizedTest
    @ValueSource(strings = {"INVALID_RESULT", "SIGNATURE_VERIFICATION_FAILURE", "CERTIFICATE_EXPIRED", "CERTIFICATE_NOT_TRUSTED"})
    void authenticationFailsWhen_MidAuthenticationResultValidationFails(String validationError) {
        MidAuthenticationResult authenticationResult = new MidAuthenticationResult();
        authenticationResult.setValid(false);
        MidAuthenticationError error = MidAuthenticationError.valueOf(validationError);
        authenticationResult.addError(error);
        Mockito.doReturn(authenticationResult).when(midAuthenticationResponseValidator).validate(ArgumentMatchers.any());

        String sessionId = startMidAuthSessionWithPollResponse("mock_responses/mid/mid_poll_response.json", 200);
        TaraSession taraSession = await().atMost(FIVE_SECONDS)
                .until(() -> sessionRepository.findById(sessionId).getAttribute(TARA_SESSION), hasProperty("state", equalTo(AUTHENTICATION_FAILED)));
        assertEquals(MID_VALIDATION_ERROR, taraSession.getAuthenticationResult().getErrorCode());
        assertErrorIsLogged(format("Authentication result validation failed: [%s]", error.getMessage()));
        assertMidApiRequests();
    }

    @Test
    void unchangedAuthenticationSessionStateWhen_MidApi_MidInternalErrorException() {
        String sessionId = createNewAuthenticationSession(MOBILE_ID);
        Mockito.doReturn(midConnectorMock).when(midClient).getMobileIdConnector();
        Mockito.doThrow(new MidInternalErrorException("MidInternalErrorException")).when(midConnectorMock).authenticate(Mockito.any());
        ServiceNotAvailableException expectedEx = assertThrows(ServiceNotAvailableException.class, () -> {
            authMidService.startMidAuthSession(sessionId, "60001019906", "+37200000766");
        });
        TaraSession taraSession = sessionRepository.findById(sessionId).getAttribute(TARA_SESSION);
        assertEquals(INIT_AUTH_PROCESS, taraSession.getState());
        assertEquals("MID service is currently unavailable: MidInternalErrorException", expectedEx.getMessage());
    }

    @Test
    void unchangedAuthenticationSessionStateWhen_MidApi_ProcessingException() {
        String sessionId = createNewAuthenticationSession(MOBILE_ID);
        Mockito.doReturn(midConnectorMock).when(midClient).getMobileIdConnector();
        Mockito.doThrow(new ProcessingException("ProcessingException")).when(midConnectorMock).authenticate(Mockito.any());
        ServiceNotAvailableException expectedEx = assertThrows(ServiceNotAvailableException.class, () -> {
            authMidService.startMidAuthSession(sessionId, "60001019906", "+37200000766");
        });
        TaraSession taraSession = sessionRepository.findById(sessionId).getAttribute(TARA_SESSION);
        assertEquals(INIT_AUTH_PROCESS, taraSession.getState());
        assertEquals("MID service is currently unavailable: ProcessingException", expectedEx.getMessage());
    }

    @Test
    void unchangedAuthenticationSessionStateWhen_MidApi_RuntimeException() {
        String sessionId = createNewAuthenticationSession(MOBILE_ID);
        Mockito.doReturn(midConnectorMock).when(midClient).getMobileIdConnector();
        Mockito.doThrow(new RuntimeException("RuntimeException")).when(midConnectorMock).authenticate(Mockito.any());
        IllegalStateException expectedEx = assertThrows(IllegalStateException.class, () -> {
            authMidService.startMidAuthSession(sessionId, "60001019906", "+37200000766");
        });
        TaraSession taraSession = sessionRepository.findById(sessionId).getAttribute(TARA_SESSION);
        assertEquals(INIT_AUTH_PROCESS, taraSession.getState());
        assertEquals("Internal error during MID authentication init: RuntimeException", expectedEx.getMessage());
    }

    @Test
    void authenticationFailsWhen_MidApi_response_delivery_error() {
        String sessionId = startMidAuthSessionWithPollResponse("mock_responses/mid/mid_poll_response_delivery_error.json", 200);
        TaraSession taraSession = await().atMost(FIVE_SECONDS)
                .until(() -> sessionRepository.findById(sessionId).getAttribute(TARA_SESSION), hasProperty("state", equalTo(AUTHENTICATION_FAILED)));
        assertWarningIsLogged("Mid polling failed: SMS sending error");
        assertEquals(MID_DELIVERY_ERROR, taraSession.getAuthenticationResult().getErrorCode());
        assertMidApiRequests();
    }

    @Test
    void authenticationFailsWhen_MidApi_response_sim_error() {
        String sessionId = startMidAuthSessionWithPollResponse("mock_responses/mid/mid_poll_response_sim_error.json", 200);
        TaraSession taraSession = await().atMost(FIVE_SECONDS)
                .until(() -> sessionRepository.findById(sessionId).getAttribute(TARA_SESSION), hasProperty("state", equalTo(AUTHENTICATION_FAILED)));
        assertWarningIsLogged("Mid polling failed: SMS sending error");
        assertEquals(MID_DELIVERY_ERROR, taraSession.getAuthenticationResult().getErrorCode());
        assertMidApiRequests();
    }

    @Test
    void authenticationFailsWhen_MidApi_response_400() {
        String sessionId = startMidAuthSessionWithPollResponse("mock_responses/mid/mid_poll_empty_response.json", 400);
        TaraSession taraSession = await().atMost(FIVE_SECONDS)
                .until(() -> sessionRepository.findById(sessionId).getAttribute(TARA_SESSION), hasProperty("state", equalTo(AUTHENTICATION_FAILED)));
        assertWarningIsLogged("Mid polling failed: HTTP 400 Bad Request");
        assertEquals(ERROR_GENERAL, taraSession.getAuthenticationResult().getErrorCode());
        assertMidApiRequests();
    }

    @Test
    void authenticationFailsWhen_MidApi_response_401() {
        String sessionId = startMidAuthSessionWithPollResponse("mock_responses/mid/mid_poll_empty_response.json", 401);
        TaraSession taraSession = await().atMost(FIVE_SECONDS)
                .until(() -> sessionRepository.findById(sessionId).getAttribute(TARA_SESSION), hasProperty("state", equalTo(AUTHENTICATION_FAILED)));
        assertWarningIsLogged("Mid polling failed: HTTP 401 Unauthorized");
        assertEquals(ERROR_GENERAL, taraSession.getAuthenticationResult().getErrorCode());
        assertMidApiRequests();
    }

    @Test
    void authenticationFailsWhen_MidApi_response_404() {
        String sessionId = startMidAuthSessionWithPollResponse("mock_responses/mid/mid_poll_empty_response.json", 404);
        TaraSession taraSession = await().atMost(FIVE_SECONDS)
                .until(() -> sessionRepository.findById(sessionId).getAttribute(TARA_SESSION), hasProperty("state", equalTo(AUTHENTICATION_FAILED)));
        assertWarningIsLogged("Mid polling failed: Mobile-ID session was not found. Sessions time out in ~5 minutes.");
        assertEquals(MID_INTEGRATION_ERROR, taraSession.getAuthenticationResult().getErrorCode());
        assertMidApiRequests();
    }

    @Test
    void authenticationFailsWhen_MidApi_response_405() {
        String sessionId = startMidAuthSessionWithPollResponse("mock_responses/mid/mid_poll_empty_response.json", 405);
        TaraSession taraSession = await().atMost(FIVE_SECONDS)
                .until(() -> sessionRepository.findById(sessionId).getAttribute(TARA_SESSION), hasProperty("state", equalTo(AUTHENTICATION_FAILED)));
        assertWarningIsLogged("Mid polling failed: HTTP 405 Method Not Allowed");
        assertEquals(ERROR_GENERAL, taraSession.getAuthenticationResult().getErrorCode());
        assertMidApiRequests();
    }

    @Test
    void authenticationFailsWhen_MidApi_response_500() {
        String sessionId = startMidAuthSessionWithPollResponse("mock_responses/mid/mid_poll_empty_response.json", 500);
        TaraSession taraSession = await().atMost(FIVE_SECONDS)
                .until(() -> sessionRepository.findById(sessionId).getAttribute(TARA_SESSION), hasProperty("state", equalTo(AUTHENTICATION_FAILED)));
        assertWarningIsLogged("Mid polling failed: HTTP 500 Server Error");
        assertEquals(MID_INTERNAL_ERROR, taraSession.getAuthenticationResult().getErrorCode());
        assertMidApiRequests();
    }

    @Test
    void authenticationFailsWhen_MidApi_response_user_cancelled() {
        String sessionId = startMidAuthSessionWithPollResponse("mock_responses/mid/mid_poll_response_user_cancelled.json", 200);
        TaraSession taraSession = await().atMost(FIVE_SECONDS)
                .until(() -> sessionRepository.findById(sessionId).getAttribute(TARA_SESSION), hasProperty("state", equalTo(AUTHENTICATION_FAILED)));
        assertWarningIsLogged("Mid polling failed: User cancelled the operation.");
        assertEquals(MID_USER_CANCEL, taraSession.getAuthenticationResult().getErrorCode());
        assertMidApiRequests();
    }

    @Test
    void authenticationFailsWhen_MidApi_response_not_mid_client() {
        String sessionId = startMidAuthSessionWithPollResponse("mock_responses/mid/mid_poll_response_not_mid_client.json", 200);
        TaraSession taraSession = await().atMost(FIVE_SECONDS)
                .until(() -> sessionRepository.findById(sessionId).getAttribute(TARA_SESSION), hasProperty("state", equalTo(AUTHENTICATION_FAILED)));
        assertWarningIsLogged("Mid polling failed: User has no active certificates, and thus is not Mobile-ID client");
        assertEquals(NOT_MID_CLIENT, taraSession.getAuthenticationResult().getErrorCode());
        assertMidApiRequests();
    }

    @Test
    void authenticationFailsWhen_MidApi_response_timeout() {
        String sessionId = startMidAuthSessionWithPollResponse("mock_responses/mid/mid_poll_response_timeout.json", 200);
        TaraSession taraSession = await().atMost(FIVE_SECONDS)
                .until(() -> sessionRepository.findById(sessionId).getAttribute(TARA_SESSION), hasProperty("state", equalTo(AUTHENTICATION_FAILED)));
        assertWarningIsLogged("Mid polling failed: User didn't enter PIN code or communication error.");
        assertEquals(MID_TRANSACTION_EXPIRED, taraSession.getAuthenticationResult().getErrorCode());
        assertMidApiRequests();
    }

    @Test
    void authenticationFailsWhen_MidApi_response_signature_hash_mismatch() {
        String sessionId = startMidAuthSessionWithPollResponse("mock_responses/mid/mid_poll_response_signature_hash_mismatch.json", 200);
        TaraSession taraSession = await().atMost(FIVE_SECONDS)
                .until(() -> sessionRepository.findById(sessionId).getAttribute(TARA_SESSION), hasProperty("state", equalTo(AUTHENTICATION_FAILED)));
        assertWarningIsLogged("Mid polling failed: Mobile-ID configuration on user's SIM card differs from what is configured on service provider side. User needs to contact his/her mobile operator.");
        assertEquals(MID_HASH_MISMATCH, taraSession.getAuthenticationResult().getErrorCode());
        assertMidApiRequests();
    }

    @Test
    void authenticationFailsWhen_MidApi_response_phone_absent() {
        String sessionId = startMidAuthSessionWithPollResponse("mock_responses/mid/mid_poll_response_phone_absent.json", 200);
        TaraSession taraSession = await().atMost(FIVE_SECONDS)
                .until(() -> sessionRepository.findById(sessionId).getAttribute(TARA_SESSION), hasProperty("state", equalTo(AUTHENTICATION_FAILED)));
        assertWarningIsLogged("Mid polling failed: Unable to reach phone or SIM card");
        assertEquals(MID_PHONE_ABSENT, taraSession.getAuthenticationResult().getErrorCode());
        assertMidApiRequests();
    }

    private String startMidAuthSessionWithPollResponse(String pollResponse, int pollHttpStatus) {
        createMidApiAuthenticationStub("mock_responses/mid/mid_authenticate_response.json", 200);
        createMidApiPollStub(pollResponse, pollHttpStatus);
        String sessionId = createNewAuthenticationSession(MOBILE_ID);
        MidAuthenticationHashToSign midAuthenticationHashToSign = authMidService.startMidAuthSession(sessionId, "60001019906", "+37200000766");
        assertNotNull(midAuthenticationHashToSign);
        return sessionId;
    }

    private void assertMidApiRequests() {
        wireMockServer.verify(1, postRequestedFor(urlEqualTo("/mid-api/authentication")));
        wireMockServer.verify(1, getRequestedFor(urlPathMatching("/mid-api/authentication/session/.*")));
    }
}