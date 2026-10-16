package com.banklab.fdservice.entity;

import java.math.BigDecimal;
import java.time.LocalDate;
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
 * FD_GL_ENTRIES (FDGE) — one debit or credit leg of a double-entry posting. Every
 * business event writes at least one D and one C row sharing FDGE_TXN_GRP_ID (the
 * same group id as the FD_TRANSACTIONS row), so debits == credits per group.
 * FKs to FD_GL_ACCOUNTS and FD_TRANSACTIONS are enforced; see FdTransaction for why
 * the FK columns are mapped twice.
 */
@Getter
@Setter
@NoArgsConstructor
@SuperBuilder
@EqualsAndHashCode(callSuper = false, onlyExplicitlyIncluded = true)
@Entity
@Table(name = "FD_GL_ENTRIES")
public class FdGlEntry extends FdAuditableEntity {

    @EqualsAndHashCode.Include
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "FDGE_ID")
    private Long fdgeId;

    @Column(name = "FDGE_GL_CD", length = 64, nullable = false)
    private String glCd;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "FDGE_GL_CD", insertable = false, updatable = false,
            foreignKey = @ForeignKey(name = "FK_FDGE_FDGL"))
    private FdGlAccount glAccount;

    @Column(name = "FDGE_FDT_ID")
    private Long fdtId;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "FDGE_FDT_ID", insertable = false, updatable = false,
            foreignKey = @ForeignKey(name = "FK_FDGE_FDT"))
    private FdTransaction transaction;

    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "FDGE_TXN_GRP_ID", columnDefinition = "CHAR(36)", nullable = false)
    private UUID txnGrpId;

    @Column(name = "FDGE_DR_CR", length = 1, nullable = false)
    private String drCr;

    @Column(name = "FDGE_AMT", precision = 20, scale = 4, nullable = false)
    private BigDecimal amt;

    @Column(name = "FDGE_POST_DT", nullable = false)
    private LocalDate postDt;

    @Column(name = "FDGE_NARRATIVE", length = 255)
    private String narrative;

    @Column(name = "FDGE_EFCTV_DT")
    private LocalDate efctvDt;
}
