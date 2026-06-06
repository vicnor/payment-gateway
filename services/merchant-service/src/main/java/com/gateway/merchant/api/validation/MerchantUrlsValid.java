package com.gateway.merchant.api.validation;

import jakarta.validation.Constraint;
import jakarta.validation.Payload;
import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

@Documented
@Constraint(validatedBy = MerchantUrlsValidator.class)
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
public @interface MerchantUrlsValid {

    String message() default "Invalid merchant URLs or URL patterns";

    Class<?>[] groups() default {};

    Class<? extends Payload>[] payload() default {};
}
