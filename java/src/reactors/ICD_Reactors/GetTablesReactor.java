package reactors.ICD_Reactors;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFTable;
import org.apache.poi.xwpf.usermodel.XWPFTableCell;
import org.apache.poi.xwpf.usermodel.XWPFTableRow;
import prerna.sablecc2.om.PixelDataType;
import prerna.sablecc2.om.nounmeta.NounMetadata;
import reactors.AbstractProjectReactor;

/*
 * GetTablesReactor reads a user-uploaded .docx file, caches all table contents
 * and metadata in the SEMOSS insight var-store, and returns all document tables.
 *
 * Inputs:
 * - fileName: original document name (must end with .docx)
 * - fileContentBase64: full .docx payload encoded as base64
 *
 * Table handling:
 * - All tables are returned in tableCandidates.
 * - Table labels are deterministic: "Table 1", "Table 2", etc.
 *
 * Response payload:
 * - documentName
 * - tableCount: number of all document tables
 * - tableCandidates[]: all document tables
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

        // if the parse is successful, cache the full table contents and construct the reactor response payload
        putParsedTablesInVarStore(fileName, parseResult.parsedTables);

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("documentName", fileName);
        response.put("tableCount", parseResult.tableCandidates.size());
        response.put("tableCandidates", parseResult.tableCandidates);

        return new NounMetadata(response, PixelDataType.MAP);
    }

    /*
     * Helper method that parses the .docx file and returns two lists:
     * - tableCandidates: all tables in the document with lightweight metadata and preview rows
     * - parsedTables: full table contents and metadata for cache-backed downstream reactors
     */
    private ParseResult parseTables(byte[] fileBytes) throws IOException {
        List<Map<String, Object>> tableCandidates = new ArrayList<>();
        List<Map<String, Object>> parsedTables = new ArrayList<>();

        try (XWPFDocument document = new XWPFDocument(new ByteArrayInputStream(fileBytes))) {
            List<XWPFTable> tables = document.getTables();
            int tableCount = 0;
            for (XWPFTable table : tables) {

                tableCount += 1;

                // store lightweight metadata and preview in the return payload
                int rowCount = table.getRows() == null ? 0 : table.getRows().size();
                int columnCount = getMaxColumnCount(table);
                List<String> firstRowPreview = getFirstRowPreview(table);
                String displayLabel = "Table " + tableCount;
                List<List<String>> allRows = getRows(table, columnCount);
                List<List<String>> previewRows = allRows.subList(0, Math.min(3, allRows.size()));

                Map<String, Object> candidate = new LinkedHashMap<>();
                candidate.put("index", tableCount);
                candidate.put("displayLabel", displayLabel);
                candidate.put("firstRowPreview", firstRowPreview);
                candidate.put("previewRows", previewRows);
                candidate.put("rowCount", rowCount);
                candidate.put("columnCount", columnCount);
                tableCandidates.add(candidate);

                // store the full table contents into the cached payload
                Map<String, Object> fullTable = new LinkedHashMap<>();
                fullTable.put("index", tableCount);
                fullTable.put("displayLabel", displayLabel);
                fullTable.put("firstRowPreview", firstRowPreview);
                fullTable.put("rowCount", rowCount);
                fullTable.put("columnCount", columnCount);
                fullTable.put("rows", allRows);
                fullTable.put("headerRow", allRows.isEmpty() ? new ArrayList<String>() : allRows.get(0));

                parsedTables.add(fullTable);
            }
        }

        return new ParseResult(tableCandidates, parsedTables);
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
        private final List<Map<String, Object>> parsedTables;

        private ParseResult(
                List<Map<String, Object>> tableCandidates,
                List<Map<String, Object>> parsedTables) {
            this.tableCandidates = tableCandidates;
            this.parsedTables = parsedTables;
        }
    }

}
