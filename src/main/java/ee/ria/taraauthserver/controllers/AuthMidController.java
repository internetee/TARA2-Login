package ee.ria.taraauthserver.controllers;

import ee.ria.taraauthserver.config.AuthConfigurationProperties;
import ee.ria.taraauthserver.config.AuthenticationType;
import ee.ria.taraauthserver.error.BadRequestException;
import ee.ria.taraauthserver.error.ErrorMessages;
import ee.ria.taraauthserver.error.ServiceNotAvailableException;
import ee.ria.taraauthserver.session.TaraSession;
import ee.ria.taraauthserver.utils.SessionUtils;
import ee.ria.taraauthserver.utils.ValidNationalIdNumber;
import ee.sk.mid.*;
import ee.sk.mid.exception.*;
import ee.sk.mid.rest.dao.MidSessionStatus;
import ee.sk.mid.rest.dao.request.MidAuthenticationRequest;
import ee.sk.mid.rest.dao.response.MidAuthenticationResponse;
import lombok.Data;
import lombok.ToString;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.http.MediaType;
import org.springframework.session.Session;
import org.springframework.session.SessionRepository;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PostMapping;

import javax.servlet.http.HttpSession;
import javax.validation.constraints.NotNull;
import javax.validation.constraints.Pattern;
import javax.ws.rs.InternalServerErrorException;
import javax.ws.rs.NotAllowedException;
import javax.ws.rs.ProcessingException;
import java.io.IOException;
import java.io.InputStream;
import java.security.KeyStore;
import java.security.KeyStoreException;
import java.security.NoSuchAlgorithmException;
import java.security.cert.CertificateException;
import java.util.Arrays;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.regex.Matcher;

import static ee.ria.taraauthserver.session.TaraAuthenticationState.*;
import static ee.ria.taraauthserver.utils.Constants.TARA_SESSION;

@Slf4j
@Validated
@Controller
@ConditionalOnProperty(value = "tara.mid-authentication.enabled", matchIfMissing = true)
public class AuthMidController {


    @Autowired
    private MidClient midClient;

    @Autowired
    private SessionRepository sessionRepository;

    @Autowired
    private AuthConfigurationProperties authConfigurationProperties;

    @Autowired
    private AuthConfigurationProperties.MidAuthConfigurationProperties midAuthConfigurationProperties;

    private Map<String, MidLanguage> midLanguages = Map.of(
            "et", MidLanguage.EST,
            "en", MidLanguage.ENG,
            "ru", MidLanguage.RUS);

    @PostMapping(value = "/auth/mid/init", produces = MediaType.TEXT_HTML_VALUE)
    public String authMidInit(@Validated @ModelAttribute(value = "credential") MidRequestBody requestParameters, Model model, HttpSession httpSession) {

        requestParameters.telephoneNumber = "+372" + requestParameters.telephoneNumber;

        Session session = sessionRepository.findById(httpSession.getId());
        if (session == null)
            throw new BadRequestException(ErrorMessages.SESSION_NOT_FOUND, "Invalid session");
        TaraSession taraSession = session.getAttribute(TARA_SESSION);
        validateAuthSession(taraSession);
        log.info("AuthSession: " + taraSession);

        MidAuthenticationHashToSign authenticationHash = MidAuthenticationHashToSign.generateRandomHashOfType(MidHashType.valueOf(midAuthConfigurationProperties.getHashType()));

        MidAuthenticationResponse response = createMidAuthSession(requestParameters, authenticationHash, taraSession);

        CompletableFuture.supplyAsync(
                () -> midClient.getSessionStatusPoller().fetchFinalSessionStatus(response.getSessionID(), "/authentication/session/" + response.getSessionID())
        ).thenApply(
                midSessionStatus -> handleMidPollResult(requestParameters, session, taraSession, authenticationHash, midSessionStatus)
        ).exceptionally(
                exception -> handlePossibleExceptions(session, taraSession, exception)
        );

        session.setAttribute(TARA_SESSION, taraSession);
        sessionRepository.save(session);

        model.addAttribute("mobileIdVerificationCode", authenticationHash.calculateVerificationCode());
        return "midLoginCode";
    }

