package com.banklab.fdservice.entity;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.ForeignKey;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.experimental.SuperBuilder;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * FD_TRANSACTIONS (FDT) — the customer-facing money ledger. Rows are write-once:
 * corrections post a contra entry pointing at the original via FDT_REVERSAL_OF_ID.
 *
 * FDA_ID is an enforced FK (same database). It's mapped twice — the plain UUID
 * column is what code reads/writes (same style as FdAccountRole), the read-only
 * association exists so the schema carries the FOREIGN KEY constraint.
 */
@Getter
@Setter
@NoArgsConstructor
@SuperBuilder
@EqualsAndHashCode(callSuper = false, onlyExplicitlyIncluded = true)
@Entity
@Table(name = "FD_TRANSACTIONS")
public class FdTransaction extends FdAuditableEntity {

    @EqualsAndHashCode.Include
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "FDT_ID")
    private Long fdtId;

    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "FDA_ID", columnDefinition = "CHAR(36)", nullable = false)
    private UUID fdaId;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "FDA_ID", insertable = false, updatable = false,
            foreignKey = @ForeignKey(name = "FK_FDT_FDA"))
    private FdAccount account;

    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "FDT_TXN_GRP_ID", columnDefinition = "CHAR(36)", nullable = false)
    private UUID txnGrpId;

    @Column(name = "FDT_TXN_TYP", length = 20, nullable = false)
    private String txnTyp;

    @Column(name = "FDT_DR_CR", length = 1, nullable = false)
    private String drCr;

    @Column(name = "FDT_AMT", precision = 20, scale = 4, nullable = false)
    private BigDecimal amt;

    @Column(name = "FDT_BAL_BEFORE", precision = 20, scale = 4)
    private BigDecimal balBefore;

    @Column(name = "FDT_BAL_AFTER", precision = 20, scale = 4)
    private BigDecimal balAfter;

    @Column(name = "FDT_CCY_CD", length = 3)
    private String ccyCd;

    @Column(name = "FDT_TXN_DT", nullable = false)
    private LocalDate txnDt;

    @Column(name = "FDT_VALUE_DT")
    private LocalDate valueDt;

    @Column(name = "FDT_TXN_TS")
    private LocalDateTime txnTs;

    @Column(name = "FDT_TDS_RT", precision = 9, scale = 4)
    private BigDecimal tdsRt;

    @Column(name = "FDT_PAN_SNAP", length = 10)
    private String panSnap;

    @Column(name = "FDT_REMARKS", length = 255)
    private String remarks;

    @Column(name = "FDT_REVERSAL_OF_ID")
    private Long reversalOfId;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "FDT_REVERSAL_OF_ID", insertable = false, updatable = false,
            foreignKey = @ForeignKey(name = "FK_FDT_REVERSAL_OF"))
    private FdTransaction reversalOf;

    @Column(name = "FDT_EFCTV_DT")
    private LocalDate efctvDt;
}
