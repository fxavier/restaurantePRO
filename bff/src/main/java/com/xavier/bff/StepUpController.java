package com.xavier.bff;

import jakarta.servlet.http.HttpSession;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.view.RedirectView;

/** The SPA navigates here (top level) after a 401 insufficient_user_authentication, then repeats its request itself. */
@RestController
class StepUpController {

    @GetMapping("/bff/step-up")
    RedirectView stepUp(@RequestParam("acr_values") String acrValues, @RequestParam("max_age") String maxAge,
                        @RequestParam("return") String returnPath, HttpSession session) {
        StepUp.parse(acrValues, maxAge, returnPath).rememberIn(session);
        return new RedirectView(SecurityConfig.AUTHORIZATION_PATH, true);
    }

    @ExceptionHandler(StepUp.InvalidStepUpException.class)
    ProblemDetail invalid(StepUp.InvalidStepUpException e) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, e.getMessage());
    }
}
