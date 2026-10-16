package com.banklab.fdservice.entity;

import java.math.BigDecimal;
import jakarta.persistence.*;
import lombok.*;

/** Immutable policy snapshot and serialized allowance consumption for customer/currency/FY. */
@Getter @Setter @NoArgsConstructor @Entity @Table(name="FD_TAX_YEARS")
public class FdTaxYear {
    @Id @Column(name="TAX_KEY",length=128) private String key;
    @Column(name="CUSTOMER_ID",length=32,nullable=false) private String customer;
    @Column(name="CURRENCY",length=3,nullable=false) private String currency;
    @Column(name="FY_START",nullable=false) private int year;
    @Column(name="START_MONTH",nullable=false) private int startMonth;
    @Column(name="POLICY_VERSION",length=64,nullable=false) private String version;
    @Column(name="TAX_RATE",precision=9,scale=4,nullable=false) private BigDecimal rate;
    @Column(name="ALLOWANCE",precision=20,scale=4,nullable=false) private BigDecimal allowance;
    @Column(name="EXEMPT",nullable=false) private boolean exempt;
    @Column(name="GROSS_INTEREST",precision=20,scale=4,nullable=false) private BigDecimal gross;
    @Column(name="TAX_WITHHELD",precision=20,scale=4,nullable=false) private BigDecimal tax;
}
