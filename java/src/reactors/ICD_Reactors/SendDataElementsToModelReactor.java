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
 * SendDataElementsToModelReactor reads extracted data elements from cached parse data,
 * chunks them into batches, and returns prompt/LLM command payloads for frontend execution.
 * This reactor reads the latest cached data dictionary (if present) and includes
 * it directly in each LLM batch call so execution is stateless across requests.
 *
 * Inputs:
 * - tableIndexes: selected table indexes as an integer array (example: [1,3,5])
 * - engine (optional): model engine id
 *
 * Output:
 * - summary metadata and per-batch prompt/llmCommand payloads
 */
public class SendDataElementsToModelReactor extends AbstractProjectReactor {

  private static final String TABLE_INDEXES_KEY = "tableIndexes";
  private static final String ENGINE_KEY = "engine";

  private static final String VARSTORE_TABLE_PARSE_CACHE = "ICD_TABLE_PARSE_CACHE";
  private static final String VARSTORE_DICTIONARY_CACHE = "ICD_DICTIONARY_CACHE";
  private static final String FIELD_NAME_HEADER = "field name";
  private static final String FUNCTIONAL_DESC_HEADER = "functional description";

  private static final String DEFAULT_ENGINE_ID = "aa876e7e-e78e-404d-b7db-1a44236bc2a5";
  private static final int DEFAULT_BATCH_SIZE = 10;
  private static final int MAX_COMPLETION_TOKENS = 2000;
  private static final double TEMPERATURE = 0.3;

  public SendDataElementsToModelReactor() {
    this.keysToGet =
      new String[] {TABLE_INDEXES_KEY, ENGINE_KEY};
    this.keyRequired = new int[] {1, 0};
  }

  @Override
  @SuppressWarnings("unchecked")
  protected NounMetadata doExecute() {
    String tableIndexesRaw = this.keyValue.get(TABLE_INDEXES_KEY);
    String engineRaw = this.keyValue.get(ENGINE_KEY);

    if (tableIndexesRaw == null || tableIndexesRaw.isBlank()) {
      return NounMetadata.getErrorNounMessage("tableIndexes is required.");
    }

    String engine =
      (engineRaw == null || engineRaw.isBlank()) ? DEFAULT_ENGINE_ID : engineRaw;

    String dictionaryText = getCachedDictionaryText();
    if (dictionaryText == null || dictionaryText.isBlank()) {
      return NounMetadata.getErrorNounMessage(
        "No cached data dictionary found. Please upload and load a data dictionary first.");
    }

    Set<Integer> requestedIndexes;
    try {
      requestedIndexes = parseRequestedIndexes(tableIndexesRaw);
    } catch (IllegalArgumentException e) {
      return NounMetadata.getErrorNounMessage(e.getMessage());
    }

    NounMetadata cacheNoun = this.insight.getVarStore().get(VARSTORE_TABLE_PARSE_CACHE);
    if (cacheNoun == null || !(cacheNoun.getValue() instanceof Map)) {
      return NounMetadata.getErrorNounMessage(
        "No cached parse data found. Please run GetTables first.");
    }

    Map<String, Object> cacheEntry = (Map<String, Object>) cacheNoun.getValue();
    String documentName = (String) cacheEntry.getOrDefault("documentName", "");
    Object tablesObj = cacheEntry.get("tables");
    if (!(tablesObj instanceof List)) {
      return NounMetadata.getErrorNounMessage(
        "No cached parse data found. Please run GetTables first.");
    }

    List<Map<String, Object>> allTables = (List<Map<String, Object>>) tablesObj;
    List<Map<String, Object>> extractedRows = extractRows(allTables, requestedIndexes);
    if (extractedRows.isEmpty()) {
      Map<String, Object> emptyResponse = new LinkedHashMap<>();
      emptyResponse.put("documentName", documentName);
      emptyResponse.put("engine", engine);
      emptyResponse.put("batchSize", DEFAULT_BATCH_SIZE);
      emptyResponse.put("totalRows", 0);
      emptyResponse.put("totalBatches", 0);
      emptyResponse.put("batches", new ArrayList<>());
      return new NounMetadata(emptyResponse, PixelDataType.MAP);
    }

    List<List<Map<String, Object>>> batches = toBatches(extractedRows, DEFAULT_BATCH_SIZE);
    List<Map<String, Object>> batchResults = new ArrayList<>();

    for (int i = 0; i < batches.size(); i++) {
      int batchNumber = i + 1;
      List<Map<String, Object>> batch = batches.get(i);
      String prompt = buildBatchPrompt(documentName, batchNumber, batches.size(), batch);
      String pixel = buildLlmPixel(engine, prompt, dictionaryText);

      Map<String, Object> batchResult = new LinkedHashMap<>();
      batchResult.put("batchNumber", batchNumber);
      batchResult.put("batchSize", batch.size());
      batchResult.put("prompt", prompt);
      batchResult.put("llmCommand", pixel);
      batchResults.add(batchResult);
    }

    Map<String, Object> response = new LinkedHashMap<>();
    response.put("engine", engine);
    response.put("batchSize", DEFAULT_BATCH_SIZE);
    response.put("totalRows", extractedRows.size());
    response.put("totalBatches", batches.size());
    response.put("batches", batchResults);

    return new NounMetadata(response, PixelDataType.MAP);
  }

