package com.banklab.fdservice.entity;

import java.math.BigDecimal;
import java.time.LocalDate;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.experimental.SuperBuilder;

/**
 * FD_GL_ACCOUNTS (FDGL) — registry of the bank's internal GL ledger codes used for
 * double-entry bookkeeping (one row per GL account; postings live in FD_GL_ENTRIES).
 */
@Getter
@Setter
@NoArgsConstructor
@SuperBuilder
@EqualsAndHashCode(callSuper = false, onlyExplicitlyIncluded = true)
@Entity
@Table(name = "FD_GL_ACCOUNTS")
public class FdGlAccount extends FdAuditableEntity {

    @EqualsAndHashCode.Include
    @Id
    @Column(name = "FDGL_CD", length = 64)
    private String fdglCd;

    @Column(name = "FDGL_NAME", length = 100)
    private String name;

    @Column(name = "FDGL_TYP", length = 20)
    private String typ;

    @Column(name = "FDGL_CCY_CD", length = 3)
    private String ccyCd;

    @Column(name = "FDGL_CURRENT_BAL", precision = 20, scale = 4)
    private BigDecimal currentBal;

    @Column(name = "FDGL_IS_ACTIVE")
    private Boolean isActive;

    @Column(name = "FDGL_EFCTV_DT")
    private LocalDate efctvDt;
}
