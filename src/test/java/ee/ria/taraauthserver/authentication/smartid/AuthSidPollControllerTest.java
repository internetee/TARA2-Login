package ee.ria.taraauthserver.authentication.smartid;

import ee.ria.taraauthserver.BaseTest;
import ee.ria.taraauthserver.error.ErrorCode;
import ee.ria.taraauthserver.session.MockSessionFilter;
import ee.ria.taraauthserver.session.TaraSession;
import io.restassured.RestAssured;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.session.Session;
import org.springframework.session.SessionRepository;

import static ee.ria.taraauthserver.config.properties.AuthenticationType.SMART_ID;
import static ee.ria.taraauthserver.session.TaraAuthenticationState.*;
import static ee.ria.taraauthserver.session.TaraSession.TARA_SESSION;
import static io.restassured.RestAssured.given;
import static java.util.List.of;
import static org.hamcrest.Matchers.equalTo;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class AuthSidPollControllerTest extends BaseTest {

    @Autowired
    private SessionRepository<Session> sessionRepository;

    @BeforeEach
    void beforeEach() {
        RestAssured.responseSpecification = null;
    }

    @Test
    @Tag(value = "SID_AUTH_STATUS_CHECK_VALID_SESSION")
    void sidAuth_session_missing() {
        given()
                .when()
                .get("/auth/sid/poll")
                .then()
                .assertThat()
                .statusCode(400)
                .headers(EXPECTED_RESPONSE_HEADERS)
                .body("message", equalTo("Teie sessiooni ei leitud! Sessioon aegus või on küpsiste kasutamine Teie brauseris piiratud."))
                .body("error", equalTo("Bad Request"));

        assertErrorIsLogged("User exception: Invalid session");
    }

    @Test
    @Tag(value = "SID_AUTH_STATUS_CHECK_VALID_SESSION")
    void sidAuth_session_status_incorrect() {
        given()
                .filter(MockSessionFilter.withTaraSession()
                        .sessionRepository(sessionRepository)
                        .authenticationTypes(of(SMART_ID))
                        .authenticationState(INIT_AUTH_PROCESS).build())
                .when()
                .get("/auth/sid/poll")
                .then()
                .assertThat()
                .statusCode(400)
                .headers(EXPECTED_RESPONSE_HEADERS)
                .body("message", equalTo("Ebakorrektne päring. Vale sessiooni staatus."))
                .body("error", equalTo("Bad Request"));

        assertErrorIsLogged("User exception: Invalid authentication state: 'INIT_AUTH_PROCESS', expected one of: [INIT_SID, POLL_SID_STATUS, AUTHENTICATION_FAILED, NATURAL_PERSON_AUTHENTICATION_COMPLETED]");
    }

    @Test
    @Tag(value = "SID_AUTH_PENDING")
    void sidAuth_session_status_poll_sid_status() {
        given()
                .filter(MockSessionFilter.withTaraSession()
                        .sessionRepository(sessionRepository)
                        .authenticationTypes(of(SMART_ID))
                        .authenticationState(POLL_SID_STATUS).build())
                .when()
                .get("/auth/sid/poll")
                .then()
                .assertThat()
                .statusCode(200)
                .headers(EXPECTED_RESPONSE_HEADERS)
                .body("status", equalTo("PENDING"));
    }

    @Test
    @Tag(value = "SID_AUTH_STATUS_CHECK_ENDPOINT")
    @Tag(value = "SID_AUTH_SUCCESS")
    void sidAuth_session_status_authentication_success() {
        MockSessionFilter sessionFilter = MockSessionFilter.withTaraSession()
                .sessionRepository(sessionRepository)
                .authenticationTypes(of(SMART_ID))
                .authenticationState(NATURAL_PERSON_AUTHENTICATION_COMPLETED).build();
        given()
                .filter(sessionFilter)
                .when()
                .get("/auth/sid/poll")
                .then()
                .assertThat()
                .statusCode(200)
                .headers(EXPECTED_RESPONSE_HEADERS)
                .body("status", equalTo("COMPLETED"))
                .header(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE + ";charset=UTF-8");

        TaraSession taraSession = sessionRepository.findById(sessionFilter.getSession().getId()).getAttribute(TARA_SESSION);
        assertEquals(NATURAL_PERSON_AUTHENTICATION_COMPLETED, taraSession.getState());
    }

    @Test
    @Tag(value = "SID_AUTH_FAILED")
    void sidAuth_session_status_authentication_failed() {
        TaraSession.AuthenticationResult authenticationResult = new TaraSession.AuthenticationResult();
        authenticationResult.setErrorCode(ErrorCode.SID_USER_REFUSED_CERT_CHOICE);

        MockSessionFilter sessionFilter = MockSessionFilter.withTaraSession()
                .sessionRepository(sessionRepository)
                .authenticationTypes(of(SMART_ID))
                .authenticationState(AUTHENTICATION_FAILED)
                .authenticationResult(authenticationResult).build();

        given()
                .filter(sessionFilter)
                .when()
                .get("/auth/sid/poll")
                .then()
                .assertThat()
                .statusCode(400)
                .body("error", equalTo("Bad Request"))
                .body("message", equalTo("Kasutajal on mitu Smart-ID kontot ja ühe kontoga tühistati autentimisprotsess."))
                .header(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE + ";charset=UTF-8");

        String sessionId = sessionFilter.getSession().getId();
        assertNull(sessionRepository.findById(sessionFilter.getSession().getId()));
        assertWarningIsLogged("Session has been invalidated: " + sessionId);
        assertInfoIsLogged("Session is removed from cache: " + sessionId);
    }

    @Test
    @Tag(value = "SID_AUTH_FAILED")
    void sidAuth_session_status_authentication_general_error() {
        TaraSession.AuthenticationResult authenticationResult = new TaraSession.AuthenticationResult();
        authenticationResult.setErrorCode(ErrorCode.ERROR_GENERAL);

        MockSessionFilter sessionFilter = MockSessionFilter.withTaraSession()
                .sessionRepository(sessionRepository)
                .authenticationTypes(of(SMART_ID))
                .authenticationState(AUTHENTICATION_FAILED)
                .authenticationResult(authenticationResult).build();

        given()
                .filter(sessionFilter)
                .when()
                .get("/auth/sid/poll")
                .then()
                .assertThat()
                .statusCode(500)
                .body("error", equalTo("Internal Server Error"))
                .body("message", equalTo("Autentimine ebaõnnestus teenuse tehnilise vea tõttu. Palun proovige mõne aja pärast uuesti."))
                .header(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE + ";charset=UTF-8");


        String sessionId = sessionFilter.getSession().getId();
        assertNull(sessionRepository.findById(sessionFilter.getSession().getId()));
        assertInfoIsLogged("Tara session state change: NOT_SET -> AUTHENTICATION_FAILED");
        assertWarningIsLogged("Session has been invalidated: " + sessionId);
        assertInfoIsLogged("Session is removed from cache: " + sessionId);

    }

    @Test
    @Tag(value = "SID_AUTH_FAILED")
    void sidAuth_session_status_authentication_sid_internal_error() {
        TaraSession.AuthenticationResult authenticationResult = new TaraSession.AuthenticationResult();
        authenticationResult.setErrorCode(ErrorCode.SID_INTERNAL_ERROR);

        MockSessionFilter sessionFilter = MockSessionFilter.withTaraSession()
                .sessionRepository(sessionRepository)
                .authenticationTypes(of(SMART_ID))
                .authenticationState(AUTHENTICATION_FAILED)
                .authenticationResult(authenticationResult).build();

        given()
                .filter(sessionFilter)
                .when()
                .get("/auth/sid/poll")
                .then()
                .assertThat()
                .statusCode(502)
                .body("error", equalTo("Bad Gateway"))
                .body("message", equalTo("Smart-ID teenuses esinevad tehnilised tõrked. Palun proovige mõne aja pärast uuesti."))
                .header(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE + ";charset=UTF-8");

        String sessionId = sessionFilter.getSession().getId();
        assertNull(sessionRepository.findById(sessionFilter.getSession().getId()));
        assertInfoIsLogged("Tara session state change: NOT_SET -> AUTHENTICATION_FAILED");
        assertErrorIsLogged("Service not available: Sid poll failed");
        assertWarningIsLogged("Session has been invalidated: " + sessionId);
        assertInfoIsLogged("Session is removed from cache: " + sessionId);
    }

}