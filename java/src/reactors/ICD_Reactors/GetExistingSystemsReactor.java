package reactors.ICD_Reactors;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import prerna.sablecc2.om.PixelDataType;
import prerna.sablecc2.om.nounmeta.NounMetadata;
import reactors.AbstractProjectReactor;
import util.QueryExecutor;

/**
 * Returns all existing System concepts from the configured RDS engine.
 */
public class GetExistingSystemsReactor extends AbstractProjectReactor {

  private static final String CONCEPT_SYSTEM = "http://semoss.org/ontologies/Concept/System";

  public GetExistingSystemsReactor() {
    this.keysToGet = new String[] {};
    this.keyRequired = new int[] {};
  }

  @Override
  protected NounMetadata doExecute() {
    String engineId = projectProperties.getEngineId();
    if (engineId == null || engineId.trim().isEmpty()) {
      return NounMetadata.getErrorNounMessage("engineId is not configured in java/project.properties.");
    }

    String query =
      "SELECT DISTINCT ?System WHERE {"
      + " ?System <http://www.w3.org/1999/02/22-rdf-syntax-ns#type>"
      + "   <" + CONCEPT_SYSTEM + "> ."
      + "}";

    QueryExecutor executor = new QueryExecutor(engineId);
    List<Map<String, String>> rows = executor.executeSelect(query);

    List<Map<String, Object>> systems = new ArrayList<>();
    List<String> systemNames = new ArrayList<>();

    for (Map<String, String> row : rows) {
      String uri = row.get("System");
      if (uri == null || uri.trim().isEmpty()) {
        continue;
      }

      String label = localName(uri).replace('_', ' ');
      systemNames.add(label);

      Map<String, Object> item = new LinkedHashMap<>();
      item.put("uri", uri);
      item.put("label", label);
      systems.add(item);
    }

    systems.sort((a, b) -> String.valueOf(a.get("label")).compareToIgnoreCase(String.valueOf(b.get("label"))));
    systemNames.sort(String::compareToIgnoreCase);

    Map<String, Object> response = new LinkedHashMap<>();
    response.put("engineId", engineId);
    response.put("count", systems.size());
    response.put("systems", systems);
    response.put("systemNames", systemNames);

    return new NounMetadata(response, PixelDataType.MAP);
  }

  private String localName(String uri) {
    if (uri == null) {
      return "";
    }
    int slash = uri.lastIndexOf('/');
    int hash = uri.lastIndexOf('#');
    int index = Math.max(slash, hash);
    return index >= 0 ? uri.substring(index + 1) : uri;
  }

  @Override
  public String getReactorDescription() {
    return "Returns existing systems from the configured database for provider/consumer dropdowns.";
  }
}
