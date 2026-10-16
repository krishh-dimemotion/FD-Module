package com.banklab.fdservice.service;

import java.time.LocalDate;
import java.util.UUID;

import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import com.banklab.fdservice.dto.AccountStatus;
import com.banklab.fdservice.dto.FdAccountSummary;
import com.banklab.fdservice.dto.FdTxnType;
import com.banklab.fdservice.dto.InterestPreviewRequest;
import com.banklab.fdservice.dto.InterestPreviewResponse;
import com.banklab.fdservice.dto.PageResponse;
import com.banklab.fdservice.entity.FdAccount;
import com.banklab.fdservice.repository.FdAccountRepository;
import com.banklab.fdservice.repository.FdTransactionRepository;

import lombok.RequiredArgsConstructor;

/**
 * Read side of the account endpoints: view, search, transaction history and the
 * interest preview (which computes but never persists).
 */
@Service
@RequiredArgsConstructor
public class FdAccountQueryService {

    @org.springframework.beans.factory.annotation.Autowired
    private com.banklab.fdservice.tax.TaxService taxes;

    private static final int MAX_PAGE_SIZE = 200;

    private final FdAccountRepository accountRepository;
    private final FdTransactionRepository transactionRepository;
    private final InterestCalculator interestCalculator;
    private final InterestPostingService interestPosting;

    @Transactional(readOnly = true)
    public com.banklab.fdservice.dto.FdAccount getAccount(UUID fdaId) {
        return FdAccountMapper.toResponse(find(fdaId));
    }

    @Transactional(readOnly = true)
    public PageResponse<FdAccountSummary> search(String custId, String acctNum, AccountStatus status,
            String productCode, int page, int size) {
        return PageResponse.of(
                accountRepository.search(blankToNull(custId), blankToNull(acctNum),
                        status == null ? null : status.name(), blankToNull(productCode), pageRequest(page, size)),
                FdAccountMapper::toSummary);
    }

    @Transactional(readOnly = true)
    public PageResponse<com.banklab.fdservice.dto.FdTransaction> listTransactions(UUID fdaId, FdTxnType txnType,
            LocalDate fromDate, LocalDate toDate, int page, int size) {
        find(fdaId);
        return PageResponse.of(
                transactionRepository.search(fdaId, txnType == null ? null : txnType.name(), fromDate, toDate,
                        pageRequest(page, size)),
                FdAccountMapper::toResponse);
    }

    /**
     * OpenAPI calculateInterestPreview: the account's current principal balance and
     * snapshotted terms over a caller-supplied period. No database writes.
     */
    @Transactional(readOnly = true)
    public InterestPreviewResponse previewInterest(UUID fdaId, InterestPreviewRequest request) {
        FdAccount account = find(fdaId);
        InterestTerms terms = interestPosting.termsOf(account);
        var principal = account.getPrincipalBal() != null ? account.getPrincipalBal() : account.getPrincipalAmt();
        InterestCalculation result = interestCalculator.calculate(principal, terms, request.periodStart(),
                request.periodEnd(), taxes==null ? (gross,date)->java.math.BigDecimal.ZERO
                : taxes.session(account,request.periodStart(),request.periodEnd(),false)::assess);
        return new InterestPreviewResponse(
                principal,
                terms.annualRatePct(),
                request.periodStart(),
                request.periodEnd(),
                terms.dayCountConvention().code(),
                result.totalInterest(),
                account.getCcyCd(),
                terms.interestType(),
                account.getCompoundFreq(),
                account.getPayoutFreq(),
                result.days(),
                result.interestPaidOut(),
                result.maturityValue(),result.taxAmount(),Boolean.TRUE.equals(account.getTaxEnabled()));
    }

    private FdAccount find(UUID fdaId) {
        return accountRepository.findById(fdaId).orElseThrow(() -> new FdAccountNotFoundException(fdaId));
    }

    private static PageRequest pageRequest(int page, int size) {
        return PageRequest.of(Math.max(page, 0), Math.clamp(size, 1, MAX_PAGE_SIZE));
    }

    private static String blankToNull(String value) {
        return StringUtils.hasText(value) ? value.trim() : null;
    }
}