    private TaraSession handleMidPollResult(MidRequestBody requestParameters, Session session, TaraSession taraSession, MidAuthenticationHashToSign authenticationHash, MidSessionStatus midSessionStatus) {
        if (StringUtils.equalsIgnoreCase("COMPLETE", midSessionStatus.getState())) {
            if (StringUtils.equalsIgnoreCase("OK", midSessionStatus.getResult())) {
                try {
                    MidAuthenticationResult midAuthenticationResult = validateAndReturnMidAuthenticationResult(authenticationHash, midSessionStatus);
                    updateAuthSessionWithResult(requestParameters, session, taraSession, midAuthenticationResult);
                } catch (Exception e) {
                    log.info("EXCEPTION: " + e.getMessage(), e);
                }
            }
        }
        return taraSession;
    }

    private void updateAuthSessionWithResult(MidRequestBody requestParameters, Session session, TaraSession taraSession, MidAuthenticationResult midAuthenticationResult) {
        String idCode = midAuthenticationResult.getAuthenticationIdentity().getIdentityCode();
        taraSession.setState(NATURAL_PERSON_AUTHENTICATION_COMPLETED);
        TaraSession.MidAuthenticationResult authenticationResult = new TaraSession.MidAuthenticationResult();

        authenticationResult.setIdCode(midAuthenticationResult.getAuthenticationIdentity().getIdentityCode());
        authenticationResult.setCountry(midAuthenticationResult.getAuthenticationIdentity().getCountry());
        authenticationResult.setFirstName(midAuthenticationResult.getAuthenticationIdentity().getGivenName());
        authenticationResult.setLastName(midAuthenticationResult.getAuthenticationIdentity().getSurName());
        authenticationResult.setPhoneNumber(requestParameters.getTelephoneNumber());
        authenticationResult.setSubject(midAuthenticationResult.getAuthenticationIdentity().getCountry() + midAuthenticationResult.getAuthenticationIdentity().getIdentityCode());
        authenticationResult.setDateOfBirth(MidNationalIdentificationCodeValidator.getBirthDate(idCode));
        authenticationResult.setAmr(AuthenticationType.MobileID);
        authenticationResult.setAcr(midAuthConfigurationProperties.getLevelOfAssurance());

        taraSession.setAuthenticationResult(authenticationResult);
        session.setAttribute(TARA_SESSION, taraSession);
        sessionRepository.save(session);
    }

