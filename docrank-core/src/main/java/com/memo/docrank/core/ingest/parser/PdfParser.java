package com.memo.docrank.core.ingest.parser;

import com.memo.docrank.core.ingest.DocumentParser;
import com.memo.docrank.core.ingest.ParsedDocument;
import lombok.extern.slf4j.Slf4j;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDDocumentInformation;
import org.apache.pdfbox.text.PDFTextStripper;

import java.io.InputStream;
import java.util.*;

@Slf4j
public class PdfParser implements DocumentParser {

    @Override
    public String supportedMimeType() { return "application/pdf"; }

    @Override
    public String supportedExtension() { return ".pdf"; }

    @Override
    public ParsedDocument parse(InputStream input, String filename, String docId) throws Exception {
        byte[] bytes = input.readAllBytes();
        try (PDDocument pdf = Loader.loadPDF(bytes)) {
            PDFTextStripper stripper = new PDFTextStripper();
            stripper.setSortByPosition(true);

            // 全文提取
            String fullText = stripper.getText(pdf);

            // 按页提取段落
            List<ParsedDocument.Section> sections = new ArrayList<>();
            int totalPages = pdf.getNumberOfPages();
            for (int page = 1; page <= totalPages; page++) {
                stripper.setStartPage(page);
                stripper.setEndPage(page);
                String pageText = stripper.getText(pdf).strip();
                if (!pageText.isBlank()) {
                    sections.add(ParsedDocument.Section.builder()
                            .heading("第 " + page + " 页")
                            .path("page/" + page)
                            .content(pageText)
                            .level(1)
                            .build());
                }
            }

            // 元数据
            PDDocumentInformation info = pdf.getDocumentInformation();
            Map<String, String> meta = new LinkedHashMap<>();
            if (info.getTitle()  != null) meta.put("title",  info.getTitle());
            if (info.getAuthor() != null) meta.put("author", info.getAuthor());
            if (info.getCreationDate() != null)
                meta.put("created", info.getCreationDate().getTime().toString());
            meta.put("pages", String.valueOf(totalPages));

            String title = (info.getTitle() != null && !info.getTitle().isBlank())
                    ? info.getTitle() : stripExtension(filename);

            log.debug("PDF 解析完成: docId={}, pages={}, chars={}", docId, totalPages, fullText.length());

            return ParsedDocument.builder()
                    .docId(docId)
                    .title(title)
                    .text(fullText)
                    .sections(sections)
                    .metadata(meta)
                    .mimeType(supportedMimeType())
                    .build();
        }
    }

    private String stripExtension(String filename) {
        int dot = filename.lastIndexOf('.');
        return dot > 0 ? filename.substring(0, dot) : filename;
    }
}
