package com.banklab.fdservice.entity;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.experimental.SuperBuilder;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * FD_ACCOUNTS (FDA) — the deposit contract. One row per fixed deposit.
 * CUST_ID / FDA_PRD_CODE / FDA_RATE_ID / FDA_CATEGORY_CD / FDA_CCY_CD are logical
 * (cross-database) references only — no DB-level FOREIGN KEY.
 */
@Getter
@Setter
@NoArgsConstructor
@SuperBuilder
@EqualsAndHashCode(callSuper = false, onlyExplicitlyIncluded = true)
@Entity
@Table(name = "FD_ACCOUNTS")
public class FdAccount extends FdAuditableEntity {

    @EqualsAndHashCode.Include
    @Id
    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "FDA_ID", columnDefinition = "CHAR(36)")
    private UUID fdaId;

    @Column(name = "FDA_ACCT_NUM", length = 20, unique = true)
    private String acctNum;

    @Column(name = "CUST_ID", length = 32, nullable = false)
    private String custId;

    @Column(name = "FDA_PRD_CODE", length = 20, nullable = false)
    private String prdCode;

    @Column(name = "FDA_RATE_ID", length = 20)
    private String rateId;

    @Column(name = "FDA_CATEGORY_CD", length = 20)
    private String categoryCd;

    @Column(name = "FDA_CCY_CD", length = 3, nullable = false)
    private String ccyCd;

    @Column(name = "FDA_PRINCIPAL_AMT", precision = 20, scale = 4, nullable = false)
    private BigDecimal principalAmt;

    @Column(name = "FDA_PRINCIPAL_BAL", precision = 20, scale = 4)
    private BigDecimal principalBal;

    @Column(name = "FDA_ACCRUED_INT_AMT", precision = 20, scale = 4)
    private BigDecimal accruedIntAmt;

    @Column(name = "FDA_INT_RT", precision = 6, scale = 4)
    private BigDecimal intRt;

    @Column(name = "FDA_INT_TYP", length = 20)
    private String intTyp;

    @Column(name = "FDA_COMPOUND_FREQ", length = 20)
    private String compoundFreq;

    @Column(name = "FDA_PAYOUT_FREQ", length = 20)
    private String payoutFreq;

    @Column(name = "FDA_DAY_COUNT_CONV", length = 10)
    private String dayCountConv;

    @Column(name = "FDA_TAX_ENABLED")
    private Boolean taxEnabled;

    @Column(name = "FDA_TDS_RT", precision = 6, scale = 4)
    private BigDecimal tdsRt;

    @Column(name = "FDA_TENURE_MONTHS")
    private Integer tenureMonths;

    @Column(name = "FDA_OPEN_DT")
    private LocalDate openDt;

    @Column(name = "FDA_VALUE_DT")
    private LocalDate valueDt;

    @Column(name = "FDA_MAT_DT")
    private LocalDate matDt;

    @Column(name = "FDA_MAT_AMT", precision = 20, scale = 4)
    private BigDecimal matAmt;

    @Column(name = "FDA_MAT_INSTRUCTION", length = 30)
    private String matInstruction;

    @Column(name = "FDA_LAST_ACCRUAL_DT")
    private LocalDate lastAccrualDt;

    @Column(name = "FDA_LAST_CAPTLZ_DT")
    private LocalDate lastCaptlzDt;

    @Column(name = "FDA_STS", length = 20)
    private String sts;

    @Column(name = "FDA_CLOSURE_DT")
    private LocalDate closureDt;

    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "FDA_RENEWED_FROM_ID", columnDefinition = "CHAR(36)")
    private UUID renewedFromId;

    @Column(name = "FDA_CUST_NAME_SNAP", length = 255)
    private String custNameSnap;

    @Column(name = "FDA_PRD_NAME_SNAP", length = 100)
    private String prdNameSnap;

    @Column(name = "FDA_CCY_DECIMALS")
    private Integer ccyDecimals;

    @Column(name = "FDA_EFCTV_DT")
    private LocalDate efctvDt;
}
