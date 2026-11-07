package com.techhouse.procurement.application;

import com.techhouse.procurement.domain.PurchaseInvoiceFile;
import com.techhouse.procurement.persistence.PurchaseInvoiceFileRepository;
import com.techhouse.procurement.persistence.PurchaseInvoiceRepository;
import com.techhouse.shared.exception.BusinessException;
import com.techhouse.shared.exception.ResourceNotFoundException;
import jakarta.persistence.EntityManager;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

/** Keeps the original supplier invoice (a PDF) with the structured invoice, for reference. */
@Service
@RequiredArgsConstructor
public class PurchaseInvoiceFileService {

    static final int MAX_BYTES = 10 * 1024 * 1024;
    private static final byte[] PDF_MAGIC = "%PDF-".getBytes(StandardCharsets.US_ASCII);

    private final PurchaseInvoiceRepository invoiceRepository;
    private final PurchaseInvoiceFileRepository fileRepository;
    private final EntityManager em;

    @Transactional
    public void upload(UUID storeId, UUID invoiceId, MultipartFile file) {
        requireInvoice(storeId, invoiceId);
        if (file == null || file.isEmpty()) {
            throw new BusinessException("FILE_EMPTY", "The file is empty");
        }
        if (file.getSize() > MAX_BYTES) {
            throw new BusinessException(HttpStatus.CONTENT_TOO_LARGE, "FILE_TOO_LARGE", "File is larger than 10 MB");
        }
        byte[] data;
        try {
            data = file.getBytes();
        } catch (IOException e) {
            throw new BusinessException("FILE_EMPTY", "The file could not be read", e);
        }
        if (!isPdf(data)) {
            throw new BusinessException(HttpStatus.UNSUPPORTED_MEDIA_TYPE, "FILE_NOT_PDF", "Only PDF files are accepted");
        }
        fileRepository.deleteById(invoiceId);
        em.flush();
        PurchaseInvoiceFile entity = new PurchaseInvoiceFile();
        entity.setInvoiceId(invoiceId);
        entity.setFileName(safeName(file.getOriginalFilename()));
        entity.setContentType("application/pdf");
        entity.setSizeBytes(data.length);
        entity.setData(data);
        em.persist(entity);
    }

    @Transactional(readOnly = true)
    public PurchaseInvoiceFile get(UUID storeId, UUID invoiceId) {
        requireInvoice(storeId, invoiceId);
        return fileRepository.findById(invoiceId)
                .orElseThrow(() -> new ResourceNotFoundException("ATTACHMENT_NOT_FOUND", "Invoice attachment", invoiceId));
    }

    @Transactional
    public void delete(UUID storeId, UUID invoiceId) {
        requireInvoice(storeId, invoiceId);
        fileRepository.deleteById(invoiceId);
    }

    private void requireInvoice(UUID storeId, UUID invoiceId) {
        if (invoiceRepository.findByIdAndStoreId(invoiceId, storeId).isEmpty()) {
            throw new ResourceNotFoundException("INVOICE_NOT_FOUND", "Purchase invoice", invoiceId);
        }
    }

    static boolean isPdf(byte[] b) {
        if (b.length < PDF_MAGIC.length) {
            return false;
        }
        for (int i = 0; i < PDF_MAGIC.length; i++) {
            if (b[i] != PDF_MAGIC[i]) {
                return false;
            }
        }
        return true;
    }

    private static String safeName(String name) {
        if (name == null || name.isBlank()) {
            return "invoice.pdf";
        }
        String base = name.replaceAll("[\\\\/\\r\\n\"]", "_").trim();
        return base.length() > 200 ? base.substring(base.length() - 200) : base;
    }
}
