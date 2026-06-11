package reactors.ICD_Reactors;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
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
 * GetTablesReactor reads a user-uploaded .docx file, caches all table contents
 * and metadata in the SEMOSS insight var-store, and returns a filtered list of
 * likely data element tables.
 *
 * Inputs:
 * - fileName: original document name (must end with .docx)
 * - fileContentBase64: full .docx payload encoded as base64
 *
 * Table filter:
 * - A table is added to the tableCandidates list if it is preceeded by text beginning
 *   with "Table A-" followed by a number (eg: "Table A-1", "Table A-2").
 * - Matching uses only the immediate preceding non-empty paragraph.
 * - All tables are still cached and returned in allTableCandidates as a fallback option.
 *
 * Response payload:
 * - documentName
 * - tableCount: number of Table A-# candidates
 * - tableCandidates[]: Table A-# candidates
 * - allTableCount: number of all document tables
 * - allTableCandidates[]: all document tables
 *
 * Cached payload (single per insight):
 * - all tables metadata
 * - headerRow
 * - rows (normalized to the widest row in each table)
 */
public class GetTablesReactor extends AbstractProjectReactor {

    private static final String FILE_NAME_KEY = "fileName";
    private static final String FILE_CONTENT_BASE64_KEY = "fileContentBase64";
    private static final String VARSTORE_TABLE_PARSE_CACHE = "ICD_TABLE_PARSE_CACHE";
    private static final int MAX_PREVIEW_CELLS = 8;
    private static final Pattern TABLE_A_HEADING_PATTERN =
        Pattern.compile(
            "^[^a-z0-9]*table\\s+a(?:\\s*[-\\u2010-\\u2015\\u2212]\\s*|\\s+)?\\d+\\b",
            Pattern.CASE_INSENSITIVE);

    public GetTablesReactor() {
        this.keysToGet = new String[] {FILE_NAME_KEY, FILE_CONTENT_BASE64_KEY};
        this.keyRequired = new int[] {1, 1};
    }

    @Override
    protected NounMetadata doExecute() {
        // get the name of the file and verify that it's a valid .docx, then decode
        String fileName = this.keyValue.get(FILE_NAME_KEY);
        String fileContentBase64 = this.keyValue.get(FILE_CONTENT_BASE64_KEY);

        if (fileName == null || !fileName.toLowerCase().endsWith(".docx")) {
            return NounMetadata.getErrorNounMessage("GetTables currently supports only .docx files.");
        }

        byte[] fileBytes;
        try {
            fileBytes = Base64.getDecoder().decode(fileContentBase64);
        } catch (IllegalArgumentException e) {
            return NounMetadata.getErrorNounMessage("Invalid base64 payload for fileContentBase64.");
        }

        // try passing the file to the parsing helper function
        ParseResult parseResult;
        try {
            parseResult = parseTables(fileBytes);
        } catch (IOException e) {
            return NounMetadata.getErrorNounMessage("Unable to parse .docx file content.");
        }

        // if the parse is successful, cache the full table contents and construct the reactor response payload,
        // which contains table metadata sorted by tableCandidates and allTableCandidates
        putParsedTablesInVarStore(fileName, parseResult.parsedTables);

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("documentName", fileName);
        response.put("tableCount", parseResult.tableCandidates.size());
        response.put("tableCandidates", parseResult.tableCandidates);
        response.put("allTableCount", parseResult.allTableCandidates.size());
        response.put("allTableCandidates", parseResult.allTableCandidates);

        return new NounMetadata(response, PixelDataType.MAP);
    }

    /*
     * Helper method that parses the .docx file and returns three lists:
     * - filteredTableCandidates: tables that are preceeded by a "Table A-#" heading 
    *      using the immediate preceding non-empty paragraph only
     * - allTableCandidates: all tables in the document with metadata, used as 
     *      a fallback in case the heading-based filter misses the correct table
     * - parsedTables: full table contents and metadata for all tables, which is 
    *      cached in the var-store for downstream reactors
     * 
     *  */
    private ParseResult parseTables(byte[] fileBytes) throws IOException {
        List<Map<String, Object>> filteredTableCandidates = new ArrayList<>();
        List<Map<String, Object>> allTableCandidates = new ArrayList<>();
        List<Map<String, Object>> parsedTables = new ArrayList<>();

        try (XWPFDocument document = new XWPFDocument(new ByteArrayInputStream(fileBytes))) {
            // for each paragraph element, store it in case it preceeds a table, in which 
            // case we will keep it as the table header
            List<IBodyElement> bodyElements = document.getBodyElements();

            String latestHeadingText = "";
            int tableCount = 0;
            // iterate through each paragraph element and check whether it's a heading
            for (IBodyElement bodyElement : bodyElements) {
                if (bodyElement instanceof XWPFParagraph paragraph) {
                    String paragraphText = safeTrim(paragraph.getText());
                    if (!paragraphText.isEmpty()) {
                        latestHeadingText = paragraphText;
                    }
                    continue;
                }

                // if the next item isn't a table, ignore it and overwrite the previous header
                // candidate on the next pass
                if (!(bodyElement instanceof XWPFTable table)) {
                    continue;
                }

                tableCount += 1;
                
                // if it's a table, store the metadata (rows, columns, preview) in the return payload
                int rowCount = table.getRows() == null ? 0 : table.getRows().size();
                int columnCount = getMaxColumnCount(table);
                List<String> firstRowPreview = getFirstRowPreview(table);
                String headingBeforeTable = latestHeadingText;
                String matchedTableAHeading = isTableAHeading(headingBeforeTable) ? headingBeforeTable : null;

                String displayLabel =
                    matchedTableAHeading != null
                        ? matchedTableAHeading
                        : (headingBeforeTable.isEmpty() ? "Table " + tableCount : headingBeforeTable);

                Map<String, Object> candidate = new LinkedHashMap<>();
                candidate.put("index", tableCount);
                candidate.put("displayLabel", displayLabel);
                candidate.put("firstRowPreview", firstRowPreview);
                candidate.put("rowCount", rowCount);
                candidate.put("columnCount", columnCount);
                allTableCandidates.add(candidate);
                if (matchedTableAHeading != null) {
                    filteredTableCandidates.add(candidate);
                }

                // store the full table contents into the cached payload
                Map<String, Object> fullTable = new LinkedHashMap<>();
                fullTable.put("index", tableCount);
                fullTable.put("displayLabel", displayLabel);
                fullTable.put("firstRowPreview", firstRowPreview);
                fullTable.put("rowCount", rowCount);
                fullTable.put("columnCount", columnCount);

                List<List<String>> rows = getRows(table, columnCount);
                fullTable.put("rows", rows);
                fullTable.put("headerRow", rows.isEmpty() ? new ArrayList<String>() : rows.get(0));

                parsedTables.add(fullTable);
            }
        }

        return new ParseResult(filteredTableCandidates, allTableCandidates, parsedTables);
    }

