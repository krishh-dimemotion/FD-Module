package com.banklab.fdservice.service;

import com.banklab.fdservice.dto.AccountStatus;
import com.banklab.fdservice.dto.BatchJobName;
import com.banklab.fdservice.dto.BatchRun;
import com.banklab.fdservice.dto.BatchRunStatus;
import com.banklab.fdservice.dto.BusinessClockView;
import com.banklab.fdservice.dto.EodStatus;
import com.banklab.fdservice.dto.FdAccountSummary;
import com.banklab.fdservice.dto.GlAccountView;
import com.banklab.fdservice.dto.InterestType;
import com.banklab.fdservice.dto.MaturityInstruction;
import com.banklab.fdservice.entity.FdAccount;
import com.banklab.fdservice.entity.FdBatchRunLog;
import com.banklab.fdservice.entity.FdBusinessClock;
import com.banklab.fdservice.entity.FdGlAccount;
import com.banklab.fdservice.entity.FdTransaction;

/**
 * Entity → API response mapping, shared by every controller-facing service.
 */
public final class FdAccountMapper {

    private FdAccountMapper() {
    }

    public static com.banklab.fdservice.dto.FdAccount toResponse(FdAccount entity) {
        return com.banklab.fdservice.dto.FdAccount.builder()
                .fdaId(entity.getFdaId().toString())
                .acctNum(entity.getAcctNum())
                .custId(entity.getCustId())
                .productCode(entity.getPrdCode())
                .rateId(entity.getRateId())
                .categoryCd(entity.getCategoryCd())
                .currencyCode(entity.getCcyCd())
                .principalAmt(money(entity.getPrincipalAmt(),entity.getCcyCd()))
                .principalBal(money(entity.getPrincipalBal(),entity.getCcyCd()))
                .accruedIntAmt(entity.getAccruedIntAmt())
                .intRt(entity.getIntRt())
                .interestType(parseEnum(InterestType.class, entity.getIntTyp()))
                .compoundFreq(entity.getCompoundFreq())
                .payoutFreq(entity.getPayoutFreq())
                .dayCountConv(entity.getDayCountConv())
                .tenureMonths(entity.getTenureMonths())
                .openDt(entity.getOpenDt())
                .valueDt(entity.getValueDt())
                .matDt(entity.getMatDt())
                .matAmt(money(entity.getMatAmt(),entity.getCcyCd()))
                .matInstruction(parseEnum(MaturityInstruction.class, entity.getMatInstruction()))
                .status(parseEnum(AccountStatus.class, entity.getSts()))
                .lastAccrualDt(entity.getLastAccrualDt())
                .lastCaptlzDt(entity.getLastCaptlzDt())
                .closureDt(entity.getClosureDt())
                .renewedFromId(entity.getRenewedFromId() != null ? entity.getRenewedFromId().toString() : null)
                .custNameSnap(entity.getCustNameSnap())
                .prdNameSnap(entity.getPrdNameSnap())
                .taxEnabled(Boolean.TRUE.equals(entity.getTaxEnabled()))
                .ccyDecimals(entity.getCcyDecimals())
                .build();
    }

    public static FdAccountSummary toSummary(FdAccount entity) {
        return new FdAccountSummary(entity.getFdaId().toString(), entity.getAcctNum(), entity.getSts(),
                money(entity.getPrincipalBal(),entity.getCcyCd()), entity.getMatDt(), entity.getPrdCode(), entity.getCustNameSnap(),
                entity.getCcyCd(),entity.getCcyDecimals());
    }

    public static com.banklab.fdservice.dto.FdTransaction toResponse(FdTransaction entity) {
        return new com.banklab.fdservice.dto.FdTransaction(
                entity.getFdtId(),
                entity.getFdaId().toString(),
                entity.getTxnGrpId().toString(),
                entity.getTxnTyp(),
                entity.getDrCr(),
                money(entity.getAmt(),entity.getCcyCd()),
                money(entity.getBalBefore(),entity.getCcyCd()),
                money(entity.getBalAfter(),entity.getCcyCd()),
                entity.getCcyCd(),
                entity.getTxnDt(),
                entity.getValueDt(),
                entity.getTxnTs(),
                entity.getTdsRt(),
                entity.getRemarks());
    }

    public static BatchRun toResponse(FdBatchRunLog entity) {
        return new BatchRun(
                entity.getFdjbId(),
                parseEnum(BatchJobName.class, entity.getJobName()),
                entity.getBusinessDt(),
                parseEnum(BatchRunStatus.class, entity.getSts()),
                entity.getReadCnt(),
                entity.getWriteCnt(),
                entity.getSkipCnt(),
                entity.getStartTs(),
                entity.getEndTs(),
                entity.getErrorMsg());
    }

    public static BusinessClockView toResponse(FdBusinessClock entity) {
        return new BusinessClockView(entity.getEntityCd(), entity.getBusinessDt(), entity.getPrevBusinessDt(),
                parseEnum(EodStatus.class, entity.getEodSts()), entity.getLastEodTs());
    }

    public static GlAccountView toResponse(FdGlAccount entity) {
        return new GlAccountView(entity.getFdglCd(), entity.getName(), entity.getTyp(), entity.getCcyCd(),
                money(entity.getCurrentBal(),entity.getCcyCd()), entity.getIsActive());
    }

    private static java.math.BigDecimal money(java.math.BigDecimal amount,String currency) {
        return amount==null?null:amount.setScale(CurrencyDecimals.forCurrency(currency),java.math.RoundingMode.UNNECESSARY);
    }

    private static <T extends Enum<T>> T parseEnum(Class<T> type, String value) {
        return value == null ? null : Enum.valueOf(type, value);
    }
}
