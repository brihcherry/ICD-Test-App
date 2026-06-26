package reactors.ICD_Reactors;

import java.io.ByteArrayInputStream;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
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
import prerna.util.AssetUtility;
import prerna.util.Utility;
import prerna.sablecc2.om.PixelDataType;
import prerna.sablecc2.om.nounmeta.NounMetadata;
import reactors.AbstractProjectReactor;

/*
 * GetDataDictionaryReactor reads a .docx data dictionary file and extracts its full
 * text content (paragraphs and tables, in document order) with minimal formatting
 * changes. The extracted text is returned to the caller so it can be sent to
 * SendDictionaryToModel immediately.
 *
 * Inputs (optional):
 * - fileName: original file name (must end with .docx)
 * - fileContentBase64: full .docx payload encoded as base64
 *
 * If both inputs are omitted, auto-loads Data Subject Area Definitions from assets.
 *
 * Response payload:
 * - fileName: the file name
 * - characterCount: length of extracted text
 * - dictionaryText: full extracted text content
 * - source: "upload" or "assets"
 *
 */
public class GetDataDictionaryReactor extends AbstractProjectReactor {

    private static final String FILE_NAME_KEY = "fileName";
    private static final String FILE_CONTENT_BASE64_KEY = "fileContentBase64";
    private static final String VARSTORE_DICTIONARY_CACHE = "ICD_DICTIONARY_CACHE";
    private static final String DSA_RELATIVE_PATH = "/java/src/files/Data Subject Area Definitions.docx";

    public GetDataDictionaryReactor() {
        this.keysToGet = new String[] {FILE_NAME_KEY, FILE_CONTENT_BASE64_KEY};
        this.keyRequired = new int[] {0, 0};  // Both optional now
    }

    @Override
    protected NounMetadata doExecute() {
        String fileName = this.keyValue.get(FILE_NAME_KEY);
        String fileContentBase64 = this.keyValue.get(FILE_CONTENT_BASE64_KEY);

        String dictionaryText;
        String source;

        // If upload inputs are provided, use those
        if (fileName != null && fileContentBase64 != null) {
            if (!fileName.toLowerCase().endsWith(".docx")) {
                return NounMetadata.getErrorNounMessage("GetDataDictionary currently supports only .docx files.");
            }

            byte[] fileBytes;
            try {
                fileBytes = Base64.getDecoder().decode(fileContentBase64);
            } catch (IllegalArgumentException e) {
                return NounMetadata.getErrorNounMessage("Invalid base64 payload for fileContentBase64.");
            }

            try {
                dictionaryText = extractText(fileBytes);
            } catch (IOException e) {
                return NounMetadata.getErrorNounMessage("Unable to parse uploaded .docx file content.");
            }
            source = "upload";
        } else {
            // No upload inputs provided, load from assets
            try {
                dictionaryText = extractTextFromAssets();
            } catch (IOException e) {
                return NounMetadata.getErrorNounMessage(
                    "Unable to load Data Subject Area Definitions from assets: " + e.getMessage());
            }
            fileName = "Data Subject Area Definitions.docx";
            source = "assets";
        }

        if (dictionaryText == null || dictionaryText.isBlank()) {
            return NounMetadata.getErrorNounMessage("Dictionary text is empty.");
        }

        // Cache the dictionary
        Map<String, Object> dictionaryCache = new LinkedHashMap<>();
        dictionaryCache.put("fileName", fileName);
        dictionaryCache.put("dictionaryText", dictionaryText);
        dictionaryCache.put("source", source);
        dictionaryCache.put("createdAtEpochMs", System.currentTimeMillis());
        this.insight
            .getVarStore()
            .put(VARSTORE_DICTIONARY_CACHE, new NounMetadata(dictionaryCache, PixelDataType.MAP));

        // Return response
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("fileName", fileName);
        response.put("characterCount", dictionaryText.length());
        response.put("dictionaryText", dictionaryText);
        response.put("source", source);

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

    /*
     * Extracts text from the DSA .docx file located in the project's assets folder.
     * Uses AssetUtility to resolve the absolute path at runtime.
     */
    private String extractTextFromAssets() throws IOException {
        String assetsFolder = AssetUtility.getProjectAssetsFolder(this.projectId);
        String filePath = Utility.normalizePath(assetsFolder + DSA_RELATIVE_PATH);

        try (InputStream inputStream = new FileInputStream(filePath)) {
            return extractText(inputStream);
        }
    }

    /*
     * Extracts text from an InputStream pointing to a .docx document.
     */
    private String extractText(InputStream inputStream) throws IOException {
        StringBuilder sb = new StringBuilder();

        try (XWPFDocument document = new XWPFDocument(inputStream)) {
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
        return "Extract full text from a .docx data dictionary (upload or auto-loaded from assets) and return it for immediate model priming.";
    }

    @Override
    public String getDescriptionForKey(String key) {
        if (FILE_NAME_KEY.equals(key)) {
            return "The data dictionary file name (optional). Must end with .docx. If omitted, loads from assets.";
        }
        if (FILE_CONTENT_BASE64_KEY.equals(key)) {
            return "The full .docx file payload encoded as base64 (optional). If omitted, loads from assets.";
        }
        return null;
    }
}

