package reactors.ICD_Reactors;

import java.util.LinkedHashMap;
import java.util.Map;
import prerna.sablecc2.om.PixelDataType;
import prerna.sablecc2.om.nounmeta.NounMetadata;
import reactors.AbstractProjectReactor;

/*
 * SendDictionaryToModelReactor reads the data dictionary text cached by
 * GetDataDictionaryReactor and returns a Pixel LLM command that sends it
 * to the model as a priming message. The model is instructed to read the
 * dictionary and reply with a simple confirmation before data elements are sent.
 *
 * Inputs:
 * - engine (optional): model engine id
 *
 * Output:
 * - engine: the engine id used
 * - llmCommand: the ready-to-execute Pixel LLM(...) command
 */
public class SendDictionaryToModelReactor extends AbstractProjectReactor {

    private static final String DICTIONARY_TEXT_KEY = "dictionaryText";
    private static final String ENGINE_KEY = "engine";

    private static final String DEFAULT_ENGINE_ID = "aa876e7e-e78e-404d-b7db-1a44236bc2a5";
    private static final int MAX_COMPLETION_TOKENS = 500;
    private static final double TEMPERATURE = 0.1;

    public SendDictionaryToModelReactor() {
        this.keysToGet = new String[] {DICTIONARY_TEXT_KEY, ENGINE_KEY};
        this.keyRequired = new int[] {1, 0};
    }

    @Override
    protected NounMetadata doExecute() {
        String dictionaryTextRaw = this.keyValue.get(DICTIONARY_TEXT_KEY);
        String engineRaw = this.keyValue.get(ENGINE_KEY);

        String engine = (engineRaw == null || engineRaw.isBlank()) ? DEFAULT_ENGINE_ID : engineRaw;

        String dictionaryText = dictionaryTextRaw == null ? "" : dictionaryTextRaw.trim();
        if (dictionaryText.isBlank()) {
            return NounMetadata.getErrorNounMessage(
                "dictionaryText is required and cannot be empty.");
        }

        String llmCommand = buildPrimingPixel(engine, dictionaryText);

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("engine", engine);
        response.put("llmCommand", llmCommand);

        return new NounMetadata(response, PixelDataType.MAP);
    }

    private String buildPrimingPixel(String engine, String dictionaryText) {
        String systemContent = escapePixelString(
            "You are helping to sort data elements from an ICD (Interface Control Document) "
            + "into the data subject areas defined in the data dictionary below. "
            + "Read the data dictionary carefully and understand what each data subject area represents. "
            + "When you are ready to receive the data elements, simply reply with: ready");

        String userContent = escapePixelString(
            "DATA DICTIONARY:\n\n" + dictionaryText);

        return "LLM(engine = \""
            + escapePixelString(engine)
            + "\", command = \"ignore\", paramValues=[{"
            + "'full_prompt':["
            + "{'role':'system','content':\"" + systemContent + "\"},"
            + "{'role':'user','content':\"" + userContent + "\"}"
            + "],"
            + "'max_completion_tokens':" + MAX_COMPLETION_TOKENS + ","
            + "'temperature':" + TEMPERATURE
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
        return "Send the provided data dictionary to the model as a one-time priming context message.";
    }

    @Override
    public String getDescriptionForKey(String key) {
        if (DICTIONARY_TEXT_KEY.equals(key)) {
            return "Required full data dictionary text extracted from GetDataDictionary.";
        }
        if (ENGINE_KEY.equals(key)) {
            return "Optional model engine id. Defaults to the configured engine.";
        }
        return null;
    }
}

