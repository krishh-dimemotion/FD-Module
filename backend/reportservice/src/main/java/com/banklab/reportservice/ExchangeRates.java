package com.banklab.reportservice;

import java.io.IOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Service;

@Service
public class ExchangeRates {
    private final NavigableMap<LocalDate,Map<String,BigDecimal>> rates=new TreeMap<>();
    public ExchangeRates(@Value("${fd.fx.rates-location}") Resource resource) throws IOException {
        Properties properties=new Properties();
        try (var input=resource.getInputStream()) { properties.load(input); }
        for (String key:properties.stringPropertyNames()) {
            int dot=key.lastIndexOf('.');
            LocalDate date=LocalDate.parse(key.substring(0,dot)); String currency=currency(key.substring(dot+1));
            BigDecimal value=new BigDecimal(properties.getProperty(key));
            if (value.signum()<=0) throw new IllegalArgumentException("Demo rates must be positive: " + key);
            rates.computeIfAbsent(date,d->new TreeMap<>()).put(currency,value);
        }
        if (rates.isEmpty()) throw new IllegalArgumentException("No demo rates configured");
        Set<String> currencies=rates.firstEntry().getValue().keySet();
        for (var set:rates.values()) {
            if (!set.keySet().equals(currencies) || set.getOrDefault("INR",BigDecimal.ZERO).compareTo(BigDecimal.ONE)!=0)
                throw new IllegalArgumentException("Every rate set must contain the same currencies and INR=1");
        }
    }
    public Map<String,Object> lookup(String base,LocalDate date) {
        base=currency(base); var set=applicable(date); BigDecimal denominator=required(set.getValue(),base);
        Map<String,String> cross=new TreeMap<>();
        for (var rate:set.getValue().entrySet()) cross.put(rate.getKey(),rate.getValue().divide(denominator,12,RoundingMode.HALF_UP).toPlainString());
        return ReportService.row("source","DEMO","base",base,"requestedDate",date,"effectiveDate",set.getKey(),"rates",cross);
    }
    public Map<String,Object> convert(String from,String to,BigDecimal amount,LocalDate date) {
        from=currency(from); to=currency(to);
        if (amount.signum()<0 || amount.precision()>30 || Math.abs((long)amount.scale())>12)
            throw ApiException.invalid("amount must be nonnegative, with at most 30 significant digits and 12 decimal places");
        var set=applicable(date); BigDecimal numerator=required(set.getValue(),to), denominator=required(set.getValue(),from);
        int decimals=Currency.getInstance(to).getDefaultFractionDigits();
        return ReportService.row("source","DEMO","from",from,"to",to,"amount",amount.toPlainString(),
            "requestedDate",date,"effectiveDate",set.getKey(),"rate",numerator.divide(denominator,12,RoundingMode.HALF_UP).toPlainString(),
            "convertedAmount",amount.multiply(numerator).divide(denominator,decimals,RoundingMode.HALF_UP).toPlainString());
    }
    private Map.Entry<LocalDate,Map<String,BigDecimal>> applicable(LocalDate date) {
        var entry=rates.floorEntry(date);
        if (entry==null) throw new ApiException(404,"RATE_NOT_FOUND","No demo rate set on or before " + date);
        return entry;
    }
    private static BigDecimal required(Map<String,BigDecimal> set,String currency) {
        var value=set.get(currency);
        if (value==null) throw new ApiException(404,"CURRENCY_NOT_SUPPORTED","No demo rate for " + currency);
        return value;
    }
    private static String currency(String currency) {
        String code=currency.trim().toUpperCase(Locale.ROOT);
        if (!code.matches("[A-Z]{3}")) throw ApiException.invalid("Currency must be a three-letter ISO code");
        try { if (Currency.getInstance(code).getDefaultFractionDigits()<0) throw new IllegalArgumentException(); }
        catch (IllegalArgumentException e) { throw ApiException.invalid("Invalid currency: " + code); }
        return code;
    }
}
