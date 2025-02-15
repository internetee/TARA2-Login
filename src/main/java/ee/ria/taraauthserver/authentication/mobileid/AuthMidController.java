package ee.ria.taraauthserver.authentication.mobileid;

import ee.ria.taraauthserver.config.properties.AuthenticationType;
import ee.ria.taraauthserver.error.exceptions.BadRequestException;
import ee.ria.taraauthserver.session.SessionUtils;
import ee.ria.taraauthserver.session.TaraSession;
import ee.ria.taraauthserver.utils.ValidNationalIdNumber;
import ee.sk.mid.MidAuthenticationHashToSign;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.SessionAttribute;

import javax.validation.constraints.NotNull;
import javax.validation.constraints.Pattern;

import static ee.ria.taraauthserver.error.ErrorCode.INVALID_REQUEST;
import static ee.ria.taraauthserver.session.TaraAuthenticationState.INIT_AUTH_PROCESS;
import static ee.ria.taraauthserver.session.TaraSession.TARA_SESSION;

@Slf4j
@Validated
@Controller
@ConditionalOnProperty(value = "tara.auth-methods.mobile-id.enabled")
public class AuthMidController {

    @Autowired
    private AuthMidService authMidService;

    @PostMapping(value = "/auth/mid/init", produces = MediaType.TEXT_HTML_VALUE, consumes = MediaType.APPLICATION_FORM_URLENCODED_VALUE)
    public String authMidInit(@Validated MidRequest midRequest, Model model, @SessionAttribute(value = TARA_SESSION, required = false) TaraSession taraSession) {
        log.info("Initiating Mobile-ID authentication session for country: " + midRequest.countryCode);
        validateSession(taraSession);
        midRequest.telephoneNumber = midRequest.phoneNumberPrefix + midRequest.telephoneNumber;
        MidAuthenticationHashToSign authenticationHash = authMidService.startMidAuthSession(taraSession, midRequest.getIdCode(), midRequest.getTelephoneNumber());
        String verificationCode = authenticationHash.calculateVerificationCode();
        model.addAttribute("mobileIdVerificationCode", verificationCode);
        return "midLoginCode";
    }

    public void validateSession(TaraSession taraSession) {
        SessionUtils.assertSessionInState(taraSession, INIT_AUTH_PROCESS);
        if (!taraSession.getAllowedAuthMethods().contains(AuthenticationType.MOBILE_ID)) {
            throw new BadRequestException(INVALID_REQUEST, "Mobile-ID authentication method is not allowed");
        }
    }

    @Data
    @ValidNationalIdNumber(fieldName = "idCode", dependFieldName = "countryCode")
    public static class MidRequest {
        private String idCode;
        private String countryCode;
        private String phoneNumberPrefix;
        @NotNull(message = "{message.mid-rest.error.invalid-phone-number}")
        @Pattern(regexp = "\\d{3,15}", message = "{message.mid-rest.error.invalid-phone-number}")
        private String telephoneNumber;
    }
}
