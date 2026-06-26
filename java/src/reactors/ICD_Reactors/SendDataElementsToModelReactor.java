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
    List<Map<String, Object>> selectedTables = filterSelectedTables(allTables, requestedIndexes);
    if (selectedTables.isEmpty()) {
      Map<String, Object> emptyResponse = new LinkedHashMap<>();
      emptyResponse.put("documentName", documentName);
      emptyResponse.put("engine", engine);
      emptyResponse.put("batchSize", DEFAULT_BATCH_SIZE);
      emptyResponse.put("totalRows", 0);
      emptyResponse.put("totalBatches", 0);
      emptyResponse.put("batches", new ArrayList<>());
      return new NounMetadata(emptyResponse, PixelDataType.MAP);
    }

    int totalRows = countRows(selectedTables);
    List<List<Map<String, Object>>> batches = toBatches(selectedTables, DEFAULT_BATCH_SIZE);
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
    response.put("totalRows", totalRows);
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
  private List<Map<String, Object>> filterSelectedTables(
      List<Map<String, Object>> allTables, Set<Integer> requestedIndexes) {
    List<Map<String, Object>> selectedTables = new ArrayList<>();

    for (Map<String, Object> table : allTables) {
      Object indexObj = table.get("index");
      if (!(indexObj instanceof Number)) {
        continue;
      }

      int tableIndex = ((Number) indexObj).intValue();
      if (!requestedIndexes.contains(tableIndex)) {
        continue;
      }

      selectedTables.add(table);
    }

    return selectedTables;
  }

  private int countRows(List<Map<String, Object>> tables) {
    int count = 0;
    for (Map<String, Object> table : tables) {
      Object rowsObj = table.get("rows");
      if (rowsObj instanceof List) {
        count += ((List<?>) rowsObj).size();
      }
    }
    return count;
  }

  private List<List<Map<String, Object>>> toBatches(List<Map<String, Object>> tables, int batchSize) {
    List<List<Map<String, Object>>> batches = new ArrayList<>();
    List<Map<String, Object>> currentBatch = new ArrayList<>();
    int currentRowCount = 0;

    for (Map<String, Object> table : tables) {
      Object rowsObj = table.get("rows");
      int tableRowCount = rowsObj instanceof List ? ((List<?>) rowsObj).size() : 0;

      // If current batch is full and has items, start a new batch
      if (currentRowCount + tableRowCount > batchSize && !currentBatch.isEmpty()) {
        batches.add(new ArrayList<>(currentBatch));
        currentBatch.clear();
        currentRowCount = 0;
      }

      currentBatch.add(table);
      currentRowCount += tableRowCount;
    }

    // Add any remaining tables
    if (!currentBatch.isEmpty()) {
      batches.add(currentBatch);
    }

    return batches;
  }

  @SuppressWarnings("unchecked")
  private String buildBatchPrompt(
      String documentName,
      int batchNumber,
      int totalBatches,
      List<Map<String, Object>> batchTables) {
    StringBuilder sb = new StringBuilder();
    sb.append("You are assisting with ICD data element extraction. ")
      .append("Use the data dictionary context to identify data elements, derive a concise Description from ICD row context, infer Variable Type, Field Length, Delimited status, and Value Range from ICD table content, and assign Data Subject Areas to each. ")
      .append("Return ONLY a markdown table with the exact columns: Data Element, Description, Variable Type, Field Length, Delimited, Value Range, Data Subject Area Primary, Data Subject Area 2, Data Subject Area 3, Confidence. ")
      .append("Do not include any prose, bullets, or code fences before or after the table.\n\n")
      .append("CRITICAL: Extract one row in your output for EACH row in the input tables below. ")
      .append("Process ALL rows, not a subset. The number of output rows must match the number of input rows.\n\n")
      .append("Data Subject Area rules: For every row, return exactly 3 ranked DSA suggestions from the data dictionary context. ")
      .append("Put the best match in Data Subject Area Primary, second-best in Data Subject Area 2, and third-best in Data Subject Area 3. ")
      .append("Do not leave these blank and do not repeat the same DSA across the 3 DSA columns for the same row.\n\n")
      .append("Variable Type rules: Regardless of how the ICD describes the type (e.g. varchar, char, text, string, alphanumeric, A/N \u2192 'Character'; ")
      .append("int, integer, number, numeric, decimal, float, double, NUM \u2192 'Numeric'), ")
      .append("you MUST output ONLY the single word 'Character' or 'Numeric' in the Variable Type column. No other values are allowed.\n\n")
      .append("Document: ").append(documentName).append("\n")
      .append("Batch: ").append(batchNumber).append(" of ").append(totalBatches).append("\n\n");

    sb.append("Tables in this batch:\n\n");
    sb.append("IMPORTANT: In each table below, the first row after the header separator line (---) is DATA, not a header. ")
      .append("Do NOT extract column names as data elements. Only extract from the actual data rows.\n\n");

    for (Map<String, Object> table : batchTables) {
      String sourceLabel = String.valueOf(table.getOrDefault("sourceTableLabel", "Unknown"));
      Object headerObj = table.get("headerRow");
      Object rowsObj = table.get("rows");

      if (rowsObj instanceof List) {
        List<List<String>> rows = (List<List<String>>) rowsObj;
        sb.append("Table: ").append(sourceLabel).append(" (").append(rows.size()).append(" data rows)\n");
      } else {
        sb.append("Table: ").append(sourceLabel).append("\n");
      }

      if (headerObj instanceof List) {
        List<String> headers = (List<String>) headerObj;
        sb.append("| ");
        for (String header : headers) {
          sb.append(header).append(" | ");
        }
        sb.append("\n");
        sb.append("|");
        for (int i = 0; i < headers.size(); i++) {
          sb.append(" --- |");
        }
        sb.append("\n");
      }

      if (rowsObj instanceof List) {
        List<List<String>> rows = (List<List<String>>) rowsObj;
        for (List<String> row : rows) {
          sb.append("| ");
          for (String cell : row) {
            sb.append(cell == null ? "" : cell).append(" | ");
          }
          sb.append("\n");
        }
      }

      sb.append("\n");
    }

    sb.append("Identify which column(s) contain the data elements and which values indicate variable/data type, field length, delimiter usage, and value range information. ")
      .append("For Description, provide a concise plain-language definition for each data element using ICD row context; if an explicit definition/description text exists in the row, use that wording. ")
      .append("Return Field Length, Delimited, and Value Range exactly as represented or implied by the ICD table row when available. ")
      .append("For Variable Type, normalize to exactly 'Character' or 'Numeric' as instructed above. ")
      .append("For Data Subject Area, return 3 ranked suggestions in the three DSA columns for every row. ")
      .append("For each data row in the tables above (rows below the --- separator), create one corresponding output row in the markdown table.");
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
