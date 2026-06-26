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
 * (written by GetTablesReactor) and extracts full table data from the user-selected tables,
 * including all columns and rows. The model will then intelligently identify which columns
 * contain data elements.
 *
 * Inputs:
 * - tableIndexes: array of 1-based table index integers (example: [1,2,5])
 *
 * Response payload:
 * - documentName: original document name from the cache
 * - totalRowCount: total number of extracted data rows across all selected tables
 * - tables[]: list of extracted tables, each containing:
 *     - sourceTableLabel: display label of the source table
 *     - headerRow: array of column names
 *     - rows: array of data rows (each row is an array of strings)
 */
public class ExtractDataElementsReactor extends AbstractProjectReactor {

    private static final String TABLE_INDEXES_KEY = "tableIndexes";
    private static final String VARSTORE_TABLE_PARSE_CACHE = "ICD_TABLE_PARSE_CACHE";

    public ExtractDataElementsReactor() {
        this.keysToGet = new String[] {TABLE_INDEXES_KEY};
        this.keyRequired = new int[] {1};
    }

    @Override
    @SuppressWarnings("unchecked")
    protected NounMetadata doExecute() {
        String tableIndexesRaw = this.keyValue.get(TABLE_INDEXES_KEY);

        if (tableIndexesRaw == null || tableIndexesRaw.isBlank()) {
            return NounMetadata.getErrorNounMessage("tableIndexes is required.");
        }

        Set<Integer> requestedIndexes;
        try {
            requestedIndexes = parseRequestedIndexesArray(tableIndexesRaw);
        } catch (IllegalArgumentException e) {
            return NounMetadata.getErrorNounMessage(e.getMessage());
        }

        // look up the var-store cache
        NounMetadata cacheNoun = this.insight.getVarStore().get(VARSTORE_TABLE_PARSE_CACHE);
        if (cacheNoun == null || !(cacheNoun.getValue() instanceof Map)) {
            return NounMetadata.getErrorNounMessage(
                "No cached parse data found. Please run GetTables first.");
        }

        Map<String, Object> cacheEntry = (Map<String, Object>) cacheNoun.getValue();
        Object tablesObj = cacheEntry.get("tables");
        if (!(tablesObj instanceof List)) {
            return NounMetadata.getErrorNounMessage(
                "No cached parse data found. Please run GetTables first.");
        }

        String documentName = (String) cacheEntry.getOrDefault("documentName", "");

        List<Map<String, Object>> allTables = (List<Map<String, Object>>) tablesObj;
        List<Map<String, Object>> extractedTables = new ArrayList<>();
        int totalRowCount = 0;

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

            // Extract header and all data rows (header is at index 0)
            List<String> headerRow = rows.get(0);
            List<List<String>> dataRows = new ArrayList<>();
            for (int i = 1; i < rows.size(); i++) {
                dataRows.add(rows.get(i));
            }

            // Skip tables with no data rows
            if (dataRows.isEmpty()) {
                continue;
            }

            Map<String, Object> extractedTable = new LinkedHashMap<>();
            extractedTable.put("sourceTableLabel", displayLabel);
            extractedTable.put("headerRow", headerRow);
            extractedTable.put("rows", dataRows);
            extractedTables.add(extractedTable);
            totalRowCount += dataRows.size();
        }

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("documentName", documentName);
        response.put("totalRowCount", totalRowCount);
        response.put("tables", extractedTables);

        return new NounMetadata(response, PixelDataType.MAP);
    }



    private Set<Integer> parseRequestedIndexesArray(String tableIndexesRaw) {
        String trimmed = tableIndexesRaw.trim();

        // Pixel keyValue can flatten array syntax into CSV in some paths; support both.
        if ((trimmed.startsWith("\"") && trimmed.endsWith("\""))
            || (trimmed.startsWith("'") && trimmed.endsWith("'"))) {
            trimmed = trimmed.substring(1, trimmed.length() - 1).trim();
        }

        String body;
        if (trimmed.startsWith("[") && trimmed.endsWith("]")) {
            body = trimmed.substring(1, trimmed.length() - 1).trim();
        } else {
            body = trimmed;
        }

        if (body.isEmpty()) {
            throw new IllegalArgumentException("tableIndexes must contain at least one index.");
        }

        Set<Integer> requestedIndexes = new HashSet<>();
        for (String part : body.split(",")) {
            String item = part.trim();
            if (item.isEmpty()) {
                throw new IllegalArgumentException(
                    "tableIndexes must contain only integer indexes (example: [1,2,5]).");
            }
            try {
                requestedIndexes.add(Integer.parseInt(item));
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException(
                    "tableIndexes must contain only integer indexes (example: [1,2,5]).");
            }
        }

        return requestedIndexes;
    }

    @Override
    public String getReactorDescription() {
        return "Extract all columns from user-selected cached tables for model processing.";
    }

    @Override
    public String getDescriptionForKey(String key) {
        if (TABLE_INDEXES_KEY.equals(key)) {
            return "Array of table index integers selected by the user (example: [1,2,5]).";
        }
        return null;
    }
}
