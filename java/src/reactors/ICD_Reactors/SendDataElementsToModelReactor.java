package reactors.ICD_Reactors;

import prerna.sablecc2.om.PixelDataType;
import prerna.sablecc2.om.nounmeta.NounMetadata;
import reactors.AbstractProjectReactor;

// SendDataElementsToModelReactor: processes ICD documents; parses and extracts Data Elements and returns them in a table.
//
// Called from the frontend as:  SendDataElementsToModelReactor(icdDocument=["document"])
// Note: SEMOSS strips the "Reactor" suffix, so SendDataElementsToModelReactor becomes SendDataElementsToModel().
public class SendDataElementsToModelReactor extends AbstractProjectReactor {

  private static final String CITY_KEY = "city";

  public SendDataElementsToModelReactor() {
    this.keysToGet = new String[] {CITY_KEY};
    this.keyRequired = new int[] {1};
  }

  public String giveModelDataDictionary() {
    /*
     * TODO: create a prompt that will send the data dictionary to the model for it to ingest
     * This is a step that must be completed before any any of the other reactors can send
     * data elements to be sorted by the model
     * 
     *  */
    return "Process ICD documents; parse and extract Data Elements and return them in a table.";
  }

  @Override
  protected NounMetadata doExecute() {
    String city = this.keyValue.get(CITY_KEY);

    String response = "It will be sunny in " + city + " today.";

    return new NounMetadata(response, PixelDataType.CONST_STRING);
  }

  @Override
  public String getReactorDescription() {
    return "Process ICD documents; parse and extract Data Elements and return them in a table.";
  }

  @Override
  public String getDescriptionForKey(String key) {
    if (CITY_KEY.equals(key)) {
      return "A table containing the data elements from the ICD.";
    }
    return null;
  }
}
