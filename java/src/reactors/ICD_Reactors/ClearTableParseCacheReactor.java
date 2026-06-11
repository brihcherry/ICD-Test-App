package reactors.ICD_Reactors;

import java.util.LinkedHashMap;
import java.util.Map;
import prerna.sablecc2.om.PixelDataType;
import prerna.sablecc2.om.nounmeta.NounMetadata;
import reactors.AbstractProjectReactor;

/*
 * ClearTableParseCacheReactor clears cached ICD table parse data for the current insight.
 * This supports a full UI reset when the user clicks Clear.
 */
public class ClearTableParseCacheReactor extends AbstractProjectReactor {

  private static final String VARSTORE_TABLE_PARSE_CACHE = "ICD_TABLE_PARSE_CACHE";
  private static final String VARSTORE_DICTIONARY_CACHE = "ICD_DICTIONARY_CACHE";

  public ClearTableParseCacheReactor() {
    this.keysToGet = new String[] {};
    this.keyRequired = new int[] {};
  }

  @Override
  protected NounMetadata doExecute() {
    Map<String, Object> emptyTableCache = new LinkedHashMap<>();
    Map<String, Object> emptyDictionaryCache = new LinkedHashMap<>();

    this.insight.getVarStore().put(
      VARSTORE_TABLE_PARSE_CACHE,
      new NounMetadata(emptyTableCache, PixelDataType.MAP)
    );
    this.insight.getVarStore().put(
      VARSTORE_DICTIONARY_CACHE,
      new NounMetadata(emptyDictionaryCache, PixelDataType.MAP)
    );

    Map<String, Object> response = new LinkedHashMap<>();
    response.put("cleared", true);
    response.put("dictionaryCleared", true);
    return new NounMetadata(response, PixelDataType.MAP);
  }

  @Override
  public String getReactorDescription() {
    return "Clear cached ICD table parse data for the current insight.";
  }

  @Override
  public String getDescriptionForKey(String key) {
    return null;
  }
}