    /* 
     * Helper method that stores table contents in the SEMOSS var-store as
     * a single cache entry for the current insight.
     * 
     *
     *  */
    private void putParsedTablesInVarStore(
            String documentName, List<Map<String, Object>> parsedTables) {
        long now = System.currentTimeMillis();

        Map<String, Object> cacheEntry = new LinkedHashMap<>();
        cacheEntry.put("documentName", documentName);
        cacheEntry.put("createdAtEpochMs", now);
        cacheEntry.put("tables", parsedTables);

        this.insight
                .getVarStore()
                .put(VARSTORE_TABLE_PARSE_CACHE, new NounMetadata(cacheEntry, PixelDataType.MAP));
    }

    private List<List<String>> getRows(XWPFTable table, int columnCount) {
        List<List<String>> rows = new ArrayList<>();
        if (table.getRows() == null) {
            return rows;
        }

        for (XWPFTableRow row : table.getRows()) {
            List<String> normalizedRow = new ArrayList<>();
            List<XWPFTableCell> cells = row == null ? null : row.getTableCells();

            int rowCellCount = cells == null ? 0 : cells.size();
            int maxCells = Math.max(columnCount, rowCellCount);
            for (int i = 0; i < maxCells; i++) {
                if (cells != null && i < cells.size()) {
                    normalizedRow.add(safeTrim(cells.get(i) == null ? "" : cells.get(i).getText()));
                } else {
                    normalizedRow.add("");
                }
            }

            rows.add(normalizedRow);
        }

        return rows;
    }

    private int getMaxColumnCount(XWPFTable table) {
        int maxColumns = 0;
        if (table.getRows() == null) {
            return maxColumns;
        }

        for (XWPFTableRow row : table.getRows()) {
            if (row == null || row.getTableCells() == null) {
                continue;
            }
            maxColumns = Math.max(maxColumns, row.getTableCells().size());
        }

        return maxColumns;
    }

    private List<String> getFirstRowPreview(XWPFTable table) {
        List<String> preview = new ArrayList<>();
        if (table.getRows() == null || table.getRows().isEmpty()) {
            return preview;
        }

        XWPFTableRow firstRow = table.getRows().get(0);
        if (firstRow == null || firstRow.getTableCells() == null) {
            return preview;
        }

        int limit = Math.min(MAX_PREVIEW_CELLS, firstRow.getTableCells().size());
        for (int i = 0; i < limit; i++) {
            XWPFTableCell cell = firstRow.getTableCells().get(i);
            preview.add(safeTrim(cell == null ? "" : cell.getText()));
        }

        return preview;
    }

    private String safeTrim(String value) {
        return value == null ? "" : value.trim().replaceAll("\\s+", " ");
    }

    private boolean isTableAHeading(String headingBeforeTable) {
        if (headingBeforeTable == null || headingBeforeTable.isEmpty()) {
            return false;
        }
        return TABLE_A_HEADING_PATTERN.matcher(headingBeforeTable).find();
    }

    @Override
    public String getReactorDescription() {
        return "Read a .docx file, cache parsed table data, and return table candidates.";
    }

    @Override
    public String getDescriptionForKey(String key) {
        if (FILE_NAME_KEY.equals(key)) {
            return "The source file name. Must end with .docx.";
        }
        if (FILE_CONTENT_BASE64_KEY.equals(key)) {
            return "The full .docx file payload encoded as base64.";
        }
        return null;
    }

    private static class ParseResult {
        private final List<Map<String, Object>> tableCandidates;
        private final List<Map<String, Object>> allTableCandidates;
        private final List<Map<String, Object>> parsedTables;

        private ParseResult(
                List<Map<String, Object>> tableCandidates,
                List<Map<String, Object>> allTableCandidates,
                List<Map<String, Object>> parsedTables) {
            this.tableCandidates = tableCandidates;
            this.allTableCandidates = allTableCandidates;
            this.parsedTables = parsedTables;
        }
    }

}
