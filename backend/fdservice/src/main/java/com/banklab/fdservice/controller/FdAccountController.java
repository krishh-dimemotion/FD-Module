package com.banklab.fdservice.controller;

import java.time.LocalDate;
import java.util.UUID;

import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.banklab.fdservice.dto.AccountStatus;
import com.banklab.fdservice.dto.CreateFdAccountRequest;
import com.banklab.fdservice.dto.FdAccountSummary;
import com.banklab.fdservice.dto.FdTxnType;
import com.banklab.fdservice.dto.InterestPreviewRequest;
import com.banklab.fdservice.dto.InterestPreviewResponse;
import com.banklab.fdservice.dto.PageResponse;
import com.banklab.fdservice.dto.PrematureWithdrawalRequest;
import com.banklab.fdservice.dto.PrematureWithdrawalResponse;
import com.banklab.fdservice.dto.WithdrawalQuoteResponse;
import com.banklab.fdservice.service.FdAccountQueryService;
import com.banklab.fdservice.service.FdAccountService;
import com.banklab.fdservice.service.PrematureWithdrawalService;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;

@RestController
@RequestMapping("/fd-accounts")
@RequiredArgsConstructor
public class FdAccountController {

    private final FdAccountService fdAccountService;
    private final FdAccountQueryService queryService;
    private final PrematureWithdrawalService withdrawalService;

    @PostMapping
    public ResponseEntity<com.banklab.fdservice.dto.FdAccount> createAccount(
            @Valid @RequestBody CreateFdAccountRequest request) {
        com.banklab.fdservice.dto.FdAccount created = fdAccountService.createAccount(request);
        return ResponseEntity.status(HttpStatus.CREATED).body(created);
    }

    @GetMapping
    public PageResponse<FdAccountSummary> searchAccounts(
            @RequestParam(required = false) String custId,
            @RequestParam(required = false) String acctNum,
            @RequestParam(required = false) AccountStatus status,
            @RequestParam(required = false) String productCode,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return queryService.search(custId, acctNum, status, productCode, page, size);
    }

    @GetMapping("/{accountId}")
    public com.banklab.fdservice.dto.FdAccount getAccount(@PathVariable UUID accountId) {
        return queryService.getAccount(accountId);
    }

    @GetMapping("/{accountId}/transactions")
    public PageResponse<com.banklab.fdservice.dto.FdTransaction> listTransactions(
            @PathVariable UUID accountId,
            @RequestParam(required = false) FdTxnType txnType,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate fromDate,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate toDate,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return queryService.listTransactions(accountId, txnType, fromDate, toDate, page, size);
    }

    @PostMapping("/{accountId}/interest-calculations")
    public InterestPreviewResponse calculateInterestPreview(@PathVariable UUID accountId,
            @Valid @RequestBody InterestPreviewRequest request) {
        return queryService.previewInterest(accountId, request);
    }

    @PostMapping("/{accountId}/withdrawal-quote")
    public WithdrawalQuoteResponse quoteEarlyWithdrawal(@PathVariable UUID accountId) {
        return withdrawalService.quote(accountId);
    }

    @PostMapping("/{accountId}/withdraw")
    public PrematureWithdrawalResponse processEarlyWithdrawal(@PathVariable UUID accountId,
            @Valid @RequestBody(required = false) PrematureWithdrawalRequest request) {
        return withdrawalService.withdraw(accountId, request);
    }
}
