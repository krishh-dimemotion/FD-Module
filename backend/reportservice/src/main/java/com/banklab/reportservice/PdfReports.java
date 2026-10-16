package com.banklab.reportservice;

import java.io.*;
import java.util.*;
import org.apache.pdfbox.pdmodel.*;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.*;

/** Banded tables retain every field, repeating row identities and currencies across bands/pages. */
public final class PdfReports implements AutoCloseable {
    private final PDDocument document=new PDDocument();
    private final PDFont regular,bold;
    private PDPageContentStream out;
    private float y;
    private List<String> currentFields=List.of();
    private String section="";
    private final ReportResult report;
    private final Map<String,String> filters;
    private static final float M=36,W=PDRectangle.A4.getHeight(),H=PDRectangle.A4.getWidth();
    private PdfReports(ReportResult report,Map<String,String> filters) throws IOException {
        this.report=report;this.filters=filters;
        try(var r=PdfReports.class.getResourceAsStream("/fonts/Vera.ttf");var b=PdfReports.class.getResourceAsStream("/fonts/VeraBd.ttf")) {
            regular=PDType0Font.load(document,Objects.requireNonNull(r)); bold=PDType0Font.load(document,Objects.requireNonNull(b));
        }
    }
    public static byte[] render(ReportResult report,Map<String,String> filters) {
        try(var pdf=new PdfReports(report,filters);var bytes=new ByteArrayOutputStream()) {
            pdf.newPage();
            for(String warning:report.warnings()) pdf.paragraph("Warning: "+warning);
            if(report.reportType()==ReportType.TDS_COMPLIANCE) pdf.paragraph("DEMO withholding only. This document is not a statutory tax certificate.");
            pdf.tables("Report rows",report.rows());
            if(!report.totals().isEmpty()) pdf.tables("Totals by currency",report.totals());
            if(pdf.out!=null) { pdf.out.close();pdf.out=null; }
            int count=pdf.document.getNumberOfPages();
            for(int i=0;i<count;i++) try(var footer=new PDPageContentStream(pdf.document,pdf.document.getPage(i),PDPageContentStream.AppendMode.APPEND,true,true)) {
                footer.beginText();footer.setFont(pdf.regular,9);footer.newLineAtOffset(M,22);
                footer.showText("FD Lab | "+(i+1)+" / "+count+" | Exact amounts in original currency");footer.endText();
            }
            pdf.document.save(bytes);return bytes.toByteArray();
        } catch(IOException ex) { throw new ApiException(500,"PDF_GENERATION_FAILED","Unable to generate report PDF"); }
    }
    private void newPage() throws IOException {
        if(out!=null) out.close();
        PDPage page=new PDPage(new PDRectangle(W,H));document.addPage(page);out=new PDPageContentStream(document,page);
        y=H-M;
        String name=report.reportType()==ReportType.TDS_COMPLIANCE?"Demo tax summary":label(report.reportType().name().toLowerCase(Locale.ROOT).replace('_',' '));
        text(name,M,y,18,bold);y-=23;
        text("Business date: "+report.businessDate()+"   Generated: "+report.generatedAt(),M,y,9,regular);y-=15;
        text("Period: "+Objects.toString(report.fromDate(),"All opening dates")+" to "+report.toDate(),M,y,9,regular);y-=15;
        for(var f:filters.entrySet()) { for(String line:wrap(f.getKey()+": "+f.getValue(),W-2*M,9,regular)) { text(line,M,y,9,regular);y-=12; } }
        y-=12;
        if(!section.isEmpty()) { text(section,M,y,11,bold);y-=18; }
        if(!currentFields.isEmpty()) header();
    }
    private void tables(String name,List<Map<String,Object>> rows) throws IOException {
        if(rows.isEmpty()) { currentFields=List.of();section="";paragraph(name+": No matching records.");return; }
        LinkedHashSet<String> all=new LinkedHashSet<>();rows.forEach(r->all.addAll(r.keySet()));
        all.remove("currency");List<String> fields=new ArrayList<>(all);
        for(int start=0;start<fields.size();start+=4) {
            List<String> band=new ArrayList<>(List.of("#","currency"));band.addAll(fields.subList(start,Math.min(start+4,fields.size())));
            currentFields=List.of();section=name+" - columns "+(start+1)+" to "+Math.min(start+4,fields.size());
            if(y<180) newPage(); else { y-=12;text(section,M,y,11,bold);y-=18; }
            currentFields=band;header();
            for(int i=0;i<rows.size();i++) {
                Map<String,Object> row=rows.get(i);List<String> cells=new ArrayList<>();
                for(String f:band) {
                    Object value=f.equals("#")?i+1:row.get(f);
                    String c=Objects.toString(row.get("currency"),"");
                    cells.add(MoneyDisplay.FIELDS.contains(f)&&value!=null&&!c.isBlank()?MoneyDisplay.amount(value,c):Objects.toString(value,"-"));
                }
                row(cells,false);
            }
            currentFields=List.of();
        }
        section="";
    }
    private void header() throws IOException { row(currentFields.stream().map(PdfReports::label).toList(),true); }
    private float width(int i) { if(i==0) return 32; if(i==1) return 60; return (W-2*M-92)/(currentFields.size()-2); }
    private void row(List<String> cells,boolean heading) throws IOException {
        PDFont font=heading?bold:regular;
        List<List<String>> lines=new ArrayList<>();int max=1;
        for(int i=0;i<cells.size();i++) {var l=wrap(cells.get(i),width(i)-12,9,font);lines.add(l);max=Math.max(max,l.size());}
        // Very long free text is split over pages without dropping any characters.
        int offset=0;
        while(offset<max) {
            int chunk=Math.min(max-offset,30);float height=chunk*12+12;
            if(y-height<M+20 && !heading) newPage();
            if(heading) out.setNonStrokingColor(0.90f,0.93f,0.97f);else out.setNonStrokingColor(0.98f,0.98f,0.99f);
            out.addRect(M,y-height,W-2*M,height);out.fill();
            float x=M;
            for(int i=0;i<lines.size();i++) {
                List<String> l=lines.get(i);
                for(int j=offset;j<Math.min(l.size(),offset+chunk);j++) text(l.get(j),x+6,y-14-(j-offset)*12,9,font);
                x+=width(i);
            }
            y-=height;offset+=chunk;
        }
    }
    private void paragraph(String content) throws IOException {
        for(String line:wrap(content,W-2*M,10,regular)) {
            if(y<70) newPage();text(line,M,y,10,regular);y-=14;
        }
        y-=8;
    }
    private String supported(String value,PDFont font) {
        StringBuilder result=new StringBuilder();
        value.codePoints().forEach(cp->{
            if(cp=='\n' || cp=='\r' || cp=='\t') { result.append(' ');return; }
            String s=new String(Character.toChars(cp));
            try { font.encode(s);result.append(s); }
            catch(IOException|IllegalArgumentException ex) { result.append(String.format("[U+%04X]",cp)); }
        });return result.toString();
    }
    private List<String> wrap(String value,float width,float size,PDFont font) throws IOException {
        String clean=supported(value,font);List<String> result=new ArrayList<>();String line="";
        for(String word:clean.split(" +")) {
            String candidate=line.isEmpty()?word:line+" "+word;
            if(!line.isEmpty() && font.getStringWidth(candidate)/1000*size>width) {
                result.add(line);line="";
            }
            while(font.getStringWidth(word)/1000*size>width) {
                int split=1;
                while(split<word.length() && font.getStringWidth(word.substring(0,split+1))/1000*size<=width) split++;
                result.add(word.substring(0,split));word=word.substring(split);
            }
            line=line.isEmpty()?word:line+" "+word;
        }
        if(!line.isEmpty() || result.isEmpty()) result.add(line);
        return result;
    }

    private void text(String value,float x,float baseline,float size,PDFont font) throws IOException {
        out.setNonStrokingColor(0.10f,0.16f,0.23f);out.beginText();out.setFont(font,size);out.newLineAtOffset(x,baseline);
        out.showText(supported(value,font));out.endText();
    }
    static String label(String value) {String s=value.replaceAll("([a-z])([A-Z])","$1 $2");return s.isEmpty()?s:Character.toUpperCase(s.charAt(0))+s.substring(1);}
    @Override public void close() throws IOException { if(out!=null) out.close();document.close(); }
}
