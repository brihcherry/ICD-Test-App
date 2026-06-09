package reactors.ICD_Reactors;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import prerna.sablecc2.om.PixelDataType;
import prerna.sablecc2.om.nounmeta.NounMetadata;
import reactors.AbstractProjectReactor;

/*
 * ExtractDataElementsReactor reads cached table data from the SEMOSS insight var-store
 * (written by GetTablesReactor) and extracts a simplified two-column table containing
 * only "Field Name" and "Functional Description" columns from the user-selected tables.
 *
 * Inputs:
 * - parseId: the opaque cache key returned by GetTablesReactor
 * - tableIndexes: comma-separated list of 1-based table index integers selected by the user
 *
 * Column matching:
 * - "Field Name" and "Functional Description" are matched case-insensitively against
 *   each table's header row.
 * - Rows where both extracted values are empty are skipped.
 *
 * Response payload:
 * - documentName: original document name from the cache
 * - rowCount: total number of extracted data rows across all selected tables
 * - rows[]: flat merged list from all selected tables, each entry contains:
 *     - fieldName
 *     - functionalDescription
 *     - sourceTableLabel: display label of the source table
 */
public class ExtractDataElementsReactor extends AbstractProjectReactor {

    private static final String PARSE_ID_KEY = "parseId";
    private static final String TABLE_INDEXES_KEY = "tableIndexes";
    private static final String VARSTORE_TABLE_PARSE_CACHE = "ICD_TABLE_PARSE_CACHE";
    private static final String FIELD_NAME_HEADER = "field name";
    private static final String FUNCTIONAL_DESC_HEADER = "functional description";

    public ExtractDataElementsReactor() {
        this.keysToGet = new String[] {PARSE_ID_KEY, TABLE_INDEXES_KEY};
        this.keyRequired = new int[] {1, 1};
    }

    @Override
    @SuppressWarnings("unchecked")
    protected NounMetadata doExecute() {
        String parseId = this.keyValue.get(PARSE_ID_KEY);
        String tableIndexesRaw = this.keyValue.get(TABLE_INDEXES_KEY);

        if (parseId == null || parseId.isBlank()) {
            return NounMetadata.getErrorNounMessage("parseId is required.");
        }
        if (tableIndexesRaw == null || tableIndexesRaw.isBlank()) {
            return NounMetadata.getErrorNounMessage("tableIndexes is required.");
        }

        // parse the comma-separated table index list
        Set<Integer> requestedIndexes = new HashSet<>();
        for (String part : tableIndexesRaw.split(",")) {
            String trimmed = part.trim();
            if (trimmed.isEmpty()) {
                continue;
            }
            try {
                requestedIndexes.add(Integer.parseInt(trimmed));
            } catch (NumberFormatException e) {
                return NounMetadata.getErrorNounMessage(
                    "Invalid value in tableIndexes: \"" + trimmed + "\". Expected integers.");
            }
        }

        if (requestedIndexes.isEmpty()) {
            return NounMetadata.getErrorNounMessage("tableIndexes must contain at least one valid index.");
        }

        // look up the var-store cache
        NounMetadata cacheNoun = this.insight.getVarStore().get(VARSTORE_TABLE_PARSE_CACHE);
        if (cacheNoun == null || !(cacheNoun.getValue() instanceof Map)) {
            return NounMetadata.getErrorNounMessage(
                "No cached parse data found. Please run GetTables first.");
        }

        Map<String, Object> cacheByParseId = (Map<String, Object>) cacheNoun.getValue();
        Object entryObj = cacheByParseId.get(parseId);
        if (!(entryObj instanceof Map)) {
            return NounMetadata.getErrorNounMessage(
                "Parse session not found for parseId: " + parseId + ". Please re-run GetTables.");
        }

        Map<String, Object> cacheEntry = (Map<String, Object>) entryObj;
        String documentName = (String) cacheEntry.getOrDefault("documentName", "");
        Object tablesObj = cacheEntry.get("tables");
        if (!(tablesObj instanceof List)) {
            return NounMetadata.getErrorNounMessage("Cached entry is missing table data.");
        }

        List<Map<String, Object>> allTables = (List<Map<String, Object>>) tablesObj;
        List<Map<String, Object>> extractedRows = new ArrayList<>();

        for (Map<String, Object> table : allTables) {
            Object indexObj = table.get("index");
            if (!(indexObj instanceof Number)) {
                continue;
            }

            int tableIndex = ((Number) indexObj).intValue();
            if (!requestedIndexes.contains(tableIndex)) {
                continue;
            }

            String displayLabel = (String) table.getOrDefault("displayLabel", "Table " + tableIndex);
            Object rowsObj = table.get("rows");
            if (!(rowsObj instanceof List)) {
                continue;
            }

            List<List<String>> rows = (List<List<String>>) rowsObj;
            if (rows.isEmpty()) {
                continue;
            }

            // find the column positions of the two target headers in the header row
            List<String> headerRow = rows.get(0);
            int fieldNameCol = findColumnIndex(headerRow, FIELD_NAME_HEADER);
            int functionalDescCol = findColumnIndex(headerRow, FUNCTIONAL_DESC_HEADER);

            // skip tables that don't have at least one of the target columns
            if (fieldNameCol < 0 && functionalDescCol < 0) {
                continue;
            }

            // extract data rows (skip header at index 0)
            for (int i = 1; i < rows.size(); i++) {
                List<String> row = rows.get(i);
                String fieldName = getCell(row, fieldNameCol);
                String functionalDesc = getCell(row, functionalDescCol);

                // skip rows where both values are empty
                if (fieldName.isEmpty() && functionalDesc.isEmpty()) {
                    continue;
                }

                Map<String, Object> extracted = new LinkedHashMap<>();
                extracted.put("fieldName", fieldName);
                extracted.put("functionalDescription", functionalDesc);
                extracted.put("sourceTableLabel", displayLabel);
                extractedRows.add(extracted);
            }
        }

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("documentName", documentName);
        response.put("rowCount", extractedRows.size());
        response.put("rows", extractedRows);

        return new NounMetadata(response, PixelDataType.MAP);
    }

    /*
     * Finds the index of the first header cell that starts with the target string
     * (case-insensitive, trimmed). Returns -1 if not found.
     */
    private int findColumnIndex(List<String> headerRow, String targetHeader) {
        String lowerTarget = targetHeader.toLowerCase();
        for (int i = 0; i < headerRow.size(); i++) {
            String cell = headerRow.get(i);
            if (cell != null && cell.trim().toLowerCase().startsWith(lowerTarget)) {
                return i;
            }
        }
        return -1;
    }

    private String getCell(List<String> row, int colIndex) {
        if (colIndex < 0 || colIndex >= row.size()) {
            return "";
        }
        String value = row.get(colIndex);
        return value == null ? "" : value.trim();
    }

    @Override
    public String getReactorDescription() {
        return "Extract Field Name and Functional Description columns from user-selected cached tables.";
    }

    @Override
    public String getDescriptionForKey(String key) {
        if (PARSE_ID_KEY.equals(key)) {
            return "The parseId returned by GetTables, used to look up cached table data.";
        }
        if (TABLE_INDEXES_KEY.equals(key)) {
            return "Comma-separated list of table index integers selected by the user.";
        }
        return null;
    }
}