  @SuppressWarnings("unchecked")
  private String getCachedDictionaryText() {
    NounMetadata dictionaryNoun = this.insight.getVarStore().get(VARSTORE_DICTIONARY_CACHE);
    if (dictionaryNoun == null || !(dictionaryNoun.getValue() instanceof Map)) {
      return null;
    }

    Map<String, Object> dictionaryCache = (Map<String, Object>) dictionaryNoun.getValue();
    Object dictionaryTextObj = dictionaryCache.get("dictionaryText");
    return dictionaryTextObj instanceof String ? (String) dictionaryTextObj : null;
  }

  private Set<Integer> parseRequestedIndexes(String tableIndexesRaw) {
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
    if (requestedIndexes.isEmpty()) {
      throw new IllegalArgumentException("tableIndexes must contain at least one index.");
    }
    return requestedIndexes;
  }

  @SuppressWarnings("unchecked")
  private List<Map<String, Object>> extractRows(
      List<Map<String, Object>> allTables, Set<Integer> requestedIndexes) {
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

      List<String> headerRow = rows.get(0);
      int fieldNameCol = findColumnIndex(headerRow, FIELD_NAME_HEADER);
      int functionalDescCol = findColumnIndex(headerRow, FUNCTIONAL_DESC_HEADER);
      if (fieldNameCol < 0 && functionalDescCol < 0) {
        continue;
      }

      for (int i = 1; i < rows.size(); i++) {
        List<String> row = rows.get(i);
        String fieldName = getCell(row, fieldNameCol);
        String functionalDesc = getCell(row, functionalDescCol);
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

    return extractedRows;
  }

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

  private List<List<Map<String, Object>>> toBatches(List<Map<String, Object>> rows, int batchSize) {
    List<List<Map<String, Object>>> batches = new ArrayList<>();
    for (int start = 0; start < rows.size(); start += batchSize) {
      int end = Math.min(start + batchSize, rows.size());
      batches.add(new ArrayList<>(rows.subList(start, end)));
    }
    return batches;
  }

  private String buildBatchPrompt(
      String documentName,
      int batchNumber,
      int totalBatches,
      List<Map<String, Object>> batchRows) {
    StringBuilder sb = new StringBuilder();
    sb.append("You are assisting with ICD data element processing. ")
      .append("Use the data dictionary context to assign a Data Subject Area to each data element. ")
      .append("Return ONLY a markdown table with the exact columns: Data Element, Data Subject Area, Confidence. ")
      .append("Do not include any prose, bullets, or code fences before or after the table.\n\n")
      .append("Document: ").append(documentName).append("\n")
      .append("Batch: ").append(batchNumber).append(" of ").append(totalBatches).append("\n\n")
      .append("Data elements:\n");

    for (int i = 0; i < batchRows.size(); i++) {
      Map<String, Object> row = batchRows.get(i);
      String fieldName = String.valueOf(row.getOrDefault("fieldName", ""));
      String functionalDescription = String.valueOf(row.getOrDefault("functionalDescription", ""));
      String sourceTableLabel = String.valueOf(row.getOrDefault("sourceTableLabel", ""));

      sb.append(i + 1)
        .append(") Field Name: ").append(fieldName)
        .append(" | Functional Description: ").append(functionalDescription)
        .append(" | Source Table: ").append(sourceTableLabel)
        .append("\n");
    }

    sb.append("\nReturn your sorted output as plain text for now.");
    return sb.toString();
  }

  private String buildLlmPixel(String engine, String prompt, String dictionaryText) {
    String systemContent = escapePixelString(
      "You are assisting with ICD data element processing. "
      + "Use the provided data dictionary context to assign a Data Subject Area "
      + "to each data element in the batch input.");

    String userContent = escapePixelString(
      "DATA DICTIONARY:\n\n"
      + dictionaryText
      + "\n\n"
      + prompt);

    return "LLM(engine = \""
      + escapePixelString(engine)
      + "\", command = \"ignore\", paramValues=[{"
      + "'full_prompt':["
      + "{'role':'system','content':\"" + systemContent + "\"},"
      + "{'role':'user','content':\"" + userContent + "\"}"
      + "],"
      + "'max_completion_tokens':"
      + MAX_COMPLETION_TOKENS
      + ","
      + "'temperature':"
      + TEMPERATURE
      + "}]);";
  }

  private String escapePixelString(String value) {
    if (value == null) {
      return "";
    }
    return value
      .replace("\\", "\\\\")
      .replace("\"", "\\\"")
      .replace("\r", "")
      .replace("\n", "\\n");
  }

  @Override
  public String getReactorDescription() {
    return "Send extracted data elements to LLM in batches and return raw model outputs per batch.";
  }

  @Override
  public String getDescriptionForKey(String key) {
    if (TABLE_INDEXES_KEY.equals(key)) {
      return "Selected table indexes as an integer array (example: [1,2,5]).";
    }
    if (ENGINE_KEY.equals(key)) {
      return "Optional model engine id. If omitted, default engine is used.";
    }
    return null;
  }
}
