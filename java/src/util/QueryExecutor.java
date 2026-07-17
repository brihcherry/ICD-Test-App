package util;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import prerna.engine.api.IDatabaseEngine;
import prerna.engine.api.ISelectStatement;
import prerna.engine.api.ISelectWrapper;
import prerna.masterdatabase.utility.MasterDatabaseUtility;
import prerna.rdf.engine.wrappers.WrapperManager;
import prerna.util.Utility;

/**
 * Utility for executing SELECT queries through SEMOSS wrappers.
 */
public class QueryExecutor {

  private static final Logger LOGGER = LogManager.getLogger(QueryExecutor.class);

  private final String engineId;
  private final IDatabaseEngine engine;

  public QueryExecutor(String engineId) {
    if (engineId == null || engineId.trim().isEmpty()) {
      throw new IllegalArgumentException("Engine ID cannot be null or empty");
    }

    this.engineId = engineId;
    String resolvedId = MasterDatabaseUtility.testDatabaseIdIfAlias(engineId);
    this.engine = Utility.getDatabase(resolvedId);

    if (this.engine == null) {
      throw new IllegalArgumentException("Cannot resolve engine with ID: " + engineId);
    }

    LOGGER.debug("QueryExecutor initialized with engine: {}", resolvedId);
  }

  public List<Map<String, String>> executeSelect(String query) {
    if (query == null || query.trim().isEmpty()) {
      throw new IllegalArgumentException("Query cannot be null or empty");
    }

    List<Map<String, String>> results = new ArrayList<>();
    ISelectWrapper wrapper = null;

    try {
      wrapper = WrapperManager.getInstance().getSWrapper(engine, query);
      if (wrapper == null) {
        throw new RuntimeException("Failed to obtain query wrapper from WrapperManager");
      }

      String[] variableNames = wrapper.getVariables();
      if (variableNames == null || variableNames.length == 0) {
        return results;
      }

      while (wrapper.hasNext()) {
        ISelectStatement statement = wrapper.next();
        Map<String, String> row = new TreeMap<>();

        for (String variableName : variableNames) {
          Object rawValue = statement.getRawVar(variableName);
          Object displayValue = statement.getVar(variableName);
          String value = chooseValue(rawValue, displayValue);
          if (value != null) {
            row.put(variableName, value);
          }
        }

        if (!row.isEmpty()) {
          results.add(row);
        }
      }

      return results;
    } catch (Exception e) {
      LOGGER.error("SPARQL query execution failed. Engine: " + engineId, e);
      throw new RuntimeException("Query execution error: " + e.getMessage(), e);
    } finally {
      if (wrapper != null) {
        try {
          wrapper.close();
        } catch (Exception closeEx) {
          LOGGER.warn("Failed to close select query wrapper cleanly", closeEx);
        }
      }
    }
  }

  private String chooseValue(Object rawValue, Object displayValue) {
    if (rawValue != null) {
      String rawText = rawValue.toString();
      if (looksLikeUri(rawText)) {
        return rawText;
      }
    }

    if (displayValue != null) {
      return displayValue.toString();
    }

    return rawValue != null ? rawValue.toString() : null;
  }

  private boolean looksLikeUri(String value) {
    return value.startsWith("http://")
      || value.startsWith("https://")
      || value.startsWith("urn:")
      || value.startsWith("file:");
  }
}
