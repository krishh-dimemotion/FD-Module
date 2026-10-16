package com.banklab.fdservice.entity;

import java.math.BigDecimal;
import java.time.LocalDate;
import jakarta.persistence.*;
import lombok.*;

/** One assessment per gross INTEREST row, including zero-tax allowance/exemption events. */
@Getter @Setter @NoArgsConstructor @Entity @Table(name="FD_TAX_EVENTS")
public class FdTaxEvent {
    @Id @Column(name="SOURCE_FDT_ID") private Long source;
    @Column(name="TAX_FDT_ID") private Long transaction;
    @Column(name="TAX_KEY",length=128,nullable=false) private String key;
    @Column(name="POLICY_VERSION",length=64,nullable=false) private String version;
    @Column(name="VALUE_DATE",nullable=false) private LocalDate date;
    @Column(name="TAXABLE_AMOUNT",precision=20,scale=4,nullable=false) private BigDecimal taxable;
    @Column(name="TAX_RATE",precision=9,scale=4,nullable=false) private BigDecimal rate;
    @Column(name="DEDUCTION",precision=20,scale=4,nullable=false) private BigDecimal deduction;
}
