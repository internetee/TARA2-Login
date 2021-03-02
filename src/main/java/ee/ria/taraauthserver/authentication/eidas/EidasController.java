package ee.ria.taraauthserver.authentication.eidas;

import ee.ria.taraauthserver.config.properties.EidasConfigurationProperties;
import ee.ria.taraauthserver.error.ErrorCode;
import ee.ria.taraauthserver.error.exceptions.BadRequestException;
import ee.ria.taraauthserver.error.exceptions.EidasInternalException;
import ee.ria.taraauthserver.session.SessionUtils;
import ee.ria.taraauthserver.session.TaraSession;
import lombok.extern.slf4j.Slf4j;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.util.UriComponentsBuilder;

import javax.cache.Cache;
import javax.servlet.http.HttpServletResponse;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static ee.ria.taraauthserver.error.ErrorCode.INVALID_REQUEST;
import static ee.ria.taraauthserver.session.TaraAuthenticationState.INIT_AUTH_PROCESS;
import static ee.ria.taraauthserver.session.TaraAuthenticationState.WAITING_EIDAS_RESPONSE;
import static ee.ria.taraauthserver.session.TaraSession.TARA_SESSION;

@Slf4j
@RestController
@ConditionalOnProperty(value = "tara.auth-methods.eidas.enabled", matchIfMissing = true)
public class EidasController {


    @Autowired
    private EidasConfigurationProperties eidasConfigurationProperties;

    @Autowired
    @Qualifier("eidasRestTemplate")
    RestTemplate restTemplate;

    @Autowired
    private Cache<String, String> eidasRelayStateCache;

    @PostMapping(value = "/auth/eidas/init", produces = MediaType.TEXT_HTML_VALUE)
    public String EidasInit(@RequestParam("country") String country, @SessionAttribute(value = TARA_SESSION, required = false) TaraSession taraSession, HttpServletResponse servletResponse) {

        validateSession(taraSession);
        String relayState = UUID.randomUUID().toString();
        eidasRelayStateCache.put(relayState, taraSession.getSessionId());

        if (!eidasConfigurationProperties.getAvailableCountries().contains(country))
            throw new BadRequestException(getAppropriateErrorCode(), "Requested country not supported.");

        try {
            ResponseEntity<String> response = restTemplate.exchange(createRequestUrl(country, taraSession, relayState), HttpMethod.GET, null, String.class);
            updateSession(country, taraSession, relayState);
            return getHtmlRedirectPageFromResponse(servletResponse, response);
        } catch (Exception e) {
            throw new EidasInternalException(ErrorCode.ERROR_GENERAL, e.getMessage());
        }
    }

    @Nullable
    private String getHtmlRedirectPageFromResponse(HttpServletResponse servletResponse, ResponseEntity<String> response) {
        servletResponse.setHeader("Content-Security-Policy", "connect-src 'self'; default-src 'none'; font-src 'self'; img-src 'self'; script-src '" + eidasConfigurationProperties.getScriptHash() + "' 'self'; style-src 'self'; base-uri 'none'; frame-ancestors 'none'; block-all-mixed-content");
        return response.getBody();
    }

    private void updateSession(String country, TaraSession taraSession, String relayState) {
        TaraSession.EidasAuthenticationResult authenticationResult = new TaraSession.EidasAuthenticationResult();
        authenticationResult.setRelayState(relayState);
        authenticationResult.setCountry(country);
        taraSession.setState(WAITING_EIDAS_RESPONSE);
        taraSession.setAuthenticationResult(authenticationResult);
    }

    private String createRequestUrl(String country, TaraSession taraSession, String relayState) {
        String url = eidasConfigurationProperties.getClientUrl() + "/login";
        UriComponentsBuilder builder = UriComponentsBuilder.fromHttpUrl(url)
                .queryParam("Country", country)
                .queryParam("RelayState", relayState);
        List<String> acr = getAcrFromSessionOidcContext(taraSession);
        if (acr != null)
            builder.queryParam("LoA", acr.get(0).toUpperCase());
        return builder.toUriString();
    }

    private ErrorCode getAppropriateErrorCode() {
        Object[] allowedCountries = eidasConfigurationProperties.getAvailableCountries().toArray(new Object[eidasConfigurationProperties.getAvailableCountries().size()]);
        ErrorCode errorCode = ErrorCode.EIDAS_COUNTRY_NOT_SUPPORTED;
        errorCode.setMessageParameters(allowedCountries);
        return errorCode;
    }

    private List<String> getAcrFromSessionOidcContext(TaraSession taraSession) {
        return Optional.of(taraSession)
                .map(TaraSession::getLoginRequestInfo)
                .map(TaraSession.LoginRequestInfo::getOidcContext)
                .map(TaraSession.OidcContext::getAcrValues)
                .orElse(null);
    }

    public void validateSession(TaraSession taraSession) {
        log.info("AuthSession: {}", taraSession);
        SessionUtils.assertSessionInState(taraSession, INIT_AUTH_PROCESS);
        List<String> allowedScopes = getAllowedRequestedScopes(taraSession.getLoginRequestInfo());
        if (!(allowedScopes.contains("eidas") || allowedScopes.contains("eidasonly"))) {
            throw new BadRequestException(INVALID_REQUEST, "Neither eidas or eidasonly scope is allowed.");
        }
    }

    @NotNull
    private List<String> getAllowedRequestedScopes(TaraSession.LoginRequestInfo loginRequestInfo) {
        return Arrays.asList(loginRequestInfo.getClient().getScope().split(" "));
    }

}
