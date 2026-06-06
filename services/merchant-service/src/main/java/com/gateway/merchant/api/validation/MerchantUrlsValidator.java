package com.gateway.merchant.api.validation;

import com.gateway.merchant.api.dto.CreateMerchantRequest;
import com.gateway.merchant.domain.MerchantMode;
import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.regex.PatternSyntaxException;

public class MerchantUrlsValidator
        implements ConstraintValidator<MerchantUrlsValid, CreateMerchantRequest> {

    private static final String INVALID_URL = "invalid_url";
    private static final String INVALID_PATTERN = "invalid_pattern";

    @Override
    public boolean isValid(CreateMerchantRequest req, ConstraintValidatorContext ctx) {
        if (req == null) {
            return true;
        }

        ctx.disableDefaultConstraintViolation();
        boolean valid = true;

        valid &= validateCallbackUrl(req.callbackUrl(), req.mode(), ctx);
        valid &= validatePattern(req.returnUrlPattern(), "returnUrlPattern", ctx);
        valid &= validatePattern(req.cancelUrlPattern(), "cancelUrlPattern", ctx);

        return valid;
    }

    private boolean validateCallbackUrl(
            String callbackUrl, MerchantMode mode, ConstraintValidatorContext ctx) {
        if (callbackUrl == null || callbackUrl.isBlank()) {
            return true; // @NotBlank handles the empty case
        }

        URI uri;
        try {
            uri = new URI(callbackUrl);
        } catch (URISyntaxException e) {
            addViolation(ctx, "callbackUrl", INVALID_URL, "callback_url must be a valid URI");
            return false;
        }

        String scheme = uri.getScheme();
        if (scheme == null) {
            addViolation(
                    ctx, "callbackUrl", INVALID_URL, "callback_url must include a scheme (https)");
            return false;
        }

        if ("https".equals(scheme)) {
            return true;
        }

        if ("http".equals(scheme) && mode == MerchantMode.TEST) {
            String host = uri.getHost();
            if ("localhost".equals(host) || "host.docker.internal".equals(host)) {
                return true;
            }
        }

        addViolation(
                ctx,
                "callbackUrl",
                INVALID_URL,
                "callback_url must use https (http only allowed for localhost or host.docker.internal on test merchants)");
        return false;
    }

    private boolean validatePattern(String pattern, String field, ConstraintValidatorContext ctx) {
        if (pattern == null || pattern.isBlank()) {
            return true; // @NotBlank handles the empty case
        }

        try {
            java.util.regex.Pattern.compile(pattern);
            return true;
        } catch (PatternSyntaxException e) {
            addViolation(ctx, field, INVALID_PATTERN, field + " is not a valid regular expression");
            return false;
        }
    }

    private void addViolation(
            ConstraintValidatorContext ctx, String field, String code, String message) {
        ctx.buildConstraintViolationWithTemplate(code + ": " + message)
                .addPropertyNode(field)
                .addConstraintViolation();
    }
}
