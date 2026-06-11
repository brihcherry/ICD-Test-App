package reactors.ICD_Reactors;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.apache.poi.xwpf.usermodel.IBodyElement;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFParagraph;
import org.apache.poi.xwpf.usermodel.XWPFTable;
import org.apache.poi.xwpf.usermodel.XWPFTableCell;
import org.apache.poi.xwpf.usermodel.XWPFTableRow;
import prerna.sablecc2.om.PixelDataType;
import prerna.sablecc2.om.nounmeta.NounMetadata;
import reactors.AbstractProjectReactor;

/*
 * GetDataDictionaryReactor reads a .docx data dictionary file and extracts its full
 * text content (paragraphs and tables, in document order) with minimal formatting
 * changes. The extracted text is returned to the caller so it can be sent to
 * SendDictionaryToModel immediately.
 *
 * Inputs:
 * - fileName: original file name (must end with .docx)
 * - fileContentBase64: full .docx payload encoded as base64
 *
 * Response payload:
 * - fileName: the uploaded file name
 * - characterCount: length of extracted text
 * - dictionaryText: full extracted text content
 *
 */
public class GetDataDictionaryReactor extends AbstractProjectReactor {

    private static final String FILE_NAME_KEY = "fileName";
    private static final String FILE_CONTENT_BASE64_KEY = "fileContentBase64";
    private static final String VARSTORE_DICTIONARY_CACHE = "ICD_DICTIONARY_CACHE";

    public GetDataDictionaryReactor() {
        this.keysToGet = new String[] {FILE_NAME_KEY, FILE_CONTENT_BASE64_KEY};
        this.keyRequired = new int[] {1, 1};
    }

    @Override
    protected NounMetadata doExecute() {
        String fileName = this.keyValue.get(FILE_NAME_KEY);
        String fileContentBase64 = this.keyValue.get(FILE_CONTENT_BASE64_KEY);

        if (fileName == null || !fileName.toLowerCase().endsWith(".docx")) {
            return NounMetadata.getErrorNounMessage("GetDataDictionary currently supports only .docx files.");
        }

        byte[] fileBytes;
        try {
            fileBytes = Base64.getDecoder().decode(fileContentBase64);
        } catch (IllegalArgumentException e) {
            return NounMetadata.getErrorNounMessage("Invalid base64 payload for fileContentBase64.");
        }

        String dictionaryText;
        try {
            dictionaryText = extractText(fileBytes);
        } catch (IOException e) {
            return NounMetadata.getErrorNounMessage("Unable to parse .docx file content.");
        }

        Map<String, Object> dictionaryCache = new LinkedHashMap<>();
        dictionaryCache.put("fileName", fileName);
        dictionaryCache.put("dictionaryText", dictionaryText);
        dictionaryCache.put("createdAtEpochMs", System.currentTimeMillis());
        this.insight
            .getVarStore()
            .put(VARSTORE_DICTIONARY_CACHE, new NounMetadata(dictionaryCache, PixelDataType.MAP));

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("fileName", fileName);
        response.put("characterCount", dictionaryText.length());
        response.put("dictionaryText", dictionaryText);

        return new NounMetadata(response, PixelDataType.MAP);
    }

    /*
     * Extracts all text from a .docx file in document order: paragraphs are
     * preserved as lines, table cells are separated by " | " and rows by newlines.
     */
    private String extractText(byte[] fileBytes) throws IOException {
        StringBuilder sb = new StringBuilder();

        try (XWPFDocument document = new XWPFDocument(new ByteArrayInputStream(fileBytes))) {
            List<IBodyElement> bodyElements = document.getBodyElements();

            for (IBodyElement element : bodyElements) {
                if (element instanceof XWPFParagraph paragraph) {
                    String text = paragraph.getText();
                    if (text != null && !text.isBlank()) {
                        sb.append(text.trim()).append("\n");
                    }
                } else if (element instanceof XWPFTable table) {
                    if (table.getRows() == null) {
                        continue;
                    }
                    for (XWPFTableRow row : table.getRows()) {
                        if (row == null || row.getTableCells() == null) {
                            continue;
                        }
                        StringBuilder rowSb = new StringBuilder();
                        for (XWPFTableCell cell : row.getTableCells()) {
                            if (rowSb.length() > 0) {
                                rowSb.append(" | ");
                            }
                            String cellText = cell == null ? "" : cell.getText();
                            rowSb.append(cellText == null ? "" : cellText.trim());
                        }
                        String rowText = rowSb.toString();
                        if (!rowText.isBlank()) {
                            sb.append(rowText).append("\n");
                        }
                    }
                    sb.append("\n");
                }
            }
        }

        return sb.toString().trim();
    }

    @Override
    public String getReactorDescription() {
        return "Extract full text from a .docx data dictionary and return it for immediate model priming.";
    }

    @Override
    public String getDescriptionForKey(String key) {
        if (FILE_NAME_KEY.equals(key)) {
            return "The data dictionary file name. Must end with .docx.";
        }
        if (FILE_CONTENT_BASE64_KEY.equals(key)) {
            return "The full .docx file payload encoded as base64.";
        }
        return null;
    }
}

