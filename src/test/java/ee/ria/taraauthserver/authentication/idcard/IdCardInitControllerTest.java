package ee.ria.taraauthserver.authentication.idcard;

import ee.ria.taraauthserver.BaseTest;
import ee.ria.taraauthserver.config.properties.AuthenticationType;
import ee.ria.taraauthserver.session.MockSessionFilter;
import ee.ria.taraauthserver.session.MockSessionFilter.CsrfMode;
import ee.ria.taraauthserver.session.TaraAuthenticationState;
import ee.ria.taraauthserver.session.TaraSession;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.session.Session;
import org.springframework.session.SessionRepository;

import static ch.qos.logback.classic.Level.ERROR;
import static ee.ria.taraauthserver.session.TaraSession.TARA_SESSION;
import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.matchesPattern;
import static org.hamcrest.Matchers.nullValue;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static java.lang.String.format;

@Slf4j
class IdCardInitControllerTest extends BaseTest {

    @Autowired
    private SessionRepository<Session> sessionRepository;

    @Test
    @Tag(value = "ESTEID_INIT_ENDPOINT")
    @Tag(value = "CSRF_PROTECTION")
    void handleRequest_NoCsrf_Fails() {
        given()
                .filter(MockSessionFilter.withoutCsrf().sessionRepository(sessionRepository).build())
                .when()
                .post("/auth/id/init")
                .then()
                .assertThat()
                .statusCode(403)
                .body("error", equalTo("Forbidden"))
                .body("message", equalTo("Keelatud päring. Päring esitati topelt, seanss aegus või on küpsiste kasutamine Teie brauseris piiratud."))
                .body("reportable", equalTo(false));

        assertErrorIsLogged("Access denied: Invalid CSRF token.");
        assertStatisticsIsNotLogged();
    }

    @Test
    @Tag(value = "ESTEID_INIT_ENDPOINT")
    @Tag(value = "CSRF_PROTECTION")
    void handleRequest_MissingSession_Fails() {
        given()
                .when()
                .post("/auth/id/init")
                .then()
                .assertThat()
                .statusCode(403)
                .header("Set-Cookie", nullValue())
                .body("message", equalTo("Keelatud päring. Päring esitati topelt, seanss aegus või on küpsiste kasutamine Teie brauseris piiratud."))
                .body("error", equalTo("Forbidden"))
                .body("incident_nr", matchesPattern("[a-f0-9]{32}"))
                .body("reportable", equalTo(false));

        assertErrorIsLogged("Access denied: Invalid CSRF token.");
        assertStatisticsIsNotLogged();
    }

    @Test
    @Tag(value = "ESTEID_INIT_ENDPOINT")
    void handleRequest_CorrectAuthenticationState_ReturnsNonce() {
        MockSessionFilter mockSessionFilter = MockSessionFilter
                .withTaraSession()
                .csrfMode(CsrfMode.HEADER)
                .sessionRepository(sessionRepository)
                .build();
        String sessionId = mockSessionFilter.getSession().getId();

        given()
                .filter(mockSessionFilter)
                .when()
                .post("/auth/id/init")
                .then()
                .assertThat()
                .statusCode(200)
                .body("nonce", equalTo(getNonceFromSession(sessionId)));

        TaraSession taraSession = sessionRepository.findById(sessionId).getAttribute(TARA_SESSION);
        assertEquals(TaraAuthenticationState.INIT_AUTH_PROCESS, taraSession.getState());
        TaraSession.AuthenticationResult result = taraSession.getAuthenticationResult();
        assertEquals(AuthenticationType.ID_CARD, result.getAmr());
        assertInfoIsLogged("Generated nonce: " + getNonceFromSession(sessionId));
        assertStatisticsIsNotLogged();
    }

    @Test
    @Tag(value = "ESTEID_INIT_ENDPOINT")
    void handleRequest_IncorrectAuthenticationState_ReturnsError() {
        MockSessionFilter mockSessionFilter = MockSessionFilter
                .withTaraSession()
                .authenticationState(TaraAuthenticationState.INIT_MID)
                .csrfMode(CsrfMode.HEADER)
                .sessionRepository(sessionRepository)
                .build();

        given()
                .filter(mockSessionFilter)
                .when()
                .post("/auth/id/init")
                .then()
                .assertThat()
                .statusCode(400)
                .body("message", equalTo("Ebakorrektne päring. Vale seansi staatus."))
                .body("error", equalTo("Bad Request"))
                .body("incident_nr", matchesPattern("[a-f0-9]{32}"))
                .body("reportable", equalTo(false));

        String sessionId = mockSessionFilter.getSession().getId();
        assertErrorIsLogged("User exception: Invalid authentication state: 'INIT_MID', expected one of: [INIT_AUTH_PROCESS]");
        assertStatisticsIsLoggedOnce(ERROR, "Authentication result: AUTHENTICATION_FAILED", format("StatisticsLogger.SessionStatistics(service=null, clientId=openIdDemo, clientNotifyUrl=null, eidasRequesterId=null, sector=public, registryCode=10001234, legalPerson=false, country=EE, idCode=null, subject=null, firstName=null, lastName=null, ocspUrl=null, authenticationType=null, authenticationState=AUTHENTICATION_FAILED, authenticationSessionId=%s, errorCode=SESSION_STATE_INVALID)", sessionId));
    }

    private String getNonceFromSession(String sessionId) {
        TaraSession taraSession = sessionRepository.findById(sessionId).getAttribute(TARA_SESSION);
        return taraSession.getWebEidChallengeNonce().getBase64EncodedNonce();
    }
}