    private TaraSession handlePossibleExceptions(Session session, TaraSession taraSession, Throwable e) {
        if (e.getCause() instanceof javax.ws.rs.BadRequestException) {
            setSessionStateFailed(session, taraSession, e.getMessage());
        }
        if (e.getCause() instanceof javax.ws.rs.NotAuthorizedException) {
            setSessionStateFailed(session, taraSession, e.getMessage());
        }
        if (e.getCause() instanceof MidSessionNotFoundException) {
            setSessionStateFailed(session, taraSession, e.getMessage());
        }
        if (e.getCause() instanceof NotAllowedException) {
            setSessionStateFailed(session, taraSession, e.getMessage());
        }
        if (e.getCause() instanceof InternalServerErrorException) {
            ((TaraSession.MidAuthenticationResult) taraSession.getAuthenticationResult()).setErrorMessage(ErrorMessages.MID_INTERNAL_ERROR);
            setSessionStateFailed(session, taraSession, e.getMessage());
        }
        if (e.getCause() instanceof MidNotMidClientException) {
            ((TaraSession.MidAuthenticationResult) taraSession.getAuthenticationResult()).setErrorMessage(ErrorMessages.NOT_MID_CLIENT);
            ((TaraSession.MidAuthenticationResult) taraSession.getAuthenticationResult()).setErrorStatus(400);
            setSessionStateFailed(session, taraSession, e.getMessage());
        }
        if (e.getCause() instanceof MidSessionTimeoutException) {
            ((TaraSession.MidAuthenticationResult) taraSession.getAuthenticationResult()).setErrorMessage(ErrorMessages.MID_TRANSACTION_EXPIRED);
            ((TaraSession.MidAuthenticationResult) taraSession.getAuthenticationResult()).setErrorStatus(500);
            setSessionStateFailed(session, taraSession, e.getMessage());
        }
        if (e.getCause() instanceof MidUserCancellationException) {
            ((TaraSession.MidAuthenticationResult) taraSession.getAuthenticationResult()).setErrorMessage(ErrorMessages.MID_USER_CANCEL);
            ((TaraSession.MidAuthenticationResult) taraSession.getAuthenticationResult()).setErrorStatus(400);
            setSessionStateFailed(session, taraSession, e.getMessage());
        }
        if (e.getCause() instanceof MidInvalidUserConfigurationException) {
            ((TaraSession.MidAuthenticationResult) taraSession.getAuthenticationResult()).setErrorMessage(ErrorMessages.MID_HASH_MISMATCH);
            ((TaraSession.MidAuthenticationResult) taraSession.getAuthenticationResult()).setErrorStatus(500);
            setSessionStateFailed(session, taraSession, e.getMessage());
        }
        if (e.getCause() instanceof MidPhoneNotAvailableException) {
            ((TaraSession.MidAuthenticationResult) taraSession.getAuthenticationResult()).setErrorMessage(ErrorMessages.MID_PHONE_ABSENT);
            ((TaraSession.MidAuthenticationResult) taraSession.getAuthenticationResult()).setErrorStatus(400);
            setSessionStateFailed(session, taraSession, e.getMessage());
        }
        if (e.getCause() instanceof MidDeliveryException) {
            ((TaraSession.MidAuthenticationResult) taraSession.getAuthenticationResult()).setErrorMessage(ErrorMessages.MID_DELIVERY_ERROR);
            ((TaraSession.MidAuthenticationResult) taraSession.getAuthenticationResult()).setErrorStatus(400);
            setSessionStateFailed(session, taraSession, e.getMessage());
        }
        if (e.getCause() instanceof MidInternalErrorException) {
            ((TaraSession.MidAuthenticationResult) taraSession.getAuthenticationResult()).setErrorMessage(ErrorMessages.MID_INTERNAL_ERROR);
            setSessionStateFailed(session, taraSession, e.getMessage());
        }
        return null;
    }

    private void setSessionStateFailed(Session session, TaraSession taraSession, String message) {
        log.info("Mid polling failed: " + message);
        taraSession.setState(AUTHENTICATION_FAILED);
        session.setAttribute(TARA_SESSION, taraSession);
        sessionRepository.save(session);
    }

    private void validateAuthSession(TaraSession taraSession) {

        if (taraSession == null) {
            throw new BadRequestException(ErrorMessages.SESSION_NOT_FOUND, "authSession is null");
        }
        if (taraSession.getState() != INIT_AUTH_PROCESS) {
            throw new BadRequestException(ErrorMessages.SESSION_STATE_INVALID, "authSession state should be " + INIT_AUTH_PROCESS + " but was " + taraSession.getState());
        }
        if (!taraSession.getAllowedAuthMethods().contains(AuthenticationType.MobileID)) {
            throw new BadRequestException(ErrorMessages.INVALID_REQUEST, "Mobile ID authentication method is not allowed");
        }
        taraSession.setState(INIT_MID);
    }

    private MidAuthenticationResponse createMidAuthSession(MidRequestBody requestParameters, MidAuthenticationHashToSign authenticationHash, TaraSession taraSession) {

        MidAuthenticationRequest midRequest = createMidAuthenticationRequest(requestParameters, authenticationHash);

        try {
            log.info("Mid request: " + midRequest.toString());
            MidAuthenticationResponse response = midClient.getMobileIdConnector().authenticate(midRequest);
            log.info("Mid response: " + response.toString());
            updateAuthSessionWithInitResponse(taraSession, response);
            return response;
        } catch (MidInternalErrorException | ProcessingException e) {
            throw new ServiceNotAvailableException(ErrorMessages.MID_INTERNAL_ERROR, String.format("MID service is currently unavailable: %s", e.getMessage()), e);
        } catch (Exception e) {
            throw new IllegalStateException("Internal error during MID authentication init: " + e.getMessage(), e);
        }
    }

    private void updateAuthSessionWithInitResponse(TaraSession taraSession, MidAuthenticationResponse response) {
        taraSession.setState(POLL_MID_STATUS);
        TaraSession.MidAuthenticationResult midAuthenticationResult = new TaraSession.MidAuthenticationResult();
        taraSession.setAuthenticationResult(midAuthenticationResult);
        midAuthenticationResult.setMidSessionId(response.getSessionID());
    }

