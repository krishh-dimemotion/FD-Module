package com.banklab.reportservice;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.*;

/** Presentation only. JSON and CSV retain ungrouped decimal strings. */
public final class MoneyDisplay {
    private MoneyDisplay() { }
    public static final Set<String> FIELDS=Set.of("principal","depositBalance","accruedInterest","totalLiability",
        "projectedMaturityAmount","openingBalance","closingBalance","netGrowth","newDeposits","closurePayouts",
        "renewalIn","renewalOut","netDeduction","amount","balanceChange","runningBalance","taxableAmount");
    public static String amount(Object value,String currency) {
        if(value==null) return "-";
        int digits=Currency.getInstance(currency).getDefaultFractionDigits();
        String raw=new BigDecimal(value.toString()).setScale(digits,RoundingMode.HALF_UP).toPlainString();
        String sign=raw.startsWith("-")?"-":"";
        if(!sign.isEmpty()) raw=raw.substring(1);
        String[] parts=raw.split("\\.");
        String whole=parts[0];
        StringBuilder grouped=new StringBuilder();
        int n=0;
        for(int i=whole.length()-1;i>=0;i--,n++) {
            if(n>0 && (currency.equals("INR")?n==3 || n>3 && (n-3)%2==0:n%3==0)) grouped.insert(0,',');
            grouped.insert(0,whole.charAt(i));
        }
        return sign+grouped+(parts.length>1?"."+parts[1]:"");
    }
}
