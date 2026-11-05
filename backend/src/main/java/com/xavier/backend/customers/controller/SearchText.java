package com.xavier.backend.customers.controller;

import com.xavier.backend.customers.model.CustomerQuery;
import jakarta.validation.Constraint;
import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;
import jakarta.validation.Payload;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/** A customer search text: at least 4 digits or 3 characters ({@link CustomerQuery}), else 400. */
@Target(ElementType.PARAMETER)
@Retention(RetentionPolicy.RUNTIME)
@Constraint(validatedBy = SearchText.Validator.class)
@interface SearchText {

    String message() default "search with at least 4 digits or 3 characters";

    Class<?>[] groups() default {};

    Class<? extends Payload>[] payload() default {};

    class Validator implements ConstraintValidator<SearchText, String> {

        @Override
        public boolean isValid(String value, ConstraintValidatorContext context) {
            return CustomerQuery.isValid(value);
        }
    }
}
