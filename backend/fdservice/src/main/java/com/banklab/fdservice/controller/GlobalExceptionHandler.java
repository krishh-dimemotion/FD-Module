package com.banklab.fdservice.controller;

import java.util.stream.Collectors;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

import com.banklab.fdservice.batch.BatchJobAlreadyRunningException;
import com.banklab.fdservice.batch.BusinessDateAfterClockException;
import com.banklab.fdservice.client.UpstreamServiceException;
import com.banklab.fdservice.dto.ErrorResponse;
import com.banklab.fdservice.service.AccountNotWithdrawableException;
import com.banklab.fdservice.service.AccountNotRenewableException;
import com.banklab.fdservice.service.AccountSequenceNotConfiguredException;
import com.banklab.fdservice.service.CategoryMappingUnresolvedException;
import com.banklab.fdservice.service.FdAccountNotFoundException;
import com.banklab.fdservice.service.GlAccountNotConfiguredException;
import com.banklab.fdservice.service.InvalidInterestTermsException;
import com.banklab.fdservice.service.InvalidPeriodException;
import com.banklab.fdservice.service.InvalidPrincipalException;
import com.banklab.fdservice.service.RoleCardinalityViolationException;

@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ErrorResponse> handleValidation(MethodArgumentNotValidException ex) {
        String message = ex.getBindingResult().getFieldErrors().stream()
                .map(error -> error.getField() + ": " + error.getDefaultMessage())
                .collect(Collectors.joining("; "));
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(new ErrorResponse("VALIDATION_ERROR", message));
    }

    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<ErrorResponse> handleTypeMismatch(MethodArgumentTypeMismatchException ex) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(new ErrorResponse("VALIDATION_ERROR", ex.getName() + ": invalid value '" + ex.getValue() + "'"));
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ErrorResponse> handleUnreadable(HttpMessageNotReadableException ex) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(new ErrorResponse("VALIDATION_ERROR", "Malformed request body"));
    }

    @ExceptionHandler(InvalidPrincipalException.class)
    public ResponseEntity<ErrorResponse> handleInvalidPrincipal(InvalidPrincipalException ex) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(new ErrorResponse("INVALID_PRINCIPAL", ex.getMessage()));
    }

    @ExceptionHandler(BusinessDateAfterClockException.class)
    public ResponseEntity<ErrorResponse> handleBusinessDateAfterClock(BusinessDateAfterClockException ex) {
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(new ErrorResponse("BUSINESS_DATE_AFTER_CLOCK", ex.getMessage()));
    }

    @ExceptionHandler(InvalidPeriodException.class)
    public ResponseEntity<ErrorResponse> handleInvalidPeriod(InvalidPeriodException ex) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(new ErrorResponse("INVALID_PERIOD", ex.getMessage()));
    }

    @ExceptionHandler(UpstreamServiceException.class)
    public ResponseEntity<ErrorResponse> handleUpstreamService(UpstreamServiceException ex) {
        HttpStatus status = switch (ex.getReason()) {
            case NOT_FOUND -> HttpStatus.NOT_FOUND;
            case UPSTREAM_ERROR -> HttpStatus.BAD_GATEWAY;
        };
        return ResponseEntity.status(status).body(new ErrorResponse(ex.getReason().name(), ex.getMessage()));
    }

    @ExceptionHandler(FdAccountNotFoundException.class)
    public ResponseEntity<ErrorResponse> handleAccountNotFound(FdAccountNotFoundException ex) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(new ErrorResponse("NOT_FOUND", ex.getMessage()));
    }

    @ExceptionHandler(CategoryMappingUnresolvedException.class)
    public ResponseEntity<ErrorResponse> handleCategoryUnresolved(CategoryMappingUnresolvedException ex) {
        return ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY)
                .body(new ErrorResponse("CATEGORY_UNRESOLVED", ex.getMessage()));
    }

    @ExceptionHandler(InvalidInterestTermsException.class)
    public ResponseEntity<ErrorResponse> handleInvalidInterestTerms(InvalidInterestTermsException ex) {
        return ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY)
                .body(new ErrorResponse("INVALID_INTEREST_TERMS", ex.getMessage()));
    }

    @ExceptionHandler(RoleCardinalityViolationException.class)
    public ResponseEntity<ErrorResponse> handleRoleCardinality(RoleCardinalityViolationException ex) {
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(new ErrorResponse("ROLE_CARDINALITY_VIOLATION", ex.getMessage()));
    }

    @ExceptionHandler(AccountNotWithdrawableException.class)
    public ResponseEntity<ErrorResponse> handleNotWithdrawable(AccountNotWithdrawableException ex) {
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(new ErrorResponse(ex.getCode(), ex.getMessage()));
    }

    @ExceptionHandler(AccountNotRenewableException.class)
    public ResponseEntity<ErrorResponse> handleNotRenewable(AccountNotRenewableException ex) {
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(new ErrorResponse(ex.getCode(), ex.getMessage()));
    }

    @ExceptionHandler(BatchJobAlreadyRunningException.class)
    public ResponseEntity<ErrorResponse> handleBatchRunning(BatchJobAlreadyRunningException ex) {
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(new ErrorResponse("BATCH_ALREADY_RUNNING", ex.getMessage()));
    }

    @ExceptionHandler(AccountSequenceNotConfiguredException.class)
    public ResponseEntity<ErrorResponse> handleSequenceNotConfigured(AccountSequenceNotConfiguredException ex) {
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(new ErrorResponse("ACCOUNT_SEQUENCE_NOT_CONFIGURED", ex.getMessage()));
    }

    @ExceptionHandler(GlAccountNotConfiguredException.class)
    public ResponseEntity<ErrorResponse> handleGlNotConfigured(GlAccountNotConfiguredException ex) {
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(new ErrorResponse("GL_ACCOUNT_NOT_CONFIGURED", ex.getMessage()));
    }
}
