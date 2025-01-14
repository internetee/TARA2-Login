package ee.ria.taraauthserver.authentication.consent;

import ee.ria.taraauthserver.config.properties.AuthConfigurationProperties;
import ee.ria.taraauthserver.config.properties.AuthConfigurationProperties.WebauthnConfigurationProperties;
import ee.ria.taraauthserver.config.properties.TaraScope;
import ee.ria.taraauthserver.logging.ClientRequestLogger;
import ee.ria.taraauthserver.logging.ClientRequestLogger.Service;
import ee.ria.taraauthserver.logging.StatisticsLogger;
import ee.ria.taraauthserver.session.SessionUtils;
import ee.ria.taraauthserver.session.TaraAuthenticationState;
import ee.ria.taraauthserver.session.TaraSession;
import org.jetbrains.annotations.NotNull;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.SessionAttribute;
import org.springframework.web.client.RestTemplate;

import javax.validation.constraints.Pattern;
import java.util.HashMap;
import java.util.Map;
import java.util.EnumSet;

import static ee.ria.taraauthserver.logging.ClientRequestLogger.Service;
import static ee.ria.taraauthserver.session.TaraAuthenticationState.CONSENT_GIVEN;
import static ee.ria.taraauthserver.session.TaraAuthenticationState.CONSENT_NOT_GIVEN;
import static ee.ria.taraauthserver.session.TaraAuthenticationState.AUTHENTICATION_SUCCESS;
import static ee.ria.taraauthserver.session.TaraAuthenticationState.VERIFICATION_SUCCESS;
import static ee.ria.taraauthserver.session.TaraAuthenticationState.WEBAUTHN_AUTHENTICATION_SUCCESS;
import static ee.ria.taraauthserver.session.TaraSession.TARA_SESSION;

@Validated
@Controller
public class AuthConsentConfirmController {
    public static final String REDIRECT_TO = "redirect_to";
    public static final String WEBAUTHN_REG_ID = "webauthn_reg_id";
    private static final EnumSet<TaraAuthenticationState> ALLOWED_STATES = EnumSet.of(AUTHENTICATION_SUCCESS, WEBAUTHN_AUTHENTICATION_SUCCESS, VERIFICATION_SUCCESS);

    @Autowired
    private AuthConfigurationProperties authConfigurationProperties;

    @Autowired
    private WebauthnConfigurationProperties webauthnConfigurationProperties;

    @Autowired
    private RestTemplate hydraRestTemplate;

    @Autowired
    private RestTemplate eeidRestTemplate;

    @Autowired
    private StatisticsLogger statisticsLogger;

    @PostMapping(value = "/auth/consent/confirm", produces = MediaType.TEXT_HTML_VALUE)
    public String authConsentConfirm(
            @RequestParam(name = "consent_given")
            @Pattern(regexp = "(true|false)", message = "supported values are: 'true', 'false'") String consentGiven,
            Model model,
            @SessionAttribute(value = TARA_SESSION, required = false) TaraSession taraSession) {
        SessionUtils.assertSessionInState(taraSession, ALLOWED_STATES);
        if (consentGiven.equals("true")) {
            String acceptConsentUrl;
            if (isWebauthnRequested(taraSession)) {
                acceptConsentUrl = webauthnConfigurationProperties.getClientUrl() + "/api/v1/webauthn/consent";
                return webauthnAcceptConsent(taraSession, acceptConsentUrl, model);
            } else {
                acceptConsentUrl = authConfigurationProperties.getHydraService().getAcceptConsentUrl() + "?consent_challenge=" + taraSession.getConsentChallenge();
                return acceptConsent(taraSession, acceptConsentUrl, model);
            }
        } else {
            return rejectConsent(taraSession);
        }
    }

    @NotNull
    private String rejectConsent(TaraSession taraSession) {
        String requestUrl = authConfigurationProperties.getHydraService().getRejectConsentUrl() + "?consent_challenge=" + taraSession.getConsentChallenge();
        Map<String, String> requestParams = new HashMap<>();
        requestParams.put("error", "user_cancel");
        requestParams.put("error_debug", "Consent not given. User canceled the authentication process.");
        requestParams.put("error_description", "Consent not given. User canceled the authentication process.");
        return getRedirectView(taraSession, CONSENT_NOT_GIVEN, requestUrl, requestParams, null);
    }

    @NotNull
    private String acceptConsent(TaraSession taraSession, String requestUrl, Model model) {
        AcceptConsentRequest acceptConsentRequest = AcceptConsentRequest.buildWithTaraSession(taraSession);
        return getRedirectView(taraSession, CONSENT_GIVEN, requestUrl, acceptConsentRequest, model);
    }

    private String getRedirectView(TaraSession taraSession, TaraAuthenticationState taraSessionState, String requestUrl, Object requestBody, Model model) {
        ClientRequestLogger requestLogger = new ClientRequestLogger(Service.TARA_HYDRA, this.getClass());
        requestLogger.logRequest(requestUrl, HttpMethod.PUT, requestBody);
        var response = hydraRestTemplate.exchange(
                requestUrl,
                HttpMethod.PUT,
                new HttpEntity<>(requestBody),
                new ParameterizedTypeReference<Map<String, String>>() {
                });
        requestLogger.logResponse(response);

        taraSession.setState(taraSessionState);

        if (response.getStatusCode() == HttpStatus.OK && response.getBody() != null && response.getBody().get(REDIRECT_TO) != null) {
            statisticsLogger.log(taraSession);
            SessionUtils.invalidateSession();
            return "redirect:" + response.getBody().get(REDIRECT_TO);
        } else {
            throw new IllegalStateException("Invalid OIDC server response. Redirect URL missing from response.");
        }
    }

    @NotNull
    private String webauthnAcceptConsent(TaraSession taraSession, String requestUrl, Model model) {
        AcceptConsentRequest acceptConsentRequest = AcceptConsentRequest.buildWithTaraSession(taraSession);
        
        ClientRequestLogger requestLogger = new ClientRequestLogger(Service.EEID, this.getClass());
        requestLogger.logRequest(requestUrl, HttpMethod.PUT, acceptConsentRequest);
        var response = eeidRestTemplate.exchange(
                requestUrl,
                HttpMethod.PUT,
                new HttpEntity<>(acceptConsentRequest),
                new ParameterizedTypeReference<Map<String, String>>() {
                });
        requestLogger.logResponse(response);

        taraSession.setState(CONSENT_GIVEN);

        if (response.getStatusCode() == HttpStatus.OK && response.getBody() != null && response.getBody().get(WEBAUTHN_REG_ID) != null) {
            return createWebauthnRegisterView(model, taraSession, response.getBody().get(WEBAUTHN_REG_ID));
        } else {
            throw new IllegalStateException("Invalid EEID server response.");
        }
    }

    private String createWebauthnRegisterView(Model model, TaraSession taraSession, String regId) {
        model.addAttribute("webauthn_reg_id", regId);
        return "redirectToWebauthnRegister";
    }

    private boolean isWebauthnRequested(TaraSession taraSession) {
        return taraSession.getState() != WEBAUTHN_AUTHENTICATION_SUCCESS && taraSession.getLoginRequestInfo().getRequestedScopes().contains(TaraScope.WEBAUTHN.getFormalName());
    }
}