    private MidAuthenticationRequest createMidAuthenticationRequest(MidRequestBody requestParameters, MidAuthenticationHashToSign authenticationHash) {
        String translatedShortName = getTranslatedShortName(SessionUtils.getAuthSession());
        MidAuthenticationRequest midRequest = MidAuthenticationRequest.newBuilder()
                .withPhoneNumber(requestParameters.getTelephoneNumber())
                .withNationalIdentityNumber(requestParameters.getIdCode())
                .withHashToSign(authenticationHash)
                .withLanguage(getMidLanguage())
                .withDisplayText(translatedShortName)
                .withDisplayTextFormat(isServiceNameUsingSpecialCharacters(translatedShortName) ? MidDisplayTextFormat.UCS2 : MidDisplayTextFormat.GSM7)
                .build();
        return midRequest;
    }

    private String getTranslatedShortName(TaraSession taraSession) {
        String translatedShortName = taraSession.getLoginRequestInfo().getClient().getMetaData().getOidcClient().getShortName();

        if (taraSession.getLoginRequestInfo().getClient().getMetaData().getOidcClient().getNameTranslations() != null) {
            Map<String, String> serviceNameTranslations = taraSession.getLoginRequestInfo().getClient().getMetaData().getOidcClient().getNameTranslations();
            Locale locale = LocaleContextHolder.getLocale();
            if (serviceNameTranslations.containsKey(locale.getLanguage()))
                translatedShortName = serviceNameTranslations.get(locale.getLanguage());
        }
        return translatedShortName;
    }

    private static boolean isServiceNameUsingSpecialCharacters(String serviceName) {
        java.util.regex.Pattern p = java.util.regex.Pattern.compile("[а-яА-ЯЁё]", java.util.regex.Pattern.CASE_INSENSITIVE);
        String[] specialCharacters = {"Õ", "Š", "Ž", "š", "ž", "õ", "Ą", "Č", "Ę", "Ė", "Į", "Š", "Ų", "Ū", "Ž", "ą", "č", "ę", "ė", "į", "š", "ų", "ū", "ž"};
        Matcher m = p.matcher(serviceName);
        boolean isSpecialCharacterIncluded = m.find();
        return Arrays.stream(specialCharacters).anyMatch(serviceName::contains) || isSpecialCharacterIncluded;
    }

    private MidAuthenticationResult validateAndReturnMidAuthenticationResult(MidAuthenticationHashToSign authenticationHash, MidSessionStatus midSessionStatus) {
        try {
            MidAuthentication authentication = midClient.createMobileIdAuthentication(midSessionStatus, authenticationHash);
            MidAuthenticationResponseValidator validator = new MidAuthenticationResponseValidator(getMidTrustStore());
            return validator.validate(authentication);
        } catch (Exception e) {
            log.error("Error when validating mid authentication result - " + e.getCause().getMessage());
            throw new IllegalStateException("Internal server error when validating mid authentication result.");
        }
    }

    public KeyStore getMidTrustStore() throws KeyStoreException, CertificateException, NoSuchAlgorithmException, IOException {
        log.info("getting mid truststore");
        InputStream is = getClass().getClassLoader().getResourceAsStream(midAuthConfigurationProperties.getTruststorePath());
        KeyStore trustStore = KeyStore.getInstance(midAuthConfigurationProperties.getTruststoreType());
        trustStore.load(is, midAuthConfigurationProperties.getTruststorePassword().toCharArray());
        return trustStore;
    }

    private MidLanguage getMidLanguage() {
        Locale locale = LocaleContextHolder.getLocale();
        return midLanguages.get(locale.getLanguage());
    }

    @Data
    @ToString
    public static class MidRequestBody {
        @ValidNationalIdNumber(message = "{message.mid-rest.error.invalid-identity-code}")
        private String idCode;
        @NotNull(message = "{message.mid-rest.error.invalid-phone-number}")
        @Pattern(regexp = "\\d{8,15}", message = "{message.mid-rest.error.invalid-phone-number}")
        private String telephoneNumber;
    }
}
