package com.xavier.backend.customers.model.dto;

import com.xavier.backend.customers.model.PhoneNumber;
import jakarta.validation.Constraint;
import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;
import jakarta.validation.Payload;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/** A phone written with digits and separators, 4 to 20 digits ({@link PhoneNumber}); null is valid. */
@Target({ElementType.FIELD, ElementType.PARAMETER, ElementType.RECORD_COMPONENT})
@Retention(RetentionPolicy.RUNTIME)
@Constraint(validatedBy = PhoneDigits.Validator.class)
public @interface PhoneDigits {

    String message() default "must be a phone number of 4 to 20 digits";

    Class<?>[] groups() default {};

    Class<? extends Payload>[] payload() default {};

    class Validator implements ConstraintValidator<PhoneDigits, String> {

        @Override
        public boolean isValid(String value, ConstraintValidatorContext context) {
            if (value == null) {
                return true;
            }
            try {
                PhoneNumber.of(value);
                return true;
            } catch (IllegalArgumentException e) {
                return false;
            }
        }
    }
}
