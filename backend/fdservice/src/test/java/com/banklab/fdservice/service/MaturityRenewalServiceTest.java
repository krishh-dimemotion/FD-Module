package com.banklab.fdservice.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import com.banklab.fdservice.dto.AccountStatus;
import com.banklab.fdservice.dto.InterestType;
import com.banklab.fdservice.dto.RenewFdAccountRequest;
import com.banklab.fdservice.entity.FdAccount;
import com.banklab.fdservice.entity.FdAccountRole;
import com.banklab.fdservice.repository.FdAccountRepository;
import com.banklab.fdservice.repository.FdAccountRoleRepository;

@ExtendWith(MockitoExtension.class)
class MaturityRenewalServiceTest {

    private static final LocalDate MATURITY = LocalDate.parse("2027-01-01");

    @Mock private FdAccountRepository accounts;
    @Mock private FdAccountRoleRepository roles;
    @Mock private AccountNumberGeneratorService numbers;
    @Mock private BusinessClockService clock;
    @Mock private InterestPostingService interestPosting;
    @Mock private FdLedgerService ledger;

    private MaturityRenewalService service;

    @BeforeEach
    void setUp() {
        service = new MaturityRenewalService(accounts, roles, numbers, clock, interestPosting,
                new InterestCalculator(), ledger);
        ReflectionTestUtils.setField(service, "defaultBranchCd", "001");
        ReflectionTestUtils.setField(service, "defaultInterestType", InterestType.COMPOUND);
    }

    @Test
    void renewsAMaturedActiveAccountIntoANewLinkedAccountAndCopiesRoles() {
        FdAccount old = activeAccount();
        when(accounts.lockById(old.getFdaId())).thenReturn(Optional.of(old));
        when(clock.currentBusinessDate()).thenReturn(MATURITY);
        when(numbers.generateAccountNumber("001")).thenReturn("FD001000099");
        when(interestPosting.hasContractedRate(old)).thenReturn(true);
        when(accounts.saveAndFlush(any(FdAccount.class))).thenAnswer(call -> call.getArgument(0));
        when(roles.findByFdaId(old.getFdaId())).thenReturn(List.of(FdAccountRole.builder()
                .fdaId(old.getFdaId()).custId("CUST-001").roleTyp("OWNER").isPrimary(true).build()));

        var response = service.renew(old.getFdaId(), new RenewFdAccountRequest(6));

        ArgumentCaptor<FdAccount> renewedCaptor = ArgumentCaptor.forClass(FdAccount.class);
        verify(accounts).saveAndFlush(renewedCaptor.capture());
        FdAccount renewed = renewedCaptor.getValue();
        assertThat(renewed.getRenewedFromId()).isEqualTo(old.getFdaId());
        assertThat(renewed.getAcctNum()).isEqualTo("FD001000099");
        assertThat(renewed.getPrincipalAmt()).isEqualByComparingTo("100000.00");
        assertThat(renewed.getMatDt()).isEqualTo(LocalDate.parse("2027-07-01"));
        assertThat(response.status()).isEqualTo(AccountStatus.ACTIVE);
        assertThat(old.getSts()).isEqualTo(AccountStatus.MATURED_RENEWED.name());
        assertThat(old.getPrincipalBal()).isEqualByComparingTo("100000.00");

        verify(interestPosting).bringAccrualTo(eq(old), eq(MATURITY), eq(MATURITY), eq(FdLedgerService.API_USER));
        verify(interestPosting).settleAccruedInterest(eq(old), eq(MATURITY), eq(MATURITY), any(UUID.class),
                eq(FdLedgerService.API_USER));
        verify(ledger).postRenewalTransferOut(eq(old), eq(new BigDecimal("100000.00")), eq(MATURITY),
                any(UUID.class), eq(FdLedgerService.API_USER));
        verify(ledger).postRenewalTransferIn(eq(renewed), eq(new BigDecimal("100000.00")), eq(MATURITY),
                any(UUID.class), eq(FdLedgerService.API_USER));
        verify(roles).saveAll(any());
    }

    @Test
    void rejectsRenewalBeforeMaturity() {
        FdAccount old = activeAccount();
        when(accounts.lockById(old.getFdaId())).thenReturn(Optional.of(old));
        when(clock.currentBusinessDate()).thenReturn(MATURITY.minusDays(1));

        assertThatThrownBy(() -> service.renew(old.getFdaId(), null))
                .isInstanceOf(AccountNotRenewableException.class)
                .extracting("code").isEqualTo(AccountNotRenewableException.ACCOUNT_NOT_MATURED);
    }

    private static FdAccount activeAccount() {
        return FdAccount.builder()
                .fdaId(UUID.randomUUID())
                .acctNum("FD001000001")
                .custId("CUST-001")
                .prdCode("FD-REG")
                .rateId("RATE-12M")
                .ccyCd("INR")
                .ccyDecimals(2)
                .principalAmt(new BigDecimal("100000.00"))
                .principalBal(new BigDecimal("100000.00"))
                .accruedIntAmt(BigDecimal.ZERO)
                .intRt(new BigDecimal("6.50"))
                .intTyp("COMPOUND")
                .compoundFreq("QUARTERLY")
                .payoutFreq("ON_MATURITY")
                .dayCountConv("ACT/ACT")
                .tenureMonths(12)
                .openDt(LocalDate.parse("2026-01-01"))
                .valueDt(LocalDate.parse("2026-01-01"))
                .matDt(MATURITY)
                .matInstruction("PAYOUT")
                .sts("ACTIVE")
                .build();
    }
}
